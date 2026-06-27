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
;; To bump the pinned version, edit .copilot-schema-version and re-run
;; `bb schemas:fetch` followed by `bb codegen`.

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
      (= a "--version")
      (let [v (first rst)]
        (when (or (nil? v) (str/blank? v))
          (println "Error: --version requires a non-blank value")
          (println "Usage: bb schemas:fetch [--version VERSION]")
          (System/exit 2))
        (recur (assoc acc :version v) (rest rst)))
      :else (do (println "Unknown arg:" a)
                (println "Usage: bb schemas:fetch [--version VERSION]")
                (System/exit 2)))))

;; Canonical platform package to source schemas from when the main
;; `@github/copilot` package no longer bundles them (see `-main`). Schemas are
;; platform-independent JSON, so any platform package yields byte-identical
;; files; we pin one deterministically for reproducible offline builds.
(def schema-platform-package "@github/copilot-linux-x64")

(defn- unscoped-name [pkg]
  ;; "@github/copilot-linux-x64" -> "copilot-linux-x64"; "@github/copilot" -> "copilot".
  (last (str/split pkg #"/")))

(defn fetch-tarball! [pkg version dest-dir]
  (let [unscoped (unscoped-name pkg)
        url      (format "https://registry.npmjs.org/%s/-/%s-%s.tgz"
                         pkg unscoped version)
        tgz      (str (fs/path dest-dir (format "%s-%s.tgz" unscoped version)))]
    (println (format "Fetching %s" url))
    (let [{:keys [exit err]}
          @(p/process ["curl" "-fsSL" "-o" tgz url] {:err :string})]
      (when-not (zero? exit)
        (println "curl failed:" err)
        (System/exit exit)))
    tgz))

(defn extract-schemas! [tgz dest-dir]
  ;; Tarball layout: package/schemas/*.json (npm convention prepends `package/`).
  ;; Extract into a dedicated `schemas` subdir so we can copy from a
  ;; clean directory (avoids accidentally picking up package.json or
  ;; other extracted files). Returns true on success, false only when the
  ;; tarball legitimately carries no `package/schemas` directory (newer
  ;; split-package layout — both GNU and BSD tar report "Not found in
  ;; archive"). Any other tar failure (corrupt download, missing `tar`,
  ;; permissions) is fatal, so a real error can't be silently masked as a
  ;; split-package fallback.
  (println (format "Extracting schemas from %s" tgz))
  (fs/create-dirs dest-dir)
  (let [{:keys [exit err]}
        @(p/process ["tar" "-xzf" tgz "-C" dest-dir
                     "--strip-components=1"
                     "package/schemas"]
                    {:err :string :out :string})]
    (cond
      (zero? exit) true
      (str/includes? (str/lower-case (or err "")) "not found in archive") false
      :else (do
              (println "tar (schemas) failed:" err)
              (System/exit exit)))))

(defn extract-package-json! [tgz dest-dir]
  ;; Extract package/package.json into dest-dir so we can verify its declared
  ;; version matches the URL-pinned version (defends against mirror caches /
  ;; redirected tags / corrupted artifacts). dest-dir is per-package so a
  ;; second tarball's package.json does not clobber the first.
  (fs/create-dirs dest-dir)
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

(defn fetch-verified-tarball! [pkg version tmp]
  ;; Fetch a package tarball and confirm its package.json version matches the
  ;; pinned version. Returns the tarball path.
  (let [tgz      (fetch-tarball! pkg version tmp)
        meta-dir (str (fs/path tmp (str (unscoped-name pkg) "-meta")))]
    (extract-package-json! tgz meta-dir)
    (verify-tarball-version! (str (fs/path meta-dir "package.json")) version)
    tgz))

(defn -main [& args]
  (let [opts    (parse-args args)
        version (or (:version opts) (read-pinned-version))
        tmp     (str (fs/create-temp-dir {:prefix "copilot-schemas-"}))]
    (try
      (println (format "Pinned schema version: %s" version))
      (fs/create-dirs tmp)
      (let [schemas-extract-dir (str (fs/path tmp "schemas"))
            ;; Schemas used to ship in the main `@github/copilot` package, but
            ;; from CLI 1.0.64 onward that package is a thin platform-loader
            ;; stub and the schemas live in the per-platform packages
            ;; (e.g. `@github/copilot-linux-x64`). Try the main package first
            ;; for backward compatibility with older pins, then fall back to a
            ;; canonical platform package.
            main-tgz (fetch-verified-tarball! "@github/copilot" version tmp)
            schema-source
            (if (extract-schemas! main-tgz tmp)
              "@github/copilot"
              (do
                (println
                  (format
                    (str "Main package carries no schemas (split-package layout); "
                         "falling back to %s.")
                    schema-platform-package))
                (let [plat-tgz (fetch-verified-tarball!
                                 schema-platform-package version tmp)]
                  (when-not (extract-schemas! plat-tgz tmp)
                    (println
                      (format "ERROR: %s carries no package/schemas directory."
                              schema-platform-package))
                    (System/exit 1))
                  schema-platform-package)))]
        (println (format "Schemas sourced from: %s" schema-source))
        ;; Wipe destination to avoid stale schemas, then copy fresh ones from
        ;; the extracted `schemas/` subdir.
        (when (fs/exists? schemas-dir)
          (fs/delete-tree schemas-dir))
        (fs/create-dirs schemas-dir)
        (doseq [f (fs/list-dir schemas-extract-dir)
                :when (and (fs/regular-file? f)
                           (str/ends-with? (str f) ".json"))]
          (let [target (fs/path schemas-dir (fs/file-name f))]
            (fs/copy f target)
            (println (format "  → %s" target))))
        ;; Drop a small README in the schemas dir so its purpose is obvious.
        (spit (str (fs/path schemas-dir "README.md"))
              (format
                (str "# Upstream Copilot CLI JSON Schemas\n\n"
                     "These files are fetched verbatim from the `%s` "
                     "npm package at the version pinned in `.copilot-schema-version`.\n\n"
                     "**Do not edit by hand.** To update, run `bb schemas:fetch` after "
                     "bumping `.copilot-schema-version`.\n\n"
                     "Currently pinned version: `%s`\n")
                schema-source version)))
      (println "Schemas updated successfully.")
      (finally
        (fs/delete-tree tmp)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
