#!/usr/bin/env bb
;; Fetch the Copilot CLI GitHub Release package at the version pinned in
;; .copilot-schema-version, verify it against SHA256SUMS.txt, and copy its
;; canonical JSON schemas to schemas/.
;;
;; Usage:
;;   bb schemas:fetch                   ;; uses .copilot-schema-version
;;   bb schemas:fetch --version 1.0.86-0 ;; one-shot override
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

(def schema-names
  ["api.schema.json" "session-events.schema.json"])

(def default-release-base-url
  "https://github.com/github/copilot-cli/releases/download")

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
        @(p/process ["curl" "-fsSL"
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
  (or
   (some
    (fn [line]
      (let [[hash name] (str/split (str/trim line) #"\s+" 2)]
        (when (and (= asset-name (some-> name (str/replace #"^\*" "")))
                   (re-matches #"[0-9a-fA-F]{64}" (or hash "")))
          (str/lower-case hash))))
    (str/split-lines checksums))
   (throw (ex-info (str "SHA256SUMS.txt does not contain " asset-name)
                   {:asset asset-name}))))

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

(defn- extract-schema! [archive members staging-dir schema-name]
  (let [member (str "package/schemas/" schema-name)]
    (when-not (= 1 (count (filter #{member} members)))
      (throw (ex-info
              (format "%s must contain exactly one %s" archive member)
              {:archive archive :member member})))
    (let [{:keys [exit out err]}
          @(p/process ["tar" "-xOzf" archive member]
                      {:out :string :err :string})]
      (when-not (zero? exit)
        (throw (ex-info (str "Could not extract " member)
                        {:exit exit :stderr err})))
      (json/parse-string out)
      (spit (str (fs/path staging-dir schema-name)) out))))

(defn- write-readme! [staging-dir asset-name version]
  (spit
   (str (fs/path staging-dir "README.md"))
   (format
    (str "# Upstream Copilot CLI JSON Schemas\n\n"
         "These files are fetched verbatim from the `%s` GitHub Release "
         "asset at the version pinned in `.copilot-schema-version` and "
         "verified against the release `SHA256SUMS.txt`.\n\n"
         "**Do not edit by hand.** To update, run `bb schemas:fetch` after "
         "bumping `.copilot-schema-version`.\n\n"
         "Currently pinned version: `%s`\n")
    asset-name version)))

(defn- resolve-release-archive! [version tmp]
  (let [asset-name (release-asset-name version)]
    (if-let [archive (System/getenv "COPILOT_CLI_RELEASE_TARBALL")]
      {:archive archive
       :asset-name asset-name
       :expected-hash (System/getenv "COPILOT_CLI_RELEASE_SHA256")}
      (let [release-base
            (str/replace
             (or (System/getenv "COPILOT_CLI_DOWNLOAD_BASE_URL")
                 default-release-base-url)
             #"/+$" "")
            release-url (str release-base "/v" version)
            checksums-path (str (fs/path tmp "SHA256SUMS.txt"))
            archive-path (str (fs/path tmp asset-name))]
        (download! (str release-url "/SHA256SUMS.txt") checksums-path)
        (download! (str release-url "/" asset-name) archive-path)
        {:archive archive-path
         :asset-name asset-name
         :expected-hash
         (find-checksum (slurp checksums-path) asset-name)}))))

(defn- install-schemas! [staging-dir schemas-dir]
  (when (fs/exists? schemas-dir)
    (fs/delete-tree schemas-dir))
  (fs/create-dirs (fs/parent schemas-dir))
  (fs/copy-tree staging-dir schemas-dir)
  (doseq [schema-name schema-names]
    (println (format "  -> %s" (fs/path schemas-dir schema-name)))))

(defn -main [& args]
  (let [opts (parse-args args)
        version (or (:version opts) (read-pinned-version))
        schemas-dir (or (System/getenv "COPILOT_CLI_SCHEMA_OUTPUT")
                        default-schemas-dir)
        tmp (str (fs/create-temp-dir {:prefix "copilot-schemas-"}))]
    (try
      (println (format "Pinned schema version: %s" version))
      (let [{:keys [archive asset-name expected-hash]}
            (resolve-release-archive! version tmp)
            staging-dir (str (fs/path tmp "staged-schemas"))]
        (verify-sha256! archive expected-hash asset-name)
        (println (format "Verified release asset: %s" asset-name))
        (fs/create-dirs staging-dir)
        (let [members (archive-members archive)]
          (doseq [schema-name schema-names]
            (extract-schema! archive members staging-dir schema-name)))
        (write-readme! staging-dir asset-name version)
        (install-schemas! staging-dir schemas-dir))
      (println "Schemas updated successfully.")
      (finally
        (fs/delete-tree tmp)))))

(when (= *file* (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch Throwable error
      (binding [*out* *err*]
        (println "ERROR:" (.getMessage error)))
      (System/exit 1))))
