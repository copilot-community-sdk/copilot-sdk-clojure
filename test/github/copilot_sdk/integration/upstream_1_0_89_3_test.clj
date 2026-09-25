(ns github.copilot-sdk.integration.upstream-1-0-89-3-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as wire]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value!
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]))

(use-fixtures :each with-mock-server)

(defn- valid? [spec value]
  (and (some? (s/get-spec spec)) (s/valid? spec value)))

(defn- read-channel! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 5000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(deftest instruction-refresh-is-a-create-only-boolean
  (is (s/valid? ::specs/session-config {}))
  (doseq [value [false true nil "" [] {} 1]]
    (let [config {:refresh-custom-instructions? value}]
      (is (= (s/valid? ::specs/session-config config) (boolean? value)))
      (doseq [spec [::specs/resume-session-config ::specs/join-session-config]]
        (is (not (s/valid? spec config)) (str spec " " config)))))
  (let [requests (atom [])]
    (mock/set-request-hook! *mock-server*
                            (fn [method _] (swap! requests conj method)))
    (doseq [value [nil "" [] {} 1]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/create-session *test-client*
                                       {:refresh-custom-instructions? value}))))
    (is (= @requests []))))

(deftest instruction-refresh-preserves-omission-and-false-on-both-create-paths
  (doseq [[label create] [[:blocking sdk/create-session]
                          [:channel #(read-channel! (sdk/<create-session %1 %2))]]
          refresh [::absent false true]]
    (testing (str label " " refresh)
      (let [requests (atom [])
            config (cond-> {:skip-custom-instructions false}
                     (not= refresh ::absent)
                     (assoc :refresh-custom-instructions? refresh))]
        (mock/set-request-hook!
         *mock-server* (fn [method params] (swap! requests conj [method params])))
        (let [session (create *test-client* config)]
          (try
            (let [creates (filter #(= (first %) "session.create") @requests)
                  params (second (first creates))]
              (is (= (count creates) 1))
              (is (= (select-keys params [:refreshCustomInstructions])
                     (if (= refresh ::absent)
                       {}
                       {:refreshCustomInstructions refresh}))))
            (sdk/disconnect! session)
            (let [resumed (sdk/resume-session *test-client* (sdk/session-id session)
                                              {:skip-custom-instructions false})]
              (sdk/disconnect! resumed))
            (is (some #(= (first %) "session.options.update") @requests))
            (doseq [[method params] @requests
                    :when (not= method "session.create")]
              (is (not (contains? params :refreshCustomInstructions)) method))
            (finally
              (sdk/disconnect! session))))))))

(deftest selective-rewind-event-contract
  (doseq [spec [::specs/session.snapshot_rewind-data ::wire/session.snapshot_rewind-data]
          [ids expected] [[::absent true] [[] true] [["first" "last"] true] [[""] true]
                          [nil false] [false false] [{} false] [#{1} false]
                          ['("first") false] [[1] false] [[nil] false]]]
    (let [data (cond-> {:up-to-event-id "first" :events-removed 2}
                 (not= ids ::absent) (assoc :event-ids ids))]
      (is (= (valid? spec data) expected) (str spec " " data)))))

(deftest oauth-static-client-scope-contract
  (doseq [spec [::specs/mcp-auth-static-client-config
                ::wire/mcp-oauth-required-static-client-config-shape]
          [scope expected] [[::absent true] ["" true] ["read write" true]
                            [nil false] [false false] [1 false] [[] false]]]
    (let [config (cond-> {:client-id "" :public-client false}
                   (not= scope ::absent) (assoc :scope scope))]
      (is (= (valid? spec config) expected) (str spec " " config))
      (doseq [event-spec [::specs/mcp.oauth_required-data ::wire/mcp.oauth_required-data]]
        (is (= (valid? event-spec
                       {:request-id "oauth" :server-name "server"
                        :server-url "https://mcp.example" :reason "initial"
                        :static-client-config config})
               expected)))))
  (doseq [config [{} {:client-id nil} {:client-id "id" :unknown true}
                  {:client-id "id" :public-client nil}
                  {:client-id "id" :grant-type "unknown"}]]
    (is (not (valid? ::specs/mcp-auth-static-client-config config)))))

(deftest new-event-fields-survive-live-and-history-paths
  (let [session (sdk/create-session *test-client* {})
        session-id (sdk/session-id session)
        events (sdk/subscribe-events session)
        cases
        (concat
         (for [ids [::absent [] ["first" "last"]]]
           [:copilot/session.snapshot_rewind
            (cond-> {:upToEventId "first" :eventsRemoved 2}
              (not= ids ::absent) (assoc :eventIds ids))
            (cond-> {:up-to-event-id "first" :events-removed 2}
              (not= ids ::absent) (assoc :event-ids ids))
            ::specs/session.snapshot_rewind-data])
         (for [scope [::absent "" "configured.read configured.write"]
               :let [static (cond-> {:clientId "client" :publicClient false}
                              (not= scope ::absent) (assoc :scope scope))
                     expected (cond-> {:client-id "client" :public-client false}
                                (not= scope ::absent) (assoc :scope scope))]]
           [:copilot/mcp.oauth_required
            {:requestId "oauth" :serverName "server" :serverUrl "https://mcp.example"
             :reason "initial" :staticClientConfig static}
            {:request-id "oauth" :server-name "server" :server-url "https://mcp.example"
             :reason "initial" :static-client-config expected}
            ::specs/mcp.oauth_required-data]))]
    (try
      (doseq [[event-type data expected spec] cases]
        (testing (str event-type " " data)
          (mock/send-session-event! *mock-server* session-id event-type data)
          (let [event (await-event-type! events event-type 5000)]
            (is (= (:data event) expected))
            (is (valid? spec (:data event))))
          (mock/set-session-messages! *mock-server* session-id
                                      [{:type (name event-type) :data data}])
          (let [[event] (sdk/get-messages session)]
            (is (= (:type event) event-type))
            (is (= (:data event) expected))
            (is (valid? spec (:data event))))))
      (finally
        (sdk/unsubscribe-events! session events)
        (sdk/disconnect! session)))))

(deftest oauth-handler-receives-configured-scope-without-rewriting-it
  (doseq [scope [::absent "" "configured.read configured.write"]]
    (let [observed (promise)
          response (promise)
          session (sdk/create-session
                   *test-client*
                   {:on-mcp-auth-request
                    (fn [request _]
                      (deliver observed request)
                      {:kind :cancelled})})
          session-id (sdk/session-id session)
          expected (cond-> {:client-id "client" :public-client false}
                     (not= scope ::absent) (assoc :scope scope))]
      (try
        (mock/set-request-hook!
         *mock-server*
         (fn [method params]
           (when (= method "session.mcp.oauth.handlePendingRequest")
             (deliver response params))))
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        (mock/send-v3-broadcast-event!
         *mock-server* session-id "mcp.oauth_required"
         {:requestId "oauth" :serverName "server" :serverUrl "https://mcp.example"
          :reason "initial"
          :staticClientConfig
          (cond-> {:clientId "client" :publicClient false}
            (not= scope ::absent) (assoc :scope scope))})
        (is (= (:static-client-config (await-value! observed "OAuth callback" 5000))
               expected))
        (is (= (:result (await-value! response "OAuth response" 5000))
               {:kind "cancelled"}))
        (finally
          (sdk/disconnect! session))))))
