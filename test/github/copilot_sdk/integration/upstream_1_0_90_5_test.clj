(ns github.copilot-sdk.integration.upstream-1-0-90-5-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as wire]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

(use-fixtures :each with-mock-server)

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 5000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(deftest allowed-models-validates-all-session-configurations
  (doseq [spec [::specs/session-config ::specs/resume-session-config
                ::specs/join-session-config]
          [value expected] [[::absent true] [[] true] [["gpt-5.4"] true]
                            [["" "provider/model" "gpt-5.4" "gpt-5.4"] true]
                            [nil false] [false false] [true false] ["" false]
                            [{} false] [#{"gpt-5.4"} false] ['("gpt-5.4") false]
                            [[nil] false] [[1] false] [[:gpt-5.4] false]]]
    (is (= (s/valid? spec (cond-> {}
                            (not= value ::absent) (assoc :allowed-models value)))
           expected)
        (str spec " " (pr-str value))))
  (let [requests (atom [])]
    (mock/set-request-hook! *mock-server*
                            (fn [method _] (swap! requests conj method)))
    (doseq [value [nil false true "" {} #{"gpt-5.4"} '("gpt-5.4") [nil] [1]]
            operation [#(sdk/create-session *test-client* %)
                       #(sdk/resume-session *test-client* "unused" %)
                       #(read-result! (sdk/<create-session *test-client* %))
                       #(read-result! (sdk/<resume-session *test-client* "unused" %))
                       sdk/join-session]]
      (is (thrown? clojure.lang.ExceptionInfo (operation {:allowed-models value}))))
    (is (= @requests []))))

(deftest allowed-models-preserves-exact-wire-values-and-omission
  (doseq [[label create resume]
          [[:blocking sdk/create-session sdk/resume-session]
           [:channel #(read-result! (sdk/<create-session %1 %2))
            #(read-result! (sdk/<resume-session %1 %2 %3))]]
          models [::absent [] ["gpt-5.4"] ["" "provider/model" "gpt-5.4" "gpt-5.4"]]]
    (testing (str label " " (pr-str models))
      (let [requests (atom [])
            config (cond-> {:skip-custom-instructions false}
                     (not= models ::absent) (assoc :allowed-models models))
            expected (if (= models ::absent) {} {:allowedModels models})]
        (mock/set-request-hook!
         *mock-server* (fn [method params] (swap! requests conj [method params])))
        (let [session (create *test-client* config)]
          (try
            (sdk/disconnect! session)
            (let [resumed (resume *test-client* (sdk/session-id session) config)]
              (sdk/disconnect! resumed))
            (is (= (mapv #(select-keys (second %) [:allowedModels])
                         (filter #(contains? #{"session.create" "session.resume"} (first %))
                                 @requests))
                   [expected expected]))
            (is (some #(= (first %) "session.options.update") @requests))
            (doseq [[method params] @requests
                    :when (not (contains? #{"session.create" "session.resume"} method))]
              (is (not (contains? params :allowedModels)) method))
            (is (= (select-keys
                    (util/clj->wire
                     (#'client/build-resume-session-params (sdk/session-id session) config))
                    [:allowedModels])
                   expected)
                "join inherits the resume builder without a separate model-policy update")
            (finally
              (sdk/disconnect! session))))))))

(deftest mcp-failure-metadata-is-optional-non-null-and-extensible
  (doseq [spec [::specs/session.mcp_server_status_changed-data
                ::wire/session.mcp_server_status_changed-data]
          field [:config-source :error-classification]
          [value expected] [[::absent true] ["" true] ["future-runtime-value" true]
                            [nil false] [false false] [true false] [1 false] [[] false] [{} false]]]
    (let [data (cond-> {:server-name "example" :status "failed"}
                 (not= value ::absent) (assoc field value))]
      (is (= (s/valid? spec data) expected) (str spec " " data)))))

(deftest new-event-metadata-survives-live-and-history
  (let [session (sdk/create-session *test-client* {})
        session-id (sdk/session-id session)
        events (sdk/subscribe-events session)
        cases
        (concat
         (for [source [::absent "" "user" "future-source"]
               classification [::absent "" "unclassified" "future-classification"]]
           [:copilot/session.mcp_server_status_changed
            (cond-> {:serverName "example" :status "failed" :error "connection refused"}
              (not= source ::absent) (assoc :configSource source)
              (not= classification ::absent) (assoc :errorClassification classification))
            (cond-> {:server-name "example" :status "failed" :error "connection refused"}
              (not= source ::absent) (assoc :config-source source)
              (not= classification ::absent) (assoc :error-classification classification))
            ::specs/session.mcp_server_status_changed-data])
         [[:copilot/permission.completed
           {:requestId "read"
            :result {:kind "approved-read-only-for-session" :directories ["/workspace"]}}
           {:request-id "read"
            :result {:kind "approved-read-only-for-session" :directories ["/workspace"]}}
           ::specs/permission.completed-data]])]
    (try
      (doseq [[event-type data expected spec] cases]
        (mock/send-session-event! *mock-server* session-id event-type data)
        (let [event (await-event-type! events event-type 5000)]
          (is (= (:data event) expected))
          (is (s/valid? spec (:data event))))
        (mock/set-session-messages! *mock-server* session-id
                                    [{:type (name event-type) :data data}])
        (let [[event] (sdk/get-messages session)]
          (is (= (:type event) event-type))
          (is (= (:data event) expected))
          (is (s/valid? spec (:data event)))))
      (is (not (s/valid? ::specs/permission-result
                         {:kind :approve-read-only-for-session :directories ["/workspace"]}))
          "passive event decoding does not expose experimental permission authority")
      (finally
        (sdk/unsubscribe-events! session events)
        (sdk/disconnect! session)))))

(deftest session-events-drain-before-remote-eof-tears-down-the-session
  (let [session (sdk/create-session *test-client* {})
        events (sdk/subscribe-events session)]
    (try
      (dotimes [index 64]
        (mock/send-session-event!
         *mock-server* (sdk/session-id session) :copilot/session.mcp_server_status_changed
         {:serverName (str index) :status "failed" :errorClassification "future-value"}))
      (.close ^java.io.PipedOutputStream (:server-out *mock-server*))
      (let [collected (async/into [] events)
            [received port] (async/alts!! [collected (async/timeout 5000)])
            statuses (filter #(= (:type %) :copilot/session.mcp_server_status_changed)
                             received)]
        (is (= port collected))
        (is (= (mapv #(get-in % [:data :server-name]) statuses)
               (mapv str (range 64)))))
      (finally
        (async/close! events)))))
