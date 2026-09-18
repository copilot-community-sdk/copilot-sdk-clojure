(ns github.copilot-sdk.schema-fetch-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.math BigInteger)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security MessageDigest)))

(def ^:private script-path
  (.getCanonicalPath (io/file "script/codegen/fetch_schemas.clj")))

(def ^:private repository-root
  (.getCanonicalFile (io/file ".")))

(def ^:private fixture-version "9.9.9")

(def ^:private fixture-asset-name
  (str "github-copilot-" fixture-version "-linux-x64.tgz"))

(def ^:private default-schemas
  {"api.schema.json" "{\"title\":\"API\"}\n"
   "session-events.schema.json" "{\"title\":\"Events\"}\n"})

(def ^:private fetch-env-vars
  ["COPILOT_CLI_RELEASE_TARBALL"
   "COPILOT_CLI_RELEASE_SHA256"
   "COPILOT_CLI_DOWNLOAD_BASE_URL"
   "COPILOT_CLI_SCHEMA_OUTPUT"])

(defn- delete-tree!
  [root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file true))))

(defmacro with-temp-root
  [[root] & body]
  `(let [~root (.toFile
                (Files/createTempDirectory
                 "copilot-schema-fetch-"
                 (make-array java.nio.file.attribute.FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree! ~root)))))

(defn- sha256-file
  [file]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (Files/readAllBytes (.toPath file)))]
    (format "%064x" (BigInteger. 1 digest))))

(defn- write-release-tree!
  [root version schemas]
  (let [schemas-dir (io/file root "package" "schemas")]
    (.mkdirs schemas-dir)
    (spit (io/file root "package" "package.json")
          (json/write-str {"name" "@github/copilot"
                           "version" version}))
    (doseq [[schema-name content] schemas]
      (spit (io/file schemas-dir schema-name) content))))

(defn- create-tar!
  [archive args]
  (let [{:keys [exit err]}
        (apply sh/sh
               (into ["tar" "-czf" (.getPath archive)] args))]
    (when-not (zero? exit)
      (throw (ex-info "Could not create schema fixture archive"
                      {:exit exit :stderr err}))))
  archive)

(defn- create-release-archive!
  ([root]
   (create-release-archive! root fixture-version default-schemas))
  ([root schemas]
   (create-release-archive! root fixture-version schemas))
  ([root version schemas]
   (let [archive (io/file root "release.tgz")]
     (write-release-tree! root version schemas)
     (create-tar! archive ["-C" (.getPath root) "package"]))))

(defn- create-release-archive-with-duplicate!
  [root member]
  (let [base-root (io/file root "base")
        duplicate-root (io/file root "duplicate")
        archive (io/file root "release.tgz")]
    (write-release-tree! base-root fixture-version default-schemas)
    (write-release-tree! duplicate-root fixture-version default-schemas)
    (create-tar!
     archive
     ["-C" (.getPath base-root) "package"
      "-C" (.getPath duplicate-root) member])))

(defn- create-release-archive-with-aliased-duplicate!
  [root member]
  (let [base-root (io/file root "base")
        duplicate-root (io/file root "duplicate")
        archive (io/file root "release.tgz")]
    (write-release-tree! base-root fixture-version default-schemas)
    (write-release-tree! duplicate-root fixture-version default-schemas)
    (create-tar!
     archive
     ["-C" (.getPath base-root) "package"
      "-C" (.getPath duplicate-root) (str "./" member)])))

(defn- run-fetch
  [{:keys [args dir env]
    :or {args ["--version" fixture-version]}}]
  (let [clean-env (apply dissoc (into {} (System/getenv)) fetch-env-vars)
        command (cond-> (conj (into ["bb" script-path] args)
                              :env
                              (merge clean-env env))
                  dir (conj :dir (str dir)))]
    (apply sh/sh command)))

(defn- run-script-eval
  [form]
  (sh/sh "bb" "-e" (str "(load-file " (pr-str script-path) ")\n" form)))

(defn- run-local-fetch
  ([archive expected-hash output-dir]
   (run-local-fetch archive expected-hash output-dir {}))
  ([archive expected-hash output-dir {:keys [args dir env]}]
   (run-fetch
    {:args (or args ["--version" fixture-version])
     :dir dir
     :env (merge (cond-> {"COPILOT_CLI_RELEASE_TARBALL" (str archive)
                          "COPILOT_CLI_SCHEMA_OUTPUT" (str output-dir)}
                   expected-hash
                   (assoc "COPILOT_CLI_RELEASE_SHA256" expected-hash))
                 env)})))

(defn- assert-fetch-failure!
  [{:keys [exit out err]} output-dir expected-message]
  (let [output (str out err)]
    (is (not (zero? exit)) output)
    (is (str/includes? output expected-message) output)
    (is (not (.exists output-dir)))))

(defn- create-release-download!
  [root archive checksums]
  (let [release-dir (io/file root "download" (str "v" fixture-version))
        release-archive (io/file release-dir fixture-asset-name)]
    (.mkdirs release-dir)
    (io/copy archive release-archive)
    (spit (io/file release-dir "SHA256SUMS.txt") checksums)
    (str "file://" (.getCanonicalPath (io/file root "download")))))

(deftest extracts-schemas-from-verified-release-archive
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (zero? exit) err)
      (when (zero? exit)
        (is (= {"title" "API"}
               (json/read-str
                (slurp (io/file output-dir "api.schema.json")))))
        (is (= {"title" "Events"}
               (json/read-str
                (slurp (io/file output-dir "session-events.schema.json")))))
        (is (str/includes?
             (slurp (io/file output-dir "README.md"))
             "local archive override"))
        (is (not (str/includes?
                  (slurp (io/file output-dir "README.md"))
                  "GitHub Release")))
        (is (str/includes? out "Verified local archive override"))))))

(deftest requires-local-archive-checksum
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          result (run-local-fetch archive nil output-dir)]
      (assert-fetch-failure!
       result output-dir "Missing or invalid SHA-256 for release.tgz"))))

(deftest rejects-release-archive-with-wrong-checksum
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          result
          (run-local-fetch archive (apply str (repeat 64 "0")) output-dir)]
      (assert-fetch-failure!
       result output-dir "Integrity verification failed"))))

(deftest rejects-release-archive-with-mismatched-version
  (with-temp-root [root]
    (let [archive (create-release-archive! root "8.8.8" default-schemas)
          output-dir (io/file root "output")
          result
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (assert-fetch-failure!
       result
       output-dir
       "Archive version mismatch: expected 9.9.9, got 8.8.8"))))

(deftest requires-exactly-one-package-manifest
  (doseq [[label archive-fn]
          [["missing"
            (fn [root]
              (write-release-tree! root fixture-version default-schemas)
              (create-tar!
               (io/file root "release.tgz")
               ["-C" (.getPath root) "package/schemas"]))]
           ["duplicate"
            #(create-release-archive-with-duplicate!
              % "package/package.json")]]]
    (testing label
      (with-temp-root [root]
        (let [archive (archive-fn root)
              output-dir (io/file root "output")
              result
              (run-local-fetch archive (sha256-file archive) output-dir)]
          (assert-fetch-failure!
           result
           output-dir
           "must contain exactly one package/package.json"))))))

(deftest identifies-malformed-package-json
  (with-temp-root [root]
    (let [archive (io/file root "release.tgz")
          output-dir (io/file root "output")]
      (write-release-tree! root fixture-version default-schemas)
      (spit (io/file root "package" "package.json") "{")
      (create-tar! archive ["-C" (.getPath root) "package"])
      (assert-fetch-failure!
       (run-local-fetch archive (sha256-file archive) output-dir)
       output-dir
       "Invalid JSON in package/package.json"))))

(deftest extracts-from-the-verified-local-archive-snapshot
  (with-temp-root [root]
    (let [original-root (io/file root "original")
          replacement-root (io/file root "replacement")
          archive (create-release-archive! original-root)
          replacement
          (create-release-archive!
           replacement-root
           (assoc default-schemas
                  "api.schema.json"
                  "{\"title\":\"Replacement\"}\n"))
          original-hash (sha256-file archive)
          replacement-hash (sha256-file replacement)
          bin-dir (io/file root "bin")
          tar-wrapper (io/file bin-dir "tar")
          real-tar (str/trim (:out (sh/sh "which" "tar")))
          output-dir (io/file root "output")]
      (.mkdirs bin-dir)
      (spit tar-wrapper
            (str "#!/bin/sh\n"
                 "if [ \"$1\" = \"-tzf\" ]; then\n"
                 "  \"$REAL_TAR\" \"$@\"\n"
                 "  status=$?\n"
                 "  cp \"$REPLACEMENT_ARCHIVE\" \"$CALLER_ARCHIVE\"\n"
                 "  exit \"$status\"\n"
                 "fi\n"
                 "exec \"$REAL_TAR\" \"$@\"\n"))
      (is (.setExecutable tar-wrapper true))
      (let [{:keys [exit err]}
            (run-local-fetch
             archive original-hash output-dir
             {:env {"PATH" (str (.getPath bin-dir)
                                (System/getProperty "path.separator")
                                (System/getenv "PATH"))
                    "REAL_TAR" real-tar
                    "REPLACEMENT_ARCHIVE" (.getPath replacement)
                    "CALLER_ARCHIVE" (.getPath archive)}})]
        (is (zero? exit) err)
        (when (zero? exit)
          (is (= replacement-hash (sha256-file archive))
              "the harness must replace the caller-owned archive")
          (is (= {"title" "API"}
                 (json/read-str
                  (slurp (io/file output-dir "api.schema.json"))))))))))

(deftest requires-both-schema-files
  (with-temp-root [root]
    (let [archive
          (create-release-archive! root
                                   (dissoc default-schemas
                                           "session-events.schema.json"))
          output-dir (io/file root "output")
          result
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (assert-fetch-failure!
       result
       output-dir
       "is missing required package/schemas/session-events.schema.json"))))

(deftest rejects-duplicate-schema-members
  (with-temp-root [root]
    (let [archive
          (create-release-archive-with-duplicate!
           root "package/schemas/api.schema.json")
          output-dir (io/file root "output")
          result
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (assert-fetch-failure!
       result
       output-dir
       "must contain exactly one package/schemas/api.schema.json"))))

(deftest rejects-noncanonical-archive-member-aliases
  (doseq [member ["package/package.json"
                  "package/schemas/api.schema.json"]]
    (testing member
      (with-temp-root [root]
        (let [archive
              (create-release-archive-with-aliased-duplicate! root member)
              output-dir (io/file root "output")]
          (assert-fetch-failure!
           (run-local-fetch archive (sha256-file archive) output-dir)
           output-dir
           (str "Non-canonical archive member: ./" member)))))))

(deftest rejects-nested-and-traversal-shaped-schema-members
  (doseq [[label member-path]
          [["nested" ["nested" "future.schema.json"]]
           ["traversal" [".." "evil.schema.json"]]]]
    (testing label
      (with-temp-root [root]
        (let [archive (io/file root "release.tgz")
              output-dir (io/file root "output")
              member-file
              (apply io/file root "package" "schemas" member-path)]
          (write-release-tree! root fixture-version default-schemas)
          (.mkdirs (.getParentFile member-file))
          (spit member-file "{\"title\":\"unexpected\"}\n")
          (create-tar!
           archive
           (cond-> ["-C" (.getPath root) "package"]
             (= label "traversal")
             (conj "-C" (.getPath root)
                   "package/schemas/../evil.schema.json")))
          (assert-fetch-failure!
           (run-local-fetch archive (sha256-file archive) output-dir)
           output-dir
           (if (= label "traversal")
             "Non-canonical archive member: package/schemas/../evil.schema.json"
             "Nested schema member is not supported: package/schemas/nested/future.schema.json")))))))

(deftest rejects-nonportable-top-level-schema-names
  (with-temp-root [root]
    (let [archive (io/file root "release.tgz")
          output-dir (io/file root "output")]
      (write-release-tree! root fixture-version default-schemas)
      (spit (io/file root "package" "schemas" "D:package.json")
            "{\"title\":\"unsafe\"}\n")
      (create-tar! archive ["-C" (.getPath root) "package"])
      (assert-fetch-failure!
       (run-local-fetch archive (sha256-file archive) output-dir)
       output-dir
       "Unsafe top-level schema member: package/schemas/D:package.json"))))

(deftest copies-additional-top-level-schema-files
  (with-temp-root [root]
    (let [archive
          (create-release-archive!
           root
           (assoc default-schemas
                  "future.schema.json"
                  "{\"title\":\"Future\"}\n"))
          output-dir (io/file root "output")
          {:keys [exit err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (zero? exit) err)
      (when (zero? exit)
        (is (.exists (io/file output-dir "future.schema.json")))
        (is (= {"title" "Future"}
               (json/read-str
                (slurp (io/file output-dir "future.schema.json")))))))))

(deftest rejects-non-object-schema-content-before-installation
  (doseq [[label content expected-message]
          [["empty" "" "Invalid JSON in package/schemas/api.schema.json"]
           ["whitespace" " \n\t"
            "Invalid JSON in package/schemas/api.schema.json"]
           ["null" "null\n"
            "package/schemas/api.schema.json must contain a JSON object"]]]
    (testing label
      (with-temp-root [root]
        (let [archive
              (create-release-archive!
               root
               (assoc default-schemas "api.schema.json" content))
              output-dir (io/file root "output")
              sentinel (io/file root "sentinel.txt")]
          (spit sentinel "preserve")
          (let [{:keys [exit out err]}
                (run-local-fetch archive (sha256-file archive) output-dir)]
            (is (not (zero? exit)))
            (is (str/includes?
                 (str out err)
                 expected-message))
            (is (.exists sentinel))
            (is (= "preserve" (slurp sentinel)))))))))

(deftest identifies-the-member-containing-malformed-json
  (doseq [[label content]
          [["truncated" "{"]
           ["trailing token" "{\"title\":\"API\"} trailing"]
           ["second document" "{\"title\":\"API\"}{\"title\":\"extra\"}"]]]
    (testing label
      (with-temp-root [root]
        (let [archive
              (create-release-archive!
               root
               (assoc default-schemas "api.schema.json" content))
              output-dir (io/file root "output")]
          (assert-fetch-failure!
           (run-local-fetch archive (sha256-file archive) output-dir)
           output-dir
           "Invalid JSON in package/schemas/api.schema.json"))))))

(deftest rejects-schema-content-with-malformed-utf8
  (with-temp-root [root]
    (let [archive (io/file root "release.tgz")
          schema-file (io/file root "package" "schemas" "api.schema.json")
          output-dir (io/file root "output")]
      (write-release-tree! root fixture-version default-schemas)
      (with-open [output (io/output-stream schema-file)]
        (.write output
                (.getBytes "{\"title\":\""
                           java.nio.charset.StandardCharsets/UTF_8))
        (.write output 255)
        (.write output
                (.getBytes "\"}\n"
                           java.nio.charset.StandardCharsets/UTF_8)))
      (create-tar! archive ["-C" (.getPath root) "package"])
      (assert-fetch-failure!
       (run-local-fetch archive (sha256-file archive) output-dir)
       output-dir
       "Invalid UTF-8 in package/schemas/api.schema.json"))))

(deftest rejects-blank-output-without-deleting-the-working-directory
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          sentinel (io/file root "sentinel.txt")]
      (spit sentinel "preserve")
      (let [{:keys [exit out err]}
            (run-local-fetch archive (sha256-file archive) ""
                             {:dir root})]
        (is (not (zero? exit)))
        (is (str/includes?
             (str out err)
             "COPILOT_CLI_SCHEMA_OUTPUT must be non-blank"))
        (is (.exists sentinel))
        (is (= "preserve" (slurp sentinel)))))))

(deftest rejects-existing-output-without-deleting-it
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          sentinel (io/file output-dir "sentinel.txt")]
      (.mkdirs output-dir)
      (spit sentinel "preserve")
      (let [{:keys [exit out err]}
            (run-local-fetch archive (sha256-file archive) output-dir)]
        (is (not (zero? exit)))
        (is (str/includes?
             (str out err)
             "COPILOT_CLI_SCHEMA_OUTPUT must not already exist"))
        (is (= "preserve" (slurp sentinel)))))))

(deftest rejects-filesystem-root-output
  (let [root (first (java.io.File/listRoots))
        {:keys [exit out err]}
        (run-fetch
         {:env {"COPILOT_CLI_SCHEMA_OUTPUT" (.getPath root)}})]
    (is (not (zero? exit)))
    (is (str/includes?
         (str out err)
         "COPILOT_CLI_SCHEMA_OUTPUT must not already exist"))))

(deftest rejects-current-working-directory-output
  (with-temp-root [root]
    (let [{:keys [exit out err]}
          (run-fetch
           {:dir root
            :env {"COPILOT_CLI_SCHEMA_OUTPUT" "."}})]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "COPILOT_CLI_SCHEMA_OUTPUT must not already exist")))))

(deftest rejects-repository-and-ancestor-outputs
  (doseq [output [repository-root (.getParentFile repository-root)]]
    (testing (.getPath output)
      (let [{:keys [exit out err]}
            (run-fetch
             {:env {"COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output)}})]
        (is (not (zero? exit)))
        (is (str/includes?
             (str out err)
             "COPILOT_CLI_SCHEMA_OUTPUT must not already exist"))))))

(deftest rejects-working-directory-ancestor-output
  (with-temp-root [root]
    (let [working-dir (io/file root "working")
          sentinel (io/file root "sentinel.txt")]
      (.mkdirs working-dir)
      (spit sentinel "preserve")
      (let [{:keys [exit out err]}
            (run-fetch
             {:dir working-dir
              :env {"COPILOT_CLI_SCHEMA_OUTPUT" ".."}})]
        (is (not (zero? exit)))
        (is (str/includes?
             (str out err)
             "COPILOT_CLI_SCHEMA_OUTPUT must not already exist"))
        (is (= "preserve" (slurp sentinel)))))))

(deftest supports-relative-output-paths
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          {:keys [exit err]}
          (run-local-fetch archive (sha256-file archive) "output"
                           {:dir root})]
      (is (zero? exit) err)
      (when (zero? exit)
        (is (= {"title" "API"}
               (json/read-str
                (slurp (io/file output-dir "api.schema.json")))))))))

(deftest creates-missing-output-parent-directories
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "nested" "output")
          {:keys [exit err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (zero? exit) err)
      (when (zero? exit)
        (is (= {"title" "API"}
               (json/read-str
                (slurp (io/file output-dir "api.schema.json")))))))))

(deftest downloads-release-manifest-and-archive
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          hash (sha256-file archive)
          release-base
          (create-release-download!
           root archive (str hash "  " fixture-asset-name "\n"))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (zero? exit) err)
      (when (zero? exit)
        (is (str/includes? out "Verified release mirror asset"))
        (is (str/includes?
             (slurp (io/file output-dir "README.md"))
             "release mirror"))
        (is (not (str/includes?
                  (slurp (io/file output-dir "README.md"))
                  "fetched verbatim from the")))))))

(deftest accepts-standard-checksum-manifest-variants
  (doseq [[label checksum-line]
          [["binary-mode filename"
            (fn [hash] (str hash " *" fixture-asset-name "\n"))]
           ["uppercase hash"
            (fn [hash]
              (str (str/upper-case hash) "  " fixture-asset-name "\n"))]]]
    (testing label
      (with-temp-root [root]
        (let [archive (create-release-archive! root)
              release-base
              (create-release-download!
               root archive (checksum-line (sha256-file archive)))
              output-dir (io/file root "output")
              {:keys [exit err]}
              (run-fetch
               {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                      "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
          (is (zero? exit) err)
          (is (.exists (io/file output-dir "api.schema.json"))))))))

(deftest rejects-download-checksum-without-local-archive
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          hash (sha256-file archive)
          release-base
          (create-release-download!
           root archive (str hash "  " fixture-asset-name "\n"))
          output-dir (io/file root "output")
          result
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_RELEASE_SHA256" hash
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (assert-fetch-failure!
       result
       output-dir
       "COPILOT_CLI_RELEASE_SHA256 requires COPILOT_CLI_RELEASE_TARBALL"))))

(deftest identifies-missing-local-archive-override
  (with-temp-root [root]
    (let [missing (io/file root "missing.tgz")
          output-dir (io/file root "output")
          result
          (run-local-fetch
           missing
           (apply str (repeat 64 "0"))
           output-dir)]
      (assert-fetch-failure!
       result
       output-dir
       "COPILOT_CLI_RELEASE_TARBALL must name an existing file")
      (is (str/includes? (str (:out result) (:err result))
                         (.getPath missing))))))

(deftest reports-release-download-failures
  (with-temp-root [root]
    (let [release-base (str "file://"
                            (.getCanonicalPath (io/file root "missing")))
          output-dir (io/file root "output")
          result
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (assert-fetch-failure! result output-dir "Download failed:")
      (is (str/includes? (str (:out result) (:err result))
                         "SHA256SUMS.txt")))))

(deftest validates-checksum-manifest-before-downloading-archive
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          release-base
          (create-release-download!
           root archive (str "not-a-sha256  " fixture-asset-name "\n"))
          release-archive
          (io/file root "download" (str "v" fixture-version)
                   fixture-asset-name)
          output-dir (io/file root "output")]
      (is (.delete release-archive))
      (assert-fetch-failure!
       (run-fetch
        {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
               "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})
       output-dir
       "SHA256SUMS.txt contains an invalid entry"))))

(deftest rejects-invalid-checksum-manifests
  (doseq [[label checksums-fn expected-message]
          [["missing release asset"
            (fn [archive]
              (str (sha256-file archive) "  another-asset.tgz\n"))
            "SHA256SUMS.txt does not contain"]
           ["invalid entry"
            (constantly
             (str "not-a-sha256  " fixture-asset-name "\n"))
            "SHA256SUMS.txt contains an invalid entry"]
           ["conflicting entries"
            (fn [archive]
              (str (sha256-file archive) "  " fixture-asset-name "\n"
                   (apply str (repeat 64 "0")) "  "
                   fixture-asset-name "\n"))
            "SHA256SUMS.txt contains conflicting entries"]]]
    (testing label
      (with-temp-root [root]
        (let [archive (create-release-archive! root)
              release-base
              (create-release-download! root archive (checksums-fn archive))
              output-dir (io/file root "output")
              result
              (run-fetch
               {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                      "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
          (assert-fetch-failure! result output-dir expected-message))))))

(deftest rejects-insecure-release-download-base-url
  (with-temp-root [root]
    (let [output-dir (io/file root "output")
          result
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL"
                  "http://127.0.0.1:1/releases/download"
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (assert-fetch-failure!
       result
       output-dir
       "COPILOT_CLI_DOWNLOAD_BASE_URL must use https:// or file://"))))

(deftest preserves-command-diagnostics
  (with-temp-root [root]
    (let [archive (io/file root "not-an-archive.tgz")
          output-dir (io/file root "output")]
      (spit archive "not an archive")
      (let [{:keys [exit out err]}
            (run-local-fetch archive (sha256-file archive) output-dir)
            output (str out err)]
        (is (not (zero? exit)))
        (is (str/includes? output "Could not inspect"))
        (is (str/includes? output ":stderr"))
        (is (str/includes? output ":exit"))))))

(deftest reads-the-pinned-version-when-version-is-omitted
  (with-temp-root [root]
    (let [version (str/trim (slurp ".copilot-schema-version"))
          archive (create-release-archive! root version default-schemas)
          output-dir (io/file root "output")
          {:keys [exit err]}
          (run-local-fetch archive (sha256-file archive) output-dir
                           {:args []})]
      (is (zero? exit) err)
      (is (.exists (io/file output-dir "api.schema.json"))))))

(deftest replaces-an-existing-schema-directory
  (with-temp-root [root]
    (let [staging-dir (io/file root "staging")
          output-dir (io/file root "schemas")
          stale-file (io/file output-dir "stale.schema.json")
          expected-permissions
          (PosixFilePermissions/fromString "rwxr-x---")]
      (.mkdirs staging-dir)
      (.mkdirs output-dir)
      (spit (io/file staging-dir "api.schema.json") "{\"title\":\"API\"}\n")
      (spit stale-file "{\"title\":\"Stale\"}\n")
      (Files/setPosixFilePermissions (.toPath output-dir)
                                     expected-permissions)
      (let [{:keys [exit err]}
            (run-script-eval
             (format
              (str "(let [install! (ns-resolve "
                   "'codegen.fetch-schemas 'install-schemas!)] "
                   "((deref install!) %s %s true))")
              (pr-str (.getPath staging-dir))
              (pr-str (.getPath output-dir))))]
        (is (zero? exit) err)
        (is (not (.exists stale-file)))
        (is (= {"title" "API"}
               (json/read-str
                (slurp (io/file output-dir "api.schema.json")))))
        (is (= expected-permissions
               (Files/getPosixFilePermissions
                (.toPath output-dir)
                (make-array java.nio.file.LinkOption 0))))))))

(deftest cleanup-failures-are-visible-without-changing-the-primary-outcome
  (let [success
        (run-script-eval
         (str
          "(let [cleanup (ns-resolve "
          "'codegen.fetch-schemas 'with-delete-tree-cleanup)] "
          "(with-redefs [babashka.fs/exists? (constantly true) "
          "babashka.fs/delete-tree "
          "(fn [_] (throw (ex-info \"cleanup failed\" {})))] "
          "(println ((deref cleanup) \"victim\" (constantly :installed)))))"))
        failure
        (run-script-eval
         (str
          "(let [cleanup (ns-resolve "
          "'codegen.fetch-schemas 'with-delete-tree-cleanup)] "
          "(with-redefs [babashka.fs/exists? (constantly true) "
          "babashka.fs/delete-tree "
          "(fn [_] (throw (ex-info \"cleanup failed\" {})))] "
          "((deref cleanup) \"victim\" "
          "(fn [] (throw (ex-info \"primary failed\" {}))))))"))]
    (is (zero? (:exit success)) (:err success))
    (is (str/includes? (:out success) ":installed"))
    (is (str/includes?
         (str (:out success) (:err success))
         "WARNING: could not remove victim: cleanup failed"))
    (is (not (zero? (:exit failure))))
    (is (str/includes?
         (str (:out failure) (:err failure))
         "primary failed"))
    (is (str/includes?
         (str (:out failure) (:err failure))
         "WARNING: could not remove victim: cleanup failed"))))

(deftest reports-usage-errors-with-exit-code-two
  (doseq [[label args expected-message]
          [["unknown argument" ["--bogus"] "Unknown argument: --bogus"]
           ["missing version" ["--version"]
            "--version requires a non-blank value"]
           ["blank version" ["--version" ""]
            "--version requires a non-blank value"]]]
    (testing label
      (let [{:keys [exit out err]} (run-fetch {:args args})
            output (str out err)]
        (is (= 2 exit))
        (is (str/includes? output expected-message))
        (is (str/includes?
             output
             "Usage: bb schemas:fetch [--version VERSION]"))))))
