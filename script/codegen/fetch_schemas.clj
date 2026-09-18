#!/usr/bin/env bb
;; Fetch the Copilot CLI GitHub Release package at the version pinned in
;; .copilot-schema-version, verify it against SHA256SUMS.txt, and copy its
;; canonical JSON schemas to schemas/.
;;
;; Usage:
;;   bb schemas:fetch                   ;; uses .copilot-schema-version
;;   bb schemas:fetch --version 1.0.86-0 ;; one-shot override
;;
;; Environment overrides:
;;   COPILOT_CLI_RELEASE_TARBALL     local archive used instead of downloading
;;   COPILOT_CLI_RELEASE_SHA256      required SHA-256 for the local archive
;;   COPILOT_CLI_DOWNLOAD_BASE_URL   HTTPS or file URL for a release mirror
;;   COPILOT_CLI_SCHEMA_OUTPUT       new output directory for isolated tests
;;
;; The fetched schemas are committed for reproducible offline builds.

(ns codegen.fetch-schemas
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io StringReader)
           (java.math BigInteger)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException
                             CodingErrorAction
                             StandardCharsets)
           (java.security MessageDigest)))

(def repo-root
  (-> *file* fs/parent fs/parent fs/parent fs/canonicalize str))

(def default-schemas-dir
  (str (fs/path repo-root "schemas")))

(def version-file
  (str (fs/path repo-root ".copilot-schema-version")))

(def schema-platform "linux-x64")

(def required-schema-names
  ["api.schema.json" "session-events.schema.json"])

(def default-release-base-url
  "https://github.com/github/copilot-cli/releases/download")

(def ^:private source-labels
  {:github-release "GitHub Release asset"
   :release-mirror "release mirror asset"
   :local-override "local archive override"})

(def usage
  "Usage: bb schemas:fetch [--version VERSION]")

(defn- env-value [name]
  (when-some [value (System/getenv name)]
    (when (str/blank? value)
      (throw (ex-info (str name " must be non-blank")
                      {:environment-variable name})))
    value))

(defn read-pinned-version []
  (-> (slurp version-file) str/trim))

(defn parse-args [args]
  (loop [acc {} [a & rst] args]
    (cond
      (nil? a) acc
      (= a "--version")
      (let [v (first rst)]
        (when (or (nil? v) (str/blank? v))
          (throw (ex-info "--version requires a non-blank value"
                          {::usage-error true})))
        (recur (assoc acc :version v) (rest rst)))
      :else
      (throw (ex-info (str "Unknown argument: " a)
                      {::usage-error true})))))

(defn- release-asset-name [version]
  (format "github-copilot-%s-%s.tgz" version schema-platform))

(defn- download! [url destination]
  (println (format "Fetching %s" url))
  (let [{:keys [exit err]}
        @(p/process ["curl"
                     "--fail"
                     "--silent"
                     "--show-error"
                     "--location"
                     "--proto" "=https,file"
                     "--proto-redir" "=https"
                     "--retry" "2"
                     "--retry-delay" "1"
                     "--connect-timeout" "30"
                     "--max-time" "600"
                     "-o" destination
                     url]
                    {:err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "Download failed: " url)
                      {:exit exit :stderr err}))))
  destination)

(defn- sha256-file [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [read-count (.read input buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- find-checksum [checksums asset-name]
  (let [entries
        (keep
         (fn [line]
           (let [[hash name] (str/split (str/trim line) #"\s+" 2)]
             (when (= asset-name (some-> name (str/replace #"^\*" "")))
               hash)))
         (str/split-lines checksums))]
    (when (empty? entries)
      (throw (ex-info (str "SHA256SUMS.txt does not contain " asset-name)
                      {:asset asset-name})))
    (when-let [invalid
               (some #(when-not (re-matches #"[0-9a-fA-F]{64}" %) %)
                     entries)]
      (throw (ex-info (str "SHA256SUMS.txt contains an invalid entry for "
                           asset-name)
                      {:asset asset-name :hash invalid})))
    (let [hashes (set (map str/lower-case entries))]
      (when (> (count hashes) 1)
        (throw (ex-info (str "SHA256SUMS.txt contains conflicting entries for "
                             asset-name)
                        {:asset asset-name :hashes (sort hashes)})))
      (first hashes))))

(defn- verify-sha256! [archive expected-hash asset-name]
  (when-not (re-matches #"[0-9a-fA-F]{64}" (or expected-hash ""))
    (throw (ex-info (str "Missing or invalid SHA-256 for " asset-name)
                    {:asset asset-name})))
  (let [actual-hash (sha256-file archive)
        normalized-expected-hash (str/lower-case expected-hash)]
    (when-not (= normalized-expected-hash actual-hash)
      (throw
       (ex-info
        (format
         "Integrity verification failed for %s: expected %s, got %s"
         asset-name normalized-expected-hash actual-hash)
        {:asset asset-name
         :expected normalized-expected-hash
         :actual actual-hash}))))
  archive)

(defn- validate-archive-member-name! [archive member]
  (let [path (str/replace member #"/$" "")
        segments (str/split path #"/" -1)]
    (when (or (str/blank? path)
              (str/starts-with? path "/")
              (str/includes? path "\\")
              (str/includes? path "//")
              (some #{"." ".."} segments))
      (throw (ex-info (str "Non-canonical archive member: " member)
                      {:archive archive :member member}))))
  member)

(defn- list-archive-members [archive]
  (let [{:keys [exit out err]}
        @(p/process ["tar" "-tzf" archive]
                    {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not inspect " archive)
                      {:exit exit :stderr err})))
    (let [members
          (->> (str/split-lines out)
               (remove str/blank?)
               vec)]
      (run! #(validate-archive-member-name! archive %) members)
      members)))

(defn- extract-archive-member [archive member]
  (let [{:keys [exit out err]}
        @(p/process ["tar" "-xOzf" archive member]
                    {:out :bytes :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not extract " member)
                      {:exit exit :stderr err})))
    out))

(defn- decode-utf8 [archive member content]
  (let [decoder
        (doto (.newDecoder StandardCharsets/UTF_8)
          (.onMalformedInput CodingErrorAction/REPORT)
          (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (str (.decode decoder (ByteBuffer/wrap content)))
      (catch CharacterCodingException error
        (throw (ex-info (str "Invalid UTF-8 in " member)
                        {:archive archive :member member}
                        error))))))

(defn- parse-json [archive member content]
  (let [text (decode-utf8 archive member content)]
    (try
      (with-open [reader (StringReader. text)]
        (let [documents (doall (take 2 (json/parsed-seq reader)))]
          (when-not (= 1 (count documents))
            (throw
             (ex-info (str member " must contain exactly one JSON document")
                      {:archive archive :member member})))
          (first documents)))
      (catch Exception error
        (throw (ex-info (str "Invalid JSON in " member)
                        {:archive archive :member member}
                        error))))))

(defn- verify-archive-version! [archive members expected-version]
  (let [member "package/package.json"
        match-count (count (filter #(= member %) members))]
    (when-not (= 1 match-count)
      (throw (ex-info
              (format "%s must contain exactly one %s" archive member)
              {:archive archive :member member})))
    (let [package (parse-json archive member
                              (extract-archive-member archive member))
          actual-version (get package "version")]
      (when-not (= expected-version actual-version)
        (throw (ex-info
                (format "Archive version mismatch: expected %s, got %s"
                        expected-version actual-version)
                {:archive archive
                 :expected expected-version
                 :actual actual-version})))
      (println (format "Verified archive package version: %s"
                       actual-version)))))

(def ^:private schema-member-prefix "package/schemas/")

(def ^:private schema-name-pattern
  #"[A-Za-z0-9][A-Za-z0-9._-]*\.json")

(defn- schema-entry [archive member]
  (when (str/starts-with? member schema-member-prefix)
    (let [schema-name (subs member (count schema-member-prefix))]
      (cond
        (or (str/blank? schema-name)
            (str/ends-with? schema-name "/"))
        nil

        (str/includes? schema-name "/")
        (throw (ex-info (str "Nested schema member is not supported: " member)
                        {:archive archive :member member}))

        (not (str/ends-with? schema-name ".json"))
        nil

        (not (re-matches schema-name-pattern schema-name))
        (throw (ex-info (str "Unsafe top-level schema member: " member)
                        {:archive archive :member member}))

        :else
        [schema-name member]))))

(defn- schema-members [archive members]
  (let [entries
        (into [] (keep #(schema-entry archive %)) members)
        counts (frequencies (map first entries))]
    (doseq [schema-name required-schema-names
            :let [member (str schema-member-prefix schema-name)]]
      (when-not (contains? counts schema-name)
        (throw (ex-info
                (format "%s is missing required %s" archive member)
                {:archive archive :member member}))))
    (doseq [[schema-name count] counts
            :when (not= 1 count)
            :let [member (str schema-member-prefix schema-name)]]
      (throw (ex-info
              (format "%s must contain exactly one %s" archive member)
              {:archive archive :member member})))
    (sort-by first entries)))

(defn- extract-schema! [archive staging-dir [schema-name member]]
  (let [content (extract-archive-member archive member)
        schema (parse-json archive member content)]
    (when-not (map? schema)
      (throw (ex-info (str member " must contain a JSON object")
                      {:archive archive :member member})))
    (with-open [output
                (io/output-stream (str (fs/path staging-dir schema-name)))]
      (.write output content))))

(defn- write-readme! [staging-dir source asset-name version]
  (spit
   (str (fs/path staging-dir "README.md"))
   (str
    "# Upstream Copilot CLI JSON Schemas\n\n"
    (case source
      :github-release
      (format
       (str "These files are fetched verbatim from the `%s` GitHub Release "
            "asset for Copilot CLI version `%s` and verified against the "
            "release `SHA256SUMS.txt`.\n\n")
       asset-name version)

      :release-mirror
      (format
       (str "These files were fetched from a configured Copilot CLI release "
            "mirror as the `%s` asset for requested version `%s` and verified "
            "against that mirror's `SHA256SUMS.txt`.\n\n")
       asset-name version)

      :local-override
      (str "These files were extracted verbatim from a local archive override "
           (format "while requesting schema version `%s`, " version)
           "and verified against the SHA-256 "
           "supplied via `COPILOT_CLI_RELEASE_SHA256`.\n\n"))
    "**Do not edit by hand.** To update, run `bb schemas:fetch` after "
    "bumping `.copilot-schema-version`.\n\n"
    (format "Schema version: `%s`\n" version))))

(defn- release-source []
  (let [override (env-value "COPILOT_CLI_DOWNLOAD_BASE_URL")
        url (str/replace (or override default-release-base-url) #"/+$" "")]
    (when-not (re-matches #"(?i)(?:https|file)://.+" url)
      (throw (ex-info
              "COPILOT_CLI_DOWNLOAD_BASE_URL must use https:// or file://"
              {:url url})))
    {:base-url url
     :source (if override :release-mirror :github-release)}))

(defn- resolve-release-archive! [version tmp]
  (let [asset-name (release-asset-name version)
        source-archive (env-value "COPILOT_CLI_RELEASE_TARBALL")
        supplied-hash (env-value "COPILOT_CLI_RELEASE_SHA256")]
    (when (and supplied-hash (nil? source-archive))
      (throw
       (ex-info
        "COPILOT_CLI_RELEASE_SHA256 requires COPILOT_CLI_RELEASE_TARBALL"
        {:environment-variable "COPILOT_CLI_RELEASE_SHA256"})))
    (if source-archive
      (do
        (when-not (fs/regular-file? source-archive)
          (throw
           (ex-info
            "COPILOT_CLI_RELEASE_TARBALL must name an existing file"
            {:environment-variable "COPILOT_CLI_RELEASE_TARBALL"
             :path source-archive})))
        (let [source-archive (str (fs/canonicalize source-archive))
              archive (str (fs/path tmp "local-release.tgz"))]
          (fs/copy source-archive archive)
          {:archive archive
           :asset-name (str (fs/file-name source-archive))
           :expected-hash supplied-hash
           :source :local-override}))
      (let [{:keys [base-url source]} (release-source)
            release-url (str base-url "/v" version)
            checksums-path (str (fs/path tmp "SHA256SUMS.txt"))
            archive-path (str (fs/path tmp asset-name))]
        (download! (str release-url "/SHA256SUMS.txt") checksums-path)
        (let [expected-hash
              (find-checksum (slurp checksums-path) asset-name)]
          (download! (str release-url "/" asset-name) archive-path)
          {:archive archive-path
           :asset-name asset-name
           :expected-hash expected-hash
           :source source})))))

(defn- resolve-schemas-destination []
  (if-let [override (env-value "COPILOT_CLI_SCHEMA_OUTPUT")]
    (let [path (-> override fs/absolutize fs/normalize)]
      (when (fs/exists? path)
        (throw (ex-info
                "COPILOT_CLI_SCHEMA_OUTPUT must not already exist"
                {:output (str path)})))
      {:path (str path)
       :replace-existing? false})
    {:path default-schemas-dir
     :replace-existing? true}))

(defn- warn-cleanup-failure! [path cleanup]
  (binding [*out* *err*]
    (println
     (format "WARNING: could not remove %s: %s"
             path
             (or (.getMessage ^Throwable cleanup)
                 (.getName (class cleanup)))))))

(defn- delete-tree-preserving! [path primary]
  (try
    (when (fs/exists? path)
      (fs/delete-tree path))
    (catch Throwable cleanup
      (when primary
        (.addSuppressed ^Throwable primary cleanup))
      (warn-cleanup-failure! path cleanup))))

(defn- with-delete-tree-cleanup [path f]
  (let [result
        (try
          (f)
          (catch Throwable primary
            (delete-tree-preserving! path primary)
            (throw primary)))]
    (delete-tree-preserving! path nil)
    result))

(defn- create-prepared-dir! [schemas-dir]
  (let [parent (fs/parent schemas-dir)]
    (fs/create-dirs parent)
    (str (fs/create-temp-dir
          {:dir parent :prefix ".copilot-schemas-"}))))

(defn- posix-permissions [path]
  (try
    (fs/posix-file-permissions path)
    (catch UnsupportedOperationException _
      nil)))

(defn- destination-directory-permissions [schemas-dir]
  (if (fs/exists? schemas-dir)
    (posix-permissions schemas-dir)
    (when (posix-permissions (fs/parent schemas-dir))
      "rwxr-xr-x")))

(defn- install-schemas!
  [staging-dir schemas-dir replace-existing?]
  (let [prepared-dir (create-prepared-dir! schemas-dir)
        permissions (destination-directory-permissions schemas-dir)]
    (with-delete-tree-cleanup
      prepared-dir
      (fn []
        (fs/copy-tree staging-dir prepared-dir)
        (when permissions
          (fs/set-posix-file-permissions prepared-dir permissions))
        (when (and replace-existing? (fs/exists? schemas-dir))
          (fs/delete-tree schemas-dir))
        (fs/move prepared-dir schemas-dir)))))

(defn- prepare-staged-schemas!
  [archive staging-dir source asset-name version]
  (let [archive-entries (list-archive-members archive)]
    (verify-archive-version! archive archive-entries version)
    (println
     (format "Verified %s: %s" (source-labels source) asset-name))
    (fs/create-dirs staging-dir)
    (let [members (schema-members archive archive-entries)]
      (doseq [member members]
        (extract-schema! archive staging-dir member))
      (write-readme! staging-dir source asset-name version)
      (mapv first members))))

(defn- fetch-schemas! [args]
  (let [opts (parse-args args)
        version (or (:version opts) (read-pinned-version))
        {:keys [path replace-existing?]} (resolve-schemas-destination)
        tmp (str (fs/create-temp-dir {:prefix "copilot-schemas-"}))]
    (with-delete-tree-cleanup
      tmp
      (fn []
        (println (format "Pinned schema version: %s" version))
        (let [{:keys [archive asset-name expected-hash source]}
              (resolve-release-archive! version tmp)
              staging-dir (str (fs/path tmp "staged-schemas"))]
          (verify-sha256! archive expected-hash asset-name)
          (let [schema-names
                (prepare-staged-schemas!
                 archive staging-dir source asset-name version)]
            (install-schemas! staging-dir path replace-existing?)
            (doseq [schema-name schema-names]
              (println
               (format "  -> %s" (fs/path path schema-name)))))
          (println "Schemas updated successfully."))))))

(defn -main [& args]
  (try
    (fetch-schemas! args)
    (catch clojure.lang.ExceptionInfo error
      (if (::usage-error (ex-data error))
        (do
          (binding [*out* *err*]
            (println (.getMessage error))
            (println usage))
          (System/exit 2))
        (throw error)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
