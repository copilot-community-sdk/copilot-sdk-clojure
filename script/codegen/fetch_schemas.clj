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
  (:import (java.io ByteArrayOutputStream
                    OutputStream
                    StringReader)
           (java.math BigInteger)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException
                             CodingErrorAction
                             StandardCharsets)
           (java.nio.file CopyOption
                          Files)
           (java.security MessageDigest)
           (java.util UUID)
           (java.util.concurrent TimeUnit
                                 TimeoutException)))

(def repo-root
  (-> *file* fs/parent fs/parent fs/parent fs/canonicalize str))

(def default-schemas-dir
  (str (fs/path repo-root "schemas")))

(def version-file
  (str (fs/path repo-root ".copilot-schema-version")))

(def schema-platform "linux-x64")

(def required-schema-names
  ["api.schema.json" "session-events.schema.json"])

(def ^:private max-checksum-manifest-bytes (* 1024 1024))
(def ^:private max-release-archive-bytes (* 256 1024 1024))
(def ^:private max-archive-listing-bytes (* 1024 1024))
(def ^:private max-archive-members 4096)
(def ^:private max-package-json-bytes (* 1024 1024))
(def ^:private max-schema-bytes (* 32 1024 1024))
(def ^:private max-total-schema-bytes (* 256 1024 1024))
(def ^:private max-command-stderr-bytes (* 1024 1024))
(def ^:private download-max-attempts 3)
(def ^:private download-retry-delay-ms 1000)
(def ^:private download-command-timeout-seconds 600)
(def ^:private archive-command-timeout-seconds 300)
(def ^:private process-termination-grace-seconds 1)
(def ^:private process-termination-force-seconds 1)
(def ^:private byte-array-class (class (byte-array 0)))

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
  (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" version)
    (throw
     (ex-info
      (str "Schema version must be a path-safe release identifier containing "
           "only letters, digits, '.', '_', and '-'")
      {:version version})))
  (format "github-copilot-%s-%s.tgz" version schema-platform))

(declare run-bounded-command-to-file)

(defn- delete-partial-download! [destination primary]
  (try
    (Files/deleteIfExists (fs/path destination))
    true
    (catch Throwable cleanup
      (.addSuppressed ^Throwable primary cleanup)
      (binding [*out* *err*]
        (println
         (format
          "WARNING: could not remove partial download %s: %s"
          destination (.getMessage ^Throwable cleanup))))
      false)))

(def ^:private retryable-curl-exit-codes
  ;; Curl's built-in retry cannot reset piped output. Mirror its HTTPS retry
  ;; categories with fresh bounded destinations instead.
  #{22 28})

(defn- retryable-download-error? [error]
  (let [{:keys [failure exit]} (ex-data error)]
    (and (= :command-exit failure)
         (contains? retryable-curl-exit-codes exit))))

(defn- wait-before-download-retry! []
  (try
    (Thread/sleep download-retry-delay-ms)
    (catch InterruptedException error
      (.interrupt (Thread/currentThread))
      (throw error))))

(defn- download! [url destination max-bytes]
  (println (format "Fetching %s" url))
  (loop [attempt 1]
    (let [outcome
          (try
            (run-bounded-command-to-file
             ["curl"
              "--fail"
              "--silent"
              "--show-error"
              "--location"
              "--proto" "=https,file"
              "--proto-redir" "=https"
              "--connect-timeout" "30"
              "--max-time" "600"
              "--max-filesize" (str max-bytes)
              url]
             destination
             max-bytes
             download-command-timeout-seconds
             (str "Download from " url))
            {:error nil}
            (catch Throwable primary
              {:error primary}))]
      (if-let [primary (:error outcome)]
        (let [cleaned? (delete-partial-download! destination primary)]
          (if (and cleaned?
                   (< attempt download-max-attempts)
                   (retryable-download-error? primary))
            (do
              (binding [*out* *err*]
                (println
                 (format
                  "Retrying download after curl exit %d (%d/%d): %s"
                  (:exit (ex-data primary))
                  (inc attempt)
                  download-max-attempts
                  url)))
              (wait-before-download-retry!)
              (recur (inc attempt)))
            (throw primary)))
        destination))))

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

(defn- snapshot-local-archive! [source destination]
  (let [buffer (byte-array 65536)]
    (with-open [input (io/input-stream source)
                output (io/output-stream destination)]
      (loop [total 0]
        (let [read-count (.read input buffer)]
          (cond
            (neg? read-count)
            destination

            (zero? read-count)
            (recur total)

            :else
            (let [new-total (+ total read-count)
                  write-count
                  (min read-count
                       (max 0 (- max-release-archive-bytes total)))]
              (when (pos? write-count)
                (.write output buffer 0 write-count))
              (when (> new-total max-release-archive-bytes)
                (throw
                 (ex-info
                  (format "Local release archive exceeds %d bytes"
                          max-release-archive-bytes)
                  {:path (str source)
                   :size-at-least new-total
                   :max-bytes max-release-archive-bytes})))
              (recur new-total))))))))

(defn- bounded-output
  ([max-bytes on-overflow]
   (bounded-output max-bytes on-overflow nil))
  ([max-bytes on-overflow destination]
   (let [buffer (when-not destination (ByteArrayOutputStream.))
         output (or buffer (io/output-stream destination))
         lock (Object.)
         total (atom 0)
         written (atom 0)
         overflow? (atom false)
         record!
         (fn [bytes offset length]
           (let [became-overflow?
                 (locking lock
                   (let [new-total (+ @total length)
                         remaining (- max-bytes @written)
                         write-count (min length (max 0 remaining))
                         overflow-now? (> new-total max-bytes)
                         became-overflow?
                         (and overflow-now? (not @overflow?))]
                     (when (pos? write-count)
                       (.write ^OutputStream output
                               bytes offset write-count))
                     (reset! total new-total)
                     (swap! written + write-count)
                     (reset! overflow? overflow-now?)
                     became-overflow?))]
             (when became-overflow?
               (on-overflow))))
         snapshot
         (fn []
           (locking lock
             (cond-> {:total @total
                      :overflow? @overflow?}
               buffer
               (assoc :bytes
                      (.toByteArray ^ByteArrayOutputStream buffer)))))]
     {:stream
      (proxy [OutputStream] []
        (write
          ([value]
           (if (instance? byte-array-class value)
             (record! value 0 (alength ^bytes value))
             (let [bytes (byte-array [(unchecked-byte value)])]
               (record! bytes 0 1))))
          ([bytes offset length]
           (record! bytes offset length)))
        (flush []
          (locking lock
            (.flush ^OutputStream output)))
        (close []
          (locking lock
            (.close ^OutputStream output))))
      :snapshot snapshot})))

(defn- process-tree-handles [process]
  (let [^Process java-process (:proc process)
        root (.toHandle java-process)
        descendants
        (with-open [stream (.descendants root)]
          (vec (iterator-seq (.iterator stream))))]
    (conj descendants root)))

(defn- await-process-handle-until! [handle deadline]
  (if-not (.isAlive handle)
    true
    (let [remaining (- deadline (System/nanoTime))]
      (if-not (pos? remaining)
        false
        (try
          (.get (.onExit handle) remaining TimeUnit/NANOSECONDS)
          true
          (catch TimeoutException _
            false)
          (catch InterruptedException error
            (.interrupt (Thread/currentThread))
            (throw error)))))))

(defn- await-process-tree! [handles timeout-seconds]
  (let [deadline
        (+ (System/nanoTime)
           (.toNanos TimeUnit/SECONDS timeout-seconds))]
    (every? #(await-process-handle-until! % deadline) handles)))

(defn- terminate-process-tree! [process]
  (let [handles (process-tree-handles process)
        cleanup-errors (atom [])]
    (when (some #(.isAlive %) handles)
      (try
        (p/destroy-tree process)
        (catch Throwable cleanup
          (swap! cleanup-errors conj cleanup)))
      (when-not
       (try
         (await-process-tree! handles process-termination-grace-seconds)
         (catch Throwable cleanup
           (swap! cleanup-errors conj cleanup)
           false))
        (let [force-handles
              (vec (distinct (concat handles
                                     (process-tree-handles process))))]
          (doseq [handle force-handles
                  :when (.isAlive handle)]
            (try
              (.destroyForcibly handle)
              (catch Throwable cleanup
                (swap! cleanup-errors conj cleanup))))
          (when-not
           (try
             (await-process-tree!
              force-handles process-termination-force-seconds)
             (catch Throwable cleanup
               (swap! cleanup-errors conj cleanup)
               false))
            (swap! cleanup-errors conj
                   (ex-info
                    "Process tree remained alive after forceful termination"
                    {:pids
                     (mapv #(.pid %)
                           (filter #(.isAlive %) force-handles))}))))))
    (when-let [[primary & suppressed] (seq @cleanup-errors)]
      (doseq [cleanup suppressed]
        (.addSuppressed ^Throwable primary cleanup))
      (throw primary))))

(defn- attach-cleanup-error! [primary termination-outcome]
  (when-let [cleanup (:error termination-outcome)]
    (.addSuppressed ^Throwable primary cleanup))
  primary)

(defn- output-limit-error
  [command description max-bytes stdout-snapshot stderr-snapshot]
  (cond
    (:overflow? stdout-snapshot)
    (ex-info
     (format "%s output exceeds %d bytes" description max-bytes)
     {:command command
      :failure :output-limit
      :max-bytes max-bytes})

    (:overflow? stderr-snapshot)
    (ex-info
     (format "%s stderr exceeds %d bytes"
             description max-command-stderr-bytes)
     {:command command
      :failure :stderr-limit
      :max-stderr-bytes max-command-stderr-bytes})))

(defn- close-command-streams [streams]
  (reduce
   (fn [errors stream]
     (try
       (.close ^OutputStream stream)
       errors
       (catch Throwable cleanup
         (conj errors cleanup))))
   []
   streams))

(defn- throw-with-cleanup-errors! [outcome cleanup-errors]
  (if-let [primary (:error outcome)]
    (do
      (doseq [cleanup cleanup-errors]
        (.addSuppressed ^Throwable primary cleanup))
      (throw primary))
    (if-let [[primary & suppressed] (seq cleanup-errors)]
      (do
        (doseq [cleanup suppressed]
          (.addSuppressed ^Throwable primary cleanup))
        (throw primary))
      (:value outcome))))

(defn- run-bounded-command
  [command max-bytes timeout-seconds description destination]
  (let [process-holder (atom nil)
        termination-started? (atom false)
        termination-outcome (promise)
        terminate-once!
        (fn []
          (when-let [process @process-holder]
            (if (compare-and-set! termination-started? false true)
              (let [outcome
                    (try
                      (terminate-process-tree! process)
                      {:error nil}
                      (catch Throwable cleanup
                        {:error cleanup}))]
                (deliver termination-outcome outcome)
                outcome)
              @termination-outcome)))
        {stdout-stream :stream
         stdout-snapshot :snapshot}
        (bounded-output max-bytes terminate-once! destination)
        {stderr-stream :stream
         stderr-snapshot :snapshot}
        (bounded-output max-command-stderr-bytes terminate-once!)
        execute!
        (fn []
          (let [process
                (p/process command
                           {:out stdout-stream
                            :err stderr-stream})
                _ (reset! process-holder process)
                initial-stdout (stdout-snapshot)
                initial-stderr (stderr-snapshot)
                _ (when (or (:overflow? initial-stdout)
                            (:overflow? initial-stderr))
                    (terminate-once!))
                ^Process java-process (:proc process)
                completed?
                (try
                  (.waitFor java-process
                            timeout-seconds
                            TimeUnit/SECONDS)
                  (catch InterruptedException primary
                    (let [termination (terminate-once!)]
                      (.interrupt (Thread/currentThread))
                      (throw
                       (attach-cleanup-error! primary termination))))
                  (catch Throwable primary
                    (throw
                     (attach-cleanup-error!
                      primary
                      (terminate-once!)))))]
            (if-not completed?
              (let [termination (terminate-once!)
                    _ (when-not (.isAlive java-process)
                        @process)
                    final-stdout (stdout-snapshot)
                    final-stderr (stderr-snapshot)
                    primary
                    (or
                     (output-limit-error
                      command
                      description
                      max-bytes
                      final-stdout
                      final-stderr)
                     (ex-info
                      (format "%s timed out after %d seconds"
                              description timeout-seconds)
                      {:command command
                       :failure :timeout
                       :timeout-seconds timeout-seconds}))]
                (throw
                 (attach-cleanup-error! primary termination)))
              (let [result @process
                    final-stdout (stdout-snapshot)
                    final-stderr (stderr-snapshot)]
                (when-let [primary
                           (output-limit-error
                            command
                            description
                            max-bytes
                            final-stdout
                            final-stderr)]
                  (throw
                   (attach-cleanup-error!
                    primary
                    (terminate-once!))))
                (when-not (zero? (:exit result))
                  (let [stderr
                        (String. ^bytes (:bytes final-stderr)
                                 StandardCharsets/UTF_8)]
                    (throw
                     (ex-info
                      (str description " failed")
                      {:command command
                       :failure :command-exit
                       :exit (:exit result)
                       :stderr stderr}))))
                final-stdout))))
        outcome
        (try
          {:value (execute!)}
          (catch Throwable primary
            {:error primary}))
        cleanup-errors
        (close-command-streams [stdout-stream stderr-stream])]
    (throw-with-cleanup-errors! outcome cleanup-errors)))

(defn- run-bounded-command-output
  [command max-bytes timeout-seconds description]
  (:bytes
   (run-bounded-command
    command max-bytes timeout-seconds description nil)))

(defn- run-bounded-command-to-file
  [command destination max-bytes timeout-seconds description]
  (run-bounded-command
   command max-bytes timeout-seconds description destination)
  destination)

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

(defn- parse-archive-members [archive listing]
  (let [members
        (->> (str/split-lines listing)
             (remove str/blank?)
             vec)]
    (when (> (count members) max-archive-members)
      (throw
       (ex-info
        (format "%s contains more than %d members"
                archive max-archive-members)
        {:archive archive
         :member-count (count members)
         :max-members max-archive-members})))
    (run! #(validate-archive-member-name! archive %) members)
    members))

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

(defn- list-archive-members [archive]
  (let [output
        (run-bounded-command-output
         ["tar" "-tzf" archive]
         max-archive-listing-bytes
         archive-command-timeout-seconds
         (str "Archive listing for " archive))]
    (parse-archive-members
     archive
     (decode-utf8 archive "archive member listing" output))))

(defn- extract-archive-member [archive member max-bytes]
  (run-bounded-command-output
   ["tar" "-xOzf" archive member]
   max-bytes
   archive-command-timeout-seconds
   (str member " extraction")))

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
    (let [package
          (parse-json archive member
                      (extract-archive-member
                       archive member max-package-json-bytes))
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

(defn- extract-schema!
  [archive staging-dir extracted-bytes [schema-name member]]
  (let [content (extract-archive-member archive member max-schema-bytes)
        total-bytes (+ extracted-bytes (alength ^bytes content))]
    (when (> total-bytes max-total-schema-bytes)
      (throw
       (ex-info
        (format "Total schema extraction output exceeds %d bytes"
                max-total-schema-bytes)
        {:archive archive
         :member member
         :failure :total-schema-output-limit
         :max-bytes max-total-schema-bytes
         :total-bytes total-bytes})))
    (let [schema (parse-json archive member content)]
      (when-not (map? schema)
        (throw (ex-info (str member " must contain a JSON object")
                        {:archive archive :member member})))
      (with-open [output
                  (io/output-stream (str (fs/path staging-dir schema-name)))]
        (.write output content))
      total-bytes)))

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
          (snapshot-local-archive! source-archive archive)
          {:archive archive
           :asset-name (str (fs/file-name source-archive))
           :expected-hash supplied-hash
           :source :local-override}))
      (let [{:keys [base-url source]} (release-source)
            release-url (str base-url "/v" version)
            checksums-path (str (fs/path tmp "SHA256SUMS.txt"))
            archive-path (str (fs/path tmp asset-name))]
        (download! (str release-url "/SHA256SUMS.txt")
                   checksums-path
                   max-checksum-manifest-bytes)
        (let [expected-hash
              (find-checksum (slurp checksums-path) asset-name)]
          (download! (str release-url "/" asset-name)
                     archive-path
                     max-release-archive-bytes)
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
  (let [message (.getMessage ^Throwable cleanup)
        class-name (.getSimpleName (class cleanup))
        detail
        ;; babashka.fs may report only the path; avoid printing it twice.
        (if (or (str/blank? message)
                (= (str path) message))
          class-name
          (str class-name ": " message))]
    (binding [*out* *err*]
      (println
       (format "WARNING: could not remove %s: %s" path detail)))))

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
  (let [parent (fs/parent schemas-dir)
        prepared-dir
        (fs/path parent (str ".copilot-schemas-" (UUID/randomUUID)))]
    (fs/create-dirs parent)
    (str
     (Files/createDirectory
      prepared-dir
      (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- posix-permissions [path]
  (try
    (fs/posix-file-permissions path)
    (catch UnsupportedOperationException _
      nil)))

(defn- destination-directory-permissions [schemas-dir]
  (when (fs/exists? schemas-dir)
    (posix-permissions schemas-dir)))

(defn- move-directory! [source destination]
  (Files/move
   (fs/path source)
   (fs/path destination)
   (make-array CopyOption 0)))

(defn- create-backup-path [schemas-dir]
  (str
   (fs/path
    (fs/parent schemas-dir)
    (str ".copilot-schemas-backup-" (UUID/randomUUID)))))

(defn- restore-schema-backup!
  [backup-dir schemas-dir primary]
  (try
    (move-directory! backup-dir schemas-dir)
    (catch Throwable restore
      (.addSuppressed
       ^Throwable primary
       (ex-info
        (format
         "Could not restore previous schemas from %s to %s"
         backup-dir schemas-dir)
        {:backup backup-dir
         :destination schemas-dir}
        restore))
      (binding [*out* *err*]
        (println
         (format
          "WARNING: could not restore previous schemas from %s to %s: %s"
          backup-dir schemas-dir (.getMessage ^Throwable restore)))))))

(defn- replace-schemas! [prepared-dir schemas-dir]
  (let [backup-dir (create-backup-path schemas-dir)]
    (move-directory! schemas-dir backup-dir)
    (try
      (move-directory! prepared-dir schemas-dir)
      (catch Throwable primary
        (restore-schema-backup! backup-dir schemas-dir primary)
        (throw primary)))
    (delete-tree-preserving! backup-dir nil)))

(defn- install-schemas!
  [staging-dir schemas-dir replace-existing?]
  (let [prepared-dir (create-prepared-dir! schemas-dir)]
    (with-delete-tree-cleanup
      prepared-dir
      (fn []
        (let [permissions
              (or (destination-directory-permissions schemas-dir)
                  (posix-permissions prepared-dir))]
          (fs/copy-tree staging-dir prepared-dir)
          (when permissions
            (fs/set-posix-file-permissions prepared-dir permissions))
          (if (and replace-existing? (fs/exists? schemas-dir))
            (replace-schemas! prepared-dir schemas-dir)
            (move-directory! prepared-dir schemas-dir)))))))

(defn- prepare-staged-schemas!
  [archive staging-dir source asset-name version]
  (let [archive-entries (list-archive-members archive)]
    (verify-archive-version! archive archive-entries version)
    (println
     (format "Verified %s: %s" (source-labels source) asset-name))
    (fs/create-dirs staging-dir)
    (let [members (schema-members archive archive-entries)]
      (reduce
       (fn [extracted-bytes member]
         (extract-schema! archive staging-dir extracted-bytes member))
       0
       members)
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
