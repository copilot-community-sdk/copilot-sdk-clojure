(ns github.copilot-sdk.integration.upstream-1-0-92-3-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support :refer [await-event-type!]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs])
  (:import [java.nio.file Files]))

(def ^:private model-providers
  {:openai "openai"
   :anthropic "anthropic"
   :azure-openai "azure_openai"
   :ollama "ollama"
   :lm-studio "lm_studio"
   :foundry-local "foundry_local"
   :llama-cpp "llama_cpp"})

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 5000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- with-client [mode f]
  (let [home (Files/createTempDirectory "copilot-provider-contract-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        server (mock/create-mock-server)
        copilot-client (sdk/client {:auto-start? false :mode mode :copilot-home (str home)})]
    (mock/start-mock-server! server)
    (try
      (let [[in out] (mock/client-streams server)]
        (client/connect-with-streams! copilot-client in out))
      (f server copilot-client)
      (finally
        (try
          (is (= (sdk/stop! copilot-client) []))
          (finally
            (mock/stop-mock-server! server)
            (Files/delete home)))))))

(defn- provider-config [kind value]
  (let [provider (cond-> {:provider-type :openai
                          :base-url "http://127.0.0.1:12345/v1"}
                   (not= value ::absent) (assoc :model-provider value))]
    (merge {:model "fixture-model"
            :on-permission-request sdk/approve-all
            :available-tools []
            :skip-custom-instructions false}
           (case kind
             :provider {:provider provider}
             :providers {:providers [(assoc provider :name "local")]
                         :models [{:id "fixture-model" :provider "local"}]}))))

(deftest provider-identity-is-a-closed-optional-keyword
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [requests (atom [])]
        (mock/set-request-hook! server (fn [method _] (swap! requests conj method)))
        (doseq [kind [:provider :providers]
                value [nil false true "" "ollama" :azure_openai :unknown 0 [] {}]
                :let [config (provider-config kind value)]]
          (testing (str kind " " (pr-str value))
            (doseq [spec [::specs/session-config
                          ::specs/resume-session-config
                          ::specs/join-session-config]]
              (is (not (s/valid? spec config))))
            (doseq [invoke [#(sdk/create-session copilot-client %)
                            #(sdk/<create-session copilot-client %)
                            #(sdk/resume-session copilot-client "unused" %)
                            #(sdk/<resume-session copilot-client "unused" %)
                            #(with-redefs [client/foreground-session-id (constantly nil)]
                               (sdk/join-session %))]]
              (is (thrown? clojure.lang.ExceptionInfo (invoke config))))))
        (is (= @requests []))))))

(deftest provider-identity-preserves-every-builder-and-omission
  (doseq [mode [:empty :copilot-cli]]
    (with-client
      mode
      (fn [server copilot-client]
        (doseq [kind [:provider :providers]
                [value wire-value] (cons [::absent nil] model-providers)
                operation [:create :create-channel :resume :resume-channel :join]]
          (testing (str mode " " kind " " operation " " value)
            (let [seed (sdk/create-session copilot-client {:available-tools []})
                  session-id (sdk/session-id seed)
                  _ (sdk/disconnect! seed)
                  requests (atom [])
                  config (provider-config kind value)
                  expected (cond-> {:type "openai" :baseUrl "http://127.0.0.1:12345/v1"}
                             (not= value ::absent) (assoc :modelProvider wire-value)
                             (= kind :providers) (assoc :name "local"))]
              (mock/set-request-hook! server
                                      (fn [method params]
                                        (swap! requests conj [method params])))
              (let [session
                    (case operation
                      :create (sdk/create-session copilot-client config)
                      :create-channel (read-result! (sdk/<create-session copilot-client config))
                      :resume (sdk/resume-session copilot-client session-id config)
                      :resume-channel (read-result! (sdk/<resume-session copilot-client session-id config))
                      :join (:session
                             (with-redefs-fn
                               {#'client/foreground-session-id (constantly session-id)
                                #'client/client (constantly copilot-client)}
                               #(sdk/join-session config))))
                    method (if (#{:create :create-channel} operation)
                             "session.create" "session.resume")]
                (try
                  (is (= (mapv #(get (second %) kind)
                               (filter #(= (first %) method) @requests))
                         [(if (= kind :provider) expected [expected])]))
                  (is (some #(= (first %) "session.options.update") @requests))
                  (doseq [[request-method params] @requests
                          :when (not= request-method method)]
                    (is (empty? (select-keys params [:provider :providers :modelProvider]))
                        request-method))
                  (finally
                    (sdk/disconnect! session)))))))))))

(def ^:private byok-events
  [[:copilot/assistant.usage ::specs/assistant.usage-data {:model "fixture-model"}]
   [:copilot/model.call_failure ::specs/model.call_failure-data {:source "top_level"}]])

(deftest byok-event-metadata-is-optional-extensible-string-data
  (doseq [[event-type spec base] byok-events
          field [:model-provider :byok-kind]
          [value valid?] [[::absent true] ["" true] ["future_value" true]
                          [nil false] [false false] [true false] [0 false]
                          [:ollama false] [[] false] [{} false]]]
    (is (= (s/valid? spec (cond-> base
                            (not= value ::absent) (assoc field value)))
           valid?)
        (str event-type " " field " " (pr-str value)))))

(deftest byok-event-metadata-survives-live-and-history
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [session (sdk/create-session copilot-client {})
            session-id (sdk/session-id session)
            events (sdk/subscribe-events session)]
        (try
          (doseq [[event-type spec base] byok-events
                  metadata [{} {:modelProvider "" :byokKind ""}
                            {:modelProvider "ollama" :byokKind "local_user"}
                            {:modelProvider "other" :byokKind "remote_user"}
                            {:modelProvider "future_product" :byokKind "future_kind"}]
                  :let [wire (merge base metadata)
                        expected (cond-> base
                                   (contains? metadata :modelProvider)
                                   (assoc :model-provider (:modelProvider metadata))
                                   (contains? metadata :byokKind)
                                   (assoc :byok-kind (:byokKind metadata)))]]
            (mock/send-session-event! server session-id event-type wire)
            (let [live (:data (await-event-type! events event-type 5000))]
              (is (= live expected))
              (is (s/valid? spec live)))
            (mock/set-session-messages! server session-id
                                        [{:type (name event-type) :data wire}])
            (let [history (:data (first (sdk/get-messages session)))]
              (is (= history expected))
              (is (s/valid? spec history))))
          (finally
            (sdk/unsubscribe-events! session events)
            (sdk/disconnect! session)))))))

(deftest experimental-progress-json-retains-source-keys
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [session (sdk/create-session copilot-client {})
            session-id (sdk/session-id session)
            events (sdk/subscribe-events session)]
        (try
          (doseq [content [nil false true "" 42 [] {}
                           {:camelCase {:snake_case [{:mixed.Key false}]}}]
                  :let [wire {:toolCallId "tool" :progressMessage "working"
                              :structuredContent content}
                        expected {:tool-call-id "tool" :progress-message "working"
                                  :structured-content content}]]
            (mock/send-session-event! server session-id :copilot/tool.execution_progress wire)
            (is (= (:data (await-event-type! events :copilot/tool.execution_progress 5000))
                   expected))
            (mock/set-session-messages! server session-id
                                        [{:type "tool.execution_progress" :data wire}])
            (is (= (:data (first (sdk/get-messages session))) expected)))
          (finally
            (sdk/unsubscribe-events! session events)
            (sdk/disconnect! session)))))))
