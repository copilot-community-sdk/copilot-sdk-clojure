(ns github.copilot-sdk.memory-fs-fixture
  "A private, text-only POSIX store for the offline example and native test.
  File contents are strings. A directory has a nil value. No host paths are accessed."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]))

(defn- parent [path]
  (when-not (= "/" path)
    (let [i (str/last-index-of path "/")]
      (if (pos? (or i 0)) (subs path 0 i) "/"))))

(defn- below? [root path]
  (or (= root path) (str/starts-with? path (if (= "/" root) "/" (str root "/")))))

(defn- node [nodes path]
  (if (contains? nodes path) (get nodes path) (throw (ex-info "Missing virtual path" {:code "ENOENT"}))))

(defn provider [tree]
  (let [edit (fn [path f]
               (swap! tree
                      (fn [nodes]
                        (let [parents (take-while some? (rest (iterate parent path)))]
                          (assoc (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 nil)) nodes parents)
                                 path (f (get nodes path)))))))
        entries (fn [path]
                  (let [nodes @tree]
                    (node nodes path)
                    (into [] (keep (fn [[p v]]
                                     (when (= path (parent p))
                                       {:name (subs p (inc (str/last-index-of p "/")))
                                        :type (if (nil? v) :directory :file)}))) nodes)))]
    {:read-file #(node @tree %) :exists #(contains? @tree %)
     :write-file (fn [path content _mode] (edit path (constantly content)))
     :append-file (fn [path content _mode] (edit path #(str % content)))
     :mkdir (fn [path recursive? _mode]
              (when-not recursive? (when-let [p (parent path)] (node @tree p)))
              (edit path identity))
     :stat (fn [path]
             (let [s (node @tree path) now (str (Instant/now))]
               {:is-file (string? s) :is-directory (nil? s)
                :size (if s (alength (.getBytes ^String s StandardCharsets/UTF_8)) 0)
                :birthtime now :mtime now}))
     :readdir #(mapv :name (entries %)) :readdir-with-types entries
     :rm (fn [path recursive? force?]
           (swap! tree (fn [nodes]
                         (when-not force? (node nodes path))
                         (when (and (not recursive?) (some #(= path (parent %)) (keys nodes)))
                           (throw (ex-info "Directory is not empty" {})))
                         (into {} (remove (fn [[p _]] (below? path p))) nodes))))
     :rename (fn [src dest]
               (swap! tree (fn [nodes]
                             (node nodes src)
                             (let [from (filter #(below? src %) (keys nodes))]
                               (into (apply dissoc nodes from)
                                     (map (fn [path] [(str dest (subs path (count src))) (get nodes path)]))
                                     from)))))}))
