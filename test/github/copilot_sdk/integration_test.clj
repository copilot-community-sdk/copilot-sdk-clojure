(ns github.copilot-sdk.integration-test
  "Integration tests using mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
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
      (is (= 3 (:protocol-version result)))
      ;; Upstream PR #1340 / CLI 1.0.51 changed timestamp from epoch number
      ;; to ISO 8601 string (`timestamp: string, format: date-time`).
      (is (string? (:timestamp result)))
      (is (some? (java.time.Instant/parse (:timestamp result)))
          ":timestamp parses as ISO 8601 instant")))
  (testing "::specs/timestamp accepts both ISO string (CLI ≥ 1.0.51) and epoch-millis number (older CLIs)"
    (is (s/valid? :github.copilot-sdk.specs/timestamp "2026-05-21T08:00:00.000Z"))
    (is (s/valid? :github.copilot-sdk.specs/timestamp (System/currentTimeMillis))
        "System/currentTimeMillis-sized long validates as epoch-ms")
    (is (s/valid? :github.copilot-sdk.specs/timestamp 1700000000000)
        "representative epoch-ms long validates")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp -1))
        "epoch-ms must be non-negative")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp 1.5))
        "epoch-ms must be an integer, not arbitrary number")))

(deftest test-get-status
  (testing "Get CLI status returns version and protocol"
    (let [result (sdk/get-status *test-client*)]
      (is (string? (:version result)))
      (is (= 3 (:protocol-version result))))))

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

(deftest test-log-returns-event-id
  (testing "log! returns a non-empty event-id string"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (doseq [[desc args] [["message only" ["Processing started"]]
                           ["with level + ephemeral options" ["Something went wrong" {:level "error" :ephemeral? true}]]]]
        (testing desc
          (let [event-id (apply sdk/log! session args)]
            (is (string? event-id))
            (is (seq event-id))))))))

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

(deftest test-<send-and-wait!-returns-final-event
  (testing "<send-and-wait! delivers the final assistant.message EVENT (not just content)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)]
      (with-redefs [protocol/send-request (fn [_ _ _]
                                            (let [ch (async/chan 1)]
                                              (async/put! ch {:result {:message-id "msg-id"}})
                                              (async/close! ch)
                                              ch))]
        (let [result-ch (sdk/<send-and-wait! session {:prompt "Q"})]
          (Thread/sleep 50)
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "first" :message-id "m1"}})
          (session/dispatch-event! client session-id
                                   {:type :copilot/assistant.message
                                    :data {:content "final answer" :message-id "m2"}})
          (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
          (let [[result _] (alts!! [result-ch (timeout 2000)])]
            (is (= :copilot/assistant.message (:type result))
                "<send-and-wait! should deliver the full event map, not just content")
            (is (= "final answer" (get-in result [:data :content]))
                "<send-and-wait! should deliver the LAST assistant.message")
            (is (= "m2" (get-in result [:data :message-id])))))))))

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
      (sdk/unsubscribe-events! session events-ch))))

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

;; v2 `tool.call` / `permission.request` RPC dispatcher tests were removed
;; alongside upstream PR #1378, which raised the minimum protocol version to
;; 3 and deleted the v2 server→client RPC adapters. The v3 broadcast event
;; equivalents (`external_tool.requested`, `permission.requested`) are
;; covered by `test-permission-no-result-v3` and the broadcast event tests
;; further down in this file.

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

(deftest test-provider-config-type-and-azure-wire-keys
  (testing "ProviderConfig :provider-type/:azure-options serialize with upstream wire keys
            (type/azure/apiVersion), not camelCased SDK names (parity with nodejs ProviderConfig)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          provider {:provider-type :azure
                    :wire-api :responses
                    :base-url "https://my-resource.openai.azure.com"
                    :api-key "key"
                    :bearer-token "tok"
                    :azure-options {:azure-api-version "2024-02-01"}}
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})]
      (doseq [method ["session.create" "session.resume"]]
        (let [p (get-in @seen [method :provider])]
          (testing method
            (is (= "azure" (:type p)) "wire key must be `type`")
            (is (not (contains? p :providerType)) "SDK key `providerType` must NOT leak onto wire")
            (is (= {:apiVersion "2024-02-01"} (:azure p)) "wire key must be `azure` with `apiVersion`")
            (is (not (contains? p :azureOptions)) "SDK key `azureOptions` must NOT leak onto wire")
            (is (not (contains? (:azure p) :azureApiVersion)) "nested `azureApiVersion` must NOT leak")
            ;; Correctly-camelCased fields must still survive alongside the remaps.
            (is (= "responses" (:wireApi p)))
            (is (= "https://my-resource.openai.azure.com" (:baseUrl p)))
            (is (= "tok" (:bearerToken p)))))))))

(deftest test-provider-config-absent-azure-stays-absent
  (testing "ProviderConfig without :azure-options must not emit a wire `azure` key"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= method "session.create")
                                                      (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:provider-type :anthropic
                                            :base-url "https://api.anthropic.com"
                                            :api-key "key"}})
          p (get-in @seen [:provider])]
      (is (= "anthropic" (:type p)))
      (is (not (contains? p :providerType)))
      (is (not (contains? p :azure)))
      (is (not (contains? p :azureOptions))))))

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
    (let [normalize @#'protocol/normalize-incoming
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
    (let [normalize @#'protocol/normalize-incoming
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

(deftest test-cloud-session-config-forwarded-on-wire
  (testing "cloud {:repository {...}} is forwarded as cloud.repository on session.create (upstream PR #1306)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {:repository {:owner "octocat"
                                                      :name "hello-world"
                                                      :branch "main"}}})
          create-params (get @seen "session.create")]
      (is (= {:owner "octocat" :name "hello-world" :branch "main"}
             (get-in create-params [:cloud :repository])))
      (is (not (contains? create-params :cloud-repository)))))

  (testing "cloud :repository may omit :branch"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {:repository {:owner "octocat"
                                                      :name "hello-world"}}})
          create-params (get @seen "session.create")]
      (is (= {:owner "octocat" :name "hello-world"}
             (get-in create-params [:cloud :repository])))))

  (testing "cloud {} (empty options) is forwarded as empty map"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {}})
          create-params (get @seen "session.create")]
      (is (= {} (:cloud create-params)))))

  (testing "cloud is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :cloud)))))

  (testing "cloud config with invalid :repository shape is rejected by spec"
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :cloud {:repository {:branch "main"}}}))) ; missing :owner and :name
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :cloud {:repository "octocat/hello-world"}})))))

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

(deftest test-defer-on-wire
  ;; Upstream PR #1632: tool definitions accept an optional `defer` of
  ;; "auto" | "never". The idiom uses keywords (:auto / :never) and the
  ;; keyword is converted to its wire string via (name kw).
  (testing "defer is sent on the wire on session.create (keyword -> string)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :auto
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (= "auto" (:defer wire-tool))
          "defer :auto must be sent as the wire string \"auto\"")))

  (testing "defer :never is sent as the wire string \"never\""
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :never
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (= "never" (:defer wire-tool))
          "defer :never must be sent as the wire string \"never\"")))

  (testing "defer is absent on the wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "my_tool" {:description "A tool" :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (not (contains? wire-tool :defer))
          "defer should be absent when not set")))

  (testing "defer is sent on the wire on session.resume"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :auto
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.resume")))]
      (is (some? wire-tool) "tool should be present in resume wire payload")
      (is (= "auto" (:defer wire-tool))
          "defer must be sent on session.resume too"))))

(deftest test-defer-spec
  (testing "::tool accepts :defer :auto and :never"
    (is (s/valid? ::specs/tool {:tool-name "t" :defer :auto}))
    (is (s/valid? ::specs/tool {:tool-name "t" :defer :never})))
  (testing "::tool rejects an invalid or non-keyword :defer"
    (is (not (s/valid? ::specs/tool {:tool-name "t" :defer "auto"}))
        "wire string is not a valid idiom value")
    (is (not (s/valid? ::specs/tool {:tool-name "t" :defer :bogus}))
        ":bogus is not a member of #{:auto :never}")))

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

(deftest test-permission-handler-now-optional
  ;; Upstream PR #1308 made :on-permission-request optional. Previously this
  ;; test asserted that create-session/resume-session threw without it.
  (testing "create-session succeeds without :on-permission-request"
    (is (some? (sdk/create-session *test-client* {}))))

  (testing "resume-session succeeds without :on-permission-request"
    (let [_ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)]
      (is (some? (sdk/resume-session *test-client* session-id {}))))))

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

;; ---------------------------------------------------------------------------
;; MCP OAuth lifecycle (upstream PR #1669) — :on-mcp-auth-request handler.
;; The runtime broadcasts `mcp.oauth_required` only to consumers that
;; register interest; the SDK registers interest on create/resume when a
;; handler is configured, then answers each request via
;; `session.mcp.oauth.handlePendingRequest`.
;; ---------------------------------------------------------------------------

(deftest test-mcp-oauth-register-interest-on-create
  (testing "create-session with :on-mcp-auth-request registers interest in mcp.oauth_required"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [_request _ctx] {:kind :cancelled})})
          register-rpcs (filter #(= "session.eventLog.registerInterest" (:method %))
                                @requests)]
      (is (sdk/session-id session))
      (is (= 1 (count register-rpcs))
          "exactly one registerInterest RPC should be sent on create")
      (is (= "mcp.oauth_required" (:eventType (:params (first register-rpcs))))
          "registerInterest should target the mcp.oauth_required event type"))))

(deftest test-mcp-oauth-no-register-interest-without-handler
  (testing "create-session without :on-mcp-auth-request does not register interest"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _session (sdk/create-session *test-client* {})]
      (is (empty? (filter #(= "session.eventLog.registerInterest" (:method %)) @requests))
          "no registerInterest RPC when no MCP auth handler is configured"))))

(deftest test-mcp-oauth-token-result-v3
  (testing "v3 mcp.oauth_required handler returning a token responds via handlePendingRequest"
    (let [received-request (atom nil)
          requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [request _ctx]
                                         (reset! received-request request)
                                         {:kind :token
                                          :access-token "tok-abc"
                                          :token-type "Bearer"
                                          :expires-in 3600})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-1"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      ;; Handler received the idiomatic (kebab-cased) request data
      (is (= "mcp-req-1" (:request-id @received-request)))
      (is (= "my-mcp" (:server-name @received-request)))
      (is (= "initial" (:reason @received-request)))
      (let [rpcs (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests)]
        (is (= 1 (count rpcs)) "exactly one handlePendingRequest RPC should be sent")
        (let [params (:params (first rpcs))]
          (is (= "mcp-req-1" (:requestId params)) "RPC carries the request-id")
          ;; Result is wire-shaped: string kind, camelCased token fields
          (is (= "token" (get-in params [:result :kind])))
          (is (= "tok-abc" (get-in params [:result :accessToken])))
          (is (= "Bearer" (get-in params [:result :tokenType])))
          (is (= 3600 (get-in params [:result :expiresIn]))))))))

(deftest test-mcp-oauth-cancel-on-nil-v3
  (testing "v3 mcp.oauth_required handler returning nil cancels the request"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request (fn [_request _ctx] nil)})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-2"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "reauth"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      (let [rpcs (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "cancelled" (get-in (:params (first rpcs)) [:result :kind]))
            "nil handler result should cancel the pending request")))))

(deftest test-mcp-oauth-no-handler-leaves-pending-v3
  (testing "v3 mcp.oauth_required without a handler sends no handlePendingRequest RPC"
    (let [requests (atom [])
          event-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-event
                                       (fn [event]
                                         (when (= :copilot/mcp.oauth_required (:type event))
                                           (.countDown event-latch)))})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-3"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await event-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for mcp.oauth_required event delivery")
      (is (empty? (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests))
          "no handlePendingRequest RPC when no MCP auth handler is registered"))))

(deftest test-mcp-oauth-register-interest-before-resume
  (testing "resume-session registers interest BEFORE session.resume (upstream client.ts:1578)"
    ;; Upstream registers `mcp.oauth_required` interest *before* the
    ;; `session.resume` RPC so OAuth the runtime needs while processing resume
    ;; (e.g. MCP servers reconnecting) reaches the handler instead of silently
    ;; falling back to a cached token.
    (let [handler (fn [_request _ctx] {:kind :cancelled})
          _ (sdk/create-session *test-client* {:on-mcp-auth-request handler})
          session-id (sdk/get-last-session-id *test-client*)
          order (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (#{"session.eventLog.registerInterest"
                                               "session.resume"} method)
                                        (swap! order conj method))))
          _ (sdk/resume-session *test-client* session-id {:on-mcp-auth-request handler})
          methods @order
          reg-idx (.indexOf methods "session.eventLog.registerInterest")
          resume-idx (.indexOf methods "session.resume")]
      (is (nat-int? reg-idx) "registerInterest RPC should be sent on resume")
      (is (nat-int? resume-idx) "session.resume RPC should be sent")
      (is (< reg-idx resume-idx)
          "registerInterest must precede session.resume"))))

(deftest test-mcp-oauth-register-interest-before-resume-async
  (testing "<resume-session registers interest BEFORE session.resume"
    (let [handler (fn [_request _ctx] {:kind :cancelled})
          _ (sdk/create-session *test-client* {:on-mcp-auth-request handler})
          session-id (sdk/get-last-session-id *test-client*)
          order (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (#{"session.eventLog.registerInterest"
                                               "session.resume"} method)
                                        (swap! order conj method))))
          _ (async/<!! (sdk/<resume-session *test-client* session-id
                                            {:on-mcp-auth-request handler}))
          methods @order
          reg-idx (.indexOf methods "session.eventLog.registerInterest")
          resume-idx (.indexOf methods "session.resume")]
      (is (nat-int? reg-idx) "registerInterest RPC should be sent on async resume")
      (is (nat-int? resume-idx) "session.resume RPC should be sent")
      (is (< reg-idx resume-idx)
          "registerInterest must precede session.resume on the async path"))))

(deftest test-mcp-oauth-register-interest-failure-rejects-create
  (testing "a failed registerInterest RPC fails create-session (upstream awaits + rejects)"
    ;; Upstream `await`s registerInterest and lets a transport failure reject
    ;; session creation rather than silently degrading to cached-token fallback.
    (let [_ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (= "session.eventLog.registerInterest" method)
                                        (throw (ex-info "registerInterest boom"
                                                        {:code -32603})))))]
      (is (thrown? Exception
                   (sdk/create-session *test-client*
                                       {:on-mcp-auth-request (fn [_ _] {:kind :cancelled})}))
          "create-session should propagate a registerInterest RPC failure"))))

(deftest test-mcp-oauth-bare-token-result-v3
  (testing "v3 handler returning a map with :access-token but no :kind still maps to token; explicit nil optional fields are omitted"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [_request _ctx] {:access-token "bare-tok"
                                                            :token-type nil
                                                            :expires-in nil})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-bare"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      (let [params (:params (first (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %))
                                           @requests)))]
        (is (= "token" (get-in params [:result :kind])))
        (is (= "bare-tok" (get-in params [:result :accessToken])))
        (is (not (contains? (:result params) :tokenType))
            "nil :token-type must not serialize to tokenType: null")
        (is (not (contains? (:result params) :expiresIn))
            "nil :expires-in must not serialize to expiresIn: null")))))

(deftest test-mcp-oauth-thrown-and-non-map-results-cancel-v3
  (testing "v3 handler that throws, returns a non-map, or a nil access token cancels the request"
    (doseq [[label handler] [["thrown" (fn [_ _] (throw (ex-info "nope" {})))]
                             ["non-map" (fn [_ _] "not-a-result")]
                             ["nil-token" (fn [_ _] {:access-token nil})]]]
      (let [requests (atom [])
            rpc-latch (java.util.concurrent.CountDownLatch. 1)
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})
                                        (when (= "session.mcp.oauth.handlePendingRequest" method)
                                          (.countDown rpc-latch))))
            session (sdk/create-session *test-client* {:on-mcp-auth-request handler})
            session-id (sdk/session-id session)]
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        (reset! requests [])
        (mock/send-v3-broadcast-event! *mock-server* session-id
                                       "mcp.oauth_required"
                                       {:requestId (str "mcp-req-" label)
                                        :serverName "my-mcp"
                                        :serverUrl "https://mcp.example/sse"
                                        :reason "initial"})
        (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
            (str label ": timed out waiting for handlePendingRequest RPC"))
        (let [params (:params (first (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %))
                                             @requests)))]
          (is (= "cancelled" (get-in params [:result :kind]))
              (str label " handler result should cancel the pending request")))))))

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
        "tool.execution_start-data should accept mcp-server-name and mcp-tool-name"))
  (testing "tool.execution_start event data allows the :model field (upstream 1.0.57)"
    (is (s/valid? :github.copilot-sdk.specs/tool.execution_start-data
                  {:tool-call-id "tc-1"
                   :tool-name "shell"
                   :model "claude-sonnet-4.6"})
        "tool.execution_start-data should accept the model identifier")))

(deftest test-extensions-attachments-pushed-event-generated
  (testing "session.extensions.attachments_pushed is a known generated event type (upstream schema 1.0.57)"
    (is (contains? github.copilot-sdk.generated.event-specs/event-types "session.extensions.attachments_pushed"))
    (is (some? (s/get-spec :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed))
        "generated envelope spec should be registered"))
  (testing "the generated event envelope validates a well-formed ephemeral payload"
    (is (s/valid? :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed
                  {:type "session.extensions.attachments_pushed"
                   :id "evt-1"
                   :parent-id nil
                   :timestamp "2026-06-02T00:00:00Z"
                   :ephemeral true
                   :data {:attachments [{:type "extension_context"
                                         :title "pill"
                                         :extension-id "ext"
                                         :captured-at "2026-06-02T00:00:00Z"}]}}))))

(deftest test-extension-context-attachment-payload-preserved
  (testing "extension_context attachment :payload survives normalize-incoming on user.message"
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "user.message"
                                    :data {:content "hi"
                                           :attachments [{:type "file"
                                                          :displayName "a.txt"
                                                          :path "/tmp/a.txt"}
                                                         {:type "extension_context"
                                                          :title "My Pill"
                                                          :extensionId "my-ext"
                                                          :payload {:firstName "Foo"
                                                                    :nested {:userId 42}}}]}}}}
          normalized (normalize raw-msg)
          atts (get-in normalized [:params :event :data :attachments])
          ext (nth atts 1)]
      (is (= "extension_context" (:type ext)))
      (is (= "Foo" (get-in ext [:payload :firstName]))
          "payload top-level keys must not be kebab-cased")
      (is (= 42 (get-in ext [:payload :nested :userId]))
          "payload nested keys must not be kebab-cased")
      (is (= "My Pill" (:title ext))
          "non-opaque attachment fields are still converted/preserved")))
  (testing "payload is preserved in historical events from session.getMessages responses"
    (let [normalize @#'protocol/normalize-incoming
          raw-response {:jsonrpc "2.0"
                        :id 7
                        :result {:events [{:type "user.message"
                                           :data {:content "hi"
                                                  :attachments [{:type "extension_context"
                                                                 :title "P"
                                                                 :extensionId "e"
                                                                 :payload {:nestedKey {:userId 7}}}]}}]}}
          normalized (normalize raw-response)
          ext (get-in normalized [:result :events 0 :data :attachments 0])]
      (is (= 7 (get-in ext [:payload :nestedKey :userId]))
          "historical payload keys must not be kebab-cased")))
  (testing "extension_context payload survives on session.extensions.attachments_pushed events"
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "session.extensions.attachments_pushed"
                                    :data {:attachments [{:type "extension_context"
                                                          :title "P"
                                                          :extensionId "e"
                                                          :payload {:camelKey 1}}]}}}}
          normalized (normalize raw-msg)
          ext (get-in normalized [:params :event :data :attachments 0])]
      (is (= 1 (get-in ext [:payload :camelKey]))
          "payload keys must not be kebab-cased on attachments_pushed events"))))

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
;; Upstream schema 1.0.52-4 sync (post-v1.0.0-beta.4 round 5)
;; -----------------------------------------------------------------------------

(deftest test-schema-1-0-52-4-mcp-app-tool-call-complete-event-type
  (testing "mcp_app.tool_call_complete is part of the public ::sdk/event-types set (SEP-1865)"
    (is (contains? sdk/event-types :copilot/mcp_app.tool_call_complete)))
  (testing "mcp_app.tool_call_complete is accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/mcp_app.tool_call_complete))))

(deftest test-schema-1-0-52-4-mcp-app-tool-call-complete-opaque-fields
  (testing "mcp_app.tool_call_complete :arguments and :result preserve source-defined keys verbatim"
    ;; Per upstream schema 1.0.52-4, the MCP App view supplies opaque
    ;; tool arguments and the MCP server returns a standard CallToolResult.
    ;; Both must survive normalize-incoming without csk kebab-casing.
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "mcp_app.tool_call_complete"
                                    :id "evt-1"
                                    :timestamp "2026-05-23T08:00:00.000Z"
                                    :parentId nil
                                    :ephemeral true
                                    :data {:serverName "demo"
                                           :toolName "doThing"
                                           :durationMs 42
                                           :success true
                                           :arguments {:firstName "Foo"
                                                       :nested {:userId 42}}
                                           :result {:isError false
                                                    :content [{:type "text"
                                                               :text "ok"}]
                                                    :customField "preserve"}}}}}
          normalized (normalize raw-msg)
          data (get-in normalized [:params :event :data])]
      (is (= "mcp_app.tool_call_complete" (get-in normalized [:params :event :type])))
      (is (= "demo" (:server-name data))
          "non-opaque fields are kebab-cased")
      (is (= 42 (:duration-ms data)))
      (is (contains? (:arguments data) :firstName)
          ":arguments must preserve camelCase keys verbatim")
      (is (= 42 (get-in data [:arguments :nested :userId]))
          ":arguments nested keys must survive csk")
      (is (contains? (:result data) :isError)
          ":result must preserve camelCase keys verbatim")
      (is (= "preserve" (get-in data [:result :customField]))
          ":result must preserve user-defined keys"))))

(deftest test-schema-1-0-52-4-service-request-id
  (testing "::service-request-id field is propagated through wire->clj on relevant event data specs"
    ;; Upstream schema 1.0.52-4 adds optional `serviceRequestId` (the
    ;; Copilot CAPI x-copilot-service-request-id header) to several
    ;; event-data shapes for correlation with CAPI logs. The generated
    ;; specs accept it via `:opt-un`; we verify roundtrip through
    ;; normalize-incoming.
    (doseq [spec-key [:github.copilot-sdk.generated.event-specs/assistant.message-data
                      :github.copilot-sdk.generated.event-specs/assistant.usage-data
                      :github.copilot-sdk.generated.event-specs/model.call_failure-data
                      :github.copilot-sdk.generated.event-specs/session.compaction_complete-data
                      :github.copilot-sdk.generated.event-specs/session.error-data]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "assistant.usage"
                                    :id "evt-2"
                                    :timestamp "2026-05-23T08:00:00.000Z"
                                    :parentId nil
                                    :data {:model "gpt-5"
                                           :serviceRequestId "svc-req-abc"}}}}
          data (get-in (normalize raw-msg) [:params :event :data])]
      (is (= "svc-req-abc" (:service-request-id data))
          "serviceRequestId must arrive as :service-request-id"))))

(deftest test-schema-1-0-52-4-model-change-context-tier
  (testing "session.model_change accepts :context-tier (default | long_context | nil)"
    ;; Upstream schema 1.0.52-4 adds optional :context-tier to ModelChangeData.
    ;; A literal `null` explicitly clears a previously-selected tier.
    (let [spec :github.copilot-sdk.generated.event-specs/session.model_change-data]
      (is (s/valid? spec {:new-model "gpt-5" :context-tier "default"}))
      (is (s/valid? spec {:new-model "gpt-5" :context-tier "long_context"}))
      (is (s/valid? spec {:new-model "gpt-5" :context-tier nil}))
      (is (s/valid? spec {:new-model "gpt-5"}))
      (is (not (s/valid? spec {:new-model "gpt-5" :context-tier "tiny"}))))))

(deftest test-schema-1-0-52-4-skill-invoked-source-trigger
  (testing "skill.invoked accepts :source and :trigger"
    (let [spec :github.copilot-sdk.generated.event-specs/skill.invoked-data]
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :source "project"
                          :trigger "user-invoked"}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :trigger "agent-invoked"}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :trigger "context-load"}))
      (is (not (s/valid? spec {:name "foo" :path "/x" :content "..."
                               :trigger "bogus"}))))))

(deftest test-schema-1-0-52-4-runtime-instructions-section
  (testing ":runtime-instructions is a known system message section (upstream PR #1377)"
    (is (contains? (set (keys specs/system-prompt-sections)) :runtime-instructions))
    (is (= "runtime_instructions" (util/section-kw->wire-id :runtime-instructions))
        ":runtime-instructions converts to the wire string \"runtime_instructions\"")
    (is (s/valid? :github.copilot-sdk.specs/system-prompt-section :runtime-instructions))
    (is (s/valid? :github.copilot-sdk.specs/system-message-section :runtime-instructions)
        "::system-message-section alias also accepts it"))
  (testing "system-message-sections alias points at the same map (upstream rename)"
    (is (identical? specs/system-prompt-sections specs/system-message-sections))))

(deftest test-schema-1-0-52-4-runtime-instructions-wire-roundtrip
  (testing ":runtime-instructions section survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:runtime-instructions
                                                   {:action :replace
                                                    :content "runtime ctx"}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (contains? wire :runtime_instructions)
          ":runtime-instructions must be sent as wire key :runtime_instructions")
      (is (= "replace" (get-in wire [:runtime_instructions :action])))
      (is (= "runtime ctx" (get-in wire [:runtime_instructions :content]))))))

(deftest test-v1-0-4-preamble-section
  (testing ":preamble is a known system message section (upstream PR #1683)"
    (is (contains? (set (keys specs/system-prompt-sections)) :preamble))
    (is (= "preamble" (util/section-kw->wire-id :preamble))
        ":preamble converts to the wire string \"preamble\"")
    (is (= :preamble (util/wire-id->section-kw "preamble"))
        "\"preamble\" round-trips back to :preamble")
    (is (s/valid? :github.copilot-sdk.specs/system-prompt-section :preamble))
    (is (s/valid? :github.copilot-sdk.specs/system-message-section :preamble)
        "::system-message-section alias also accepts it")))

(deftest test-v1-0-4-preamble-section-wire-roundtrip
  (testing ":preamble section survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:preamble
                                                   {:action :replace
                                                    :content "you are an agent"}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (contains? wire :preamble)
          ":preamble must be sent as wire key :preamble")
      (is (= "replace" (get-in wire [:preamble :action])))
      (is (= "you are an agent" (get-in wire [:preamble :content]))))))

(deftest test-v1-0-4-preserve-section-action
  (testing ":preserve is a valid static section action (upstream PR #1713)"
    (is (s/valid? :github.copilot-sdk.specs/section-action :preserve)
        ":preserve must validate as a static section action")
    ;; :preserve is a no-op marker — content is NOT required (unlike replace/append/prepend)
    (is (s/valid? :github.copilot-sdk.specs/section-override {:action :preserve})
        ":preserve override needs no :content")
    ;; ...and it carries no content: a content-bearing :preserve/:remove is a
    ;; caller mistake the spec must reject (upstream PR #1713 — these actions
    ;; have no content payload).
    (is (false? (s/valid? :github.copilot-sdk.specs/section-override
                          {:action :preserve :content "x"}))
        ":preserve must reject :content")
    (is (false? (s/valid? :github.copilot-sdk.specs/section-override
                          {:action :remove :content "x"}))
        ":remove must reject :content"))
  (testing ":preserve action survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:identity {:action :remove}
                                                   :tone {:action :preserve}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (= "preserve" (get-in wire [:tone :action]))
          ":preserve must be sent as the wire action string \"preserve\"")
      (is (not (contains? (get wire :tone) :content))
          ":preserve emits no :content key"))))

(deftest test-v1-0-4-capi-enable-websocket-responses-wire
  (testing ":capi {:enable-web-socket-responses ...} forwards on both session.create and session.resume (upstream PR #1711)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :capi {:enable-web-socket-responses false}})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :capi {:enable-web-socket-responses false}})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= {:enableWebSocketResponses false} (:capi create-params))
          ":capi must be sent verbatim under wire key :capi with camelCase :enableWebSocketResponses on create")
      (is (= {:enableWebSocketResponses false} (:capi resume-params))
          ":capi must also forward on resume")))
  (testing ":capi is accepted by the session-config spec"
    (is (s/valid? :github.copilot-sdk.specs/capi {:enable-web-socket-responses true}))
    (is (s/valid? :github.copilot-sdk.specs/capi {})
        "empty :capi map is valid (field is optional)")))

(deftest test-v1-0-5-new-session-options-wire
  (testing ":excluded-builtin-agents, :enable-citations, :session-limits forward on both session.create and session.resume (upstream PR #1865)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          opts {:excluded-builtin-agents ["planner" "reviewer"]
                :enable-citations true
                :session-limits {:max-ai-credits 500}}
          _ (sdk/create-session *test-client*
                                (merge {:on-permission-request sdk/approve-all} opts))
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                (merge {:on-permission-request sdk/approve-all} opts))
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= ["planner" "reviewer"] (:excludedBuiltinAgents create-params))
          ":excluded-builtin-agents must forward under camelCase :excludedBuiltinAgents on create")
      (is (= ["planner" "reviewer"] (:excludedBuiltinAgents resume-params))
          ":excluded-builtin-agents must also forward on resume")
      (is (true? (:enableCitations create-params))
          ":enable-citations must forward as :enableCitations on create")
      (is (true? (:enableCitations resume-params))
          ":enable-citations must also forward on resume")
      (is (= {:maxAiCredits 500} (:sessionLimits create-params))
          ":session-limits {:max-ai-credits n} must forward as {:maxAiCredits n} on create")
      (is (= {:maxAiCredits 500} (:sessionLimits resume-params))
          ":session-limits must also forward on resume")))
  (testing ":enable-citations is gated on some?, so an explicit false is still forwarded (not omitted)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-citations false})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-citations false})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (false? (:enableCitations create-params))
          "explicit :enable-citations false must forward as :enableCitations false on create")
      (is (false? (:enableCitations resume-params))
          "explicit :enable-citations false must also forward on resume")))
  (testing "new options are accepted by the session-config, resume-session-config, and join-session-config specs"
    (let [opts {:excluded-builtin-agents ["a"]
                :enable-citations true
                :session-limits {:max-ai-credits 100}}]
      (is (s/valid? ::specs/session-config
                    (merge {:on-permission-request sdk/approve-all} opts)))
      (is (s/valid? ::specs/resume-session-config
                    (merge {:on-permission-request sdk/approve-all} opts)))
      (is (s/valid? ::specs/join-session-config
                    (merge {:on-permission-request sdk/approve-all} opts))))
    (is (s/valid? ::specs/session-limits {:max-ai-credits 100}))
    (is (s/valid? ::specs/session-limits {})
        "empty :session-limits map is valid (:max-ai-credits is optional)")
    (is (not (s/valid? ::specs/session-limits {:max-ai-credits 0}))
        ":max-ai-credits must be positive (wire exclusiveMinimum 0)")
    (is (not (s/valid? ::specs/session-limits {:max-ai-credits -5}))
        ":max-ai-credits rejects negative values")))

(deftest test-v1-0-5-session-limits-events
  (testing "session.response_limits_changed renamed to session.session_limits_changed (upstream schema 1.0.67)"
    (is (contains? sdk/event-types :copilot/session.session_limits_changed)
        "renamed event must be in the master event-types set")
    (is (contains? sdk/session-events :copilot/session.session_limits_changed)
        "renamed event must be in the session-events set")
    (is (not (contains? sdk/event-types :copilot/session.response_limits_changed))
        "the old event name must be gone from the public sets"))
  (testing "new usage_checkpoint and session_limits_exhausted events are public (upstream schema 1.0.67)"
    (is (contains? sdk/event-types :copilot/session.usage_checkpoint))
    (is (contains? sdk/session-events :copilot/session.usage_checkpoint))
    (is (contains? sdk/event-types :copilot/session_limits_exhausted.requested))
    (is (contains? sdk/event-types :copilot/session_limits_exhausted.completed))
    (is (contains? sdk/interaction-events :copilot/session_limits_exhausted.requested))
    (is (contains? sdk/interaction-events :copilot/session_limits_exhausted.completed))))

(deftest test-v1-0-7-preview-new-events
  (testing "assistant.tool_call_delta is a public assistant event (upstream schema 1.0.69-3)"
    (is (contains? sdk/event-types :copilot/assistant.tool_call_delta)
        "must be in the master event-types set")
    (is (contains? sdk/assistant-events :copilot/assistant.tool_call_delta)
        "must be categorized under assistant-events"))
  (testing "mcp list_changed events are public MCP interaction events (upstream schema 1.0.70)"
    (doseq [ev [:copilot/mcp.tools.list_changed
                :copilot/mcp.resources.list_changed
                :copilot/mcp.prompts.list_changed]]
      (is (contains? sdk/event-types ev)
          (str ev " must be in the master event-types set"))
      (is (contains? sdk/interaction-events ev)
          (str ev " must be categorized under interaction-events"))))
  (testing "session.auto_mode_resolved is a public session event (upstream schema 1.0.70)"
    (is (contains? sdk/event-types :copilot/session.auto_mode_resolved)
        "must be in the master event-types set")
    (is (contains? sdk/session-events :copilot/session.auto_mode_resolved)
        "must be categorized under session-events"))
  (testing "new event types validate against the idiom ::event-type enum"
    (doseq [ev [:copilot/assistant.tool_call_delta
                :copilot/mcp.tools.list_changed
                :copilot/mcp.resources.list_changed
                :copilot/mcp.prompts.list_changed
                :copilot/session.auto_mode_resolved]]
      (is (s/valid? ::specs/event-type ev)
          (str ev " must be accepted by the idiom ::event-type spec")))))

(deftest test-v1-0-4-provider-transport-wire
  (testing ":provider :transport forwards on both session.create and session.resume (upstream PR #1711)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          provider {:provider-type :openai
                    :wire-api :responses
                    :base-url "https://example.test"
                    :api-key "key"
                    :transport :websockets}
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})]
      (doseq [method ["session.create" "session.resume"]]
        (let [p (get-in @seen [method :provider])]
          (testing method
            (is (= "websockets" (:transport p))
                ":transport keyword value must serialize to its wire string"))))))
  (testing "::transport enum is enforced on ::provider"
    (is (s/valid? :github.copilot-sdk.specs/transport :http))
    (is (s/valid? :github.copilot-sdk.specs/transport :websockets))
    (is (false? (s/valid? :github.copilot-sdk.specs/transport :bogus)))
    (is (s/valid? :github.copilot-sdk.specs/provider
                  {:base-url "https://example.test" :transport :http}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider
                          {:base-url "https://example.test" :transport :bogus}))
        "an invalid :transport value must fail provider validation")))

(deftest test-v1-0-4-multi-provider-byok-registry-wire
  (testing ":providers/:models forward on both session.create and session.resume (upstream PR #1718)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          providers [{:name "my-openai"
                      :provider-type :openai
                      :wire-api :responses
                      :base-url "https://oai.test"
                      :api-key "k1"
                      :headers {"X-Org" "acme"}}
                     {:name "my-azure"
                      :provider-type :azure
                      :base-url "https://azure.test"
                      :api-key "k2"
                      :azure-options {:azure-api-version "2024-06-01"}}]
          models [{:id "gpt-4o"
                   :provider "my-openai"
                   :model-id "gpt-4o"
                   :name "GPT-4o"
                   :wire-model "gpt-4o-2024"
                   :max-input-tokens 128000
                   :max-context-window-tokens 200000
                   :max-output-tokens 16000
                   :capabilities {"reasoningEffort" "high"
                                  "max_prompt_tokens" 120000
                                  "supported_media_types" ["image/png"]}}]
          cfg {:on-permission-request sdk/approve-all
               :model "fallback-model"
               :providers providers
               :models models}
          _ (sdk/create-session *test-client* cfg)
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id cfg)]
      (doseq [method ["session.create" "session.resume"]]
        (testing method
          (let [params (get @seen method)
                wprov (:providers params)
                wmodels (:models params)
                oai (first wprov)
                azure (second wprov)
                m (first wmodels)]
            (is (= 2 (count wprov)) "both named providers are forwarded")
            ;; named provider wire shape
            (is (= "my-openai" (:name oai)))
            (is (= "openai" (:type oai)) ":provider-type renames to wire :type")
            (is (= "responses" (:wireApi oai)))
            (is (= "https://oai.test" (:baseUrl oai)))
            (is (= "k1" (:apiKey oai)))
            (is (= {:X-Org "acme"} (:headers oai)))
            (is (not (contains? oai :providerType)) "no :providerType leaks onto the wire")
            ;; azure nested rename
            (is (= "azure" (:type azure)))
            (is (= {:apiVersion "2024-06-01"} (:azure azure))
                ":azure-options -> :azure with nested :azure-api-version -> :apiVersion")
            ;; provider-model wire shape
            (is (= "gpt-4o" (:id m)))
            (is (= "my-openai" (:provider m)))
            (is (= "gpt-4o" (:modelId m)))
            (is (= "GPT-4o" (:name m)))
            (is (= "gpt-4o-2024" (:wireModel m)))
            (is (= 128000 (:maxPromptTokens m)) ":max-input-tokens -> wire :maxPromptTokens")
            (is (= 200000 (:maxContextWindowTokens m)))
            (is (= 16000 (:maxOutputTokens m)))
            (is (not (contains? m :maxInputTokens)) "no :maxInputTokens leaks onto the wire")
            ;; capabilities passthrough: opaque string keys survive unmangled
            (is (= "high" (get-in m [:capabilities :reasoningEffort])))
            (is (= 120000 (get-in m [:capabilities :max_prompt_tokens]))
                ":capabilities is opaque — snake_case keys must NOT be camelCased")
            (is (= ["image/png"] (get-in m [:capabilities :supported_media_types]))))))))
  (testing "specs accept the multi-provider registry shapes"
    (is (s/valid? :github.copilot-sdk.specs/named-provider
                  {:name "p" :base-url "https://x.test"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:base-url "https://x.test"}))
        ":name is required on a named provider")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "has/slash" :base-url "https://x.test"}))
        "named provider :name must not contain '/'")
    ;; A named provider carries no transport or inline model-override fields
    ;; (upstream NamedProviderConfig, PR #1718) — those belong on the singular
    ;; ::provider / ::provider-model. The spec must reject them so misuse fails
    ;; fast at validate-session-config! instead of silently forwarding on the wire.
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :transport :http}))
        ":transport is not a named-provider field")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :model-id "gpt-4o"}))
        ":model-id is not a named-provider field")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :max-input-tokens 1000}))
        "model-override token limits are not named-provider fields")
    (is (s/valid? :github.copilot-sdk.specs/provider-model
                  {:id "m" :provider "p"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:provider "p"}))
        ":id is required on a provider model")
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:id "m"}))
        ":provider is required on a provider model")
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:id "m" :provider "p" :max-input-tokens 0}))
        "token overrides must be positive")
    (is (s/valid? :github.copilot-sdk.specs/providers
                  [{:name "p" :base-url "https://x.test"}]))
    (is (s/valid? :github.copilot-sdk.specs/models
                  [{:id "m" :provider "p"}]))
    (is (s/valid? :github.copilot-sdk.specs/session-config
                  {:providers [{:name "p" :base-url "https://x.test"}]
                   :models [{:id "m" :provider "p"}]}))))

(deftest test-v1-0-4-exp-assignments-wire
  (testing ":exp-assignments forwards verbatim on both session.create and session.resume (upstream PR #1750)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          exp {"flight-abc" "treatment" "feature_x" {"enabled" true}}
          cfg {:on-permission-request sdk/approve-all
               :exp-assignments exp}
          _ (sdk/create-session *test-client* cfg)
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id cfg)]
      (doseq [method ["session.create" "session.resume"]]
        (testing method
          (let [p (get @seen method)]
            (is (= {:flight-abc "treatment" :feature_x {:enabled true}}
                   (:expAssignments p))
                ":exp-assignments forwards under wire key :expAssignments with keys preserved verbatim (no kebab->camel)"))))))
  (testing "::exp-assignments accepts an opaque string-keyed map"
    (is (s/valid? :github.copilot-sdk.specs/exp-assignments {"a" 1 "b" {"c" 2}}))
    (is (false? (s/valid? :github.copilot-sdk.specs/exp-assignments {:a 1}))
        "keys must be strings (source-defined flight ids), not keywords")
    (is (s/valid? :github.copilot-sdk.specs/session-config
                  {:exp-assignments {"a" 1}}))))

(deftest test-v1-0-4-provider-and-providers-mutually-exclusive
  (testing "combining singular :provider with the :providers registry is rejected on both create and resume (upstream ProviderTokenArgs/SessionConfig contract, PR #1718)"
    ;; Upstream documents that combining `providers`/`models` with the singular
    ;; `provider` is rejected; the SDK forwards both to the runtime which rejects
    ;; the combination. We fail fast client-side with a clear message — a
    ;; Clojure-only convenience that never alters the wire for any valid config
    ;; (the combination is invalid everywhere).
    (let [cfg {:on-permission-request sdk/approve-all
               :model "m"
               :provider {:base-url "https://single.test"}
               :providers [{:name "p" :base-url "https://registry.test"}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i):provider.*cannot be combined.*:providers"
                            (sdk/create-session *test-client* cfg))
          "create-session rejects :provider + :providers")
      (let [ok-session (sdk/create-session *test-client*
                                           {:on-permission-request sdk/approve-all
                                            :model "m"})
            session-id (sdk/session-id ok-session)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i):provider.*cannot be combined.*:providers"
                              (sdk/resume-session *test-client* session-id cfg))
            "resume-session rejects :provider + :providers")))))

(deftest test-v1-0-4-provider-and-models-mutually-exclusive
  (testing "combining singular :provider with the :models registry is rejected on both create and resume (upstream SessionConfig contract, PR #1718)"
    ;; Upstream documents (types.ts SessionConfig.providers/models JSDoc) that
    ;; combining *either* `providers` *or* `models` with the singular `provider`
    ;; is rejected — `:models` is part of the same multi-provider registry
    ;; surface. A config with `:provider` + `:models` (no `:providers`) must fail
    ;; the same client-side guard, otherwise it serializes a wire payload that
    ;; contradicts the documented "provider vs multi-provider registry" contract.
    (let [cfg {:on-permission-request sdk/approve-all
               :model "m"
               :provider {:base-url "https://single.test"}
               :models [{:provider "p" :id "m"}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i):provider.*cannot be combined.*:models"
                            (sdk/create-session *test-client* cfg))
          "create-session rejects :provider + :models")
      (let [ok-session (sdk/create-session *test-client*
                                           {:on-permission-request sdk/approve-all
                                            :model "m"})
            session-id (sdk/session-id ok-session)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i):provider.*cannot be combined.*:models"
                              (sdk/resume-session *test-client* session-id cfg))
            "resume-session rejects :provider + :models")))))

(deftest test-v1-0-4-bearer-token-exception-message-not-leaked
  (testing "an exception thrown by a bearer-token callback never leaks its message to logs or the runtime (SEC)"
    ;; The callback mints credentials; an exception it raises can easily carry
    ;; sensitive material in its message (e.g. a token echoed in an auth error).
    ;; The JSON-RPC error returned to the runtime must be generic and the log
    ;; must record only the exception class, never `ex-message`. Handler invoked
    ;; directly so `thread-call` conveys the `with-log` binding to the io thread.
    (let [secret "tok_LEAKED_IN_EXCEPTION_MESSAGE_456"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [_] (throw (ex-info secret {})))}})
          session-id (sdk/session-id session)]
      (log-test/with-log
        (let [resp (<!! (session/handle-provider-token-request!
                         *test-client* session-id "default"))]
          (is (= -32001 (get-in resp [:error :code]))
              "a thrown callback yields a JSON-RPC error")
          (is (not (str/includes? (str (get-in resp [:error :message])) secret))
              "the error message returned to the runtime must not echo the exception message")
          (is (seq (log-test/the-log)) "the exception branch should emit a log entry")
          (doseq [entry (log-test/the-log)]
            (is (not (str/includes? (str (:message entry)) secret))
                "exception message must not be logged")))))))

(deftest test-v1-0-4-bearer-token-provider-wire
  (testing ":bearer-token-provider strips the fn and emits :hasBearerTokenProvider on both builders (upstream PR #1748)"
    (let [token-fn (fn [_args] "secret-token")
          provider {:provider-type :openai
                    :wire-api :responses
                    :base-url "https://oai.test"
                    :bearer-token-provider token-fn}
          named [{:name "my-azure"
                  :provider-type :azure
                  :base-url "https://azure.test"
                  :bearer-token-provider token-fn}]
          capture (fn [cfg]
                    (let [seen (atom {})]
                      (mock/set-request-hook! *mock-server*
                                              (fn [method params]
                                                (when (#{"session.create" "session.resume"} method)
                                                  (swap! seen assoc method params))))
                      (sdk/create-session *test-client* cfg)
                      (let [session-id (sdk/get-last-session-id *test-client*)]
                        (sdk/resume-session *test-client* session-id cfg))
                      @seen))
          ;; singular :provider and registry :providers are mutually exclusive,
          ;; so exercise each on its own config.
          singular-seen (capture {:on-permission-request sdk/approve-all
                                  :model "fallback-model"
                                  :provider provider})
          registry-seen (capture {:on-permission-request sdk/approve-all
                                  :model "fallback-model"
                                  :providers named})]
      (doseq [method ["session.create" "session.resume"]]
        (testing method
          (let [wprov (:provider (get singular-seen method))
                wnamed (first (:providers (get registry-seen method)))]
            ;; singular provider
            (is (true? (:hasBearerTokenProvider wprov))
                "singular provider with a callback emits :hasBearerTokenProvider true")
            (is (not (contains? wprov :bearerTokenProvider))
                "the callback fn must NOT be serialized onto the wire")
            (is (not (contains? wprov :bearer-token-provider))
                "the kebab-case callback key must NOT leak onto the wire")
            ;; named provider
            (is (true? (:hasBearerTokenProvider wnamed))
                "named provider with a callback emits :hasBearerTokenProvider true")
            (is (not (contains? wnamed :bearerTokenProvider))
                "the named-provider callback fn must NOT be serialized onto the wire"))))))
  (testing "a provider without a callback omits :hasBearerTokenProvider"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider {:base-url "https://oai.test" :api-key "k"}})]
      (is (not (contains? (:provider @seen) :hasBearerTokenProvider))
          "no callback => no :hasBearerTokenProvider flag")))
  (testing "::bearer-token-provider is accepted on provider and named-provider specs"
    (is (s/valid? :github.copilot-sdk.specs/provider
                  {:base-url "https://x.test" :bearer-token-provider (fn [_] "t")}))
    (is (s/valid? :github.copilot-sdk.specs/named-provider
                  {:name "p" :base-url "https://x.test" :bearer-token-provider (fn [_] "t")}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider
                          {:base-url "https://x.test" :bearer-token-provider "not-a-fn"}))
        ":bearer-token-provider must be a function")))

(deftest test-v1-0-4-provider-token-get-token-callback
  (testing "providerToken.getToken routes to the singular provider callback (DEFAULT_PROVIDER_NAME)"
    (let [called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [args]
                                                    (reset! called args)
                                                    "singular-token")}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "providerToken.getToken"
                                           {:sessionId session-id
                                            :providerName "default"})]
      (is (= {:provider-name "default" :session-id session-id} @called)
          "callback receives idiomatic ProviderTokenArgs with :provider-name and :session-id")
      (is (= "singular-token" (get-in response [:result :token]))
          "the resolved token is returned under wire key :token")))
  (testing "providerToken.getToken routes to a named provider callback by name"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :providers [{:name "my-azure"
                                                    :base-url "https://azure.test"
                                                    :bearer-token-provider
                                                    (fn [_] "azure-token")}]})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "providerToken.getToken"
                                           {:sessionId session-id
                                            :providerName "my-azure"})]
      (is (= "azure-token" (get-in response [:result :token])))))
  (testing "providerToken.getToken with no matching callback returns an error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider (fn [_] "tok")}})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i)no bearer-token provider"
                            (mock/send-rpc-request! *mock-server*
                                                    "providerToken.getToken"
                                                    {:sessionId session-id
                                                     :providerName "nonexistent"}))))))

(deftest test-v1-0-4-bearer-token-non-string-result-not-logged
  (testing "a non-string bearer-token callback result value never reaches the logs (SEC)"
    ;; The callback is credential-related; a mistaken non-string return (e.g. a
    ;; map carrying the token) must not have its value interpolated into a log
    ;; message. The JSON-RPC error returned to the runtime is generic; this
    ;; guards the log side-channel. The handler is invoked directly on the test
    ;; thread so core.async `thread-call` conveys the `with-log` binding frame
    ;; to the io thread (the server-dispatch path runs on a reader thread that
    ;; predates this binding, so it cannot be captured via the mock RPC route).
    (let [secret "tok_LEAKED_IN_MAP_VALUE_123"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [_] {:access-token secret})}})
          session-id (sdk/session-id session)]
      (log-test/with-log
        (let [resp (<!! (session/handle-provider-token-request!
                         *test-client* session-id "default"))]
          (is (= -32001 (get-in resp [:error :code]))
              "a non-string result must yield a JSON-RPC error")
          (is (not (str/includes? (str (get-in resp [:error :message])) secret))
              "the error message must not echo the secret")
          (is (seq (log-test/the-log)) "the non-string branch should emit a warning")
          (doseq [entry (log-test/the-log)]
            (is (not (str/includes? (str (:message entry)) secret))
                "non-string callback result value must not be logged")))))))

(deftest test-schema-1-0-52-4-min-protocol-version-3
  (testing "client rejects servers reporting protocol version < 3 (upstream PR #1378)"
    ;; The SDK no longer supports v2 servers after the cleanup PR removed
    ;; the v2 `tool.call` / `permission.request` back-compat adapters.
    (let [server (mock/create-mock-server)
          _ (reset! (:protocol-version server) 2)
          _ (mock/start-mock-server! server)
          client (sdk/client {:auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i)protocol.*version"
                              (client/connect-with-streams! client in out)))
        (finally
          (try (sdk/disconnect! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

;; -----------------------------------------------------------------------------
;; Last Session ID Tests
;; -----------------------------------------------------------------------------

(deftest test-cli-1.0.46-sync-spec-additions
  (testing "::permission-kind accepts new extension-* kinds (CLI 1.0.44-3)"
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-management))
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-permission-access))
    (is (false? (s/valid? :github.copilot-sdk.specs/permission-kind :bogus-kind))))

  (testing "::assistant.message-data accepts :server-tools + :service-request-id (CLI 1.0.63)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1"
                   :content "answer"
                   :server-tools {:advisor-model "claude-advisor"}
                   :service-request-id "req-1"
                   :model "gpt-5"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1"
                           :content "answer"
                           :server-tools "not-a-map"})))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1"
                           :content "answer"
                           :service-request-id 42}))))

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
      (sdk/unsubscribe-events! session events-ch))))

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

(deftest test-command-error-rpc
  (testing "command errors are reported via handlePendingCommand"
    (doseq [[desc commands req-id command command-name expected-error]
            [["unknown command reports an error"
              [{:name "deploy" :command-handler (fn [_] nil)}]
              "cmd-req-2" "/unknown" "unknown" #"Unknown command"]
             ["handler exception reports the exception message"
              [{:name "fail" :command-handler (fn [_] (throw (Exception. "deploy failed")))}]
              "cmd-req-3" "/fail" "fail" "deploy failed"]]]
      (testing desc
        (let [requests (atom [])
              rpc-latch (java.util.concurrent.CountDownLatch. 1)
              _ (mock/set-request-hook! *mock-server*
                                        (fn [method params]
                                          (swap! requests conj {:method method :params params})
                                          (when (= "session.commands.handlePendingCommand" method)
                                            (.countDown rpc-latch))))
              session (sdk/create-session *test-client*
                                          {:on-permission-request sdk/approve-all
                                           :commands commands})
              session-id (sdk/session-id session)]
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
          (reset! requests [])
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "command.execute"
                                         {:requestId req-id
                                          :command command
                                          :commandName command-name
                                          :args ""})
          (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)
                err (:error (:params (first cmd-rpcs)))]
            (is (= 1 (count cmd-rpcs)))
            (when (seq cmd-rpcs)
              (if (string? expected-error)
                (is (= expected-error err))
                (is (re-find expected-error err))))))))))

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
;; SessionFs SQLite Tests (upstream PR #1299)
;; -----------------------------------------------------------------------------

(deftest test-create-session-fs-adapter-sqlite
  (testing "provider with nested :sqlite map is adapted to flat :sqlite-query and :sqlite-exists handler keys"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "2026-01-01T00:00:00Z" :birthtime "2026-01-01T00:00:00Z"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)
                    :sqlite {:query (fn [query-type sql params]
                                      (is (= :query query-type))
                                      (is (= "SELECT 1" sql))
                                      (is (= {:$id 7} params))
                                      {:rows [{:n 1}] :columns ["n"] :rows-affected 0})
                             :exists (fn [] true)}}
          handler (session/create-session-fs-adapter provider)]
      (is (fn? (:sqlite-query handler)))
      (is (fn? (:sqlite-exists handler)))
      (is (= {:rows [{:n 1}] :columns ["n"] :rows-affected 0}
             ((:sqlite-query handler) {:query-type :query :query "SELECT 1" :params {:$id 7}})))
      (is (= {:exists true} ((:sqlite-exists handler) {})))))

  (testing "provider without :sqlite gets no sqlite handlers"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (not (contains? handler :sqlite-query)))
      (is (not (contains? handler :sqlite-exists)))))

  (testing "sqlite.query returning nil defaults to {:rows [] :columns [] :rows-affected 0}"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)
                    :sqlite {:query (fn [_ _ _] nil)
                             :exists (fn [] false)}}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:rows [] :columns [] :rows-affected 0}
             ((:sqlite-query handler) {:query-type :exec :query "CREATE TABLE t (x INT)"}))))))

(deftest test-session-fs-sqlite-rpc-dispatch
  (testing "sessionFs.sqliteQuery RPC dispatches to handler with :query-type coerced to keyword"
    (let [received (atom nil)
          client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [qtype sql params]
                                                            (reset! received {:query-type qtype :query sql :params params})
                                                            {:rows [{:c 42}] :columns ["c"] :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteQuery"
                                           {:sessionId (sdk/session-id session)
                                            :query "SELECT c FROM t WHERE id = $userId"
                                            :queryType "query"
                                            :params {:$userId "abc"}})]
      (is (= :query (:query-type @received)))
      (is (= "SELECT c FROM t WHERE id = $userId" (:query @received)))
      ;; Opaque bind params preserved verbatim (no kebab-case mangling)
      (is (= {:$userId "abc"} (:params @received)))
      (is (= {:rows [{:c 42}] :columns ["c"] :rowsAffected 0}
             (:result response)))))

  (testing "sessionFs.sqliteQuery preserves snake_case column-name keys in result rows (review feedback)"
    ;; Without an outgoing escape hatch, `util/clj->wire` would convert
    ;; `:user_id` → `:userId`, producing rows whose keys no longer match
    ;; the `columns` array. Upstream Node forwards row maps verbatim.
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [_ _ _]
                                                            {:rows [{:user_id 1 :created_at "2026-01-01"}
                                                                    {:user_id 2 :created_at "2026-01-02"}]
                                                             :columns ["user_id" "created_at"]
                                                             :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteQuery"
                                           {:sessionId (sdk/session-id session)
                                            :query "SELECT user_id, created_at FROM users"
                                            :queryType "query"})
          result (:result response)]
      ;; Row keys must round-trip verbatim
      (is (= [{:user_id 1 :created_at "2026-01-01"}
              {:user_id 2 :created_at "2026-01-02"}]
             (:rows result)))
      ;; Columns array (strings) is unchanged
      (is (= ["user_id" "created_at"] (:columns result)))
      ;; Sibling SDK fields still get kebab→camelCase converted
      (is (= 0 (:rowsAffected result)))))

  (testing "sessionFs.sqliteExists RPC dispatches to handler"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [_ _ _] {:rows [] :columns [] :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteExists"
                                           {:sessionId (sdk/session-id session)})]
      (is (= {:exists true} (:result response))))))

(deftest test-session-fs-capabilities-forwarded-on-wire
  (testing ":capabilities is forwarded on sessionFs.setProvider when configured (upstream PR #1299)"
    ;; Build a fresh server + client so we can intercept the setProvider call sent during connect.
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server (fn [method params]
                                             (when (= "sessionFs.setProvider" method)
                                               (swap! seen assoc method params))))
          client (sdk/client {:auto-start? false
                              :session-fs {:initial-cwd "/workspace"
                                           :session-state-path "/state"
                                           :conventions "posix"
                                           :capabilities {:sqlite true}}})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [params (get @seen "sessionFs.setProvider")]
          (is (= {:sqlite true} (:capabilities params)))
          (is (= "/workspace" (:initialCwd params))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server)))))

  (testing ":capabilities is omitted when not configured"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server (fn [method params]
                                             (when (= "sessionFs.setProvider" method)
                                               (swap! seen assoc method params))))
          client (sdk/client {:auto-start? false
                              :session-fs {:initial-cwd "/workspace"
                                           :session-state-path "/state"
                                           :conventions "posix"}})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [params (get @seen "sessionFs.setProvider")]
          (is (not (contains? params :capabilities))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-session-fs-sqlite-capability-validation
  (testing "create-session throws when capabilities.sqlite is declared but provider lacks :sqlite"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"capabilities\.sqlite"
           (sdk/create-session client-with-fs
                               {:on-permission-request sdk/approve-all
                                :create-session-fs-handler
                                (fn [_session]
                                  {:read-file (fn [_] "x")
                                   :write-file (fn [_ _ _] nil)
                                   :append-file (fn [_ _ _] nil)
                                   :exists (fn [_] true)
                                   :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                   :mkdir (fn [_ _ _] nil)
                                   :readdir (fn [_] [])
                                   :readdir-with-types (fn [_] [])
                                   :rm (fn [_ _ _] nil)
                                   :rename (fn [_ _] nil)})})))))

  (testing "create-session throws when capabilities.sqlite is declared with only :sqlite-query (review feedback)"
    ;; Low-level handler shape: presence of :sqlite-query alone must NOT pass
    ;; validation, since sessionFs.sqliteExists would route to a missing key
    ;; and surface as an opaque \"Unknown sessionFs method\" error at runtime.
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"capabilities\.sqlite"
           (sdk/create-session client-with-fs
                               {:on-permission-request sdk/approve-all
                                :create-session-fs-handler
                                (fn [_session]
                                  {:read-file (fn [_] "x")
                                   :write-file (fn [_ _ _] nil)
                                   :append-file (fn [_ _ _] nil)
                                   :exists (fn [_] true)
                                   :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                   :mkdir (fn [_ _ _] nil)
                                   :readdir (fn [_] [])
                                   :readdir-with-types (fn [_] [])
                                   :rm (fn [_ _ _] nil)
                                   :rename (fn [_ _] nil)
                                   ;; only :sqlite-query, missing :sqlite-exists
                                   :sqlite-query (fn [_] {:rows [] :columns [] :rows-affected 0})})}))))))

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

(deftest test-hooks-post-tool-use-failure
  (testing "hooks.invoke postToolUseFailure calls registered handler (upstream PR #1421)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use-failure
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:additional-context "noted"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "false"}
                                                    :error "command exited 1"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= "command exited 1" (get-in @handler-called [:input :error])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= "noted" (get-in response [:result :additionalContext]))))))

(deftest test-hooks-post-tool-use-failure-no-handler
  (testing "hooks.invoke postToolUseFailure with no handler returns nil result"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       ;; Only success hook registered; failure should pass through as nil.
                                       :hooks {:on-post-tool-use
                                               (fn [_ _] nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :error "boom"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
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

(deftest test-hooks-input-exposes-session-id
  (testing "hook input includes :session-id (upstream PR #1290 — BaseHookInput.sessionId)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called)))))

  (testing "hook input :session-id preserves wire-provided value (sub-agent case)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          parent-session-id (sdk/session-id session)
          sub-agent-session-id "sub-agent-session-xyz"
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId parent-session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId sub-agent-session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= sub-agent-session-id (:session-id @handler-called)))))

  (testing "hook input :session-id falls back to outer session-id when wire omits it"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called))))))

;; -----------------------------------------------------------------------------
;; preMcpToolCall hook (upstream PR #1366)
;; -----------------------------------------------------------------------------

(deftest test-hooks-pre-mcp-tool-call-input-shape
  (testing "preMcpToolCall: handler receives kebab-cased base fields + opaque arguments/_meta"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          opaque-args {:filePath "/tmp/foo.txt"
                       :user_id 42
                       :nested {:keepCamelCase true}}
          opaque-meta {:traceId "abc-123" :foo_bar "ok"}
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preMcpToolCall"
                                     :input {:serverName "my-mcp"
                                             :toolName "fetch"
                                             :toolCallId "call-42"
                                             :arguments opaque-args
                                             :_meta opaque-meta
                                             :timestamp 12345
                                             :cwd "/workspace"
                                             :sessionId session-id}})]
      (is (some? @handler-called))
      ;; Base fields are kebab-cased
      (is (= "my-mcp" (get-in @handler-called [:input :server-name])))
      (is (= "fetch" (get-in @handler-called [:input :tool-name])))
      (is (= "call-42" (get-in @handler-called [:input :tool-call-id])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= 12345 (get-in @handler-called [:input :timestamp])))
      ;; Opaque arguments preserved verbatim (wire-keyword shape, NOT kebab-cased)
      (is (= opaque-args (get-in @handler-called [:input :arguments])))
      ;; _meta key preserved verbatim (kebab conversion would strip leading _)
      (is (= opaque-meta (get-in @handler-called [:input :_meta]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-object
  (testing "preMcpToolCall: :meta-to-use map becomes metaToUse on wire with opaque inner contents"
    (let [opaque-replacement {:newTraceId "xyz-789" :keep_snake "yes"}
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use opaque-replacement})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The wire field name is metaToUse, NOT meta-to-use
      (is (contains? (:result response) :metaToUse))
      (is (not (contains? (:result response) :meta-to-use)))
      ;; Inner map preserved verbatim — inner keys NOT camelCased
      (is (= opaque-replacement (get-in response [:result :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-null
  (testing "preMcpToolCall: :meta-to-use nil serializes as JSON null (key present with null value)"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use nil})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The metaToUse key MUST be present (not absent) and its value MUST be null.
      (is (contains? (:result response) :metaToUse))
      (is (nil? (get-in response [:result :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-no-meta-to-use
  (testing "preMcpToolCall: handler returning {} or nil omits metaToUse field"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _] {})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      (is (not (contains? (:result response) :metaToUse))))))

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

(deftest test-tool-result-normalization
  (testing "tool handler return values are normalized into the handlePendingToolCall result"
    (doseq [[desc tool-handler req-id tc-id assert-result]
            [["string is normalized to success"
              (fn [_args _inv] "hello world") "tool-req-1" "tc-1"
              (fn [result]
                (is (= "hello world" (:textResultForLlm result)))
                (is (= "success" (:resultType result))))]
             ["nil is normalized to failure"
              (fn [_args _inv] nil) "tool-req-2" "tc-2"
              (fn [result]
                (is (= "Tool returned no result" (:textResultForLlm result)))
                (is (= "failure" (:resultType result))))]
             ["structured ToolResultObject is forwarded with telemetry"
              (fn [_args _inv] {:text-result-for-llm "all good"
                                :result-type "success"
                                :tool-telemetry {:latency-ms 42}})
              "tool-req-3" "tc-3"
              (fn [result]
                (is (= "all good" (:textResultForLlm result)))
                (is (= "success" (:resultType result)))
                (is (= 42 (get-in result [:toolTelemetry :latencyMs]))))]]]
      (testing desc
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
                                                    :tool-handler tool-handler}]})
              session-id (sdk/session-id session)]
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
          (reset! requests [])
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "external_tool.requested"
                                         {:requestId req-id
                                          :toolName "test-tool"
                                          :toolCallId tc-id
                                          :arguments {}})
          (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
                result (get-in (first rpcs) [:params :result])]
            (is (= 1 (count rpcs)))
            (is (map? result))
            (assert-result result)))))))

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

(deftest test-switch-model-with-context-tier
  (testing "switch-model! forwards contextTier (upstream PR #1522)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:context-tier :long-context})]
      (is (= "long_context" (:contextTier @captured-params))
          "context-tier keyword must convert to the underscore wire value")))

  (testing "switch-model! forwards reasoningSummary (setModel parity)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:reasoning-summary "concise"})]
      (is (= "concise" (:reasoningSummary @captured-params)))))

  (testing "set-model! forwards contextTier (alias for switch-model!)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/set-model! session "gpt-5.4" {:context-tier :default})]
      (is (= "default" (:contextTier @captured-params)))))

  (testing "switch-model! omits contextTier when :context-tier is nil"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:context-tier nil})]
      (is (not (contains? @captured-params :contextTier))
          "nil :context-tier must be omitted, not sent as contextTier: null (switchTo schema has no null tier)"))))

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
                   :mcp-server-type :stdio}))
    (testing "upstream PR #1347: :mcp-args is optional"
      (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                    {:mcp-command "true" :mcp-tools ["read"]})
          ":mcp-args may be omitted (upstream PR #1347)")
      (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                    {:mcp-command "node" :mcp-tools ["read"] :mcp-server-type :stdio})
          ":mcp-args optional with explicit :stdio type"))))

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

(deftest test-request-permission-flag-on-create-and-resume
  (testing "requestPermission flag reflects whether a permission handler is active"
    (let [base-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))]
      (doseq [[desc method create! expected]
              [["session.create always sends requestPermission: true"
                "session.create"
                #(sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
                true]
               ["session.resume with default join handler sends requestPermission: false"
                "session.resume"
                #(sdk/resume-session *test-client* base-id
                                     {:on-permission-request sdk/default-join-session-permission-handler})
                false]
               ["session.resume with explicit handler sends requestPermission: true"
                "session.resume"
                #(sdk/resume-session *test-client* base-id {:on-permission-request sdk/approve-all})
                true]]]
        (testing desc
          (let [seen (atom nil)]
            (mock/set-request-hook! *mock-server*
                                    (fn [m params] (when (= method m) (reset! seen params))))
            (create!)
            (is (= expected (:requestPermission @seen)))))))))

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
          "post-wire->clj event data must validate against the idiom spec")))
  (testing "inbound user.message echoing wire-string :agent-mode validates"
    ;; Server echoes agentMode as the wire string ("interactive", "plan",
    ;; "autopilot", "shell"). wire->clj keeps the value as a string;
    ;; ::user.message-data must accept that without rejecting on the
    ;; caller-side keyword set.
    (doseq [mode ["interactive" "plan" "autopilot" "shell"]]
      (let [wire-data {:content "hi" :agentMode mode}
            clj-data (util/wire->clj wire-data)]
        (is (= mode (:agent-mode clj-data))
            (str "wire->clj preserves wire-string for :agent-mode " mode))
        (is (s/valid? :github.copilot-sdk.specs/user.message-data clj-data)
            (str "inbound user.message-data accepts wire-string :agent-mode " mode))))))

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

;; --- assistant.usage :time-to-first-token-ms (upstream CLI 1.0.51 schema) --

(deftest test-assistant-usage-time-to-first-token-ms
  (testing "assistant.usage-data accepts :time-to-first-token-ms (renamed from :ttft-ms)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :time-to-first-token-ms 250}))
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :time-to-first-token-ms -1}))
        ":time-to-first-token-ms must be a non-negative integer")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :time-to-first-token-ms "fast"}))
        ":time-to-first-token-ms must be an integer")
    (testing "legacy :ttft-ms key still accepted for backward compatibility (older CLIs)"
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                    {:model "gpt-5" :ttft-ms 250}))
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                    {:model "gpt-5" :ttft-ms 250 :time-to-first-token-ms 250})
          "both keys may coexist during CLI version transition"))))

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

;; --- GitHub telemetry forwarding (upstream PR #1835) ----------------------

(defn- with-telemetry-client*
  "Create a mock server + client (optionally with :on-github-telemetry),
   connect them over in-memory streams, run (f server client), then tear
   down. Used for telemetry-forwarding tests that need a client carrying an
   :on-github-telemetry handler (the shared *test-client* has none)."
  [client-opts f]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client (merge {:auto-start? false} client-opts))
        [in out] (mock/client-streams server)]
    (client/connect-with-streams! client in out)
    (try
      (f server client)
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (Thread/sleep 50)
        (mock/stop-mock-server! server)))))

(deftest test-on-github-telemetry-client-option-accepted
  (testing ":on-github-telemetry client option is accepted and stored on the client (upstream PR #1835)"
    (let [c (sdk/client {:auto-start? false :on-github-telemetry (fn [_])})]
      (is (fn? (:on-github-telemetry c))))))

(deftest test-github-telemetry-forwarding-flag-on-wire
  (testing "enableGitHubTelemetryForwarding=true in session.create when handler is set (upstream PR #1835)"
    (with-telemetry-client*
      {:on-github-telemetry (fn [_])}
      (fn [server client]
        (let [seen (atom {})
              _ (mock/set-request-hook! server (fn [method params]
                                                 (when (= "session.create" method)
                                                   (swap! seen assoc method params))))
              _ (sdk/create-session client {:on-permission-request sdk/approve-all})
              create-params (get @seen "session.create")]
          (is (true? (:enableGitHubTelemetryForwarding create-params)))))))

  (testing "enableGitHubTelemetryForwarding=true in session.resume when handler is set (upstream PR #1835)"
    (with-telemetry-client*
      {:on-github-telemetry (fn [_])}
      (fn [server client]
        (let [session-id (sdk/session-id (sdk/create-session client {:on-permission-request sdk/approve-all}))
              seen (atom {})
              _ (mock/set-request-hook! server (fn [method params]
                                                 (when (= "session.resume" method)
                                                   (swap! seen assoc method params))))
              _ (sdk/resume-session client session-id {:on-permission-request sdk/approve-all})
              resume-params (get @seen "session.resume")]
          (is (true? (:enableGitHubTelemetryForwarding resume-params)))))))

  (testing "enableGitHubTelemetryForwarding is omitted from session.create when no handler (upstream PR #1835)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableGitHubTelemetryForwarding)))))

  (testing "enableGitHubTelemetryForwarding is omitted from session.resume when no handler (upstream PR #1835)"
    (let [session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (not (contains? resume-params :enableGitHubTelemetryForwarding)))))

  (testing "wire builders emit enableGitHubTelemetryForwarding only for an explicit true config value (upstream PR #1835)"
    ;; Guard the emit on `true?`, not `some?`: an explicit `false` in config
    ;; must be omitted from the wire, never stamped as `false`.
    (let [create @#'client/build-create-session-params
          resume #(#'client/build-resume-session-params %1 %2)]
      (is (true? (:enableGitHubTelemetryForwarding
                  (create {:enable-github-telemetry-forwarding? true}))))
      (is (not (contains? (create {:enable-github-telemetry-forwarding? false})
                          :enableGitHubTelemetryForwarding)))
      (is (not (contains? (create {}) :enableGitHubTelemetryForwarding)))
      (is (true? (:enableGitHubTelemetryForwarding
                  (resume "s-1" {:enable-github-telemetry-forwarding? true}))))
      (is (not (contains? (resume "s-1" {:enable-github-telemetry-forwarding? false})
                          :enableGitHubTelemetryForwarding)))
      (is (not (contains? (resume "s-1" {}) :enableGitHubTelemetryForwarding))))))

(deftest test-github-telemetry-event-invokes-callback
  (testing "gitHubTelemetry.event notification invokes :on-github-telemetry with idiom-shaped params; opaque sub-maps pass through verbatim (upstream PR #1835)"
    (let [received (promise)]
      (with-telemetry-client*
        {:on-github-telemetry (fn [notif] (deliver received notif))}
        (fn [server _client]
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "sess-1"
                                    :restricted false
                                    :event {:kind "model_call"
                                            :created_at "2024-01-01T00:00:00Z"
                                            :model_call_id "mc-123"
                                            :session_id "sess-1"
                                            :copilot_tracking_id "trk-9"
                                            :properties {:someWeirdKey "v" :another_Key "w"}
                                            :metrics {:someMetricKey 1 :another_Metric 2}
                                            :features {:someFeatureKey "on"}
                                            :client {:cli_version "1.0.0"
                                                     :os_platform "darwin"}}})
          (let [notif (deref received 1000 :timeout)]
            (is (not= :timeout notif) "callback should be invoked within 1s")
            (when (map? notif)
              ;; Top-level notification scalars: snake/camel -> kebab
              (is (= "sess-1" (:session-id notif)))
              (is (= false (:restricted notif)))
              (let [event (:event notif)]
                ;; Event scalars: snake_case -> kebab-case
                (is (= "model_call" (:kind event)))
                (is (= "mc-123" (:model-call-id event)))
                (is (= "sess-1" (:session-id event)))
                (is (= "trk-9" (:copilot-tracking-id event)))
                ;; Opaque sub-maps: keys preserved VERBATIM (not kebab-cased)
                (is (= {:someWeirdKey "v" :another_Key "w"} (:properties event)))
                (is (= {:someMetricKey 1 :another_Metric 2} (:metrics event)))
                (is (= {:someFeatureKey "on"} (:features event)))
                ;; Nested client info: snake_case scalars -> kebab-case
                (is (= "1.0.0" (get-in event [:client :cli-version])))
                (is (= "darwin" (get-in event [:client :os-platform])))))))))))

(deftest test-github-telemetry-handler-throwable-does-not-kill-router
  (testing "a telemetry handler throwing a non-Exception Throwable (e.g. AssertionError) must not kill the notification router; a later notification still dispatches (upstream PR #1835, regression guard)"
    (let [calls (atom 0)
          second-received (promise)
          handler (fn [_notif]
                    (if (= 1 (swap! calls inc))
                      ;; AssertionError is a Throwable but NOT an Exception —
                      ;; a `catch Exception` would let it escape and unwind the
                      ;; notification go-loop, killing dispatch for all sessions.
                      (throw (AssertionError. "boom"))
                      (deliver second-received :ok)))]
      (with-telemetry-client*
        {:on-github-telemetry handler}
        (fn [server _client]
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "s1" :restricted false :event {:kind "k"}})
          ;; Let the router process the first (throwing) notification.
          (Thread/sleep 50)
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "s2" :restricted false :event {:kind "k"}})
          (is (= :ok (deref second-received 1000 :timeout))
              "router must survive a Throwable from the handler and dispatch later notifications"))))))

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

;; -----------------------------------------------------------------------------
;; Optional callbacks (upstream PR #1308)
;; -----------------------------------------------------------------------------

(deftest test-create-session-without-permission-handler
  (testing "create-session accepts omission of :on-permission-request (upstream PR #1308)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)) "session.create still issued")
      (is (some? (sdk/session-id session))))))

(deftest test-resume-session-without-permission-handler
  (testing "resume-session accepts omission of :on-permission-request"
    (let [requests (atom [])
          _ (sdk/create-session *test-client* {})
          session-id (sdk/get-last-session-id *test-client*)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          resumed (sdk/resume-session *test-client* session-id {})
          resume-rpcs (filter #(= "session.resume" (:method %)) @requests)]
      (is (= 1 (count resume-rpcs)))
      (is (some? (sdk/session-id resumed))))))

(deftest test-define-tool-without-handler
  (testing "define-tool accepts omission of :handler (declaration-only tool, upstream PR #1308)"
    (let [tool (tools/define-tool "manual_lookup"
                 {:description "Look up status manually"
                  :parameters {:type "object"
                               :properties {:id {:type "string"}}
                               :required ["id"]}})]
      (is (= "manual_lookup" (:tool-name tool)))
      (is (not (contains? tool :tool-handler))
          "tool map must NOT contain :tool-handler key when handler omitted")
      ;; Must pass tool spec validation (handler now optional)
      (is (s/valid? :github.copilot-sdk.specs/tool tool))))

  (testing "define-tool-from-spec accepts omission of :handler (upstream PR #1308)"
    (let [tool (tools/define-tool-from-spec "manual_spec_tool"
                 {:description "Declaration-only spec tool"})]
      (is (= "manual_spec_tool" (:tool-name tool)))
      (is (not (contains? tool :tool-handler))
          "define-tool-from-spec must NOT install a wrapper handler when :handler omitted")
      (is (s/valid? :github.copilot-sdk.specs/tool tool)))))

(deftest test-declaration-only-tool-not-stored-in-handler-map
  (testing "declaration-only tools don't populate :tool-handlers"
    (let [decl-tool (tools/define-tool "decl_only" {:description "decl-only"})
          handled-tool (tools/define-tool "with_handler"
                         {:description "has handler"
                          :handler (fn [_ _] "ok")})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [decl-tool handled-tool]})
          session-id (sdk/session-id session)
          tool-handlers (get-in @(:state *test-client*)
                                [:sessions session-id :tool-handlers])]
      (is (not (contains? tool-handlers "decl_only"))
          "declaration-only tools must not be in :tool-handlers")
      (is (contains? tool-handlers "with_handler")
          "tools with handlers must remain in :tool-handlers"))))

(deftest test-handle-pending-permission-request!-sends-rpc
  (testing "handle-pending-permission-request! issues session.permissions.handlePendingPermissionRequest"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-42"
                                               :result {:kind :approve-once}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "req-42" (get-in (first pending-rpcs) [:params :requestId])))
        ;; Result is wire-serialized via clj->wire: keyword :kind → "approve-once"
        (is (= "approve-once" (get-in (first pending-rpcs)
                                      [:params :result :kind]))))))

  (testing "handle-pending-permission-request! accepts :user-not-available"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-una"
                                               :result {:kind :user-not-available}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "user-not-available" (get-in (first pending-rpcs)
                                            [:params :result :kind]))))))

  (testing "handle-pending-permission-request! accepts :approve-permanently"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-perm"
                                               :result {:kind :approve-permanently}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "approve-permanently" (get-in (first pending-rpcs)
                                             [:params :result :kind]))))))

  (testing "handle-pending-permission-request! rejects :no-result"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-permission-request!
                    session
                    {:request-id "req-1" :result {:kind :no-result}})))))

  (testing "handle-pending-permission-request! rejects malformed result"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-permission-request!
                    session
                    {:request-id "req-1" :result "not-a-map"})))))

  (testing "handle-pending-permission-request! rejects blank :request-id"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "" :result {:kind :approve-once}})))
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "   " :result {:kind :approve-once}})))))

  (testing "handle-pending-permission-request! rejects non-keyword :kind"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":kind must be a keyword"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind "approve-once"}})))
      (is (thrown-with-msg? Exception #":kind must be a keyword"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind 42}})))))

  (testing "handle-pending-permission-request! rejects unsupported :kind"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"not a supported permission decision"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind :totally-made-up}}))))))

(deftest test-handle-pending-tool-call!-sends-rpc
  (testing "handle-pending-tool-call! with :result issues session.tools.handlePendingToolCall"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-tool-call! session
                                     {:request-id "tool-req-7"
                                      :result "MANUAL_STATUS_OK"})
      (let [pending (filter #(= "session.tools.handlePendingToolCall" (:method %))
                            @requests)]
        (is (= 1 (count pending)))
        (is (= "tool-req-7" (get-in (first pending) [:params :requestId])))
        ;; String result is normalized through normalize-tool-result
        (is (= "MANUAL_STATUS_OK"
               (get-in (first pending) [:params :result :textResultForLlm]))))))

  (testing "handle-pending-tool-call! with :error forwards error string"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-tool-call! session
                                     {:request-id "tool-req-8"
                                      :error "manual failure"})
      (let [pending (first (filter #(= "session.tools.handlePendingToolCall" (:method %))
                                   @requests))]
        (is (= "manual failure" (get-in pending [:params :error])))
        (is (not (contains? (:params pending) :result))))))

  (testing "handle-pending-tool-call! rejects :result and :error together"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-tool-call!
                    session
                    {:request-id "x" :result "a" :error "b"})))))

  (testing "handle-pending-tool-call! rejects non-string :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":error must be a string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x" :error {:code 1}})))
      (is (thrown-with-msg? Exception #":error must be a string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x" :error 42})))))

  (testing "handle-pending-tool-call! requires :result or :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"exactly one of :result or :error"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x"})))))

  (testing "<handle-pending-tool-call! requires :result or :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"exactly one of :result or :error"
                            (sdk/<handle-pending-tool-call!
                             session
                             {:request-id "x"})))))

  (testing "handle-pending-tool-call! rejects blank :request-id"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "" :result "ok"})))
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "   " :result "ok"}))))))

;; =============================================================================
;; Upstream schema 1.0.56-1 sync (post-v1.0.0-beta.4 round 6)
;; =============================================================================
;;
;; Covers upstream tags v1.0.0-beta.9 and v1.0.0-beta.10. PRs referenced inline.

;; --- :agent-mode and :display-prompt on send! (PRs #1438, #1470) -----------

(deftest test-send-agent-mode-on-wire
  (testing "send! forwards :agent-mode as wire :agentMode (upstream PR #1438)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (doseq [mode [:interactive :plan :autopilot :shell]]
        (reset! seen {})
        (sdk/send! session {:prompt "hi" :agent-mode mode})
        (is (= (name mode) (:agentMode (get @seen "session.send")))
            (str "agentMode should be " (name mode))))))

  (testing "send! omits agentMode when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "hi"})
          send-params (get @seen "session.send")]
      (is (not (contains? send-params :agentMode))))))

(deftest test-send-display-prompt-on-wire
  (testing "send! forwards :display-prompt as wire :displayPrompt (upstream PR #1470)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "internal model prompt"
                                :display-prompt "What you see in the timeline"})
          send-params (get @seen "session.send")]
      (is (= "What you see in the timeline" (:displayPrompt send-params))))))

(deftest test-spec-send-options-accepts-agent-mode-and-display-prompt
  (testing "::send-options accepts :agent-mode keyword (PR #1438)"
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :interactive}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :plan}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :autopilot}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :shell}))
    (is (not (s/valid? ::specs/send-options {:prompt "x" :agent-mode :invalid}))))
  (testing "::send-options accepts :display-prompt string (PR #1470)"
    (is (s/valid? ::specs/send-options {:prompt "x" :display-prompt "shown"}))
    (is (not (s/valid? ::specs/send-options {:prompt "x" :display-prompt 42})))))

;; --- mcpOAuthTokenStorage (PR #1326) ----------------------------------------

(deftest test-mcp-oauth-token-storage-on-wire
  (testing ":mcp-oauth-token-storage forwarded as wire string (upstream PR #1326)"
    (doseq [[kw expected-wire] [[:persistent "persistent"]
                                [:in-memory "in-memory"]]]
      (let [seen (atom {})
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (when (= "session.create" method)
                                          (swap! seen assoc method params))))
            _ (sdk/create-session *test-client*
                                  {:on-permission-request sdk/approve-all
                                   :mcp-oauth-token-storage kw})
            create-params (get @seen "session.create")]
        (is (= expected-wire (:mcpOAuthTokenStorage create-params))
            (str ":mcp-oauth-token-storage " kw " should wire as \"" expected-wire "\" "
                 "(not csk-camelized — must preserve hyphen)")))))

  (testing ":mcp-oauth-token-storage forwarded on session.resume too"
    (let [seen (atom {})
          ;; First create a session to resume
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :mcp-oauth-token-storage :in-memory})
          resume-params (get @seen "session.resume")]
      (is (= "in-memory" (:mcpOAuthTokenStorage resume-params))))))

(deftest test-spec-mcp-oauth-token-storage
  (testing "::mcp-oauth-token-storage spec accepts :persistent / :in-memory"
    (is (s/valid? ::specs/mcp-oauth-token-storage :persistent))
    (is (s/valid? ::specs/mcp-oauth-token-storage :in-memory))
    (is (not (s/valid? ::specs/mcp-oauth-token-storage :invalid)))
    (is (not (s/valid? ::specs/mcp-oauth-token-storage "in-memory")))))

;; --- Multitenancy flags (PR #1474) -----------------------------------------

(deftest test-multitenancy-flags-on-wire
  (testing "All multitenancy flags forwarded on session.create (upstream PR #1474)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :skip-embedding-retrieval true
                                 :embedding-cache-storage :in-memory
                                 :organization-custom-instructions "You are an Acme assistant."
                                 :enable-on-demand-instruction-discovery true
                                 :enable-file-hooks false
                                 :enable-host-git-operations false
                                 :enable-session-store false
                                 :enable-skills false})
          create-params (get @seen "session.create")]
      (is (= true (:skipEmbeddingRetrieval create-params)))
      (is (= "in-memory" (:embeddingCacheStorage create-params))
          ":embedding-cache-storage must preserve hyphen on wire")
      (is (= "You are an Acme assistant." (:organizationCustomInstructions create-params)))
      (is (= true (:enableOnDemandInstructionDiscovery create-params)))
      (is (= false (:enableFileHooks create-params)))
      (is (= false (:enableHostGitOperations create-params)))
      (is (= false (:enableSessionStore create-params)))
      (is (= false (:enableSkills create-params)))))

  (testing "Multitenancy flags omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (doseq [k [:skipEmbeddingRetrieval :embeddingCacheStorage
                 :organizationCustomInstructions :enableOnDemandInstructionDiscovery
                 :enableFileHooks :enableHostGitOperations :enableSessionStore
                 :enableSkills]]
        (is (not (contains? create-params k))
            (str "Should NOT contain " k " when not set"))))))

(deftest test-spec-multitenancy-flags
  (testing "All multitenancy flag specs accept correct values (PR #1474)"
    (is (s/valid? ::specs/skip-embedding-retrieval true))
    (is (s/valid? ::specs/embedding-cache-storage :in-memory))
    (is (s/valid? ::specs/embedding-cache-storage :persistent))
    (is (s/valid? ::specs/organization-custom-instructions "anything"))
    (is (s/valid? ::specs/enable-on-demand-instruction-discovery true))
    (is (s/valid? ::specs/enable-file-hooks true))
    (is (s/valid? ::specs/enable-host-git-operations true))
    (is (s/valid? ::specs/enable-session-store true))
    (is (s/valid? ::specs/enable-skills true))
    (is (not (s/valid? ::specs/embedding-cache-storage "in-memory")))
    (is (not (s/valid? ::specs/enable-file-hooks "true")))))

;; --- :plugin-directories (PR #1482 partial) ---------------------------------

(deftest test-plugin-directories-on-wire
  (testing ":plugin-directories forwarded as wire :pluginDirectories (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :plugin-directories ["/etc/copilot/plugins"
                                                      "/usr/local/share/copilot/plugins"]})
          create-params (get @seen "session.create")]
      (is (= ["/etc/copilot/plugins" "/usr/local/share/copilot/plugins"]
             (:pluginDirectories create-params)))))

  (testing ":plugin-directories also forwarded on session.resume"
    (let [seen (atom {})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :plugin-directories ["/tmp/plugins"]})
          resume-params (get @seen "session.resume")]
      (is (= ["/tmp/plugins"] (:pluginDirectories resume-params))))))

(deftest test-spec-plugin-directories
  (testing "::plugin-directories spec rejects empty strings and non-collections"
    (is (s/valid? ::specs/plugin-directories ["/a/b"]))
    (is (s/valid? ::specs/plugin-directories []))
    (is (not (s/valid? ::specs/plugin-directories ["" "/a"])))
    (is (not (s/valid? ::specs/plugin-directories "/a/b")))))

;; --- Pre-existing parity gaps: reasoningSummary, contextTier, resume largeOutput ---

(deftest test-reasoning-summary-on-wire
  (testing ":reasoning-summary forwarded on create and resume (parity gap)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! seen assoc method params)))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :reasoning-summary "detailed"})
          session-id (sdk/session-id session)]
      (is (= "detailed" (:reasoningSummary (get @seen "session.create"))))
      (reset! seen {})
      (sdk/resume-session *test-client* session-id
                          {:on-permission-request sdk/approve-all
                           :reasoning-summary "concise"})
      (is (= "concise" (:reasoningSummary (get @seen "session.resume")))))))

(deftest test-context-tier-on-wire
  (testing ":context-tier forwarded on create and resume (parity gap)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! seen assoc method params)))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :context-tier :long-context})
          session-id (sdk/session-id session)]
      ;; Wire enum uses underscore per schema: "default" | "long_context"
      (is (= "long_context" (:contextTier (get @seen "session.create"))))
      (reset! seen {})
      (sdk/resume-session *test-client* session-id
                          {:on-permission-request sdk/approve-all
                           :context-tier :default})
      (is (= "default" (:contextTier (get @seen "session.resume")))))))

(deftest test-resume-large-output-on-wire
  (testing ":large-output forwarded on session.resume (parity gap; was create-only)"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :max-size-bytes 16384}})
          resume-params (get @seen "session.resume")]
      (is (= true (get-in resume-params [:largeOutput :enabled])))
      (is (= 16384 (get-in resume-params [:largeOutput :maxSizeBytes]))))))

(deftest test-spec-reasoning-summary-and-context-tier
  (testing "::reasoning-summary accepts upstream enum values"
    (is (s/valid? ::specs/reasoning-summary "none"))
    (is (s/valid? ::specs/reasoning-summary "concise"))
    (is (s/valid? ::specs/reasoning-summary "detailed"))
    (is (not (s/valid? ::specs/reasoning-summary "auto")))
    (is (not (s/valid? ::specs/reasoning-summary 42))))
  (testing "::context-tier accepts :default and :long-context"
    (is (s/valid? ::specs/context-tier :default))
    (is (s/valid? ::specs/context-tier :long-context))
    (is (not (s/valid? ::specs/context-tier :other)))
    (is (not (s/valid? ::specs/context-tier "default")))))

;; --- :config-directory / :output-directory rename (PR #1482) ---------------

(deftest test-config-directory-rename-on-wire
  (testing ":config-directory (new spelling) wires as :configDir (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :config-directory "/my/config"})
          create-params (get @seen "session.create")]
      (is (= "/my/config" (:configDir create-params)))
      (is (not (contains? create-params :configDirectory))
          "Wire must NOT include configDirectory (wire stays configDir)")))

  (testing ":config-dir (deprecated alias) continues to work as :configDir"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :config-dir "/legacy/config"})
          create-params (get @seen "session.create")]
      (is (= "/legacy/config" (:configDir create-params))
          "Backward-compat: :config-dir still wires as :configDir")))

  (testing "On resume, :config-directory also wires as :configDir"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :config-directory "/resumed/config"})
          resume-params (get @seen "session.resume")]
      (is (= "/resumed/config" (:configDir resume-params))))))

(deftest test-output-directory-rename-on-wire
  (testing ":output-directory inside :large-output wires as :outputDir (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :output-directory "/tmp/out"}})
          create-params (get @seen "session.create")]
      (is (= "/tmp/out" (get-in create-params [:largeOutput :outputDir])))
      (is (not (contains? (:largeOutput create-params) :outputDirectory)))))

  (testing ":output-dir (deprecated alias) continues to work as :outputDir"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :output-dir "/legacy/out"}})
          create-params (get @seen "session.create")]
      (is (= "/legacy/out" (get-in create-params [:largeOutput :outputDir]))))))

(deftest test-spec-config-and-output-directory-aliases
  (testing "::session-config accepts both :config-directory and :config-dir"
    (is (s/valid? ::specs/session-config {:config-directory "/a"}))
    (is (s/valid? ::specs/session-config {:config-dir "/a"})))
  (testing "::large-output accepts both :output-directory and :output-dir"
    (is (s/valid? ::specs/large-output {:enabled true :output-directory "/a"}))
    (is (s/valid? ::specs/large-output {:enabled true :output-dir "/a"}))))

;; --- New event types (schema 1.0.56-1) -------------------------------------

(deftest test-new-event-types-in-public-set
  (testing "Schema 1.0.56-1 event types are exposed in public event-types set"
    (is (contains? sdk/event-types :copilot/session.autopilot_objective_changed))
    (is (contains? sdk/event-types :copilot/session.permissions_changed))
    (is (contains? sdk/event-types :copilot/hook.progress))))

(deftest test-spec-session-permissions-changed-data
  (testing "::session.permissions_changed-data accepts the documented shape"
    ;; Schema requires :allow-all-permissions and :previous-allow-all-permissions
    (let [evt {:allow-all-permissions true
               :previous-allow-all-permissions false}]
      (is (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data evt)))
    (is (not (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                       {:allow-all-permissions "yes"
                        :previous-allow-all-permissions false})))))

(deftest test-spec-hook-progress-data
  (testing "::hook.progress-data accepts a non-blank :message string"
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "uploading 50%"}))
    (is (not (s/valid? :github.copilot-sdk.specs/hook.progress-data
                       {:message ""})))
    (is (not (s/valid? :github.copilot-sdk.specs/hook.progress-data
                       {:message 42})))))

(deftest test-generated-session-resume-data-context-tier
  (testing "generated session.resume-data accepts wire :context-tier values"
    ;; The curated ::specs/session.resume-data deliberately does NOT lift
    ;; :context-tier into the idiom layer (matches the round-5 pattern for
    ;; ::session.model_change-data: event payloads carry the wire string).
    ;; The generated wire spec is the source of truth for the field shape.
    (let [spec :github.copilot-sdk.generated.event-specs/session.resume-data]
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier "long_context"}))
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier "default"}))
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier nil}))
      (is (not (s/valid? spec
                         {:resume-time "2026-05-01T00:00:00Z"
                          :event-count 0
                          :context-tier "bogus"}))))))

;; --- Cloud session: defer sessionId to server (PR #1479) -------------------

(deftest test-cloud-session-omits-session-id-on-wire
  (testing "Cloud session without :session-id omits sessionId from session.create (upstream PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :cloud {:repository {:owner "octocat"
                                                            :name "hello"
                                                            :branch "main"}}})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :sessionId))
          "Cloud-no-id path must omit sessionId; server assigns one")
      ;; The mock-server returns whatever id it generates; verify our session
      ;; was registered with that server-assigned id
      (is (string? (sdk/session-id session)))
      (is (.startsWith ^String (sdk/session-id session) "session-")
          "Session id should come from mock server (prefix 'session-'), not a UUID"))))

(deftest test-cloud-session-with-caller-supplied-id-is-sent
  (testing "Cloud session WITH :session-id sends sessionId on wire (PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          custom-id "caller-supplied-id-xyz"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :cloud {}
                                       :session-id custom-id})
          create-params (get @seen "session.create")]
      (is (= custom-id (:sessionId create-params)))
      (is (= custom-id (sdk/session-id session))))))

(deftest test-non-cloud-session-still-generates-uuid
  (testing "Non-cloud session still gets a client-generated UUID (unchanged path)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (string? (:sessionId create-params)))
      ;; Should be a UUID, not a server-generated id
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                      (:sessionId create-params)))
      (is (= (:sessionId create-params) (sdk/session-id session))))))

(deftest test-async-cloud-session-omits-session-id-on-wire
  (testing "<create-session cloud-no-id path omits sessionId and adopts server-assigned id (PR #1479)"
    ;; Async branch has separate promise/cleanup logic and uses the 4-arity
    ;; proto/send-request options path, so the sync cloud-no-id coverage does
    ;; not exercise it. Mirror test-cloud-session-omits-session-id-on-wire for
    ;; <create-session.
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          result-ch (sdk/<create-session *test-client*
                                         {:on-permission-request sdk/approve-all
                                          :cloud {:repository {:owner "octocat"
                                                               :name "hello"
                                                               :branch "main"}}})
          [session _] (alts!! [result-ch (timeout 5000)])
          create-params (get @seen "session.create")]
      (is (some? session) "<create-session should deliver a session")
      (is (not (instance? Throwable session))
          (str "<create-session cloud-no-id should not return an error: " session))
      (is (not (contains? create-params :sessionId))
          "Async cloud-no-id path must omit sessionId; server assigns one")
      (is (string? (sdk/session-id session)))
      (is (.startsWith ^String (sdk/session-id session) "session-")
          "Session id should come from mock server (prefix 'session-'), not a UUID")
      (sdk/destroy! session))))

(deftest test-async-cloud-session-with-caller-supplied-id-is-sent
  (testing "<create-session WITH :session-id sends sessionId on wire (PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          custom-id "async-caller-supplied-id-xyz"
          result-ch (sdk/<create-session *test-client*
                                         {:on-permission-request sdk/approve-all
                                          :cloud {}
                                          :session-id custom-id})
          [session _] (alts!! [result-ch (timeout 5000)])
          create-params (get @seen "session.create")]
      (is (not (instance? Throwable session))
          (str "<create-session with caller-supplied id should not return an error: " session))
      (is (= custom-id (:sessionId create-params)))
      (is (= custom-id (sdk/session-id session)))
      (sdk/destroy! session))))

;; -----------------------------------------------------------------------------
;; Client Mode (upstream PR #1428) — constructor + validation tests.
;; The session-time options.update orchestration and wire-shape parity tests
;; live further down once that plumbing lands; these are the pure-validation
;; checks (client construction + bare-`*` rejection + required :available-tools).
;; -----------------------------------------------------------------------------

(deftest test-empty-mode-requires-tenant-scoped-storage
  (testing "construction without storage hook throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mode :empty requires"
         (sdk/client {:mode :empty :auto-start? false}))))
  (testing ":copilot-home satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))
      (is (= "/tmp/empty-mode-test" (get-in c [:options :copilot-home])))))
  (testing ":session-fs satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :session-fs {:initial-cwd "/workspace"
                                      :session-state-path "/state"
                                      :conventions "posix"}
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))))
  (testing ":cli-url satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :cli-url "localhost:1234"
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))))
  (testing ":is-child-process? satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :is-child-process? true
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode]))))))

(deftest test-empty-mode-default-and-cli-mode
  (testing "default mode is :copilot-cli (no extra options needed)"
    (let [c (sdk/client {:auto-start? false})]
      ;; :mode is not auto-injected; absence is treated as :copilot-cli by
      ;; downstream code. The point is that no extra options are required.
      (is (nil? (get-in c [:options :mode]))
          ":mode should be unset by default — downstream code treats nil as :copilot-cli")))
  (testing "explicit :copilot-cli mode imposes no extra requirements"
    (let [c (sdk/client {:mode :copilot-cli :auto-start? false})]
      (is (= :copilot-cli (get-in c [:options :mode]))))))

(deftest test-validation-errors-redact-secrets
  (testing "secrets never appear in validation exception data or messages (SEC-1)"
    (let [token "ghp_SUPERSECRETtoken123"
          tcp-token "tcptokenSECRET000"
          azure-key "sk-azureSECRET456"
          mcp-secret "Bearer mcpSECRET789"
          dump (fn [^Throwable e] (pr-str (Throwable->map e)))
          leaked? (fn [^Throwable e ^String s]
                    (or (.contains (str (ex-message e)) s)
                        (.contains ^String (dump e) s)))
          capture (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))]
      (testing "client-options spec-explain path"
        (let [e (capture #(sdk/client {:github-token token :log-level :bogus :auto-start? false}))]
          (is (some? e) "expected a validation failure")
          (is (not (leaked? e token)) "github-token must be redacted")))
      (testing "client-options unknown-keys path"
        (let [e (capture #(sdk/client {:github-token token :totally-unknown-key 1 :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e token)) "github-token must be redacted")))
      (testing "client-options mutual-exclusion raw-opts path"
        (let [e (capture #(sdk/client {:cli-url "localhost:1234" :use-stdio? true
                                       :tcp-connection-token tcp-token :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e tcp-token)) "tcp-connection-token must be redacted")))
      (testing "session-config BYOK provider api-key"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/create-session c {:provider {:provider-type "azure" :api-key azure-key}}))]
          (is (some? e))
          (is (not (leaked? e azure-key)) "BYOK :api-key must be redacted")))
      (testing "session-config BYOK :providers registry secrets (v1.0.4)"
        ;; Item 4 added the multi-provider :providers registry; each named
        ;; provider carries the same secret-bearing fields as the singular
        ;; :provider. A validation failure (tripped here by an unknown key)
        ;; must redact them all.
        (let [c (sdk/client {:auto-start? false})
              named-key "sk-namedSECRET789"
              named-bearer "namedBearerSECRET321"
              named-hdr "Bearer namedHdrSECRET654"
              e (capture #(sdk/create-session
                           c {:providers [{:name "openai" :base-url "https://o.test"
                                           :api-key named-key
                                           :bearer-token named-bearer
                                           :headers {"Authorization" named-hdr}}]
                              :totally-unknown-key 1}))]
          (is (some? e) "an unknown key should fail validation")
          (is (not (leaked? e named-key)) "named provider :api-key must be redacted")
          (is (not (leaked? e named-bearer)) "named provider :bearer-token must be redacted")
          (is (not (leaked? e named-hdr)) "named provider :headers value must be redacted")))
      (testing "session-config BYOK :providers as a non-sequential collection (set)"
        ;; redact-secrets runs on the *already-invalid* config in the error path,
        ;; so it must not rely on ::providers being sequential. A set of providers
        ;; (still a valid ::coll-of) must have its secrets masked too.
        (let [c (sdk/client {:auto-start? false})
              set-key "sk-setSECRET111"]
          (let [e (capture #(sdk/create-session
                             c {:providers #{{:name "openai" :base-url "https://o.test"
                                              :api-key set-key}}
                                :totally-unknown-key 1}))]
            (is (some? e) "an unknown key should fail validation")
            (is (not (leaked? e set-key))
                "set-valued :providers entry :api-key must still be redacted"))))
      (testing "session-config BYOK :providers as an (invalid) map of name->config"
        ;; The valid ::providers shape is a sequential collection, but
        ;; redact-secrets runs on the *already-invalid* config in the error
        ;; path. A caller mistake of passing a map (name->provider-config) must
        ;; still have its secret-bearing values masked, not leaked verbatim.
        (let [c (sdk/client {:auto-start? false})
              map-key "sk-mapSECRET222"
              map-bearer "mapBearerSECRET333"
              map-hdr "Bearer mapHdrSECRET444"]
          (let [e (capture #(sdk/create-session
                             c {:providers {:openai {:base-url "https://o.test"
                                                     :api-key map-key
                                                     :bearer-token map-bearer
                                                     :headers {"Authorization" map-hdr}}}}))]
            (is (some? e) "a map-valued :providers should fail validation")
            (is (not (leaked? e map-key))
                "map-valued :providers entry :api-key must still be redacted")
            (is (not (leaked? e map-bearer))
                "map-valued :providers entry :bearer-token must still be redacted")
            (is (not (leaked? e map-hdr))
                "map-valued :providers entry :headers value must still be redacted"))))
      (testing "resume-config BYOK provider api-key"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/resume-session c "sid" {:provider {:provider-type "azure" :api-key azure-key}}))]
          (is (some? e))
          (is (not (leaked? e azure-key)) "BYOK :api-key must be redacted")))
      (testing "mcp-servers header secret"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/create-session c {:mcp-servers {"s" {:mcp-headers {"Authorization" mcp-secret}}}}))]
          (is (some? e))
          (is (not (leaked? e mcp-secret)) "MCP header secret must be redacted")))
      (testing "client-options :env secret value"
        ;; :env is merged into the spawned CLI environment, so it can carry
        ;; credentials; a validation failure must not leak them via ex-data.
        (let [env-secret "ghp_ENVSECRET999"
              e (capture #(sdk/client {:env {"GH_TOKEN" env-secret} :log-level :bogus :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e env-secret)) ":env secret value must be redacted")))
      (testing "blank/invalid secret value still produces a useful spec error"
        ;; A blank :github-token fails the ::non-blank-string spec. Redaction must
        ;; NOT mask it to "***" (which would make the map look valid and suppress
        ;; the explanation); blank values carry no secret to leak.
        (let [e (capture #(sdk/client {:github-token "" :auto-start? false}))]
          (is (some? e) "blank github-token should fail validation")
          (is (.contains (str (ex-message e)) "github-token")
              "the error should still point at :github-token"))))))

(deftest test-failed-start-releases-resources
  (testing "a failed start! tears down the spawned process and connection (A5)"
    ;; `true` exits 0 immediately, so in TCP mode wait-for-port observes the
    ;; process die before announcing a port and start! throws after spawning.
    (let [c (sdk/client {:cli-path "true" :use-stdio? false :auto-start? false})]
      (is (thrown? Exception (sdk/start! c)))
      (let [st @(:state c)]
        (is (= :error (:status st)) "status should be :error after a failed start")
        (is (nil? (:process st)) "spawned process must be released")
        (is (nil? (:connection-io st)) "connection must be released")
        (is (nil? (:socket st)) "socket must be released")))))

(deftest test-empty-mode-spec-validation
  (testing "an unknown :mode value is rejected by the spec"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid client options"
         (sdk/client {:mode :bogus :copilot-home "/tmp/x" :auto-start? false})))))

(deftest test-bare-star-rejected-in-available-tools
  (testing ":available-tools containing bare * is rejected at create-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid availableTools entry '\*'"
           (sdk/create-session c {:available-tools ["*"]}))))))

(deftest test-bare-star-rejected-in-excluded-tools
  (testing ":excluded-tools containing bare * is rejected at create-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid excludedTools entry '\*'"
           (sdk/create-session c {:excluded-tools ["*"]}))))))

(deftest test-bare-star-rejected-in-resume-session
  (testing ":available-tools containing bare * is rejected at resume-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid availableTools entry '\*'"
           (sdk/resume-session c "session-id" {:available-tools ["*"]}))))))

(deftest test-source-qualified-tools-accepted
  (testing "source-qualified patterns pass the bare-* validation"
    ;; We can't fully exercise create-session without a started client, but
    ;; the validation step throws BEFORE ensure-connected!, so a non-throw
    ;; below means we made it past validate-tool-filters! (further work
    ;; will hit the connection check).
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {:available-tools ["builtin:*" "mcp:my_server"]}))))))

(deftest test-empty-mode-requires-available-tools-on-create
  (testing "empty mode rejects create-session without :available-tools"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Mode :empty requires every session to specify :available-tools"
           (sdk/create-session c {}))))))

(deftest test-empty-mode-allows-empty-available-tools
  (testing "empty mode accepts :available-tools [] as explicit opt-in to no tools"
    ;; The validation passes; downstream will fail because client is not
    ;; started. Distinguish those errors by message.
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {:available-tools []}))))))

(deftest test-empty-mode-requires-available-tools-on-resume
  (testing "empty mode rejects resume-session without :available-tools"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Mode :empty requires every session to specify :available-tools"
           (sdk/resume-session c "session-id" {}))))))

(deftest test-cli-mode-does-not-require-available-tools
  (testing "cli mode allows create-session without :available-tools"
    (let [c (sdk/client {:auto-start? false})]
      ;; Validation passes; downstream throws because not started.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {}))))))

;; -----------------------------------------------------------------------------
;; Client Mode (upstream PR #1428) — wire payload tests:
;;   - `tool-filter-precedence` is always emitted as "excluded" (both modes).
;;   - In `:empty` mode the 9 mode defaults are spread under caller config.
;; -----------------------------------------------------------------------------

(deftest test-tool-filter-precedence-always-excluded-on-create
  (testing "session.create always sends toolFilterPrecedence=\"excluded\" (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (= "excluded" (:toolFilterPrecedence create-params))
          "tool-filter-precedence must always be \"excluded\" in CLI mode"))))

(deftest test-tool-filter-precedence-always-excluded-on-resume
  (testing "session.resume always sends toolFilterPrecedence=\"excluded\" (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (= "excluded" (:toolFilterPrecedence resume-params))
          "tool-filter-precedence must always be \"excluded\" on resume"))))

(deftest test-empty-mode-spreads-config-defaults-under-caller
  (testing "empty mode spreads the 10 safe defaults under caller config (PR #1428)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-wire-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]})
        (let [p (get @seen "session.create")]
          (is (= false (:enableSessionTelemetry p)))
          (is (= "in-memory" (:mcpOAuthTokenStorage p)))
          (is (= true (:skipEmbeddingRetrieval p)))
          (is (= "in-memory" (:embeddingCacheStorage p)))
          (is (= false (:enableOnDemandInstructionDiscovery p)))
          (is (= false (:enableFileHooks p)))
          (is (= false (:enableHostGitOperations p)))
          (is (= false (:enableSessionStore p)))
          (is (= false (:enableSkills p)))
          (is (= {:enabled false} (:memory p))
              "empty mode disables persistent memory by default")
          (is (= "excluded" (:toolFilterPrecedence p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-memory-default-on-create-and-resume
  (testing "empty mode sends memory {:enabled false} by default on create and resume,
            and the caller can override it (upstream configDefaultsForMode)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-memory-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        ;; Default: no caller :memory -> empty-mode default flows to the wire.
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]})
        (is (= {:enabled false} (:memory (get @seen "session.create")))
            "create defaults memory to {:enabled false} in empty mode")
        (let [session-id (sdk/get-last-session-id client)]
          (sdk/resume-session client session-id
                              {:on-permission-request sdk/approve-all
                               :available-tools ["builtin:think"]})
          (is (= {:enabled false} (:memory (get @seen "session.resume")))
              "resume defaults memory to {:enabled false} in empty mode"))
        ;; Caller override wins over the mode default.
        (reset! seen {})
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]
                             :memory {:enabled true}})
        (is (= {:enabled true} (:memory (get @seen "session.create")))
            "caller-provided :memory overrides the empty-mode default")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-caller-config-wins-over-mode-defaults
  (testing "caller-provided config values always win over mode defaults (PR #1428)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-override-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        ;; Caller overrides 2 of the 9 defaults — those values must win.
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]
                             :enable-session-telemetry? true
                             :enable-skills true})
        (let [p (get @seen "session.create")]
          (is (= true (:enableSessionTelemetry p))
              "caller-provided :enable-session-telemetry? must override the mode default")
          (is (= true (:enableSkills p))
              "caller-provided :enable-skills must override the mode default")
          ;; Other defaults still apply
          (is (= true (:skipEmbeddingRetrieval p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-cli-mode-does-not-spread-mode-defaults
  (testing "CLI mode does NOT spread the empty-mode defaults (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          p (get @seen "session.create")]
      ;; None of the 9 mode-default flags should appear unless caller set them.
      (is (not (contains? p :enableSessionTelemetry)))
      (is (not (contains? p :skipEmbeddingRetrieval)))
      (is (not (contains? p :enableFileHooks)))
      (is (not (contains? p :enableSkills))))))

;; -----------------------------------------------------------------------------
;; Client Mode (upstream PR #1428) — system message normalization tests.
;; In :empty mode, the SDK enforces that environment_context is stripped from
;; the system message (unless app has taken control of it). Mirrors upstream
;; `getSystemMessageConfigForMode`.
;; -----------------------------------------------------------------------------

(defn- empty-mode-create-with-system-message
  "Spin up a fresh empty-mode client against a fresh mock server, invoke
   create-session with the supplied :system-message, and return the
   captured wire payload's :systemMessage field."
  [system-message]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        seen (atom {})
        _ (mock/set-request-hook! server
                                  (fn [method params]
                                    (when (= "session.create" method)
                                      (swap! seen assoc method params))))
        client (sdk/client {:mode :empty
                            :copilot-home "/tmp/empty-mode-sm-test"
                            :auto-start? false})
        [in out] (mock/client-streams server)]
    (try
      (client/connect-with-streams! client in out)
      (sdk/create-session client
                          (cond-> {:on-permission-request sdk/approve-all
                                   :available-tools []}
                            system-message (assoc :system-message system-message)))
      (-> (get @seen "session.create") :systemMessage)
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (Thread/sleep 50)
        (mock/stop-mock-server! server)))))

(deftest test-empty-mode-system-message-default
  (testing "no caller :system-message → emit customize with env-context removed"
    (let [sm (empty-mode-create-with-system-message nil)]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))))))

(deftest test-empty-mode-system-message-replace-passes-through
  (testing ":replace mode is preserved unchanged in empty mode"
    (let [sm (empty-mode-create-with-system-message
              {:mode :replace :content "Replacement text."})]
      (is (= "replace" (:mode sm)))
      (is (= "Replacement text." (:content sm)))
      (is (not (contains? sm :sections))))))

(deftest test-empty-mode-system-message-append-promoted-to-customize
  (testing ":append mode is promoted to :customize, content preserved, env-context removed"
    (let [sm (empty-mode-create-with-system-message
              {:mode :append :content "Extra instructions."})]
      (is (= "customize" (:mode sm)))
      (is (= "Extra instructions." (:content sm))
          "caller :content must be preserved verbatim")
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))))))

(deftest test-empty-mode-system-message-customize-adds-env-context-remove
  (testing ":customize without env-context override → SDK adds env-context remove"
    (let [sm (empty-mode-create-with-system-message
              {:mode :customize
               :sections {:tone {:action :replace :content "Be terse."}}})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))
          "env-context section must be removed")
      (is (some? (get-in sm [:sections :tone]))
          "caller's :tone section must be preserved"))))

(deftest test-empty-mode-system-message-customize-no-sections-key
  (testing ":customize with NO :sections key at all → SDK adds :sections with env-context remove"
    (let [sm (empty-mode-create-with-system-message {:mode :customize})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))
          "env-context section must be added even when caller omits :sections entirely"))))

(deftest test-empty-mode-system-message-customize-respects-app-env-context
  (testing ":customize with explicit env-context override → SDK does NOT touch it"
    (let [sm (empty-mode-create-with-system-message
              {:mode :customize
               :sections {:environment-context {:action :replace :content "Custom env."}}})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "replace" :content "Custom env."}
             (get-in sm [:sections :environment_context]))
          "app's env-context override must win unchanged"))))

(deftest test-cli-mode-system-message-untouched
  (testing "CLI mode does NOT normalize :system-message (preserve historical behavior)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :append
                                                  :content "Just append this."}})
          sm (-> (get @seen "session.create") :systemMessage)]
      (is (= "append" (:mode sm)) "CLI mode keeps :append, no promotion")
      (is (= "Just append this." (:content sm))))))

;; -----------------------------------------------------------------------------
;; Client Mode (upstream PR #1428) — session.options.update RPC tests.
;; After session.create succeeds, the SDK issues a follow-up RPC to set the 4
;; overridable feature flags and (in :empty mode) the installedPlugins list.
;; Mirrors upstream `updateSessionOptionsForMode`.
;; -----------------------------------------------------------------------------

(defn- empty-mode-capture-options-update
  "Spin up a fresh empty-mode client, create a session with the given extra
   config map, and return the captured session.options.update wire params
   (or nil if the RPC was not issued)."
  [extra-config]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        seen (atom nil)
        _ (mock/set-request-hook! server
                                  (fn [method params]
                                    (when (= "session.options.update" method)
                                      (reset! seen params))))
        client (sdk/client {:mode :empty
                            :copilot-home "/tmp/empty-mode-opts-test"
                            :auto-start? false})
        [in out] (mock/client-streams server)]
    (try
      (client/connect-with-streams! client in out)
      (sdk/create-session client
                          (merge {:on-permission-request sdk/approve-all
                                  :available-tools []}
                                 extra-config))
      @seen
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (Thread/sleep 50)
        (mock/stop-mock-server! server)))))

(deftest test-empty-mode-options-update-defaults
  (testing "empty mode sends session.options.update with safe flag defaults + empty plugins"
    (let [p (empty-mode-capture-options-update {})]
      (is (some? p) "session.options.update must be issued in :empty mode")
      (is (= true (:skipCustomInstructions p)))
      (is (= true (:customAgentsLocalOnly p)))
      (is (= false (:coauthorEnabled p)))
      (is (= false (:manageScheduleEnabled p)))
      (is (= [] (:installedPlugins p)))
      (is (string? (:sessionId p)) "session-id must accompany the patch"))))

(deftest test-empty-mode-options-update-caller-overrides
  (testing "caller-supplied flags override empty-mode defaults in options.update patch"
    (let [p (empty-mode-capture-options-update
             {:skip-custom-instructions false
              :coauthor-enabled true})]
      (is (= false (:skipCustomInstructions p)) "caller false must win over default true")
      (is (= true (:coauthorEnabled p)) "caller true must win over default false")
      (is (= true (:customAgentsLocalOnly p)) "untouched flags keep their defaults")
      (is (= false (:manageScheduleEnabled p)))
      (is (= [] (:installedPlugins p)) "installedPlugins always forced to [] in :empty mode"))))

(deftest test-cli-mode-options-update-skipped-when-no-caller-flags
  (testing "CLI mode does NOT issue session.options.update when caller sets no flags"
    (let [seen (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})]
      (Thread/sleep 100)
      (is (nil? @seen)
          "no RPC should be sent when the patch would be empty"))))

(deftest test-cli-mode-options-update-forwards-only-explicit-flags
  (testing "CLI mode forwards ONLY caller-supplied flags via options.update (no defaults, no installedPlugins)"
    (let [seen (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :manage-schedule-enabled true})
          p @seen]
      (is (some? p) "RPC should be issued because caller set a flag")
      (is (= true (:manageScheduleEnabled p)))
      (is (not (contains? p :skipCustomInstructions)) "untouched flags must NOT appear")
      (is (not (contains? p :customAgentsLocalOnly)))
      (is (not (contains? p :coauthorEnabled)))
      (is (not (contains? p :installedPlugins))
          "installedPlugins must NOT be forced in :copilot-cli mode"))))

(deftest test-empty-mode-options-update-async-path
  (testing "async <create-session also issues session.options.update with mode defaults"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom nil)
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-async-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [result-ch (sdk/<create-session client
                                             {:on-permission-request sdk/approve-all
                                              :available-tools []})
              result (first (alts!! [result-ch (timeout 5000)]))]
          (is (some? result) "async create-session must return a session")
          (is (not (instance? Throwable result))
              (str "async create-session failed: " result)))
        (let [p @seen]
          (is (some? p) "options.update must be issued in async path")
          (is (= true (:skipCustomInstructions p)))
          (is (= [] (:installedPlugins p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-options-update-failure-cleans-up-session
  (testing "options.update RPC failure cleans up session (disconnect + remove) and rethrows"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          _ (mock/set-request-hook! server
                                    (fn [method _params]
                                      (when (= "session.options.update" method)
                                        (throw (ex-info "Simulated options.update failure"
                                                        {:code -32603})))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-fail-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [ex (try
                   (sdk/create-session client
                                       {:on-permission-request sdk/approve-all
                                        :available-tools []})
                   nil
                   (catch Throwable t t))]
          (is (some? ex) "create-session must rethrow on options.update failure")
          (is (re-find #"options\.update" (.getMessage ex))
              "exception message should mention options.update"))
        ;; After failure, the SDK should have removed the half-configured session
        ;; from its in-memory registry.
        (is (empty? (:sessions @(:state client)))
            "failed session must be removed from in-memory registry")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-options-update-async-failure-cleans-up-session
  (testing "async <create-session: options.update failure cleans up session and yields Throwable"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          _ (mock/set-request-hook! server
                                    (fn [method _params]
                                      (when (= "session.options.update" method)
                                        (throw (ex-info "Simulated options.update failure"
                                                        {:code -32603})))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-async-fail-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [result-ch (sdk/<create-session client
                                             {:on-permission-request sdk/approve-all
                                              :available-tools []})
              result (first (alts!! [result-ch (timeout 5000)]))]
          (is (instance? Throwable result)
              "async create-session must yield a Throwable on options.update failure")
          (is (re-find #"options\.update" (.getMessage result))
              "exception message should mention options.update"))
        (is (empty? (:sessions @(:state client)))
            "failed session must be removed from in-memory registry (async path)")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(deftest disconnect-concurrent-idempotent-test
  ;; disconnect! must be idempotent under concurrent calls: only the caller that
  ;; atomically claims :destroyed? should send session.destroy. A non-atomic
  ;; check-then-act lets multiple concurrent callers each send the RPC. Run many
  ;; iterations with several racing threads; the non-atomic version observes the
  ;; race in ~45% of iterations, so requiring exactly one RPC per iteration is a
  ;; reliable (non-flaky) regression guard.
  (dotimes [_ 100]
    (let [ch (chan)
          client {:state (atom {:sessions {"s1" {:destroyed? false}}
                                :session-io {"s1" {:event-chan ch}}
                                :connection-io :fake})}
          calls (atom 0)
          latch (java.util.concurrent.CountDownLatch. 1)]
      (with-redefs [protocol/send-request! (fn [& _] (swap! calls inc) nil)]
        (let [threads (doall (for [_ (range 8)]
                               (future (.await latch)
                                       (try (session/disconnect! client "s1")
                                            (catch Throwable _)))))]
          (.countDown latch)
          (doseq [t threads] @t)))
      (is (= 1 @calls)
          "exactly one concurrent disconnect! should send session.destroy")
      (is (true? (get-in @(:state client) [:sessions "s1" :destroyed?]))))))

;; -----------------------------------------------------------------------------
;; v1.0.1 sync — open-canvases snapshot, schedule cron/at, optional event fields
;; (upstream PRs #1597, #1604, #1612)
;; -----------------------------------------------------------------------------

(deftest test-canvas-closed-event-in-public-event-types
  (testing "session.canvas.closed registered (upstream PR #1604)"
    (is (contains? sdk/event-types :copilot/session.canvas.closed))
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.canvas.closed)))
  (testing "::session.canvas.closed-data idiom spec accepts well-formed payload"
    (is (s/valid? :github.copilot-sdk.specs/session.canvas.closed-data
                  {:instance-id "i1" :extension-id "ext-a" :canvas-id "c1"}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.canvas.closed-data
                       {:extension-id "ext-a" :canvas-id "c1"})))))

(deftest test-open-canvases-initialized-from-resume
  (testing "session.resume populates open-canvases snapshot (upstream PR #1604)"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :title "Hello"}]})
    ;; create-session does NOT populate open-canvases
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (is (= [] (sdk/open-canvases created)))
      (let [session-id (sdk/session-id created)
            resumed (sdk/resume-session *test-client* session-id
                                        {:on-permission-request sdk/approve-all})
            canvases (sdk/open-canvases resumed)]
        (is (= 1 (count canvases)))
        (is (= "i1" (:instance-id (first canvases))))
        (is (= "ext-a" (:extension-id (first canvases))))
        (is (= "c1" (:canvas-id (first canvases))))
        (is (= "Hello" (:title (first canvases))))))))

(deftest test-open-canvases-resume-sanitizes-invalid-entries
  (testing "session.resume openCanvases drops only structurally-invalid entries"
    (mock/set-resume-response-extras!
     *mock-server*
     ;; Two valid entries (3 required ids; extra optional fields), then three
     ;; structurally-invalid ones: missing :canvasId, blank :instanceId, and a
     ;; non-map. Under v1.0.4 the snapshot shape requires only the three ids;
     ;; :reopen/:availability were removed from OpenCanvasInstance entirely.
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"}
                     {:instanceId "i2"
                      :extensionId "ext-a"
                      :canvasId "c2"
                      :title "Second"}
                     {:instanceId "i3"
                      :extensionId "ext-a"}
                     {:instanceId ""
                      :extensionId "ext-a"
                      :canvasId "c4"}
                     "not-a-map"]})
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id created)
          resumed (sdk/resume-session *test-client* session-id
                                      {:on-permission-request sdk/approve-all})
          canvases (sdk/open-canvases resumed)]
      (is (= 2 (count canvases)) "both fully-valid entries are kept")
      (is (= ["i1" "i2"] (mapv :instance-id canvases))))))

(deftest test-create-session-no-open-canvases-snapshot
  (testing "session.create does NOT populate open-canvases (resume-only)"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :reopen false
                      :availability "ready"}]})
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (is (= [] (sdk/open-canvases session))
          "create response is ignored even if mock injects openCanvases"))))

(deftest test-canvas-opened-upserts-snapshot
  (testing "session.canvas.opened upserts by instanceId"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (= [] (sdk/open-canvases session)))
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"
                                 :status "loading"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      (is (= "loading" (:status (first (sdk/open-canvases session)))))
      ;; Re-emit with same instanceId, different status — replace in place
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"
                                 :status "ready"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))) "length unchanged on upsert")
      (is (= "ready" (:status (first (sdk/open-canvases session)))) "entry updated in place")
      ;; Append a new instance
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i2"
                                 :extensionId "ext-a"
                                 :canvasId "c2"
                                 :reopen false
                                 :availability "ready"})
      (Thread/sleep 200)
      (is (= 2 (count (sdk/open-canvases session))))
      (is (= ["i1" "i2"] (mapv :instance-id (sdk/open-canvases session)))))))

(deftest test-canvas-closed-removes-from-snapshot
  (testing "session.canvas.closed removes by instanceId (upstream PR #1604)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.closed"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= [] (sdk/open-canvases session)))
      ;; Idempotent — closing absent instanceId is a no-op
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.closed"
                                {:instanceId "i-missing"
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= [] (sdk/open-canvases session))))))

(deftest test-canvas-events-malformed-payload-no-op
  (testing "missing/blank instanceId on opened/closed warns and no-ops"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Seed one entry so we can verify no mutation
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      ;; Closed with empty instanceId — no-op
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.closed"
                                {:instanceId ""
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      ;; Opened with blank instanceId — no-op
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId ""
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      ;; Closed with non-string instanceId (numeric) — no-op (matches strict
      ;; ::instance-id non-blank-string spec used elsewhere)
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.closed"
                                {:instanceId 42
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session)))))))

(deftest test-schedule-data-cron-and-at
  (testing "schedule_created accepts cron-only payload (no :interval-ms)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "ping" :cron "0 * * * *" :tz "UTC"})))
  (testing "schedule_created accepts :at one-shot payload"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "ping" :at 1700000000000})))
  (testing "schedule_created still requires :id"
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:prompt "ping" :cron "0 * * * *"})))))

(deftest test-events-file-size-bytes-on-resume-and-shutdown
  (testing ":events-file-size-bytes accepted on resume idiom data"
    (is (s/valid? :github.copilot-sdk.specs/session.resume-data
                  {:event-count 0 :events-file-size-bytes 1024})))
  (testing ":events-file-size-bytes accepted on shutdown idiom data"
    (is (s/valid? :github.copilot-sdk.specs/session.shutdown-data
                  {:shutdown-type "routine"
                   :total-api-duration-ms 10
                   :session-start-time 0
                   :code-changes {}
                   :model-metrics {}
                   :events-file-size-bytes 1024}))))

(deftest test-api-call-id-on-assistant-message
  (testing ":api-call-id accepted on assistant.message idiom data"
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1" :content "hi" :api-call-id "call_1"}))))

(deftest test-temporary-on-hook-progress
  (testing ":temporary accepted on hook.progress idiom data"
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "in progress" :temporary true}))
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "in progress" :temporary false}))))

;; -----------------------------------------------------------------------------
;; v1.0.1 sync — review-driven fixes
;; -----------------------------------------------------------------------------

(deftest test-canvas-opened-event-input-preserved-verbatim
  (testing "session.canvas.opened :input keys round-trip without kebab-casing"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Caller-defined opaque input map with snake_case AND camelCase AND
      ;; nested keys — none should be re-cased by wire->clj.
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"
                                 :reopen false
                                 :availability "ready"
                                 :input {:user_id 42
                                         :myKey "v"
                                         :nested {:secondKey "v2"
                                                  :third_key 3}}})
      (Thread/sleep 200)
      (let [snap (first (sdk/open-canvases session))
            input (:input snap)]
        (is (some? snap) "snapshot was upserted")
        (is (= 42 (:user_id input)) "snake_case caller key preserved")
        (is (= "v" (:myKey input)) "camelCase caller key preserved")
        (let [nested (:nested input)]
          (is (= "v2" (:secondKey nested)) "nested camelCase preserved")
          (is (= 3 (:third_key nested)) "nested snake_case preserved"))))))

(deftest test-resume-response-open-canvases-input-preserved
  (testing "session.resume openCanvases[].input keys round-trip verbatim"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :reopen false
                      :availability "ready"
                      :input {:user_id 7
                              :nested {:keepThis "v"
                                       :and_this 1}}}]})
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id created)
          resumed (sdk/resume-session *test-client* session-id
                                      {:on-permission-request sdk/approve-all})
          canvases (sdk/open-canvases resumed)]
      (is (= 1 (count canvases)))
      (let [input (-> canvases first :input)]
        (is (= 7 (:user_id input)) "top-level snake_case preserved")
        (is (= "v" (get-in input [:nested :keepThis])) "nested camelCase preserved")
        (is (= 1 (get-in input [:nested :and_this])) "nested snake_case preserved")))
    ;; Cleanup: clear the stub so subsequent tests see a clean resume response.
    (mock/set-resume-response-extras! *mock-server* {})))

(deftest test-canvas-upsert-strict-validation
  (testing "upsert requires the three ids (matches v1.0.4 isOpenCanvasInstance)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Seed a valid entry first (3 required ids only)
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i1"
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session))))
      ;; Missing :canvasId — guard rejects, no upsert
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i2"
                                 :extensionId "ext-a"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session)))
          "missing :canvas-id rejected")
      ;; Blank :extensionId — guard rejects
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i3"
                                 :extensionId ""
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session)))
          "blank :extension-id rejected")
      ;; Non-string :instanceId — guard rejects
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId 42
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 1 (count (sdk/open-canvases session)))
          ":instance-id must be a non-blank string")
      ;; Sanity: a fully valid second entry is still admitted
      (mock/send-session-event! *mock-server* session-id
                                "session.canvas.opened"
                                {:instanceId "i2"
                                 :extensionId "ext-a"
                                 :canvasId "c1"})
      (Thread/sleep 200)
      (is (= 2 (count (sdk/open-canvases session))) "valid entry admitted"))))

(deftest test-resume-config-open-canvases-outbound-wire
  (testing "resume-session :open-canvases sends camelCase wire shape with verbatim :input"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases [{:instance-id "i1"
                             :extension-id "ext-a"
                             :canvas-id "c1"
                             :extension-name "Ext A"
                             :title "Hello"
                             :status "loading"
                             :url "https://example.test/c1"
                             :input {:user_id 99
                                     :nested {:keepCase "v"
                                              :snake_field 1}}}]})
          (let [params @captured]
            (is (some? params) "session.resume request was captured")
            (let [oc (first (:openCanvases params))]
              (is (some? oc) "openCanvases array was sent")
              (is (= "i1"   (:instanceId oc)))
              (is (= "ext-a" (:extensionId oc)))
              (is (= "c1"   (:canvasId oc)))
              (is (= "Ext A" (:extensionName oc)))
              (is (= "Hello" (:title oc)))
              (is (= "loading" (:status oc)))
              (is (= "https://example.test/c1" (:url oc)))
              ;; Input keys must NOT have been camelCased.
              (let [input (:input oc)]
                ;; Keys may be strings (preserved through clj->wire) or
                ;; symbols of the original name; verify by string lookup.
                (is (= 99 (or (get input "user_id") (get input :user_id))))
                (let [nested (or (get input "nested") (get input :nested))]
                  (is (= "v" (or (get nested "keepCase") (get nested :keepCase))))
                  (is (= 1   (or (get nested "snake_field") (get nested :snake_field)))))))))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-resume-config-open-canvases-namespaced-input-keys
  (testing "Namespaced keyword keys in :input preserve their full ns/name when stringified"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases [{:instance-id "i1"
                             :extension-id "ext-a"
                             :canvas-id "c1"
                             :reopen false
                             :availability "ready"
                             :input {:my.app/user_id 42
                                     :nested {:other.ns/key "v"}}}]})
          (let [params @captured
                oc (first (:openCanvases params))
                input (:input oc)]
            (is (some? input))
            ;; Namespaced keys must be preserved as "ns/name", NOT silently
            ;; truncated to just the local name. Keys may surface as strings
            ;; or as preserved keywords through clj->wire — check both.
            (is (= 42 (or (get input "my.app/user_id")
                          (get input :my.app/user_id)))
                "namespaced keyword key preserved as full ns/name")
            (is (nil? (get input "user_id")) "local-name-only must not appear")
            (is (nil? (get input :user_id)))
            (let [nested (or (get input "nested") (get input :nested))]
              (is (= "v" (or (get nested "other.ns/key")
                             (get nested :other.ns/key)))
                  "nested namespaced key also preserved"))))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-resume-config-open-canvases-explicit-empty-and-omitted
  (testing "resume-session forwards explicit empty :open-canvases (parity with upstream config.openCanvases) and omits the param when the key is absent"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        ;; Explicit empty vector — must be sent as []
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases []})
          (let [params @captured]
            (is (some? params))
            (is (contains? params :openCanvases) "explicit empty vector forwarded as openCanvases param")
            (is (= [] (:openCanvases params)))))
        (reset! captured nil)
        ;; Key absent — param must be omitted entirely
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all})
          (let [params @captured]
            (is (some? params))
            (is (not (contains? params :openCanvases)) "missing :open-canvases key omits openCanvases param")))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-schedule-at-spec-rejects-non-positive
  (testing "::at requires a positive integer (epoch ms)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "p" :at 1700000000000}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at 0}))
        "zero is not a valid epoch-ms timestamp")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at -1}))
        "negative is rejected")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at 1.5}))
        "non-integer (double) is rejected")))

;; --- C1: memory configuration on the wire (upstream PR memory 86df7e50) -------

(deftest test-memory-config-on-wire
  (testing ":memory is forwarded to session.create as {:enabled bool}"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled true}})
          create-params (get @seen "session.create")]
      (is (= {:enabled true} (:memory create-params)))))

  (testing ":memory {:enabled false} is forwarded (not stripped)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled false}})
          create-params (get @seen "session.create")]
      (is (= {:enabled false} (:memory create-params)))))

  (testing ":memory is omitted from session.create when unset (never null)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :memory)))))

  (testing ":memory is forwarded to session.resume (parity with create)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled true}})
          resume-params (get @seen "session.resume")]
      (is (= {:enabled true} (:memory resume-params))))))

;; --- C5: deferTools on MCP server configs (1.0.63 schema) --------------------

(deftest test-mcp-defer-tools-on-wire
  (testing ":mcp-defer-tools keyword is forwarded as deferTools string"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :mcp-servers {"srv-http" {:mcp-server-type :http
                                                           :mcp-url "https://mcp.test"
                                                           :mcp-tools ["*"]
                                                           :mcp-defer-tools :auto}
                                               "srv-stdio" {:mcp-command "node"
                                                            :mcp-args ["server.js"]
                                                            :mcp-tools ["*"]
                                                            :mcp-defer-tools :never}}})
          create-params (get @seen "session.create")]
      (is (= "auto" (get-in create-params [:mcpServers :srv-http :deferTools])))
      (is (= "never" (get-in create-params [:mcpServers :srv-stdio :deferTools])))))

  (testing "deferTools is omitted when :mcp-defer-tools unset"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :mcp-servers {"srv-http" {:mcp-server-type :http
                                                           :mcp-url "https://mcp.test"
                                                           :mcp-tools ["*"]}}})
          create-params (get @seen "session.create")]
      (is (not (contains? (get-in create-params [:mcpServers :srv-http]) :deferTools))))))

;; --- C3: graceful runtime.shutdown in stop! (upstream PR #1667) --------------

(deftest test-graceful-runtime-shutdown
  (testing "stop! sends runtime.shutdown for SDK-owned (non-external) process"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (swap! seen conj method)))]
      ;; Inject a placeholder process so the SDK-owned guard fires. A bare map
      ;; has no :process key, so proc/destroy! is a no-op.
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (sdk/stop! *test-client*)
      (is (some #{"runtime.shutdown"} @seen)
          "runtime.shutdown RPC is sent during graceful stop")))

  (testing "stop! does NOT send runtime.shutdown when no process is owned"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (swap! seen conj method)))]
      ;; No :process injected — fixture connect-with-streams! spawns nothing.
      (sdk/stop! *test-client*)
      (is (not (some #{"runtime.shutdown"} @seen))
          "runtime.shutdown is skipped when the client does not own a process"))))

(deftest graceful-shutdown-waits-for-natural-exit
  (testing "stop! waits for the child to exit on its own after a successful
            runtime.shutdown and skips the kill when it does"
    (let [waited? (atom false)
          killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [proc/wait-for-exit! (fn [_ _] (reset! waited? true) true)
                    proc/destroy! (fn [_] (reset! killed? true))]
        (sdk/stop! *test-client*))
      (is @waited? "stop! waits for natural exit after runtime.shutdown succeeds")
      (is (not @killed?)
          "stop! does not SIGTERM the child when it exits gracefully")))

  (testing "stop! force-kills the child when it does not exit within the window"
    (let [killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [proc/wait-for-exit! (fn [_ _] false)
                    proc/destroy! (fn [_] (reset! killed? true))]
        (sdk/stop! *test-client*))
      (is @killed?
          "stop! kills the child if it does not exit within the graceful window")))

  (testing "stop! kills immediately (no graceful wait) when runtime.shutdown fails"
    (let [waited? (atom false)
          killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [protocol/send-request!
                    (fn [_conn method _params & _]
                      (when (= method "runtime.shutdown")
                        (throw (ex-info "boom" {})))
                      nil)
                    proc/wait-for-exit! (fn [_ _] (reset! waited? true) true)
                    proc/destroy! (fn [_] (reset! killed? true))]
        (sdk/stop! *test-client*))
      (is (not @waited?)
          "stop! does not wait for natural exit when runtime.shutdown failed")
      (is @killed?
          "stop! force-kills the child immediately when runtime.shutdown failed"))))
