(ns copilot.sdk.specs
  "Shared data contracts for the native SDK. Session option builders own
  their input shapes; generated event specs describe the runtime protocol."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.util Base64]))

(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::session-id ::non-blank-string)
(defn- keyword-option-map? [v] (and (map? v) (every? keyword? (keys v))))
(s/def ::client-options (s/and keyword-option-map? #(#{:empty :coding-agent} (:mode %))))
(s/def ::conversation-config
  (s/and map? #(= #{:client :session} (set (keys %)))
         #(s/valid? ::client-options (:client %))
         #(map? (:session %))))
(s/def ::turn-message
  (s/or :prompt string? :options (s/and keyword-option-map? #(string? (:prompt %)))))
(s/def ::structured-message
  (s/and keyword-option-map? #(string? (:prompt %)) #(map? (:response-schema %))))
(s/def ::turn-timeout-ms #(and (pos-int? %) (<= % Integer/MAX_VALUE)))
(s/def ::turn-reply
  (s/nilable (s/and map? #(= :copilot/assistant.message (:type %))
                    #(contains? #{nil ""} (:agent-id %)))))
(s/def ::log-level #{:none :error :warning :info :debug :all})
(s/def ::session-idle-timeout-seconds nat-int?)

(def system-message-sections
  "Sections an application can customize with :system-message. Keys are the
  Clojure section identifiers; descriptions explain their role in the prompt."
  {:preamble {:description "The agent's identity and operating mode."}
   :identity {:description "Group containing the preamble, tone, and related sections."}
   :tone {:description "Response style, length, and format."}
   :tool-efficiency {:description "How to use and combine tool calls."}
   :environment-context {:description "Working directory, operating system, repository, and available tools."}
   :code-change-rules {:description "Coding, testing, and formatting rules."}
   :guidelines {:description "General behavioral guidance."}
   :safety {:description "Environment limits and prohibited actions."}
   :tool-instructions {:description "Instructions for individual tools."}
   :custom-instructions {:description "Repository and organization instructions."}
   :runtime-instructions {:description "Context and instructions supplied by the runtime."}
   :last-instructions {:description "Final instructions about tool calls and task completion."}})

(s/def ::json-scalar
  #(or (nil? %) (boolean? %) (string? %) (integer? %) (decimal? %)
       (and (float? %) (Double/isFinite (double %)))))

(defn- json-value? [root]
  (loop [pending (list root)]
    (if (seq pending)
      (let [value (first pending)
            remaining (rest pending)]
        (cond
          (vector? value) (recur (into remaining value))
          (map? value) (and (every? string? (keys value))
                            (recur (into remaining (vals value))))
          (s/valid? ::json-scalar value) (recur remaining)
          :else false))
      true)))

(s/def ::json-value json-value?)
(s/def ::json-object (s/and map? ::json-value))

(defn- base64? [text]
  (and (string? text)
       (try
         (= text (.encodeToString (Base64/getEncoder) (.decode (Base64/getDecoder) ^String text)))
         (catch IllegalArgumentException _ false))))

(s/def ::base64 base64?)
(s/def ::user-input-image-attachment-constraints
  (s/and map?
         #(= (set (keys %)) #{:allowed-mime-types :max-count :max-bytes-per-image})
         #(and (vector? (:allowed-mime-types %)) (every? string? (:allowed-mime-types %)))
         #(nat-int? (:max-count %))
         #(nat-int? (:max-bytes-per-image %))))
(s/def ::user-input-image-attachment
  (s/and map?
         #(every? #{:type :data :mime-type :display-name :description} (keys %))
         #(= (:type %) :image)
         #(string? (:mime-type %))
         #(and (s/valid? ::base64 (:data %)) (seq (:data %)))
         #(every? string? (vals (select-keys % [:display-name :description])))))
(s/def ::mcp-call-tool-result
  (s/and map?
         #(vector? (:content %))
         #(every? (fn [block] (string? (:type block))) (:content %))
         #(or (not (contains? % :is-error?)) (boolean? (:is-error? %)))
         #(every? #{:content :is-error?} (keys %))))
(s/def ::result-type #{:success :failure :rejected :denied :timeout})
(s/def ::text-result-for-llm string?)
(s/def ::session-log string?)
(s/def ::skip-large-output-processing? boolean?)
(s/def ::error string?)
(s/def ::tool-telemetry (s/map-of string? ::json-object))
(s/def ::tool-references (s/coll-of string? :kind vector?))
(s/def ::binary-result
  (s/and map?
         #(s/valid? ::base64 (:data %))
         #(string? (:mime-type %))
         #(#{"image" "resource"} (:type %))
         #(or (not (contains? % :description)) (string? (:description %)))))
(s/def ::binary-results-for-llm (s/coll-of ::binary-result :kind vector?))
(s/def ::tool-result-object
  (s/and map?
         #(every? (fn [k] (contains? % k)) [:text-result-for-llm :result-type])
         #(not (or (contains? % "skipLargeOutputProcessing") (contains? % :skipLargeOutputProcessing)))
         ;; s/keys would also consult global application specs for qualified JSON extensions.
         (fn [m]
           (every? (fn [[k spec]] (or (not (contains? m k)) (s/valid? spec (get m k))))
                   {:text-result-for-llm ::text-result-for-llm :result-type ::result-type
                    :binary-results-for-llm ::binary-results-for-llm :session-log ::session-log
                    :skip-large-output-processing? ::skip-large-output-processing?
                    :error ::error :tool-telemetry ::tool-telemetry :tool-references ::tool-references}))))

(s/def ::initial-cwd ::non-blank-string)
(s/def ::session-state-path ::non-blank-string)
(s/def ::conventions #{"windows" "posix"})
(s/def ::capabilities (s/map-of #{:binary :sqlite :read-file-range} boolean?))
(s/def ::session-fs
  (s/and map? #(every? #{:initial-cwd :session-state-path :conventions :capabilities} (keys %))
         (s/keys :req-un [::initial-cwd ::session-state-path ::conventions]
                 :opt-un [::capabilities])))

(defn- accepts-arity? [f n]
  (and (fn? f)
       (or (and (instance? clojure.lang.RestFn f)
                (<= (.getRequiredArity ^clojure.lang.RestFn f) n))
           (some (fn [^java.lang.reflect.Method m]
                   (and (= "invoke" (.getName m)) (= n (.getParameterCount m))))
                 (.getDeclaredMethods (class f))))))

(defn- operation-map? [required optional operations]
  (and (keyword-option-map? operations)
       (every? #(contains? operations %) (keys required))
       (every? (fn [[op f]]
                 (when-let [arity (or (required op) (optional op))]
                   (accepts-arity? f arity)))
               operations)))

(s/def ::session-fs-provider
  (s/and keyword-option-map?
         #(operation-map? {:read-file 1 :write-file 3 :append-file 3 :exists 1 :stat 1
                           :mkdir 3 :readdir 1 :readdir-with-types 1 :rm 3 :rename 2}
                          {:read-file-bytes 1 :write-file-bytes 3 :read-file-range 3}
                          (dissoc % :sqlite))
         #(or (not (contains? % :sqlite))
              (operation-map? {:query 3 :exists 0} {:transaction 1} (:sqlite %)))))
