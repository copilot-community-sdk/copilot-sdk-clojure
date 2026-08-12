(ns github.copilot-sdk.integration.messaging-events-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

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
          send-calls (atom 0)
          ;; Latches replace fixed sleeps: `send!` runs after the mult tap, so a
          ;; caller that has entered `send!` holds the send-lock. The first call
          ;; parks until released, deterministically proving the second call
          ;; cannot enter `send!` until the first completes.
          first-entered (promise)
          release-first (promise)
          second-entered (promise)
          take-attempts (java.util.concurrent.CountDownLatch. 2)
          parked-takes (java.util.concurrent.CountDownLatch. 1)
          send-lock (get-in @(:state client) [:session-io session-id :send-lock])]
      (swap! (:state client) assoc-in
             [:session-io session-id :send-lock]
             (observe-take-attempts send-lock take-attempts parked-takes))
      (with-redefs [session/send! (fn [_ _]
                                    (case (long (swap! send-calls inc))
                                      1 (do (deliver first-entered true)
                                            @release-first)
                                      2 (deliver second-entered true)
                                      nil)
                                    "msg")]
        (let [first-f (future (session/send-and-wait! session {:prompt "A"} 5000))]
          ;; Gate the second start on the first caller holding the lock, so the
          ;; first future is deterministically call 1 (lock acquisition order
          ;; between two concurrently-started futures is otherwise unspecified).
          (is (true? (deref first-entered 2000
                            :github.copilot-sdk.integration-test/timeout)))
          (let [second-f (future (session/send-and-wait! session {:prompt "B"} 5000))]
            ;; The second caller blocks on the lock and cannot enter send!.
            (is (and (.await take-attempts 1 java.util.concurrent.TimeUnit/SECONDS)
                     (.await parked-takes 1 java.util.concurrent.TimeUnit/SECONDS)
                     (not (realized? second-entered))))
            (is (= 1 @send-calls))
            ;; Release the first send!; the tap is already installed, so events
            ;; dispatched now buffer on the tapped channel and drive completion.
            (deliver release-first true)
            (session/dispatch-event! client session-id
                                     {:type :copilot/assistant.message
                                      :data {:content "first"}})
            (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
            (is (map? (deref first-f 5000
                             :github.copilot-sdk.integration-test/timeout)))
            ;; Lock released → the second caller now enters send! (proving it was
            ;; blocked until the first completed).
            (is (true? (deref second-entered 2000
                              :github.copilot-sdk.integration-test/timeout)))
            (is (= 2 @send-calls))
            (session/dispatch-event! client session-id
                                     {:type :copilot/assistant.message
                                      :data {:content "second"}})
            (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
            (is (map? (deref second-f 5000
                             :github.copilot-sdk.integration-test/timeout)))))))))

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
          send-calls (atom 0)
          take-attempts (java.util.concurrent.CountDownLatch. 2)
          parked-takes (java.util.concurrent.CountDownLatch. 1)
          send-lock (get-in @(:state client) [:session-io session-id :send-lock])]
      (swap! (:state client) assoc-in
             [:session-io session-id :send-lock]
             (observe-take-attempts send-lock take-attempts parked-takes))
      ;; <send-async* uses proto/send-request directly
      (let [first-send (promise)
            second-send (promise)]
        (with-redefs [protocol/send-request (fn [_ _ _]
                                              (case (swap! send-calls inc)
                                                1 (deliver first-send true)
                                                2 (deliver second-send true)
                                                nil)
                                              (let [ch (async/chan 1)]
                                                (async/put! ch {:result {:message-id "msg"}})
                                                (async/close! ch)
                                                ch))]
          (let [ch1 (session/send-async session {:prompt "A"})
                ch2-f (future (session/send-async session {:prompt "B"}))]
            (await-value! first-send "first serialized send" 1000)
            (is (and (.await take-attempts 1 java.util.concurrent.TimeUnit/SECONDS)
                     (.await parked-takes 1 java.util.concurrent.TimeUnit/SECONDS)
                     (= 1 @send-calls))
                "second send must reach the send lock without issuing its RPC")
            (session/dispatch-event! client session-id
                                     {:type :copilot/assistant.message
                                      :data {:content "first"}})
            (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
            (is (not= :github.copilot-sdk.integration-test/timeout
                      (deref ch2-f 1000
                             :github.copilot-sdk.integration-test/timeout)))
            (await-value! second-send "second serialized send" 1000)
            (is (= 2 @send-calls))
            (session/dispatch-event! client session-id
                                     {:type :copilot/assistant.message
                                      :data {:content "second"}})
            (session/dispatch-event! client session-id {:type :copilot/session.idle :data {}})
            (loop []
              (let [[v _] (alts!! [ch1 (timeout 1000)])]
                (when (some? v)
                  (recur))))))))))

(deftest test-<send!-returns-last-assistant-message
  (testing "<send! returns the last assistant.message content, not the first"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)]
      ;; Bypass actual send to control event flow
      (let [send-requested (promise)]
        (with-redefs [protocol/send-request (fn [_ _ _]
                                              (deliver send-requested true)
                                              (let [ch (async/chan 1)]
                                                (async/put! ch {:result {:message-id "msg-id"}})
                                                (async/close! ch)
                                                ch))]
          (let [result-ch (sdk/<send! session {:prompt "Test agentic flow"})]
            (await-value! send-requested "<send! request setup" 1000)
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
                  "<send! should return the last assistant.message content, not the first"))))))))

(deftest test-<send-and-wait!-returns-final-event
  (testing "<send-and-wait! delivers the final assistant.message EVENT (not just content)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          client (:client session)]
      (let [send-requested (promise)]
        (with-redefs [protocol/send-request (fn [_ _ _]
                                              (deliver send-requested true)
                                              (let [ch (async/chan 1)]
                                                (async/put! ch {:result {:message-id "msg-id"}})
                                                (async/close! ch)
                                                ch))]
          (let [result-ch (sdk/<send-and-wait! session {:prompt "Q"})]
            (await-value! send-requested "<send-and-wait! request setup" 1000)
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
              (is (= "m2" (get-in result [:data :message-id]))))))))))

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

(deftest test-event-subscription
  (testing "Can subscribe to session event stream"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          events-ch (sdk/subscribe-events session)]
      (try
        (sdk/send! session {:prompt "Event test"})
        (is (= :copilot/session.idle
               (:type (await-event-type! events-ch :copilot/session.idle 1000))))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

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
            dispatch-result (deref dispatch-future 50
                                   :github.copilot-sdk.integration-test/timeout)]
        (is (not= :github.copilot-sdk.integration-test/timeout dispatch-result))
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
          (is (not= :github.copilot-sdk.integration-test/timeout
                    (deref dispatch 1000
                           :github.copilot-sdk.integration-test/timeout)))
          (<!! incoming)
          (let [seen (loop []
                       (when-let [v (<!! incoming)]
                         (if (= "session.event" (:method v))
                           v
                           (recur))))]
            (is (= "session.event" (:method seen)))))
        (finally
          (protocol/disconnect conn))))))
