(ns ^:no-doc github.copilot-sdk.native-interaction
  "Session-local hooks and interactions implemented by the protocol-4 Node
  client. Only the reverse owner calls handlers and waits for their results."
  (:require [clojure.data.json :as json]
            [clojure.set :as sets]
            [clojure.spec.alpha :as s]
            [github.copilot-sdk.callback-results :as results]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import [java.time Instant]))

(def method-names
  #{"hooks.invoke" "systemMessage.transform" "userInput.request" "exitPlanMode.request"
    "autoModeSwitch.request" "shellAuthentication.request"})

(def ^:private hook-names
  {"preToolUse" :on-pre-tool-use "preMcpToolCall" :on-pre-mcp-tool-call
   "postToolUse" :on-post-tool-use "postToolUseFailure" :on-post-tool-use-failure
   "userPromptSubmitted" :on-user-prompt-submitted "userPromptTransformed" :on-user-prompt-transformed
   "sessionStart" :on-session-start "sessionEnd" :on-session-end "errorOccurred" :on-error-occurred
   "agentStop" :on-agent-stop "subagentStart" :on-subagent-start "subagentStop" :on-subagent-stop})

(defn callbacks [opts]
  {:hooks (get-in opts [:hooks :handlers])
   :interaction (select-keys (:interaction opts)
                             [:on-user-input-request :on-exit-plan-mode-request
                              :on-auto-mode-switch-request :on-shell-authentication-request])
   :transforms (into {} (keep (fn [[k {:keys [action]}]]
                                (when (fn? action) [(util/section-key->wire k) action])))
                     (get-in opts [:system-message :sections]))})

(defn- check [valid?]
  (when-not valid? (throw (ex-info "Invalid callback payload" {}))))

(defn- strings? [x] (and (vector? x) (every? string? x)))

(defn- enum-key [v] (if (string? v) (keyword (.replace ^String v "_" "-")) v))

(defn- hook-input [kind input]
  (let [e (:error input)]
    (cond-> input
      (number? (:timestamp input))
      (update :timestamp #(Instant/ofEpochMilli (long %)))
      (map? input) (sets/rename-keys {:cwd :working-directory :stop-hook-active :stop-hook-active?
                                      :recoverable :recoverable?})
      (and (= kind "sessionStart") (contains? input :source)) (update :source enum-key)
      (and (= kind "sessionEnd") (contains? input :reason)) (update :reason enum-key)
      (and (= kind "errorOccurred") (contains? input :error-context)) (update :error-context enum-key)
      (and (= kind "postToolUse") (contains? (:tool-result input) :result-type))
      (update-in [:tool-result :result-type] enum-key)
      (and (#{"errorOccurred" "sessionEnd"} kind) (map? e))
      (assoc :error (if (string? (:message e)) (:message e)
                        (json/write-str (util/opaque-json->wire e)))))))

(def ^:private hook-outputs
  {"preToolUse" #{:permission-decision :permission-decision-reason :modified-args :additional-context :suppress-output?}
   "preMcpToolCall" #{:meta-to-use}
   "postToolUse" #{:modified-result :additional-context :suppress-output?}
   "postToolUseFailure" #{:additional-context}
   "userPromptSubmitted" #{:modified-prompt :additional-context :suppress-output? :decision :reason}
   "userPromptTransformed" #{:modified-transformed-prompt}
   "sessionStart" #{:additional-context :modified-config}
   "sessionEnd" #{:suppress-output? :cleanup-actions :session-summary}
   "errorOccurred" #{:suppress-output? :error-handling :retry-count :user-notification}
   "agentStop" #{:decision :reason}
   "subagentStart" #{:additional-context}
   "subagentStop" #{:decision :reason :modified-response}})

(defn- hook-output [kind o]
  (check (and (map? o) (every? (hook-outputs kind) (keys o))))
  (when (and (= kind "subagentStop") (= :block (:decision o))) (check (string? (:reason o))))
  (reduce-kv
   (fn [out k v]
     (let [wire (case k
                  :modified-args (util/opaque-json->wire v)
                  :meta-to-use (do (check (or (map? v) (nil? v))) (util/opaque-json->wire v))
                  :modified-config (do (check (map? v)) (util/opaque-json->wire v))
                  :modified-result (results/structured-tool-result v)
                  :suppress-output? (do (check (boolean? v)) v)
                  :decision (do (check ((if (= kind "subagentStop") #{:allow :block} #{:block}) v)) (name v))
                  :permission-decision (do (check (#{:allow :deny :ask} v)) (name v))
                  :error-handling (do (check (#{:retry :skip :abort} v)) (name v))
                  :cleanup-actions (do (check (strings? v)) v)
                  :retry-count (do (check (and (number? v) (s/valid? ::specs/json-scalar v))) v)
                  (do (check (string? v)) v))]
       (assoc out (if (= k :suppress-output?) :suppress-output k) wire)))
   {} o))

(defn- hook [call hooks ctx {:keys [hook-type input]}]
  (check (string? hook-type))
  (if-let [f (get hooks (get hook-names hook-type))]
    (let [result (try
                   (call f (hook-input hook-type input) ctx)
                   (catch InterruptedException t (throw t))
                   (catch Throwable _ nil))]
      (if (nil? result) {} {:output (hook-output hook-type result)}))
    {}))

(defn- transform [call transforms sections]
  (check (map? sections))
  {:sections
   (reduce-kv (fn [m section {:keys [content]}]
                (check (string? content))
                (let [id (if (keyword? section) (subs (str section) 1) section)
                      f (get transforms id)]
                  (assoc m id {:content (if f
                                          (try
                                            (let [result (call f content)]
                                              (if (string? result) result content))
                                            (catch InterruptedException t (throw t))
                                            (catch Throwable _ content))
                                          content)})))
              {} sections)})

(defn- attributed [result encoded]
  (cond-> encoded
    (contains? result :decision-context)
    (assoc :decision-context (results/decision-context->wire (:decision-context result)))))

(defn- user-input [call f ctx p]
  (check (and f (string? (:question p))
              (or (not (contains? p :choices)) (strings? (:choices p)))
              (or (not (contains? p :allow-freeform)) (boolean? (:allow-freeform p)))
              (or (not (contains? p :request-id)) (string? (:request-id p)))))
  (let [req (-> (select-keys p [:question :choices :allow-freeform :request-id])
                (sets/rename-keys {:allow-freeform :allow-freeform?}))
        {:keys [answer was-freeform?] :as result} (call f req ctx)]
    (check (and (map? result) (string? answer) (boolean? was-freeform?)
                (every? #{:answer :was-freeform? :decision-context} (keys result))))
    (attributed result {:answer answer :was-freeform was-freeform?})))

(defn- exit-plan [call f ctx p]
  (check (and (string? (:summary p)) (strings? (:actions p)) (string? (:recommended-action p))
              (or (not (contains? p :plan-content)) (string? (:plan-content p)))
              (or (not (contains? p :request-id)) (string? (:request-id p)))))
  ;; No capability was advertised without a handler. An unsolicited request
  ;; does not grant authority to approve a plan on the user's behalf.
  (if-not f
    {:approved false}
    (let [result (call f (select-keys p [:summary :plan-content :actions :recommended-action :request-id]) ctx)]
      (check (and (map? result) (boolean? (:approved? result))
                  (every? #{:approved? :selected-action :feedback :decision-context} (keys result))
                  (or (not (contains? result :selected-action)) (string? (:selected-action result)))
                  (or (not (contains? result :feedback)) (string? (:feedback result)))))
      (attributed result (-> (select-keys result [:approved? :selected-action :feedback])
                             (sets/rename-keys {:approved? :approved}))))))

(defn- auto-mode [call f ctx p]
  (let [recovery (:auto-mode-recovery p)
        _ (check (and (or (not (contains? p :error-code)) (string? (:error-code p)))
                      (or (not (contains? p :retry-after-seconds)) (number? (:retry-after-seconds p)))
                      (or (not (contains? p :auto-mode-recovery))
                          (and (map? recovery) (string? (:request-id recovery))
                               (string? (:original-model recovery)) (boolean? (:allow-always recovery))
                               (every? string? (vals (select-keys recovery [:interaction-id :originating-message-id])))))))
        req (cond-> (select-keys p [:error-code :retry-after-seconds :auto-mode-recovery])
              (map? (:auto-mode-recovery p))
              (update :auto-mode-recovery sets/rename-keys {:allow-always :allow-always?}))]
    {:response (if f (case (call f req ctx)
                       :yes "yes"
                       :yes-always (if (false? (:allow-always recovery)) "no" "yes_always")
                       :no "no")
                   "no")}))

(def shell-cancel {:action "cancel"})

(defn shell-request? [{:keys [session-id request-id program prompt input-kind]}]
  (and (every? string? [session-id request-id program prompt]) (#{"secret" "text" "confirm"} input-kind)))

(defn- shell [call f ctx p signal]
  (if-not (and f signal)
    shell-cancel
    (let [req (-> (select-keys p [:request-id :program :prompt :input-kind])
                  (update :input-kind keyword)
                  (assoc :command (if (string? (:command p)) (:command p) "")
                         :cwd (if (string? (:cwd p)) (:cwd p) "")))
          result (call f req (assoc ctx :cancel-ch signal))]
      (if (and (= :accept (:action result)) (string? (:value result)))
        {:action "accept" :value (:value result)}
        shell-cancel))))

(defn handle [call {:keys [hooks interaction transforms]} method params signal]
  (let [ctx {:session-id (:session-id params)}]
    {:result (case method
               "hooks.invoke" (hook call hooks ctx params)
               "systemMessage.transform" (transform call transforms (:sections params))
               "userInput.request" (user-input call (:on-user-input-request interaction) ctx params)
               "exitPlanMode.request" (exit-plan call (:on-exit-plan-mode-request interaction) ctx params)
               "autoModeSwitch.request" (auto-mode call (:on-auto-mode-switch-request interaction) ctx params)
               "shellAuthentication.request" (shell call (:on-shell-authentication-request interaction) ctx params signal))}))
