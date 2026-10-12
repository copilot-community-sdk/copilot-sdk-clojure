(ns github.copilot-sdk.integration.upstream-1-0-96-2-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.teardown :as teardown]))

(use-fixtures :each with-mock-server)

(def ^:private transport-fields
  [{:key :request-body-encoding :wire-key :requestBodyEncoding
    :values ["identity" "gzip" "zstd"]
    :invalid [nil "" "brotli" :gzip false]}
   {:key :request-body-wire-bytes :wire-key :requestBodyWireBytes
    :values [0 1 1048576]
    :invalid [nil -1 0.5 "0" false ##NaN ##Inf]}
   {:key :websocket-fallback-error-kind :wire-key :websocketFallbackErrorKind
    :values ["dns" "tls" "timeout" "connection_refused" "connection_reset"
             "closed_by_peer" "closed_locally" "not_connected" "http_status"
             "protocol" "configuration" "other"]
    :invalid [nil "" "unknown" :dns false]}
   {:key :websocket-fallback-status-code :wire-key :websocketFallbackStatusCode
    :values [0 503 999]
    :invalid [nil -1 0.5 1000 "503" false ##NaN ##Inf]}
   {:key :websocket-fallback-close-code :wire-key :websocketFallbackCloseCode
    :values [0 1006 4999]
    :invalid [nil -1 0.5 5000 "1006" false ##NaN ##Inf]}
   {:key :websocket-fallback-reconnect-attempted :wire-key :websocketFallbackReconnectAttempted
    :values [false true]
    :invalid [nil 0 1 "" "false"]}
   {:key :websocket-fallback-started-this-call :wire-key :websocketFallbackStartedThisCall
    :values [false true]
    :invalid [nil 0 1 "" "false"]}])

(def ^:private transport-events
  [{:type :copilot/assistant.usage :base {:model "fixture"}
    :specs [::specs/assistant.usage-data ::gen/assistant.usage-data]}
   {:type :copilot/model.call_failure :base {:source "top_level"}
    :specs [::specs/model.call_failure-data ::gen/model.call_failure-data]}])

(deftest transport-fields-have-exact-optional-wire-and-idiom-contracts
  (doseq [{:keys [type base specs]} transport-events, spec specs]
    (testing (str type " " spec)
      (is (s/valid? spec base))
      (is (s/valid? spec (assoc base :future-field {:value false})))
      (doseq [{:keys [key values invalid]} transport-fields]
        (doseq [value values]
          (is (s/valid? spec (assoc base key value)) (str key " " value)))
        (doseq [value invalid]
          (is (not (s/valid? spec (assoc base key value))) (str key " " value)))))))

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

(deftest transport-fields-preserve-values-and-omission-on-live-and-history-paths
  (with-event-session
    (fn [session events]
      (let [session-id (sdk/session-id session)
            cases (cons [{} {}]
                        (for [{:keys [key wire-key values]} transport-fields, value values]
                          [{wire-key value} {key value}]))]
        (doseq [{:keys [type base]} transport-events
                [wire data] cases]
          (mock/send-session-event! *mock-server* session-id type (merge base wire))
          (is (= (:data (await-event-type! events type 5000)) (merge base data)))
          (mock/set-session-messages! *mock-server* session-id
                                      [{:type (name type) :data (merge base wire)}])
          (is (= (:data (first (sdk/get-messages session))) (merge base data))))))))

(deftest passive-managed-context-preserves-opaque-policy-keys
  (with-event-session
    (fn [session events]
      (let [session-id (sdk/session-id session)
            type :copilot/session.managed_settings_resolved
            base {:source "client" :serverManaged false :deviceManaged false
                  :failClosed false :bypassPermissionsDisabled false :managedKeys ["permissions"]}
            policies [nil false "" 0 []
                      {:limitTo ["Domain(api.example.com)"]
                       :Source_ID {:nestedPolicy [{:URLMatches [] :MixedCase false}]}
                       :emptyObject {} :nothing nil}]]
        (doseq [context (cons {:failClosed false}
                              (map #(hash-map :failClosed false :permissions %) policies))]
          (let [wire (assoc base :permissionsContext context)
                expected (cond-> {:fail-closed false}
                           (contains? context :permissions)
                           (assoc :permissions (:permissions context)))]
            (mock/send-session-event! *mock-server* session-id type wire)
            (is (= (get-in (await-event-type! events type 5000) [:data :permissions-context])
                   expected))
            (mock/set-session-messages! *mock-server* session-id [{:type (name type) :data wire}])
            (is (= (get-in (first (sdk/get-messages session)) [:data :permissions-context])
                   expected))))))))

(deftest passive-monthly-usage-preserves-zero-fractions-and-unknown-consumption
  (with-event-session
    (fn [session events]
      (let [session-id (sdk/session-id session)
            type :copilot/session.quota_observation
            provider {:id "provider" :label "Provider" :kind "loki"}
            base {:provider provider :quotaId "monthly" :unit "unknown"
                  :accessState "unknown" :capacityState "unknown"}
            timestamp "2026-10-10T02:20:03Z"]
        (doseq [[wire expected]
                [[{} {}]
                 [{:monthlyUsage {:scope "user" :state "available" :unit "ai_credits"
                                  :consumedQuantity 0 :queriedAt timestamp}}
                  {:monthly-usage {:scope "user" :state "available" :unit "ai_credits"
                                   :consumed-quantity 0 :queried-at timestamp}}]
                 [{:monthlyUsage {:scope "user" :state "available" :unit "ai_credits"
                                  :consumedQuantity 12.5 :cycleStart timestamp :resetOn timestamp}}
                  {:monthly-usage {:scope "user" :state "available" :unit "ai_credits"
                                   :consumed-quantity 12.5 :cycle-start timestamp :reset-on timestamp}}]
                 [{:monthlyUsage {:scope "unknown" :state "unavailable" :unit "unknown"}}
                  {:monthly-usage {:scope "unknown" :state "unavailable" :unit "unknown"}}]]]
          (mock/send-notification!
           *mock-server* "session.event"
           {:sessionId session-id
            :event {:id (str (random-uuid)) :timestamp timestamp :parentId nil :ephemeral true
                    :type (name type) :data {:observation (merge base wire)}}})
          (let [data (:data (await-event-type! events type 5000))]
            (is (= (:observation data)
                   (merge {:provider provider :quota-id "monthly" :unit "unknown"
                           :access-state "unknown" :capacity-state "unknown"}
                          expected)))
            (is (s/valid? ::gen/session.quota_observation-data data))))))))
