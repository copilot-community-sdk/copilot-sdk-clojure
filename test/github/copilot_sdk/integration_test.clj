(ns github.copilot-sdk.integration-test
  "Integration tests using mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs]
            [github.copilot-sdk.mock-server :as mock]))

;; Fixture to manage mock server lifecycle
(def ^:dynamic *mock-server* nil)
(def ^:dynamic *test-client* nil)

(defn with-mock-server
  "Fixture that creates a mock server and client for each test."
  [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)]
    ;; Connect client to mock server
    (client/connect-with-streams! client in out)
    (binding [*mock-server* server
              *test-client* client]
      (try
        (test-fn)
        (finally
          ;; Stop client first to suppress auto-restart during teardown
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(use-fixtures :each with-mock-server)

;; -----------------------------------------------------------------------------
;; Client Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-client-connection
  (testing "Client connects to mock server"
    (is (= :connected (sdk/state *test-client*)))
    (is (some? (:connection @(:state *test-client*))))))

(deftest test-auto-restart-deprecated-connection-close
  (testing "auto-restart no longer triggers on connection close (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)]
      (log/info "Warnings expected in this test: connection close no longer triggers auto-restart.")
      (with-redefs [client/stop! (fn [c]
                                   (swap! stops inc)
                                   (swap! (:state c) assoc :status :disconnected)
                                   [])
                    client/start! (fn [c]
                                    (swap! starts inc)
                                    (swap! (:state c) assoc :status :connected)
                                    nil)]
        (mock/stop-mock-server! *mock-server*)
        (Thread/sleep 200)
        (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
        (is (zero? @starts) "auto-restart is deprecated; start! should not be called")))))

(deftest test-auto-restart-deprecated-process-exit
  (testing "auto-restart no longer triggers on process exit (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)
          exit-ch (chan 1)
          watch-exit (var client/watch-process-exit!)]
      (log/info "Warnings expected in this test: simulated process exit no longer triggers auto-restart.")
      (with-redefs [client/stop! (fn [c]
                                   (swap! stops inc)
                                   (swap! (:state c) assoc :status :disconnected)
                                   [])
                    client/start! (fn [c]
                                    (swap! starts inc)
                                    (swap! (:state c) assoc :status :connected)
                                    nil)]
        (watch-exit *test-client* {:exit-chan exit-ch})
        (>!! exit-ch {:exit-code 123})
        (close! exit-ch)
        (Thread/sleep 200)
        (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
        (is (zero? @starts) "auto-restart is deprecated; start! should not be called")))))

(deftest test-auto-restart-suppressed-when-stopping
  (testing "auto-restart is suppressed while stopping"
    (let [starts (atom 0)
          stops (atom 0)]
      (swap! (:state *test-client*) assoc :stopping? true)
      (try
        (with-redefs [client/stop! (fn [_] (swap! stops inc) [])
                      client/start! (fn [_] (swap! starts inc) nil)]
          (mock/stop-mock-server! *mock-server*)
          (Thread/sleep 200)
          (is (zero? @stops))
          (is (zero? @starts)))
        (finally
          (swap! (:state *test-client*) assoc :stopping? false))))))

;; -----------------------------------------------------------------------------
;; Stderr Capture Tests (upstream PR #492)
;; -----------------------------------------------------------------------------

(deftest test-stderr-capture-and-forwarding
  (testing "start-stderr-forwarder! captures stderr lines"
    (let [stderr-content "error line 1\nerror line 2\nwarning: something\n"
          stderr-stream (java.io.ByteArrayInputStream.
                         (.getBytes stderr-content "UTF-8"))
          exit-ch (chan 1)
          fake-mp (github.copilot-sdk.process/map->ManagedProcess
                   {:process nil :stdin nil :stdout nil
                    :stderr stderr-stream :exit-chan exit-ch})
          start-forwarder (var client/start-stderr-forwarder!)
          get-stderr (var client/get-stderr-output)
          client (sdk/client {:auto-start? false})]
      (start-forwarder client fake-mp)
      ;; Give the go-loop time to drain stderr
      (Thread/sleep 200)
      (let [output (get-stderr client)]
        (is (some? output) "stderr output should be captured")
        (is (clojure.string/includes? output "error line 1"))
        (is (clojure.string/includes? output "error line 2"))
        (is (clojure.string/includes? output "warning: something")))
      ;; Verify buffer atom contains individual lines
      (let [buf @(:stderr-buffer @(:state client))]
        (is (= 3 (count buf)))
        (is (= "error line 1" (first buf))))))

  (testing "get-stderr-output returns nil when no stderr captured"
    (let [client (sdk/client {:auto-start? false})
          get-stderr (var client/get-stderr-output)]
      (is (nil? (get-stderr client))))))

(deftest test-early-process-exit-detected-during-startup
  (testing "verify-protocol-version! detects early process exit with stderr"
    (let [exit-ch (chan 1)
          ;; Inject a fake process and pre-populated stderr buffer
          _ (swap! (:state *test-client*) assoc
                   :process {:exit-chan exit-ch}
                   :stderr-buffer (atom ["fatal: config file not found"
                                         "copilot: exiting"]))
          ;; Signal process exit before the ping can complete
          _ (>!! exit-ch {:exit-code 1})
          _ (close! exit-ch)
          verify-version (var client/verify-protocol-version!)]
      (try
        (verify-version *test-client*)
        (is false "Should have thrown on early process exit")
        (catch clojure.lang.ExceptionInfo e
          (is (clojure.string/includes? (ex-message e) "CLI server exited with code 1"))
          (is (clojure.string/includes? (ex-message e) "fatal: config file not found"))
          (is (= 1 (:exit-code (ex-data e))))
          (is (some? (:stderr (ex-data e)))))))))

(deftest test-ping
  (testing "Ping returns protocol version"
    (let [result (sdk/ping *test-client*)]
      (is (= 2 (:protocol-version result)))
      (is (number? (:timestamp result))))))

(deftest test-get-status
  (testing "Get CLI status returns version and protocol"
    (let [result (sdk/get-status *test-client*)]
      (is (string? (:version result)))
      (is (= 2 (:protocol-version result))))))

(deftest test-get-auth-status
  (testing "Get auth status returns authentication info"
    (let [result (sdk/get-auth-status *test-client*)]
      (is (boolean? (:authenticated? result)))
      (when (:authenticated? result)
        (is (keyword? (:auth-type result)))
        (is (string? (:login result)))))))

(deftest test-list-models
  (testing "List models returns available models"
    (let [models (sdk/list-models *test-client*)]
      (is (vector? models))
      (is (pos? (count models)))
      (let [model (first models)]
        (is (string? (:id model)))
        (is (string? (:name model)))
        (is (string? (:vendor model)))
        (is (number? (:max-input-tokens model)))
        (is (number? (:max-output-tokens model)))))))

(deftest test-list-models-with-on-list-models-handler
  (let [call-count (atom 0)
        fake-models [{:id "test-model" :name "Test Model" :vendor "test"
                      :family "test" :version "1" :max-input-tokens 4096
                      :max-output-tokens 1024 :preview? false}]
        handler (fn []
                  (swap! call-count inc)
                  fake-models)
        c (sdk/client {:auto-start? false :on-list-models handler})]
    (testing "returns handler result without requiring start!"
      (let [models (sdk/list-models c)]
        (is (vector? models))
        (is (= 1 (count models)))
        (is (= "test-model" (:id (first models))))))
    (testing "caches result (handler called only once)"
      (let [_m1 (sdk/list-models c)
            _m2 (sdk/list-models c)]
        (is (= 1 @call-count))))))

;; -----------------------------------------------------------------------------
;; Session Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-create-session
  (testing "Create new session"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "gpt-5.4"})]
      (is (some? session))
      (is (string? (sdk/session-id session)))
      ;; Session ID is now generated client-side as a UUID
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                      (sdk/session-id session)))))
  (testing "Create session with custom session-id"
    (let [custom-id "my-custom-session-id"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :session-id custom-id})]
      (is (= custom-id (sdk/session-id session)))))
  (testing "Create session with :on-event captures session.start"
    (let [events (atom [])
          got-start (promise)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-event (fn [evt]
                                                   (swap! events conj evt)
                                                   (when (= :copilot/session.start (:type evt))
                                                     (deliver got-start true)))})]
      (is (deref got-start 2000 false)
          "on-event handler should receive session.start event within timeout")
      (is (some #(= :copilot/session.start (:type %)) @events)))))

(deftest test-list-sessions
  (testing "List sessions includes created sessions"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sessions (sdk/list-sessions *test-client*)]
      (is (seq sessions))
      (is (some #(= (sdk/session-id session) (:session-id %)) sessions)))))

(deftest test-list-sessions-with-context
  (testing "List sessions returns context when present"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)]
      (mock/set-session-context! *mock-server* sid
                                 {:cwd "/home/user/project"
                                  :gitRoot "/home/user/project"
                                  :repository "owner/repo"
                                  :branch "main"})
      (let [sessions (sdk/list-sessions *test-client*)
            found (first (filter #(= sid (:session-id %)) sessions))]
        (is (some? found))
        (is (= "/home/user/project" (get-in found [:context :cwd])))
        (is (= "owner/repo" (get-in found [:context :repository])))
        (is (= "main" (get-in found [:context :branch])))))))

(deftest test-list-sessions-with-filter
  (testing "List sessions filter narrows results"
    (let [s1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          s2 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-session-context! *mock-server* (sdk/session-id s1)
                                 {:cwd "/project-a" :repository "org/repo-a"})
      (mock/set-session-context! *mock-server* (sdk/session-id s2)
                                 {:cwd "/project-b" :repository "org/repo-b"})
      (let [filtered (sdk/list-sessions *test-client* {:repository "org/repo-a"})]
        (is (= 1 (count filtered)))
        (is (= (sdk/session-id s1) (:session-id (first filtered))))))))

(deftest test-get-session-metadata
  (testing "Get metadata for an existing session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)
          metadata (sdk/get-session-metadata *test-client* sid)]
      (is (some? metadata))
      (is (= sid (:session-id metadata)))
      (is (instance? java.time.Instant (:start-time metadata)))
      (is (instance? java.time.Instant (:modified-time metadata)))
      (is (= false (:remote? metadata))))))

(deftest test-get-session-metadata-with-context
  (testing "Get session metadata includes context when present"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)]
      (mock/set-session-context! *mock-server* sid
                                 {:cwd "/home/user/project"
                                  :gitRoot "/home/user/project"
                                  :repository "owner/repo"
                                  :branch "main"})
      (let [metadata (sdk/get-session-metadata *test-client* sid)]
        (is (some? metadata))
        (is (= "/home/user/project" (get-in metadata [:context :cwd])))
        (is (= "owner/repo" (get-in metadata [:context :repository])))
        (is (= "main" (get-in metadata [:context :branch])))))))

(deftest test-get-session-metadata-not-found
  (testing "Get metadata for non-existent session returns nil"
    (is (nil? (sdk/get-session-metadata *test-client* "non-existent-session-id")))))

(deftest test-list-tools
  (testing "List tools returns tool info"
    (let [tools (sdk/list-tools *test-client*)]
      (is (seq tools))
      (is (every? #(and (:name %) (:description %)) tools))
      (is (some #(= "bash" (:name %)) tools))
      (is (some #(= "builtin/grep" (:namespaced-name %)) tools)))))

(deftest test-get-quota
  (testing "Get quota returns quota snapshots"
    (let [quotas (sdk/get-quota *test-client*)]
      (is (map? quotas))
      (is (contains? quotas "chat"))
      (let [chat (get quotas "chat")]
        (is (= 1000 (:entitlement-requests chat)))
        (is (= 42 (:used-requests chat)))
        (is (number? (:remaining-percentage chat)))
        (is (= false (:overage-allowed-with-exhausted-quota? chat)))))))

(deftest test-get-current-model
  (testing "Get current model for session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})]
      (is (= "gpt-5.4" (sdk/get-current-model session))))))

(deftest test-switch-model
  (testing "Switch model for session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          new-model (sdk/switch-model! session "claude-sonnet-4.5")]
      (is (= "claude-sonnet-4.5" new-model))
      (is (= "claude-sonnet-4.5" (sdk/get-current-model session))))))

(deftest test-log-message-only
  (testing "Log with message only returns event-id"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          event-id (sdk/log! session "Processing started")]
      (is (string? event-id))
      (is (seq event-id)))))

(deftest test-log-with-options
  (testing "Log with level and ephemeral options returns event-id"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          event-id (sdk/log! session "Something went wrong" {:level "error" :ephemeral? true})]
      (is (string? event-id))
      (is (seq event-id)))))

(deftest test-log-verifies-rpc-params
  (testing "Log sends correct RPC params"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.log")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/log! session "test message" {:level "warning" :ephemeral? true})]
      (is (= (:message @captured-params) "test message"))
      (is (= (:level @captured-params) "warning"))
      (is (= (:ephemeral @captured-params) true))
      (is (string? (:sessionId @captured-params))))))

(deftest test-delete-session
  (testing "Delete session removes it from list"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (sdk/delete-session! *test-client* session-id)
          sessions (sdk/list-sessions *test-client*)]
      (is (not (some #(= session-id (:session-id %)) sessions))))))

(deftest test-destroy-session
  (testing "Destroy session via session object"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (sdk/destroy! session)
      (let [sessions (sdk/list-sessions *test-client*)]
        (is (not (some #(= session-id (:session-id %)) sessions)))))))

;; -----------------------------------------------------------------------------
;; Message Sending Tests
;; -----------------------------------------------------------------------------

(deftest test-send-message
  (testing "Send message returns message ID"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          msg-id (sdk/send! session {:prompt "Hello world"})]
      (is (string? msg-id)))))

(deftest test-send-and-wait
  (testing "Send and wait receives events and returns assistant message"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          result (sdk/send-and-wait! session {:prompt "Test message"})]
      ;; Returns the last assistant message event (map)
      (is (map? result))
      (is (= :copilot/assistant.message (:type result)))
      (is (string? (get-in result [:data :content]))))))

(deftest test-send-and-wait-serializes
  (testing "send-and-wait serializes concurrent calls"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)
          send-calls (atom 0)]
      (with-redefs [session/send! (fn [_ _]
                                    (swap! send-calls inc)
                                    "msg")]
        (let [first-f (future (session/send-and-wait! session {:prompt "A"} 1000))
              second-f (future (session/send-and-wait! session {:prompt "B"} 1000))]
          (Thread/sleep 50)
          (is (= 1 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "first"}})
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
          (is (map? (deref first-f 1000 ::timeout)))
          (Thread/sleep 50)
          (is (= 2 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "second"}})
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
          (is (map? (deref second-f 1000 ::timeout))))))))

(deftest test-send-async
  (testing "Send async returns channel with events"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          event-ch (sdk/send-async session {:prompt "Async test"})
          events (atom [])]
      ;; Collect events
      (loop []
        (let [[v _] (alts!! [event-ch (timeout 5000)])]
          (when (some? v)
            (swap! events conj v)
            (recur))))
      ;; Should have received events
      (is (pos? (count @events)))
      ;; Should include idle event
      (is (some #(= :copilot/session.idle (:type %)) @events)))))

(deftest test-send-async-with-id
  (testing "send-async-with-id returns message-id and matching events"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          {:keys [message-id events-ch]} (sdk/send-async-with-id session {:prompt "Async with id"})]
      (is (string? message-id))
      (let [matched (loop [count 0]
                      (when (< count 30)
                        (let [[event _] (alts!! [events-ch (timeout 1000)])]
                          (cond
                            (nil? event) nil
                            (and (= :copilot/assistant.message (:type event))
                                 (= message-id (get-in event [:data :message-id])))
                            event
                            :else (recur (inc count))))))]
        (is (some? matched))))))

(deftest test-send-async-serializes
  (testing "send-async serializes concurrent calls"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)
          send-calls (atom 0)]
      ;; <send-async* uses proto/send-request directly
      (with-redefs [protocol/send-request (fn [_ _ _]
                                            (swap! send-calls inc)
                                            (let [ch (async/chan 1)]
                                              (async/put! ch {:result {:message-id "msg"}})
                                              (async/close! ch)
                                              ch))]
        (let [ch1 (session/send-async session {:prompt "A"})
              ch2-f (future (session/send-async session {:prompt "B"}))]
          (Thread/sleep 50)
          (is (= 1 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "first"}})
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
          (is (not= ::timeout (deref ch2-f 1000 ::timeout)))
          (Thread/sleep 50)
          (is (= 2 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "second"}})
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
          (loop []
            (let [[v _] (alts!! [ch1 (timeout 1000)])]
              (when (some? v)
                (recur)))))))))

(deftest test-<send!-returns-last-assistant-message
  (testing "<send! returns the last assistant.message content, not the first"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)]
      ;; Bypass actual send to control event flow
      (with-redefs [protocol/send-request (fn [_ _ _]
                                            (let [ch (async/chan 1)]
                                              (async/put! ch {:result {:message-id "msg-id"}})
                                              (async/close! ch)
                                              ch))]
        (let [result-ch (sdk/<send! session {:prompt "Test agentic flow"})]
          ;; Let the go block acquire lock, send RPC, and tap event-mult
          (Thread/sleep 50)
          ;; Simulate agentic flow: multiple assistant messages with tool calls between
          ;; First assistant.message (often empty in agentic flows)
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "" :message-id "msg-1"}})
          ;; Tool execution
          (session/dispatch-event! client session-id
                                   {:type :copilot/tool.execution_start
                                    :data {:tool-call-id "tc-1" :tool-name "view"}})
          (session/dispatch-event! client session-id
                                   {:type :copilot/tool.execution_complete
                                    :data {:tool-call-id "tc-1" :success true}})
          ;; Second assistant.message (intermediate, may also be empty)
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "Analyzing..." :message-id "msg-2"}})
          ;; More tool execution
          (session/dispatch-event! client session-id
                                   {:type :copilot/tool.execution_start
                                    :data {:tool-call-id "tc-2" :tool-name "grep"}})
          (session/dispatch-event! client session-id
                                   {:type :copilot/tool.execution_complete
                                    :data {:tool-call-id "tc-2" :success true}})
          ;; Final assistant.message with actual content
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "Here is the final answer with all the details." :message-id "msg-3"}})
          ;; Session idle
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})

          ;; Verify <send! returns the LAST message content, not the first empty one
          (let [[result _] (alts!! [result-ch (timeout 2000)])]
            (is (= "Here is the final answer with all the details." result)
                "<send! should return the last assistant.message content, not the first")))))))

;; -----------------------------------------------------------------------------
;; Session Operations Tests
;; -----------------------------------------------------------------------------

(deftest test-abort-session
  (testing "Abort session operation"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      ;; Should not throw
      (is (nil? (sdk/abort! session))))))

(deftest test-get-messages
  (testing "Get messages from session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send-and-wait! session {:prompt "Test"})
          messages (sdk/get-messages session)]
      ;; Mock server returns empty events vector
      (is (vector? messages)))))

(deftest test-get-messages-applies-coercion
  (testing "Historical session.start events have :start-time coerced to Instant"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (:session-id session)
          iso "2024-01-01T00:00:00Z"]
      ;; Seed a wire-shape session.start event into the mock server's history
      (mock/set-session-messages!
       *mock-server* session-id
       [{:type "session.start"
         :data {:sessionId session-id
                :version 1
                :producer "test"
                :copilotVersion "1.0.0"
                :startTime iso}}])
      (let [[msg] (sdk/get-messages session)]
        (is (= :copilot/session.start (:type msg))
            "type is normalized to a keyword")
        (is (instance? java.time.Instant (get-in msg [:data :start-time]))
            "wire ISO string is coerced to java.time.Instant")
        (is (= (java.time.Instant/parse iso)
               (get-in msg [:data :start-time]))
            "Instant value is preserved across coercion")))))

;; -----------------------------------------------------------------------------
;; Event Channel Tests
;; -----------------------------------------------------------------------------

(deftest test-event-subscription
  (testing "Can subscribe to session event stream"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          events-ch (sdk/subscribe-events session)]
      ;; Send a message to trigger events
      (sdk/send! session {:prompt "Event test"})
      ;; Wait for some events
      (Thread/sleep 200)
      ;; Should have received events via subscription
      (let [events (atom [])]
        (loop []
          (let [[v _] (alts!! [events-ch (timeout 100)])]
            (when (some? v)
              (swap! events conj v)
              (recur))))
        (is (pos? (count @events))))
      (sdk/unsubscribe-events session events-ch))))

(deftest test-non-session-notification-routed
  (testing "Non-session notifications are delivered to client notifications channel"
    (let [notif-ch (sdk/notifications *test-client*)
          payload {:status "ok" :version "1.2.3"}]
      (mock/send-notification! *mock-server* "cli.status" payload)
      (let [[notif _] (alts!! [notif-ch (timeout 1000)])]
        (is (some? notif))
        (is (= "cli.status" (:method notif)))
        (is (= payload (:params notif)))))))

(deftest test-dispatch-event-drops-when-full
  (testing "dispatch-event! drops events when buffer is full"
    (log/info "Warnings expected in this test: event buffer full triggers drop warning.")
    (let [session-id "session-test"
          small-ch (chan 1)
          client {:state (atom {:sessions {session-id {:destroyed? false}}
                                :session-io {session-id {:event-chan small-ch}}})}]
      (>!! small-ch {:type :dummy})
      (let [dispatch-future (future (session/dispatch-event! client session-id
                                                             {:type :copilot/session.idle}))
            dispatch-result (deref dispatch-future 50 ::timeout)]
        (is (not= ::timeout dispatch-result))
        (is (= :dummy (:type (<!! small-ch))))
        (is (nil? (async/poll! small-ch)))))))

(deftest test-protocol-notification-queue
  (testing "Protocol notifications queue without blocking reader thread"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (java.io.PipedInputStream.)
          _ (java.io.PipedOutputStream. in)
          out (java.io.ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)
          incoming (:incoming-ch conn)
          msg {:jsonrpc "2.0"
               :method "session.event"
               :params {:sessionId "s-1"
                        :event {:type :copilot/session.idle}}}]
      (try
        (dotimes [i 1024]
          (>!! incoming {:i i}))
        (let [dispatch (future (#'protocol/dispatch-message! conn msg))]
          (Thread/sleep 50)
          (is (true? (realized? dispatch)))
          (<!! incoming)
          (let [seen (loop []
                       (when-let [v (<!! incoming)]
                         (if (= "session.event" (:method v))
                           v
                           (recur))))]
            (is (= "session.event" (:method seen)))))
        (finally
          (protocol/disconnect conn))))))

;; -----------------------------------------------------------------------------
;; Tool Handler Tests
;; -----------------------------------------------------------------------------

(deftest test-tool-registration
  (testing "Register tool handler"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [(sdk/define-tool "test_tool"
                                                 {:description "A test tool"
                                                  :parameters {:type "object"
                                                               :properties {"value" {:type "string"}}}
                                                  :handler (fn [_args _invocation] "result")})]})]
      (is (some? session)))))

(deftest test-tool-call-response-shape
  (testing "tool.call handler returns a nested result wrapper"
    (let [tool (sdk/define-tool "echo"
                 {:handler (fn [args _] args)})
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "tool.call" {:session-id (sdk/session-id session)
                                              :tool-call-id "tc-1"
                                              :tool-name "echo"
                                              :arguments {:x 1}}))]
      (is (map? response))
      (is (contains? response :result))
      (is (map? (:result response)))
      (is (contains? (:result response) :result))
      (is (map? (get-in response [:result :result])))
      (is (contains? (get-in response [:result :result]) :text-result-for-llm))
      (is (not (contains? (get-in response [:result :result]) :result))))))

(deftest test-tool-handler-runs-on-blocking-thread
  (testing "tool handler executes on a blocking thread"
    (let [thread-name (atom nil)
          tool (sdk/define-tool "thread_check"
                 {:handler (fn [_ _]
                             (reset! thread-name (.getName (Thread/currentThread)))
                             "ok")})
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          handler (get-in @(:state *test-client*) [:connection :request-handler])]
      (<!! (handler "tool.call" {:session-id (sdk/session-id session)
                                 :tool-call-id "tc-2"
                                 :tool-name "thread_check"
                                 :arguments {}}))
      (is (string? @thread-name))
      (is (re-find #"async-(thread|mixed)" @thread-name))
      (is (not (clojure.string/starts-with? @thread-name "async-dispatch"))))))

(deftest test-session-config-wire-keys
  (testing "session config maps are converted to wire keys"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://example.test"
                                            :api-key "key"}
                                 :mcp-servers {"srv-1" {:mcp-server-type :http
                                                        :mcp-url "https://mcp.test"
                                                        :mcp-tools ["*"]
                                                        :mcp-timeout 1000}}
                                 :custom-agents [{:agent-name "agent-1"
                                                  :agent-prompt "You are agent 1"
                                                  :agent-display-name "Agent One"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://resume.test"}
                                 :mcp-servers {"srv-2" {:mcp-server-type :sse
                                                        :mcp-url "https://mcp.resume.test"
                                                        :mcp-tools ["*"]}}
                                 :custom-agents [{:agent-name "agent-2"
                                                  :agent-prompt "You are agent 2"}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= "https://example.test" (get-in create-params [:provider :baseUrl])))
      (is (= "key" (get-in create-params [:provider :apiKey])))
      ;; MCP server keys: :mcp-* prefix is stripped on wire (upstream uses bare names)
      (is (= "http" (get-in create-params [:mcpServers :srv-1 :type])))
      (is (= "https://mcp.test" (get-in create-params [:mcpServers :srv-1 :url])))
      (is (= ["*"] (get-in create-params [:mcpServers :srv-1 :tools])))
      (is (= 1000 (get-in create-params [:mcpServers :srv-1 :timeout])))
      (is (= "agent-1" (get-in create-params [:customAgents 0 :agentName])))
      (is (= "Agent One" (get-in create-params [:customAgents 0 :agentDisplayName])))
      (is (= "https://resume.test" (get-in resume-params [:provider :baseUrl])))
      (is (= "sse" (get-in resume-params [:mcpServers :srv-2 :type])))
      (is (= "https://mcp.resume.test" (get-in resume-params [:mcpServers :srv-2 :url])))
      (is (= ["*"] (get-in resume-params [:mcpServers :srv-2 :tools])))
      (is (= "agent-2" (get-in resume-params [:customAgents 0 :agentName])))
      ;; envValueMode is always sent as "direct" (upstream PR #484)
      (is (= "direct" (:envValueMode create-params)))
      (is (= "direct" (:envValueMode resume-params))))))

(deftest test-instruction-directories-forwarded-on-wire
  (testing ":instruction-directories is forwarded to both session.create and session.resume (upstream PR #1190)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          dirs ["/tmp/instructions/a" "/tmp/instructions/b"]
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :instruction-directories dirs})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :instruction-directories dirs})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= dirs (:instructionDirectories create-params)))
      (is (= dirs (:instructionDirectories resume-params))))))

(deftest test-continue-pending-work-forwarded-on-resume
  (testing ":continue-pending-work? is forwarded as continuePendingWork on session.resume (upstream PR — types.ts:1458)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :continue-pending-work? true})
          resume-params (get @seen "session.resume")]
      (is (= true (:continuePendingWork resume-params))
          "continuePendingWork should appear on the wire when option is set"))))

;; -----------------------------------------------------------------------------
;; ProviderConfig override fields (upstream PR #966)
;; -----------------------------------------------------------------------------

(deftest test-provider-config-overrides-forwarded-on-wire
  (testing "ProviderConfig override fields are forwarded with correct wire keys (upstream PR #966)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          provider {:base-url "https://example.test"
                    :api-key "key"
                    :model-id "gpt-5"
                    :wire-model "gpt-5-2026"
                    :max-input-tokens 100000
                    :max-output-tokens 4096}
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          create-provider (get-in @seen ["session.create" :provider])
          resume-provider (get-in @seen ["session.resume" :provider])]
      (testing "create"
        (is (= "gpt-5" (:modelId create-provider)))
        (is (= "gpt-5-2026" (:wireModel create-provider)))
        (is (= 100000 (:maxPromptTokens create-provider))
            "SDK :max-input-tokens must serialize as wire `maxPromptTokens` (matches upstream toWireProviderConfig)")
        (is (= 4096 (:maxOutputTokens create-provider)))
        (is (not (contains? create-provider :maxInputTokens))
            "the SDK-side key `maxInputTokens` must NOT leak onto the wire"))
      (testing "resume"
        (is (= "gpt-5" (:modelId resume-provider)))
        (is (= "gpt-5-2026" (:wireModel resume-provider)))
        (is (= 100000 (:maxPromptTokens resume-provider)))
        (is (= 4096 (:maxOutputTokens resume-provider)))
        (is (not (contains? resume-provider :maxInputTokens)))))))

(deftest test-provider-config-without-overrides-passes-through
  (testing "ProviderConfig without override fields is forwarded unchanged"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= method "session.create")
                                                      (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://example.test"
                                            :api-key "key"}})
          create-provider (get-in @seen [:provider])]
      (is (= "https://example.test" (:baseUrl create-provider)))
      (is (= "key" (:apiKey create-provider)))
      (is (not (contains? create-provider :maxPromptTokens)))
      (is (not (contains? create-provider :maxInputTokens))))))

;; -----------------------------------------------------------------------------
;; Schedule events (upstream schema 1.0.42 — adds session.schedule_created and
;; session.schedule_cancelled to the event types known by the SDK)
;; -----------------------------------------------------------------------------

(deftest test-schedule-events-in-public-event-types
  (testing "schedule events are part of the public ::sdk/event-types set (upstream schema 1.0.42)"
    (is (contains? sdk/event-types :copilot/session.schedule_created))
    (is (contains? sdk/event-types :copilot/session.schedule_cancelled)))
  (testing "schedule events are also categorized under session-events"
    (is (contains? sdk/session-events :copilot/session.schedule_created))
    (is (contains? sdk/session-events :copilot/session.schedule_cancelled)))
  (testing "schedule events are accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.schedule_created))
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.schedule_cancelled)))
  (testing "schedule data idiom specs validate well-formed payloads (integer :id)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 42 :interval-ms 1000 :prompt "hi"}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id "uuid" :interval-ms 1000 :prompt "hi"}))
        "schedule data must reject non-integer :id (vs the UUID-string ::id used elsewhere)")
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_cancelled-data {:id 42}))))

(deftest test-custom-notification-event-type
  (testing "session.custom_notification is part of the public ::sdk/event-types set (upstream PR #1292)"
    (is (contains? sdk/event-types :copilot/session.custom_notification)))
  (testing "session.custom_notification is categorized under session-events"
    (is (contains? sdk/session-events :copilot/session.custom_notification)))
  (testing "session.custom_notification is accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.custom_notification)))
  (testing "::session.custom_notification-data idiom spec accepts a well-formed payload"
    (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                  {:source "my-extension"
                   :name "doc.opened"
                   :payload {:path "/tmp/x"}
                   :subject {:doc "foo"}
                   :version 1}))
    (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                  {:source "my-extension"
                   :name "ping"
                   :payload "scalar-ok"})
        "payload may be a scalar")
    (is (not (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                       {:name "doc.opened" :payload {}}))
        "missing :source must reject"))
  (testing "subject and payload keys are preserved verbatim (not kebab-cased) by normalize-incoming"
    ;; Source-defined identifiers (subject) and opaque JSON (payload) must
    ;; survive normalize-incoming without key transformation, matching the
    ;; existing escape hatch for external_tool.requested arguments.
    (let [normalize @#'github.copilot-sdk.protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "session.custom_notification"
                                    :data {:source "my-ext"
                                           :name "doc.opened"
                                           :subject {:GitHub-Login "octocat"
                                                     :actor.id "123"}
                                           :payload {:firstName "Foo"
                                                     :nested {:userId 42}}}}}}
          normalized (normalize raw-msg)
          data (get-in normalized [:params :event :data])]
      (is (= "session.custom_notification" (get-in normalized [:params :event :type])))
      (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data data))
      (is (contains? (:subject data) :GitHub-Login)
          "subject must preserve original casing, not collapse to :git-hub-login")
      (is (= "octocat" (get-in data [:subject :GitHub-Login])))
      (is (= "123" (get-in data [:subject :actor.id]))
          "subject must preserve dotted keys")
      (is (= 42 (get-in data [:payload :nested :userId]))
          "payload nested keys must not be kebab-cased")
      (is (= "Foo" (get-in data [:payload :firstName]))
          "payload top-level keys must not be kebab-cased")))
  (testing "subject and payload keys are preserved in historical events from session.getMessages responses"
    ;; Response messages (id, no :method) carrying :result :events collections
    ;; must apply the same preservation rules per-event, so live and
    ;; historical custom_notification events have the same shape.
    (let [normalize @#'github.copilot-sdk.protocol/normalize-incoming
          raw-response {:jsonrpc "2.0"
                        :id 42
                        :result {:events [{:type "session.start"
                                           :data {:sessionId "s1"}}
                                          {:type "session.custom_notification"
                                           :data {:source "ext"
                                                  :name "x"
                                                  :subject {:GitHub-Login "octocat"}
                                                  :payload {:nestedKey {:userId 7}}}}
                                          {:type "external_tool.requested"
                                           :data {:id "t1"
                                                  :name "do"
                                                  :arguments {:OriginalKey "v"}}}]}}
          normalized (normalize raw-response)
          events (get-in normalized [:result :events])
          custom (nth events 1)
          ext-tool (nth events 2)]
      (is (= "octocat" (get-in custom [:data :subject :GitHub-Login]))
          "historical custom_notification subject keys must be preserved")
      (is (= 7 (get-in custom [:data :payload :nestedKey :userId]))
          "historical custom_notification payload keys must be preserved")
      (is (= {:OriginalKey "v"} (get-in ext-tool [:data :arguments]))
          "historical external_tool.requested arguments must be preserved")))
  (testing "remote-enable opts are validated synchronously when provided"
    (let [session {:session-id "s" :client {}}]
      (is (thrown? Exception (github.copilot-sdk.session/remote-enable session {:mode :bogus})))
      (is (thrown? Exception (github.copilot-sdk.session/remote-enable session {:mode "on"}))
          "string :mode value is rejected — the spec requires a keyword from #{:off :export :on}"))))

(deftest test-custom-agent-info-tools-nilable
  (testing "::custom-agent-info accepts :tools nil (upstream schema 1.0.41-1: tools: string[] | null)"
    (let [agent-with-nil-tools {:id "a"
                                :name "agent"
                                :display-name "Agent"
                                :description "test"
                                :source "user"
                                :user-invocable? true
                                :tools nil}
          agent-with-vec-tools (assoc agent-with-nil-tools :tools ["read" "write"])]
      (is (s/valid? :github.copilot-sdk.specs/custom-agent-info agent-with-nil-tools)
          "tools=nil must be accepted")
      (is (s/valid? :github.copilot-sdk.specs/custom-agent-info agent-with-vec-tools)
          "tools=vector still accepted")
      (is (not (s/valid? :github.copilot-sdk.specs/custom-agent-info
                         (assoc agent-with-nil-tools :tools "not-a-vec")))
          "tools must still reject non-nil non-collection values"))))

;; -----------------------------------------------------------------------------

(deftest test-connect-rpc-used-for-handshake
  (testing "verify-protocol-version! sends `connect` and forwards token"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj [method params]))))
          ;; The fixture client is already connected — just exercise verify
          ;; with a fresh client option that pre-loaded a token via state.
          _ (swap! (:state *test-client*) assoc-in [:options :tcp-connection-token] "tok-123")
          _ (reset! (:expected-token *mock-server*) "tok-123")
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)
          calls @seen]
      (is (some (fn [[m p]] (and (= m "connect") (= "tok-123" (:token p)))) calls)
          "connect should be called with the token")
      (is (not-any? (fn [[m _]] (= m "ping")) calls)
          "ping should NOT be called when connect succeeds"))))

(deftest test-connect-falls-back-to-ping-on-method-not-found
  (testing "legacy server without `connect` falls back to `ping`"
    (let [seen (atom [])
          _ (reset! (:supports-connect? *mock-server*) false)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj [method params]))))
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)
          methods (map first @seen)]
      (is (some #{"connect"} methods) "connect should be tried first")
      (is (some #{"ping"} methods) "ping should be the fallback")
      (is (= "connect" (first methods))
          "connect should precede ping"))))

(deftest test-connect-falls-back-to-ping-on-unhandled-method-message
  (testing "legacy server returning non-MethodNotFound code but \"Unhandled method connect\" message also falls back to ping (upstream parity)"
    (let [seen (atom [])
          _ (reset! (:supports-connect? *mock-server*) :legacy-message)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj method))))
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)]
      (is (= ["connect" "ping"] @seen)
          "ping must be the fallback when error message is exactly \"Unhandled method connect\""))))

(deftest test-connect-non-method-not-found-error-propagates
  (testing "non-MethodNotFound errors from `connect` are NOT swallowed by ping fallback"
    ;; Token validation is enforced — a wrong token returns -32603 (not -32601),
    ;; so the SDK must propagate the error rather than silently fall back to ping.
    (let [seen (atom [])
          _ (reset! (:expected-token *mock-server*) "correct-token")
          _ (swap! (:state *test-client*) assoc-in [:options :tcp-connection-token] "wrong-token")
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj method))))
          verify-version (var client/verify-protocol-version!)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid connection token"
           (verify-version *test-client*))
          "non-MethodNotFound error should propagate")
      (is (= ["connect"] @seen)
          "ping fallback must NOT be triggered for non-MethodNotFound errors"))))

(deftest test-tcp-connection-token-rejected-with-use-stdio
  (testing ":tcp-connection-token cannot be combined with :use-stdio? true"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"tcp-connection-token cannot be used with use-stdio"
         (sdk/client {:tcp-connection-token "tok"
                      :use-stdio? true
                      :auto-start? false})))))

(deftest test-tcp-connection-token-auto-generated-for-tcp-spawn
  (testing "SDK auto-generates a UUID token when spawning CLI in TCP mode"
    (let [c (sdk/client {:use-stdio? false
                         :auto-start? false})
          token (get-in c [:options :tcp-connection-token])]
      (is (string? token) "auto-generated token should be a non-blank string")
      (is (re-matches
           #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
           token)
          "auto-generated token should be a UUID")))
  (testing "explicit token wins over auto-generation"
    (let [c (sdk/client {:use-stdio? false
                         :tcp-connection-token "explicit-token"
                         :auto-start? false})]
      (is (= "explicit-token" (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated for stdio mode"
    (let [c (sdk/client {:use-stdio? true :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated for cli-url (external server)"
    (let [c (sdk/client {:cli-url "localhost:9999" :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated when running as a child process"
    (let [c (sdk/client {:is-child-process? true :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token]))))))

(deftest test-client-name-forwarded-on-wire
  (testing "clientName is forwarded in session.create when set (upstream PR #510)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :client-name "my-app"})
          create-params (get @seen "session.create")]
      (is (= "my-app" (:clientName create-params)))))

  (testing "clientName is forwarded in session.resume when set (upstream PR #510)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all :client-name "my-app"})
          resume-params (get @seen "session.resume")]
      (is (= "my-app" (:clientName resume-params)))))

  (testing "clientName is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :clientName))))))

(deftest test-per-session-github-token-forwarded-on-wire
  (testing "githubToken is forwarded in session.create when set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :github-token "session-token-create"})
          create-params (get @seen "session.create")]
      (is (= "session-token-create" (:gitHubToken create-params)))
      (is (not (contains? create-params :githubToken)))))

  (testing "githubToken is forwarded in session.resume when set"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :github-token "session-token-resume"})
          resume-params (get @seen "session.resume")]
      (is (= "session-token-resume" (:gitHubToken resume-params)))
      (is (not (contains? resume-params :githubToken)))))

  (testing "githubToken is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :gitHubToken)))
      (is (not (contains? create-params :githubToken))))))

(deftest test-remote-session-config-forwarded-on-wire
  (testing "remote-session :on is forwarded as remoteSession in session.create (upstream PR #1295)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :remote-session :on})
          create-params (get @seen "session.create")]
      (is (= "on" (:remoteSession create-params)))
      (is (not (contains? create-params :remote-session)))))

  (testing "remote-session :export is forwarded in session.resume (upstream PR #1295)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :remote-session :export})
          resume-params (get @seen "session.resume")]
      (is (= "export" (:remoteSession resume-params)))))

  (testing "remote-session :off is forwarded literally (not stripped)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :remote-session :off})
          create-params (get @seen "session.create")]
      (is (= "off" (:remoteSession create-params)))))

  (testing "remote-session is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :remoteSession)))))

  (testing "remote-session config rejects unknown values via spec validation"
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :remote-session :bogus})))))

(deftest test-agent-forwarded-on-wire
  (testing "agent is forwarded in session.create when set (upstream PR #722)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :agent "my-agent"})
          create-params (get @seen "session.create")]
      (is (= "my-agent" (:agent create-params)))))

  (testing "agent is forwarded in session.resume when set (upstream PR #722)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all :agent "my-agent"})
          resume-params (get @seen "session.resume")]
      (is (= "my-agent" (:agent resume-params)))))

  (testing "agent is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :agent))))))

(deftest test-override-built-in-tool-on-wire
  (testing "overridesBuiltInTool is sent on the wire when true"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "grep"
                 {:description "Custom grep"
                  :overrides-built-in-tool true
                  :parameters {:type "object"
                               :properties {:query {:type "string"}}}
                  :handler (fn [args _] (str "Custom grep: " (:query args)))})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          create-params (get @seen "session.create")
          wire-tool (first (:tools create-params))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (= true (:overridesBuiltInTool wire-tool))
          "overridesBuiltInTool must be true on wire")))

  (testing "overridesBuiltInTool is absent on the wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "my_tool"
                 {:description "A tool"
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          create-params (get @seen "session.create")
          wire-tool (first (:tools create-params))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (not (contains? wire-tool :overridesBuiltInTool))
          "overridesBuiltInTool should be absent when not set"))))

;; -----------------------------------------------------------------------------
;; Permission Tests (upstream PR #509: deny-by-default)
;; -----------------------------------------------------------------------------

(deftest test-request-permission-always-true-on-wire
  (testing "requestPermission is always true on create with handler"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (true? (:requestPermission create-params))
          "requestPermission must be true when handler is configured")))

  (testing "requestPermission is true on create with explicit handler"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:model "gpt-5.4"
                                 :on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (true? (:requestPermission create-params)))))

  (testing "requestPermission is true on resume with handler"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (true? (:requestPermission resume-params))
          "requestPermission must be true on resume with handler"))))

(deftest test-approve-all-returns-approve-once
  (testing "approve-all returns {:kind :approve-once}"
    (let [result (sdk/approve-all {:permission-kind :shell
                                   :tool-call-id "tc-1"}
                                  {:session-id "session-1"})]
      (is (= {:kind :approve-once} result))))

  (testing "approve-all works for any permission kind"
    (doseq [kind [:shell :write :mcp :read :url :custom-tool]]
      (is (= {:kind :approve-once}
             (sdk/approve-all {:permission-kind kind} {:session-id "s1"}))))))

(deftest test-permission-handler-required
  (testing "create-session throws without :on-permission-request"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"on-permission-request handler is required"
                          (sdk/create-session *test-client* {}))))

  (testing "create-session throws with nil handler"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"on-permission-request handler is required"
                          (sdk/create-session *test-client* {:on-permission-request nil}))))

  (testing "resume-session throws without :on-permission-request"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"on-permission-request handler is required"
                            (sdk/resume-session *test-client* session-id {}))))))

(deftest test-permission-denied-with-deny-handler
  (testing "Permission requests are denied when handler denies"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :denied-no-approval-rule-and-could-not-request-from-user})})
          session-id (sdk/session-id session)
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "permission.request"
                                 {:session-id session-id
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "echo test"}}))]
      (is (= :user-not-available
             (get-in response [:result :result :kind]))))))

(deftest test-permission-approved-with-handler
  (testing "Permission requests use configured handler"
    (let [session (sdk/create-session *test-client*
                                       {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "permission.request"
                                 {:session-id session-id
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "echo test"}}))]
      (is (= :approve-once (get-in response [:result :result :kind]))))))

(deftest test-permission-unknown-session-response-shape
  (testing "unknown-session permission requests use the same nested result shape as handled requests"
    (let [handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "permission.request"
                                 {:session-id "missing-session"
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "echo test"}}))]
      (is (= :user-not-available
             (get-in response [:result :result :kind])))
      (is (nil? (get-in response [:result :kind]))))))

(deftest test-permission-custom-handler
  (testing "Custom permission handler can selectively deny"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [request _ctx]
                                         (if (= "safe-cmd" (:full-command-text request))
                                           {:kind :approve-once}
                                           {:kind :denied-by-rules
                                            :rules [{:kind "shell"
                                                     :argument (:full-command-text request)}]}))})
          session-id (sdk/session-id session)
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          approved (<!! (handler "permission.request"
                                 {:session-id session-id
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "safe-cmd"}}))
          denied (<!! (handler "permission.request"
                               {:session-id session-id
                                :permission-request {:permission-kind "shell"
                                                     :full-command-text "dangerous-cmd"}}))]
      (is (= :approve-once (get-in approved [:result :result :kind])))
      (is (= :reject (get-in denied [:result :result :kind]))))))

(deftest test-permission-no-result-v2
  (testing "no-result permission handler returns error on v2 protocol"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :no-result})})
          session-id (sdk/session-id session)
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "permission.request"
                                 {:session-id session-id
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "echo test"}}))]
      ;; v2: no-result should propagate as a JSON-RPC internal error
      (is (= -32603 (get-in response [:error :code]))
          "no-result on v2 should produce a -32603 internal error")))

  (testing "wrapped no-result permission handler returns error on v2 protocol"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:result {:kind :no-result}})})
          session-id (sdk/session-id session)
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "permission.request"
                                 {:session-id session-id
                                  :permission-request {:permission-kind "shell"
                                                       :full-command-text "echo test"}}))]
      (is (= -32603 (get-in response [:error :code]))
          "wrapped no-result on v2 should also produce a -32603 error"))))

(deftest test-permission-no-result-v3
  (testing "v3 no-result skips handlePendingPermissionRequest RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :no-result})})
          session-id (sdk/session-id session)]
      ;; Force protocol v3 so the broadcast path is active
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      ;; Reset captured requests after session creation
      (reset! requests [])
      ;; Inject a v3 permission.requested broadcast event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-req-1"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}})
      ;; Allow async handler to process
      (Thread/sleep 500)
      ;; The handler returned no-result — no handlePendingPermissionRequest RPC
      (is (empty? (filter #(= "session.permissions.handlePendingPermissionRequest"
                              (:method %))
                          @requests))
          "no-result should skip the handlePendingPermissionRequest RPC"))))

(deftest test-permission-approved-v3
  (testing "v3 approve-once handler sends handlePendingPermissionRequest RPC"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      ;; Reset captured requests after session creation
      (reset! requests [])
      ;; Inject a v3 permission.requested broadcast event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-req-2"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}})
      ;; Wait for the RPC to arrive (up to 5 seconds)
      (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
      ;; The handler approved — should send handlePendingPermissionRequest RPC
      (let [perm-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                  (:method %))
                              @requests)]
        (is (= 1 (count perm-rpcs))
            "approved result should send handlePendingPermissionRequest RPC")
        (when (seq perm-rpcs)
          (is (= "perm-req-2" (:requestId (:params (first perm-rpcs))))
              "RPC should include the correct request-id"))))))

(deftest test-legacy-permission-denials-normalize-to-v3-reject
  (testing "legacy denial aliases send upstream reject decisions with feedback"
    (doseq [[legacy-kind expected-feedback]
            [[:denied-by-rules "Denied by rules"]
             [:denied-interactively-by-user "custom feedback"]
             [:denied-by-content-exclusion-policy "Denied by content exclusion policy"]
             [:denied-by-permission-request-hook "Denied by permission request hook"]]]
      (let [requests (atom [])
            rpc-latch (java.util.concurrent.CountDownLatch. 1)
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})
                                        (when (= "session.permissions.handlePendingPermissionRequest" method)
                                          (.countDown rpc-latch))))
            session (sdk/create-session *test-client*
                                        {:on-permission-request
                                          (fn [_request _ctx]
                                            (cond-> {:kind legacy-kind}
                                              (= legacy-kind :denied-interactively-by-user)
                                              (assoc :feedback "custom feedback")

                                              true
                                              (assoc :rules [{:kind "shell"}]
                                                     :internal true)))})
            session-id (sdk/session-id session)]
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        (reset! requests [])
        (mock/send-v3-broadcast-event! *mock-server* session-id
                                       "permission.requested"
                                       {:requestId (str "perm-" (name legacy-kind))
                                        :permissionRequest {:permissionKind "shell"
                                                            :fullCommandText "echo test"}})
        (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
            (str "timed out waiting for legacy denial " legacy-kind))
        (let [decision (->> @requests
                            (filter #(= "session.permissions.handlePendingPermissionRequest"
                                        (:method %)))
                            first
                            :params
                            :result)]
          (is (= "reject" (:kind decision)))
          (is (= expected-feedback (:feedback decision)))
          (is (not (contains? decision :message)))
          (is (not (contains? decision :rules)))
          (is (not (contains? decision :internal))))))))

(deftest test-permission-decisions-drop-legacy-and-extra-fields
  (testing "normalized permission decisions only forward upstream decision fields"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :approved
                                          :message "legacy message"
                                          :feedback "extra feedback"
                                          :rules [{:kind "shell"}]
                                          :internal true})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-legacy-approved"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [decision (->> @requests
                          (filter #(= "session.permissions.handlePendingPermissionRequest"
                                      (:method %)))
                          first
                          :params
                          :result)]
        (is (= {:kind "approve-once"} decision))))))

(deftest test-permission-resolved-by-hook-v3
  (testing "v3 permission.requested with resolvedByHook=true skips handler entirely"
    (let [handler-called? (atom false)
          requests (atom [])
          ;; Use a latch to wait for the event to be delivered to the session
          event-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         (reset! handler-called? true)
                                         {:kind :approve-once})
                                       :on-event
                                       (fn [event]
                                         (when (= :copilot/permission.requested (:type event))
                                           (.countDown event-latch)))})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject permission.requested with resolvedByHook=true
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-hook-1"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}
                                      :resolvedByHook true})
      ;; Wait for the event to be delivered (proves routing completed)
      (is (.await event-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for permission.requested event delivery")
      ;; Handler should NOT be called
      (is (false? @handler-called?)
          "permission handler should not be invoked when resolvedByHook is true")
      ;; No RPC should be sent
      (is (empty? (filter #(= "session.permissions.handlePendingPermissionRequest"
                              (:method %))
                          @requests))
          "no handlePendingPermissionRequest RPC when resolvedByHook is true")))

  (testing "v3 permission.requested with resolvedByHook=false invokes handler normally"
    (let [handler-called? (atom false)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         (reset! handler-called? true)
                                         {:kind :approve-once})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject permission.requested with resolvedByHook=false
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-hook-2"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}
                                      :resolvedByHook false})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingPermissionRequest RPC")
      (is (true? @handler-called?)
          "permission handler should be invoked when resolvedByHook is false")
      (is (= 1 (count (filter #(= "session.permissions.handlePendingPermissionRequest"
                                  (:method %))
                              @requests)))
          "handlePendingPermissionRequest RPC should be sent"))))

(deftest test-permission-result-kinds-spec
  (testing "upstream v0.3.0 permission decision kinds are valid"
    (doseq [kind [:approve-once
                  :approve-for-session
                  :approve-for-location
                  :reject
                  :user-not-available
                  :no-result]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result-kind kind)
          (str kind " should be a valid permission result kind"))))
  (testing "legacy Clojure permission result kinds remain accepted for compatibility"
    (doseq [kind [:approved
                  :denied-by-rules
                  :denied-no-approval-rule-and-could-not-request-from-user
                  :denied-interactively-by-user
                  :denied-by-content-exclusion-policy
                  :denied-by-permission-request-hook]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result-kind kind)
          (str kind " should remain accepted")))))

(deftest test-permission-result-spec-uses-kind-key
  (testing "permission result maps use the public :kind key"
    (doseq [result [{:kind :approve-once}
                    {:kind :approve-for-session
                     :approval {:kind :commands
                                :command-identifiers ["echo"]}}
                    {:kind :approve-for-location
                     :approval {:kind :write}
                     :location-key "/workspace"}
                    {:kind :reject
                     :feedback "Not allowed"}
                    {:kind :user-not-available}
                    {:kind :no-result}]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result result)
          (str result " should be a valid permission result"))))
  (testing "internal spec key is not part of the public permission result shape"
    (is (not (s/valid? :github.copilot-sdk.specs/permission-result
                       {:permission-result-kind :approve-once})))))

(deftest test-tool-execution-start-mcp-fields
  (testing "tool.execution_start event data allows MCP fields"
    (is (s/valid? :github.copilot-sdk.specs/tool.execution_start-data
                  {:tool-call-id "tc-1"
                   :tool-name "mcp__server__tool"
                   :mcp-server-name "my-mcp-server"
                   :mcp-tool-name "original-tool"})
        "tool.execution_start-data should accept mcp-server-name and mcp-tool-name")))

(deftest test-upstream-event-data-field-specs
  (testing "assistant reasoning and message fields from generated events are explicitly spec'd"
    (doseq [spec-key [:github.copilot-sdk.specs/reasoning-id
                      :github.copilot-sdk.specs/encrypted-content
                      :github.copilot-sdk.specs/output-tokens
                      :github.copilot-sdk.specs/phase
                      :github.copilot-sdk.specs/reasoning-opaque
                      :github.copilot-sdk.specs/reasoning-text
                      :github.copilot-sdk.specs/request-id]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (is (s/valid? :github.copilot-sdk.specs/assistant.reasoning-data
                  {:reasoning-id "r1" :content "thinking"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1"
                   :content "answer"
                   :encrypted-content "ciphertext"
                   :output-tokens 42
                   :phase "response"
                   :reasoning-opaque "opaque"
                   :reasoning-text "visible reasoning"
                   :request-id "req-1"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.reasoning-data
                          {:reasoning-id "r1" :content {:unexpected true}})))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1" :content {:unexpected true}})))
    (is (false? (s/valid? :github.copilot-sdk.specs/user.message-data
                          {:content {:unexpected true}})))
    (is (s/valid? :github.copilot-sdk.specs/elicitation-result
                  {:action "accept" :content {:name "test-value"}})))

  (testing "status and loaded event data fields from generated events are explicitly spec'd"
    (doseq [spec-key [:github.copilot-sdk.specs/mcp-server-status
                      :github.copilot-sdk.specs/mcp-loaded-server
                      :github.copilot-sdk.specs/session.mcp_servers_loaded-data
                      :github.copilot-sdk.specs/session.mcp_server_status_changed-data
                      :github.copilot-sdk.specs/session.skills_loaded-data
                      :github.copilot-sdk.specs/session.extensions_loaded-data]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (is (s/valid? :github.copilot-sdk.specs/session.mcp_server_status_changed-data
                  {:server-name "github" :status "needs-auth"}))
    (is (s/valid? :github.copilot-sdk.specs/session.skills_loaded-data
                  {:skills [{:name "skill-a"
                             :description "A skill"
                             :enabled true
                             :source "project"
                             :user-invocable false}]}))
    (is (s/valid? :github.copilot-sdk.specs/session.extensions_loaded-data
                  {:extensions [{:id "project:ext-a"
                                 :name "ext-a"
                                 :source "project"
                                 :status "running"}
                                {:id "user:ext-b"
                                 :name "ext-b"
                                 :source "user"
                                 :status "starting"}]}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.extensions_loaded-data
                       {:extensions [{:id "project:ext-a"
                                      :name "ext-a"
                                      :source "project"
                                      :status "enabled"}]})))))

;; -----------------------------------------------------------------------------
;; Last Session ID Tests
;; -----------------------------------------------------------------------------

(deftest test-cli-1.0.46-sync-spec-additions
  (testing "::permission-kind accepts new extension-* kinds (CLI 1.0.44-3)"
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-management))
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-permission-access))
    (is (false? (s/valid? :github.copilot-sdk.specs/permission-kind :bogus-kind))))

  (testing "::assistant.message-data accepts new advisor + model fields (CLI 1.0.45)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1"
                   :content "answer"
                   :anthropic-advisor-blocks [{:type "server_tool_use"}]
                   :anthropic-advisor-model "claude-advisor"
                   :model "gpt-5"})))

  (testing "::session.start-data accepts :detached-from-spawning-parent-session-id (CLI 1.0.44-3)"
    (is (s/valid? :github.copilot-sdk.specs/session.start-data
                  {:session-id "s1"
                   :detached-from-spawning-parent-session-id "parent-s0"})))

  (testing "::model-info accepts model-picker categorization fields (CLI 1.0.46)"
    (is (s/valid? :github.copilot-sdk.specs/model-info
                  {:id "m1"
                   :name "Model 1"
                   :model-picker-category "powerful"
                   :model-picker-price-category "very_high"}))
    ;; Open string enum — unknown values should still validate as strings.
    (is (s/valid? :github.copilot-sdk.specs/model-info
                  {:id "m1"
                   :name "Model 1"
                   :model-picker-category "future-tier-not-yet-defined"}))))

(deftest test-list-models-surfaces-model-picker-fields
  (testing "list-models exposes modelPickerCategory and modelPickerPriceCategory (CLI 1.0.46)"
    (mock/set-request-hook! *mock-server*
                            (fn [method _params]
                              (when (= "models.list" method)
                                {:github.copilot-sdk.mock-server/merge-response
                                 {:models [{:id "m-picker"
                                            :name "Picker Model"
                                            :modelPickerCategory "powerful"
                                            :modelPickerPriceCategory "very_high"}]}})))
    (let [models (sdk/list-models *test-client*)
          m (first models)]
      (is (= "m-picker" (:id m)))
      (is (= "powerful" (:model-picker-category m)))
      (is (= "very_high" (:model-picker-price-category m))))))

(deftest test-respond-to-queued-command
  (testing "respond-to-queued-command! sends correct wire shape with handled=true"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-1"
                                           :handled? true
                                           :stop-processing-queue? true})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= "cmd-q-1" (:requestId (:params req))))
        (is (= {:handled true :stopProcessingQueue true} (:result (:params req)))))))

  (testing "respond-to-queued-command! sends correct wire shape with handled=false"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-2"
                                           :handled? false})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= "cmd-q-2" (:requestId (:params req))))
        (is (= {:handled false} (:result (:params req)))))))

  (testing "respond-to-queued-command! forwards explicit stop-processing-queue?=false"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-3"
                                           :handled? true
                                           :stop-processing-queue? false})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= {:handled true :stopProcessingQueue false} (:result (:params req)))
            "explicit false should be forwarded on the wire (not silently dropped)")))))

(deftest test-send-async-untaps-on-send-failure
  (testing "send-async cleans up tap when RPC fails"
    (log/info "Warnings expected in this test: async send RPC error is deliberate.")
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          taps (atom 0)
          untaps (atom 0)
          fake-mult (reify
                      async/Mux
                      (muxch* [_] (chan))
                      async/Mult
                      (tap* [_ _ _] (swap! taps inc) nil)
                      (untap* [_ _] (swap! untaps inc) nil)
                      (untap-all* [_] nil))]
      (swap! (:state *test-client*) assoc-in [:session-io (sdk/session-id session) :event-mult] fake-mult)
      ;; <send-async* uses proto/send-request — return an error response
      (with-redefs [protocol/send-request (fn [_ _ _]
                                            (let [ch (async/chan 1)]
                                              (async/put! ch {:error {:code -1 :message "forced failure"}})
                                              (async/close! ch)
                                              ch))]
        (let [events-ch (sdk/send-async session {:prompt "should-fail"})]
          ;; Channel should close without events (error path)
          (is (nil? (<!! events-ch)))))
      (is (= 1 @taps))
      (is (pos? @untaps)))))

(deftest test-get-last-session-id
  (testing "Get last session ID"
    (let [_ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          last-id (sdk/get-last-session-id *test-client*)]
      (is (string? last-id)))))

;; -----------------------------------------------------------------------------
;; Multiple Sessions Tests
;; -----------------------------------------------------------------------------

(deftest test-multiple-sessions
  (testing "Can manage multiple concurrent sessions"
    (let [session1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "model-1"})
          session2 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "model-2"})
          id1 (sdk/session-id session1)
          id2 (sdk/session-id session2)]
      (is (not= id1 id2))
      (is (= 2 (count (sdk/list-sessions *test-client*))))
      ;; Clean up one session
      (sdk/destroy! session1)
      (is (= 1 (count (sdk/list-sessions *test-client*)))))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-resume-nonexistent-session
  (testing "Resume nonexistent session throws error"
    (is (thrown-with-msg? Exception #"Session not found"
                          (sdk/resume-session *test-client* "nonexistent-session-id" {:on-permission-request sdk/approve-all})))))

(deftest test-tool-handler-errors
  (testing "Tool handler that throws returns failure result"
    (let [error-tool (sdk/define-tool "error_tool"
                       {:description "A tool that always fails"
                        :parameters {:type "object"
                                     :properties {}}
                        :handler (fn [_ _]
                                   (throw (ex-info "Tool error" {:cause "test"})))})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [error-tool]})]
      ;; Session should still be usable after tool error
      (is (some? session)))))

;; -----------------------------------------------------------------------------
;; System Message Tests
;; -----------------------------------------------------------------------------

(deftest test-session-with-append-system-message
  (testing "Create session with appended system message"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :append
                                                        :content "Always end with 'DONE'"}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

(deftest test-session-with-replace-system-message
  (testing "Create session with replaced system message"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :replace
                                                        :content "You are a test assistant."}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

;; -----------------------------------------------------------------------------
;; Streaming Tests
;; -----------------------------------------------------------------------------

(deftest test-session-with-streaming
  (testing "Create session with streaming enabled"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :streaming? true})]
      (is (some? session))
      ;; Should still work normally
      (let [result (sdk/send-and-wait! session {:prompt "Test"})]
        (is (some? result))))))

;; -----------------------------------------------------------------------------
;; Resume Session Tests
;; -----------------------------------------------------------------------------

(deftest test-resume-session
  (testing "Resume existing session"
    (let [session1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session1)
          _ (sdk/send-and-wait! session1 {:prompt "First message"})
          session2 (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})]
      (is (= session-id (sdk/session-id session2)))
      ;; Should be able to continue conversation
      (let [result (sdk/send-and-wait! session2 {:prompt "Follow up"})]
        (is (some? result))))))

;; -----------------------------------------------------------------------------
;; Session State Event Tests
;; -----------------------------------------------------------------------------

(deftest test-session-snapshot-rewind-event
  (testing "session.snapshot_rewind event is received and parsed correctly"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)
          rewind-events (atom [])]
      ;; Send snapshot_rewind event from mock server
      (mock/send-session-event! *mock-server* session-id
                                :copilot/session.snapshot_rewind
                                {:upToEventId "evt-42"
                                 :eventsRemoved 5}
                                :ephemeral? true)
      ;; Collect events
      (Thread/sleep 100)
      (loop []
        (let [[v _] (alts!! [events-ch (timeout 100)])]
          (when (some? v)
            (when (= :copilot/session.snapshot_rewind (:type v))
              (swap! rewind-events conj v))
            (recur))))
      ;; Verify event was received
      (is (= 1 (count @rewind-events)))
      (let [event (first @rewind-events)]
        (is (= :copilot/session.snapshot_rewind (:type event)))
        (is (= "evt-42" (get-in event [:data :up-to-event-id])))
        (is (= 5 (get-in event [:data :events-removed]))))
      (sdk/unsubscribe-events session events-ch))))

;; -----------------------------------------------------------------------------
;; Async Session Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-<create-session
  (testing "<create-session creates session asynchronously"
    (let [result-ch (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          [session _] (alts!! [result-ch (timeout 5000)])]
      (is (some? session) "<create-session should deliver a session")
      (is (not (instance? Throwable session)) "<create-session should not return an error")
      (is (string? (sdk/session-id session)))
      (sdk/destroy! session))))

(deftest test-<create-session-parallel
  (testing "Multiple <create-session calls run concurrently in go blocks"
    (let [ch1 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          ch2 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          ch3 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          [s1 _] (alts!! [ch1 (timeout 5000)])
          [s2 _] (alts!! [ch2 (timeout 5000)])
          [s3 _] (alts!! [ch3 (timeout 5000)])]
      (is (not (instance? Throwable s1)) "<create-session s1 should not return an error")
      (is (not (instance? Throwable s2)) "<create-session s2 should not return an error")
      (is (not (instance? Throwable s3)) "<create-session s3 should not return an error")
      (is (some? s1))
      (is (some? s2))
      (is (some? s3))
      (is (= 3 (count (set [(sdk/session-id s1) (sdk/session-id s2) (sdk/session-id s3)]))))
      (sdk/destroy! s1)
      (sdk/destroy! s2)
      (sdk/destroy! s3))))

;; -----------------------------------------------------------------------------
;; Command Tests
;; -----------------------------------------------------------------------------

(deftest test-commands-on-wire
  (testing "commands are sent on wire as name+description only"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :commands [{:name "deploy"
                                             :description "Deploy the app"
                                             :command-handler (fn [_ctx] nil)}
                                            {:name "rollback"
                                             :command-handler (fn [_ctx] nil)}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :commands [{:name "status"
                                             :description "Check status"
                                             :command-handler (fn [_ctx] nil)}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      ;; Commands are sent with name and description only (no handler)
      (is (= [{:name "deploy" :description "Deploy the app"}
              {:name "rollback"}]
             (:commands create-params)))
      (is (= [{:name "status" :description "Check status"}]
             (:commands resume-params))))))

(deftest test-command-execute-v3
  (testing "v3 command.execute event routes to handler and sends RPC response"
    (let [requests (atom [])
          handler-called (atom nil)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.commands.handlePendingCommand" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :commands [{:name "deploy"
                                                   :command-handler (fn [ctx]
                                                                      (reset! handler-called ctx))}]})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject command.execute event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "command.execute"
                                     {:requestId "cmd-req-1"
                                      :command "/deploy production"
                                      :commandName "deploy"
                                      :args "production"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      ;; Handler was called with context
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called)))
      (is (= "/deploy production" (:command @handler-called)))
      (is (= "deploy" (:command-name @handler-called)))
      (is (= "production" (:args @handler-called)))
      ;; handlePendingCommand RPC was sent
      (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)]
        (is (= 1 (count cmd-rpcs)))
        (when (seq cmd-rpcs)
          (is (= "cmd-req-1" (:requestId (:params (first cmd-rpcs)))))
          (is (nil? (:error (:params (first cmd-rpcs))))))))))

(deftest test-command-execute-unknown-command
  (testing "unknown command sends error via RPC"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.commands.handlePendingCommand" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :commands [{:name "deploy"
                                                   :command-handler (fn [_] nil)}]})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "command.execute"
                                     {:requestId "cmd-req-2"
                                      :command "/unknown"
                                      :commandName "unknown"
                                      :args ""})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)]
        (is (= 1 (count cmd-rpcs)))
        (when (seq cmd-rpcs)
          (is (string? (:error (:params (first cmd-rpcs)))))
          (is (re-find #"Unknown command" (:error (:params (first cmd-rpcs))))))))))

(deftest test-command-handler-error
  (testing "command handler exception sends error via RPC"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.commands.handlePendingCommand" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :commands [{:name "fail"
                                                   :command-handler (fn [_]
                                                                      (throw (Exception. "deploy failed")))}]})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "command.execute"
                                     {:requestId "cmd-req-3"
                                      :command "/fail"
                                      :commandName "fail"
                                      :args ""})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)]
        (is (= 1 (count cmd-rpcs)))
        (when (seq cmd-rpcs)
          (is (= "deploy failed" (:error (:params (first cmd-rpcs))))))))))

;; -----------------------------------------------------------------------------
;; Capabilities and Elicitation Tests
;; -----------------------------------------------------------------------------

(deftest test-session-capabilities
  (testing "capabilities default to empty map when not in response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {} (sdk/capabilities session)))
      (is (false? (sdk/elicitation-supported? session))))))

(deftest test-session-capabilities-from-response
  (testing "capabilities stored from session.create response"
    (let [_ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (= "session.create" method)
                                        {::mock/merge-response {:capabilities {:ui {:elicitation true}}}})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {:ui {:elicitation true}} (sdk/capabilities session)))
      (is (true? (sdk/elicitation-supported? session))))))

(deftest test-elicitation-throws-when-unsupported
  (testing "elicitation convenience methods throw when not supported"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/confirm! session "test")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/select! session "test" ["a" "b"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/input! session "test"))))))

(deftest test-session-without-commands
  (testing "session without commands has empty command handlers"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {} (get-in @(:state *test-client*)
                        [:sessions (sdk/session-id session) :command-handlers]))))))

;; -----------------------------------------------------------------------------
;; Elicitation Provider Tests (upstream PR #908)
;; -----------------------------------------------------------------------------

(deftest test-request-elicitation-wire-flag
  (testing "requestElicitation is true when :on-elicitation-request is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request (fn [_ctx] {:action "cancel"})})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (when (seq create-rpcs)
        (is (true? (:requestElicitation (:params (first create-rpcs))))))))

  (testing "requestElicitation is false when :on-elicitation-request is not provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (when (seq create-rpcs)
        (is (false? (:requestElicitation (:params (first create-rpcs)))))))))

(deftest test-elicitation-requested-v3
  (testing "v3 elicitation.requested event routes to handler and sends RPC response"
    (let [requests (atom [])
          handler-called (atom nil)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.ui.handlePendingElicitation" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request
                                       (fn [context]
                                         (reset! handler-called context)
                                         {:action "accept"
                                          :content {:name "test-value"}})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject elicitation.requested event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "elicitation.requested"
                                     {:requestId "elicit-req-1"
                                      :message "Enter your name"
                                      :requestedSchema {:type "object"
                                                        :properties {"name" {:type "string"}}}
                                      :mode "form"
                                      :elicitationSource "mcp-server"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      ;; Handler was called with ElicitationContext (single arg, includes session-id)
      (is (some? @handler-called))
      (is (= "Enter your name" (:message @handler-called)))
      (is (= session-id (:session-id @handler-called)))
      ;; handlePendingElicitation RPC was sent with handler's result
      (let [rpcs (filter #(= "session.ui.handlePendingElicitation" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (when (seq rpcs)
          (is (= "elicit-req-1" (:requestId (:params (first rpcs)))))
          (is (= "accept" (get-in (first rpcs) [:params :result :action]))))))))

(deftest test-elicitation-handler-error-sends-cancel
  (testing "handler exception sends cancel response to avoid hanging"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.ui.handlePendingElicitation" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request
                                       (fn [_ctx]
                                         (throw (Exception. "UI unavailable")))})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "elicitation.requested"
                                     {:requestId "elicit-req-2"
                                      :message "Prompt"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [rpcs (filter #(= "session.ui.handlePendingElicitation" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (when (seq rpcs)
          (is (= "cancel" (get-in (first rpcs) [:params :result :action]))))))))

(deftest test-capabilities-changed-event
  (testing "capabilities.changed broadcast updates session capabilities"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (false? (sdk/elicitation-supported? session)))
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      ;; Inject capabilities.changed event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "capabilities.changed"
                                     {:ui {:elicitation true}}
                                     :ephemeral? true)
      ;; Give event routing time to process
      (Thread/sleep 200)
      (is (true? (sdk/elicitation-supported? session))))))

;; -----------------------------------------------------------------------------
;; SessionFs Tests (upstream PR #917)
;; -----------------------------------------------------------------------------

(deftest test-session-fs-handler-stored
  (testing "session-fs-handler is stored in session state when provided"
    (let [fs-handler {:read-file (fn [_] {:content "hello"})
                      :write-file (fn [_] nil)
                      :append-file (fn [_] nil)
                      :exists (fn [_] {:exists true})
                      :stat (fn [_] {:is-file true :is-directory false :size 5 :mtime "2026-01-01T00:00:00Z"})
                      :mkdir (fn [_] nil)
                      :readdir (fn [_] {:entries []})
                      :readdir-with-types (fn [_] {:entries []})
                      :rm (fn [_] nil)
                      :rename (fn [_] nil)}
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Set handler directly (as client code would do after factory call)
      (session/set-session-fs-handler! (:client session) session-id fs-handler)
      (let [stored (get-in @(:state *test-client*)
                           [:sessions session-id :session-fs-handler])]
        (is (some? stored))
        (is (fn? (:read-file stored)))
        (is (= {:content "hello"} ((:read-file stored) {:path "/test.txt"})))))))

(deftest test-create-session-fs-adapter
  (testing "provider-style functions are adapted to sessionFs handler results"
    (let [provider {:read-file (fn [path]
                                 (is (= "/ok.txt" path))
                                 "hello")
                    :write-file (fn [path content mode]
                                  (is (= "/ok.txt" path))
                                  (is (= "updated" content))
                                  (is (= 420 mode)))
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true
                                       :is-directory false
                                       :size 5
                                       :mtime "2026-01-01T00:00:00Z"
                                       :birthtime "2026-01-01T00:00:00Z"})
                    :readdir (fn [_path] ["a.txt"])
                    :readdir-with-types (fn [_path] [{:name "a.txt" :is-file true :is-directory false}])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:content "hello"} ((:read-file handler) {:path "/ok.txt"})))
      (is (nil? ((:write-file handler) {:path "/ok.txt"
                                        :content "updated"
                                        :mode 420})))
      (is (= {:exists true} ((:exists handler) {:path "/ok.txt"})))
      (is (= {:entries ["a.txt"]} ((:readdir handler) {:path "/"})))))

  (testing "provider exceptions become structured sessionFs errors"
    (let [missing (ex-info "missing file" {:code "ENOENT"})
          provider {:read-file (fn [_path] (throw missing))
                    :write-file (fn [_path _content _mode]
                                  (throw (ex-info "disk full" {})))
                    :exists (fn [_path] (throw (ex-info "boom" {})))
                    :stat (fn [_path] (throw missing))
                    :readdir (fn [_path] (throw missing))
                    :readdir-with-types (fn [_path] (throw missing))
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:content ""
              :error {:code "ENOENT" :message "missing file"}}
             ((:read-file handler) {:path "/missing.txt"})))
      (is (= {:code "UNKNOWN" :message "disk full"}
             ((:write-file handler) {:path "/x" :content "data"})))
      (is (= {:exists false} ((:exists handler) {:path "/x"})))
      (is (= "ENOENT" (get-in ((:stat handler) {:path "/x"}) [:error :code])))
      (is (= {:entries []
              :error {:code "ENOENT" :message "missing file"}}
             ((:readdir handler) {:path "/x"})))))

  (testing "provider async results are realized before normalization"
    (letfn [(value-chan [value]
              (let [ch (chan 1)]
                (>!! ch value)
                (close! ch)
                ch))
            (promise-value [value]
              (let [ch (async/promise-chan)]
                (>!! ch value)
                ch))]
      (let [writes (atom [])
            provider {:read-file (fn [_path] (value-chan "async content"))
                      :write-file (fn [path content mode]
                                    (future (swap! writes conj [path content mode])))
                      :exists (fn [_path] (promise-value false))
                      :stat (fn [_path] (future {:is-file true
                                                 :is-directory false
                                                 :size 13}))
                      :readdir (fn [_path] (value-chan ["async.txt"]))
                      :readdir-with-types (fn [_path] (future [{:name "async.txt"
                                                                :is-file true
                                                                :is-directory false}]))
                      :append-file (fn [_path _content _mode] (value-chan nil))
                      :mkdir (fn [_path _recursive _mode] (future nil))
                      :rm (fn [_path _recursive _force] (value-chan nil))
                      :rename (fn [_src _dest] (future nil))}
            handler (session/create-session-fs-adapter provider)]
        (is (= {:content "async content"}
               ((:read-file handler) {:path "/async.txt"})))
        (is (nil? ((:write-file handler) {:path "/async.txt"
                                          :content "updated"
                                          :mode 420})))
        (is (= [["/async.txt" "updated" 420]] @writes))
        (is (= {:exists false} ((:exists handler) {:path "/async.txt"})))
        (is (= {:is-file true :is-directory false :size 13}
               ((:stat handler) {:path "/async.txt"})))
        (is (= {:entries ["async.txt"]} ((:readdir handler) {:path "/"})))
        (is (= {:entries [{:name "async.txt" :is-file true :is-directory false}]}
               ((:readdir-with-types handler) {:path "/"}))))))

  (testing "one-arg fallback exceptions are converted to sessionFs errors"
    (let [provider {:read-file (fn [_path] "ok")
                    :write-file (fn [& args]
                                  (if (= 1 (count args))
                                    (throw (ex-info "fallback missing" {:code "ENOENT"}))
                                    (throw (clojure.lang.ArityException. 3 "write-file"))))
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true :is-directory false :size 2})
                    :readdir (fn [_path] [])
                    :readdir-with-types (fn [_path] [])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:code "ENOENT" :message "fallback missing"}
             ((:write-file handler) {:path "/x" :content "data"})))))

  (testing "provider spec distinguishes provider-style maps from low-level handlers"
    (let [provider {:read-file (fn [_path] "ok")
                    :write-file (fn [_path _content _mode] nil)
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true :is-directory false :size 2})
                    :readdir (fn [_path] [])
                    :readdir-with-types (fn [_path] [])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          low-level-handler {:read-file (fn [_params] {:content "ok"})
                             :write-file (fn [_params] nil)
                             :exists (fn [_params] {:exists true})
                             :stat (fn [_params] {:is-file true :is-directory false :size 2})
                             :readdir (fn [_params] {:entries []})
                             :readdir-with-types (fn [_params] {:entries []})
                             :append-file (fn [_params] nil)
                             :mkdir (fn [_params] nil)
                             :rm (fn [_params] nil)
                             :rename (fn [_params] nil)}]
      (is (s/valid? :github.copilot-sdk.specs/session-fs-provider provider))
      (is (not (s/valid? :github.copilot-sdk.specs/session-fs-provider low-level-handler)))
      (is (s/valid? :github.copilot-sdk.specs/session-fs-handler low-level-handler))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid sessionFs provider"
                            (session/create-session-fs-adapter low-level-handler)))))

  (testing "adapter detection is nil-safe for incomplete maps"
    (is (= {:read-file ::only}
           (session/adapt-session-fs-handler {:read-file ::only})))))

(deftest test-create-session-fs-handler-factory-auto-adapts-provider
  (testing "create-session auto-adapts provider-style factory return like upstream Node"
    (let [calls (atom [])
          client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [path]
                                                       (swap! calls conj [:read-file path])
                                                       "content")
                                          :write-file (fn [path content mode]
                                                        (swap! calls conj [:write-file path content mode]))
                                          :append-file (fn [_path _content _mode] nil)
                                          :exists (fn [_path] true)
                                          :stat (fn [_path] {:is-file true :is-directory false :size 7})
                                          :mkdir (fn [_path _recursive _mode] nil)
                                          :readdir (fn [_path] [])
                                          :readdir-with-types (fn [_path] [])
                                          :rm (fn [_path _recursive _force] nil)
                                          :rename (fn [_src _dest] nil)})})
          session-id (sdk/session-id session)
          read-response (mock/send-rpc-request! *mock-server*
                                                "sessionFs.readFile"
                                                {:sessionId session-id :path "/file.txt"})
          write-response (mock/send-rpc-request! *mock-server*
                                                 "sessionFs.writeFile"
                                                 {:sessionId session-id
                                                  :path "/file.txt"
                                                  :content "updated"
                                                  :mode 420})]
      (is (= {:content "content"} (:result read-response)))
      (is (nil? (:result write-response)))
      (is (= [[:read-file "/file.txt"]
              [:write-file "/file.txt" "updated" 420]]
             @calls)))))

(deftest test-create-session-fs-handler-factory-preserves-low-level-handler
  (testing "create-session still accepts existing one-arg RPC-shaped handler maps"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [{:keys [path]}]
                                                       {:content (str "read " path)})
                                          :write-file (fn [_params] nil)
                                          :append-file (fn [_params] nil)
                                          :exists (fn [_params] {:exists true})
                                          :stat (fn [_params] {:is-file true :is-directory false :size 7})
                                          :mkdir (fn [_params] nil)
                                          :readdir (fn [_params] {:entries []})
                                          :readdir-with-types (fn [_params] {:entries []})
                                          :rm (fn [_params] nil)
                                          :rename (fn [_params] nil)})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.readFile"
                                           {:sessionId (sdk/session-id session)
                                            :path "/legacy.txt"})]
      (is (= {:content "read /legacy.txt"} (:result response))))))

(deftest test-create-session-fs-handler-factory-validates-return
  (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                         :session-state-path "/state"
                                                         :conventions "posix"})
        invalid-factory (fn [_session]
                          {:read-file (fn [_params] {:content "partial"})})
        config {:on-permission-request sdk/approve-all
                :create-session-fs-handler invalid-factory}]
    (testing "create-session fails before storing an invalid handler"
      (let [session-id "invalid-fs-create"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/create-session client-with-fs
                                                  (assoc config :session-id session-id))))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "<create-session fails synchronously before returning a channel"
      (let [session-id "invalid-fs-create-async"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/<create-session client-with-fs
                                                   (assoc config :session-id session-id))))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "resume-session fails before storing an invalid handler"
      (let [session-id "invalid-fs-resume"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/resume-session client-with-fs session-id config)))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "<resume-session fails synchronously before returning a channel"
      (let [session-id "invalid-fs-resume-async"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/<resume-session client-with-fs session-id config)))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))))

;; -----------------------------------------------------------------------------
;; Hooks Tests (server→client RPC)
;; -----------------------------------------------------------------------------

(deftest test-hooks-pre-tool-use
  (testing "hooks.invoke preToolUse calls registered handler and returns result"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:permission-decision "allow"
                                                  :additional-context "extra info"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "echo hi"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      ;; Input keys are converted to kebab-case by wire->clj
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= {:command "echo hi"} (get-in @handler-called [:input :tool-args])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      ;; Response contains the handler's return value (wire-converted)
      (is (= "allow" (get-in response [:result :permissionDecision]))))))

(deftest test-hooks-post-tool-use
  (testing "hooks.invoke postToolUse calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :toolResult {:textResultForLlm "ok"
                                                                 :resultType "success"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      ;; Handler returned nil, so result is nil
      (is (nil? (:result response))))))

(deftest test-hooks-session-start
  (testing "hooks.invoke sessionStart calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-session-start
                                               (fn [input ctx]
                                                 (reset! handler-called input)
                                                 {:additional-context "welcome"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "sessionStart"
                                            :input {:source "new"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "new" (:source @handler-called)))
      (is (= "welcome" (get-in response [:result :additionalContext]))))))

(deftest test-hooks-unknown-type-returns-nil
  (testing "hooks.invoke with unknown hook type returns nil result"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] {:permission-decision "allow"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "unknownHookType"
                                            :input {:timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (nil? (:result response))))))

(deftest test-hooks-handler-exception-returns-nil
  (testing "hooks.invoke handler exception returns nil gracefully"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] (throw (Exception. "oops")))}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (nil? (:result response))))))

(deftest test-hooks-no-hooks-registered
  (testing "hooks.invoke with no hooks registered returns nil"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (nil? (:result response))))))

;; -----------------------------------------------------------------------------
;; User Input Handler Tests (server→client RPC)
;; -----------------------------------------------------------------------------

(deftest test-user-input-handler-invoked
  (testing "userInput.request calls registered handler with correct shape"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-user-input-request
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         {:answer "option A" :was-freeform false})})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "userInput.request"
                                           {:sessionId session-id
                                            :question "Which option?"
                                            :choices ["option A" "option B"]
                                            :allowFreeform true})]
      (is (some? @handler-called))
      (is (= "Which option?" (get-in @handler-called [:request :question])))
      (is (= "option A" (get-in response [:result :answer])))
      (is (false? (get-in response [:result :wasFreeform]))))))

(deftest test-user-input-no-handler-errors
  (testing "userInput.request without handler returns error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User input requested but no handler registered"
                            (mock/send-rpc-request! *mock-server*
                                                    "userInput.request"
                                                    {:sessionId session-id
                                                     :question "Which option?"}))))))

;; -----------------------------------------------------------------------------
;; System Message Transform Tests (server→client RPC)
;; -----------------------------------------------------------------------------

(deftest test-system-message-transform-callback
  (testing "systemMessage.transform invokes registered transform callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [content]
                                                                                        (str content " EXTRA"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "I am an agent."}}})]
      (is (= "I am an agent. EXTRA"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-error-returns-original
  (testing "systemMessage.transform returns original content on callback error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [_] (throw (Exception. "fail")))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "original text"}}})]
      (is (= "original text"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-no-callback-passthrough
  (testing "systemMessage.transform passes through sections without callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [c] (str c "!"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "hello"}
                                                       :tone {:content "be nice"}}})]
      (is (= "hello!" (get-in response [:result :sections :identity :content])))
      (is (= "be nice" (get-in response [:result :sections :tone :content]))))))

;; -----------------------------------------------------------------------------
;; Tool Result Normalization Tests (v3 broadcast path)
;; -----------------------------------------------------------------------------

(deftest test-tool-result-string-passthrough
  (testing "tool handler returning string is normalized to success result"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.tools.handlePendingToolCall" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [{:tool-name "test-tool"
                                                :tool-handler (fn [_args _inv] "hello world")}]})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "external_tool.requested"
                                     {:requestId "tool-req-1"
                                      :toolName "test-tool"
                                      :toolCallId "tc-1"
                                      :arguments {}})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
            result (get-in (first rpcs) [:params :result])]
        (is (= 1 (count rpcs)))
        (is (map? result))
        (is (= "hello world" (:textResultForLlm result)))
        (is (= "success" (:resultType result)))))))

(deftest test-tool-result-nil-normalized
  (testing "tool handler returning nil is normalized to failure result"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.tools.handlePendingToolCall" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [{:tool-name "nil-tool"
                                                :tool-handler (fn [_args _inv] nil)}]})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "external_tool.requested"
                                     {:requestId "tool-req-2"
                                      :toolName "nil-tool"
                                      :toolCallId "tc-2"
                                      :arguments {}})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
            result (get-in (first rpcs) [:params :result])]
        (is (= 1 (count rpcs)))
        (is (map? result))
        (is (= "Tool returned no result" (:textResultForLlm result)))
        (is (= "failure" (:resultType result)))))))

(deftest test-tool-result-structured-object
  (testing "tool handler returning structured ToolResultObject is forwarded correctly"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.tools.handlePendingToolCall" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [{:tool-name "struct-tool"
                                                :tool-handler (fn [_args _inv]
                                                                {:text-result-for-llm "all good"
                                                                 :result-type "success"
                                                                 :tool-telemetry {:latency-ms 42}})}]})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "external_tool.requested"
                                     {:requestId "tool-req-3"
                                      :toolName "struct-tool"
                                      :toolCallId "tc-3"
                                      :arguments {}})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
            result (get-in (first rpcs) [:params :result])]
        (is (= 1 (count rpcs)))
        (is (map? result))
        (is (= "all good" (:textResultForLlm result)))
        (is (= "success" (:resultType result)))
        (is (= 42 (get-in result [:toolTelemetry :latencyMs])))))))

;; -----------------------------------------------------------------------------
;; Session RPC Wrapper Tests (experimental session APIs)
;; -----------------------------------------------------------------------------

(deftest test-mode-get
  (testing "mode-get calls session.mode.get RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/mode-get session)]
        (is (some? result))
        (is (= "interactive" (:mode result)))
        (is (some #(= "session.mode.get" (:method %)) @requests))))))

(deftest test-mode-set
  (testing "mode-set! calls session.mode.set RPC with mode param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mode-set! session "plan")
      (let [mode-rpcs (filter #(= "session.mode.set" (:method %)) @requests)]
        (is (= 1 (count mode-rpcs)))
        (is (= "plan" (:mode (:params (first mode-rpcs)))))))))

(deftest test-plan-read
  (testing "plan-read calls session.plan.read RPC and returns normalized shape"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/plan-read session)]
        (is (some? result))
        (is (some #(= "session.plan.read" (:method %)) @requests))
        ;; Mock returns {:exists false :content nil :filePath nil}
        ;; plan-read renames :exists → :exists? and wire->clj converts :filePath → :file-path
        (is (contains? result :exists?) ":exists key should be renamed to :exists?")
        (is (false? (:exists? result)))
        (is (nil? (:content result)))))))

(deftest test-plan-update
  (testing "plan-update! calls session.plan.update RPC with content"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/plan-update! session "# My Plan\n\nStep 1: ...")
      (let [plan-rpcs (filter #(= "session.plan.update" (:method %)) @requests)]
        (is (= 1 (count plan-rpcs)))
        (is (= "# My Plan\n\nStep 1: ..." (:content (:params (first plan-rpcs)))))))))

(deftest test-plan-delete
  (testing "plan-delete! calls session.plan.delete RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/plan-delete! session)
      (is (some #(= "session.plan.delete" (:method %)) @requests)))))

(deftest test-workspace-list-files
  (testing "workspace-list-files calls session.workspace.listFiles RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/workspace-list-files session)]
        (is (some? result))
        (is (some #(= "session.workspace.listFiles" (:method %)) @requests))))))

(deftest test-workspace-read-file
  (testing "workspace-read-file calls session.workspace.readFile RPC with path"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-read-file session "notes.md")
      (let [rpcs (filter #(= "session.workspace.readFile" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "notes.md" (:path (:params (first rpcs)))))))))

(deftest test-workspace-create-file
  (testing "workspace-create-file! calls session.workspace.createFile RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-create-file! session "test.txt" "content here")
      (let [rpcs (filter #(= "session.workspace.createFile" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "test.txt" (:path (:params (first rpcs)))))
        (is (= "content here" (:content (:params (first rpcs)))))))))

(deftest test-agent-list
  (testing "agent-list calls session.agent.list RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/agent-list session)]
        (is (some? result))
        (is (some #(= "session.agent.list" (:method %)) @requests))))))

(deftest test-agent-select
  (testing "agent-select! calls session.agent.select RPC with agent name"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-select! session "researcher")
      (let [rpcs (filter #(= "session.agent.select" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "researcher" (:name (:params (first rpcs)))))))))

(deftest test-agent-deselect
  (testing "agent-deselect! calls session.agent.deselect RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-deselect! session)
      (is (some #(= "session.agent.deselect" (:method %)) @requests)))))

(deftest test-fleet-start
  (testing "fleet-start! calls session.fleet.start RPC with session-id forced"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Pass params that attempt to override session-id
      (session/fleet-start! session {:prompt "do stuff" :session-id "evil-override"})
      (let [rpcs (filter #(= "session.fleet.start" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        ;; Session-id must be the real one, not the override
        (is (= session-id (:sessionId (:params (first rpcs)))))
        (is (= "do stuff" (:prompt (:params (first rpcs)))))))))

(deftest test-mcp-config-list
  (testing "mcp-config-list calls mcp.config.list RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-list *test-client*)]
      (is (some? result))
      (is (some #(= "mcp.config.list" (:method %)) @requests)))))

(deftest test-mcp-config-add
  (testing "mcp-config-add! calls mcp.config.add RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-add! *test-client*
                                         {:name "my-server" :command "npx" :args ["-y" "server"]})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.add" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-mcp-config-update
  (testing "mcp-config-update! calls mcp.config.update RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-update! *test-client*
                                            {:name "my-server" :tools ["read_file"]})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.update" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-mcp-config-remove
  (testing "mcp-config-remove! calls mcp.config.remove RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-remove! *test-client* {:name "my-server"})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.remove" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-agent-get-current
  (testing "agent-get-current calls session.agent.getCurrent RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/agent-get-current session)]
      (is (some? result))
      (is (some #(= "session.agent.getCurrent" (:method %)) @requests)))))

(deftest test-agent-reload
  (testing "agent-reload! calls session.agent.reload RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-reload! session)
      (is (some #(= "session.agent.reload" (:method %)) @requests)))))

;; ---------------------------------------------------------------------------
;; v0.2.2 sync tests
;; ---------------------------------------------------------------------------

(deftest test-enable-config-discovery-on-wire
  (testing "enableConfigDiscovery is forwarded in session.create (upstream PR #1044)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-config-discovery true})
          create-params (get @seen "session.create")]
      (is (true? (:enableConfigDiscovery create-params)))))

  (testing "enableConfigDiscovery is forwarded in session.resume (upstream PR #1044)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-config-discovery false})
          resume-params (get @seen "session.resume")]
      (is (false? (:enableConfigDiscovery resume-params)))))

  (testing "enableConfigDiscovery is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableConfigDiscovery))))))

(deftest test-model-capabilities-on-wire
  (testing "modelCapabilities is forwarded in session.create (upstream PR #1029)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model-capabilities {:model-supports {:supports-vision true}}})
          create-params (get @seen "session.create")]
      (is (= true (get-in create-params [:modelCapabilities :modelSupports :supportsVision])))))

  (testing "modelCapabilities is forwarded in session.resume (upstream PR #1029)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model-capabilities {:model-supports {:supports-reasoning-effort true}}})
          resume-params (get @seen "session.resume")]
      (is (= true (get-in resume-params [:modelCapabilities :modelSupports :supportsReasoningEffort])))))

  (testing "modelCapabilities is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :modelCapabilities))))))

(deftest test-switch-model-with-model-capabilities
  (testing "switch-model! forwards modelCapabilities (upstream PR #1029)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4"
                               {:model-capabilities {:model-supports {:supports-vision false}}})]
      (is (= false (get-in @captured-params [:modelCapabilities :modelSupports :supportsVision])))))

  (testing "set-model! forwards modelCapabilities (alias for switch-model!)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/set-model! session "gpt-5.4"
                            {:model-capabilities {:model-supports {:supports-vision true}}})]
      (is (= true (get-in @captured-params [:modelCapabilities :modelSupports :supportsVision]))))))

(deftest test-history-compact-rpc-name
  (testing "compaction-compact! uses session.history.compact RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/compaction-compact! session)
      (is (some #(= "session.history.compact" (:method %)) @requests))
      (is (not (some #(= "session.compaction.compact" (:method %)) @requests))))))

(deftest test-history-truncate-rpc
  (testing "history-truncate! calls session.history.truncate RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/history-truncate! session)
      (is (some #(= "session.history.truncate" (:method %)) @requests)))))

(deftest test-sessions-fork-rpc
  (testing "sessions-fork! calls sessions.fork RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/sessions-fork! session)
      (is (some #(= "sessions.fork" (:method %)) @requests)))))

;; =============================================================================
;; Post-v0.2.2 upstream sync tests
;; =============================================================================

;; --- convert-mcp-call-tool-result (upstream PR #1049) -----------------------

(deftest test-convert-mcp-call-tool-result-text
  (testing "converts text content blocks to textResultForLlm"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "Hello"}
                             {:type "text" :text "World"}]})]
      (is (= "Hello\nWorld" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (nil? (:binary-results-for-llm result))))))

(deftest test-convert-mcp-call-tool-result-image
  (testing "converts image content blocks to binaryResultsForLlm"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "image"
                              :data "base64data"
                              :mime-type "image/png"}]})]
      (is (= "" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "base64data" (:data (first (:binary-results-for-llm result)))))
      (is (= "image/png" (:mime-type (first (:binary-results-for-llm result)))))
      (is (= "image" (:type (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-resource
  (testing "converts resource content blocks with text and blob"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///test.txt"
                                         :text "file content"
                                         :blob "blobdata"
                                         :mime-type "text/plain"}}]})]
      (is (= "file content" (:text-result-for-llm result)))
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "blobdata" (:data (first (:binary-results-for-llm result)))))
      (is (= "text/plain" (:mime-type (first (:binary-results-for-llm result)))))
      (is (= "file:///test.txt" (:description (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-error
  (testing "isError maps to failure result-type"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "something failed"}]
                   :is-error true})]
      (is (= "failure" (:result-type result)))
      (is (= "something failed" (:text-result-for-llm result))))))

(deftest test-convert-mcp-call-tool-result-mixed
  (testing "mixed content types are handled correctly"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "preamble"}
                             {:type "image" :data "img" :mime-type "image/jpeg"}
                             {:type "resource" :resource {:uri "f" :text "res-text"}}]})]
      (is (= "preamble\nres-text" (:text-result-for-llm result)))
      (is (= 1 (count (:binary-results-for-llm result)))))))

(deftest test-convert-mcp-call-tool-result-empty
  (testing "empty content array produces empty result"
    (let [result (tools/convert-mcp-call-tool-result {:content []})]
      (is (= "" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (nil? (:binary-results-for-llm result))))))

;; --- MCP config spec renames (upstream PR #1051) ----------------------------

(deftest test-mcp-stdio-server-spec
  (testing "::mcp-stdio-server spec validates local/stdio configs"
    (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                  {:mcp-command "node" :mcp-args ["server.js"] :mcp-tools ["read"]}))
    (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                  {:mcp-command "node" :mcp-args ["server.js"] :mcp-tools ["read"]
                   :mcp-server-type :stdio}))))

(deftest test-mcp-http-server-spec
  (testing "::mcp-http-server spec validates remote/http configs"
    (is (s/valid? :github.copilot-sdk.specs/mcp-http-server
                  {:mcp-server-type :http :mcp-url "https://example.com" :mcp-tools ["*"]}))
    (is (s/valid? :github.copilot-sdk.specs/mcp-http-server
                  {:mcp-server-type :sse :mcp-url "https://example.com" :mcp-tools ["*"]}))))

;; --- Per-agent skills field (upstream PR #995) ------------------------------

(deftest test-custom-agent-skills-spec
  (testing "::custom-agent spec accepts optional :agent-skills field"
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"}))
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-skills ["skill-a" "skill-b"]}))))

(deftest test-custom-agent-skills-on-wire
  (testing "skills field is sent on wire in session.create"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "test-agent"
                                                  :agent-prompt "Hello"
                                                  :agent-skills ["my-skill"]}]})
          create-params (get @seen "session.create")
          agent (first (:customAgents create-params))]
      (is (= ["my-skill"] (:agentSkills agent))))))

;; --- Per-agent model field (upstream PR #1309) ------------------------------

(deftest test-custom-agent-model-spec
  (testing "::custom-agent spec accepts optional :agent-model field"
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"}))
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-model "claude-haiku-4.5"}))
    (is (not (s/valid? :github.copilot-sdk.specs/custom-agent
                       {:agent-name "test" :agent-prompt "You are helpful"
                        :agent-model 42}))
        ":agent-model must be a string when provided")))

(deftest test-custom-agent-model-on-wire
  (testing "model field is sent on wire in session.create and session.resume (upstream PR #1309)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "haiku-agent"
                                                  :agent-prompt "Hello"
                                                  :agent-model "claude-haiku-4.5"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "haiku-agent-2"
                                                  :agent-prompt "Hi"
                                                  :agent-model "gpt-5.4"}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= "claude-haiku-4.5"
             (get-in create-params [:customAgents 0 :agentModel])))
      (is (= "gpt-5.4"
             (get-in resume-params [:customAgents 0 :agentModel]))))))

(deftest test-custom-agent-model-omitted-when-not-set
  (testing ":agent-model is omitted from wire when not provided"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "no-model"
                                                  :agent-prompt "Hi"}]})
          agent (first (get-in @seen ["session.create" :customAgents]))]
      (is (not (contains? agent :agentModel))))))

;; --- Default agent config (upstream PR #1098) --------------------------------

(deftest test-default-agent-excluded-tools-on-wire
  (testing "session.create forwards defaultAgent.excludedTools"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :default-agent {:excluded-tools ["private_tool" "delegate_only"]}})
          create-params (get @seen "session.create")]
      (is (= ["private_tool" "delegate_only"]
             (get-in create-params [:defaultAgent :excludedTools])))))

  (testing "session.resume forwards defaultAgent.excludedTools"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client*
                                                         {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :default-agent {:excluded-tools ["private_tool"]}})
          resume-params (get @seen "session.resume")]
      (is (= ["private_tool"]
             (get-in resume-params [:defaultAgent :excludedTools]))))))

;; --- requestPermission behavioral change (upstream PR #1056) ----------------

(deftest test-request-permission-true-on-create
  (testing "session.create always sends requestPermission: true"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (true? (:requestPermission create-params))))))

(deftest test-request-permission-false-on-resume-without-handler
  (testing "session.resume sends requestPermission: false with default handler"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/default-join-session-permission-handler})
          resume-params (get @seen "session.resume")]
      (is (false? (:requestPermission resume-params))))))

(deftest test-request-permission-true-on-resume-with-handler
  (testing "session.resume sends requestPermission: true when handler provided"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (true? (:requestPermission resume-params))))))

;; --- New RPC wrappers (CLI 1.0.22 / 1.0.26) --------------------------------

(deftest test-session-name-get
  (testing "session-name-get calls session.name.get RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/session-name-get session)
      (is (some #(= "session.name.get" (:method %)) @requests)))))

(deftest test-session-name-set
  (testing "session-name-set! calls session.name.set RPC with name param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/session-name-set! session "My Session")
      (let [req (first (filter #(= "session.name.set" (:method %)) @requests))]
        (is (some? req))
        (is (= "My Session" (get-in req [:params :name])))))))

(deftest test-workspace-get-workspace
  (testing "workspace-get-workspace calls session.workspaces.getWorkspace RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-get-workspace session)
      (is (some #(= "session.workspaces.getWorkspace" (:method %)) @requests)))))

(deftest test-mcp-discover
  (testing "mcp-discover calls mcp.discover RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mcp-discover session)
      (is (some #(= "mcp.discover" (:method %)) @requests)))))

(deftest test-mcp-discover-with-working-directory
  (testing "mcp-discover forwards working-directory param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mcp-discover session {:working-directory "/tmp"})
      (let [req (first (filter #(= "mcp.discover" (:method %)) @requests))]
        (is (some? req))
        (is (= "/tmp" (get-in req [:params :workingDirectory])))))))

(deftest test-usage-get-metrics
  (testing "usage-get-metrics calls session.usage.getMetrics RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/usage-get-metrics session)
      (is (some #(= "session.usage.getMetrics" (:method %)) @requests)))))

;; --- Remote sessions (upstream PR #1192) -----------------------------------

(deftest test-remote-enable-rpc
  (testing "remote-enable calls session.remote.enable RPC and coerces the result to kebab-case"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/remote-enable session)]
      (is (some #(= "session.remote.enable" (:method %)) @requests))
      (is (= "https://copilot-remote.test/abc" (:url result)))
      (is (= true (:remote-steerable result))
          "wire `remoteSteerable` must arrive on the SDK side as :remote-steerable (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/remote-enable-result result)))))

(deftest test-remote-disable-rpc
  (testing "remote-disable calls session.remote.disable RPC and returns nil"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/remote-disable session)]
      (is (some #(= "session.remote.disable" (:method %)) @requests))
      (is (nil? result)))))

;; --- Remote enable mode parameter (upstream CLI 1.0.48-1, PR #1288) --------

(deftest test-remote-enable-no-mode
  (testing "remote-enable with no args sends no :mode on the wire (back-compat)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (session/remote-enable session)
          req (first (filter #(= "session.remote.enable" (:method %)) @requests))]
      (is (some? req))
      (is (not (contains? (:params req) :mode))
          "no-arg call must not include :mode in wire params"))))

(deftest test-remote-enable-with-mode
  (testing "remote-enable accepts opts {:mode :export} and forwards as wire string"
    (doseq [m [:off :export :on]]
      (let [requests (atom [])
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})))
            session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all})
            _ (session/remote-enable session {:mode m})
            req (first (filter #(= "session.remote.enable" (:method %)) @requests))]
        (is (some? req))
        (is (= (name m) (:mode (:params req)))
            (str ":mode " m " must arrive on the wire as the raw enum string"))))))

(deftest test-remote-enable-mode-spec
  (testing "::remote-session-mode accepts upstream values"
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :off))
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :export))
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :on))
    (is (not (s/valid? :github.copilot-sdk.specs/remote-session-mode :bogus)))))

;; --- session.schedule_created :recurring? (upstream CLI 1.0.48-1) ----------

(deftest test-schedule-created-recurring-field
  (testing "session.schedule_created-data accepts optional :recurring boolean"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping" :recurring true}))
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping" :recurring false}))
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping"})
        "still valid without :recurring for back-compat")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :interval-ms 1000 :prompt "ping" :recurring "yes"}))
        ":recurring must be a boolean if present"))
  (testing "wire-shaped event from wire->clj round-trip validates against idiom spec"
    ;; Real upstream wire data uses `recurring` (csk does NOT append `?`)
    (let [wire-data {:id 1 :intervalMs 1000 :prompt "ping" :recurring true}
          clj-data (util/wire->clj wire-data)]
      (is (= true (:recurring clj-data))
          "wire->clj must produce :recurring (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data clj-data)
          "post-wire->clj event data must validate against the idiom spec"))))

;; --- user.message :is-autopilot-continuation (upstream CLI 1.0.47) ---------

(deftest test-user-message-is-autopilot-continuation-field
  (testing "user.message-data accepts optional :is-autopilot-continuation boolean"
    (is (s/valid? :github.copilot-sdk.specs/user.message-data
                  {:content "hello" :is-autopilot-continuation true}))
    (is (s/valid? :github.copilot-sdk.specs/user.message-data
                  {:content "hello" :is-autopilot-continuation false}))
    (is (s/valid? :github.copilot-sdk.specs/user.message-data {:content "hello"})
        "still valid without :is-autopilot-continuation for back-compat")
    (is (not (s/valid? :github.copilot-sdk.specs/user.message-data
                       {:content "hello" :is-autopilot-continuation "no"}))
        ":is-autopilot-continuation must be boolean if present"))
  (testing "wire-shaped event from wire->clj round-trip validates against idiom spec"
    (let [wire-data {:content "hi" :isAutopilotContinuation true}
          clj-data (util/wire->clj wire-data)]
      (is (= true (:is-autopilot-continuation clj-data))
          "wire->clj must produce :is-autopilot-continuation (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/user.message-data clj-data)
          "post-wire->clj event data must validate against the idiom spec"))))

;; --- assistant.usage :api-endpoint (upstream CLI 1.0.47) -------------------

(deftest test-assistant-usage-api-endpoint-field
  (testing "assistant.usage-data accepts optional :api-endpoint string (open enum)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/chat/completions"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/v1/messages"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/responses"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "ws:/responses"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/future-unknown"})
        "open enum: unknown future strings should validate (forward-compat)")
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data {:model "gpt-5"})
        "still valid without :api-endpoint")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :api-endpoint 42}))
        ":api-endpoint must be a string if present")))

;; --- Memory permission event data specs (CLI 1.0.22) -----------------------

(deftest test-memory-permission-event-specs
  (testing "memory action/direction/reason specs exist and validate"
    (is (s/valid? :github.copilot-sdk.specs/memory-action :store))
    (is (s/valid? :github.copilot-sdk.specs/memory-action :vote))
    (is (not (s/valid? :github.copilot-sdk.specs/memory-action :invalid)))
    (is (s/valid? :github.copilot-sdk.specs/memory-direction :upvote))
    (is (s/valid? :github.copilot-sdk.specs/memory-direction :downvote))
    (is (s/valid? :github.copilot-sdk.specs/memory-reason "some reason"))))

;; --- includeSubAgentStreamingEvents wire flag (upstream PR #1108) ---------

(deftest test-include-sub-agent-streaming-events-on-wire
  (testing "includeSubAgentStreamingEvents defaults to true in session.create (upstream PR #1108)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (true? (:includeSubAgentStreamingEvents create-params)))))

  (testing "includeSubAgentStreamingEvents=false is forwarded in session.create (upstream PR #1108)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :include-sub-agent-streaming-events? false})
          create-params (get @seen "session.create")]
      (is (false? (:includeSubAgentStreamingEvents create-params)))))

  (testing "includeSubAgentStreamingEvents defaults to true in session.resume (upstream PR #1108)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (true? (:includeSubAgentStreamingEvents resume-params)))))

  (testing "includeSubAgentStreamingEvents=false is forwarded in session.resume (upstream PR #1108)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :include-sub-agent-streaming-events? false})
          resume-params (get @seen "session.resume")]
      (is (false? (:includeSubAgentStreamingEvents resume-params))))))

;; --- requestHeaders on send! (upstream PR #1094) --------------------------

(deftest test-send-request-headers-on-wire
  (testing "send! forwards :request-headers as wire :requestHeaders (upstream PR #1094)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "Hi"
                                :request-headers {"X-Trace-Id" "abc-123"
                                                  "X-Custom" "value"}})
          send-params (get @seen "session.send")]
      (is (= "abc-123" (get-in send-params [:requestHeaders (keyword "X-Trace-Id")])))
      (is (= "value" (get-in send-params [:requestHeaders (keyword "X-Custom")])))))

  (testing "send! omits requestHeaders when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "Hi"})
          send-params (get @seen "session.send")]
      (is (not (contains? send-params :requestHeaders)))))

  (testing "send-async forwards :request-headers as wire :requestHeaders (upstream PR #1094)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send-async session {:prompt "Hi"
                                     :request-headers {"X-Trace-Id" "xyz-789"}})
          ;; Poll for the async RPC to land rather than fixed sleep (avoids flakes under load).
          deadline (+ (System/currentTimeMillis) 2000)]
      (while (and (nil? (get @seen "session.send"))
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 10))
      (let [send-params (get @seen "session.send")]
        (is (some? send-params) "async send should have issued session.send within deadline")
        (is (= "xyz-789" (get-in send-params [:requestHeaders (keyword "X-Trace-Id")])))))))

;; --- ProviderConfig headers (upstream PR #1094) ---------------------------

(deftest test-provider-headers-on-wire
  (testing "Provider :headers field is forwarded in session.create (upstream PR #1094)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5"
                                 :provider {:base-url "https://example.com"
                                            :headers {"X-Org" "acme"}}})
          create-params (get @seen "session.create")]
      (is (= "acme" (get-in create-params [:provider :headers (keyword "X-Org")]))))))

;; --- Spec-only additions (event data fields, upstream 1.0.28 / 1.0.32 / #1108) ---

(deftest test-spec-can-offer-session-approval
  (testing ":can-offer-session-approval is a valid boolean spec (upstream 1.0.28)"
    (is (s/valid? :github.copilot-sdk.specs/can-offer-session-approval true))
    (is (s/valid? :github.copilot-sdk.specs/can-offer-session-approval false))
    (is (not (s/valid? :github.copilot-sdk.specs/can-offer-session-approval "yes")))))

(deftest test-spec-reasoning-tokens
  (testing ":reasoning-tokens is a non-negative integer spec (upstream 1.0.32)"
    (is (s/valid? :github.copilot-sdk.specs/reasoning-tokens 0))
    (is (s/valid? :github.copilot-sdk.specs/reasoning-tokens 1234))
    (is (not (s/valid? :github.copilot-sdk.specs/reasoning-tokens -1)))
    (is (not (s/valid? :github.copilot-sdk.specs/reasoning-tokens "100")))))

(deftest test-spec-agent-id-on-base-event
  (testing ":agent-id is accepted as an optional string on base events (upstream PR #1108)"
    (let [evt {:event-id "evt-1"
               :event-timestamp "2026-04-20T10:00:00Z"
               :parent-id nil
               :agent-id "subagent-42"}]
      (is (s/valid? :github.copilot-sdk.specs/base-event evt)))
    (let [evt-no-agent {:event-id "evt-2"
                        :event-timestamp "2026-04-20T10:00:00Z"
                        :parent-id nil}]
      (is (s/valid? :github.copilot-sdk.specs/base-event evt-no-agent)))))

;; =============================================================================
;; v1.0.0-beta.3 upstream sync tests
;; =============================================================================

;; --- enableSessionTelemetry (upstream PR #1224) -----------------------------

(deftest test-enable-session-telemetry-on-wire
  (testing "enableSessionTelemetry is forwarded in session.create when true"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? true})
          create-params (get @seen "session.create")]
      (is (true? (:enableSessionTelemetry create-params)))))

  (testing "enableSessionTelemetry is forwarded in session.create when false"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? false})
          create-params (get @seen "session.create")]
      (is (false? (:enableSessionTelemetry create-params)))))

  (testing "enableSessionTelemetry is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableSessionTelemetry)))))

  (testing "enableSessionTelemetry is forwarded in session.resume"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? false})
          resume-params (get @seen "session.resume")]
      (is (false? (:enableSessionTelemetry resume-params))))))

;; --- Exit Plan Mode handler (upstream PR #1228) -----------------------------

(deftest test-request-exit-plan-mode-wire-flag
  (testing "requestExitPlanMode is true when :on-exit-plan-mode is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :on-exit-plan-mode (fn [_req _ctx] {:approved? true})})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (is (true? (:requestExitPlanMode (:params (first create-rpcs)))))))

  (testing "requestExitPlanMode is false when no handler"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (false? (:requestExitPlanMode (:params (first create-rpcs))))))))

(deftest test-exit-plan-mode-handler-invoked
  (testing "exitPlanMode.request calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-exit-plan-mode
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         {:approved? true :selected-action "continue" :feedback "ok"})})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "exitPlanMode.request"
                                           {:sessionId session-id
                                            :summary "Plan summary"
                                            :planContent "1. Step\n2. Step"
                                            :actions ["continue" "abort"]
                                            :recommendedAction "continue"})]
      (is (some? @handler-called))
      (is (= "Plan summary" (get-in @handler-called [:request :summary])))
      (is (= ["continue" "abort"] (get-in @handler-called [:request :actions])))
      (is (= "continue" (get-in @handler-called [:request :recommended-action])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      (is (true? (get-in response [:result :approved])))
      (is (= "continue" (get-in response [:result :selectedAction])))
      (is (= "ok" (get-in response [:result :feedback]))))))

(deftest test-exit-plan-mode-no-handler-default-approves
  (testing "exitPlanMode.request without handler returns {:approved true}"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "exitPlanMode.request"
                                           {:sessionId session-id
                                            :summary "p"
                                            :actions ["go"]
                                            :recommendedAction "go"})]
      (is (true? (get-in response [:result :approved]))))))

;; --- Auto Mode Switch handler (upstream PR #1228) ---------------------------

(deftest test-request-auto-mode-switch-wire-flag
  (testing "requestAutoModeSwitch is true when :on-auto-mode-switch is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :on-auto-mode-switch (fn [_req _ctx] :no)})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (is (true? (:requestAutoModeSwitch (:params (first create-rpcs)))))))

  (testing "requestAutoModeSwitch is false when no handler"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (false? (:requestAutoModeSwitch (:params (first create-rpcs))))))))

(deftest test-auto-mode-switch-handler-invoked
  (testing "autoModeSwitch.request calls handler; response wrapped in {response}"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-auto-mode-switch
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         :yes-always)})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id
                                            :errorCode "rate_limited"
                                            :retryAfterSeconds 60})]
      (is (some? @handler-called))
      (is (= "rate_limited" (get-in @handler-called [:request :error-code])))
      (is (= 60 (get-in @handler-called [:request :retry-after-seconds])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      (is (= "yes_always" (get-in response [:result :response]))))))

(deftest test-auto-mode-switch-handler-string-response
  (testing "handler may return wire string directly"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-auto-mode-switch (fn [_ _] "yes")})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id})]
      (is (= "yes" (get-in response [:result :response]))))))

(deftest test-auto-mode-switch-no-handler-default-no
  (testing "autoModeSwitch.request without handler returns {:response \"no\"}"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id})]
      (is (= "no" (get-in response [:result :response]))))))

;; --- MCP binary tool result mime-type fallback (upstream PR #1222) ----------

(deftest test-convert-mcp-call-tool-result-empty-mime-type
  (testing "empty mime-type string falls back to application/octet-stream"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///x"
                                         :blob "blobdata"
                                         :mime-type ""}}]})]
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "application/octet-stream"
             (:mime-type (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-non-string-mime-type
  (testing "non-string mime-type falls back to application/octet-stream"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///x"
                                         :blob "blobdata"
                                         :mime-type 123}}]})]
      (is (= "application/octet-stream"
             (:mime-type (first (:binary-results-for-llm result))))))))

;; --- AbortReason / SubagentStartedData.model (upstream PR #1225 codegen) -----

(deftest test-abort-reason-enum
  (testing "wire spec for abort reason is a closed enum"
    (is (s/valid? :github.copilot-sdk.generated.event-specs/reason "user_initiated"))
    (is (s/valid? :github.copilot-sdk.generated.event-specs/reason "remote_command"))
    (is (s/valid? :github.copilot-sdk.generated.event-specs/reason "user_abort"))
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/reason "arbitrary_reason")))))

(deftest test-subagent-started-model-field
  (testing "idiom ::subagent.started-data spec accepts optional :model"
    (is (s/valid? :github.copilot-sdk.specs/subagent.started-data
                  {:tool-call-id "tc-1"
                   :agent-name "rubber-duck"
                   :agent-display-name "Rubber Duck"
                   :model "gpt-5.4"}))
    (is (s/valid? :github.copilot-sdk.specs/subagent.started-data
                  {:tool-call-id "tc-1"
                   :agent-name "rubber-duck"
                   :agent-display-name "Rubber Duck"}))))
