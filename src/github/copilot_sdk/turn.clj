(ns ^:no-doc github.copilot-sdk.turn
  "Request-correlated native turn completion and session event subscriptions."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as p]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [copilot.sdk.specs :as specs]
            [github.copilot-sdk.channels :as channels]
            [github.copilot-sdk.generated.coerce :as coerce]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.teardown :as teardown]
            [github.copilot-sdk.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory TimeUnit]))

(def ^:private capacity 64)
(def ^:private method-name "session.sendAndWait")

(defn new-state
  ([] (new-state false))
  ([observer?] (atom {:cursor 0 :waiters {} :subscriptions {} :observer? observer?})))

(defn admission-cursor [state]
  (:cursor @state))

(defn- register! [state registry channel entry]
  ;; Advance the cursor in the same swap that installs the consumer. The
  ;; reader needs only this number, not the set of subscriptions or waiters.
  (swap! state (fn [{:keys [cursor] :as s}]
                 (let [next-cursor (inc cursor)]
                   (-> s
                       (assoc :cursor next-cursor)
                       (assoc-in [registry channel] (entry next-cursor)))))))

(defn normalize-event
  "Convert a protocol-decoded event to the SDK event shape. Use for live events
   and the response embedded in session.sendAndWait. If a field cannot be
   converted, retain the original data and normalize only the event type."
  [event]
  (let [converted (try (coerce/event-wire->idiom event)
                       (catch ExceptionInfo e
                         (log/warn "Could not coerce session event; retaining wire values"
                                   (select-keys (ex-data e) [:event-type :field]))
                         event))]
    (update converted :type util/event-type->keyword)))

(defn- failure [cause]
  {:error (if (or (instance? ExceptionInfo cause) (instance? InterruptedException cause))
            cause
            (ex-info (or (ex-message cause) "Session closed") {} cause))})

(defn- finish! [ch outcome]
  ;; A terminal put can wait behind a full buffer. No further puts follow it.
  (a/put! ch outcome)
  (a/close! ch))

(defn subscribe
  "Return a read channel of normalized events; close it to unsubscribe.
   A slow subscriber can buffer 64 events.
   Overflow or session close ends the stream with {:error ExceptionInfo}.
   On overflow, ex-data contains :type :subscription-overflow and the first
   undelivered :event, even if that event was a runtime error. As with any
   core.async channel, take! callbacks must not block. For blocking work, use
   <!! from a consumer thread you own instead."
  [state]
  (let [source (a/chan capacity)]
    (locking state
      (if-let [closed (:closed @state)]
        (finish! source closed)
        (register! state :subscriptions source identity)))
    (channels/read-channel source #(locking state
                                     (swap! state update :subscriptions dissoc source)
                                     (a/close! source)))))

(defn- root-event? [{:keys [agent-id]}]
  (or (nil? agent-id) (= "" agent-id)))

(defn- waits-closed [{:keys [released closed]}] (or released closed))

(defn- observe! [state signal {:keys [id seen] :as waiter} published receipt]
  (cond
    (= id published) (do (swap! state assoc-in [:waiters signal] (dissoc waiter :id))
                         (a/offer! signal {:receipt receipt}))
    (nil? seen) nil
    (contains? seen published) nil
    (< (count seen) capacity) (swap! state assoc-in [:waiters signal :seen published] receipt)
    :else
    (do
      (swap! state update :waiters dissoc signal)
      (finish! signal {:error (ex-info "Too many completion events before the RPC response"
                                       {:type :completion-overflow :capacity capacity})}))))

(defn publish!
  "Publish one normalized event. Only root completion IDs are tracked, and
   only while a request can still name them in its native response. An ordinary
   observer supplies a promise channel with {:ok nil} or {:error ExceptionInfo}
   after delivery. Without an observer, omit the receipt or pass nil. For
   queued session events, pass the admission cursor captured by the reader."
  ([state event] (publish! state event nil))
  ([state event receipt] (publish! state event receipt (admission-cursor state)))
  ([state event receipt cursor]
   (locking state
     (doseq [[ch registered] (:subscriptions @state) :when (<= registered cursor)]
       (when-not (a/offer! ch event)
         (swap! state update :subscriptions dissoc ch)
         (finish! ch {:error (ex-info "Session event subscription overflow"
                                      {:type :subscription-overflow :event event})})))
     (when (and (:id event) (root-event? event)
                (#{:copilot/session.idle :copilot/session.error
                   :copilot/session.autopilot_stopped} (:type event)))
       (doseq [[signal waiter] (:waiters @state) :when (<= (:cursor waiter) cursor)]
         (observe! state signal waiter (:id event) receipt))))))

(defn release-waits!
  "Seal turn admission at release. A timely release drains pre-ACK replies
   and events when there is no observer; the runtime reply is withdrawn at
   the ACK if still unanswered. Never wait for an application callback."
  [state cause timely?]
  (locking state
    (when-not (waits-closed @state)
      (let [outcome (failure cause)
            draining (if (and timely? (not (:observer? @state)))
                       (into {} (filter (fn [[_ waiter]] @(:submitted? waiter))) (:waiters @state))
                       {})
            [before _] (swap-vals! state assoc :released outcome :waiters draining)]
        (doseq [ch (keys (:waiters before)) :when (not (contains? draining ch))]
          (finish! ch outcome))))))

(defn close!
  "Stop admitting operations and close subscriptions. At EOF the protocol
   settles every RPC; retain early completions until each waiter reads its
   own reply. Only a known ID still missing its event needs a wakeup."
  ([state cause] (close! state cause false))
  ([state cause eof?]
   (locking state
     (when-not (:closed @state)
       (let [{:keys [waiters subscriptions released]} @state
             outcome (or released (failure cause))
             drain? (or eof? released)]
         (swap! state assoc :closed outcome :waiters (if drain? waiters {}) :subscriptions {})
         (doseq [ch (keys subscriptions)] (finish! ch outcome))
         (doseq [[ch {:keys [id]}] waiters :when (or (not drain?) id)] (finish! ch outcome)))))))

(defn- response-outcome [{:keys [result error]} structured?]
  (if error
    {:error (ex-info (or (:message error) "RPC error") {:method method-name :error error})}
    (let [{:keys [response aborted]} result
          {:keys [content tool-requests]} (:data response)]
      (cond
        (not (and (map? result) (string? (:message-id result)) (boolean? aborted)))
        {:error (ex-info "Invalid native completion result" {:type :invalid-completion})}
        (and (contains? result :response)
             (not (and (= "assistant.message" (:type response)) (root-event? response))))
        {:error (ex-info "Runtime returned a non-root assistant response" {:type :invalid-completion})}
        aborted {:error (ex-info "The requested run was aborted" {:type :turn-aborted})}
        (and structured? (or (not (string? content)) (str/blank? content) (seq tool-requests)))
        {:error (ex-info "Run completed without a structured assistant response"
                         {:type :missing-structured-response})}
        :else {:ok (some-> response normalize-event)}))))

(defn- match-completion! [state signal id]
  (locking state
    (if-let [{:keys [seen] :as waiter} (get-in @state [:waiters signal])]
      (if (and (string? id) (seq id) (not (contains? seen id)))
        (or (:closed @state) (do (swap! state assoc-in [:waiters signal] (assoc (dissoc waiter :seen) :id id)) nil))
        (do (swap! state assoc-in [:waiters signal] (dissoc waiter :seen))
            {:receipt (get seen id)}))
      (or (waits-closed @state) (a/poll! signal)))))

(defn- timeout-error [timeout-ms]
  (ex-info "Timeout waiting for native turn completion" {:type :turn-timeout :timeout-ms timeout-ms}))

(defonce ^:private ^ScheduledThreadPoolExecutor deadline-worker
  (doto (ScheduledThreadPoolExecutor.
         1 (reify ThreadFactory
             (newThread [_ r] (doto (Thread. nil r "copilot-turn-deadline" 0 false) (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- start-deadline [timeout-ms]
  ;; core.async/timeout may share a timer between callers; it cannot be retired per turn.
  (let [ch (a/chan)
        timer (.schedule deadline-worker ^Runnable #(a/close! ch) (long timeout-ms) TimeUnit/MILLISECONDS)]
    [ch #(.cancel timer false)]))

(defn- settle-wait! [state signal outcome]
  (locking state
    (let [[before _] (swap-vals! state update :waiters dissoc signal)]
      ;; EOF keeps reader-owned replies; explicit release takes unfinished waits.
      (if (contains? (:waiters before) signal) outcome (or (waits-closed before) outcome)))))

(defn- await-response [state signal {:keys [reply cancel! observer-delivery]} deadline timeout-ms structured?]
  (a/go
    (settle-wait!
     state signal
     (try
       (teardown/with-cleanup cancel!
         (let [[response port] (a/alts! [signal deadline reply] :priority true)]
           (when (= port signal) (throw (:error response)))
           (when (= port deadline) (throw (timeout-error timeout-ms)))
           (when (instance? Throwable response) (throw response))
           (let [id (if (:error response)
                      ;; Protocol error data retains its original JSON member names.
                      (get-in response [:error :data :completionEventId])
                      (get-in response [:result :completion-event-id]))
                 publication (or (match-completion! state signal id)
                                 (let [[v port] (a/alts! [signal deadline] :priority true)]
                                   (when (= port deadline) (throw (timeout-error timeout-ms)))
                                   v))]
             (when-let [e (:error publication)] (throw e))
             ;; An active observer cannot finish the event queued behind its own
             ;; callback. If it already returned, the normal delivery fence applies.
             (when-not (and observer-delivery (not (p/closed? observer-delivery)))
               (when-let [receipt (:receipt publication)]
                 (let [[v port] (a/alts! [signal deadline receipt] :priority true)]
                   (cond
                     (= port signal) (throw (:error v))
                     (= port deadline) (throw (timeout-error timeout-ms))
                     ;; A stopped observer can discard the completion. Handler
                     ;; exceptions and observer queue overflow remain local.
                     (#{:session-closed :observer-drain-timeout :observer-stopped}
                      (some-> v :error ex-data :type)) (throw (:error v))
                     (nil? v) (throw (ex-info "Observer closed without a receipt"
                                              {:type :missing-observer-receipt}))))))
             (response-outcome response structured?))))
       (catch Exception e (failure e))
       (catch Error e (settle-wait! state signal nil) (throw e))))))

(defn- completed [outcome]
  (doto (a/chan 1) (finish! outcome)))

(defn start-turn!
  "Return a channel with exactly one {:ok root-assistant-event-or-nil} or
   {:error cause}. (send-fn message admission-options) submits or reserves
   one native RPC on the caller and returns {:reply channel :cancel! fn}.
   Forward admission-options to the RPC without removing its checks or hooks,
   including after a queued trace lookup. A local reservation is not a
   submitted turn. cancel!
   releases only this reservation or pending RPC. The message excludes :timeout-ms.
   The budget (1..2147483647 ms, default 60000) starts after registration and
   covers preparation, admission, the RPC, publication and its observer receipt.
   It does not interrupt a caller waiting for a lock or bound channel return time.
   Closing the result abandons the local wait and withdraws any unsent
   reservation or a queued request not yet claimed by the writer. Neither
   timeout nor close aborts runtime work. A session
   admits up to 64 concurrent waits. Each tracks up to 64 distinct early root
   completion IDs. Exceeding either limit returns an error, never a best guess."
  [state send-fn options & [interrupted?]]
  (let [caller (when interrupted? (Thread/currentThread))
        timeout-ms (get options :timeout-ms 60000)
        signal (a/chan 1)
        submitted? (volatile! false)
        rejected (locking state
                   (or (waits-closed @state)
                       (cond
                         (not (s/valid? ::specs/turn-timeout-ms timeout-ms))
                         {:error (ex-info "Expected :timeout-ms between 1 and 2147483647"
                                          {:type :invalid-timeout})}
                         (>= (count (:waiters @state)) capacity)
                         {:error (ex-info "Too many pending turns" {:type :turn-capacity})}
                         :else (do (register! state :waiters signal (fn [cursor] {:cursor cursor :seen {} :submitted? submitted?})) nil))))]
    (if rejected
      (completed rejected)
      (try
        ;; Enqueue takes the client lifecycle lock. Detach closes this turn
        ;; state under that lock, so do not hold the turn lock here.
        (let [start (System/nanoTime)
              budget-ns (* 1000000 (long timeout-ms))
              [deadline stop-timer!] (start-deadline timeout-ms)
              check! (fn []
                       (when (and interrupted? (or (.isInterrupted ^Thread caller) @interrupted?))
                         (throw (InterruptedException. "Interrupted before turn submission")))
                       (when (or (p/closed? deadline) (>= (unchecked-subtract (System/nanoTime) start) budget-ns))
                         (throw (timeout-error timeout-ms))))
              rpc (try (send-fn (dissoc options :timeout-ms)
                                (cond-> {:check-admission! check! :on-submitted #(vreset! submitted? true)}
                                  interrupted? (assoc :with-admission (fn [offer!] (locking interrupted? (offer!))))))
                       (catch Throwable t (stop-timer!) (throw t)))
              cleanup (delay (stop-timer!) ((:cancel! rpc)))
              result (await-response state signal (assoc rpc :cancel! #(force cleanup))
                                     deadline timeout-ms (contains? options :response-schema))]
          (channels/read-channel
           result
           (fn []
             ;; Do not hold the turn lock while withdrawing admission on the client.
             (a/close! result)
             (locking state
               (when (contains? (:waiters @state) signal)
                 (swap! state update :waiters dissoc signal)
                 (finish! signal {:error (ex-info "Turn wait abandoned" {:type :turn-abandoned})})))
             (force cleanup))))
        (catch Throwable e
          (if (instance? Exception e)
            (completed (settle-wait! state signal (failure e)))
            (do (settle-wait! state signal nil) (throw e))))))))
