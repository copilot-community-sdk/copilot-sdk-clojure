(ns github.copilot-sdk.schema-fetch-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security MessageDigest)
           (java.util Arrays)))

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

(defn- create-release-archive-with-duplicate-member!
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
  (create-release-archive-with-duplicate-member!
   root
   (str "./" member)))

(defn- tar-flavor
  []
  (let [{:keys [exit out err]} (sh/sh "tar" "--version")
        output (str/lower-case (str out err))]
    (when-not (zero? exit)
      (throw (ex-info "Could not identify tar implementation"
                      {:exit exit :output output})))
    (cond
      (str/includes? output "gnu tar") :gnu
      (str/includes? output "bsdtar") :bsd
      :else
      (throw (ex-info "Unsupported tar implementation"
                      {:output output})))))

(defn- create-release-archive-with-traversal-member!
  [root]
  (let [archive (io/file root "release.tgz")
        source-name "traversal-entry"
        member-name "package/schemas/../evil.schema.json"
        transform
        (case (tar-flavor)
          :gnu ["--transform"
                (str "s|^" source-name "$|" member-name "|")]
          :bsd ["-s"
                (str ",^" source-name "$," member-name ",")])]
    (write-release-tree! root fixture-version default-schemas)
    (spit (io/file root source-name) "{\"title\":\"unexpected\"}\n")
    (create-tar!
     archive
     (into transform
           ["-C" (.getPath root) "package" source-name]))))

(defn- same-bytes?
  [expected file]
  (Arrays/equals
   (.getBytes ^String expected StandardCharsets/UTF_8)
   (Files/readAllBytes (.toPath file))))

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
  ([form]
   (run-script-eval form {}))
  ([form env]
   (let [clean-env
         (apply dissoc (into {} (System/getenv)) fetch-env-vars)]
     (sh/sh "bb" "-e"
            (str "(load-file " (pr-str script-path) ")\n" form)
            :env (merge clean-env env)))))

(defn- private-var-form
  [symbol]
  (format "(deref (ns-resolve 'codegen.fetch-schemas '%s))" symbol))

(defn- private-call-form
  [symbol & arguments]
  (str "(" (private-var-form symbol)
       (when (seq arguments) " ")
       (str/join " " arguments)
       ")"))

(defn- run-script-eval-with-umask
  [root mask form]
  (let [script (io/file root "eval-with-umask.clj")]
    (spit script (str "(load-file " (pr-str script-path) ")\n" form))
    (sh/sh "sh" "-c"
           "umask \"$1\"; exec bb \"$2\""
           "schema-fetch-test"
           mask
           (.getPath script))))

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
        (is (same-bytes?
             (get default-schemas "api.schema.json")
             (io/file output-dir "api.schema.json")))
        (is (same-bytes?
             (get default-schemas "session-events.schema.json")
             (io/file output-dir "session-events.schema.json")))
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
            #(create-release-archive-with-duplicate-member!
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
          (create-release-archive-with-duplicate-member!
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

(deftest rejects-nested-schema-members
  (with-temp-root [root]
    (let [archive (io/file root "release.tgz")
          output-dir (io/file root "output")
          member-file
          (io/file root "package" "schemas" "nested" "future.schema.json")]
      (write-release-tree! root fixture-version default-schemas)
      (.mkdirs (.getParentFile member-file))
      (spit member-file "{\"title\":\"unexpected\"}\n")
      (create-tar! archive ["-C" (.getPath root) "package"])
      (assert-fetch-failure!
       (run-local-fetch archive (sha256-file archive) output-dir)
       output-dir
       "Nested schema member is not supported: package/schemas/nested/future.schema.json"))))

(deftest rejects-traversal-shaped-schema-members
  (with-temp-root [root]
    (let [archive (create-release-archive-with-traversal-member! root)
          output-dir (io/file root "output")]
      (assert-fetch-failure!
       (run-local-fetch archive (sha256-file archive) output-dir)
       output-dir
       "Non-canonical archive member: package/schemas/../evil.schema.json"))))

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

(deftest rejects-invalid-utf8-archive-listings
  (let [{:keys [exit out err]}
        (run-script-eval
         (str
          "(let [run! (ns-resolve "
          "'codegen.fetch-schemas 'run-bounded-command-output) "
          "list! " (private-var-form 'list-archive-members) "] "
          "(with-redefs-fn "
          "{run! (fn [& _] (byte-array [(unchecked-byte 255)]))} "
          "#(list! \"fixture.tgz\")))"))]
    (is (not (zero? exit)))
    (is (str/includes? (str out err)
                       "Invalid UTF-8 in archive member listing"))))

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
        (is (str/includes? output "Archive listing for"))
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

(deftest resource-limits-reject-oversized-files-and-command-output
  (with-temp-root [root]
    (let [oversized (io/file root "oversized")
          output-pid-file (io/file root "overflow-pid")
          _ (spit oversized "12345")
          file-result
          (run-script-eval
           (private-call-form
            'ensure-file-size!
            (pr-str (.getPath oversized))
            "4"
            "\"fixture\""))
          output-result
          (run-script-eval
           (private-call-form
            'run-bounded-command-output
            (pr-str
             ["sh" "-c"
              (format
               "printf '%%s' \"$$\" > %s; printf 12345; while :; do sleep 1; done"
               (pr-str (.getPath output-pid-file)))])
            "4"
            "2"
            "\"fixture\""))
          output-pid (Long/parseLong (slurp output-pid-file))
          output-handle
          (.orElse (java.lang.ProcessHandle/of output-pid) nil)]
      (is (not (zero? (:exit file-result))))
      (is (str/includes?
           (str (:out file-result) (:err file-result))
           "fixture exceeds 4 bytes"))
      (is (not (zero? (:exit output-result))))
      (is (str/includes?
           (str (:out output-result) (:err output-result))
           "fixture output exceeds 4 bytes"))
      (is (or (nil? output-handle)
              (not (.isAlive output-handle)))))))

(deftest bounded-output-supports-byte-array-writes
  (let [{:keys [exit out err]}
        (run-script-eval
         (str
          "(let [make-output " (private-var-form 'bounded-output)
          "\n      output (make-output 4 (constantly nil))"
          "\n      stream (:stream output)]"
          "\n  (.write stream (.getBytes \"1234\" java.nio.charset.StandardCharsets/UTF_8))"
          "\n  (println (String. (:bytes ((:snapshot output)))"
          " java.nio.charset.StandardCharsets/UTF_8)))"))]
    (is (zero? exit) err)
    (is (= "1234\n" out))))

(deftest bounded-command-output-times-out-and-reaps-process
  (with-temp-root [root]
    (let [pid-file (io/file root "pid")
          command
          (format
           (str "trap '' TERM; "
                "sh -c 'trap \"\" TERM; while :; do sleep 1; done' & "
                "child=$!; printf '%%s %%s' \"$$\" \"$child\" > %s; "
                "wait \"$child\"")
           (pr-str (.getPath pid-file)))
          {:keys [exit err]}
          (run-script-eval
           (private-call-form
            'run-bounded-command-output
            (pr-str ["sh" "-c" command])
            "1024"
            "1"
            "\"fixture\""))
          pids (mapv parse-long (str/split (slurp pid-file) #" "))
          handles
          (mapv #(.orElse (java.lang.ProcessHandle/of %) nil) pids)]
      (is (not (zero? exit)))
      (is (str/includes? err "fixture timed out after 1 seconds"))
      (is (every? #(or (nil? %) (not (.isAlive %))) handles)))))

(deftest bounded-output-overflow-precedes-timeout-and-reaps-process
  (with-temp-root [root]
    (let [pid-file (io/file root "pid")
          command
          (format
           (str "trap '' TERM; "
                "sh -c 'trap \"\" TERM; while :; do sleep 1; done' & "
                "child=$!; printf '%%s %%s' \"$$\" \"$child\" > %s; "
                "printf 12345; wait \"$child\"")
           (pr-str (.getPath pid-file)))
          {:keys [exit err]}
          (run-script-eval
           (private-call-form
            'run-bounded-command-output
            (pr-str ["sh" "-c" command])
            "4"
            "1"
            "\"fixture\""))
          pids (mapv parse-long (str/split (slurp pid-file) #" "))
          handles
          (mapv #(.orElse (java.lang.ProcessHandle/of %) nil) pids)]
      (is (not (zero? exit)))
      (is (str/includes? err "fixture output exceeds 4 bytes"))
      (is (not (str/includes? err "fixture timed out")))
      (is (every? #(or (nil? %) (not (.isAlive %))) handles)))))

(deftest bounded-stderr-overflow-precedes-timeout-and-reaps-process
  (with-temp-root [root]
    (let [pid-file (io/file root "pid")
          command
          (format
           (str "trap '' TERM; "
                "printf '%%s' \"$$\" > %s; "
                "printf 12345 >&2; while :; do sleep 1; done")
           (pr-str (.getPath pid-file)))
          {:keys [exit err]}
          (run-script-eval
           (str
            "(let [limit (or (ns-resolve "
            "'codegen.fetch-schemas 'max-command-stderr-bytes) "
            "(intern 'codegen.fetch-schemas 'max-command-stderr-bytes 4)) "
            "run! " (private-var-form 'run-bounded-command-output) "] "
            "(with-redefs-fn {limit 4} "
            "#(run! " (pr-str ["sh" "-c" command])
            " 1024 1 \"fixture\")))"))
          pid (parse-long (slurp pid-file))
          handle (.orElse (java.lang.ProcessHandle/of pid) nil)]
      (is (not (zero? exit)))
      (is (str/includes? err "fixture stderr exceeds 4 bytes"))
      (is (not (str/includes? err "fixture timed out")))
      (is (or (nil? handle) (not (.isAlive handle)))))))

(deftest interrupted-command-wait-reaps-process
  (with-temp-root [root]
    (let [pid-file (io/file root "pid")
          command
          (format "printf '%%s' \"$$\" > %s; while :; do sleep 1; done"
                  (pr-str (.getPath pid-file)))
          {:keys [exit out err]}
          (run-script-eval
           (str
            "(let [run! " (private-var-form 'run-bounded-command-output)
            "\n      failure (atom nil)"
            "\n      runner"
            "\n      (Thread."
            "\n       (fn []"
            "\n         (try"
            "\n           (run! " (pr-str ["sh" "-c" command])
            " 1024 30 \"fixture\")"
            "\n           (catch Throwable error"
            "\n             (reset! failure error)))))"
            "\n      deadline (+ (System/nanoTime) 5000000000)]"
            "\n  (.start runner)"
            "\n  (loop []"
            "\n    (when (and (not (babashka.fs/exists? "
            (pr-str (.getPath pid-file)) "))"
            "\n               (< (System/nanoTime) deadline))"
            "\n      (Thread/sleep 10)"
            "\n      (recur)))"
            "\n  (when-not (babashka.fs/exists? "
            (pr-str (.getPath pid-file)) ")"
            "\n    (throw (ex-info \"process did not start\" {})))"
            "\n  (.interrupt runner)"
            "\n  (.join runner 5000)"
            "\n  (println (some-> @failure class .getName))"
            "\n  (println (.isAlive runner)))"))
          pid (parse-long (slurp pid-file))
          handle (.orElse (java.lang.ProcessHandle/of pid) nil)
          process-alive? (and handle (.isAlive handle))]
      (try
        (is (zero? exit) err)
        (is (str/includes? out "java.lang.InterruptedException"))
        (is (str/includes? out "false"))
        (is (not process-alive?))
        (finally
          (when process-alive?
            (.destroyForcibly handle)
            (.get (.onExit handle))))))))

(deftest bounded-output-preserves-termination-diagnostics
  (let [{:keys [exit out err]}
        (run-script-eval
         (str
          "(let [terminate-var (ns-resolve "
          "'codegen.fetch-schemas 'terminate-process-tree!) "
          "run! " (private-var-form 'run-bounded-command-output) "] "
          "(with-redefs-fn "
          "{terminate-var (fn [_] (throw (ex-info \"cleanup failed\" {})))} "
          "(fn [] "
          " (try "
          "   (run! [\"sh\" \"-c\" \"printf 12345\"] 4 2 \"fixture\") "
          "   (catch Throwable error "
          "     (println (.getMessage error)) "
          "     (println (mapv (fn [suppressed] (.getMessage suppressed)) "
          "                    (.getSuppressed error))))))))"))]
    (is (zero? exit) err)
    (is (str/includes? out "fixture output exceeds 4 bytes"))
    (is (str/includes? out "cleanup failed"))))

(deftest local-archive-snapshot-is-bounded-during-copy
  (with-temp-root [root]
    (let [source (io/file root "source.tgz")
          destination (io/file root "snapshot.tgz")
          _ (spit source "12345")
          {:keys [exit err]}
          (run-script-eval
           (str
            "(let [limit (ns-resolve "
            "'codegen.fetch-schemas 'max-release-archive-bytes) "
            "snapshot! " (private-var-form 'snapshot-local-archive!) "] "
            "(with-redefs-fn {limit 4} "
            "#(snapshot! " (pr-str (.getPath source)) " "
            (pr-str (.getPath destination)) ")))"))]
      (is (not (zero? exit)))
      (is (str/includes? err "Local release archive exceeds 4 bytes"))
      (is (<= (.length destination) 4)))))

(deftest local-archive-resolution-uses-the-bounded-snapshot
  (with-temp-root [root]
    (let [source (io/file root "source.tgz")
          tmp (io/file root "tmp")
          _ (.mkdirs tmp)
          _ (spit source "12345")
          {:keys [exit err]}
          (run-script-eval
           (str
            "(let [limit (ns-resolve "
            "'codegen.fetch-schemas 'max-release-archive-bytes) "
            "resolve! " (private-var-form 'resolve-release-archive!) "] "
            "(with-redefs-fn {limit 4} "
            "#(resolve! " (pr-str fixture-version) " "
            (pr-str (.getPath tmp)) ")))")
           {"COPILOT_CLI_RELEASE_TARBALL" (.getPath source)
            "COPILOT_CLI_RELEASE_SHA256" (apply str (repeat 64 "0"))})]
      (is (not (zero? exit)))
      (is (str/includes? err "Local release archive exceeds 4 bytes"))
      (is (<= (.length (io/file tmp "local-release.tgz")) 4)))))

(deftest download-enforces-curl-file-size-limit
  (with-temp-root [root]
    (let [source (io/file root "source.bin")
          destination (io/file root "download.bin")
          _ (spit source "12345")
          {:keys [exit err]}
          (run-script-eval
           (private-call-form
            'download!
            (pr-str (str (.toURI source)))
            (pr-str (.getPath destination))
            "4"))]
      (is (not (zero? exit)))
      (is (str/includes? err "Download failed:")))))

(deftest production-extraction-path-enforces-resource-limits
  (with-temp-root [root]
    (let [archive (create-release-archive! root)]
      (doseq [[limit-symbol expected-message]
              [['max-archive-listing-bytes
                "Archive listing for"]
               ['max-package-json-bytes
                "package/package.json extraction output exceeds 4 bytes"]
               ['max-schema-bytes
                "package/schemas/api.schema.json extraction output exceeds 4 bytes"]]]
        (let [staging-dir (io/file root (name limit-symbol))
              {:keys [exit out err]}
              (run-script-eval
               (str
                "(let [limit (ns-resolve 'codegen.fetch-schemas '"
                limit-symbol ") "
                "prepare! " (private-var-form 'prepare-staged-schemas!) "] "
                "(with-redefs-fn {limit 4} "
                "#(prepare! " (pr-str (.getPath archive)) " "
                (pr-str (.getPath staging-dir))
                " :local-override \"fixture.tgz\" "
                (pr-str fixture-version) ")))"))]
          (is (not (zero? exit)) (name limit-symbol))
          (is (str/includes? (str out err) expected-message)
              (name limit-symbol)))))))

(deftest rejects-archive-listings-over-the-member-limit
  (let [{:keys [exit out err]}
        (run-script-eval
         (str
          "(let [limit (ns-resolve "
          "'codegen.fetch-schemas 'max-archive-members) "
          "parse! (ns-resolve "
          "'codegen.fetch-schemas 'parse-archive-members)] "
          "(with-redefs-fn {limit 2} "
          "#((deref parse!) \"fixture.tgz\" \"a\\nb\\nc\\n\")))"))]
    (is (not (zero? exit)))
    (is (str/includes? (str out err)
                       "fixture.tgz contains more than 2 members"))))

(deftest fresh-schema-directory-honors-the-process-umask
  (with-temp-root [root]
    (let [staging-dir (io/file root "staging")
          output-dir (io/file root "schemas")
          expected-permissions
          (PosixFilePermissions/fromString "rwxr-xr-x")]
      (.mkdirs staging-dir)
      (spit (io/file staging-dir "api.schema.json") "{\"title\":\"API\"}\n")
      (let [{:keys [exit err]}
            (run-script-eval-with-umask
             root
             "022"
             (private-call-form
              'install-schemas!
              (pr-str (.getPath staging-dir))
              (pr-str (.getPath output-dir))
              "false"))]
        (is (zero? exit) err)
        (when (zero? exit)
          (is (= expected-permissions
                 (Files/getPosixFilePermissions
                  (.toPath output-dir)
                  (make-array java.nio.file.LinkOption 0)))))))))

(deftest create-only-install-refuses-a-destination-that-appears-during-copy
  (with-temp-root [root]
    (let [staging-dir (io/file root "staging")
          output-dir (io/file root "schemas")
          sentinel (io/file output-dir "sentinel.txt")]
      (.mkdirs staging-dir)
      (spit (io/file staging-dir "api.schema.json") "{\"title\":\"API\"}\n")
      (let [{:keys [exit out err]}
            (run-script-eval
             (str
              "(let [copy-tree-var (ns-resolve 'babashka.fs 'copy-tree)"
              "\n      copy-tree (deref copy-tree-var)"
              "\n      install! " (private-var-form 'install-schemas!) "]"
              "\n  (with-redefs-fn"
              "\n    {copy-tree-var"
              "\n     (fn [& args]"
              "\n       (apply copy-tree args)"
              "\n       (babashka.fs/create-dirs " (pr-str (.getPath output-dir)) ")"
              "\n       (spit " (pr-str (.getPath sentinel)) " \"preserve\"))}"
              "\n    #(install! " (pr-str (.getPath staging-dir)) " "
              (pr-str (.getPath output-dir)) " false)))"))]
        (is (not (zero? exit)) (str out err))
        (is (= "preserve" (slurp sentinel)))
        (is (empty?
             (filter #(str/starts-with? (.getName %) ".copilot-schemas-")
                     (.listFiles root))))))))

(deftest prepared-directory-is-owned-before-permission-lookup
  (with-temp-root [root]
    (let [staging-dir (io/file root "staging")
          output-dir (io/file root "schemas")]
      (.mkdirs staging-dir)
      (spit (io/file staging-dir "api.schema.json") "{\"title\":\"API\"}\n")
      (let [{:keys [exit out err]}
            (run-script-eval
             (format
              (str "(let [install! (ns-resolve "
                   "'codegen.fetch-schemas 'install-schemas!) "
                   "permissions (ns-resolve "
                   "'codegen.fetch-schemas "
                   "'destination-directory-permissions)] "
                   "(with-redefs-fn "
                   "{permissions "
                   "(fn [_] (throw (ex-info \"permission failed\" {})))} "
                   "#((deref install!) %s %s false)))")
              (pr-str (.getPath staging-dir))
              (pr-str (.getPath output-dir))))]
        (is (not (zero? exit)))
        (is (str/includes? (str out err) "permission failed"))
        (is (empty?
             (filter #(str/starts-with? (.getName %) ".copilot-schemas-")
                     (.listFiles root))))))))

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
          "(fn [_] (throw (ex-info \"victim\" {})))] "
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
         "WARNING: could not remove victim: ExceptionInfo"))
    (is (not (zero? (:exit failure))))
    (is (str/includes?
         (str (:out failure) (:err failure))
         "primary failed"))
    (is (str/includes?
         (str (:out failure) (:err failure))
         "WARNING: could not remove victim: ExceptionInfo: cleanup failed"))))

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
