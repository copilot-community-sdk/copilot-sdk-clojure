(ns github.copilot-sdk.bench.driver
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [github.copilot-sdk.bench.diagnostics :as diagnostics]
            [github.copilot-sdk :as sdk]))

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

(defn- require-args!
  [args names]
  (doseq [name names]
    (when (str/blank? (get args name))
      (throw (ex-info (str "Missing --" name) {:argument name}))))
  args)

(def common-required-args
  ["mode" "uri" "corpus" "output" "run-id"])

(def steady-required-args
  ["stability-output"
   "diagnostics-output"
   "warmup"
   "iterations"
   "timeout-ms"
   "replicate"
   "pair-order-index"
   "sample-offset"
   "warmup-window-size"
   "stable-window-count"
   "warmup-relative-drift-reference"
   "measured-drift-window"
   "measured-relative-drift-reference"])

(defn validate-args!
  [raw-args]
  (let [args (-> (parse-args raw-args)
                 (require-args! common-required-args))]
    (when (= "steady" (get args "mode"))
      (require-args! args steady-required-args))
    args))

(def ^:dynamic *start-rss-process*
  (fn []
    (let [pid (str (.pid (java.lang.ProcessHandle/current)))]
      (.start (ProcessBuilder. ^java.util.List ["ps" "-o" "rss=" "-p" pid])))))

(defn rss-bytes
  []
  (let [process (*start-rss-process*)
        _ (.close (.getOutputStream process))
        output (slurp (.getInputStream process))
        error (slurp (.getErrorStream process))
        exit (.waitFor process)
        value (parse-long (str/trim output))]
    (when-not (and (zero? exit) (some? value) (not (neg? value)))
      (throw (ex-info "Invalid ps output while measuring RSS"
                      {:stdout output :stderr error :exit exit})))
    (* 1024 value)))

(defn- elapsed-ms
  [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn- percentile
  [values probability]
  (let [ordered (vec (sort values))
        index (max 0 (dec (long (Math/ceil (* probability (count ordered))))))]
    (nth ordered index)))

(defn- relative-drift
  [first-value last-value]
  (/ (Math/abs (- last-value first-value)) first-value))

(defn- observe!
  [args phase workload metric sample-index value unit]
  (let [observation {"schema-version" 1
                     "run-id" (get args "run-id")
                     "implementation" "clojure"
                     "phase" phase
                     "workload" workload
                     "metric" metric
                     "replicate" (parse-long (get args "replicate" "0"))
                     "sample-index" sample-index
                     "value" value
                     "unit" unit}]
    (spit (get args "output")
          (str (json/write-str observation) "\n")
          :append true)))

(defn- stability-record
  [args workload kind window-index operation-count median-ms
   reference-median-ms relative-drift relative-drift-reference
   within-reference-bound]
  {"schema-version" 1
   "run-id" (get args "run-id")
   "implementation" "clojure"
   "workload" workload
   "replicate" (parse-long (get args "replicate" "0"))
   "kind" kind
   "window-index" window-index
   "operation-count" operation-count
   "median-ms" median-ms
   "reference-median-ms" reference-median-ms
   "relative-drift" relative-drift
   "relative-drift-reference" relative-drift-reference
   "within-reference-bound" within-reference-bound})

(defn- diagnostic-record
  [args workload workload-order-index checkpoint window-index
   timed-operation-count validation-operation-count started-ns rss]
  (diagnostics/capture
   {:run-id (get args "run-id")
    :implementation "clojure"
    :workload workload
    :replicate (parse-long (get args "replicate"))
    :pair-order-index (parse-long (get args "pair-order-index"))
    :workload-order-index workload-order-index
    :checkpoint checkpoint
    :window-index window-index
    :timed-operation-count timed-operation-count
    :validation-operation-count validation-operation-count
    :started-ns started-ns
    :rss-bytes rss}))

(defn sample-operations
  [operation count]
  (persistent!
   (loop [sample-index 0
          samples (transient [])]
     (if (= sample-index count)
       samples
       (let [started (System/nanoTime)]
         (operation)
         (recur (inc sample-index)
                (conj! samples (elapsed-ms started))))))))

(defn validate-preflight!
  [operation validate]
  (validate (operation)))

(defn- create-client
  [args corpus]
  (sdk/client {:cli-url (get args "uri")
               :tcp-connection-token (get corpus "connectionToken")
               :auto-start? false
               :log-level :error}))

(defn- assert-ping!
  [corpus result]
  (when-not (= {:message (get corpus "pingMessage")
                :timestamp (get corpus "timestamp")
                :protocol-version (get corpus "protocolVersion")}
               result)
    (throw (ex-info "Invalid ping result" {:result result}))))

(defn- assert-response!
  [corpus result]
  (when-not (and (= :copilot/assistant.message (:type result))
                 (= (get corpus "response") (get-in result [:data :content])))
    (throw (ex-info "Invalid send-and-wait! result" {:result result}))))

(defn- stop-client!
  [client]
  (let [errors (sdk/stop! client)]
    (when (seq errors)
      (throw (ex-info "Clojure client cleanup failed" {:errors errors})))))

(defn- run-cold!
  [args corpus]
  (let [rss-before (rss-bytes)
        started (System/nanoTime)
        client (create-client args corpus)
        sample-index (parse-long (get args "sample-index" "0"))]
    (try
      (sdk/start! client)
      (let [result (sdk/ping client (get corpus "pingMessage"))
            latency (elapsed-ms started)
            rss-delta (- (rss-bytes) rss-before)]
        (assert-ping! corpus result)
        (observe! args "cold" "connect-ping" "latency" sample-index latency "ms")
        (observe! args "cold" "connect-ping" "rss-delta" sample-index
                  rss-delta "bytes"))
      (finally
        (stop-client! client)))))

(defn- measured-loop!
  [args workload workload-order-index operation validate started-ns]
  (let [warmup (parse-long (get args "warmup" "0"))
        iterations (parse-long (get args "iterations" "1"))
        replicate (parse-long (get args "replicate" "0"))
        sample-offset (parse-long (get args "sample-offset" "0"))
        window-size (parse-long (get args "warmup-window-size"))
        stable-window-count (parse-long (get args "stable-window-count"))
        warmup-drift-reference
        (parse-double (get args "warmup-relative-drift-reference"))
        drift-window (parse-long (get args "measured-drift-window"))
        measured-drift-reference
        (parse-double (get args "measured-relative-drift-reference"))]
    (when (or (not (zero? (mod warmup window-size)))
              (< (quot warmup window-size) (* 2 stable-window-count)))
      (throw (ex-info "Warmup must contain complete stability windows"
                      {:warmup warmup :window-size window-size})))
    (when (< iterations (* 2 drift-window))
      (throw (ex-info "Measurement must contain two drift windows"
                      {:iterations iterations :drift-window drift-window})))
    (validate-preflight! operation validate)
    (let [pre-warmup
          (diagnostic-record args workload workload-order-index
                             "pre-warmup" nil 0 1 started-ns (rss-bytes))
          warmup-result
          (loop [window-index 0
                 medians []
                 records []
                 diagnostic-records [pre-warmup]]
            (if (= window-index (quot warmup window-size))
              {:medians medians
               :records records
               :diagnostic-records diagnostic-records}
              (let [samples (sample-operations operation window-size)
                    window-median (percentile samples 0.50)
                    medians (conj medians window-median)
                    enough-windows? (>= (count medians) (* 2 stable-window-count))
                    previous (when enough-windows?
                               (take stable-window-count
                                     (take-last (* 2 stable-window-count) medians)))
                    recent (when enough-windows?
                             (take-last stable-window-count medians))
                    drift (when enough-windows?
                            (relative-drift (percentile previous 0.50)
                                            (percentile recent 0.50)))]
                (recur
                 (inc window-index)
                 medians
                 (conj records
                       (stability-record
                        args workload "warmup-window" window-index
                        (* (inc window-index) window-size)
                        window-median nil drift warmup-drift-reference
                        (when drift (<= drift warmup-drift-reference))))
                 (conj diagnostic-records
                       (diagnostic-record
                        args workload workload-order-index "warmup-window"
                        window-index (* (inc window-index) window-size)
                        1 started-ns nil))))))
          rss-before (rss-bytes)
          pre-measurement
          (diagnostic-record args workload workload-order-index
                             "pre-measurement" nil warmup 1
                             started-ns rss-before)
          batch-started (System/nanoTime)
          samples (sample-operations operation iterations)
          batch-duration (elapsed-ms batch-started)
          rss-after (rss-bytes)
          rss-delta (- rss-after rss-before)
          post-measurement
          (diagnostic-record args workload workload-order-index
                             "post-measurement" nil (+ warmup iterations) 1
                             started-ns rss-after)
          first-median (percentile (take drift-window samples) 0.50)
          last-median (percentile (take-last drift-window samples) 0.50)
          measured-drift (/ (Math/abs (- last-median first-median)) first-median)
          stability-records
          (conj (:records warmup-result)
                (stability-record args workload "measurement-drift" 0 iterations
                                  first-median last-median measured-drift
                                  measured-drift-reference
                                  (<= measured-drift
                                      measured-drift-reference)))]
      (validate-preflight! operation validate)
      (let [postflight
            (diagnostic-record args workload workload-order-index
                               "postflight" nil (+ warmup iterations) 2
                               started-ns (rss-bytes))]
        (doseq [[sample-index value] (map-indexed vector samples)]
          (observe! args "steady" workload "latency"
                    (+ sample-offset sample-index) value "ms"))
        (observe! args "steady" workload "batch-duration" replicate batch-duration "ms")
        (observe! args "steady" workload "rss-delta" replicate rss-delta "bytes")
        (spit (get args "stability-output")
              (str (str/join "\n" (map json/write-str stability-records)) "\n")
              :append true)
        (spit (get args "diagnostics-output")
              (str (str/join
                    "\n"
                    (map json/write-str
                         (into (:diagnostic-records warmup-result)
                               [pre-measurement post-measurement postflight])))
                   "\n")
              :append true)))))

(defn- run-steady!
  [args corpus]
  (let [client (create-client args corpus)
        timeout-ms (parse-long (get args "timeout-ms" "5000"))
        started-ns (System/nanoTime)]
    (try
      (sdk/start! client)
      (measured-loop! args "ping" 0
                      #(sdk/ping client (get corpus "pingMessage"))
                      #(assert-ping! corpus %)
                      started-ns)
      (let [session (sdk/create-session
                     client
                     {:session-id "bench-session"
                      :model (get corpus "model")})]
        (measured-loop! args "send-and-wait" 1
                        #(sdk/send-and-wait!
                          session
                          {:prompt (get corpus "prompt")}
                          timeout-ms)
                        #(assert-response! corpus %)
                        started-ns))
      (finally
        (stop-client! client)))))

(defn -main
  [& raw-args]
  (when (log/enabled? :info)
    (throw (ex-info "Benchmark logging must suppress INFO before measurement" {})))
  (let [args (validate-args! raw-args)
        corpus (json/read-str (slurp (io/file (get args "corpus"))))]
    (case (get args "mode")
      "cold" (run-cold! args corpus)
      "steady" (run-steady! args corpus)
      (throw (ex-info "Unsupported mode" {:mode (get args "mode")})))
    (println (json/write-str {"ok" true
                              "implementation" "clojure"
                              "mode" (get args "mode")}))))
