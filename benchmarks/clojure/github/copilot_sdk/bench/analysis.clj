(ns github.copilot-sdk.bench.analysis
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def observation-keys
  #{:schema-version :run-id :implementation :phase :workload
    :metric :replicate :sample-index :value :unit})

(def comparable-metadata-keys
  [:schema-version :fixture-version :corpus-sha256 :profile
   :warmup :iterations :steady-repetitions :concurrency :host-id
   :repository-commit :repository-dirty-sha256 :benchmark-inputs-sha256
   :node-sdk-repository-root :node-sdk-commit :node-sdk-dirty-sha256
   :node-sdk-package-version
   :node-entry-sha256 :node-dist-sha256 :node-package-sha256
   :node-lock-sha256 :copilot-cli-version :node-version :java-version
   :clojure-cli-version :babashka-version :os :cpu
   :warmup-window-size :stable-window-count :max-warmup-relative-drift
   :measured-drift-window :max-measured-relative-drift :confirmatory?
   :confirmatory-min-pairs :familywise-alpha])

(def stability-keys
  #{:schema-version :run-id :implementation :workload :replicate :kind
    :window-index :operation-count :median-ms :reference-median-ms
    :relative-drift :stable})

(def implementations #{"clojure" "node"})
(def phases #{"cold" "steady"})
(def workloads #{"process" "connect-ping" "ping" "send-and-wait"})
(def metrics #{"latency" "batch-duration" "rss-delta"})

(def allowed-combinations
  #{["cold" "process" "latency" "ms"]
    ["cold" "connect-ping" "latency" "ms"]
    ["cold" "connect-ping" "rss-delta" "bytes"]
    ["steady" "ping" "latency" "ms"]
    ["steady" "ping" "batch-duration" "ms"]
    ["steady" "ping" "rss-delta" "bytes"]
    ["steady" "send-and-wait" "latency" "ms"]
    ["steady" "send-and-wait" "batch-duration" "ms"]
    ["steady" "send-and-wait" "rss-delta" "bytes"]})

(defn- finite-number?
  [value]
  (and (number? value)
       (Double/isFinite (double value))))

(defn validate-observation!
  [observation]
  (let [keys-present (set (keys observation))
        valid? (and (= observation-keys keys-present)
                    (= 1 (:schema-version observation))
                    (string? (:run-id observation))
                    (not (str/blank? (:run-id observation)))
                    (contains? implementations (:implementation observation))
                    (contains? phases (:phase observation))
                    (contains? workloads (:workload observation))
                    (contains? metrics (:metric observation))
                    (nat-int? (:replicate observation))
                    (nat-int? (:sample-index observation))
                    (finite-number? (:value observation))
                    (contains? allowed-combinations
                               [(:phase observation)
                                (:workload observation)
                                (:metric observation)
                                (:unit observation)]))]
    (when-not valid?
      (throw (ex-info "Invalid benchmark observation"
                      {:observation observation
                       :missing (set/difference observation-keys keys-present)
                       :extra (set/difference keys-present observation-keys)})))
    observation))

(defn percentile
  [samples probability]
  (when-not (and (seq samples) (<= 0.0 probability 1.0))
    (throw (ex-info "Percentile requires samples and a probability in [0, 1]"
                    {:sample-count (count samples) :probability probability})))
  (let [ordered (vec (sort (map double samples)))
        rank (long (Math/ceil (* probability (count ordered))))
        index (max 0 (dec rank))]
    (nth ordered index)))

(defn bootstrap-matched-cluster-ratio-ci
  [numerator-clusters denominator-clusters {:keys [seed resamples]}]
  (let [numerator-ids (set (keys numerator-clusters))
        denominator-ids (set (keys denominator-clusters))
        cluster-ids (vec (sort numerator-ids))]
    (when-not (and (= numerator-ids denominator-ids)
                   (> (count cluster-ids) 1)
                   (pos-int? resamples)
                   (every? seq (vals numerator-clusters))
                   (every? seq (vals denominator-clusters)))
      (throw (ex-info "Matched bootstrap requires at least two paired non-empty clusters"
                      {:numerator-clusters numerator-ids
                       :denominator-clusters denominator-ids
                       :resamples resamples})))
    (let [random (java.util.Random. (long seed))
          cluster-count (count cluster-ids)
          ratios
          (repeatedly
           resamples
           (fn []
             (let [sampled-ids (repeatedly cluster-count
                                           #(nth cluster-ids
                                                 (.nextInt random cluster-count)))
                   numerator (mapcat numerator-clusters sampled-ids)
                   denominator (mapcat denominator-clusters sampled-ids)
                   denominator-statistic (percentile denominator 0.50)]
               (when (zero? denominator-statistic)
                 (throw (ex-info "Cannot bootstrap a ratio with zero denominator"
                                 {:sampled-clusters sampled-ids})))
               (/ (percentile numerator 0.50) denominator-statistic))))]
      {:low (percentile ratios 0.025)
       :high (percentile ratios 0.975)
       :independent-clusters cluster-count})))

(defn assert-comparable!
  [left right]
  (let [left-comparable (select-keys left comparable-metadata-keys)
        right-comparable (select-keys right comparable-metadata-keys)]
    (when-not (= left-comparable right-comparable)
      (throw (ex-info "Benchmark metadata mismatch"
                      {:left left-comparable
                       :right right-comparable
                       :mismatched-keys
                       (into {}
                             (keep (fn [key]
                                     (when-not (= (get left-comparable key)
                                                  (get right-comparable key))
                                       [key [(get left-comparable key)
                                             (get right-comparable key)]])))
                             comparable-metadata-keys)})))
    left))

(defn summarize-latencies
  [samples]
  {:n (count samples)
   :p50-ms (percentile samples 0.50)
   :p95-ms (percentile samples 0.95)
   :p99-ms (percentile samples 0.99)})

(defn read-observations
  [file]
  (with-open [reader (io/reader file)]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(-> (json/read-str % :key-fn keyword)
                    validate-observation!)))))

(defn validate-stability-record!
  [record]
  (let [keys-present (set (keys record))
        warmup? (= "warmup-window" (:kind record))
        measurement? (= "measurement-drift" (:kind record))
        valid? (and (= stability-keys keys-present)
                    (= 1 (:schema-version record))
                    (string? (:run-id record))
                    (not (str/blank? (:run-id record)))
                    (contains? implementations (:implementation record))
                    (#{"ping" "send-and-wait"} (:workload record))
                    (nat-int? (:replicate record))
                    (or warmup? measurement?)
                    (nat-int? (:window-index record))
                    (pos-int? (:operation-count record))
                    (finite-number? (:median-ms record))
                    (pos? (:median-ms record))
                    (boolean? (:stable record))
                    (if warmup?
                      (and (nil? (:reference-median-ms record))
                           (or (nil? (:relative-drift record))
                               (and (finite-number? (:relative-drift record))
                                    (not (neg? (:relative-drift record))))))
                      (and (finite-number? (:reference-median-ms record))
                           (pos? (:reference-median-ms record))
                           (finite-number? (:relative-drift record))
                           (not (neg? (:relative-drift record))))))]
    (when-not valid?
      (throw (ex-info "Invalid benchmark stability record"
                      {:record record
                       :missing (set/difference stability-keys keys-present)
                       :extra (set/difference keys-present stability-keys)})))
    record))

(defn read-stability-records
  [file]
  (with-open [reader (io/reader file)]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(-> (json/read-str % :key-fn keyword)
                    validate-stability-record!)))))

(defn observation-key
  [observation]
  ((juxt :implementation :phase :workload :metric :replicate :sample-index)
   observation))

(defn expected-observation-keys
  [{:keys [cold-start-count steady-repetitions iterations]}]
  (set
   (concat
    (for [implementation implementations
          replicate (range cold-start-count)
          [workload metric] [["process" "latency"]
                             ["connect-ping" "latency"]
                             ["connect-ping" "rss-delta"]]]
      [implementation "cold" workload metric replicate replicate])
    (for [implementation implementations
          replicate (range steady-repetitions)
          workload ["ping" "send-and-wait"]
          metric ["latency"]
          sample-index (range (* replicate iterations)
                              (* (inc replicate) iterations))]
      [implementation "steady" workload metric replicate sample-index])
    (for [implementation implementations
          replicate (range steady-repetitions)
          workload ["ping" "send-and-wait"]
          metric ["batch-duration" "rss-delta"]]
      [implementation "steady" workload metric replicate replicate]))))

(defn validate-observation-set!
  [observations profile]
  (doseq [observation observations]
    (validate-observation! observation))
  (let [frequencies (frequencies (map observation-key observations))
        duplicates (into {} (filter (fn [[_ count]] (> count 1))) frequencies)
        actual (set (keys frequencies))
        expected (expected-observation-keys profile)
        missing (set/difference expected actual)
        unexpected (set/difference actual expected)]
    (when (or (seq duplicates) (seq missing) (seq unexpected))
      (throw (ex-info "Invalid benchmark observation set"
                      {:duplicates duplicates
                       :missing missing
                       :unexpected unexpected
                       :expected-count (count expected)
                       :actual-count (count observations)})))
    observations))

(defn stability-key
  [record]
  ((juxt :implementation :workload :replicate :kind :window-index) record))

(defn expected-stability-keys
  [{:keys [warmup warmup-window-size steady-repetitions]}]
  (set
   (concat
    (for [implementation implementations
          workload ["ping" "send-and-wait"]
          replicate (range steady-repetitions)
          window-index (range (quot warmup warmup-window-size))]
      [implementation workload replicate "warmup-window" window-index])
    (for [implementation implementations
          workload ["ping" "send-and-wait"]
          replicate (range steady-repetitions)]
      [implementation workload replicate "measurement-drift" 0]))))

(defn validate-stability-set!
  [records {:keys [warmup-window-size stable-window-count iterations
                   max-warmup-relative-drift max-measured-relative-drift]
            :as profile}]
  (doseq [record records]
    (validate-stability-record! record))
  (let [frequencies (frequencies (map stability-key records))
        duplicates (into {} (filter (fn [[_ count]] (> count 1))) frequencies)
        actual (set (keys frequencies))
        expected (expected-stability-keys profile)
        missing (set/difference expected actual)
        unexpected (set/difference actual expected)]
    (when (or (seq duplicates) (seq missing) (seq unexpected))
      (throw (ex-info "Invalid benchmark stability record set"
                      {:duplicates duplicates :missing missing :unexpected unexpected})))
    (doseq [[_ workload replicate values]
            (map (fn [[[implementation workload replicate] values]]
                   [implementation workload replicate values])
                 (group-by (juxt :implementation :workload :replicate) records))]
      (let [warmup-records (sort-by :window-index
                                    (filter #(= "warmup-window" (:kind %)) values))
            measurement (first (filter #(= "measurement-drift" (:kind %)) values))
            medians (mapv :median-ms warmup-records)
            tolerance 1.0e-9
            close? (fn [recorded expected]
                     (<= (Math/abs (- (double recorded) (double expected)))
                         (* tolerance (max 1.0 (Math/abs (double expected))))))]
        (doseq [[window-index record] (map-indexed vector warmup-records)]
          (when-not (= (* (inc window-index) warmup-window-size)
                       (:operation-count record))
            (throw (ex-info "Invalid cumulative warmup count"
                            {:workload workload :replicate replicate :record record})))
          (let [has-drift? (>= window-index (dec (* 2 stable-window-count)))
                expected-drift
                (when has-drift?
                  (let [observed (subvec medians 0 (inc window-index))
                        previous (take stable-window-count
                                       (take-last (* 2 stable-window-count) observed))
                        recent (take-last stable-window-count observed)
                        first-median (percentile previous 0.50)
                        last-median (percentile recent 0.50)]
                    (/ (Math/abs (- last-median first-median)) first-median)))
                expected-stable (boolean
                                 (and expected-drift
                                      (<= expected-drift max-warmup-relative-drift)))]
            (when-not (= has-drift? (some? (:relative-drift record)))
              (throw (ex-info "Invalid warmup drift availability"
                              {:workload workload :replicate replicate :record record})))
            (when (and has-drift?
                       (not (close? (:relative-drift record) expected-drift)))
              (throw (ex-info "Recorded warmup drift does not match recomputation"
                              {:workload workload :replicate replicate
                               :record record :expected expected-drift})))
            (when-not (= expected-stable (:stable record))
              (throw (ex-info "Recorded warmup stability flag is invalid"
                              {:workload workload :replicate replicate
                               :record record :expected expected-stable})))))
        (let [expected-measured-drift
              (/ (Math/abs (- (:reference-median-ms measurement)
                              (:median-ms measurement)))
                 (:median-ms measurement))
              expected-measured-stable
              (<= expected-measured-drift max-measured-relative-drift)]
          (when-not (close? (:relative-drift measurement) expected-measured-drift)
            (throw (ex-info "Recorded measured drift does not match recomputation"
                            {:workload workload :replicate replicate
                             :record measurement :expected expected-measured-drift})))
          (when-not (= expected-measured-stable (:stable measurement))
            (throw (ex-info "Recorded measured stability flag is invalid"
                            {:workload workload :replicate replicate
                             :record measurement :expected expected-measured-stable}))))
        (when-not (:stable (last warmup-records))
          (throw (ex-info "Warmup failed stability criterion"
                          {:workload workload :replicate replicate
                           :record (last warmup-records)})))
        (when-not (and (= iterations (:operation-count measurement))
                       (:stable measurement))
          (throw (ex-info "Measured window drift failed stability criterion"
                          {:workload workload :replicate replicate
                           :record measurement})))))
    records))

(defn- matching-observations
  [observations implementation phase workload metric]
  (filterv #(and (= implementation (:implementation %))
                 (= phase (:phase %))
                 (= workload (:workload %))
                 (= metric (:metric %)))
           observations))

(defn- cluster-values
  [observations implementation phase workload metric transform]
  (->> (matching-observations observations implementation phase workload metric)
       (group-by :replicate)
       (map (fn [[replicate values]]
              [replicate (mapv (comp transform :value) values)]))
       (into (sorted-map))))

(defn- flattened-values
  [clusters]
  (vec (mapcat val clusters)))

(defn- distribution-summary
  [samples]
  {:n (count samples)
   :min (apply min samples)
   :p50 (percentile samples 0.50)
   :max (apply max samples)})

(defn- workload-summary
  [observations implementation phase workload iterations]
  (let [latencies (-> (cluster-values observations implementation phase workload
                                      "latency" identity)
                      flattened-values)
        throughput (-> (cluster-values observations implementation phase workload
                                       "batch-duration"
                                       #(/ (* 1000.0 iterations) %))
                       flattened-values)
        rss (-> (cluster-values observations implementation phase workload
                                "rss-delta" identity)
                flattened-values)]
    (cond-> (summarize-latencies latencies)
      (seq throughput)
      (assoc :throughput-ops-per-second (distribution-summary throughput))
      (seq rss)
      (assoc :rss-delta-bytes (distribution-summary rss)))))

(defn- paired-ratio
  [numerator-clusters denominator-clusters]
  (/ (percentile (flattened-values numerator-clusters) 0.50)
     (percentile (flattened-values denominator-clusters) 0.50)))

(defn descriptive-rss-ratio
  [clojure-clusters node-clusters]
  (when (and (seq clojure-clusters)
             (= (set (keys clojure-clusters)) (set (keys node-clusters)))
             (every? pos? (flattened-values clojure-clusters))
             (every? pos? (flattened-values node-clusters)))
    (paired-ratio clojure-clusters node-clusters)))

(defn- maybe-bootstrap
  [numerator-clusters denominator-clusters options]
  (when (> (count numerator-clusters) 1)
    (bootstrap-matched-cluster-ratio-ci
     numerator-clusters denominator-clusters options)))

(defn paired-log-effects
  [clojure-values node-values minimum-pairs]
  (let [clojure-ids (set (keys clojure-values))
        node-ids (set (keys node-values))]
    (when-not (and (= clojure-ids node-ids)
                   (>= (count clojure-ids) minimum-pairs))
      (throw (ex-info "Insufficient or mismatched paired process values"
                      {:clojure-ids clojure-ids
                       :node-ids node-ids
                       :minimum-pairs minimum-pairs})))
    (mapv
     (fn [replicate]
       (let [clojure-value (get clojure-values replicate)
             node-value (get node-values replicate)]
         (when-not (and (pos? clojure-value) (pos? node-value))
           (throw (ex-info "Confirmatory ratios require positive process values"
                           {:replicate replicate
                            :clojure clojure-value
                            :node node-value})))
         {:replicate replicate
          :clojure-value clojure-value
          :node-value node-value
          :ratio (/ (double clojure-value) node-value)
          :log-ratio (Math/log (/ (double clojure-value) node-value))}))
     (sort clojure-ids))))

(defn geometric-mean-ratio
  [effects]
  (when-not (seq effects)
    (throw (ex-info "Geometric mean requires paired effects" {})))
  (Math/exp (/ (reduce + (map :log-ratio effects)) (count effects))))

(defn bootstrap-paired-effect-ci
  [effects {:keys [seed resamples]}]
  (when-not (and (> (count effects) 1) (pos-int? resamples))
    (throw (ex-info "Paired process bootstrap requires at least two effects"
                    {:effect-count (count effects) :resamples resamples})))
  (let [random (java.util.Random. (long seed))
        effects (vec effects)
        pair-count (count effects)
        ratios
        (repeatedly
         resamples
         (fn []
           (Math/exp
            (/ (reduce +
                       (repeatedly pair-count
                                   #(:log-ratio (nth effects
                                                     (.nextInt random pair-count)))))
               pair-count))))]
    {:low (percentile ratios 0.025)
     :high (percentile ratios 0.975)
     :independent-pairs pair-count}))

(defn exact-sign-flip-p-value
  [effects]
  (when-not (> (count effects) 1)
    (throw (ex-info "Exact sign-flip test requires at least two paired effects"
                    {:effect-count (count effects)})))
  (let [log-effects (mapv :log-ratio effects)
        observed (Math/abs (reduce + log-effects))
        signed-sums
        (reduce (fn [sums effect]
                  (into (mapv #(+ % effect) sums)
                        (map #(- % effect) sums)))
                [0.0]
                log-effects)
        tolerance 1.0e-12
        extreme (count (filter #(>= (+ (Math/abs (double %)) tolerance)
                                    observed)
                               signed-sums))]
    {:p-value (/ (double extreme) (count signed-sums))
     :permutations (count signed-sums)
     :minimum-two-sided-resolution (/ 2.0 (count signed-sums))
     :statistic "absolute mean paired log ratio"}))

(defn holm-adjust
  [p-values]
  (let [ordered (sort-by val p-values)
        family-size (count ordered)]
    (loop [rank 0
           previous 0.0
           remaining ordered
           adjusted {}]
      (if-let [[endpoint p-value] (first remaining)]
        (let [candidate (min 1.0 (* (- family-size rank) p-value))
              value (max previous candidate)]
          (recur (inc rank) value (next remaining)
                 (assoc adjusted endpoint value)))
        adjusted))))

(defn conclusion-label
  [endpoint ratio adjusted-p alpha]
  (if (> adjusted-p alpha)
    "no-supported-difference"
    (cond
      (#{"ping-latency" "send-and-wait-latency"} endpoint)
      (if (< ratio 1.0) "clojure-lower-latency" "node-lower-latency")
      (#{"ping-throughput" "send-and-wait-throughput"} endpoint)
      (if (> ratio 1.0) "clojure-higher-throughput" "node-higher-throughput")
      :else (throw (ex-info "Unknown confirmatory endpoint"
                            {:endpoint endpoint})))))

(def confirmatory-endpoints
  [{:id "ping-latency" :workload "ping" :metric "latency"}
   {:id "send-and-wait-latency" :workload "send-and-wait" :metric "latency"}
   {:id "ping-throughput" :workload "ping" :metric "batch-duration"}
   {:id "send-and-wait-throughput" :workload "send-and-wait"
    :metric "batch-duration"}])

(defn- process-values
  [observations implementation workload metric iterations]
  (let [clusters (cluster-values
                  observations implementation "steady" workload metric identity)]
    (into (sorted-map)
          (map (fn [[replicate values]]
                 [replicate
                  (if (= metric "latency")
                    (percentile values 0.50)
                    (/ (* 1000.0 iterations) (first values)))]))
          clusters)))

(defn confirmatory-analysis
  [observations {:keys [iterations bootstrap-seed bootstrap-resamples
                        confirmatory-min-pairs familywise-alpha]}]
  (let [unadjusted
        (mapv
         (fn [{:keys [id workload metric]}]
           (let [effects (paired-log-effects
                          (process-values observations "clojure" workload metric
                                          iterations)
                          (process-values observations "node" workload metric
                                          iterations)
                          confirmatory-min-pairs)
                 ratio (geometric-mean-ratio effects)
                 sign-flip (exact-sign-flip-p-value effects)]
             {:endpoint id
              :process-pairs (count effects)
              :effect "geometric mean Clojure/Node ratio"
              :geometric-mean-ratio ratio
              :paired-process-bootstrap-95
              (bootstrap-paired-effect-ci
               effects {:seed bootstrap-seed :resamples bootstrap-resamples})
              :exact-sign-flip sign-flip
              :pair-effects effects}))
         confirmatory-endpoints)
        adjusted (holm-adjust
                  (into {}
                        (map (juxt :endpoint
                                   #(get-in % [:exact-sign-flip :p-value])))
                        unadjusted))]
    {:status "confirmatory"
     :family "four predeclared steady endpoints"
     :alpha familywise-alpha
     :multiplicity "Holm familywise correction"
     :analysis-unit "matched process pair"
     :statistic "mean paired log ratio, reported as geometric mean ratio"
     :endpoints
     (mapv (fn [endpoint]
             (let [adjusted-p (get adjusted (:endpoint endpoint))]
               (assoc endpoint
                      :holm-adjusted-p-value adjusted-p
                      :conclusion
                      (conclusion-label (:endpoint endpoint)
                                        (:geometric-mean-ratio endpoint)
                                        adjusted-p familywise-alpha))))
           unadjusted)}))

(defn- stability-diagnostics
  [records]
  (mapv
   (fn [[[implementation workload replicate] values]]
     (let [final-warmup (last (sort-by :window-index
                                       (filter #(= "warmup-window" (:kind %)) values)))
           measurement (first (filter #(= "measurement-drift" (:kind %)) values))]
       {:implementation implementation
        :workload workload
        :replicate replicate
        :actual-warmup-count (:operation-count final-warmup)
        :final-warmup-window-median-ms (:median-ms final-warmup)
        :final-warmup-relative-drift (:relative-drift final-warmup)
        :warmup-stable (:stable final-warmup)
        :first-measured-window-median-ms (:median-ms measurement)
        :last-measured-window-median-ms (:reference-median-ms measurement)
        :measured-relative-drift (:relative-drift measurement)
        :measured-stable (:stable measurement)}))
   (sort-by key (group-by (juxt :implementation :workload :replicate) records))))

(defn analyze
  ([observations metadata]
   (analyze observations metadata []))
  ([observations {:keys [iterations confirmatory?] :as metadata} stability]
   (let [configurations [["cold" "process"]
                         ["cold" "connect-ping"]
                         ["steady" "ping"]
                         ["steady" "send-and-wait"]]]
     (cond->
      {:schema-version 1
       :ratio-direction
       {:latency-and-rss "clojure/node; above 1 means Clojure is higher"
        :throughput "clojure/node; above 1 means Clojure has higher throughput"}
       :descriptive-warning
       "Operation-level p50/p95/p99 and pooled ratios are descriptive, not inferential."
       :descriptive-results
       (mapv
        (fn [[phase workload]]
          (let [clojure-latency (cluster-values observations "clojure" phase workload
                                                "latency" identity)
                node-latency (cluster-values observations "node" phase workload
                                             "latency" identity)
                clojure-throughput
                (cluster-values observations "clojure" phase workload "batch-duration"
                                #(/ (* 1000.0 iterations) %))
                node-throughput
                (cluster-values observations "node" phase workload "batch-duration"
                                #(/ (* 1000.0 iterations) %))
                clojure-rss (cluster-values observations "clojure" phase workload
                                            "rss-delta" identity)
                node-rss (cluster-values observations "node" phase workload
                                         "rss-delta" identity)
                throughput-ratio (when (seq clojure-throughput)
                                   (paired-ratio clojure-throughput node-throughput))
                rss-ratio (descriptive-rss-ratio clojure-rss node-rss)]
            {:phase phase
             :workload workload
             :measures (if (and (= phase "cold") (= workload "process"))
                         "process-launch-and-language-runtime-startup"
                         "public-sdk-operation")
             :clojure (workload-summary observations "clojure" phase workload iterations)
             :node (workload-summary observations "node" phase workload iterations)
             :clojure-node-ratio
             (cond-> {:pooled-p50-latency
                      (paired-ratio clojure-latency node-latency)}
               throughput-ratio
               (assoc :throughput throughput-ratio)
               rss-ratio
               (assoc :rss-delta rss-ratio))}))
        configurations)}
       (seq stability) (assoc :stability (stability-diagnostics stability))
       true (assoc :confirmatory
                   (if confirmatory?
                     (confirmatory-analysis observations metadata)
                     {:status "not-run"
                      :reason "smoke profile has insufficient process pairs"}))))))
