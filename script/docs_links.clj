(ns docs-links
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def topic-manifest-filename "topic-manifest.edn")

(def ^:private markdown-extension-re #"\.(?i:md|markdown)$")
(def ^:private markdown-link-re #"(\]\()(\s*)(<[^>]+>|[^\s)]+)([^)]*)(\))")
(def ^:private href-re #"(?i)(href\s*=\s*)([\"'])([^\"']*)([\"'])")
(def ^:private scheme-re #"(?i)^[a-z][a-z0-9+.-]*:")
(def ^:private topic-content-start "<div class=\"markdown\">")
(def ^:private topic-content-end "</div></div></div></body>")

(defn- canonical-file
  [file]
  (.getCanonicalFile (io/file file)))

(defn- path-key
  [file]
  (.getPath (canonical-file file)))

(defn- posix-path
  [path]
  (str/replace (str path) java.io.File/separator "/"))

(defn- markdown-file?
  [file]
  (and (.isFile file)
       (re-find markdown-extension-re (.getName file))))

(defn- relative-path
  [root file]
  (posix-path (.relativize (.toPath (canonical-file root))
                           (.toPath (canonical-file file)))))

(defn- descendant?
  [root file]
  (.startsWith (.toPath (canonical-file file))
               (.toPath (canonical-file root))))

(defn- resolve-from-root
  [root path]
  (canonical-file (if (.isAbsolute (io/file path))
                    path
                    (io/file root path))))

(defn- document-roots
  [root options]
  (mapv #(resolve-from-root root %)
        (or (:doc-paths options) ["doc"])))

(defn- selected-source-files
  [root options doc-roots]
  (let [doc-files (:doc-files options :all)]
    (->> (if (= :all doc-files)
           (mapcat file-seq doc-roots)
           (map #(resolve-from-root root %) doc-files))
         (filter markdown-file?)
         (map canonical-file)
         distinct
         (sort-by path-key))))

(defn- source-doc-root
  [doc-roots source-file]
  (or (->> doc-roots
           (filter #(descendant? % source-file))
           (sort-by #(count (path-key %)) >)
           first)
      (throw (ex-info "Documentation source is outside the documentation roots"
                      {:source-file (path-key source-file)
                       :doc-roots (mapv path-key doc-roots)}))))

(defn- basename
  [source-path]
  (-> source-path
      (str/split #"/")
      last
      (str/replace markdown-extension-re "")))

(defn- path-derived-name
  [source-path]
  (-> source-path
      (str/replace markdown-extension-re "")
      (str/replace "/" "-")))

(defn- assign-output-names
  [entries]
  (let [basename-counts (frequencies (map :basename entries))]
    (mapv (fn [{:keys [basename source-path] :as entry}]
            (let [output-name (if (> (basename-counts basename) 1)
                                (path-derived-name source-path)
                                basename)]
              (assoc entry
                     :output-name output-name
                     :output-file (str output-name ".html"))))
          entries)))

(defn- assert-unique-output-identities
  [entries]
  (let [collisions (->> entries
                        (group-by :output-file)
                        (keep (fn [[output-file matches]]
                                (when (> (count matches) 1)
                                  [output-file (mapv :source-path matches)])))
                        (into (sorted-map)))]
    (when (seq collisions)
      (throw (ex-info "Generated topic identities are not unique"
                      {:collisions collisions})))
    entries))

(defn reserve-output-identities
  "Derive path-based names for topics that collide with Codox-owned outputs."
  [entries reserved-output-files]
  (let [entries (mapv (fn [{:keys [output-file source-path] :as entry}]
                        (if (contains? reserved-output-files output-file)
                          (let [output-name (path-derived-name source-path)]
                            (assoc entry
                                   :output-name output-name
                                   :output-file (str output-name ".html")))
                          entry))
                      entries)]
    (assert-unique-output-identities entries)
    (let [collisions (->> entries
                          (filter #(contains? reserved-output-files
                                              (:output-file %)))
                          (map :output-file)
                          sort
                          vec)]
      (when (seq collisions)
        (throw (ex-info "Generated topic identities collide with Codox outputs"
                        {:collisions collisions}))))
    entries))

(defn build-topic-manifest
  "Build deterministic source-to-output identities for Codox Markdown topics."
  [root options]
  (let [root (canonical-file root)
        doc-roots (document-roots root options)
        entries (->> (selected-source-files root options doc-roots)
                     (mapv (fn [source-file]
                             (let [doc-root (source-doc-root doc-roots source-file)
                                   source-path (relative-path root source-file)
                                   doc-relative-path (relative-path doc-root source-file)]
                               {:source-file source-file
                                :source-key (path-key source-file)
                                :doc-root doc-root
                                :source-path source-path
                                :doc-relative-path doc-relative-path
                                :basename (basename doc-relative-path)})))
                     assign-output-names)]
    (reserve-output-identities entries #{"index.html"})))

(defn write-topic-manifest!
  "Persist the fully reserved topic manifest for standalone validation."
  [manifest reserved-output-files output-dir]
  (let [data {:version 1
              :reserved-output-files (vec (sort reserved-output-files))
              :topics (->> manifest
                           (sort-by :source-path)
                           (mapv #(select-keys % [:source-path :output-file])))}]
    (spit (io/file output-dir topic-manifest-filename)
          (str (pr-str data) "\n"))))

(defn read-topic-manifest
  "Load generated topic identities and merge them into the current source manifest."
  [source-manifest output-dir]
  (let [file (io/file output-dir topic-manifest-filename)
        {:keys [version reserved-output-files topics]} (edn/read-string (slurp file))
        source-paths (set (map :source-path source-manifest))
        generated-paths (set (map :source-path topics))]
    (when-not (= 1 version)
      (throw (ex-info "Unsupported generated topic manifest version"
                      {:version version})))
    (when-not (= source-paths generated-paths)
      (throw (ex-info "Generated topic manifest does not match documentation sources"
                      {:missing-sources (vec (sort (set/difference
                                                    source-paths
                                                    generated-paths)))
                       :stale-sources (vec (sort (set/difference
                                                  generated-paths
                                                  source-paths)))})))
    (when-not (= (count topics) (count generated-paths))
      (throw (ex-info "Generated topic manifest contains duplicate sources"
                      {:topics topics})))
    (let [outputs-by-source (into {} (map (juxt :source-path :output-file)) topics)
          manifest (mapv (fn [{:keys [source-path] :as entry}]
                           (let [output-file (outputs-by-source source-path)]
                             (when-not (and (string? output-file)
                                            (re-matches #"[^/\\]+\.html" output-file))
                               (throw (ex-info "Invalid generated topic output identity"
                                               {:source-path source-path
                                                :output-file output-file})))
                             (assoc entry
                                    :output-file output-file
                                    :output-name (str/replace output-file #"\.html$" ""))))
                         source-manifest)
          reserved-output-files (set reserved-output-files)]
      (assert-unique-output-identities manifest)
      (let [collisions (->> manifest
                            (filter #(contains? reserved-output-files
                                                (:output-file %)))
                            (mapv #(select-keys % [:source-path :output-file])))]
        (when (seq collisions)
          (throw (ex-info "Generated topics collide with Codox-owned outputs"
                          {:collisions collisions}))))
      {:manifest manifest
       :reserved-output-files reserved-output-files})))

(defn- split-url-suffix
  [href]
  (let [query-index (.indexOf ^String href "?")
        fragment-index (.indexOf ^String href "#")
        indexes (remove neg? [query-index fragment-index])
        suffix-index (when (seq indexes) (apply min indexes))]
    (if suffix-index
      [(subs href 0 suffix-index) (subs href suffix-index)]
      [href ""])))

(defn- relative-url?
  [path]
  (and (seq path)
       (not (str/starts-with? path "/"))
       (not (str/starts-with? path "\\"))
       (not (re-find scheme-re path))))

(defn- source-candidate-files
  [{:keys [source-file]} path]
  (let [source-dir (.getParentFile ^java.io.File source-file)
        resolved (canonical-file (io/file source-dir path))]
    (cond
      (re-find markdown-extension-re path)
      [resolved]

      (re-find #"(?i)\.html$" path)
      [(canonical-file (io/file source-dir
                                (str/replace path #"(?i)\.html$" ".md")))
       (canonical-file (io/file source-dir
                                (str/replace path #"(?i)\.html$" ".markdown")))]

      :else
      [])))

(defn- expected-topic-href
  [manifest-by-source source-entry href]
  (let [[path suffix] (split-url-suffix href)]
    (if-not (relative-url? path)
      href
      (let [matches (->> (source-candidate-files source-entry path)
                         (keep #(manifest-by-source (path-key %)))
                         distinct
                         vec)]
        (when (> (count matches) 1)
          (throw (ex-info "Generated topic link is ambiguous"
                          {:source-page (:source-path source-entry)
                           :href href
                           :matches (mapv :source-path matches)})))
        (if-let [target (first matches)]
          (str (:output-file target) suffix)
          href)))))

(defn- rewrite-link-segment
  [manifest-by-source source-entry segment]
  (str/replace segment markdown-link-re
               (fn [[_ prefix leading raw-href trailing closing]]
                 (let [angle-wrapped? (and (str/starts-with? raw-href "<")
                                           (str/ends-with? raw-href ">"))
                       href (if angle-wrapped?
                              (subs raw-href 1 (dec (count raw-href)))
                              raw-href)
                       rewritten (expected-topic-href manifest-by-source
                                                      source-entry
                                                      href)]
                   (str prefix
                        leading
                        (if angle-wrapped? (str "<" rewritten ">") rewritten)
                        trailing
                        closing)))))

(defn- backtick-run
  [line index]
  (let [end (loop [position index]
              (if (and (< position (count line))
                       (= \` (.charAt ^String line position)))
                (recur (inc position))
                position))]
    (subs line index end)))

(defn- rewrite-outside-inline-code
  [manifest-by-source source-entry line]
  (loop [position 0
         output (StringBuilder.)]
    (let [open (.indexOf ^String line "`" position)]
      (if (neg? open)
        (str (.append output
                      (rewrite-link-segment manifest-by-source
                                            source-entry
                                            (subs line position))))
        (let [ticks (backtick-run line open)
              close (.indexOf ^String line ticks (+ open (count ticks)))]
          (.append output
                   (rewrite-link-segment manifest-by-source
                                         source-entry
                                         (subs line position open)))
          (if (neg? close)
            (str (.append output (subs line open)))
            (let [end (+ close (count ticks))]
              (.append output (subs line open end))
              (recur end output))))))))

(defn- fence-marker
  [line]
  (some-> (re-find #"^\s*(`{3,}|~{3,})" line)
          second
          first
          str))

(defn- closes-fence?
  [marker line]
  (boolean
   (re-matches (if (= marker "`")
                 #"^\s*`{3,}\s*$"
                 #"^\s*~{3,}\s*$")
               line)))

(defn rewrite-markdown-links
  "Rewrite source-topic Markdown links before Codox renders document chrome."
  [manifest source-entry markdown]
  (let [manifest-by-source (into {} (map (juxt :source-key identity)) manifest)]
    (->> (str/split markdown #"\n" -1)
         (reduce (fn [{:keys [fence lines]} line]
                   (cond
                     fence
                     {:fence (when-not (closes-fence? fence line) fence)
                      :lines (conj lines line)}

                     (fence-marker line)
                     {:fence (fence-marker line)
                      :lines (conj lines line)}

                     :else
                     {:fence nil
                      :lines (conj lines
                                   (rewrite-outside-inline-code
                                    manifest-by-source
                                    source-entry
                                    line))}))
                 {:fence nil :lines []})
         :lines
         (str/join "\n"))))

(defn- topic-content-html
  [html]
  (let [start (.indexOf ^String html topic-content-start)
        end (.lastIndexOf ^String html topic-content-end)]
    (when (or (neg? start) (neg? end) (< end start))
      (throw (ex-info "Could not locate generated Markdown topic content"
                      {})))
    (subs html (+ start (count topic-content-start)) end)))

(defn- hrefs
  [html]
  (keep (fn [[_ _ quote href closing-quote]]
          (when (= quote closing-quote)
            href))
        (re-seq href-re html)))

(defn broken-output-links
  "Return broken relative links between generated topic pages."
  [manifest output-dir]
  (let [output-dir (canonical-file output-dir)
        output-files (set (map :output-file manifest))]
    (->> manifest
         (mapcat
          (fn [{:keys [source-path output-file]}]
            (let [html-file (io/file output-dir output-file)]
              (if-not (.isFile html-file)
                [{:source-page source-path
                  :output-page output-file
                  :href output-file
                  :target output-file}]
                (for [href (hrefs (topic-content-html (slurp html-file)))
                      :let [[path] (split-url-suffix href)]
                      :when (and (relative-url? path)
                                 (contains? output-files path)
                                 (not (.isFile (io/file output-dir path))))]
                  {:source-page source-path
                   :output-page output-file
                   :href href
                   :target path})))))
         vec)))

(defn broken-relative-output-links
  "Return relative topic-content hrefs whose generated target does not exist."
  [manifest output-dir]
  (let [output-dir (canonical-file output-dir)]
    (->> manifest
         (mapcat
          (fn [{:keys [source-path output-file]}]
            (let [html-file (io/file output-dir output-file)]
              (when (.isFile html-file)
                (for [href (hrefs (topic-content-html (slurp html-file)))
                      :let [[path] (split-url-suffix href)
                            target (canonical-file (io/file output-dir path))]
                      :when (and (relative-url? path)
                                 (not (.isFile target))
                                 (not (.isDirectory target)))]
                  {:source-page source-path
                   :output-page output-file
                   :href href
                   :target (posix-path (.getPath target))})))))
         vec)))

(defn unresolved-output-links
  "Return source-topic hrefs that do not use their generated identities."
  [manifest output-dir]
  (let [manifest-by-source (into {} (map (juxt :source-key identity)) manifest)]
    (->> manifest
         (mapcat
          (fn [{:keys [source-path output-file] :as entry}]
            (let [html-file (io/file output-dir output-file)]
              (when (.isFile html-file)
                (keep (fn [href]
                        (let [expected (expected-topic-href manifest-by-source
                                                            entry
                                                            href)]
                          (when-not (= href expected)
                            {:source-page source-path
                             :output-page output-file
                             :href href
                             :expected expected})))
                      (hrefs (topic-content-html (slurp html-file))))))))
         vec)))

(defn- fragment
  [href]
  (let [index (.indexOf ^String href "#")]
    (when (and (not (neg? index))
               (< index (dec (count href))))
      (subs href (inc index)))))

(defn- anchor-present?
  [html anchor]
  (or (str/includes? html (str "id=\"" anchor "\""))
      (str/includes? html (str "id='" anchor "'"))))

(defn broken-output-anchors
  "Return missing anchors in local generated topic links."
  [manifest output-dir]
  (let [output-html (->> manifest
                         (keep (fn [{:keys [output-file]}]
                                 (let [file (io/file output-dir output-file)]
                                   (when (.isFile file)
                                     [output-file (slurp file)]))))
                         (into {}))
        output-files (set (keys output-html))]
    (->> manifest
         (mapcat
          (fn [{:keys [source-path output-file]}]
            (when-let [html (output-html output-file)]
              (for [href (hrefs (topic-content-html html))
                    :let [[path] (split-url-suffix href)
                          anchor (fragment href)
                          target (if (seq path) path output-file)
                          target-html (output-html target)]
                    :when (and anchor
                               (or (empty? path)
                                   (contains? output-files path))
                               target-html
                               (not (anchor-present? target-html anchor)))]
                {:source-page source-path
                 :output-page output-file
                 :href href
                 :target target
                 :anchor anchor}))))
         vec)))

(defn reserved-target-content-links
  "Return topic-content links that resolve to a reserved Codox-owned output.

   Derived only from the topic manifest's reserved-output set, independent of the
   producer's source-link resolution. A rendered topic page may only reference
   generated topic identities, so a relative same-directory link whose flattened
   target is a reserved output (the Codox project index or a namespace page) proves
   the producer failed to rewrite a documentation link and Codox left it pointing
   at its own chrome. Because this check never reuses `expected-topic-href`, it
   catches producer misses even when source resolution silently short-circuits.

   `reserve-output-identities` guarantees rewritten topics never collide with
   reserved names, so a hit can only come from an unrewritten source link; a
   hand-authored documentation link straight to a namespace page is reported for
   the same reason, since it bypasses the manifest and breaks if the page is
   renamed."
  [manifest reserved-output-files output-dir]
  (let [output-dir (canonical-file output-dir)]
    (->> manifest
         (mapcat
          (fn [{:keys [source-path output-file]}]
            (let [html-file (io/file output-dir output-file)]
              (when (.isFile html-file)
                (for [href (hrefs (topic-content-html (slurp html-file)))
                      :let [[path] (split-url-suffix href)]
                      :when (and (relative-url? path)
                                 (not (str/includes? path "/"))
                                 (contains? reserved-output-files path))]
                  {:source-page source-path
                   :output-page output-file
                   :href href
                   :target path})))))
         vec)))

(defn assert-valid-output-links!
  [manifest reserved-output-files output-dir]
  (let [broken (broken-output-links manifest output-dir)
        broken-relative (broken-relative-output-links manifest output-dir)
        unresolved (unresolved-output-links manifest output-dir)
        broken-anchors (broken-output-anchors manifest output-dir)
        reserved-targets (reserved-target-content-links manifest
                                                        reserved-output-files
                                                        output-dir)]
    (when (or (seq broken) (seq broken-relative) (seq unresolved) (seq broken-anchors)
              (seq reserved-targets))
      (throw (ex-info "Generated topic links are broken"
                      {:broken-links broken
                       :broken-relative-links broken-relative
                       :unresolved-links unresolved
                       :broken-anchors broken-anchors
                       :reserved-target-links reserved-targets}))))
  nil)
