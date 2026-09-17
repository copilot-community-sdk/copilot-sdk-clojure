(ns github.copilot-sdk.schema-fetch-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.math BigInteger)
           (java.nio.file Files)
           (java.security MessageDigest)))

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
  [root include-events?]
  (let [schemas-dir (io/file root "package" "schemas")
        archive (io/file root "release.tgz")]
    (.mkdirs schemas-dir)
    (spit (io/file schemas-dir "api.schema.json") "{\"title\":\"API\"}\n")
    (when include-events?
      (spit (io/file schemas-dir "session-events.schema.json")
            "{\"title\":\"Events\"}\n"))
    (let [{:keys [exit err]}
          (sh/sh "tar" "-czf" (.getPath archive) "-C" (.getPath root) "package")]
      (when-not (zero? exit)
        (throw (ex-info "Could not create schema fixture archive"
                        {:exit exit :stderr err}))))
    archive))

(defn- run-fetch
  [archive expected-hash output-dir]
  (sh/sh "bb" "script/codegen/fetch_schemas.clj"
         "--version" "9.9.9"
         :env (merge (into {} (System/getenv))
                     {"COPILOT_CLI_RELEASE_TARBALL" (.getPath archive)
                      "COPILOT_CLI_RELEASE_SHA256" expected-hash
                      "COPILOT_CLI_SCHEMA_OUTPUT" (.getPath output-dir)})))

(deftest extracts-schemas-from-verified-release-archive
  (with-temp-root [root]
    (let [archive (create-release-archive! root true)
          output-dir (io/file root "output")
          {:keys [exit err]} (run-fetch archive (sha256-file archive) output-dir)]
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
             "github-copilot-9.9.9-linux-x64.tgz"))))))

(deftest rejects-release-archive-with-wrong-checksum
  (with-temp-root [root]
    (let [archive (create-release-archive! root true)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch archive (apply str (repeat 64 "0")) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes? (str out err) "Integrity verification failed"))
      (is (not (.exists output-dir))))))

(deftest requires-both-schema-files
  (with-temp-root [root]
    (let [archive (create-release-archive! root false)
          output-dir (io/file root "output")
          {:keys [exit out err]}
          (run-fetch archive (sha256-file archive) output-dir)]
      (is (not (zero? exit)))
      (is (str/includes?
           (str out err)
           "must contain exactly one package/schemas/session-events.schema.json"))
      (is (not (.exists output-dir))))))
