(ns generate-docs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [codox.main :as codox]))

(defn- adr-page-names
  [root]
  (->> (file-seq (io/file root "doc/adr"))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(str/ends-with? % ".md"))
       (map #(str/replace % #"\.md$" ""))
       sort))

(defn- rewrite-flattened-adr-links!
  [root output-dir]
  (let [page-names (adr-page-names root)]
    (doseq [page-name page-names]
      (when-not (.isFile (io/file output-dir (str page-name ".html")))
        (throw (ex-info "Codox did not generate the expected ADR page"
                        {:page page-name :output-dir (str output-dir)}))))
    (doseq [html-file (->> (file-seq output-dir)
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) ".html")))]
      (let [before (slurp html-file)
            after (reduce (fn [html page-name]
                            (str/replace html
                                         (str "href=\"adr/" page-name ".html\"")
                                         (str "href=\"" page-name ".html\"")))
                          before
                          page-names)]
        (when-not (= before after)
          (spit html-file after))))))

(defn generate-docs
  "Generate Codox output and repair links to nested Markdown documents that
  Codox flattens into the output root."
  [options]
  (codox/generate-docs options)
  (let [root (io/file (or (:root-path options)
                          (System/getProperty "user.dir")))
        output-dir (io/file root (or (:output-path options) "target/doc"))]
    (rewrite-flattened-adr-links! root output-dir)))
