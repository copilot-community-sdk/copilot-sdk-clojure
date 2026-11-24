(ns github.copilot-sdk.turn-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as p]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.turn :as turn])
  (:import [clojure.lang ExceptionInfo]))

(defn- take-bounded [ch]
  (let [[v port] (a/alts!! [ch (a/timeout 3000)] :priority true)]
    (when-not (= port ch) (throw (ex-info "Test channel did not resolve" {})))
    v))

(defn- one [ch]
  (let [v (take-bounded ch)]
    (is (some? v))
    (is (nil? (take-bounded ch)))
    v))

(defn- request [ch]
  {:reply ch :cancel! #(a/close! ch)})

(defn- wire-reply [reply]
  (request (doto (a/chan 1) (a/offer! reply) (a/close!))))

(defn- native-result [id]
  {:message-id (str "request-" id)
   :response {:type "assistant.message" :data {:content id}}
   :aborted false
   :completion-event-id id})

(defn- publish-idle! [state id]
  (turn/publish! state {:id id :type :copilot/session.idle}))

(defn- until-draining [state id]
  ;; The watch synchronizes publication after (not concurrently with) RPC
  ;; admission on a worker; no scheduler or fixed delay decides this test.
  (let [ready (promise)
        key (Object.)
        observe (fn [s] (when (some #(= id (:id %)) (vals (:waiters s)))
                          (deliver ready true)))]
    (add-watch state key (fn [_ _ _ s] (observe s)))
    (try (observe @state) (is (true? (deref ready 3000 false)))
         (finally (remove-watch state key)))))

(deftest overlapping-rpcs-keep-their-own-response-and-publication-boundary
  (let [state (turn/new-state)
        early (native-result "early")
        late (native-result "late")
        late-op (turn/send-and-wait! state (fn [_] (wire-reply {:result late})) {})
        early-op (turn/send-and-wait!
                  state (fn [opts]
                          (is (= {:prompt "hello"} opts))
                          (publish-idle! state "early")
                          (wire-reply {:result early}))
                  {:prompt "hello" :timeout-ms 5000})]
    (until-draining state "late")
    (is (= {:ok {:type :copilot/assistant.message :data {:content "early"}}} (one early-op)))
    (turn/publish! state {:id "late" :type :copilot/session.error :agent-id "child"})
    (turn/publish! state {:id "late" :type :copilot/assistant.message})
    (is (nil? (a/poll! late-op)))
    (turn/publish! state {:id "late" :type :copilot/session.autopilot_stopped})
    (is (= {:ok {:type :copilot/assistant.message :data {:content "late"}}} (one late-op)))))

(deftest native-errors-drain-their-own-event-and-preserve-opaque-data
  (let [state (turn/new-state)
        error {:code -32001 :message "Provider error"
               :data {:completionEventId "failure" :VendorKey [1 2]}}
        op (turn/send-and-wait! state (fn [_] (wire-reply {:error error})) {})]
    (until-draining state "failure")
    (turn/publish! state {:id "failure" :type :copilot/session.error :data {:message "event error"}})
    (let [e (:error (one op))]
      (is (instance? ExceptionInfo e))
      (is (= "Provider error" (ex-message e)))
      (is (= {:method "session.sendAndWait" :error error} (ex-data e))))))

(deftest local-admission-failure-is-not-a-wire-error
  (let [state (turn/new-state)
        cause (ex-info "Session closed before enqueue" {:type :session-closed})
        op (turn/send-and-wait! state (fn [_] (wire-reply cause)) {})]
    (is (identical? cause (:error (one op))))))

(deftest native-completion-shapes
  (let [state (turn/new-state)
        base {:message-id "quiet" :aborted false}
        run (fn [r] (one (turn/send-and-wait! state (fn [_] (wire-reply {:result r})) {})))]
    (is (= {:ok nil} (run base)))
    (doseq [r [(assoc base :aborted true)
               (assoc base :response {:type "user.message"})
               (assoc base :response {:type "assistant.message" :agent-id "sub"})
               (assoc base :response nil)
               nil]]
      (is (instance? ExceptionInfo (:error (run r)))))
    (is (= :invalid-timeout
           (-> (turn/send-and-wait! state (fn [_] (throw (AssertionError. "unexpected send")))
                                    {:timeout-ms nil})
               one :error ex-data :type)))))

(deftest structured-completion-requires-content-without-tool-requests
  (let [state (turn/new-state)
        base {:message-id "structured" :aborted false}
        completed (fn [response]
                    (one (turn/send-and-wait!
                          state (fn [_] (wire-reply {:result (cond-> base response (assoc :response response))}))
                          {:response-schema {:type "object"}})))
        assistant (fn [data] {:type "assistant.message" :data data})]
    (doseq [response [nil (assistant {}) (assistant {:content " \t\n"})
                      (assistant {:content "{}" :tool-requests [{:name "get"}]})]]
      (is (= :missing-structured-response (-> (completed response) :error ex-data :type))))
    (let [data {:content "{\"answer\":2}" :tool-requests []}]
      (is (= {:ok {:type :copilot/assistant.message :data data}} (completed (assistant data)))))))

(deftest inbound-coercion-preserves-unmodeled-and-malformed-fields
  (doseq [[type data] [["user.message" {:attachments [{:type "file"} {:type "session_reference"}]}]
                       ["assistant.usage" {:cache-expires-at "not an instant"}]]]
    (is (= {:type (keyword "copilot" type) :data data}
           (turn/normalize-event {:type type :data data})))))

(deftest timeout-is-local-to-one-wire-request
  (let [state (turn/new-state)
        rpc (a/chan 1)
        stale (native-result "old")]
    (let [op (turn/send-and-wait! state (constantly (request rpc)) {:timeout-ms 20})]
      (is (= :turn-timeout (-> op one :error ex-data :type)))
      (is (p/closed? rpc)))
    (let [op (turn/send-and-wait! state (fn [_] (wire-reply {:result (native-result "new")})) {})]
      (until-draining state "new")
      (a/offer! rpc {:result stale})
      (a/close! rpc)
      (publish-idle! state "old")
      (publish-idle! state "new")
      (is (= "new" (get-in (one op) [:ok :data :content]))))))

(deftest close-wakes-rpcs-publication-waits-and-full-subscriptions
  (let [state (turn/new-state)
        s (turn/subscribe state)
        rpc (a/chan)
        pending (turn/send-and-wait! state (constantly (request rpc)) {})
        drain (turn/send-and-wait! state (fn [_] (wire-reply {:result (native-result "wait")})) {})
        cause (ex-info "Disconnected" {:type :disconnected})]
    (until-draining state "wait")
    (dotimes [i 64] (turn/publish! state {:index i}))
    (turn/close! state cause)
    (turn/close! state (Exception. "redundant close"))
    (is (= (mapv #(hash-map :index %) (range 64)) (vec (repeatedly 64 #(take-bounded s)))))
    (doseq [ch [pending drain s (turn/subscribe state)
                (turn/send-and-wait! state (fn [_] (throw (AssertionError. "unexpected send"))) {})]]
      (is (identical? cause (:error (one ch)))))
    (is (p/closed? rpc))))

(deftest bounded-slow-subscriber-does-not-stall-publication
  (let [state (turn/new-state)
        slow (turn/subscribe state)
        fast (turn/subscribe state)
        error-event {:type :copilot/session.error :id "overflow"}]
    (let [events (mapv #(hash-map :index %) (range 64))]
      (is (= events (mapv #(do (turn/publish! state %) (take-bounded fast)) events))))
    (let [publisher (Thread/currentThread)
          observed (promise)]
      (a/take! fast #(deliver observed [% (Thread/currentThread)]))
      (turn/publish! state error-event)
      (let [[e t] (deref observed 3000 [nil nil])]
        (is (= error-event e))
        (is (not (identical? publisher t)))))
    (is (= (mapv #(hash-map :index %) (range 64)) (vec (repeatedly 64 #(take-bounded slow)))))
    (is (= {:type :subscription-overflow :event error-event} (-> slow one :error ex-data)))
    (a/close! fast)
    (turn/publish! state {:index :after-close})
    (is (nil? (take-bounded fast)))))

(deftest pre-response-completion-history-is-bounded
  (let [state (turn/new-state)
        rpc (a/chan)
        op (turn/send-and-wait! state
                                (fn [_]
                                  (dotimes [i 65] (publish-idle! state (str i)))
                                  (request rpc))
                                {})]
    (is (= :completion-overflow (-> op one :error ex-data :type)))
    (is (p/closed? rpc))
    (is (= {:ok nil}
           (one (turn/send-and-wait! state (fn [_] (wire-reply {:result {:message-id "other"
                                                                         :aborted false}})) {}))))))

(defn- blocking-read [read! ch]
  (let [parked (promise)
        reader (future (read! (reify p/ReadPort
                                (take! [_ handler]
                                  (let [v (p/take! ch handler)]
                                    (when-not v (deliver parked true))
                                    v)))))]
    (is (true? (deref parked 3000 false)))
    reader))

(deftest blocked-take-callbacks-do-not-starve-unrelated-readers
  (let [noisy (turn/new-state)
        events (turn/subscribe noisy)
        entered (java.util.concurrent.CountDownLatch. 4)
        gate (promise)
        quiet (turn/new-state)
        rpc (a/chan 1)
        op (turn/send-and-wait! quiet (constantly (request rpc)) {})
        done (blocking-read sdk/await!! op)
        closed (blocking-read a/<!! (turn/subscribe quiet))
        cause (ex-info "Detached" {})]
    (try
      ;; Deliberately violate take!'s nonblocking callback contract in one session.
      (dotimes [_ 4] (a/take! events (fn [_] (.countDown entered) (deref gate 15000 false))))
      (dotimes [i 4] (turn/publish! noisy {:index i}))
      (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
      (a/offer! rpc {:result {:message-id "quiet" :aborted false}})
      (is (nil? (deref done 3000 ::stalled)))
      (turn/close! quiet cause)
      (is (= {:error cause} (deref closed 3000 ::stalled)))
      (finally
        (deliver gate true)
        (a/close! events)
        (turn/close! quiet cause)))))

(deftest enqueue-precedes-return-and-close-prevents-later-enqueues
  (let [state (turn/new-state)
        caller (Thread/currentThread)
        trace (atom [])
        submit (fn [opts] (swap! trace conj [(:prompt opts) (Thread/currentThread)]) (request (a/chan)))
        closed (ex-info "Closed after enqueue" {})
        op (turn/send-and-wait! state submit {:prompt :turn})]
    (swap! trace conj [:abort caller])
    (turn/close! state closed)
    (let [late (turn/send-and-wait! state submit {:prompt :late})]
      (is (= [[:turn caller] [:abort caller]] @trace))
      (is (identical? closed (:error (one op))))
      (is (identical? closed (:error (one late)))))))

(deftest close-during-enqueue-does-not-invert-client-and-session-locks
  (let [state (turn/new-state)
        entered (promise)
        release (promise)
        rpc (a/chan)
        cause (ex-info "Session detached" {})
        op (future (turn/send-and-wait! state
                                        (fn [_] (deliver entered true)
                                          (when-not (deref release 3000 false)
                                            (throw (ex-info "Blocked waiting for client lock" {})))
                                          (request rpc))
                                        {}))]
    (try
      (is (true? (deref entered 3000 false)))
      (turn/close! state cause)
      (finally (deliver release true)))
    (is (identical? cause (:error (one (deref op 3000 nil)))))
    (is (p/closed? rpc))))

(defn- observable-receipt []
  (let [source (a/promise-chan)
        waiting (promise)]
    {:waiting waiting
     :resolve! #(a/offer! source %)
     :receipt (reify p/ReadPort
                (take! [_ handler]
                  (let [result (p/take! source handler)]
                    (deliver waiting true)
                    result)))}))

(deftest observer-fence-handles-both-publication-orders
  (doseq [event-first? [false true]]
    (let [state (turn/new-state)
          subscribers (turn/subscribe state)
          {:keys [receipt resolve! waiting]} (observable-receipt)
          rpc (a/chan 1)
          id "matched"
          event {:id id :type :copilot/session.idle}
          op (turn/send-and-wait! state (constantly (request rpc)) {})]
      (when event-first? (turn/publish! state event receipt))
      (a/offer! rpc {:result (native-result id)})
      (when-not event-first?
        (until-draining state id)
        (turn/publish! state event receipt))
      (is (true? (deref waiting 3000 false)))
      (is (= event (take-bounded subscribers)))
      (is (nil? (a/poll! op)))
      (resolve! {:ok nil})
      (is (= id (get-in (one op) [:ok :data :content])))
      (a/close! subscribers))))

(deftest observer-fence-is-bounded-by-the-original-deadline-and-session-close
  (doseq [stop [:deadline :close :observer-error :wire-error]]
    (let [state (turn/new-state)
          deadline (a/chan)
          {:keys [receipt resolve! waiting]} (observable-receipt)
          cause (ex-info "Interrupted" {})
          id "fence"
          reply (if (= stop :wire-error)
                  {:error {:code -32000 :message "Runtime error" :data {:completionEventId id}}}
                  {:result (native-result id)})
          op (with-redefs [a/timeout (fn [^long _] deadline)]
               (turn/send-and-wait! state (fn [_] (wire-reply reply)) {}))]
      (until-draining state id)
      (turn/publish! state {:type :copilot/session.error :id id} receipt)
      (is (true? (deref waiting 3000 false)))
      (case stop
        :deadline (a/close! deadline)
        :close (turn/close! state cause)
        (:observer-error :wire-error) (resolve! {:error cause}))
      (let [{:keys [error ok]} (one op)]
        (case stop
          :deadline (is (= :turn-timeout (:type (ex-data error))))
          :close (is (identical? cause error))
          :observer-error (is (= id (get-in ok [:data :content])))
          :wire-error (is (= (:error reply) (:error (ex-data error))))))
      (resolve! {:ok nil})
      (is (empty? (:waiters @state))))))
