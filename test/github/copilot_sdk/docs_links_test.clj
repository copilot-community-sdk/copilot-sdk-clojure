(ns github.copilot-sdk.docs-links-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [docs-links :as links]
            [generate-docs :as generate-docs]))

(defn- delete-tree!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (io/delete-file child true))))

(defmacro with-temp-root
  [[root] & body]
  `(let [~root (.toFile (java.nio.file.Files/createTempDirectory
                         "codox-links"
                         (make-array java.nio.file.attribute.FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree! ~root)))))

(defn- write-file!
  [root path content]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn- topic-html
  [content]
  (str "<html><body><header><a href=\"index.html\">Project index</a></header>"
       "<div class=\"document\"><div class=\"doc\"><div class=\"markdown\">"
       content
       "</div></div></div></body></html>"))

(deftest builds-deterministic-topic-identities
  (with-temp-root [root]
    (doseq [[path title] [["doc/index.md" "Documentation"]
                          ["doc/auth/index.md" "Authentication"]
                          ["doc/auth/byok.md" "BYOK"]
                          ["doc/reference/API.md" "API"]]]
      (write-file! root path (str "# " title)))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          identities (into {} (map (juxt :source-path :output-file)) manifest)]
      (is (= {"doc/auth/index.md" "doc-auth-index.html"
              "doc/auth/byok.md" "byok.html"
              "doc/reference/API.md" "API.html"
              "doc/index.md" "doc-index.html"}
             identities))
      (is (= manifest
             (links/build-topic-manifest root {:doc-files (reverse
                                                           (map :source-file manifest))}))))))

(deftest avoids-codox-owned-output-identities
  (with-temp-root [root]
    (write-file! root "doc/example.core.md" "# Namespace topic")
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          [entry] (links/reserve-output-identities
                   manifest
                   #{"index.html" "example.core.html"})]
      (is (= "doc-example.core.html" (:output-file entry))))))

(deftest rejects-second-order-codox-output-collisions
  (with-temp-root [root]
    (write-file! root "doc/example.md" "# Reserved")
    (write-file! root "doc/doc-example.md" "# Existing")
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          error (try
                  (links/reserve-output-identities manifest #{"example.html"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "Generated topic identities are not unique" (ex-message error)))
      (is (= {"doc-example.html" ["doc/doc-example.md" "doc/example.md"]}
             (:collisions (ex-data error)))))))

(deftest persists-fully-reserved-topic-identities
  (with-temp-root [root]
    (write-file! root "doc/example.md" "# Reserved")
    (let [source-manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          manifest (links/reserve-output-identities
                    source-manifest
                    #{"index.html" "example.html"})
          output-dir (io/file root "doc/api")]
      (.mkdirs output-dir)
      (links/write-topic-manifest! manifest
                                   #{"index.html" "example.html"}
                                   output-dir)
      (is (= {:manifest manifest
              :reserved-output-files #{"index.html" "example.html"}}
             (links/read-topic-manifest source-manifest output-dir))))))

(deftest rejects-derived-topic-identity-collisions
  (with-temp-root [root]
    (write-file! root "doc/doc-auth-index.md" "# Flat")
    (write-file! root "doc/auth/index.md" "# Nested")
    (let [error (try
                  (links/build-topic-manifest root {:doc-paths ["doc"]})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "Generated topic identities are not unique" (ex-message error)))
      (is (= {"doc-auth-index.html" ["doc/auth/index.md"
                                     "doc/doc-auth-index.md"]}
             (:collisions (ex-data error)))))))

(deftest rewrites-only-exact-source-topic-links
  (with-temp-root [root]
    (let [input-links ["auth/byok.html#providers"
                       "reference/API.md?view=full#streaming"
                       "https://example.com/reference/API.md"
                       "//example.com/reference/API.md"
                       "/reference/API.md"
                       "#streaming"
                       "?view=full"
                       "mailto:docs@example.com"
                       "data:text/plain,reference/API.md"
                       "javascript:void(0)"
                       "css/default.css"
                       "../README.md"
                       "auth/missing.html"]]
      (doseq [[path title] [["doc/index.md" "Documentation"]
                            ["doc/auth/index.md" "Authentication"]
                            ["doc/auth/byok.md" "BYOK"]
                            ["doc/auth/index with space.md" "Spaced"]
                            ["doc/reference/API.md" "API"]
                            ["doc/guides/topic.md" "Topic"]]]
        (write-file! root path (str "# " title)))
      (write-file! root "README.md" "# Outside documentation")
      (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
            index-entry (first (filter #(= "doc/index.md" (:source-path %))
                                       manifest))
            topic-entry (first (filter #(= "doc/guides/topic.md"
                                           (:source-path %))
                                       manifest))
            markdown (str/join "\n"
                               (concat
                                (map #(str "[link](" % ")") input-links)
                                ["`[code](reference/API.md)`"
                                 "```clojure"
                                 "[code](reference/API.md)"
                                 "```"]))
            rewritten (links/rewrite-markdown-links manifest
                                                    index-entry
                                                    markdown)]
        (testing "exact source topics use their generated identities"
          (is (str/includes? rewritten
                             "[link](byok.html#providers)"))
          (is (str/includes? rewritten
                             "[link](API.html?view=full#streaming)"))
          (is (= "[Auth](  doc-auth-index.html  )"
                 (links/rewrite-markdown-links
                  manifest
                  index-entry
                  "[Auth](  auth/index.md  )")))
          (is (= "[Spaced](<index with space.html>)"
                 (links/rewrite-markdown-links
                  manifest
                  index-entry
                  "[Spaced](<auth/index with space.md>)")))
          (is (= "[API](API.html#nested)"
                 (links/rewrite-markdown-links
                  manifest
                  topic-entry
                  "[API](../reference/API.md#nested)"))))
        (testing "non-topic and non-relative URLs remain byte-for-byte unchanged"
          (doseq [href (drop 2 input-links)]
            (is (str/includes? rewritten (str "[link](" href ")"))))
          (is (str/includes? rewritten "`[code](reference/API.md)`"))
          (is (str/includes? rewritten
                             "```clojure\n[code](reference/API.md)\n```")))))))

(deftest rejects-ambiguous-source-topic-links
  (with-temp-root [root]
    (doseq [path ["doc/index.md" "doc/foo.md" "doc/foo.markdown"]]
      (write-file! root path "# Topic"))
    (let [[index-entry] (links/build-topic-manifest
                         root
                         {:doc-files ["doc/index.md"]})
          [md-entry] (links/build-topic-manifest
                      root
                      {:doc-files ["doc/foo.md"]})
          [markdown-entry] (links/build-topic-manifest
                            root
                            {:doc-files ["doc/foo.markdown"]})
          manifest [index-entry
                    (assoc md-entry :output-file "foo-md.html")
                    (assoc markdown-entry :output-file "foo-markdown.html")]
          error (try
                  (links/rewrite-markdown-links
                   manifest
                   index-entry
                   "[Foo](foo.html#section)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "Generated topic link is ambiguous" (ex-message error)))
      (is (= {:source-page "doc/index.md"
              :href "foo.html#section"
              :matches ["doc/foo.md" "doc/foo.markdown"]}
             (ex-data error))))))

(deftest rejects-missing-generated-topic-targets
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/auth/byok.md" "# BYOK")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html "<a href=\"byok.html#providers\">BYOK</a>"))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          error (try
                  (links/assert-valid-output-links!
                   manifest
                   #{"index.html"}
                   (io/file root "doc/api"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "Generated topic links are broken" (ex-message error)))
      (is (some #(= {:source-page "doc/index.md"
                     :output-page "doc-index.html"
                     :href "byok.html#providers"
                     :target "byok.html"}
                    %)
                (:broken-links (ex-data error)))))))

(deftest validates-generated-topic-links
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/auth/byok.md" "# BYOK")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html "<a href=\"byok.html#section\">BYOK</a>"))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})]
      (is (= [{:source-page "doc/auth/byok.md"
               :output-page "byok.html"
               :href "byok.html"
               :target "byok.html"}
              {:source-page "doc/index.md"
               :output-page "doc-index.html"
               :href "byok.html#section"
               :target "byok.html"}]
             (links/broken-output-links manifest (io/file root "doc/api")))))))

(deftest validates-all-generated-relative-targets
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html
                  (str "<a href=\"missing/file.html#section\">Missing</a>"
                       "<a href=\"https://example.com/missing.html\">External</a>"
                       "<a href=\"#local\">Local</a>")))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})]
      (is (= [{:source-page "doc/index.md"
               :output-page "doc-index.html"
               :href "missing/file.html#section"
               :target (.getPath (.getCanonicalFile
                                  (io/file root "doc/api/missing/file.html")))}]
             (links/broken-relative-output-links
              manifest
              (io/file root "doc/api")))))))

(deftest detects-unresolved-generated-topic-links
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/auth/byok.md" "# BYOK")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html "<a href=\"auth/byok.html#providers\">BYOK</a>"))
    (write-file! root "doc/api/byok.html" (topic-html "<h1>BYOK</h1>"))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})]
      (is (= [{:source-page "doc/index.md"
               :output-page "doc-index.html"
               :href "auth/byok.html#providers"
               :expected "byok.html#providers"}]
             (links/unresolved-output-links manifest
                                            (io/file root "doc/api")))))))

(deftest distinguishes-topic-index-links-from-codox-chrome
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/upstream-doc-gap-matrix.md"
                 "# Matrix\n\n[`index.md`](index.md)\n")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html "<h1>Documentation</h1>"))
    ;; Rendered as Codox would emit it if the producer had NOT rewritten the source
    ;; link: a same-directory index.md flattens onto the reserved project index.
    (write-file! root "doc/api/upstream-doc-gap-matrix.html"
                 (topic-html "<a href=\"index.html\"><code>index.md</code></a>"))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          matrix-entry (first (filter #(= "doc/upstream-doc-gap-matrix.md"
                                          (:source-path %))
                                      manifest))]
      (testing "producer rewrites the source link to the reserved index topic"
        (is (= "# Matrix\n\n[`index.md`](doc-index.html)\n"
               (links/rewrite-markdown-links
                manifest matrix-entry
                "# Matrix\n\n[`index.md`](index.md)\n"))))
      (testing "source-coupled check reports the stale identity"
        (is (= [{:source-page "doc/upstream-doc-gap-matrix.md"
                 :output-page "upstream-doc-gap-matrix.html"
                 :href "index.html"
                 :expected "doc-index.html"}]
               (links/unresolved-output-links manifest
                                              (io/file root "doc/api")))))
      (testing "independent check flags the content link but never Codox chrome"
        (is (= [{:source-page "doc/upstream-doc-gap-matrix.md"
                 :output-page "upstream-doc-gap-matrix.html"
                 :href "index.html"
                 :target "index.html"}]
               (links/reserved-target-content-links
                manifest #{"index.html"} (io/file root "doc/api"))))))))

(deftest independent-check-catches-shared-short-circuit
  ;; A nested topic links to a same-directory index.md that has no matching source
  ;; file, so the producer cannot resolve it and Codox flattens the link onto the
  ;; reserved project index. Because the producer and unresolved-output-links share
  ;; the same source resolution, both silently accept the stale link; the
  ;; manifest-derived reserved-target check is the only guard that catches it.
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/guides/topic.md" "# Topic")
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})
          topic-entry (first (filter #(= "doc/guides/topic.md" (:source-path %))
                                     manifest))]
      (testing "producer leaves an unresolvable same-directory index link"
        (is (= "[home](index.md)"
               (links/rewrite-markdown-links manifest topic-entry
                                             "[home](index.md)"))))
      (write-file! root "doc/api/doc-index.html"
                   (topic-html "<h1>Documentation</h1>"))
      (write-file! root "doc/api/topic.html"
                   (topic-html "<a href=\"index.html\">home</a>"))
      (testing "source-coupled check shares the short-circuit and misses it"
        (is (empty? (links/unresolved-output-links manifest
                                                   (io/file root "doc/api")))))
      (testing "independent reserved-target check catches the stale link"
        (is (= [{:source-page "doc/guides/topic.md"
                 :output-page "topic.html"
                 :href "index.html"
                 :target "index.html"}]
               (links/reserved-target-content-links
                manifest #{"index.html"} (io/file root "doc/api"))))))))

(deftest validates-generated-topic-anchors
  (with-temp-root [root]
    (write-file! root "doc/index.md" "# Documentation")
    (write-file! root "doc/reference/API.md" "# API")
    (write-file! root "doc/api/doc-index.html"
                 (topic-html
                  (str "<a href=\"API.html#present\">Present</a>"
                       "<a href=\"API.html#missing\">Missing</a>")))
    (write-file! root "doc/api/API.html"
                 (topic-html "<h2 id=\"present\">Present</h2>"))
    (let [manifest (links/build-topic-manifest root {:doc-paths ["doc"]})]
      (is (= [{:source-page "doc/index.md"
               :output-page "doc-index.html"
               :href "API.html#missing"
               :target "API.html"
               :anchor "missing"}]
             (links/broken-output-anchors manifest
                                          (io/file root "doc/api")))))))

(deftest generates-colliding-topics-as-distinct-pages
  (with-temp-root [root]
    (write-file! root "doc/index.md"
                 "# Documentation\n\n[Auth](auth/index.md)\n")
    (write-file! root "doc/auth/index.md"
                 "# Authentication\n\n[Home](../index.md)\n")
    (write-file! root "doc/upstream-doc-gap-matrix.md"
                 "# Matrix\n\n[Index](index.md) and [`index.md`](index.md)\n")
    (let [output-dir (io/file root "doc/api")]
      (generate-docs/generate-docs
       {:root-path (.getPath root)
        :source-paths [(.getPath (io/file (System/getProperty "user.dir")
                                          "src"))]
        :doc-paths [(.getPath (io/file root "doc"))]
        :output-path (.getPath output-dir)
        :metadata {:doc/format :markdown}})
      (is (str/includes? (slurp (io/file output-dir "doc-index.html"))
                         "<h1><a href=\"#documentation\""))
      (is (str/includes? (slurp (io/file output-dir "doc-auth-index.html"))
                         "<h1><a href=\"#authentication\""))
      (is (str/includes? (slurp (io/file output-dir "doc-index.html"))
                         "href=\"doc-auth-index.html\""))
      (is (str/includes? (slurp (io/file output-dir "doc-auth-index.html"))
                         "href=\"doc-index.html\""))
      (is (str/includes? (slurp (io/file output-dir "index.html"))
                         "class=\"namespace-index\""))
      (is (str/includes? (slurp (io/file output-dir "index.html"))
                         "href=\"doc-index.html\""))
      (is (str/includes? (slurp (io/file output-dir
                                         "upstream-doc-gap-matrix.html"))
                         "href=\"doc-index.html\">Index</a>"))
      (is (str/includes? (slurp (io/file output-dir
                                         "upstream-doc-gap-matrix.html"))
                         "href=\"doc-index.html\"><code>index.md</code></a>"))
      (is (not (str/includes?
                (#'links/topic-content-html
                 (slurp (io/file output-dir "upstream-doc-gap-matrix.html")))
                "href=\"index.html\"")))
      (is (str/includes? (slurp (io/file output-dir
                                         "upstream-doc-gap-matrix.html"))
                         "<h1><a href=\"index.html\""))
      (is (< (.indexOf (slurp (io/file output-dir "index.html"))
                       ">Authentication</span>")
             (.indexOf (slurp (io/file output-dir "index.html"))
                       ">Documentation</span>")))
      (is (.isFile (io/file output-dir "github.copilot-sdk.html")))
      (is (.isFile (io/file output-dir "css/default.css"))))))
