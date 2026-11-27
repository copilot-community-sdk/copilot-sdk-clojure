(ns ^:no-doc github.copilot-sdk.callback-results
  "Pure encoders for native callback responses. Reject unknown decision fields:
  dropping a misspelled qualifier could silently broaden an approval."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

(defn- check [valid?]
  (when-not valid? (throw (ex-info "Invalid callback result" {:type :invalid-callback-result}))))

(defn- fields [m required optional]
  (check (and (map? m)
              (every? #(contains? m %) (keys required))
              (every? (fn [[k v]] (when-let [pred (or (get required k) (get optional k))]
                                    (pred v))) m)))
  m)

(defn- nonempty-text [v] (when (and (string? v) (seq v)) v))

(defn- mcp-text [{:keys [type text resource]}]
  (case type
    "text" (when (string? text) text)
    "resource" (nonempty-text (:text resource))
    nil))

(defn- mcp-binary [{:keys [type data mime-type resource]}]
  (when-let [part (case type
                    "image" (when (and (nonempty-text data) (string? mime-type))
                              {:type type :data data :mime-type mime-type})
                    "resource" (when-let [blob (nonempty-text (:blob resource))]
                                 {:type type :data blob :description (:uri resource)
                                  :mime-type (or (nonempty-text (:mime-type resource))
                                                 "application/octet-stream")})
                    nil)]
    (check (s/valid? ::specs/binary-result part))
    part))

(defn mcp-tool-result [{:keys [content is-error?] :as result}]
  (check (s/valid? ::specs/mcp-call-tool-result result))
  (let [binary (into [] (keep mcp-binary) content)]
    (cond-> {:text-result-for-llm (str/join "\n" (keep mcp-text content))
             :result-type (if is-error? :failure :success)}
      (seq binary) (assoc :binary-results-for-llm binary))))

(def ^:private tool-result-fields
  {:text-result-for-llm :textResultForLlm, :result-type :resultType, :binary-results-for-llm :binaryResultsForLlm,
   :skip-large-output-processing? :skipLargeOutputProcessing,
   :session-log :sessionLog, :tool-telemetry :toolTelemetry, :tool-references :toolReferences})

(defn- rename-members [m names]
  ;; Only SDK field names change. Extra fields may contain arbitrary JSON objects.
  (if-not (map? m)
    m
    (reduce-kv (fn [out k v]
                 (let [member (get names k k)]
                   (check (not (contains? out member)))
                   (assoc out member v)))
               {} m)))

(defn- decoded-fields [names]
  ;; A literal extra named like a Clojure field can coexist on the wire.
  (into names (map (fn [field] [field (name field)])) (vals names)))

(def ^:private decoded-tool-result-fields (decoded-fields (set/map-invert tool-result-fields)))
(def ^:private decoded-binary-fields (decoded-fields {:mimeType :mime-type}))

(defn wire->structured-tool-result [value]
  ;; Input keys have been keywordized, but not normalized to SDK spellings.
  (let [result (rename-members value decoded-tool-result-fields)]
    (cond-> result
      (vector? (:binary-results-for-llm result))
      (update :binary-results-for-llm #(mapv (fn [p] (rename-members p decoded-binary-fields)) %)))))

(defn structured-tool-result
  "Return a wire-ready tool result. Check every value before it reaches the shared writer."
  [value]
  (let [result (cond-> value
                 (contains? value :tool-telemetry) (update :tool-telemetry util/opaque-json->wire))]
    (check (s/valid? ::specs/tool-result-object result))
    (-> (cond-> (update result :result-type name)
          (contains? result :binary-results-for-llm)
          (update :binary-results-for-llm
                  #(mapv (fn [p] (rename-members p {:mime-type :mimeType})) %)))
        (rename-members tool-result-fields)
        util/opaque-json->wire)))

(defn decision-context->wire [context]
  (let [enums {:outcome #{:auto-approved :autopilot-denied :prompted-user}
               :source #{:assisted-approval :human-response :host-policy :unattended-fallback
                         :authorization-carry-forward}
               :surface #{:tui :prompt-mode :copilot-app :acp :sdk}}]
    (fields context enums {:response-capability #{:interactive :headless :none} :user-feedback string?})
    (reduce-kv (fn [m k v] (assoc m k (if (= k :user-feedback) v (str/replace (name v) "-" "_"))))
               {} context)))

(defn- strings? [xs] (and (vector? xs) (every? string? xs)))
(defn- nonempty-strings? [xs] (and (strings? xs) (seq xs)))
(defn- nullable-text? [x] (or (nil? x) (string? x)))

(def ^:private scopes
  {:commands [{:command-identifiers strings?}]
   :read [{}] :write [{}] :memory [{}]
   :mcp [{:server-name string? :tool-name nullable-text?}]
   :mcp-sampling [{:server-name string?}]
   :custom-tool [{:tool-name string?}]
   :extension-management [{} {:operation string?}]
   :workflow [{} {:approval-key string?}]
   :extension-permission-access [{:extension-name string?}]
   :extension-env-access [{:extension-name string? :environment-variables nonempty-strings?}]})

(defn- variant [shapes {:keys [kind] :as value}]
  (let [[required optional] (get shapes kind)]
    (check required)
    (fields value (assoc required :kind #{kind}) optional)))

(defn- scope? [scope] (variant scopes scope))
(defn- recorded-scope? [scope] (and (not= :mcp-sampling (:kind scope)) (scope? scope)))
(defn- rules? [xs]
  (and (vector? xs) (every? #(fields % {:kind string? :argument nullable-text?} {}) xs)))

(def ^:private permission-decisions
  {:no-result [{}]
   :approve-once [{} {:approved-interactively? boolean? :selected-option-id string?}]
   :approve-for-session [{} {:approval scope? :domain string? :selected-option-id string?}]
   :approve-for-location [{:approval scope? :location-key string?}]
   :approve-read-only-for-session [{:directories nonempty-strings?}]
   :approve-permanently [{:domain string?}]
   :reject [{} {:feedback string?}]
   :user-not-available [{} {:selected-option-id string?}]
   :approved [{} {:selected-option-id string?}]
   :approved-for-session [{:approval recorded-scope?}]
   :approved-for-location [{:approval recorded-scope? :location-key string?}]
   :cancelled [{} {:reason string?}]
   :denied-by-rules [{:rules rules?}]
   :denied-no-approval-rule-and-could-not-request-from-user [{}]
   :denied-interactively-by-user [{} {:feedback string? :force-reject? boolean? :selected-option-id string?}]
   :denied-by-content-exclusion-policy [{:path string? :message string?}]
   :denied-by-permission-request-hook [{} {:message string? :interrupt? boolean?}]})

(defn permission-response
  "Encode a handler decision, returning nil for explicit abstention."
  [response]
  (let [attributed? (= :attributed (:kind response))
        _ (when attributed? (fields response {:kind #{:attributed} :result map? :decision-context map?} {}))
        context (when attributed? (decision-context->wire (:decision-context response)))
        decision (variant permission-decisions (if attributed? (:result response) response))]
    (when-not (= :no-result (:kind decision))
      (let [result (reduce (fn [m [k wire]] (if (contains? m k) (-> m (assoc wire (get m k)) (dissoc k)) m))
                           (update decision :kind name)
                           [[:approved-interactively? :approved-interactively]
                            [:force-reject? :force-reject] [:interrupt? :interrupt]])]
        (cond-> {:result (cond-> result (:approval result) (update-in [:approval :kind] name))}
          context (assoc :decision-context context))))))

(defn- form-value? [v]
  (or (string? v) (boolean? v) (strings? v) (and (number? v) (s/valid? ::specs/json-value v))))

(defn allowed-image? [{:keys [allowed-mime-types max-bytes-per-image]} {:keys [mime-type data] :as image}]
  (and (some #{mime-type} allowed-mime-types)
       (string? data)
       ;; Check the decoded size before allocating bytes for Base64 validation.
       (<= (- (* 3 (quot (count data) 4))
              (cond (.endsWith ^String data "==") 2 (.endsWith ^String data "=") 1 :else 0))
           max-bytes-per-image)
       (s/valid? ::specs/user-input-image-attachment image)))

(defn elicitation-metadata [v]
  (check (map? v))
  (util/opaque-json->wire v))

(def ^:private ask-user-images "github.com/copilot/ask-user-images")

(defn- negotiated-form-images? [offer groups]
  (let [eligible (get offer "fieldNames")
        limits (get offer "imageAttachmentConstraints")
        constraints (when (and (map? limits) (= (set (keys limits)) #{"allowedMimeTypes" "maxCount" "maxBytesPerImage"}))
                      (util/wire->clj (update-keys limits keyword)))]
    (and (strings? eligible) (map? groups) (every? (set eligible) (keys groups))
         (every? vector? (vals groups))
         (or (not (contains? offer "imageAttachmentConstraints"))
             (and (s/valid? ::specs/user-input-image-attachment-constraints constraints)
                  (<= (reduce + (map count (vals groups))) (:max-count constraints))))
         (every? (fn [image]
                   (and (map? image)
                        (every? #{"type" "data" "mimeType" "displayName" "description"} (keys image))
                        (let [image (update (util/wire->clj (update-keys image keyword)) :type keyword)]
                          (if constraints (allowed-image? constraints image)
                              (s/valid? ::specs/user-input-image-attachment image)))))
                 (mapcat val groups)))))

(defn- answer-metadata [ctx answer]
  (let [metadata (elicitation-metadata (:_meta answer))]
    (when (contains? metadata ask-user-images)
      (let [offer (get-in ctx [:_meta ask-user-images])
            reply (get metadata ask-user-images)]
        (check (and (not (contains? ctx :elicitation-source)) (= (:action answer) :accept)
                    (= (get offer "version") (get reply "version") 1)
                    (negotiated-form-images? offer (get reply "attachments"))))))
    metadata))

(defn elicitation-response [ctx answer]
  (fields answer {:action #{:accept :decline :cancel}}
          {:decision-context map? :_meta map? :content #(and (map? %) (every? form-value? (vals %)))})
  (cond-> {:result (cond-> {:action (name (:action answer))}
                     (contains? answer :_meta) (assoc "_meta" (answer-metadata ctx answer))
                     (contains? answer :content)
                     (assoc :content (util/opaque-json->wire (:content answer))))}
    (contains? answer :decision-context) (assoc :decision-context (decision-context->wire (:decision-context answer)))))

(defn oauth-response [request {:keys [kind access-token] :as answer}]
  {:result
   (cond
     (= kind :cancelled) {:kind "cancelled"}
     (and (string? access-token) (seq access-token))
     (do (fields answer {:access-token string?} {:kind #{:token} :token-type string?
                                                 :expires-in #(and (integer? %) (pos? %))})
         (assoc answer :kind "token"))
     (and (= kind :runtime-refresh) (true? (:runtime-refresh-supported request)))
     {:kind "runtimeRefresh"}
     :else {:kind "cancelled"})})
