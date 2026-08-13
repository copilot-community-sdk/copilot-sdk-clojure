(ns github.copilot-sdk.bench.runner
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [github.copilot-sdk.bench.analysis :as analysis]
            [github.copilot-sdk.bench.protocol :as bench-protocol])
  (:import [java.io File]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(def profiles
  {"smoke" {:cold-start-count 2
            :warmup 20
            :warmup-window-size 5
            :stable-window-count 2
            :max-warmup-relative-drift 10.0
            :iterations 10
            :measured-drift-window 5
            :max-measured-relative-drift 10.0
            :steady-repetitions 1
            :confirmatory? false
            :confirmatory-min-pairs 20
            :familywise-alpha 0.05
            :concurrency 1
            :timeout-ms 5000
            :driver-timeout-ms 60000
            :bootstrap-seed 424242
            :bootstrap-resamples 1000}
   "rigorous" {:cold-start-count 30
               :warmup 20000
               :warmup-window-size 250
               :stable-window-count 8
               :max-warmup-relative-drift 0.15
               :iterations 4000
               :measured-drift-window 2000
               :max-measured-relative-drift 0.10
               :steady-repetitions 20
               :confirmatory? true
               :confirmatory-min-pairs 20
               :familywise-alpha 0.05
               :concurrency 1
               :timeout-ms 5000
               :driver-timeout-ms 300000
               :bootstrap-seed 424242
               :bootstrap-resamples 10000}})

(defn parse-args
  [args]
  (into {}
        (map (fn [arg]
               (let [[key value] (str/split arg #"=" 2)]
                 (when-not (and (str/starts-with? key "--")
                                (not (str/blank? (subs key 2)))
                                (not (str/blank? value)))
                   (throw (ex-info "Expected --name=value" {:argument arg})))
                 [(subs key 2) value])))
        args))

(defn- sha256-bytes
  [bytes]
  (format "%064x" (BigInteger. 1 (.digest (doto (MessageDigest/getInstance "SHA-256")
                                            (.update bytes))))))

(defn- sha256-file
  [file]
  (sha256-bytes (Files/readAllBytes (.toPath (io/file file)))))

(defn path-within?
  [root file]
  (.startsWith (.toPath (.getCanonicalFile (io/file file)))
               (.toPath (.getCanonicalFile (io/file root)))))

(defn- regular-files
  [root excluded-root]
  (let [root-file (io/file root)]
    (if (.isFile root-file)
      [root-file]
      (->> (file-seq root-file)
           (filter #(.isFile ^File %))
           (remove #(path-within? excluded-root %))
           (sort-by #(.getPath ^File %))))))

(defn hash-files
  ([roots base]
   (hash-files roots base (io/file "benchmarks" "results")))
  ([roots base excluded-root]
   (let [base-path (.toPath (.getCanonicalFile (io/file base)))
         digest (MessageDigest/getInstance "SHA-256")]
     (doseq [file (mapcat #(regular-files % excluded-root) roots)]
       (let [path (.toPath (.getCanonicalFile ^File file))
             relative (str (.relativize base-path path))]
         (.update digest (.getBytes relative "UTF-8"))
         (.update digest (byte-array [0]))
         (.update digest (Files/readAllBytes path))))
     (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- checked-command
  [command]
  (let [result (bench-protocol/run-process! command {:timeout-ms 30000})]
    (when-not (and (not (:alive? result))
                   (not (:timed-out? result))
                   (= 0 (:exit result)))
      (throw (ex-info "Metadata command failed"
                      {:command command :result result})))
    (str/trim (if (str/blank? (:stdout result))
                (:stderr result)
                (:stdout result)))))

(defn optional-command
  [command]
  (try
    {:status "available"
     :value (checked-command command)}
    (catch Throwable error
      {:status "unavailable"
       :command command
       :reason (ex-message error)})))

(defn node-root-candidate
  [configured-root resolved-repository-root]
  (if configured-root
    (io/file configured-root)
    (io/file resolved-repository-root "nodejs")))

(defn resolve-node-root
  ([]
   (resolve-node-root
    (System/getenv "COPILOT_BENCH_NODE_SDK_ROOT")
    (when-not (System/getenv "COPILOT_BENCH_NODE_SDK_ROOT")
      (checked-command
       ["bash" ".github/skills/update-upstream/scripts/resolve-upstream.sh"]))))
  ([configured-root resolved-repository-root]
   (let [root (node-root-candidate configured-root resolved-repository-root)
         package-json (io/file root "package.json")
         entry (io/file root "dist" "cjs" "index.js")]
     (when-not (.isFile package-json)
       (throw (ex-info "Node SDK root has no package.json"
                       {:node-sdk-root (str root)})))
     (when-not (.isFile entry)
       (throw (ex-info
               "Node SDK root is not built; run npm install and npm run build in that directory"
               {:node-sdk-root (str root) :expected-entry (str entry)})))
     (let [check (bench-protocol/run-process!
                  ["node" "-e"
                   (str "const s=require(" (pr-str (.getCanonicalPath root)) ");"
                        "if(typeof s.CopilotClient!=='function'||"
                        "typeof s.RuntimeConnection?.forUri!=='function')process.exit(2)")]
                  {:timeout-ms 30000})]
       (when-not (= 0 (:exit check))
         (throw (ex-info
                 "Node SDK public package lacks CopilotClient or RuntimeConnection.forUri"
                 {:root (str root) :result check}))))
     (.getCanonicalPath root))))

(def benchmark-input-roots
  ["src"
   "resources"
   "benchmarks"
   "test/github/copilot_sdk/benchmark_contract_test.clj"
   "bb.edn"
   "deps.edn"])

(defn- collect-metadata
  [profile-name profile corpus node-root]
  (let [os (checked-command ["uname" "-a"])
        cpu (if (str/includes? (System/getProperty "os.name") "Mac")
              (checked-command ["sysctl" "-n" "machdep.cpu.brand_string"])
              (checked-command
               ["sh" "-c"
                "grep -m1 -E 'model name|Hardware' /proc/cpuinfo | cut -d: -f2-"]))
        node-repository-root (checked-command
                              ["git" "-C" node-root "rev-parse" "--show-toplevel"])
        package-file (io/file node-root "package.json")
        lock-file (io/file node-root "package-lock.json")
        entry-file (io/file node-root "dist" "cjs" "index.js")
        package (json/read-str (slurp package-file))
        repository-dirty (checked-command
                          ["git" "status" "--porcelain" "--untracked-files=all"])
        node-dirty (checked-command
                    ["git" "-C" node-root "status" "--porcelain"
                     "--untracked-files=all" "--" "."])]
    (merge
     {:schema-version 1
      :fixture-version (get corpus "fixtureVersion")
      :corpus-sha256 (sha256-file "benchmarks/corpus.json")
      :profile profile-name
      :warmup (:warmup profile)
      :iterations (:iterations profile)
      :steady-repetitions (:steady-repetitions profile)
      :cold-start-count (:cold-start-count profile)
      :concurrency (:concurrency profile)
      :execution-order
      {:cold "alternating node/clojure by sample; first sample starts with node"
       :steady "alternating node/clojure by replicate; first replicate starts with clojure"}
      :host-id (sha256-bytes (.getBytes (str os "\n" cpu) "UTF-8"))
      :repository-commit (checked-command ["git" "rev-parse" "HEAD"])
      :repository-dirty-sha256 (sha256-bytes (.getBytes repository-dirty "UTF-8"))
      :repository-dirty-state repository-dirty
      :benchmark-inputs-sha256 (hash-files benchmark-input-roots ".")
      :node-sdk-root node-root
      :node-sdk-repository-root node-repository-root
      :node-sdk-commit (checked-command ["git" "-C" node-root "rev-parse" "HEAD"])
      :node-sdk-dirty-sha256 (sha256-bytes (.getBytes node-dirty "UTF-8"))
      :node-sdk-dirty-state node-dirty
      :node-sdk-package-version (get package "version")
      :node-entry-sha256 (sha256-file entry-file)
      :node-dist-sha256 (hash-files [(io/file node-root "dist")] node-root)
      :node-package-sha256 (sha256-file package-file)
      :node-lock-sha256 (sha256-file lock-file)
      :copilot-cli-version (optional-command ["copilot" "--version"])
      :node-version (checked-command ["node" "--version"])
      :java-version (checked-command ["java" "-version"])
      :clojure-cli-version (checked-command ["clojure" "--version"])
      :babashka-version (checked-command ["bb" "--version"])
      :os os
      :cpu cpu
      :generated-at (.toString (Instant/now))}
     (select-keys profile [:timeout-ms :driver-timeout-ms
                           :bootstrap-seed :bootstrap-resamples
                           :warmup-window-size :stable-window-count
                           :max-warmup-relative-drift :measured-drift-window
                           :max-measured-relative-drift :confirmatory?
                           :confirmatory-min-pairs :familywise-alpha]))))

(defn- evidence-prefix
  [implementation phase replicate]
  (format "%s-%s-%03d" implementation phase replicate))

(def ^:dynamic *fixture-command-builder*
  (fn [state-file trace-file phase implementation]
    ["node" "benchmarks/fixture/server.mjs"
     (str "--corpus=" (.getCanonicalPath (io/file "benchmarks/corpus.json")))
     (str "--state=" (.getCanonicalPath state-file))
     (str "--trace=" (.getCanonicalPath trace-file))
     (str "--phase=" phase)
     (str "--implementation=" implementation)]))

(defn- suppress-cleanup-error!
  [^Throwable primary cleanup]
  (try
    (cleanup)
    (catch Throwable error
      (.addSuppressed primary error))))

(defn- fail-fixture-start!
  [error process stdout-stream stdout-reader readiness-future
   stderr-stream stderr-future]
  (suppress-cleanup-error!
   error #(when (.isAlive ^Process process)
            (bench-protocol/terminate-process! process)))
  (suppress-cleanup-error! error #(.close ^java.io.Closeable stdout-stream))
  (let [readiness-drained? (atom false)]
    (suppress-cleanup-error!
     error
     (fn []
       (when (= ::stream-timeout
                (deref readiness-future 5000 ::stream-timeout))
         (throw (ex-info "Fixture readiness reader did not complete" {})))
       (reset! readiness-drained? true)))
    (when (or @readiness-drained? (realized? readiness-future))
      (suppress-cleanup-error! error #(.close ^java.io.Closeable stdout-reader))))
  (let [stderr-value (atom ::stream-timeout)]
    (suppress-cleanup-error!
     error
     (fn []
       (let [value (deref stderr-future 5000 ::stream-timeout)]
         (when (= ::stream-timeout value)
           (throw (ex-info "Fixture stderr reader did not complete" {})))
         (reset! stderr-value value))))
    (suppress-cleanup-error! error #(.close ^java.io.Closeable stderr-stream))
    (when (= ::stream-timeout @stderr-value)
      (suppress-cleanup-error!
       error
       (fn []
         (let [value (deref stderr-future 5000 ::stream-timeout)]
           (when (= ::stream-timeout value)
             (throw (ex-info "Fixture stderr reader remained blocked" {})))
           (reset! stderr-value value)))))
    (when-not (.isAlive ^Process process)
      (suppress-cleanup-error!
       error #(bench-protocol/unregister-process! process)))
    (let [enriched (ex-info (ex-message error)
                            (assoc (ex-data error)
                                   :stderr (when-not (= ::stream-timeout @stderr-value)
                                             @stderr-value))
                            (ex-cause error))]
      (doseq [suppressed (.getSuppressed ^Throwable error)]
        (.addSuppressed ^Throwable enriched suppressed))
      (throw enriched))))

(def ^:dynamic *fixture-readiness-timeout-ms* 10000)

(defn start-fixture!
  [implementation phase replicate output-dir]
  (let [prefix (evidence-prefix implementation phase replicate)
        state-file (io/file output-dir (str prefix "-fixture.json"))
        trace-file (io/file output-dir (str prefix "-trace.ndjson"))
        command (*fixture-command-builder* state-file trace-file phase implementation)
        process (bench-protocol/start-process!
                 (ProcessBuilder. ^java.util.List command))
        stdout-stream (.getInputStream process)
        stdout-reader (io/reader stdout-stream)
        stderr-stream (.getErrorStream process)
        stderr-future (future (slurp stderr-stream))
        readiness-future (future (.readLine stdout-reader))
        readiness-line (deref readiness-future
                              *fixture-readiness-timeout-ms*
                              ::timeout)]
    (when (or (= ::timeout readiness-line) (nil? readiness-line))
      (fail-fixture-start!
       (ex-info "Fixture did not signal readiness"
                {:implementation implementation
                 :phase phase
                 :replicate replicate})
       process stdout-stream stdout-reader readiness-future
       stderr-stream stderr-future))
    (let [readiness
          (try
            (json/read-str readiness-line :key-fn keyword)
            (catch Throwable parse-error
              (fail-fixture-start!
               (ex-info "Invalid fixture readiness signal"
                        {:readiness-line readiness-line}
                        parse-error)
               process stdout-stream stdout-reader readiness-future
               stderr-stream stderr-future)))]
      (when-not (:ready readiness)
        (fail-fixture-start!
         (ex-info "Invalid fixture readiness signal" {:readiness readiness})
         process stdout-stream stdout-reader readiness-future
         stderr-stream stderr-future))
      {:process process
       :stdout-stream stdout-stream
       :stdout-reader stdout-reader
       :stdout-future (future (slurp stdout-reader))
       :stderr-stream stderr-stream
       :stderr-future stderr-future
       :state-file state-file
       :trace-file trace-file
       :readiness readiness})))

(def ^:dynamic *fixture-process-timeout-ms* 5000)
(def ^:dynamic *fixture-stream-timeout-ms* 5000)

(defn await-fixture-streams!
  [stdout-stream stdout-reader stdout-future stderr-stream stderr-future]
  (let [stdout (deref stdout-future *fixture-stream-timeout-ms* ::stream-timeout)
        stderr (deref stderr-future *fixture-stream-timeout-ms* ::stream-timeout)
        complete? (and (not= ::stream-timeout stdout)
                       (not= ::stream-timeout stderr))]
    (if complete?
      (do
        (.close ^java.io.Closeable stdout-reader)
        {:stdout stdout :stderr stderr})
      (do
        (let [error (ex-info "Fixture stream reader did not complete"
                             {:stdout-complete? (not= ::stream-timeout stdout)
                              :stderr-complete? (not= ::stream-timeout stderr)})
              stdout-drained? (atom (not= ::stream-timeout stdout))]
          (when stdout-stream
            (suppress-cleanup-error!
             error #(.close ^java.io.Closeable stdout-stream)))
          (when stderr-stream
            (suppress-cleanup-error!
             error #(.close ^java.io.Closeable stderr-stream)))
          (suppress-cleanup-error!
           error
           (fn []
             (when (= ::stream-timeout
                      (deref stdout-future *fixture-stream-timeout-ms*
                             ::stream-timeout))
               (throw (ex-info "Fixture stdout reader remained blocked" {})))
             (reset! stdout-drained? true)))
          (when stderr-stream
            (suppress-cleanup-error!
             error
             (fn []
               (when (= ::stream-timeout
                        (deref stderr-future *fixture-stream-timeout-ms*
                               ::stream-timeout))
                 (throw (ex-info "Fixture stderr reader remained blocked" {}))))))
          (when @stdout-drained?
            (suppress-cleanup-error!
             error #(.close ^java.io.Closeable stdout-reader)))
          (throw error))))))

(defn stop-fixture!
  [{:keys [^Process process stdout-stream stdout-reader stdout-future
           stderr-stream stderr-future state-file]
    :as fixture}]
  (let [primary-error (atom nil)]
    (try
      (try
        (.destroy process)
        (when-not (.waitFor process *fixture-process-timeout-ms* TimeUnit/MILLISECONDS)
          (bench-protocol/terminate-process! process))
        (when (.isAlive process)
          (throw (ex-info "Fixture cleanup was not confirmed" {:pid (.pid process)})))
        (let [{:keys [stderr]}
              (await-fixture-streams! stdout-stream stdout-reader
                                      stdout-future stderr-stream stderr-future)]
          (when-not (zero? (.exitValue process))
            (throw (ex-info "Fixture exited unsuccessfully"
                            {:exit (.exitValue process) :stderr stderr}))))
        (when-not (.isFile state-file)
          (throw (ex-info "Fixture did not write final state" {:state-file (str state-file)})))
        (assoc fixture :state (json/read-str (slurp state-file) :key-fn keyword))
        (catch Throwable error
          (reset! primary-error error)
          (throw error)))
      (finally
        (when-not (.isAlive process)
          (try
            (bench-protocol/unregister-process! process)
            (catch Throwable unregister-error
              (if-let [primary @primary-error]
                (.addSuppressed ^Throwable primary unregister-error)
                (throw unregister-error)))))))))

(defn- fixture-uri
  [fixture]
  (str (get-in fixture [:readiness :host])
       ":"
       (get-in fixture [:readiness :port])))

(defn- driver-command
  [implementation mode uri output stability-output run-id profile node-root
   sample-index replicate sample-offset]
  (let [common [(str "--mode=" mode)
                (str "--uri=" uri)
                (str "--corpus=" (.getCanonicalPath (io/file "benchmarks/corpus.json")))
                (str "--output=" (.getCanonicalPath output))
                (str "--stability-output=" (.getCanonicalPath stability-output))
                (str "--run-id=" run-id)
                (str "--warmup=" (:warmup profile))
                (str "--iterations=" (:iterations profile))
                (str "--timeout-ms=" (:timeout-ms profile))
                (str "--sample-index=" sample-index)
                (str "--replicate=" replicate)
                (str "--sample-offset=" sample-offset)
                (str "--warmup-window-size=" (:warmup-window-size profile))
                (str "--stable-window-count=" (:stable-window-count profile))
                (str "--max-warmup-relative-drift="
                     (:max-warmup-relative-drift profile))
                (str "--measured-drift-window=" (:measured-drift-window profile))
                (str "--max-measured-relative-drift="
                     (:max-measured-relative-drift profile))]]
    (case implementation
      "node" (into ["node" "benchmarks/node/driver.cjs"
                    (str "--node-sdk-root=" node-root)]
                   common)
      "clojure" (into ["clojure"
                       "-J-Dorg.slf4j.simpleLogger.defaultLogLevel=error"
                       "-M:bench" "-m" "github.copilot-sdk.bench.driver"]
                      common))))

(defn- run-driver!
  [command timeout-ms]
  (let [result (bench-protocol/run-process! command {:timeout-ms timeout-ms})]
    (when-not (and (not (:alive? result))
                   (not (:timed-out? result))
                   (= 0 (:exit result)))
      (throw (ex-info "Benchmark driver failed"
                      {:command command :result result})))
    result))

(defn- append-observation!
  [output observation]
  (spit output (str (json/write-str observation) "\n") :append true))

(defn- expected-counts
  [phase profile]
  (if (= "cold" phase)
    {:connect 1 :ping 1}
    {:connect 1
     :ping (+ 2 (:warmup profile) (:iterations profile))
     :session.create 1
     :session.destroy 1
     :session.send (+ 2 (:warmup profile) (:iterations profile))}))

(defn- validate-fixture-state!
  [implementation phase replicate profile state]
  (when (:failed state)
    (throw (ex-info "Fixture rejected a benchmark request"
                    {:implementation implementation
                     :phase phase
                     :replicate replicate
                     :state state})))
  (when-not (= (expected-counts phase profile) (:counts state))
    (throw (ex-info "Fixture request count mismatch"
                    {:implementation implementation
                     :phase phase
                     :replicate replicate
                     :expected (expected-counts phase profile)
                     :actual (:counts state)})))
  (when-not (= 1 (:connectionCount state))
    (throw (ex-info "Fixture connection count mismatch"
                    {:implementation implementation
                     :phase phase
                     :replicate replicate
                     :expected 1
                     :actual (:connectionCount state)}))))

(defn- run-sample!
  [implementation phase replicate output stability-output run-id profile node-root
   output-dir]
  (let [fixture (start-fixture! implementation phase replicate output-dir)
        primary-error (atom nil)
        finished (atom nil)]
    (try
      (let [mode (if (= phase "cold") "cold" "steady")
            command (driver-command
                     implementation mode (fixture-uri fixture) output stability-output
                     run-id profile node-root replicate replicate
                     (if (= phase "steady") (* replicate (:iterations profile)) 0))
            started (System/nanoTime)]
        (run-driver! command (:driver-timeout-ms profile))
        (when (= phase "cold")
          (append-observation!
           output
           {"schema-version" 1
            "run-id" run-id
            "implementation" implementation
            "phase" "cold"
            "workload" "process"
            "metric" "latency"
            "replicate" replicate
            "sample-index" replicate
            "value" (/ (double (- (System/nanoTime) started)) 1000000.0)
            "unit" "ms"})))
      (catch Throwable error
        (reset! primary-error error))
      (finally
        (try
          (reset! finished (stop-fixture! fixture))
          (catch Throwable cleanup-error
            (if-let [primary @primary-error]
              (.addSuppressed ^Throwable primary cleanup-error)
              (reset! primary-error cleanup-error))))))
    (when-let [error @primary-error]
      (throw error))
    (validate-fixture-state! implementation phase replicate profile
                             (:state @finished))
    @finished))

(defn- alternating-order
  [index first-implementation]
  (let [other (if (= first-implementation "node") "clojure" "node")]
    (if (even? index)
      [first-implementation other]
      [other first-implementation])))

(defn- trace-hashes
  [trace-file]
  (with-open [reader (io/reader trace-file)]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(get (json/read-str % :key-fn keyword) :comparableSha256)))))

(defn- assert-fixtures-match!
  [phase replicate matched]
  (let [clojure (get matched "clojure")
        node (get matched "node")
        clojure-state (:state clojure)
        node-state (:state node)
        clojure-trace (trace-hashes (:trace-file clojure))
        node-trace (trace-hashes (:trace-file node))]
    (when-not (= (dissoc clojure-state :implementation)
                 (dissoc node-state :implementation))
      (throw (ex-info "Node and Clojure fixture evidence differs"
                      {:phase phase
                       :replicate replicate
                       :clojure clojure-state
                       :node node-state})))
    (when-not (= clojure-trace node-trace)
      (throw (ex-info "Node and Clojure comparable request traces differ"
                      {:phase phase
                       :replicate replicate
                       :clojure-count (count clojure-trace)
                       :node-count (count node-trace)})))))

(defn- run-matched-phase!
  [phase replicate-count output stability-output run-id profile node-root output-dir]
  (dotimes [replicate replicate-count]
    (let [first-implementation (if (= phase "cold") "node" "clojure")
          matched
          (into {}
                (map (fn [implementation]
                       [implementation
                        (run-sample! implementation phase replicate output
                                     stability-output run-id profile node-root
                                     output-dir)]))
                (alternating-order replicate first-implementation))]
      (assert-fixtures-match! phase replicate matched)))
  (println "completed matched" phase))

(defn write-summary!
  [summary-file observations stability profile metadata]
  (analysis/validate-observation-set! observations profile)
  (analysis/validate-stability-set! stability profile)
  (let [summary (analysis/analyze observations metadata stability)]
    (spit summary-file (json/write-str summary :escape-slash false))
    summary))

(defn -main
  [& raw-args]
  (let [args (parse-args raw-args)
        profile-name (get args "profile" "smoke")
        profile (or (get profiles profile-name)
                    (throw (ex-info "Unknown benchmark profile"
                                    {:profile profile-name
                                     :profiles (keys profiles)})))
        output-dir (io/file (get args "output"
                                 (str "benchmarks/results/" profile-name "-"
                                      (System/currentTimeMillis))))
        _ (when (and (.exists output-dir) (seq (.list output-dir)))
            (throw (ex-info "Benchmark output directory must be empty"
                            {:output (.getCanonicalPath output-dir)})))
        _ (.mkdirs output-dir)
        run-id (str profile-name "-" (System/currentTimeMillis))
        node-root (resolve-node-root)
        corpus (json/read-str (slurp "benchmarks/corpus.json"))
        initial-metadata (collect-metadata profile-name profile corpus node-root)
        raw-file (io/file output-dir "observations.ndjson")
        stability-file (io/file output-dir "stability.ndjson")]
    (spit (io/file output-dir "metadata.json")
          (json/write-str initial-metadata :escape-slash false))
    (run-matched-phase! "cold" (:cold-start-count profile)
                        raw-file stability-file run-id profile node-root output-dir)
    (run-matched-phase! "steady" (:steady-repetitions profile)
                        raw-file stability-file run-id profile node-root output-dir)
    (let [final-metadata (collect-metadata profile-name profile corpus node-root)
          _ (spit (io/file output-dir "metadata-final.json")
                  (json/write-str final-metadata :escape-slash false))
          _ (analysis/assert-comparable! initial-metadata final-metadata)
          observations (analysis/read-observations raw-file)
          stability (analysis/read-stability-records stability-file)
          _ (write-summary! (io/file output-dir "summary.json")
                            observations stability profile initial-metadata)]
      (println (json/write-str
                {:ok true
                 :profile profile-name
                 :output (.getCanonicalPath output-dir)
                 :observation-count (count observations)})))))
