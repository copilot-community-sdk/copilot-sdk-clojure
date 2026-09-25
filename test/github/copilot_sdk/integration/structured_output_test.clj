(ns github.copilot-sdk.integration.structured-output-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each with-mock-server)

(def ^:private response-json-schema
  {"type" "object"
   "properties" {:answer-value {"type" "integer"}}
   "required" ["answer-value"]
   "additionalProperties" false})

(defn- parsed-response-schema
  [parse]
  {:to-json-schema (constantly response-json-schema)
   :parse parse})

(deftest response-schema-spec-contract
  (let [response-schema-spec (s/get-spec ::specs/response-schema)
        parsed-response-schema-spec
        (s/get-spec ::specs/parsed-response-schema)]
    (is (some? response-schema-spec))
    (is (some? parsed-response-schema-spec))
    (when (and response-schema-spec parsed-response-schema-spec)
      (is (s/valid? ::specs/response-schema response-json-schema))
      (is (s/valid? ::specs/response-schema
                    (parsed-response-schema identity)))
      (is (s/valid? ::specs/parsed-response-schema
                    (assoc (parsed-response-schema identity)
                           :schema-name "answer")))
      (doseq [invalid [nil
                       {:to-json-schema (constantly response-json-schema)}
                       {:parse identity}
                       {"type" "object"
                        "properties" {"values" #{1 2 3}}}
                       {"type" "object" "metadata" (Object.)}]]
        (is (not (s/valid? ::specs/response-schema invalid))
            (str "must reject " (pr-str invalid)))))))

(deftest response-schema-rejects-immediate-mode
  (is (not
       (s/valid?
        ::specs/send-options
        {:prompt "Do not admit"
         :mode :immediate
         :response-schema response-json-schema}))))

(deftest response-schema-wire-contract
  (let [requests (atom [])
        schema-calls (atom 0)
        schema {:to-json-schema
                (fn []
                  (swap! schema-calls inc)
                  response-json-schema)
                :parse identity}
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (swap! requests conj params))))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})]
    (sdk/send! copilot-session
               {:prompt "Return an object"
                :response-schema schema})
    (let [params (last @requests)]
      (is (= 1 @schema-calls))
      (is (= "json_schema" (get-in params [:responseFormat :type])))
      (is (= "response" (get-in params [:responseFormat :jsonSchema :name])))
      (is (true? (get-in params [:responseFormat :jsonSchema :strict])))
      (is (= "integer"
             (get-in params
                     [:responseFormat :jsonSchema :schema
                      :properties :answer-value :type])))
      (is (not (contains?
                (get-in params [:responseFormat :jsonSchema :schema :properties])
                :answerValue))
          "caller-defined JSON Schema property names must not be camel-cased"))))

(deftest response-schema-option-omission-and-nil-contract
  (let [requests (atom [])
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (swap! requests conj params))))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})]
    (sdk/send! copilot-session {:prompt "No schema"})
    (is (not (contains? (last @requests) :responseFormat)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid send options"
         (sdk/send! copilot-session
                    {:prompt "Invalid schema"
                     :response-schema nil})))
    (is (= 1 (count @requests)))))

(deftest typed-send-and-wait-parses-correlated-root-response
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        sent-opts (atom nil)
        parsed-value (atom nil)
        schema
        (parsed-response-schema
         (fn [value]
           (reset! parsed-value value)
           (get value "answer")))
        result
        (with-redefs
         [session/send-with-timeout!
          (fn [_ opts _]
            (reset! sent-opts opts)
            (session/dispatch-event!
             client session-id
             {:type :copilot/assistant.message
              :data {:originating-message-id "other"
                     :content "{\"answer\":0}"}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/session.idle :data {}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/user.message
              :data {:message-id "request-1" :content "Question"}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/assistant.message
              :data {:originating-message-id "request-1"
                     :tool-requests [{:tool-call-id "tool-1"
                                      :name "lookup"}]
                     :content "{\"answer\":1}"}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/assistant.message
              :agent-id "subagent-1"
              :data {:originating-message-id "request-1"
                     :content "{\"answer\":2}"}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/assistant.message
              :data {:originating-message-id "request-1"
                     :content "{\"answer\":4}"}})
            (session/dispatch-event!
             client session-id
             {:type :copilot/session.idle :data {}})
            "request-1")]
          (sdk/send-and-wait!
           copilot-session
           {:prompt "What is 2+2?"}
           schema
           5000))]
    (is (= 4 result))
    (is (= {"answer" 4} @parsed-value))
    (is (= schema (:response-schema @sent-opts)))))

(deftest raw-response-schema-returns-correlated-assistant-event-without-user-event
  (doseq [agent-id [nil ""]]
    (let [copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id copilot-session)
          client (:client copilot-session)
          result
          (with-redefs
           [session/send-with-timeout!
            (fn [_ _ _]
              (session/dispatch-event!
               client session-id
               (cond-> {:type :copilot/assistant.message
                        :data {:originating-message-id "request-1"
                               :content "{\"answer-value\":4}"}}
                 (some? agent-id) (assoc :agent-id agent-id)))
              (session/dispatch-event!
               client session-id
               {:type :copilot/session.idle :data {}})
              "request-1")]
            (sdk/send-and-wait!
             copilot-session
             {:prompt "What is 2+2?"
              :response-schema response-json-schema}
             5000))]
      (is (= :copilot/assistant.message (:type result)))
      (is (= "{\"answer-value\":4}" (get-in result [:data :content]))))))

(deftest typed-send-and-wait-rejects-invalid-combinations
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        schema (parsed-response-schema identity)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Do not specify :response-schema in opts"
         (sdk/send-and-wait!
          copilot-session
          {:prompt "Conflict"
           :response-schema response-json-schema}
          schema)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Structured output cannot be requested on an immediate steering message"
         (sdk/send-and-wait!
          copilot-session
          {:prompt "Immediate" :mode :immediate}
          schema)))))

(deftest typed-send-and-wait-rejects-invalid-json-schema-output
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        schema {:to-json-schema (constantly [])
                :parse identity}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Response schema must resolve to a JSON object"
         (sdk/send! copilot-session
                    {:prompt "Return JSON"
                     :response-schema schema})))))

(deftest typed-send-and-wait-requires-final-structured-message
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ _]
        (session/dispatch-event!
         client session-id
         {:type :copilot/user.message
          :data {:message-id "request-1" :content "Question"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle :data {}})
        "request-1")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"completed without a structured assistant response"
           (sdk/send-and-wait!
            copilot-session
            {:prompt "Return JSON"}
            schema
            5000))))))

(deftest typed-send-and-wait-surfaces-session-error-before-correlation
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ _]
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.error
          :data {:message "response schema rejected"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle :data {}})
        "request-1")]
      (let [pending
            (future
              (try
                (sdk/send-and-wait!
                 copilot-session
                 {:prompt "Return JSON"}
                 schema
                 nil)
                :completed
                (catch Exception e
                  [:threw (ex-message e)])))]
        (try
          (is (= [:threw "response schema rejected"]
                 (deref pending 1000 ::timeout)))
          (finally
            (future-cancel pending)))))))

(deftest typed-send-and-wait-reports-invalid-json-with-context
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ _]
        (session/dispatch-event!
         client session-id
         {:type :copilot/user.message
          :data {:message-id "request-1" :content "Question"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/assistant.message
          :data {:originating-message-id "request-1"
                 :content "not JSON"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle :data {}})
        "request-1")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Failed to parse the structured assistant response as JSON"
           (sdk/send-and-wait!
            copilot-session
            {:prompt "Return JSON"}
            schema
            5000))))))

(deftest typed-send-and-wait-surfaces-aborted-run
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ _]
        (session/dispatch-event!
         client session-id
         {:type :copilot/user.message
          :data {:message-id "request-1" :content "Question"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle
          :data {:aborted true}})
        "request-1")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"aborted before a structured result"
           (sdk/send-and-wait!
            copilot-session
            {:prompt "Return JSON"}
            schema
            5000))))))

(deftest concurrent-typed-sends-correlate-their-own-results
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        started (CountDownLatch. 2)
        schema (parsed-response-schema #(get % "answer"))
        send-id (fn [opts]
                  (case (:prompt opts)
                    "first" "request-1"
                    "second" "request-2"))
        results
        (with-redefs
         [session/send-with-timeout!
          (fn [_ opts _]
            (.countDown started)
            (is (.await started 2 TimeUnit/SECONDS)
                "both structured sends should be admitted concurrently")
            (send-id opts))]
          (let [first-result
                (future
                  (sdk/send-and-wait!
                   copilot-session {:prompt "first"} schema 5000))
                second-result
                (future
                  (sdk/send-and-wait!
                   copilot-session {:prompt "second"} schema 5000))]
            (is (.await started 2 TimeUnit/SECONDS))
            (doseq [[message-id answer]
                    [["request-1" 1] ["request-2" 2]]]
              (session/dispatch-event!
               client session-id
               {:type :copilot/user.message
                :data {:message-id message-id :content "Question"}})
              (session/dispatch-event!
               client session-id
               {:type :copilot/assistant.message
                :data {:originating-message-id message-id
                       :content (str "{\"answer\":" answer "}")}}))
            (session/dispatch-event!
             client session-id
             {:type :copilot/session.idle :data {}})
            [@first-result @second-result]))]
    (is (= [1 2] results))))

(deftest structured-wait-does-not-overlap-an-ordinary-wait
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        ordinary-entered (promise)
        release-ordinary (promise)
        structured-entered (promise)
        schema (parsed-response-schema #(get % "answer"))]
    (with-redefs
     [session/send!
      (fn [_ _]
        (deliver ordinary-entered true)
        @release-ordinary
        "ordinary-1")
      session/send-with-timeout!
      (fn [_ _ _]
        (deliver structured-entered true)
        "structured-1")]
      (let [ordinary
            (future
              (sdk/send-and-wait!
               copilot-session {:prompt "ordinary"} 5000))]
        (is (true? (deref ordinary-entered 1000 ::timeout)))
        (let [structured
              (future
                (sdk/send-and-wait!
                 copilot-session {:prompt "structured"} schema 5000))]
          (is (= ::timeout (deref structured-entered 100 ::timeout))
              "structured admission must wait while an ordinary wait owns the session")
          (deliver release-ordinary true)
          (session/dispatch-event!
           client session-id
           {:type :copilot/assistant.message
            :data {:content "ordinary result"}})
          (session/dispatch-event!
           client session-id
           {:type :copilot/session.idle :data {}})
          (is (= "ordinary result"
                 (get-in (deref ordinary 1000 ::timeout) [:data :content])))
          (is (true? (deref structured-entered 1000 ::timeout)))
          (session/dispatch-event!
           client session-id
           {:type :copilot/user.message
            :data {:message-id "structured-1" :content "Question"}})
          (session/dispatch-event!
           client session-id
           {:type :copilot/assistant.message
            :data {:originating-message-id "structured-1"
                   :content "{\"answer\":4}"}})
          (session/dispatch-event!
           client session-id
           {:type :copilot/session.idle :data {}})
          (is (= 4 (deref structured 1000 ::timeout))))))))

(deftest structured-timeout-bounds-send-admission
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        observed-timeout (atom nil)
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ timeout-ms]
        (reset! observed-timeout timeout-ms)
        (throw
         (ex-info "Request timeout"
                  {:method "session.send" :timeout-ms timeout-ms})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Timeout after 250ms waiting for the structured response"
           (sdk/send-and-wait!
            copilot-session
            {:prompt "Return JSON"}
            schema
            250))))
    (is (pos-int? @observed-timeout))
    (is (<= @observed-timeout 250))))

(deftest structured-timeout-bounds-lock-admission
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        send-lock
        (get-in @(:state (:client copilot-session))
                [:session-io session-id :send-lock])
        schema (parsed-response-schema identity)]
    (is (= :token (async/<!! send-lock)))
    (let [result
          (future
            (try
              (sdk/send-and-wait!
               copilot-session
               {:prompt "Return JSON"}
               schema
               50)
              (catch clojure.lang.ExceptionInfo e
                e)))]
      (try
        (let [outcome (deref result 500 ::blocked)]
          (is (instance? clojure.lang.ExceptionInfo outcome))
          (when (instance? clojure.lang.ExceptionInfo outcome)
            (is (re-find
                 #"Timeout after 50ms waiting for the structured response"
                 (ex-message outcome)))))
        (finally
          (async/>!! send-lock :token)
          (deref result 1000 nil))))))

(deftest parsed-three-arity-honors-options-timeout
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        observed-timeout (atom nil)
        schema (parsed-response-schema #(get % "answer"))]
    (with-redefs
     [session/send-with-timeout!
      (fn [_ _ timeout-ms]
        (reset! observed-timeout timeout-ms)
        (session/dispatch-event!
         client session-id
         {:type :copilot/user.message
          :data {:message-id "request-1" :content "Question"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/assistant.message
          :data {:originating-message-id "request-1"
                 :content "{\"answer\":4}"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle :data {}})
        "request-1")]
      (is (= 4
             (sdk/send-and-wait!
              copilot-session
              {:prompt "Return JSON"
               :timeout-ms 250}
              schema)))
      (is (pos-int? @observed-timeout))
      (is (<= @observed-timeout 250)))))

(deftest parsed-three-arity-preserves-unbounded-timeout
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        client (:client copilot-session)
        observed-timeout (atom ::not-called)
        schema (parsed-response-schema #(get % "answer"))]
    (with-redefs
     [protocol/send-request!
      (fn [_ method _ timeout-ms]
        (is (= "session.send" method))
        (reset! observed-timeout timeout-ms)
        (session/dispatch-event!
         client session-id
         {:type :copilot/user.message
          :data {:message-id "request-1" :content "Question"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/assistant.message
          :data {:originating-message-id "request-1"
                 :content "{\"answer\":4}"}})
        (session/dispatch-event!
         client session-id
         {:type :copilot/session.idle :data {}})
        {:message-id "request-1"})]
      (is (= 4
             (sdk/send-and-wait!
              copilot-session
              {:prompt "Return JSON"
               :timeout-ms nil}
              schema)))
      (is (nil? @observed-timeout)))))

(deftest structured-timeout-bounds-event-wait
  (let [copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all})
        schema (parsed-response-schema identity)]
    (with-redefs
     [session/send-with-timeout! (fn [_ _ _] "request-1")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Timeout after 50ms waiting for the structured response"
           (sdk/send-and-wait!
            copilot-session
            {:prompt "Return JSON"}
            schema
            50))))))

(deftest extension-context-attachment-contract
  (let [attachment-spec
        (s/get-spec ::specs/extension-context-attachment)
        attachment
        {:type :extension-context
         :extension-id "example.extension"
         :canvas-id "example-canvas"
         :instance-id "canvas-1"
         :title "Selected dashboard"
         :payload {:account-id 42
                   :nested {:userName "octocat"}}
         :captured-at "2026-09-20T18:00:00Z"}]
    (is (some? attachment-spec))
    (when attachment-spec
      (is (s/valid? ::specs/extension-context-attachment attachment))
      (is (s/valid? ::specs/inbound-attachment attachment))
      (is (not
           (s/valid? ::specs/extension-context-attachment
                     {:type :extension-context
                      :title "Missing runtime identity"
                      :payload nil}))))
    (let [requests (atom [])
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (when (= "session.send" method)
                 (swap! requests conj params))))
          copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all})]
      (sdk/send! copilot-session
                 {:prompt "Use the selected dashboard"
                  :attachments [attachment]})
      (let [wire (-> @requests last :attachments first)]
        (is (= "extension_context" (:type wire)))
        (is (= "example.extension" (:extensionId wire)))
        (is (= "example-canvas" (:canvasId wire)))
        (is (= "canvas-1" (:instanceId wire)))
        (is (= "2026-09-20T18:00:00Z" (:capturedAt wire)))
        (is (= 42 (get-in wire [:payload :account-id])))
        (is (= "octocat" (get-in wire [:payload :nested :userName])))
        (is (not (contains? (:payload wire) :accountId))
            "opaque payload keys must not be camel-cased")))))
