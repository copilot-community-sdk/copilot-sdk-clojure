#!/usr/bin/env bb
;; Fetch the upstream @github/copilot npm package at the version pinned in
;; .copilot-schema-version, extract its `schemas/` directory, and copy it
;; to schemas/.
;;
;; Usage:
;;   bb schemas:fetch                  ;; uses pinned version from .copilot-schema-version
;;   bb schemas:fetch --version 0.0.404 ;; one-shot override (does not change the pin)
;;
;; The fetched schemas are committed to the repo for reproducible offline builds.
;; Bumping the pinned version is a deliberate human-reviewed action — use
;; `bb schemas:bump --version <new>` (added in Phase 8).

(ns codegen.fetch-schemas
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def repo-root
  (-> *file* fs/parent fs/parent fs/parent fs/canonicalize str))

(def schemas-dir
  (str (fs/path repo-root "schemas")))

(def version-file
  (str (fs/path repo-root ".copilot-schema-version")))

(defn read-pinned-version []
  (-> (slurp version-file) str/trim))

(defn parse-args [args]
  (loop [acc {} [a & rst] args]
    (cond
      (nil? a) acc
      (= a "--version") (recur (assoc acc :version (first rst)) (rest rst))
      :else (do (println "Unknown arg:" a) (System/exit 2)))))

(defn fetch-tarball! [version dest-dir]
  (let [url (format "https://registry.npmjs.org/@github/copilot/-/copilot-%s.tgz"
                    version)
        tgz (str (fs/path dest-dir (format "copilot-%s.tgz" version)))]
    (println (format "Fetching %s" url))
    (let [{:keys [exit err]}
          @(p/process ["curl" "-fsSL" "-o" tgz url] {:err :string})]
      (when-not (zero? exit)
        (println "curl failed:" err)
        (System/exit exit)))
    tgz))

(defn extract-schemas! [tgz dest-dir]
  ;; Tarball layout: package/schemas/*.json (npm convention prepends `package/`).
  (println (format "Extracting schemas from %s" tgz))
  (let [{:keys [exit err]}
        @(p/process ["tar" "-xzf" tgz "-C" dest-dir
                     "--strip-components=2"
                     "package/schemas"]
                    {:err :string})]
    (when-not (zero? exit)
      (println "tar failed:" err)
      (System/exit exit))))

(defn extract-package-json! [tgz dest-dir]
  ;; Extract package/package.json so we can verify its declared version
  ;; matches the URL-pinned version (defends against mirror caches /
  ;; redirected tags / corrupted artifacts).
  (let [{:keys [exit err]}
        @(p/process ["tar" "-xzf" tgz "-C" dest-dir
                     "--strip-components=1"
                     "package/package.json"]
                    {:err :string})]
    (when-not (zero? exit)
      (println "tar (package.json) failed:" err)
      (System/exit exit))))

(defn verify-tarball-version! [pkg-json-path expected]
  (let [pkg     (json/parse-string (slurp pkg-json-path) true)
        actual  (:version pkg)]
    (when-not (= actual expected)
      (println (format "ERROR: tarball version mismatch — expected %s, got %s"
                       expected actual))
      (System/exit 1))
    (println (format "Verified tarball package.json version: %s" actual))))

(defn -main [& args]
  (let [opts    (parse-args args)
        version (or (:version opts) (read-pinned-version))
        tmp     (str (fs/create-temp-dir {:prefix "copilot-schemas-"}))]
    (try
      (println (format "Pinned schema version: %s" version))
      (let [tgz (fetch-tarball! version tmp)]
        (fs/create-dirs tmp)
        (extract-package-json! tgz tmp)
        (verify-tarball-version! (str (fs/path tmp "package.json")) version)
        (extract-schemas! tgz tmp)
        ;; Wipe destination to avoid stale schemas, then copy fresh ones.
        (when (fs/exists? schemas-dir)
          (fs/delete-tree schemas-dir))
        (fs/create-dirs schemas-dir)
        (doseq [f (fs/list-dir tmp)
                :when (and (fs/regular-file? f)
                           (str/ends-with? (str f) ".json"))]
          (let [target (fs/path schemas-dir (fs/file-name f))]
            (fs/copy f target)
            (println (format "  → %s" target))))
        ;; Drop a small README in the schemas dir so its purpose is obvious.
        (spit (str (fs/path schemas-dir "README.md"))
              (format
                (str "# Upstream Copilot CLI JSON Schemas\n\n"
                     "These files are fetched verbatim from the `@github/copilot` "
                     "npm package at the version pinned in `.copilot-schema-version`.\n\n"
                     "**Do not edit by hand.** To update, run `bb schemas:fetch` after "
                     "bumping `.copilot-schema-version`.\n\n"
                     "Currently pinned version: `%s`\n")
                version)))
      (println "Schemas updated successfully.")
      (finally
        (fs/delete-tree tmp)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
