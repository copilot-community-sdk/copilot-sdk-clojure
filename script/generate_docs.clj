(ns generate-docs
  (:require [clojure.java.io :as io]
            [codox.main :as codox]
            [codox.reader.plaintext :as plaintext]
            [codox.writer.html :as html]
            [docs-links :as links]))

(defn write-docs
  "Delegate to Codox's HTML writer with explicit topic output identities."
  [{:keys [topic-manifest generation-state] :as project}]
  (let [reserved (into #{"index.html"}
                       (map #(str (:name %) ".html"))
                       (:namespaces project))
        topic-manifest (links/reserve-output-identities topic-manifest reserved)]
    (reset! generation-state {:manifest topic-manifest
                              :reserved-output-files reserved})
    (html/write-docs
     (assoc project
            :documents
            (->> topic-manifest
                 (sort-by (juxt :basename :source-path))
                 (mapv (fn [{:keys [source-file output-name] :as entry}]
                         (-> (plaintext/read-file source-file)
                             (assoc :name output-name)
                             (update :content #(links/rewrite-markdown-links
                                                topic-manifest
                                                entry
                                                %))))))))))

(defn generate-docs
  "Generate Codox output with deterministic topic identities and valid links."
  [options]
  (let [root (io/file (or (:root-path options)
                          (System/getProperty "user.dir")))
        output-path (or (:output-path options) "target/doc")
        output-dir (if (.isAbsolute (io/file output-path))
                     (io/file output-path)
                     (io/file root output-path))
        manifest (links/build-topic-manifest root options)
        generation-state (atom {:manifest manifest
                                :reserved-output-files #{"index.html"}})]
    (codox/generate-docs
     (assoc options
            :writer 'generate-docs/write-docs
            :topic-manifest manifest
            :generation-state generation-state))
    (let [{:keys [manifest reserved-output-files]} @generation-state]
      (links/assert-valid-output-links! manifest reserved-output-files output-dir)
      (links/write-topic-manifest! manifest reserved-output-files output-dir))))
