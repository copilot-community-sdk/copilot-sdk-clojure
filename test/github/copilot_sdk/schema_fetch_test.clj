(ns github.copilot-sdk.schema-fetch-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.math BigInteger)
           (java.nio.file Files)
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

(defn- create-release-archive!
  ([root]
   (create-release-archive! root fixture-version default-schemas))
  ([root schemas]
   (create-release-archive! root fixture-version schemas))
  ([root version schemas]
   (let [schemas-dir (io/file root "package" "schemas")
         archive (io/file root "release.tgz")]
     (.mkdirs schemas-dir)
     (spit (io/file root "package" "package.json")
           (json/write-str {"name" "@github/copilot"
                            "version" version}))
     (doseq [[schema-name content] schemas]
       (spit (io/file schemas-dir schema-name) content))
     (let [{:keys [exit err]}
           (sh/sh "tar" "-czf" (.getPath archive) "-C" (.getPath root) "package")]
       (when-not (zero? exit)
         (throw (ex-info "Could not create schema fixture archive"
                         {:exit exit :stderr err}))))
     archive)))

(defn- run-fetch
  [{:keys [args dir env]
    :or {args ["--version" fixture-version]}}]
  (let [clean-env (apply dissoc (into {} (System/getenv)) fetch-env-vars)
        command (cond-> (into ["bb" script-path] args)
                  true (conj :env (merge clean-env env))
                  dir (conj :dir (str dir)))]
    (apply sh/sh command)))

(defn- run-local-fetch
  ([archive expected-hash output-dir]
   (run-local-fetch archive expected-hash output-dir {}))
  ([archive expected-hash output-dir {:keys [dir env]}]
   (run-fetch
    {:dir dir
     :env (merge (cond-> {"COPILOT_CLI_RELEASE_TARBALL" (str archive)
                          "COPILOT_CLI_SCHEMA_OUTPUT" (str output-dir)}
                   expected-hash
                   (assoc "COPILOT_CLI_RELEASE_SHA256" expected-hash))
                 env)})))

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
          {:keys [exit out err]}
          (run-local-fetch archive nil output-dir)]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "Missing or invalid SHA-256 for release.tgz"))
      (is (not (.exists output-dir))))))

(deftest rejects-release-archive-with-wrong-checksum
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-local-fetch archive (apply str (repeat 64 "0")) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes? (str out err) "Integrity verification failed"))
      (is (not (.exists output-dir))))))

(deftest rejects-release-archive-with-mismatched-version
  (with-temp-root [root]
    (let [archive (create-release-archive! root "8.8.8" default-schemas)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "Archive version mismatch: expected 9.9.9, got 8.8.8"))
      (is (not (.exists output-dir))))))

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
          {:keys [exit out err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "must contain exactly one package/schemas/session-events.schema.json"))
      (is (not (.exists output-dir))))))

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
  (doseq [[label content] [["empty" ""]
                           ["whitespace" " \n\t"]
                           ["null" "null\n"]]]
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
                 "package/schemas/api.schema.json must contain a JSON object"))
            (is (.exists sentinel))
            (when (.exists sentinel)
              (is (= "preserve" (slurp sentinel))))))))))

(deftest identifies-the-member-containing-malformed-json
  (with-temp-root [root]
    (let [archive
          (create-release-archive!
           root
           (assoc default-schemas "api.schema.json" "{"))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-local-fetch archive (sha256-file archive) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "Invalid JSON in package/schemas/api.schema.json"))
      (is (not (.exists output-dir))))))

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
        (when (.exists sentinel)
          (is (= "preserve" (slurp sentinel))))))))

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

(deftest reports-release-download-failures
  (with-temp-root [root]
    (let [release-base (str "file://"
                            (.getCanonicalPath (io/file root "missing")))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (not (zero? exit)))
      (is (str/includes? (str out err) "Download failed:"))
      (is (str/includes? (str out err) "SHA256SUMS.txt"))
      (is (not (.exists output-dir))))))

(deftest rejects-checksum-manifest-without-release-asset
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          release-base
          (create-release-download!
           root archive
           (str (sha256-file archive) "  another-asset.tgz\n"))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "SHA256SUMS.txt does not contain"))
      (is (not (.exists output-dir))))))

(deftest rejects-invalid-checksum-entry
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          release-base
          (create-release-download!
           root archive (str "not-a-sha256  " fixture-asset-name "\n"))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "SHA256SUMS.txt contains an invalid entry"))
      (is (not (.exists output-dir))))))

(deftest rejects-conflicting-checksum-entries
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          hash (sha256-file archive)
          release-base
          (create-release-download!
           root archive
           (str hash "  " fixture-asset-name "\n"
                (apply str (repeat 64 "0")) "  " fixture-asset-name "\n"))
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL" release-base
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "SHA256SUMS.txt contains conflicting entries"))
      (is (not (.exists output-dir))))))

(deftest rejects-insecure-release-download-base-url
  (with-temp-root [root]
    (let [output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch
           {:env {"COPILOT_CLI_DOWNLOAD_BASE_URL"
                  "http://127.0.0.1:1/releases/download"
                  "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)}})]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "COPILOT_CLI_DOWNLOAD_BASE_URL must use https:// or file://"))
      (is (not (.exists output-dir))))))

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
