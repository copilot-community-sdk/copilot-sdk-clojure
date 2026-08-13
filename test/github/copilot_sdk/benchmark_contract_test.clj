(ns github.copilot-sdk.benchmark-contract-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.bench.analysis :as analysis]
            [github.copilot-sdk.bench.driver :as bench-driver]
            [github.copilot-sdk.bench.protocol :as bench-protocol]
            [github.copilot-sdk.bench.runner :as runner])
  (:import [java.io BufferedReader PipedInputStream PipedOutputStream StringReader]
           [java.lang ProcessHandle]
           [java.nio.file Files]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def matching-metadata
  (merge
   (zipmap analysis/comparable-metadata-keys (repeat "same"))
   {:schema-version 1
    :fixture-version "1"
    :corpus-sha256 "abc"
    :profile "smoke"
    :warmup 2
    :iterations 5
    :steady-repetitions 2
    :concurrency 1
    :host-id "host-a"
    :repository-commit "repo-a"
    :repository-dirty-sha256 "dirty-a"
    :benchmark-inputs-sha256 "inputs-a"}))

(defn observation
  [implementation phase workload metric replicate sample-index value]
  {:schema-version 1
   :run-id "run"
   :implementation implementation
   :phase phase
   :workload workload
   :metric metric
   :replicate replicate
   :sample-index sample-index
   :value value
   :unit (if (= metric "rss-delta") "bytes" "ms")})

(deftest observation-schema-contract
  (testing "valid latency, batch, and memory observations are accepted"
    (doseq [value [(observation "clojure" "steady" "ping" "latency" 0 0 1.25)
                   (observation "node" "steady" "ping" "batch-duration" 0 0 10.0)
                   (observation "node" "steady" "ping" "rss-delta" 0 0 4096)]]
      (is (= value (analysis/validate-observation! value)))))
  (testing "schema mutations fail loudly"
    (let [valid (observation "node" "steady" "ping" "latency" 0 0 1.0)]
      (doseq [invalid [(assoc valid :extra true)
                       (dissoc valid :replicate)
                       (assoc valid :run-id "")
                       (assoc valid :implementation "python")
                       (assoc valid :phase "warm")
                       (assoc valid :workload "unknown")
                       (assoc valid :metric "rss-delta" :unit "ms")
                       (assoc valid :replicate -1)
                       (assoc valid :sample-index -1)
                       (assoc valid :value Double/NaN)
                       (assoc valid :value Double/POSITIVE_INFINITY)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid benchmark observation"
                              (analysis/validate-observation! invalid)))))))

(deftest percentile-and-matched-cluster-bootstrap-contract
  (is (= 5.0 (analysis/percentile [1 2 3 4 5 6 7 8 9] 0.5)))
  (is (= 9.0 (analysis/percentile [1 2 3 4 5 6 7 8 9] 0.95)))
  (let [constant (analysis/bootstrap-matched-cluster-ratio-ci
                  {0 [2 2] 1 [2 2]}
                  {0 [1 1] 1 [1 1]}
                  {:seed 4242 :resamples 500})
        heterogeneous (analysis/bootstrap-matched-cluster-ratio-ci
                       {0 [1 1] 1 [9 9]}
                       {0 [1 1] 1 [1 1]}
                       {:seed 4242 :resamples 500})]
    (is (= {:low 2.0 :high 2.0 :independent-clusters 2} constant))
    (is (= 1.0 (:low heterogeneous)))
    (is (= 9.0 (:high heterogeneous)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"at least two paired"
         (analysis/bootstrap-matched-cluster-ratio-ci
          {0 [2]} {0 [1]} {:seed 1 :resamples 100})))))

(deftest exact-observation-set-contract
  (let [profile {:cold-start-count 2 :steady-repetitions 2 :iterations 2}
        observations
        (mapv
         (fn [[implementation phase workload metric replicate sample-index]]
           (observation implementation phase workload metric
                        replicate sample-index 1.0))
         (analysis/expected-observation-keys profile))]
    (is (= observations
           (analysis/validate-observation-set! observations profile)))
    (doseq [invalid [(conj observations (first observations))
                     (pop observations)
                     (conj (pop observations)
                           (assoc (first observations) :sample-index 999))]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid benchmark observation set"
                            (analysis/validate-observation-set! invalid profile))))))

(deftest stability-record-contract
  (let [profile {:warmup 20
                 :warmup-window-size 5
                 :stable-window-count 2
                 :max-warmup-relative-drift 0.10
                 :steady-repetitions 1
                 :iterations 10
                 :max-measured-relative-drift 0.10}
        records
        (vec
         (for [implementation ["clojure" "node"]
               workload ["ping" "send-and-wait"]
               [kind window-index operation-count median reference drift stable]
               [["warmup-window" 0 5 1.0 nil nil false]
                ["warmup-window" 1 10 1.0 nil nil false]
                ["warmup-window" 2 15 1.0 nil nil false]
                ["warmup-window" 3 20 1.0 nil 0.0 true]
                ["measurement-drift" 0 10 1.0 1.05 0.05 true]]]
           {:schema-version 1
            :run-id "stability"
            :implementation implementation
            :workload workload
            :replicate 0
            :kind kind
            :window-index window-index
            :operation-count operation-count
            :median-ms median
            :reference-median-ms reference
            :relative-drift drift
            :stable stable}))]
    (is (= records (analysis/validate-stability-set! records profile)))
    (doseq [invalid [(conj records (first records))
                     (pop records)
                     (assoc-in records [3 :stable] false)
                     (assoc-in records [3 :relative-drift] 99.0)
                     (-> records
                         (assoc-in [2 :median-ms] 99.0)
                         (assoc-in [3 :median-ms] 99.0))
                     (assoc-in records [4 :relative-drift] 99.0)
                     (assoc-in records [4 :reference-median-ms] 0.0)
                     (assoc-in records [4 :reference-median-ms] -1.0)
                     (assoc-in records [0 :reference-median-ms] 1.0)
                     (assoc-in records [0 :kind] "measurement-drift")
                     (assoc-in records [4 :operation-count] 9)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (analysis/validate-stability-set! invalid profile))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid benchmark stability record"
         (analysis/validate-stability-record!
          (assoc (first records) :median-ms Double/NaN))))))

(deftest stability-schema-kind-contract
  (let [schema (json/read-str
                (slurp "benchmarks/schema/stability.schema.json")
                :key-fn keyword)
        conditions (:allOf schema)
        by-kind (into {}
                      (map (fn [condition]
                             [(get-in condition [:if :properties :kind :const])
                              (:then condition)]))
                      conditions)]
    (is (= {:type "number" :exclusiveMinimum 0}
           (get-in by-kind ["measurement-drift" :properties
                            :reference-median-ms])))
    (is (= {:type "number" :minimum 0}
           (get-in by-kind ["measurement-drift" :properties :relative-drift])))
    (is (= {:const nil}
           (get-in by-kind ["warmup-window" :properties
                            :reference-median-ms])))))

(deftest analysis-contract
  (let [observations
        (vec
         (for [implementation ["clojure" "node"]
               [phase workload] [["cold" "process"]
                                 ["cold" "connect-ping"]
                                 ["steady" "ping"]
                                 ["steady" "send-and-wait"]]
               replicate (range 2)
               sample (range 2)]
           (observation implementation phase workload "latency" replicate
                        (+ (* replicate 2) sample)
                        (if (= "clojure" implementation) 2.0 1.0))))
        summary (analysis/analyze observations
                                  {:iterations 4
                                   :bootstrap-seed 7
                                   :bootstrap-resamples 100})
        ping (some #(when (and (= "steady" (:phase %))
                               (= "ping" (:workload %)))
                      %)
                   (:descriptive-results summary))]
    (is (= 4 (get-in ping [:clojure :n])))
    (is (= 2.0 (get-in ping [:clojure :p99-ms])))
    (is (= 2.0 (get-in ping [:clojure-node-ratio :pooled-p50-latency])))
    (is (= "not-run" (get-in summary [:confirmatory :status])))))

(deftest confirmatory-statistics-contract
  (let [effects (mapv (fn [replicate]
                        {:replicate replicate
                         :clojure-value 2.0
                         :node-value 1.0
                         :ratio 2.0
                         :log-ratio (Math/log 2.0)})
                      (range 5))]
    (is (= 2.0 (analysis/geometric-mean-ratio effects)))
    (is (= {:low 2.0 :high 2.0 :independent-pairs 5}
           (analysis/bootstrap-paired-effect-ci
            effects {:seed 7 :resamples 100})))
    (is (= {:p-value 0.0625
            :permutations 32
            :minimum-two-sided-resolution 0.0625
            :statistic "absolute mean paired log ratio"}
           (analysis/exact-sign-flip-p-value effects)))
    (is (= {:d 0.008 :a 0.03 :c 0.06 :b 0.06}
           (analysis/holm-adjust {:a 0.01 :b 0.04 :c 0.03 :d 0.002})))
    (is (= "clojure-lower-latency"
           (analysis/conclusion-label "ping-latency" 0.9 0.01 0.05)))
    (is (= "node-higher-throughput"
           (analysis/conclusion-label "ping-throughput" 0.9 0.01 0.05)))
    (is (= "no-supported-difference"
           (analysis/conclusion-label "ping-latency" 0.9 0.051 0.05))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Insufficient or mismatched"
       (analysis/paired-log-effects {0 1.0} {0 1.0} 2)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Insufficient or mismatched"
       (analysis/paired-log-effects {0 1.0 1 1.0} {0 1.0 2 1.0} 2))))

(deftest descriptive-rss-ratio-requires-positive-pairs
  (is (= 2.0 (analysis/descriptive-rss-ratio {0 [2.0] 1 [4.0]}
                                             {0 [1.0] 1 [2.0]})))
  (is (nil? (analysis/descriptive-rss-ratio {0 [0.0] 1 [4.0]}
                                            {0 [1.0] 1 [2.0]})))
  (is (nil? (analysis/descriptive-rss-ratio {0 [-1.0] 1 [4.0]}
                                            {0 [1.0] 1 [2.0]})))
  (is (nil? (analysis/descriptive-rss-ratio {0 [2.0] 1 [4.0]}
                                            {0 [1.0] 1 [0.0]})))
  (is (nil? (analysis/descriptive-rss-ratio {0 [2.0] 1 [-4.0]}
                                            {0 [1.0] 1 [2.0]})))
  (is (nil? (analysis/descriptive-rss-ratio {0 [2.0]} {1 [1.0]}))))

(deftest confirmatory-family-contract
  (let [observations
        (vec
         (for [implementation ["clojure" "node"]
               replicate (range 20)
               workload ["ping" "send-and-wait"]
               [metric value] [["latency" (if (= implementation "clojure") 2.0 1.0)]
                               ["batch-duration"
                                (if (= implementation "clojure") 500.0 1000.0)]]]
           (observation implementation "steady" workload metric
                        replicate replicate value)))
        result (analysis/confirmatory-analysis
                observations
                {:iterations 1000
                 :bootstrap-seed 7
                 :bootstrap-resamples 100
                 :confirmatory-min-pairs 20
                 :familywise-alpha 0.05})]
    (is (= "confirmatory" (:status result)))
    (is (= 4 (count (:endpoints result))))
    (is (every? #(= 20 (:process-pairs %)) (:endpoints result)))
    (is (every? #(<= (:holm-adjusted-p-value %) 0.05) (:endpoints result)))
    (is (= #{"node-lower-latency" "clojure-higher-throughput"}
           (set (map :conclusion (:endpoints result)))))))

(deftest incomplete-run-does-not-write-summary
  (let [directory (.toFile (Files/createTempDirectory
                            "copilot-benchmark-incomplete"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        summary-file (io/file directory "summary.json")]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid benchmark observation set"
           (runner/write-summary!
            summary-file [] []
            {:cold-start-count 1
             :steady-repetitions 1
             :iterations 1
             :warmup 4
             :warmup-window-size 1
             :stable-window-count 2}
            {:iterations 1 :confirmatory? false})))
      (is (false? (.exists summary-file)))
      (finally
        (.delete directory)))))

(deftest metadata-comparison-contract
  (is (= matching-metadata
         (analysis/assert-comparable! matching-metadata matching-metadata)))
  (testing "provenance and profile mismatches reject comparison"
    (doseq [[key value] [[:corpus-sha256 "different"]
                         [:profile "rigorous"]
                         [:iterations 1000]
                         [:host-id "host-b"]
                         [:repository-commit "repo-b"]
                         [:repository-dirty-sha256 "dirty-b"]
                         [:benchmark-inputs-sha256 "inputs-b"]]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Benchmark metadata mismatch"
           (analysis/assert-comparable! matching-metadata
                                        (assoc matching-metadata key value)))))))
(deftest metadata-missing-key-contract
  (let [metadata-key :corpus-sha256]
    (doseq [[left right expected-left expected-right]
            [[(dissoc matching-metadata metadata-key)
              (dissoc matching-metadata metadata-key)
              #{metadata-key} #{metadata-key}]
             [(dissoc matching-metadata metadata-key)
              matching-metadata
              #{metadata-key} #{}]
             [matching-metadata
              (dissoc matching-metadata metadata-key)
              #{} #{metadata-key}]]]
      (let [error (try
                    (analysis/assert-comparable! left right)
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is error)
        (is (= expected-left (:missing-left (ex-data error))))
        (is (= expected-right (:missing-right (ex-data error))))))))

(deftest configured-node-root-contract
  (is (= "/tmp/package"
         (.getPath (runner/node-root-candidate "/tmp/package" "/tmp/repository"))))
  (is (= "/tmp/repository/nodejs"
         (.getPath (runner/node-root-candidate nil "/tmp/repository")))))

(deftest optional-cli-metadata-contract
  (let [result (runner/optional-command
                ["definitely-not-a-real-copilot-benchmark-command"])]
    (is (= "unavailable" (:status result)))
    (is (= ["definitely-not-a-real-copilot-benchmark-command"]
           (:command result)))
    (is (not-empty (:reason result)))))

(deftest validation-is-outside-sampled-operations
  (let [calls (atom [])
        operation #(do (swap! calls conj :operation) {:invalid true})
        validate #(do (swap! calls conj :validate)
                      (throw (ex-info "invalid deterministic result" {:result %})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid deterministic result"
                          (bench-driver/validate-preflight! operation validate)))
    (is (= [:operation :validate] @calls))
    (reset! calls [])
    (is (= 3 (count (bench-driver/sample-operations operation 3))))
    (is (= [:operation :operation :operation] @calls))))

(deftest clojure-driver-argument-contract
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Expected --name=value"
                        (bench-driver/validate-args! ["foo=bar"])))
  (let [common ["--mode=steady"
                "--uri=127.0.0.1:1"
                "--corpus=corpus.json"
                "--output=observations.ndjson"
                "--run-id=run"]
        steady (mapv #(str "--" % "=1") bench-driver/steady-required-args)]
    (doseq [missing bench-driver/steady-required-args]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           (re-pattern (str "Missing --" missing))
           (bench-driver/validate-args!
            (into common
                  (remove #(str/starts-with? % (str "--" missing "=")) steady))))))
    (is (= "cold"
           (get (bench-driver/validate-args!
                 (assoc common 0 "--mode=cold"))
                "mode")))))

(deftest protocol-frame-contract
  (let [payload "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"message\":\"bench\"}}"
        frame (bench-protocol/encode-frame payload)]
    (is (= payload (bench-protocol/decode-frame frame))))
  (testing "truncated and malformed frames fail"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Incomplete JSON-RPC frame"
                          (bench-protocol/decode-frame
                           (.getBytes "Content-Length: 10\r\n\r\n{}" "UTF-8"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing Content-Length"
                          (bench-protocol/decode-frame
                           (.getBytes "X: 2\r\n\r\n{}" "UTF-8"))))))

(deftest bounded-process-cleanup-contract
  (testing "success and nonzero exit return complete streams"
    (let [success (bench-protocol/run-process!
                   ["sh" "-c" "printf ready; printf warning >&2"]
                   {:timeout-ms 1000})
          failed (bench-protocol/run-process!
                  ["sh" "-c" "printf failure >&2; exit 7"]
                  {:timeout-ms 1000})]
      (is (= {:exit 0
              :stdout "ready"
              :stderr "warning"
              :timed-out? false
              :alive? false}
             success))
      (is (= "failure" (:stderr failed)))
      (is (= 7 (:exit failed)))
      (is (false? (:alive? failed)))))
  (testing "timeout terminates the descendant process tree"
    (let [timed-out (bench-protocol/run-process!
                     ["sh" "-c" "sleep 5 & wait"]
                     {:timeout-ms 100})]
      (is (:timed-out? timed-out))
      (is (false? (:alive? timed-out))))))

(deftest run-process-preserves-termination-failure
  (bench-protocol/reset-process-registry-for-tests!)
  (let [primary (ex-info "forced termination failure" {:kind :primary})
        real-terminate bench-protocol/terminate-process!]
    (try
      (let [error (with-redefs [bench-protocol/terminate-process!
                                (fn [_] (throw primary))]
                    (try
                      (bench-protocol/run-process!
                       ["sleep" "5"] {:timeout-ms 10})
                      nil
                      (catch Throwable error error)))]
        (is (identical? primary error))
        (is (= "forced termination failure" (ex-message error)))
        (is (= 1 (bench-protocol/tracked-process-count))))
      (finally
        (doseq [process (:processes (bench-protocol/process-registry-state))]
          (when (.isAlive ^Process process)
            (real-terminate process))
          (when-not (.isAlive ^Process process)
            (bench-protocol/unregister-process! process)))
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest benchmark-process-registry-contract
  (bench-protocol/reset-process-registry-for-tests!)
  (is (identical? (bench-protocol/install-shutdown-hook!)
                  (bench-protocol/install-shutdown-hook!)))
  (is (zero? (bench-protocol/tracked-process-count)))
  (let [tracked (.start (ProcessBuilder. ^java.util.List
                         ["sh" "-c" "sleep 5 & echo ready; wait"]))
        unrelated (.start (ProcessBuilder. ^java.util.List ["sleep" "5"]))
        reader (io/reader (.getInputStream tracked))]
    (try
      (bench-protocol/register-process! tracked)
      (is (= "ready" (deref (future (.readLine reader)) 2000 ::timeout)))
      (let [descendants (with-open [stream (.descendants (.toHandle tracked))]
                          (vec (iterator-seq (.iterator stream))))]
        (is (seq descendants))
        (let [handles (bench-protocol/termination-handles tracked)]
          (is (= (.pid tracked) (.pid ^ProcessHandle (last handles))))
          (is (every? #(not= (.pid tracked) (.pid ^ProcessHandle %))
                      (butlast handles))))
        (is (= 1 (bench-protocol/tracked-process-count)))
        (is (empty? (bench-protocol/cleanup-tracked-processes!)))
        (is (false? (.isAlive tracked)))
        (is (integer? (.exitValue tracked)))
        (is (every? #(not (.isAlive ^ProcessHandle %)) descendants))
        (is (.isAlive unrelated))
        (is (empty? (bench-protocol/cleanup-tracked-processes!)))
        (is (zero? (bench-protocol/tracked-process-count))))
      (finally
        (.close reader)
        (when (.isAlive tracked)
          (bench-protocol/terminate-process! tracked))
        (bench-protocol/unregister-process! tracked)
        (when (.isAlive unrelated)
          (bench-protocol/terminate-process! unrelated))
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest benchmark-process-registry-reports-cleanup-errors
  (bench-protocol/reset-process-registry-for-tests!)
  (let [process (.start (ProcessBuilder. ^java.util.List ["sleep" "5"]))]
    (try
      (bench-protocol/register-process! process)
      (let [failure (ex-info "forced cleanup failure" {})
            errors (with-redefs [bench-protocol/terminate-process!
                                 (fn [_] (throw failure))]
                     (bench-protocol/cleanup-tracked-processes!))]
        (is (= [failure] errors))
        (is (= 1 (bench-protocol/tracked-process-count)))
        (is (.isAlive process)))
      (finally
        (when (.isAlive process)
          (bench-protocol/terminate-process! process))
        (bench-protocol/unregister-process! process)
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest live-process-cannot-be-unregistered
  (bench-protocol/reset-process-registry-for-tests!)
  (let [process (.start (ProcessBuilder. ^java.util.List ["sleep" "5"]))]
    (try
      (bench-protocol/register-process! process)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot unregister a live"
                            (bench-protocol/unregister-process! process)))
      (is (= 1 (bench-protocol/tracked-process-count)))
      (bench-protocol/terminate-process! process)
      (is (false? (.isAlive process)))
      (is (integer? (.exitValue process)))
      (bench-protocol/unregister-process! process)
      (is (zero? (bench-protocol/tracked-process-count)))
      (finally
        (when (.isAlive process)
          (bench-protocol/terminate-process! process))
        (when-not (.isAlive process)
          (bench-protocol/unregister-process! process))
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest late-registration-is-drained-during-shutdown
  (bench-protocol/reset-process-registry-for-tests!)
  (let [primary (.start (ProcessBuilder. ^java.util.List ["sleep" "5"]))
        late (.start (ProcessBuilder. ^java.util.List ["sleep" "5"]))
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        real-terminate bench-protocol/terminate-process!]
    (try
      (bench-protocol/register-process! primary)
      (with-redefs [bench-protocol/terminate-process!
                    (fn [process]
                      (when (identical? process primary)
                        (.countDown entered)
                        (.await release 2 TimeUnit/SECONDS))
                      (real-terminate process))]
        (let [cleanup (future (bench-protocol/cleanup-tracked-processes!))]
          (is (.await entered 2 TimeUnit/SECONDS))
          (let [registration (future (bench-protocol/register-process! late))]
            (.countDown release)
            @registration
            (is (empty? @cleanup)))))
      (is (false? (.isAlive primary)))
      (is (false? (.isAlive late)))
      (is (zero? (bench-protocol/tracked-process-count)))
      (finally
        (doseq [process [primary late]]
          (when (.isAlive process)
            (real-terminate process))
          (when-not (.isAlive process)
            (bench-protocol/unregister-process! process)))
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest cleanup-cannot-pass-spawn-before-registration
  (bench-protocol/reset-process-registry-for-tests!)
  (let [started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        process-ref (promise)]
    (try
      (let [creation
            (binding [bench-protocol/*after-process-start*
                      (fn [process]
                        (deliver process-ref process)
                        (.countDown started)
                        (.await release 2 TimeUnit/SECONDS))]
              (future
                (bench-protocol/start-process!
                 (ProcessBuilder. ^java.util.List ["sleep" "5"]))))]
        (is (.await started 2 TimeUnit/SECONDS))
        (let [cleanup (future (bench-protocol/cleanup-tracked-processes!))]
          (is (= ::timeout (deref cleanup 100 ::timeout)))
          (.countDown release)
          (let [process @creation]
            (is (identical? @process-ref process))
            (is (empty? @cleanup))
            (is (false? (.isAlive ^Process process)))
            (is (zero? (bench-protocol/tracked-process-count))))))
      (finally
        (when-let [process (deref process-ref 0 nil)]
          (when (.isAlive ^Process process)
            (bench-protocol/terminate-process! process))
          (when-not (.isAlive ^Process process)
            (bench-protocol/unregister-process! process)))
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest process-start-is-refused-after-shutdown
  (bench-protocol/reset-process-registry-for-tests!)
  (let [directory (.toFile (Files/createTempDirectory
                            "copilot-benchmark-no-late-spawn"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        marker (io/file directory "spawned")]
    (try
      (is (empty? (bench-protocol/cleanup-tracked-processes!)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"creation refused"
           (bench-protocol/start-process!
            (ProcessBuilder. ^java.util.List
             ["sh" "-c" (str "touch " (.getCanonicalPath marker))]))))
      (is (false? (.exists marker)))
      (finally
        (.delete marker)
        (.delete directory)
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest stop-fixture-preserves-live-termination-failure
  (bench-protocol/reset-process-registry-for-tests!)
  (let [process (bench-protocol/start-process!
                 (ProcessBuilder.
                  ^java.util.List
                  ["sh" "-c" "trap '' TERM; echo ready; while :; do sleep 1; done"]))
        reader (io/reader (.getInputStream process))
        ready (deref (future (.readLine reader)) 2000 ::timeout)
        stdout-future (future (slurp reader))
        stderr-future (future (slurp (.getErrorStream process)))
        primary (ex-info "forced fixture termination failure" {})
        real-terminate bench-protocol/terminate-process!]
    (try
      (is (= "ready" ready))
      (let [error
            (binding [runner/*fixture-process-timeout-ms* 10]
              (with-redefs [bench-protocol/terminate-process!
                            (fn [_] (throw primary))]
                (try
                  (runner/stop-fixture!
                   {:process process
                    :stdout-reader reader
                    :stdout-future stdout-future
                    :stderr-future stderr-future
                    :state-file (io/file "does-not-exist")})
                  nil
                  (catch Throwable error error))))]
        (is (identical? primary error))
        (is (.isAlive process))
        (is (= 1 (bench-protocol/tracked-process-count))))
      (finally
        (when (.isAlive process)
          (real-terminate process))
        (bench-protocol/unregister-process! process)
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest stop-fixture-suppresses-dead-unregister-failure
  (bench-protocol/reset-process-registry-for-tests!)
  (let [process (bench-protocol/start-process!
                 (ProcessBuilder. ^java.util.List ["sh" "-c" "exit 0"]))
        _ (.waitFor process)
        stdout-reader (BufferedReader. (StringReader. ""))
        stdout-future (promise)
        stderr-future (doto (promise) (deliver ""))
        unregister-failure (ex-info "forced unregister failure" {})
        real-unregister bench-protocol/unregister-process!]
    (try
      (let [error
            (binding [runner/*fixture-stream-timeout-ms* 10]
              (with-redefs [bench-protocol/unregister-process!
                            (fn [_] (throw unregister-failure))]
                (try
                  (runner/stop-fixture!
                   {:process process
                    :stdout-reader stdout-reader
                    :stdout-future stdout-future
                    :stderr-future stderr-future
                    :state-file (io/file "does-not-exist")})
                  nil
                  (catch Throwable error error))))]
        (is (= "Fixture stream reader did not complete" (ex-message error)))
        (is (= [unregister-failure] (vec (.getSuppressed ^Throwable error))))
        (is (= 1 (bench-protocol/tracked-process-count))))
      (finally
        (real-unregister process)
        (bench-protocol/reset-process-registry-for-tests!)))))

(deftest fixture-stream-timeout-does-not-block-on-reader-close
  (let [stdout-stream (PipedInputStream.)
        writer (PipedOutputStream. stdout-stream)
        reader (BufferedReader. (io/reader stdout-stream))
        stdout-future (future (slurp reader))
        stderr-future (doto (promise) (deliver ""))]
    (try
      (let [result
            (deref
             (future
               (binding [runner/*fixture-stream-timeout-ms* 10]
                 (try
                   (runner/await-fixture-streams!
                    stdout-stream reader stdout-future stderr-future)
                   nil
                   (catch Throwable error error))))
             1000
             ::timeout)]
        (is (not= ::timeout result))
        (is (= "Fixture stream reader did not complete" (ex-message result))))
      (finally
        (.close writer)
        (future-cancel stdout-future)))))
