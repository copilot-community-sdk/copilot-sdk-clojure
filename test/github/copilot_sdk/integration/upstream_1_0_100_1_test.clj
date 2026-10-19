(ns github.copilot-sdk.integration.upstream-1-0-100-1-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk.instrument]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.teardown :as teardown]
            [github.copilot-sdk.util :as util]))

(use-fixtures :each with-mock-server)

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 15000)])]
    (when (or (not= port channel) (nil? value))
      (throw (ex-info "SDK operation did not produce a result" {:closed? (= port channel)})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- open-session [operation config]
  (case operation
    :create (sdk/create-session *test-client* config)
    :create-channel (read-result! (sdk/<create-session *test-client* config))
    (let [seed (sdk/create-session *test-client* {})
          id (sdk/session-id seed)]
      (sdk/disconnect! seed)
      (case operation
        :resume (sdk/resume-session *test-client* id config)
        :resume-channel (read-result! (sdk/<resume-session *test-client* id config))
        :join (:session
               (with-redefs-fn
                 {#'client/foreground-session-id (constantly id)
                  #'client/client (constantly *test-client*)}
                 #(sdk/join-session config)))))))

(def ^:private oauth-cases
  [{:label "advertised refresh" :capability true
    :result {:kind :runtime-refresh} :expected {:kind "runtimeRefresh"}}
   {:label "omitted capability" :result {:kind :runtime-refresh}
    :expected {:kind "cancelled"}}
   {:label "false capability" :capability false
    :result {:kind :runtime-refresh} :expected {:kind "cancelled"}}
   {:label "cancellation wins" :capability true
    :result {:kind :cancelled :access-token "not-used"} :expected {:kind "cancelled"}}
   {:label "token wins over handoff" :capability true
    :result {:kind :runtime-refresh :access-token "provided"}
    :expected {:kind "token" :accessToken "provided"}}
   {:label "token needs no refresh capability" :capability false
    :result {:kind :runtime-refresh :access-token "provided"}
    :expected {:kind "token" :accessToken "provided"}}
   {:label "bare token preserves optional values"
    :result {:access-token "legacy" :token-type "" :expires-in 0}
    :expected {:kind "token" :accessToken "legacy" :tokenType "" :expiresIn 0}}
   {:label "nil token options remain omitted"
    :result {:kind :token :access-token "provided" :token-type nil :expires-in nil}
    :expected {:kind "token" :accessToken "provided"}}
   {:label "token whitespace is not rewritten"
    :result {:access-token " "} :expected {:kind "token" :accessToken " "}}
   {:label "empty token cancels"
    :result {:kind :token :access-token ""} :expected {:kind "cancelled"}}
   {:label "non-string token cancels"
    :result {:kind :token :access-token 42} :expected {:kind "cancelled"}}
   {:label "nil token cancels"
    :result {:access-token nil} :expected {:kind "cancelled"}}
   {:label "empty token does not suppress advertised handoff" :capability true
    :result {:kind :runtime-refresh :access-token ""} :expected {:kind "runtimeRefresh"}}
   {:label "nil cancels" :result nil :expected {:kind "cancelled"}}
   {:label "malformed result cancels" :result "invalid" :expected {:kind "cancelled"}}
   {:label "unknown kind cancels" :result {:kind :unknown} :expected {:kind "cancelled"}}
   {:label "throwing handler cancels" :throw? true :expected {:kind "cancelled"}}
   {:label "channel handoff" :capability true :channel? true
    :result {:kind :runtime-refresh} :expected {:kind "runtimeRefresh"}}
   {:label "closed channel cancels" :channel? true
    :result nil :expected {:kind "cancelled"}}])

(deftest oauth-response-precedence-and-capability-gating-in-every-builder
  (doseq [operation [:create :create-channel :resume :resume-channel :join]]
    (let [active (atom nil)
          requests (atom [])
          handler (fn [request context]
                    (let [{:keys [received result channel? throw?]} @active]
                      (deliver received [request context])
                      (when throw?
                        (throw (ex-info "OAuth fixture failure" {})))
                      (if channel?
                        (let [out (async/promise-chan)]
                          (when (some? result)
                            (async/put! out result))
                          (async/close! out)
                          out)
                        result)))]
      (mock/set-request-hook!
       *mock-server*
       (fn [method params]
         (swap! requests conj [method params])
         (when (= method "session.mcp.oauth.handlePendingRequest")
           (deliver (:replied @active) params))))
      (let [session (open-session operation {:on-mcp-auth-request handler})
            id (sdk/session-id session)]
        (teardown/call-with-cleanup
         (fn []
           (is (= (mapv #(get-in % [1 :eventType])
                        (filter #(= (first %) "session.eventLog.registerInterest") @requests))
                  ["mcp.oauth_required"]))
           (doseq [[method params] @requests
                   :when (contains? #{"session.create" "session.resume" "session.options.update"} method)]
             (is (not (contains? params :runtimeRefreshSupported)))
             (is (not (contains? params :onMcpAuthRequest))))
           (doseq [{:keys [label capability expected] :as entry} oauth-cases]
             (testing (str operation " " label)
               (let [received (promise)
                     replied (promise)
                     wire (cond-> {:requestId label :serverName "fixture"
                                   :serverUrl "https://mcp.example.test" :reason "refresh"}
                            (contains? entry :capability) (assoc :runtimeRefreshSupported capability))]
                 (reset! active (assoc entry :received received :replied replied))
                 (mock/send-v3-broadcast-event! *mock-server* id "mcp.oauth_required" wire)
                 (is (= (await-value! received "OAuth handler" 5000)
                        [(util/wire->clj wire) {:session-id id}]))
                 (is (= (await-value! replied "OAuth response" 5000)
                        {:sessionId id :requestId label :result expected})))))
           (sdk/ping *test-client*)
           (is (= (frequencies
                   (map #(get-in % [1 :requestId])
                        (filter #(= (first %) "session.mcp.oauth.handlePendingRequest") @requests)))
                  (zipmap (map :label oauth-cases) (repeat 1)))))
         #(sdk/disconnect! session))))))

(deftest oauth-refresh-capability-is-optional-non-null-event-data
  (let [base {:request-id "request" :server-name "server"
              :server-url "https://mcp.example.test" :reason "refresh"}]
    (doseq [spec [::specs/mcp.oauth_required-data ::gen/mcp.oauth_required-data]]
      (is (s/valid? spec base))
      (doseq [value [false true]]
        (is (s/valid? spec (assoc base :runtime-refresh-supported value))))
      (doseq [value [nil 0 "true" []]]
        (is (not (s/valid? spec (assoc base :runtime-refresh-supported value))))))))

(defn- with-event-session [f]
  (let [started (promise)
        session (sdk/create-session
                 *test-client*
                 {:on-event #(when (= (:type %) :copilot/session.start)
                               (deliver started true))})]
    (teardown/call-with-cleanup
     #(do
        (await-value! started "initial session.start" 5000)
        (let [events (sdk/subscribe-events session)]
          (teardown/call-with-cleanup
           (fn [] (f session events))
           (fn [] (sdk/unsubscribe-events! session events)))))
     #(sdk/disconnect! session))))

(def ^:private new-events
  [{:type :copilot/mcp.oauth_recovery_completed
    :specs [::specs/mcp.oauth_recovery_completed-data ::gen/mcp.oauth_recovery_completed-data]
    :base {:request-id "request" :server-name "server" :outcome "succeeded"}
    :fields {:request-id ["" nil] :server-name ["" nil]
             :outcome ["cancelled" "invalid"]}
    :ephemeral? false}
   {:type :copilot/session.mcp_server_tools_loaded
    :specs [::specs/session.mcp_server_tools_loaded-data ::gen/session.mcp_server_tools_loaded-data]
    :base {:server-name "server" :tool-count 0}
    :fields {:server-name ["" nil] :tool-count [-1 0.5]}
    :ephemeral? true}])

(deftest new-mcp-events-have-open-typed-payloads
  (doseq [{:keys [type specs base fields]} new-events]
    (is (contains? sdk/event-types type))
    (is (s/valid? ::specs/event-type type))
    (doseq [spec specs]
      (is (some? (s/get-spec spec)) (str spec))
      (when (s/get-spec spec)
        (is (s/valid? spec base))
        (is (s/valid? spec (assoc base :future-field {:value false})))
        (doseq [[key [valid invalid]] fields]
          (is (s/valid? spec (assoc base key valid)))
          (is (not (s/valid? spec (assoc base key invalid))))
          (is (not (s/valid? spec (assoc base key nil))))
          (is (not (s/valid? spec (dissoc base key))))))))
  (is (contains? sdk/session-events :copilot/session.mcp_server_tools_loaded))
  (is (contains? sdk/interaction-events :copilot/mcp.oauth_recovery_completed)))

(deftest mcp-events-preserve-payloads-and-ephemeral-status
  (with-event-session
    (fn [session events]
      (let [id (sdk/session-id session)]
        (doseq [[type wire expected ephemeral?]
                (concat
                 (for [outcome ["succeeded" "cancelled" "failed"]]
                   [:copilot/mcp.oauth_recovery_completed
                    {:requestId "request" :serverName "server" :outcome outcome}
                    {:request-id "request" :server-name "server" :outcome outcome} false])
                 (for [count [0 3]]
                   [:copilot/session.mcp_server_tools_loaded
                    {:serverName "server" :toolCount count}
                    {:server-name "server" :tool-count count} true]))]
          (let [envelope {:id (str (random-uuid)) :timestamp "2026-10-19T07:00:00Z"
                          :parentId nil :ephemeral ephemeral? :type (name type) :data wire}]
            (mock/send-notification! *mock-server* "session.event" {:sessionId id :event envelope})
            (let [event (await-event-type! events type 5000)]
              (is (= (:data event) expected))
              (is (= (:ephemeral event) ephemeral?))
              (is (= (:timestamp event) (:timestamp envelope))))
            (when-not ephemeral?
              (mock/set-session-messages! *mock-server* id [envelope])
              (let [event (first (sdk/get-messages session))]
                (is (= (:type event) type))
                (is (= (:data event) expected))))))))))

(def ^:private compression-fields
  [{:key :request-body-compression-fallback :wire-key :requestBodyCompressionFallback
    :values ["retried" "disabled"] :invalid [nil "" "unknown" :retried false]}
   {:key :request-body-compression-fallback-from :wire-key :requestBodyCompressionFallbackFrom
    :values ["identity" "gzip" "zstd"] :invalid [nil "" "brotli" :gzip false]}
   {:key :request-body-compression-rejected-status :wire-key :requestBodyCompressionRejectedStatus
    :values [0 400 415 999] :invalid [nil -1 0.5 1000 "415" false ##NaN ##Inf]}])

(def ^:private compression-events
  [{:type :copilot/assistant.usage :base {:model "fixture"}
    :specs [::specs/assistant.usage-data ::gen/assistant.usage-data]}
   {:type :copilot/model.call_failure :base {:source "top_level"}
    :specs [::specs/model.call_failure-data ::gen/model.call_failure-data]}])

(deftest compression-diagnostics-have-exact-optional-contracts
  (doseq [{:keys [type base specs]} compression-events, spec specs]
    (testing (str type " " spec)
      (is (s/valid? spec base))
      (is (s/valid? spec (assoc base :future-field {:value false})))
      (doseq [{:keys [key values invalid]} compression-fields]
        (doseq [value values]
          (is (s/valid? spec (assoc base key value)) (str key " " value)))
        (doseq [value invalid]
          (is (not (s/valid? spec (assoc base key value))) (str key " " value)))))))

(deftest compression-diagnostics-preserve-omission-and-values-live-and-in-history
  (with-event-session
    (fn [session events]
      (let [id (sdk/session-id session)
            cases (cons [{} {}]
                        (for [{:keys [key wire-key values]} compression-fields, value values]
                          [{wire-key value} {key value}]))]
        (doseq [{:keys [type base]} compression-events, [wire expected] cases]
          (mock/send-session-event! *mock-server* id type (merge base wire))
          (is (= (:data (await-event-type! events type 5000)) (merge base expected)))
          (mock/set-session-messages! *mock-server* id [{:type (name type) :data (merge base wire)}])
          (is (= (:data (first (sdk/get-messages session))) (merge base expected))))))))

(deftest internal-compression-state-remains-wire-only
  (let [type :copilot/session.request_body_compression_disabled]
    (is (contains? gen/event-types (name type)))
    (doseq [events [sdk/event-types sdk/session-events sdk/interaction-events]]
      (is (not (contains? events type))))
    (is (not (s/valid? ::specs/event-type type)))
    (is (nil? (s/get-spec ::specs/session.request_body_compression_disabled-data)))))
