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
;;   COPILOT_CLI_SCHEMA_OUTPUT       dedicated output directory to replace
;;
;; The fetched schemas are committed for reproducible offline builds.

(ns codegen.fetch-schemas
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.math BigInteger)
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
          (throw (ex-info "--version requires a non-blank value" {})))
        (recur (assoc acc :version v) (rest rst)))
      :else
      (throw (ex-info (str "Unknown argument: " a) {})))))

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
               (first
                (remove #(re-matches #"[0-9a-fA-F]{64}" (or % ""))
                        entries))]
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
        expected-hash (str/lower-case expected-hash)]
    (when-not (= expected-hash actual-hash)
      (throw
       (ex-info
        (format
         "Integrity verification failed for %s: expected %s, got %s"
         asset-name expected-hash actual-hash)
        {:asset asset-name
         :expected expected-hash
         :actual actual-hash}))))
  archive)

(defn- archive-members [archive]
  (let [{:keys [exit out err]}
        @(p/process ["tar" "-tzf" archive]
                    {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not inspect " archive)
                      {:exit exit :stderr err})))
    (remove str/blank? (str/split-lines out))))

(defn- schema-members [archive members]
  (let [members-by-name
        (group-by
         first
         (keep
          (fn [member]
            (when-let [[_ schema-name]
                       (re-matches #"package/schemas/([^/]+\.json)" member)]
              [schema-name member]))
          members))]
    (doseq [schema-name required-schema-names]
      (let [member (str "package/schemas/" schema-name)]
        (when-not (= 1 (count (get members-by-name schema-name)))
          (throw (ex-info
                  (format "%s must contain exactly one %s" archive member)
                  {:archive archive :member member})))))
    (doseq [[schema-name entries] members-by-name]
      (when-not (= 1 (count entries))
        (throw (ex-info
                (format "%s must contain exactly one package/schemas/%s"
                        archive schema-name)
                {:archive archive
                 :member (str "package/schemas/" schema-name)}))))
    (->> members-by-name
         vals
         (map first)
         (sort-by first))))

(defn- extract-schema! [archive staging-dir [schema-name member]]
  (let [{:keys [exit out err]}
        @(p/process ["tar" "-xOzf" archive member]
                    {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not extract " member)
                      {:exit exit :stderr err})))
    (let [schema
          (try
            (json/parse-string out)
            (catch Exception error
              (throw (ex-info (str "Invalid JSON in " member)
                              {:archive archive :member member}
                              error))))]
      (when-not (map? schema)
        (throw (ex-info (str member " must contain a JSON object")
                        {:archive archive :member member})))
      (spit (str (fs/path staging-dir schema-name)) out))))

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

      :local-override
      (str "These files were extracted verbatim from a local archive override "
           (format "while requesting schema version `%s`, " version)
           "and verified against the SHA-256 "
           "supplied via `COPILOT_CLI_RELEASE_SHA256`.\n\n"))
    "**Do not edit by hand.** To update, run `bb schemas:fetch` after "
    "bumping `.copilot-schema-version`.\n\n"
    (format "Schema version: `%s`\n" version))))

(defn- release-base-url []
  (let [url (str/replace
             (or (env-value "COPILOT_CLI_DOWNLOAD_BASE_URL")
                 default-release-base-url)
             #"/+$" "")]
    (when-not (re-matches #"(?i)(?:https|file)://.+" url)
      (throw (ex-info
              (str "COPILOT_CLI_DOWNLOAD_BASE_URL must use https:// or file://")
              {:url url})))
    url))

(defn- resolve-release-archive! [version tmp]
  (let [asset-name (release-asset-name version)]
    (if-let [archive (env-value "COPILOT_CLI_RELEASE_TARBALL")]
      {:archive (str (fs/canonicalize archive))
       :asset-name (str (fs/file-name archive))
       :expected-hash (env-value "COPILOT_CLI_RELEASE_SHA256")
       :source :local-override}
      (let [release-base (release-base-url)
            release-url (str release-base "/v" version)
            checksums-path (str (fs/path tmp "SHA256SUMS.txt"))
            archive-path (str (fs/path tmp asset-name))]
        (download! (str release-url "/SHA256SUMS.txt") checksums-path)
        (download! (str release-url "/" asset-name) archive-path)
        {:archive archive-path
         :asset-name asset-name
         :expected-hash
         (find-checksum (slurp checksums-path) asset-name)
         :source :github-release}))))

(defn- resolve-schemas-dir []
  (let [path (-> (or (env-value "COPILOT_CLI_SCHEMA_OUTPUT")
                     default-schemas-dir)
                 fs/absolutize
                 fs/normalize)
        resolved-path (fs/canonicalize path)
        cwd (fs/canonicalize (fs/cwd))
        repository (fs/canonicalize repo-root)]
    (when (or (= resolved-path (fs/root resolved-path))
              (= resolved-path cwd)
              (fs/starts-with? repository resolved-path))
      (throw (ex-info
              "COPILOT_CLI_SCHEMA_OUTPUT must identify a dedicated directory"
              {:output (str path)})))
    (str path)))

(defn- install-schemas! [staging-dir schemas-dir schema-names]
  (let [parent (fs/parent schemas-dir)]
    (fs/create-dirs parent)
    (let [prepared-dir
          (str (fs/create-temp-dir
                {:dir parent :prefix ".copilot-schemas-"}))]
      (try
        (fs/copy-tree staging-dir prepared-dir)
        (when (fs/exists? schemas-dir)
          (fs/delete-tree schemas-dir))
        (fs/move prepared-dir schemas-dir)
        (finally
          (when (fs/exists? prepared-dir)
            (fs/delete-tree prepared-dir))))))
  (doseq [schema-name schema-names]
    (println (format "  -> %s" (fs/path schemas-dir schema-name)))))

(defn -main [& args]
  (let [opts (parse-args args)
        version (or (:version opts) (read-pinned-version))
        schemas-dir (resolve-schemas-dir)
        tmp (str (fs/create-temp-dir {:prefix "copilot-schemas-"}))]
    (try
      (println (format "Pinned schema version: %s" version))
      (let [{:keys [archive asset-name expected-hash source]}
            (resolve-release-archive! version tmp)
            staging-dir (str (fs/path tmp "staged-schemas"))]
        (verify-sha256! archive expected-hash asset-name)
        (case source
          :github-release
          (println (format "Verified GitHub Release asset: %s" asset-name))

          :local-override
          (println (format "Verified local archive override: %s" archive)))
        (fs/create-dirs staging-dir)
        (let [members (schema-members archive (archive-members archive))
              schema-names (mapv first members)]
          (doseq [member members]
            (extract-schema! archive staging-dir member))
          (write-readme! staging-dir source asset-name version)
          (install-schemas! staging-dir schemas-dir schema-names)))
      (println "Schemas updated successfully.")
      (finally
        (fs/delete-tree tmp)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
