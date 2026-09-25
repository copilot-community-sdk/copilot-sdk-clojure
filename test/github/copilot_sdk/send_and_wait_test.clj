(ns github.copilot-sdk.send-and-wait-test
  "Deterministic regression coverage for `send-and-wait!` outcome races.

   Mirrors the upstream `nodejs/test/session-send-and-wait.test.ts` suite that
   guards the send/idle/error ordering contract. Instead of fixed sleeps these
   tests gate the real `session.send` RPC on the piped-stream mock server via a
   request hook, inject events while the send is in flight, then release the
   send and assert which outcome wins.

   The upstream contract these tests pin:

   - an early `session.error` observed while `send` is in flight is retained and
     surfaced once `send` completes;
   - an early `session.idle` (and any assistant message) is retained but does
     not produce a return before `send` completes;
   - a `send` RPC rejection wins over an earlier `session.error`;
   - the first terminal outcome (idle or error) observed wins;
   - the zero-timeout default is 60000ms, matching upstream `session.ts`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.integration.support
             :refer [await-event-type! await-value! await-atom! observe-take-attempts]]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.mock-server :as mock]))

;; -----------------------------------------------------------------------------
;; Fixture: a piped-stream mock server whose `session.send` RPC is gated on a
;; promise, mirroring upstream's `controlledSession` (a manually-resolved
;; sendRequest). While the send is parked we inject session events over the wire
;; so they buffer ahead of the send response in strict FIFO order.
;; -----------------------------------------------------------------------------

(defn- gated-send-context
  "Start a mock server + connected client with a gated `session.send`.

   Returns a map:
   - :client       connected CopilotClient
   - :session      a live CopilotSession
   - :session-id   its id
   - :server       the mock server (for injecting events)
   - :send-started promise delivered when the server receives `session.send`
   - :release      (fn) resolves the send RPC (default handler then runs)
   - :reject       (fn [msg]) rejects the send RPC with `msg`
   - :close        (fn) tears everything down"
  []
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)
        _ (client/connect-with-streams! client in out)
        copilot-session (sdk/create-session client {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        send-started (promise)
        ;; Delivered value drives the gate: :resolve → normal response;
        ;; {:reject msg} → server writes a JSON-RPC error for `session.send`.
        send-gate (promise)]
    (mock/set-request-hook!
     server
     (fn [method _params]
       (when (= method "session.send")
         (deliver send-started true)
         (let [gate @send-gate]
           (when-let [msg (:reject gate)]
             (throw (ex-info msg {:code -32000})))))
       nil))
    {:client client
     :session copilot-session
     :session-id session-id
     :server server
     :send-started send-started
     :release #(deliver send-gate :resolve)
     :reject (fn [msg] (deliver send-gate {:reject msg}))
     :close (fn []
              (try (sdk/stop! client) (catch Exception _))
              (mock/stop-mock-server! server))}))

(defn- inject-error!
  "Inject a `session.error` event over the wire (the shape a joined client's
   `session.log(_, {level: \"error\"})` produces)."
  [{:keys [server session-id]} message]
  (mock/send-session-event! server session-id "session.error"
                            {:errorType "notification" :message message}))

(defn- inject-idle!
  "Inject a `session.idle` event over the wire."
  ([ctx]
   (inject-idle! ctx {}))
  ([{:keys [server session-id]} data]
   (mock/send-session-event! server session-id "session.idle" data :ephemeral? true)))

;; -----------------------------------------------------------------------------
;; Timeout-selection harness (PAR-003 follow-up): drives `send-and-wait!` with a
;; stubbed `send!` that records the opts it receives and dispatches idle itself
;; (it runs after the mult tap, so idle is delivered deterministically). Every
;; `async/timeout` call is captured so a test can assert exactly which deadline
;; the wait armed -- or that none was armed for a disabled (nil) timeout. No
;; sleeps; the client is always torn down.
;; -----------------------------------------------------------------------------

(defn- capture-send-and-wait
  "Runs `(f session)` under instrumentation-free capture and returns
   {:timeouts <vec of ms passed to async/timeout>
    :send-opts <opts map the stubbed send! received>
    :result <return value of f>}."
  [f]
  (let [timeouts (atom [])
        send-opts (atom ::unset)
        real-timeout async/timeout
        client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "capture-session" {})
        session-id (sdk/session-id copilot-session)]
    (try
      (with-redefs [async/timeout (fn [^long ms] (swap! timeouts conj ms) (real-timeout ms))
                    session/send! (fn [_ opts]
                                    (reset! send-opts opts)
                                    (session/dispatch-event!
                                     client session-id
                                     {:type :copilot/session.idle :data {}})
                                    "msg")]
        (let [result (f copilot-session)]
          {:timeouts @timeouts
           :send-opts @send-opts
           :result result}))
      (finally
        (sdk/force-stop! client)))))

(deftest send-and-wait-honors-explicit-numeric-timeout-in-opts
  (testing "an explicit numeric :timeout-ms in opts arms that exact deadline and is not forwarded"
    (let [{:keys [timeouts send-opts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi" :timeout-ms 1234})))]
      (is (= [1234] timeouts)
          "the deadline must use the opts :timeout-ms, not the default")
      (is (nil? result))
      (is (map? send-opts))
      (is (not (contains? send-opts :timeout-ms))
          ":timeout-ms must be stripped before the underlying session.send"))))

(deftest send-and-wait-nil-timeout-in-opts-disables-deadline
  (testing "a nil :timeout-ms in opts arms no deadline channel at all"
    (let [{:keys [timeouts send-opts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi" :timeout-ms nil})))]
      (is (= [] timeouts)
          "nil :timeout-ms must not call async/timeout")
      (is (nil? result))
      (is (not (contains? send-opts :timeout-ms))))))

(deftest send-and-wait-nil-positional-timeout-disables-deadline
  (testing "a nil positional 3-arity timeout arms no deadline channel"
    (let [{:keys [timeouts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi"} nil)))]
      (is (= [] timeouts)
          "nil positional timeout must not call async/timeout")
      (is (nil? result)))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 1: early session.error is retained until send completes.
;; -----------------------------------------------------------------------------

(deftest early-session-error-is-retained-while-send-is-in-flight
  (testing "a session.error arriving before send resolves surfaces once send completes"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          ;; Wait until the send RPC is parked server-side, then inject the error
          ;; while send() is still in flight (its idle race is not yet armed).
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "MCP server failed to start")
          ;; Nothing is observed until send completes.
          (release)
          (is (= [:threw "MCP server failed to start"]
                 (deref pending 5000 ::timeout))))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 2: an early idle is preserved but does not return early.
;; -----------------------------------------------------------------------------

(deftest early-idle-is-preserved-until-send-completes
  (testing "an early session.idle does not produce a return before send completes"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-idle! ctx)
          ;; send() is still parked, so the outcome cannot have been consumed yet.
          (is (not (realized? pending))
              "send-and-wait! must not return before send completes")
          (release)
          ;; No assistant message preceded idle in buffer order → returns nil.
          (is (nil? (deref pending 5000 ::timeout))))
        (finally (close))))))

(deftest autopilot-idle-is-not-terminal-for-send-and-wait
  (testing "send-and-wait! ignores autopilot idle and returns after the regular idle"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)
          timeout-calls (atom [])
          real-timeout async/timeout]
      (try
        (with-redefs [async/timeout
                      (fn [^long timeout-ms]
                        (swap! timeout-calls conj timeout-ms)
                        (real-timeout timeout-ms))]
          (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
            (is (true? (deref send-started 2000 ::timeout)))
            (session/dispatch-event!
             (:client ctx) (:session-id ctx)
             {:type :copilot/session.idle
              :data {:mode "autopilot"}})
            (release)
            (let [result (deref pending 5000 ::timeout)]
              (is (= :copilot/assistant.message (:type result)))
              (is (= "Mock response to: hi" (get-in result [:data :content]))))
            (is (= 1 (count (filter #(= 5000 %) @timeout-calls)))
                "ignored autopilot idle must not reset the original deadline")))
        (finally (close))))))

(deftest send-and-wait-times-out-after-autopilot-only-idle
  (testing "autopilot idle does not reset or defeat the blocking deadline"
    (let [client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "autopilot-timeout" {})
          session-id (sdk/session-id copilot-session)
          deadline-ch (async/chan)
          timeout-calls (atom [])
          send-started (promise)
          autopilot-observed (promise)
          terminal-idle-event? session/terminal-idle-event?
          pending (atom nil)]
      (try
        (with-redefs [async/timeout
                      (fn [^long timeout-ms]
                        (swap! timeout-calls conj timeout-ms)
                        deadline-ch)
                      session/send!
                      (fn [_session _opts]
                        (deliver send-started true)
                        "message-id")
                      session/terminal-idle-event?
                      (fn [event]
                        (let [terminal? (terminal-idle-event? event)]
                          (when (= "autopilot" (get-in event [:data :mode]))
                            (deliver autopilot-observed true))
                          terminal?))]
          (reset! pending
                  (future
                    (try
                      (session/send-and-wait!
                       copilot-session
                       {:prompt "wait"}
                       5000)
                      ::completed
                      (catch Throwable error
                        error))))
          (is (true? (deref send-started 1000 false)))
          (session/dispatch-event!
           client
           session-id
           {:type :copilot/session.idle
            :data {:mode "autopilot"}})
          (is (true? (deref autopilot-observed 1000 false)))
          (async/close! deadline-ch)
          (let [caught (deref @pending 1000 ::timeout)]
            (is (instance? clojure.lang.ExceptionInfo caught))
            (is (= 5000 (:timeout-ms (ex-data caught)))))
          (is (= [5000] @timeout-calls)))
        (finally
          (async/close! deadline-ch)
          (sdk/force-stop! client)
          (when-let [send @pending]
            (deref send 1000 nil)))))))

(deftest autopilot-idle-is-not-terminal-for-async-sends
  (testing "both async send paths remain open across autopilot idle"
    (doseq [[label start]
            [[:events #(session/send-async % {:prompt "hi" :timeout-ms 5000})]
             [:with-id #(-> (session/send-async-with-id %
                                                        {:prompt "hi" :timeout-ms 5000})
                            :events-ch)]]]
      (testing (name label)
        (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
          (try
            (let [events-ch (future (start session))]
              (is (true? (deref send-started 2000 ::timeout)))
              (inject-idle! ctx {:mode "autopilot"})
              (release)
              (let [events-ch (deref events-ch 5000 ::timeout)
                    events (loop [acc []]
                             (let [[event port] (async/alts!! [events-ch (async/timeout 5000)])]
                               (cond
                                 (not= port events-ch) ::timeout
                                 (nil? event) acc
                                 :else (recur (conj acc event)))))]
                (is (vector? events) "the event stream must close after regular idle")
                (is (some #(and (= :copilot/session.idle (:type %))
                                (= "autopilot" (get-in % [:data :mode])))
                          events))
                (is (some #(= :copilot/assistant.message (:type %)) events))
                (is (= :copilot/session.idle (:type (last events))))
                (is (nil? (get-in (last events) [:data :mode])))))
            (finally (close))))))))

(deftest async-send-times-out-after-autopilot-only-idle
  (testing "autopilot idle is forwarded before the original async deadline wins"
    (let [client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "async-autopilot-timeout" {})
          session-id (sdk/session-id copilot-session)
          deadline-ch (async/chan)
          timeout-calls (atom [])
          send-started (promise)
          send-methods (atom [])
          real-timeout async/timeout]
      (try
        (with-redefs [async/timeout
                      (fn [^long timeout-ms]
                        (swap! timeout-calls conj timeout-ms)
                        deadline-ch)
                      github.copilot-sdk.protocol/send-request
                      (fn [_connection method _params]
                        (swap! send-methods conj method)
                        (deliver send-started true)
                        (async/to-chan! [{:result {:message-id "message-id"}}]))]
          (let [events-ch
                (session/send-async
                 copilot-session
                 {:prompt "wait" :timeout-ms 5000})]
            (is (true? (deref send-started 1000 false)))
            (session/dispatch-event!
             client
             session-id
             {:type :copilot/session.idle
              :data {:mode "autopilot"}})
            (let [[event port] (async/alts!! [events-ch (real-timeout 1000)])]
              (is (identical? events-ch port))
              (is (= "autopilot" (get-in event [:data :mode]))))
            (async/close! deadline-ch)
            (let [[event port] (async/alts!! [events-ch (real-timeout 1000)])]
              (is (identical? events-ch port))
              (is (= :copilot/session.error (:type event)))
              (is (= 5000 (get-in event [:data :timeout-ms]))))
            (let [[event port] (async/alts!! [events-ch (real-timeout 1000)])]
              (is (identical? events-ch port))
              (is (nil? event)))
            (is (= ["session.send"] @send-methods))
            (is (= [5000] @timeout-calls))))
        (finally
          (async/close! deadline-ch)
          (sdk/force-stop! client))))))

(deftest autopilot-idle-is-not-terminal-for-channel-convenience-wrappers
  (testing "<send! and <send-and-wait! remain open across autopilot idle"
    (doseq [[label start collect]
            [[:content
              #(session/<send! % {:prompt "hi" :timeout-ms 5000})
              (fn [result-ch]
                (first (async/alts!! [result-ch (async/timeout 5000)])))]
             [:last-message
              #(session/<send-and-wait! % {:prompt "hi" :timeout-ms 5000})
              (fn [result-ch]
                (first (async/alts!! [result-ch (async/timeout 5000)])))]]]
      (testing (name label)
        (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
          (try
            (let [result-ch (start session)]
              (is (true? (deref send-started 2000 ::timeout)))
              (inject-idle! ctx {:mode "autopilot"})
              (release)
              (let [result (collect result-ch)]
                (if (= label :content)
                  (is (= "Mock response to: hi" result))
                  (do
                    (is (= :copilot/assistant.message (:type result)))
                    (is (= "Mock response to: hi" (get-in result [:data :content])))))))
            (finally (close))))))))

(deftest terminal-idle-recognizes-wire-autopilot-mode
  (is (false? (@#'session/terminal-idle-event?
               {:type :copilot/session.idle :data {:mode "autopilot"}})))
  (is (true? (@#'session/terminal-idle-event?
              {:type :copilot/session.idle :data {}}))))

(defn- read-channel! [ch]
  (let [[value port] (async/alts!! [ch (async/timeout 5000)])]
    (when-not (= port ch)
      (throw (ex-info "Timed out reading send result" {})))
    value))

(defn- inject-events!
  [{:keys [server session-id]} events]
  (doseq [event events]
    (mock/send-notification! server "session.event"
                             {:sessionId session-id :event event})))

(def ^:private child-events
  [{:type "assistant.message" :agentId "child-1"
    :data {:messageId "child-message" :content "child reply"}}
   {:type "session.error" :agentId "child-1"
    :data {:errorType "notification" :message "child failed"}}
   {:type "session.idle" :agentId "child-1" :data {}}])

(deftest send-waits-select-only-root-agent-outcomes
  (doseq [[label start collect]
          [[:blocking #(future (session/send-and-wait! % {:prompt "hi"} 5000))
            #(deref % 5000 ::timeout)]
           [:content #(session/<send! % {:prompt "hi" :timeout-ms 5000}) read-channel!]
           [:message #(session/<send-and-wait! % {:prompt "hi" :timeout-ms 5000})
            read-channel!]]
          root-id [nil ""]
          root-message? [false true]]
    (testing (str label " root-id=" (pr-str root-id) " message=" root-message?)
      (let [{:keys [session release send-started close] :as ctx} (gated-send-context)
            events (cond-> []
                     root-message?
                     (conj (cond-> {:type "assistant.message"
                                    :data {:messageId "root-message" :content "root reply"}}
                             (some? root-id) (assoc :agentId root-id))))
            events (into events child-events)
            events (conj events (cond-> {:type "session.idle" :data {}}
                                  (some? root-id) (assoc :agentId root-id)))
            subscription (sdk/subscribe-events session)]
        (try
          (let [pending (start session)]
            (is (true? (deref send-started 2000 false)))
            (inject-events! ctx events)
            (release)
            (let [result (collect pending)]
              (if root-message?
                (is (= (if (= label :content) result (get-in result [:data :content]))
                       "root reply"))
                (is (nil? result))))
            (let [observed (mapv #(await-event-type! subscription
                                                     (keyword "copilot" (:type %))
                                                     5000)
                                 events)]
              (is (= (mapv (juxt :type :agent-id) observed)
                     (mapv #(vector (keyword "copilot" (:type %)) (:agentId %)) events)))
              (is (= (count (filter #(= (:agent-id %) "child-1") observed)) 3)
                  "Ordinary event subscriptions still receive every child event")))
          (finally
            (release)
            (sdk/unsubscribe-events! session subscription)
            (close)))))))

(deftest async-event-streams-retain-child-events-until-root-completion
  (doseq [[label start]
          [[:events #(session/send-async % {:prompt "hi" :timeout-ms 5000})]
           [:with-id #(-> (session/send-async-with-id %
                                                      {:prompt "hi" :timeout-ms 5000})
                          :events-ch)]]]
    (testing (name label)
      (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
        (try
          (let [pending (future (start session))]
            (is (true? (deref send-started 2000 false)))
            (inject-events! ctx child-events)
            (release)
            (let [events (read-channel! (async/into [] (deref pending 5000 nil)))]
              (is (= (mapv :type (filter #(= (:agent-id %) "child-1") events))
                     [:copilot/assistant.message :copilot/session.error :copilot/session.idle]))
              (is (= (get-in (last (filter #(= (:type %) :copilot/assistant.message)
                                           events))
                             [:data :content])
                     "Mock response to: hi"))
              (is (= (:type (last events)) :copilot/session.idle))
              (is (nil? (:agent-id (last events))))))
          (finally
            (release)
            (close)))))))

(deftest only-nonempty-agent-ids-mark-child-terminal-events
  (doseq [event-type [:copilot/session.idle :copilot/session.error]
          [agent-id expected] [[nil true] ["" true] ["child" false] [" " false]]]
    (let [event (cond-> {:type event-type :data {}}
                  (some? agent-id) (assoc :agent-id agent-id))]
      (is (= (session/terminal-event? event) expected))
      (is (= (session/terminal-idle-event? event)
             (and expected (= event-type :copilot/session.idle)))))))

(defn- paused-output
  [make-channel saturated]
  (let [output (make-channel 1024)
        read-gate (make-channel)
        reads (make-channel)
        last-write (atom nil)
        closed (promise)
        finished (promise)
        close-count (atom 0)]
    (async/go
      (async/<! read-gate)
      (loop []
        (when-let [value (async/<! output)]
          (when (async/>! reads value)
            (recur))))
      (async/close! reads))
    {:channel
     (reify
       async-protocols/ReadPort
       (take! [_ handler]
         (async-protocols/take! reads handler))
       async-protocols/WritePort
       (put! [_ value handler]
         (let [result (async-protocols/put! output value handler)]
           (reset! last-write value)
           (when (or (and (nil? result) (async-protocols/blockable? handler))
                     (session/terminal-event? value))
             (deliver saturated true))
           result))
       async-protocols/Channel
       (close! [_]
         (async/close! output)
         (deliver closed true)
         (when (= 2 (swap! close-count inc))
           (deliver finished true)))
       (closed? [_] (async-protocols/closed? output)))
     :last-write last-write
     :closed closed
     :finished finished
     :resume! #(async/close! read-gate)
     :close! #(do (async/close! output)
                  (async/close! read-gate)
                  (async/close! reads))}))

(deftest async-sends-preserve-results-beyond-the-output-buffer-capacity
  (doseq [[label start]
          [[:events #(session/send-async % {:prompt "hi" :timeout-ms 15000})]
           [:with-id #(-> (session/send-async-with-id %
                                                      {:prompt "hi" :timeout-ms 15000})
                          :events-ch)]
           [:content #(session/<send! % {:prompt "hi" :timeout-ms 15000})]
           [:message #(session/<send-and-wait! % {:prompt "hi" :timeout-ms 15000})]]]
    (testing (name label)
      (let [{:keys [session release send-started close] :as ctx} (gated-send-context)
            real-chan async/chan
            real-timeout async/timeout
            deadline (real-chan)
            saturated (promise)
            output-control (atom nil)
            children (mapv (fn [index]
                             {:type "assistant.message" :agentId "child-1"
                              :data {:messageId (str "child-" index)
                                     :content (str index)}})
                           (range 1536))]
        (try
          (with-redefs [async/chan
                        (fn [& args]
                          (if (and (= args [1024]) (nil? @output-control))
                            (let [control (paused-output real-chan saturated)]
                              (reset! output-control control)
                              (:channel control))
                            (apply real-chan args)))
                        async/timeout (fn [^long ms]
                                        (if (= ms 15000) deadline (real-timeout ms)))]
            (let [pending (future (start session))]
              (await-value! send-started "send admission" 5000)
              (inject-events! ctx children)
              (release)
              (let [result-ch (await-value! pending "async send result channel" 5000)]
                (await-value! saturated "full output buffer or completed producer" 5000)
                (async/close! deadline)
                ((:resume! @output-control))
                (if (#{:events :with-id} label)
                  (let [events (read-channel! (async/into [] result-ch))
                        delivered (mapv #(get-in % [:data :content])
                                        (filter #(= (:agent-id %) "child-1") events))]
                    (is (pos? (count delivered)))
                    (is (<= (count delivered) 1024))
                    (is (= delivered (mapv str (range (count delivered)))))
                    (is (= (get-in (last (filter #(and (= (:type %) :copilot/assistant.message)
                                                       (nil? (:agent-id %)))
                                                 events))
                                   [:data :content])
                           "Mock response to: hi"))
                    (is (= (:type (last events)) :copilot/session.idle)))
                  (let [result (read-channel! result-ch)]
                    (is (= (if (= label :content) result (get-in result [:data :content]))
                           "Mock response to: hi")))))))
          (finally
            (release)
            (async/close! deadline)
            (when-let [control @output-control]
              ((:close! control)))
            (close)))))))

(deftest async-root-completion-is-independent-of-lossy-observer-fanout
  (doseq [[label start] [[:events #(session/send-async % {:prompt "hi" :timeout-ms 30000})]
                         [:message #(session/<send-and-wait! % {:prompt "hi" :timeout-ms 30000})]]]
    (testing (name label)
      (let [{:keys [session client session-id release send-started close] :as ctx}
            (gated-send-context)
            event-mult (get-in @(:state client) [:session-io session-id :event-mult])
            stalled-observer (async/chan 1)
            real-chan async/chan
            saturated (promise)
            output-control (atom nil)]
        (async/tap event-mult stalled-observer)
        (try
          (with-redefs [async/chan
                        (fn [& args]
                          (if (and (= args [1024]) (nil? @output-control))
                            (let [control (paused-output real-chan saturated)]
                              (reset! output-control control)
                              (:channel control))
                            (apply real-chan args)))]
            (let [result-ch (start session)]
              (await-value! send-started "send admission" 5000)
              (inject-events! ctx [{:type "assistant.message"
                                    :data {:messageId "early-root" :content "early root"}}])
              (doseq [batch (partition-all 256 (range 8192))]
                (inject-events! ctx
                                (mapv (fn [index]
                                        {:type "assistant.message" :agentId "child-1"
                                         :data {:messageId (str "child-" index) :content (str index)}})
                                      batch))
                (await-atom! (:last-write @output-control)
                             #(= (get-in % [:data :content]) (str (last batch)))
                             "session intake batch" 5000))
              (inject-idle! ctx)
              (release)
              (await-value! saturated "reserved completion publication" 5000)
              ((:resume! @output-control))
              (if (= label :events)
                (let [events (read-channel! (async/into [] result-ch))]
                  (is (= (mapv #(get-in % [:data :content])
                               (filter #(and (= (:type %) :copilot/assistant.message)
                                             (nil? (:agent-id %)))
                                       events))
                         ["early root"]))
                  (is (= (:type (last events)) :copilot/session.idle)))
                (is (= (get-in (read-channel! result-ch) [:data :content]) "early root")))))
          (finally
            (release)
            (async/untap event-mult stalled-observer)
            (async/close! stalled-observer)
            (loop []
              (when (some? (async/poll! stalled-observer))
                (recur)))
            (when-let [control @output-control]
              ((:close! control)))
            (close)))))))

(deftest cancelling-unadmitted-async-sends-does-not-send-or-return-an-unowned-token
  (doseq [[label start]
          [[:events #(session/send-async % {:prompt "abandoned" :timeout-ms nil})]
           [:with-id #(session/send-async-with-id % {:prompt "abandoned" :timeout-ms nil})]
           [:content #(session/<send! % {:prompt "abandoned" :timeout-ms nil})]
           [:message #(session/<send-and-wait! % {:prompt "abandoned" :timeout-ms nil})]]]
    (testing (name label)
      (let [{:keys [session client session-id server close]} (gated-send-context)
            lock (get-in @(:state client) [:session-io session-id :send-lock])
            attempts (java.util.concurrent.CountDownLatch. 1)
            parked (java.util.concurrent.CountDownLatch. 1)
            cancelled (promise)
            cancel-count (atom 0)
            requests (atom [])
            real-wrapper util/cancellable-channel]
        (async/<!! lock)
        (swap! (:state client) assoc-in [:session-io session-id :send-lock]
               (observe-take-attempts lock attempts parked))
        (mock/set-request-hook! server
                                (fn [method params]
                                  (when (= method "session.send")
                                    (swap! requests conj (:prompt params)))))
        (try
          (with-redefs [util/cancellable-channel
                        (fn [output cancel!]
                          (real-wrapper output
                                        #(do (cancel!)
                                             (swap! cancel-count inc)
                                             (deliver cancelled true))))]
            (let [pending (future (start session))]
              (is (.await parked 5 java.util.concurrent.TimeUnit/SECONDS))
              (if (= label :with-id)
                (future-cancel pending)
                (let [result (await-value! pending "unadmitted channel" 5000)]
                  (async/close! result)
                  (async/close! result)))
              (await-value! cancelled "cancellation propagation" 5000)
              (async/>!! lock :token)
              (let [following (future
                                (session/send-and-wait! session {:prompt "after cancellation"} 5000))]
                (try
                  (is (= (get-in (await-value! following "send after cancellation" 5000)
                                 [:data :content])
                         "Mock response to: after cancellation"))
                  (finally
                    (future-cancel following))))
              (is (= @requests ["after cancellation"]))
              (is (= @cancel-count (if (#{:content :message} label) 2 1)))))
          (finally
            (close)))))))

(deftest timeout-survives-a-full-output-buffer-and-cancels-the-pending-ack
  (let [{:keys [session client session-id release send-started close] :as ctx}
        (gated-send-context)
        io (get-in @(:state client) [:session-io session-id])
        real-chan async/chan
        real-timeout async/timeout
        deadline (real-chan)
        saturated (promise)
        output-control (atom nil)]
    (try
      (with-redefs [async/chan
                    (fn [& args]
                      (if (and (= args [1024]) (nil? @output-control))
                        (let [control (paused-output real-chan saturated)]
                          (reset! output-control control)
                          (:channel control))
                        (apply real-chan args)))
                    async/timeout (fn [^long ms]
                                    (if (= ms 15000) deadline (real-timeout ms)))]
        (let [result-ch (session/send-async session {:prompt "hi" :timeout-ms 15000})]
          (await-value! send-started "pending send RPC" 5000)
          (inject-events! ctx
                          (mapv (fn [index]
                                  {:type "assistant.message" :agentId "child-1"
                                   :data {:messageId (str "child-" index) :content (str index)}})
                                (range 1536)))
          (await-atom! (:last-write @output-control)
                       #(= (get-in % [:data :content]) "1535")
                       "full output at session intake" 5000)
          (async/close! deadline)
          (await-value! saturated "timeout publication after cleanup" 5000)
          (is (empty? (get-in @(:state client) [:connection :pending-requests])))
          (is (nil? @(:async-send-state io)))
          ((:resume! @output-control))
          (let [events (read-channel! (async/into [] result-ch))]
            (is (= (:type (last events)) :copilot/session.error))
            (is (= (get-in (last events) [:data :timeout-ms]) 15000)))))
      (finally
        (release)
        (async/close! deadline)
        (when-let [control @output-control]
          ((:close! control)))
        (close)))))

(deftest cancelling-async-sends-removes-pending-rpc-acknowledgements
  (doseq [[label start]
          [[:events #(session/send-async % {:prompt "hi" :timeout-ms nil})]
           [:with-id #(session/send-async-with-id % {:prompt "hi" :timeout-ms nil})]
           [:content #(session/<send! % {:prompt "hi" :timeout-ms nil})]
           [:message #(session/<send-and-wait! % {:prompt "hi" :timeout-ms nil})]]]
    (testing (name label)
      (let [{:keys [session client session-id release send-started close]} (gated-send-context)
            io (get-in @(:state client) [:session-io session-id])]
        (try
          (let [pending (future (start session))]
            (await-value! send-started "pending send RPC" 5000)
            (is (= (count (get-in @(:state client) [:connection :pending-requests])) 1))
            (if (= label :with-id)
              (future-cancel pending)
              (async/close! (await-value! pending "async channel" 5000)))
            (await-atom! (:state client)
                         #(empty? (get-in % [:connection :pending-requests]))
                         "pending RPC cancellation" 5000)
            (let [[token port] (async/alts!! [(:send-lock io) (async/timeout 5000)])]
              (is (= port (:send-lock io)))
              (is (= token :token))
              (is (nil? @(:async-send-state io)))
              (when (= port (:send-lock io))
                (async/>!! (:send-lock io) token))))
          (finally
            (release)
            (close)))))))

(deftest cancellation-and-disconnect-release-full-final-publication
  (doseq [action [:cancel :disconnect]
          [label start]
          [[:events #(session/send-async % {:prompt "hi" :timeout-ms nil})]
           [:with-id #(-> (session/send-async-with-id % {:prompt "hi" :timeout-ms nil}) :events-ch)]
           [:content #(session/<send! % {:prompt "hi" :timeout-ms nil})]
           [:message #(session/<send-and-wait! % {:prompt "hi" :timeout-ms nil})]]]
    (testing (str action " " label)
      (let [{:keys [session client session-id release send-started close] :as ctx}
            (gated-send-context)
            io (get-in @(:state client) [:session-io session-id])
            real-chan async/chan
            saturated (promise)
            output-control (atom nil)]
        (try
          (with-redefs [async/chan
                        (fn [& args]
                          (if (and (= args [1024]) (nil? @output-control))
                            (let [control (paused-output real-chan saturated)]
                              (reset! output-control control)
                              (:channel control))
                            (apply real-chan args)))]
            (let [pending (future (start session))]
              (await-value! send-started "send admission" 5000)
              (inject-events! ctx
                              (mapv (fn [index]
                                      {:type "assistant.message" :agentId "child-1"
                                       :data {:messageId (str "child-" index) :content (str index)}})
                                    (range 1536)))
              (release)
              (let [result-ch (await-value! pending "async channel" 5000)]
                (await-value! saturated "full final publication" 5000)
                (is (nil? @(:async-send-state io)))
                (if (= action :cancel)
                  (do
                    (async/close! result-ch)
                    (await-value! (:finished @output-control) "canceled final publisher" 5000)
                    (let [following (future (session/send-and-wait! session {:prompt "next"} 5000))]
                      (try
                        (is (= (get-in (await-value! following "next send" 5000) [:data :content])
                               "Mock response to: next"))
                        (finally
                          (future-cancel following)))))
                  (do
                    (sdk/disconnect! session)
                    (await-value! (:closed @output-control) "disconnected final publisher" 5000)
                    (is (async-protocols/closed? (:shutdown-chan io))))))))
          (finally
            (release)
            (when-let [control @output-control]
              ((:close! control)))
            (close)))))))

(deftest async-timeout-cannot-replace-an-already-claimed-rpc-response
  (let [{:keys [session client session-id release send-started close]} (gated-send-context)
        real-request protocol/send-request
        real-cancel protocol/cancel-request!
        real-timeout async/timeout
        deadline (async/chan)
        claimed (promise)
        release-claim (promise)
        cancel-attempt (promise)]
    (try
      (with-redefs
       [protocol/send-request
        (fn send-request
          ([conn method params] (send-request conn method params {}))
          ([conn method params opts]
           (real-request
            conn method params
            (cond-> opts
              (= method "session.send")
              (assoc :on-response-inline
                     (fn [_]
                       (deliver claimed true)
                       (await-value! release-claim "claimed response release" 5000)))))))
        protocol/cancel-request!
        (fn [conn response]
          (let [removed? (real-cancel conn response)]
            (deliver cancel-attempt (boolean removed?))
            removed?))
        async/timeout (fn [^long ms]
                        (if (= ms 15000) deadline (real-timeout ms)))]
        (let [pending (future (session/send-async-with-id session {:prompt "hi" :timeout-ms 15000}))]
          (await-value! send-started "send admission" 5000)
          (release)
          (await-value! claimed "reader claim" 5000)
          (let [done-chan (:done-chan @(get-in @(:state client)
                                               [:session-io session-id :async-send-state]))
                [_ port] (async/alts!! [done-chan (real-timeout 5000)])]
            (is (= port done-chan) "Root terminal intake must precede deadline expiry"))
          (async/close! deadline)
          (is (false? (await-value! cancel-attempt "timeout ownership check" 5000)))
          (deliver release-claim true)
          (let [{:keys [message-id events-ch]} (await-value! pending "claimed acknowledgement" 5000)
                events (read-channel! (async/into [] events-ch))]
            (is (string? message-id))
            (is (= (:type (last events)) :copilot/session.idle))
            (is (= (get-in (last (filter #(= (:type %) :copilot/assistant.message) events))
                           [:data :content])
                   "Mock response to: hi")))))
      (finally
        (deliver release-claim true)
        (release)
        (async/close! deadline)
        (close)))))

(deftest shutdown-during-request-preparation-cancels-the-captured-registration
  (doseq [[label start]
          [[:events session/send-async]
           [:with-id session/send-async-with-id]
           [:content session/<send!]
           [:message session/<send-and-wait!]]]
    (testing (name label)
      (let [{:keys [session client server release close]} (gated-send-context)
            preparing (promise)
            release-preparation (promise)
            caller-thread (promise)
            request-count (atom 0)
            opts {:prompt "hi" :timeout-ms nil
                  :response-schema
                  {:to-json-schema
                   (fn []
                     (deliver preparing (Thread/currentThread))
                     (await-value! release-preparation "schema preparation release" 5000)
                     {"type" "object"})
                   :parse identity}}]
        (mock/set-request-hook! server
                                (fn [method _]
                                  (when (= method "session.send")
                                    (swap! request-count inc))))
        (try
          (let [pending (future
                          (deliver caller-thread (Thread/currentThread))
                          (try
                            {:value (start session opts)}
                            (catch Throwable failure {:error failure})))]
            (is (= (await-value! preparing "schema preparation" 5000)
                   (await-value! caller-thread "caller thread" 5000)))
            (sdk/stop! client)
            (is (empty? (:session-io @(:state client))))
            (deliver release-preparation true)
            (let [{:keys [value error]} (await-value! pending "shutdown during preparation" 5000)]
              (if (= label :with-id)
                (is (instance? clojure.lang.ExceptionInfo error))
                (do
                  (is (nil? error))
                  (is (nil? (read-channel! value))))))
            (is (zero? @request-count)))
          (finally
            (deliver release-preparation true)
            (release)
            (close)))))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 3: a send rejection wins over an earlier session.error.
;; -----------------------------------------------------------------------------

(deftest send-rejection-wins-over-earlier-session-error
  (testing "a rejected session.send propagates even when a session.error arrived first"
    (let [{:keys [session reject send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "session error")
          (reject "send failed")
          (is (= [:threw "send failed"]
                 (deref pending 5000 ::timeout))
              "the send rejection, not the earlier session.error, must win"))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 4: the first terminal outcome observed wins.
;; -----------------------------------------------------------------------------

(deftest first-terminal-outcome-wins-idle-first
  (testing "idle observed before a later error returns nil (idle wins)"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-idle! ctx)
          (inject-error! ctx "later error")
          (release)
          (is (nil? (deref pending 5000 ::timeout))))
        (finally (close))))))

(deftest first-terminal-outcome-wins-error-first
  (testing "an error observed before a later idle throws the error (error wins)"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "first error")
          (inject-idle! ctx)
          (release)
          (is (= [:threw "first error"]
                 (deref pending 5000 ::timeout))))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; PAR-003: the zero-timeout default aligns to upstream's 60000ms.
;;
;; Isolated from the RPC path: `send!` is stubbed so the only `async/timeout`
;; call inside `send-and-wait!` is the loop deadline, making the captured value
;; the default under test.
;;
;; Determinism: `send-and-wait!` taps the event mult *before* calling `send!`,
;; so the stubbed `send!` dispatches `session.idle` itself. That guarantees idle
;; is emitted after the tap is installed and cannot be dropped, letting the call
;; run synchronously and return the moment idle is observed -- no thread-timing
;; assumptions and no fixed sleeps. A `finally` tears the client down so an
;; unexpected failure cannot strand a 60s deadline wait or a live tap.
;; -----------------------------------------------------------------------------

(deftest default-timeout-matches-upstream-60s
  (testing "the zero-timeout send-and-wait! deadline is 60000ms"
    (let [captured (atom [])
          real-timeout async/timeout
          client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "timeout-session" {})
          session-id (sdk/session-id copilot-session)]
      (try
        (with-redefs [async/timeout (fn [^long ms] (swap! captured conj ms) (real-timeout ms))
                      session/send! (fn [_ _]
                                      ;; Invoked after the mult tap, so this idle
                                      ;; is guaranteed to reach the waiting loop.
                                      (session/dispatch-event!
                                       client session-id
                                       {:type :copilot/session.idle :data {}})
                                      "msg")]
          (is (nil? (session/send-and-wait! copilot-session {:prompt "hi"}))
              "idle with no preceding assistant message returns nil"))
        (is (= [60000] @captured)
            "the sole deadline timeout must be the 60000ms default")
        (is (not-any? #{300000 180000} @captured)
            "the prior 300000/180000 defaults must be gone")
        (finally
          (sdk/force-stop! client))))))

;; -----------------------------------------------------------------------------
;; ASY-002 follow-up: a caller parked on the send-lock when the session is torn
;; down wakes with a closed lock (nil) and must settle on a consistent
;; "disconnected" outcome via `send!`'s own guard -- never a hang, and never a
;; silent proceed-to-send. This is distinct from the force-stop test that covers
;; a caller already past the lock and waiting on events (which fails with
;; "Event channel closed"); here the caller is still blocked acquiring the lock.
;; -----------------------------------------------------------------------------

(deftest parked-send-lock-caller-sees-consistent-disconnect-after-force-stop
  (testing "force-stop closing the send-lock wakes a parked caller into a disconnected error"
    (let [client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "parked-lock-session" {})
          send-lock (get-in @(:state client)
                            [:session-io "parked-lock-session" :send-lock])]
      ;; Drain the single lock token so a fresh send-and-wait! must park on it.
      (is (some? (async/<!! send-lock)))
      (let [pending (future
                      (try
                        (session/send-and-wait! copilot-session {:prompt "hi"} 5000)
                        :completed
                        (catch Exception e [:threw (ex-message e)])))]
        ;; The caller is now parked acquiring the lock (or about to). Tearing the
        ;; session down closes the lock, waking the caller with nil; send!'s guard
        ;; then produces a deterministic disconnected error regardless of the
        ;; check/park interleaving.
        (sdk/force-stop! client)
        (let [result (deref pending 5000 ::timeout)]
          (is (vector? result)
              "the parked caller must throw, not hang or complete a send")
          (is (= :threw (first result)))
          (is (re-find #"Session has been disconnected" (second result))
              "the outcome must be a consistent disconnected error"))))))
