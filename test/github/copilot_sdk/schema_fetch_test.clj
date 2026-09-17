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
   (create-release-archive! root default-schemas))
  ([root schemas]
   (let [schemas-dir (io/file root "package" "schemas")
         archive (io/file root "release.tgz")]
     (.mkdirs schemas-dir)
     (doseq [[schema-name content] schemas]
       (spit (io/file schemas-dir schema-name) content))
     (let [{:keys [exit err]}
           (sh/sh "tar" "-czf" (.getPath archive) "-C" (.getPath root) "package")]
       (when-not (zero? exit)
         (throw (ex-info "Could not create schema fixture archive"
                         {:exit exit :stderr err}))))
     archive)))

(defn- run-fetch
  [{:keys [dir env]}]
  (let [clean-env (apply dissoc (into {} (System/getenv)) fetch-env-vars)
        command (cond-> ["bb" script-path
                         "--version" fixture-version
                         :env (merge clean-env env)]
                  dir (conj :dir (str dir)))]
    (apply sh/sh command)))

(defn- run-local-fetch
  ([archive expected-hash output-dir]
   (run-local-fetch archive expected-hash output-dir {}))
  ([archive expected-hash output-dir {:keys [dir env]}]
   (run-fetch
    {:dir dir
     :env (merge
           {"COPILOT_CLI_RELEASE_TARBALL" (str archive)
            "COPILOT_CLI_RELEASE_SHA256" expected-hash
            "COPILOT_CLI_SCHEMA_OUTPUT" (str output-dir)}
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

(deftest rejects-release-archive-with-wrong-checksum
  (with-temp-root [root]
    (let [archive (create-release-archive! root)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-local-fetch archive (apply str (repeat 64 "0")) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes? (str out err) "Integrity verification failed"))
      (is (not (.exists output-dir))))))

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

(deftest rejects-non-object-schema-content-before-replacement
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
              sentinel (io/file output-dir "sentinel.txt")]
          (.mkdirs output-dir)
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
        (is (str/includes? out "Verified GitHub Release asset"))
        (is (str/includes?
             (slurp (io/file output-dir "README.md"))
             "GitHub Release"))))))

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
