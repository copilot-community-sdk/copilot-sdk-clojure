(ns ^:no-doc github.copilot-sdk.native-callbacks
  "Owner for session.event broadcasts answered by an outbound RPC. User input,
  plan exit and auto-mode requests are reverse RPCs, not broadcast decisions.

  :events/:on-event receives a normalized event; its return is ignored.
  :tools/:definitions handlers take [arguments invocation]. Return a string,
  structured tool result, JSON value, or nil (empty text).
  A runtime invalid-params rejection of an automatic tool result or permission
  decision gets one redacted tool error or unattributed :user-not-available
  decision, never another handler invocation. If the terminal reply itself
  is rejected as invalid, the live request can be answered manually.
  Ambiguous delivery or a negative acknowledgement never triggers a retry.
  :permissions/:on-request takes [request invocation]. Return a permission
  decision, optionally {:kind :attributed :result decision :decision-context label}.
  Missing handlers and :no-result leave requests pending. Invalid decisions
  yield :user-not-available. No decision is labeled automatically.
  :interaction/:commands handlers receive one context; their result is ignored.
  :interaction/:on-elicitation-request takes one context; return {:action
  :accept/:decline/:cancel} with optional :content, :_meta and :decision-context.
  :mcp/:on-auth-request takes [request invocation]; return a token, :cancelled
  or :runtime-refresh result. An invalid result cancels the request.
  Application callbacks can return a core.async channel.

  Invocation :cancelled? is an owner-controlled zero-argument predicate.
  Cancellation interrupts blocking handlers and suppresses stale replies.
  An application that ignores interruption must check the predicate before
  performing side effects. Synchronous handlers and channel results share
  the invocation lifetime; there is no separate channel-only timeout.
  Form responders have their own two slots per session and four per client;
  they do not queue behind tools awaiting the user's answer.
  Other requests use two slots per session and eight per connection,
  including the wait for the runtime acknowledgement. A shared FIFO holds
  up to 16 waiting requests per session, 128 per connection. Each free
  worker takes the oldest request whose session has a free slot. A session
  can queue its first request even when all eight workers are busy.
  Excess requests receive a safe negative reply without invoking the handler.
  A session allows 16 outstanding busy replies and tracks 65,536 request IDs.
  A request that exceeds either limit fails this session's callback owner;
  detach it to release the runtime's work.
  No other session or connection is closed.

  Up to 16 sessions per connection can own a serial :on-event observer.
  Each buffers 64 live events; an initial batch retained before registration
  takes one slot. Handler return values are ignored. Overflow stops that
  observer only. After an active handler returns it receives a terminal
  {:error ex} instead of more events.
  Callback errors and interrupted/lost observations are reported separately
  from programmatic event subscriptions. A handler that ignores interruption
  retains its worker until it exits; new observers cannot exceed the limit.
  On connection loss, decisions stop immediately. Accepted observations can
  drain for 500 ms after input ends; a timeout fails outstanding receipts."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.callback-results :as results]
            [github.copilot-sdk.callback-scope :as scope]
            [github.copilot-sdk.interruption :as interruption]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.protocol :as rpc]
            [copilot.sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import [java.util.concurrent ArrayBlockingQueue CancellationException ExecutionException FutureTask SynchronousQueue
            RejectedExecutionException ThreadFactory ThreadPoolExecutor TimeUnit TimeoutException]
           [java.util.concurrent.locks ReentrantReadWriteLock]))

(defn- pool [size queue label]
  (doto (ThreadPoolExecutor.
         size size 60 TimeUnit/SECONDS queue
         (reify ThreadFactory
           (newThread [_ r] (doto (Thread. nil r label 0 false) (.setDaemon true)))))
    (.allowCoreThreadTimeOut true)))

(defn- lane [limit form? label]
  ;; An assigned successor may queue in the executor while its predecessor returns.
  {:active (volatile! #{}) :limit limit :form? form?
   :executor (pool limit (ArrayBlockingQueue. limit) label)})

(defn- connected? [{:keys [connection-id]} state]
  (and (true? (get-in state [:connection :running?]))
       (identical? connection-id (get-in state [:connection :connection-id]))))

(defn- registered? [sessions reg]
  (and (:token reg) (identical? (:token reg) (:token (get @sessions (:id reg))))))

(defn- decision-refusal [{:keys [conn sessions client-open?]} reg]
  ;; A terminal failure belongs to this generation. Physical EOF and release
  ;; also revoke decisions, but do not invalidate observations already accepted.
  (or @(:failure reg)
      (when-not (and (registered? sessions reg) (client-open?) (not @(:token reg))
                     (not (ap/closed? (:closed reg))) (connected? conn @(:state-atom conn)))
        ::retired)))

(defn- require-decision! [owner reg]
  (when-let [reason (decision-refusal owner reg)]
    (throw (if (= reason ::retired)
             (ex-info "Session is no longer active" {:type :session-closed})
             reason))))

(defn- admission-timeout [id]
  (ex-info "Timed out waiting for session decisions" {:type :decision-admission-timeout :session-id id}))

(defn- try-after-decisions! [conn {:keys [decision-fence]} admit!]
  ;; A receipt closes as soon as the router reconciles that decision. Ordinary
  ;; observations cannot delay admission, even when their own delivery is slow.
  (locking (:notification-queue conn)
    (let [fence @decision-fence]
      (if (or (nil? fence) (ap/closed? fence)) [(admit!)] [nil fence]))))

(defn- after-decisions!! [conn {:keys [closed failure id] :as reg} admit!]
  (loop [deadline nil]
    (let [[value fence] (try-after-decisions! conn reg admit!)]
      (if-not fence value
              (let [deadline (or deadline (a/timeout 10000))
                    [_ port] (a/alts!! [closed deadline fence] :priority true)]
                (cond
                  (= port closed) (throw (or @failure (ex-info "Session is no longer active" {:type :session-closed :session-id id})))
                  (= port deadline) (throw (admission-timeout id))
                  :else (recur deadline)))))))

(defn- fatal? [t] (and (instance? Error t) (not (instance? AssertionError t))))

(defn- cancel! [{:keys [interrupt cancel]}]
  (a/close! cancel)
  (when interrupt (interrupt :cancel)))

(defn- settle! [receipt outcome]
  (a/put! receipt outcome)
  (a/close! receipt)
  receipt)

(defn- finish-observer! [{:keys [queue pending terminal]} cause]
  (let [outcome {:error cause}]
    (when (compare-and-set! terminal nil outcome)
      (doseq [receipt @pending] (settle! receipt outcome))
      (reset! pending #{})
      (a/close! queue)
      (while (a/poll! queue)))))

(defn- retire-requests! [conn id token]
  (when-let [requests (seq (rpc/withdraw-requests! conn token))]
    (rpc/settle-withdrawn! conn requests (ex-info "Session closed" {:type :session-closed :session-id id}))))

(defn- cancel-requests! [{:keys [conn id token closed failure] :as reg}]
  (a/close! (:admission-closed reg))
  (when-not (ap/closed? closed)
    (when-not @failure (retire-requests! conn id token))
    (a/close! closed))
  (vswap! (:waiting reg) #(filterv (fn [job] (not (identical? token (:token job)))) %))
  (doseq [job (vals (first (reset-vals! (:jobs reg) {})))]
    (cancel! job)))

(defn- observer-thread? [lane]
  (identical? (Thread/currentThread) @(:worker lane)))

(defn- cancel-observer! [lane cause]
  (vreset! (:cancelled? lane) true)
  (finish-observer! lane cause)
  (when-not (observer-thread? lane)
    (when-let [interrupt @(:interrupt lane)] (interrupt :cancel))))

(def ^:private shutdown-ms 500)

(defn- await-observer-interrupt! [^FutureTask interrupt]
  (when interrupt
    (try (.get interrupt shutdown-ms TimeUnit/MILLISECONDS)
         (catch CancellationException _ nil)
         (catch ExecutionException e (throw (.getCause e)))
         (catch TimeoutException _ (log/warn "Observer interruption did not finish before the shutdown deadline"))
         (catch InterruptedException _ (.interrupt (Thread/currentThread))))))

(defn- release! [reg]
  (cancel-requests! reg)
  (when-let [lane (:observer reg)]
    (cancel-observer! lane (ex-info "Session observer closed" {:type :session-closed}))))

(defn- claim-failure! [{:keys [conn token closed client-open? failure-claimed?]}]
  ;; One CAS claims unanswered turns and forms, not replies already received.
  ;; An empty collection still means this caller won the session failure.
  (when (and (not @failure-claimed?) (not @token) (not (ap/closed? closed)) (client-open?))
    (when-let [requests (rpc/withdraw-requests! conn token)]
      (vreset! failure-claimed? true)
      requests)))

(defn- finish-failure! [{:keys [conn failure on-failure] :as reg} cause requests]
  ;; Fence ordinary reverse work before exposing the cause on the public handle.
  (when on-failure (on-failure cause))
  (reset! failure cause)
  (rpc/settle-withdrawn! conn requests cause)
  (release! reg))

(defn- fail-registration!
  ([reg cause] (fail-registration! reg cause nil))
  ([{:keys [conn failure] :as reg} cause eligible?]
   (let [lock (.readLock ^ReentrantReadWriteLock (:reply-barrier conn))]
     (.lock lock)
     (when-let [requests (try (when (or (nil? eligible?) (eligible?)) (claim-failure! reg))
                              (finally (.unlock lock)))]
       (finish-failure! reg cause requests)))
   @failure))

(defn fail!
  "Fail only the captured generation when publication cannot continue."
  [{:keys [sessions]} registration cause]
  (locking sessions
    (when (registered? sessions registration)
      (fail-registration! (get @sessions (:id registration)) cause))))

(defn- fail-active-decision! [{:keys [sessions] :as owner} reg eligible? cause]
  ;; A release can leave the registration in place to drain observations.
  ;; Claim failure under the same barrier as revocation. Teardown may
  ;; itself revoke reverse replies, so it runs after releasing the barrier.
  (locking sessions
    (fail-registration! reg cause #(and (nil? (decision-refusal owner reg)) (eligible?)))))

(defn- after-active-decisions!! [owner reg eligible? admit!]
  (try (after-decisions!! (:conn owner) reg admit!)
       (catch clojure.lang.ExceptionInfo e
         (when (= :decision-admission-timeout (:type (ex-data e)))
           (fail-active-decision! owner reg eligible? e))
         (throw e))))

(defn- stop-requests! [{:keys [sessions requests forms]}]
  (locking sessions
    (when-not (.isShutdown ^ThreadPoolExecutor (:executor requests))
      (doseq [reg (vals @sessions)] (cancel-requests! reg))
      ;; Discarded handoffs never enter run-job!'s finally. No more admits
      ;; are possible; the executor, not this set, owns any surviving worker.
      (doseq [{:keys [^ThreadPoolExecutor executor active]} [requests forms]]
        (.clear (.getQueue executor))
        (.shutdown executor)
        (vreset! active #{})))))

(defn- drain-observer! [{:keys [sessions draining]} {:keys [token observer]}]
  (when (and observer (not= :closed (:self-release (get @draining token)))
             (not (.isDone ^FutureTask @(:task observer))))
    (vswap! draining assoc token observer)
    (a/close! (:queue observer))
    (a/take! (a/timeout shutdown-ms)
             (fn [_]
               (locking sessions
                 (when-let [lane (get @draining token)]
                   ;; Self-close has already settled its receipts. Only an
                   ;; explicit owner close may interrupt the remaining callback.
                   (when-not (= :closed (:self-release lane))
                     (vswap! draining dissoc token)
                     (log/warn "Session event observer did not drain before the shutdown deadline")
                     (cancel-observer! lane (ex-info "Session event observer drain timed out"
                                                     {:type :observer-drain-timeout}))))))
             false)))

(defn- stop! [{:keys [sessions draining conn ^ThreadPoolExecutor observers] :as owner} drain?]
  (locking sessions
    (when-some [regs @sessions]
      (stop-requests! owner)
      (reset! sessions nil)
      (remove-watch (:state-atom conn) sessions)
      (if drain?
        (do (doseq [reg (vals regs)] (drain-observer! owner reg))
            (.shutdown observers))
        (doseq [lane (keep :observer (vals regs))]
          (cancel-observer! lane (ex-info "Session observer closed" {:type :session-closed})))))
    (when-not drain?
      (doseq [lane (vals @draining)]
        (cancel-observer! lane (ex-info "Session observer closed" {:type :session-closed})))
      (vreset! draining {})
      (.shutdown observers))))

(defn finish-input!
  "After the last accepted notification, close observer input without waiting for handlers."
  [owner]
  (stop! owner true))

(defn cancel-all!
  "Stop callback admission, cancel active and draining lanes, and do not join workers."
  [owner]
  (stop! owner false))

(defn open!
  "Create a bounded broadcast owner. SessionFs and host reverse requests
  belong to their own dispatcher, not the event worker."
  [conn client-open?]
  (let [sessions (atom {})
        lost (a/chan)
        owner {:conn conn :client-open? client-open? :sessions sessions :lost lost
               :draining (volatile! {}) :waiting (volatile! [])
               :requests (lane 8 false "copilot-native-request")
               :forms (lane 4 true "copilot-native-form")
               :observers (pool 16 (SynchronousQueue.) "copilot-native-observer")
               :interrupts (interruption/owner (+ 8 4 16))}]
    ;; The protocol can change its state while holding the reader lock. Signal
    ;; the transition here; never acquire our admission lock from a watch.
    (a/take! lost (fn [_] (stop-requests! owner)) false)
    (add-watch (:state-atom conn) sessions
               (fn [_ _ prev next]
                 (when (and (connected? conn prev) (not (connected? conn next)))
                   (a/close! lost))))
    (when-not (connected? conn @(:state-atom conn))
      (stop! owner false)
      (.shutdown ^ThreadPoolExecutor (:interrupts owner))
      (throw (ex-info "Callback connection is closed" {:type :connection-closed})))
    owner))

(defn- tool-handler [{:keys [name parameters handler overrides-built-in-tool?]}]
  (if (and handler (= name "apply_patch") overrides-built-in-tool?
           (= "string" (get parameters :type (get parameters "type"))))
    (fn [args invocation]
      (let [input (if (map? args) (get args :input (get args "input")) args)]
        (when-not (string? input) (throw (ex-info "Expected a string apply_patch input" {})))
        (handler input invocation)))
    handler))

(defn- observer-lane [handler]
  (when handler {:handler handler :queue (a/chan 64) :pending (atom #{})
                 :task (atom nil) :worker (volatile! nil) :interrupt (volatile! nil)
                 ;; Overflow settles receipts but can still notify; cancellation revokes that call.
                 :terminal (atom nil) :cancelled? (volatile! false)}))

(def ^:private ^:dynamic *observer-delivery* nil)

(defn current-observer-receipt [token]
  (when (identical? token (first *observer-delivery*)) (second *observer-delivery*)))

(defn- start-observer! [{:keys [conn sessions observers draining interrupts]} {:keys [id token observer]}]
  (when-let [{:keys [handler queue pending terminal cancelled? task worker]} observer]
    (let [notify! (fn [event]
                    (try (handler event) {:ok nil}
                         (catch Throwable t
                           (when (fatal? t) (throw t))
                           (log/warn "Event observer failed" {:session-id id})
                           {:error (ex-info "Event observer failed" {:type :observer-failed})})))
          run (fn []
                (vreset! worker (Thread/currentThread))
                (try
                  (loop []
                    (if-let [[receipt item] (a/<!! queue)]
                      (do
                        (when-not @terminal
                          (if-not receipt
                            ;; A retained batch predates all live turns and owns one queue slot.
                            (doseq [event item :while (not @terminal)]
                              (let [active (a/chan)]
                                (try (binding [*observer-delivery* [token active]] (notify! event))
                                     (finally (a/close! active)))))
                            (let [outcome (binding [*observer-delivery* [token receipt]] (notify! item))]
                              (locking sessions
                                (when (contains? @pending receipt)
                                  (swap! pending disj receipt)
                                  (settle! receipt outcome))))))
                        (recur))
                      (when (and (not @cancelled?)
                                 (= :observer-overflow (-> @terminal :error ex-data :type)))
                        (notify! @terminal))))
                  (catch InterruptedException _ nil)
                  (finally
                    (locking sessions
                      (vreset! worker nil)
                      (vswap! draining dissoc token)
                      (finish-observer! observer (ex-info "Session event observer stopped" {:type :observer-stopped}))))))
          t (interruption/worker-task #(scope/invoke conn token :observer run))
          interrupt (interruption/task interrupts t)]
      (reset! task t)
      (vreset! (:interrupt observer) interrupt)
      (try (.execute ^ThreadPoolExecutor observers ^Runnable #(try (.run t) (finally (interrupt :finish))))
           (catch RejectedExecutionException _
             (throw (ex-info "No observer workers available" {:type :observer-capacity})))))))

(def ^:private decision-actions
  {"abort" [:abort]
   "external_tool.requested" [:request :tool]       "external_tool.completed" [:settle :tool]
   "permission.requested" [:request :permission]    "permission.completed" [:settle :permission]
   "command.execute" [:request :command]            "command.completed" [:settle :command]
   "elicitation.requested" [:request :elicitation]  "elicitation.completed" [:settle :elicitation]
   "mcp.oauth_required" [:request :oauth]           "mcp.oauth_completed" [:settle :oauth]})

(defn- decision-action [{:keys [type data checkpoint]}]
  ;; The reader sees wire strings; the router and held-event replay see idiom keywords.
  (when-let [[action kind :as decision] (get decision-actions (if (keyword? type) (name type) type))]
    (if (= action :abort)
      (when-not (true? checkpoint) decision)
      (let [id (:request-id data)]
        (when (and (string? id) (not (str/blank? id)))
          (cond
            (and (= kind :permission) (:resolved-by-hook data)) [:settle :permission]
            (or (= action :settle) (not (true? checkpoint))) decision))))))

(defn track-decision! [{:keys [decision-fence]} event]
  ;; The reader and local storage marker share the notification admission lock.
  ;; Router order guarantees that the latest receipt covers all earlier ones.
  (when (decision-action event)
    (vreset! decision-fence (a/chan))))

(declare ^:private dispatch!)

(defn register!
  "Admit callbacks before session.create/resume. The caller must first validate
  session options with the closed create/resume builder; it rejects unimplemented
  callback groups. Replay the same normalized held events in order.
  Keep the returned token, not just the session ID."
  [{:keys [sessions conn client-open? waiting] :as owner} id opts & [retired? on-failure held-events await-storage?]]
  (let [definitions (get-in opts [:tools :definitions])
        tools (into {} (map (juxt :name tool-handler)) definitions)
        registration {:id id :token (or retired? (volatile! false)) :closed (a/chan) :admission-closed (a/chan)
                      :failure (atom nil)
                      :failure-claimed? (volatile! false)
                      ;; Held requests are reconciled here, before their observations are queued.
                      :decision-fence (volatile! nil)}
        reg (assoc registration :conn conn :client-open? client-open? :jobs (atom {}) :claims (atom {}) :tools tools
                   :root-aborted? (volatile! false)
                   :awaiting-storage (volatile! (when await-storage? []))
                   :waiting waiting
                   :on-failure on-failure :busy-replies (atom 0)
                   :permission (get-in opts [:permissions :on-request])
                   :elicitation (get-in opts [:interaction :on-elicitation-request])
                   :oauth (get-in opts [:mcp :on-auth-request])
                   :commands (into {} (map (juxt :name :handler)) (get-in opts [:interaction :commands]))
                   :observer (observer-lane (get-in opts [:events :on-event]))
                   :managed? (or (true? (get-in opts [:github :fetch-managed-settings?]))
                                 (contains? (:policy opts) :managed-settings)))]
    (when-not (= (count definitions) (count tools))
      (throw (ex-info "Tool names must be unique" {:type :duplicate-tool})))
    ;; Establish authority in wire order before exposing this registration.
    ;; No executor submission or application callback is allowed in this pass.
    (doseq [event held-events] (dispatch! nil reg event))
    (locking sessions
      (when-not (and @sessions (connected? conn @(:state-atom conn)))
        (throw (ex-info "Callback owner is closed" {:type :connection-closed})))
      (when (contains? @sessions id)
        (throw (ex-info "Session already owns callbacks" {:type :session-already-open})))
      (start-observer! owner reg)
      (swap! sessions assoc id reg))
    registration))

(defn unroute-released! [{:keys [sessions]} {:keys [id token] :as registration}]
  ;; Keep observations accepted for this generation, not an ID route that
  ;; can admit decisions. Its release caller still owns the eventual drain.
  (locking sessions
    (when (registered? sessions registration)
      (swap! sessions #(-> % (assoc token (get % id)) (dissoc id))))))

(defn- publication-key [sessions {:keys [id token] :as registration}]
  (if (registered? sessions registration) id token))

(defn drain! [{:keys [sessions] :as owner} registration]
  (locking sessions
    (let [key (publication-key sessions registration)]
      (when-let [{:keys [observer] :as reg} (get @sessions key)]
        (swap! sessions dissoc key)
        (cancel-requests! reg)
        (drain-observer! owner (cond-> reg
                                 (and observer (observer-thread? observer))
                                 (assoc-in [:observer :self-release] :draining)))))))

(defn- close-observer! [draining token lane]
  (when lane
    ;; Self-close stops delivery, but an independent close still owns
    ;; cancellation until the running callback exits.
    (if (observer-thread? lane)
      (vswap! draining assoc token (assoc lane :self-release :closed))
      (vswap! draining dissoc token))
    (cancel-observer! lane (ex-info "Session observer closed" {:type :session-closed}))))

(defn close-retired-observer! [{:keys [sessions draining conn]} {:keys [token] :as registration}]
  (await-observer-interrupt!
   (locking sessions
     (when-let [lane (or (get @draining token)
                         (:observer (get @sessions (publication-key sessions registration))))]
       ;; An ordinary acknowledged detach still owes already accepted events.
       (when (or (:self-release lane) (not (connected? conn @(:state-atom conn))))
         (close-observer! draining token lane)))))
  nil)

(defn unregister! [{:keys [sessions draining]} {:keys [token] :as registration}]
  (await-observer-interrupt!
   (locking sessions
     (let [key (publication-key sessions registration)
           reg (get @sessions key)]
       (when reg
         (swap! sessions dissoc key)
         (cancel-requests! reg))
       (close-observer! draining token (or (:observer reg) (get @draining token))))))
  nil)

(defn close!
  "Fence the owner and interrupt its work. An uncooperative handler cannot
  delay teardown indefinitely. Report remaining workers without user data."
  [{:keys [requests forms observers ^ThreadPoolExecutor interrupts] :as owner}]
  (cancel-all! owner)
  (.shutdown interrupts)
  (doseq [^ThreadPoolExecutor executor [(:executor requests) (:executor forms) observers interrupts]]
    (when-not (.awaitTermination executor shutdown-ms TimeUnit/MILLISECONDS)
      (log/warn "Callback workers did not stop before the shutdown deadline")))
  nil)

(defn- tool-result [value]
  (cond
    (nil? value) ""
    (string? value) value
    (and (map? value) (string? (:text-result-for-llm value))
         (s/valid? ::specs/result-type (:result-type value)))
    (results/structured-tool-result value)
    :else (json/write-str (util/opaque-json->wire value))))

(def ^:private response-method
  {:tool "session.tools.handlePendingToolCall"
   :permission "session.permissions.handlePendingPermissionRequest"
   :elicitation "session.ui.handlePendingElicitation"
   :command "session.commands.handlePendingCommand"
   :oauth "session.mcp.oauth.handlePendingRequest"})

(def ^:private terminal-response
  {:permission {:result {:kind "user-not-available"}}
   :elicitation {:result {:action "cancel"}}
   :oauth {:result {:kind "cancelled"}}})

(defn- failed-response [kind cause]
  (case kind
    :tool {:error (str "Tool callback " cause)}
    :command {:error (str "Command callback " cause)}
    (terminal-response kind)))

(defn- await-rpc! [conn cancel method ch]
  (try
    (let [[response port] (a/alts!! [cancel ch (a/timeout 10000)] :priority true)]
      (cond
        (= cancel port) (throw (ex-info "Callback is no longer active" {:type :callback-cancelled}))
        (= ch port) (rpc/response-result! method response)
        :else (throw (ex-info "Callback RPC timed out" {:method method}))))
    (finally (rpc/cancel-request! conn ch))))

(defn- send-session-request [{:keys [conn sessions] :as owner} reg method params check! & [with-admission]]
  (rpc/send-request conn method (assoc params :session-id (:id reg))
                    (cond-> {:callback-registry sessions
                             :check-admission! #(do (require-decision! owner reg) (check!))}
                      with-admission (assoc :with-admission with-admission))))

(defn- request-rpc!
  "Serialize submission, not response waiting, with unregister. The worker
  owns its pending RPC until response, cancellation or deadline."
  [{:keys [conn] :as owner} reg cancel method params & [key retry?]]
  (let [eligible? #(let [status (when key (first (get @(:claims reg) key)))]
                     (and (not (ap/closed? cancel))
                          (or (nil? key) (= status :automatic) (and (not retry?) (= status :checkpointed)))))
        check! #(when-not (eligible?) (throw (ex-info "Callback is no longer active" {:type :callback-cancelled})))
        ch (send-session-request owner reg method params check! (when key (partial after-active-decisions!! owner reg eligible?)))]
    (await-rpc! conn cancel method ch)))

(defn- await-value [value cancel]
  (let [v (if (satisfies? ap/ReadPort value)
            (let [[result port] (a/alts!! [cancel value] :priority true)]
              (if (= port value) result
                  (throw (ex-info "Callback cancelled" {:type :callback-cancelled}))))
            value)]
    (if (instance? Throwable v) (throw v) v)))

(defn- invalid-params? [response]
  ;; Other server failures may occur after the result was applied.
  (= -32602 (get-in response [:error :code])))

(defn- permit-manual! [owner reg cancel key]
  ;; Each registration owns its claims. A concurrent completion or abort
  ;; retires the same key and must win over a late rejection.
  (let [[before after]
        (swap-vals! (:claims reg)
                    (fn [state]
                      (let [[status agent-id] (get state key)]
                        (if (and (= :automatic status) (nil? (decision-refusal owner reg)) (not (ap/closed? cancel)))
                          (assoc state key [:manual agent-id])
                          state))))]
    (not (identical? before after))))

(defn- deliver-response! [owner reg cancel [kind request-id :as key] body]
  (let [terminal (if (= :tool kind)
                   (if (contains? body :error) body {:error "Runtime rejected the tool result"})
                   (terminal-response kind))]
    (letfn [(deliver! [reply retry?]
              (try
                (let [ack (request-rpc! owner reg cancel (response-method kind)
                                        (assoc reply :request-id request-id) key retry?)]
                  (when (and (not (ap/closed? cancel)) (not (true? (:success ack))))
                    (log/warn "Runtime did not accept callback reply" {:kind kind})))
                (catch clojure.lang.ExceptionInfo e
                  (cond
                    (not (and terminal (invalid-params? (ex-data e)))) (throw e)
                    (not= reply terminal) (deliver! terminal true)
                    (#{:oauth :elicitation} kind)
                    (log/warn "Runtime rejected callback cancellation"
                              {:kind kind :session-id (:id reg) :request-id request-id})
                    (permit-manual! owner reg cancel key)
                    (log/warn "Runtime rejected terminal callback reply; manual response required"
                              {:kind kind :session-id (:id reg) :request-id request-id})))))]
      (deliver! body false))))

(defn- begin-invocation! [owner reg cancel key]
  (after-active-decisions!!
   owner reg #(= :queued (first (get @(:claims reg) key)))
   #(locking (:sessions owner)
      (let [[status agent] (get @(:claims reg) key)]
        (when (and (= status :queued) (not (ap/closed? cancel)) (nil? (decision-refusal owner reg)))
          (swap! (:claims reg) assoc key [:automatic agent])
          true)))))

(defn- invoke-request! [{:keys [lost client-open?] :as owner} {:keys [token] :as reg} kind event handler cancel]
  (let [{:keys [data agent-id]} event
        {:keys [request-id permission-request arguments tool-name]} data
        key [kind request-id]
        cancelled? #(or (ap/closed? cancel) (ap/closed? lost) @token (not (client-open?)))
        ctx {:session-id (:id reg) :cancelled? cancelled?}
        metadata (when (and (= kind :tool) (= tool-name "tool_search_tool"))
                   (try
                     (:tools (request-rpc! owner reg cancel "session.tools.getCurrentMetadata" {}))
                     (catch Exception _
                       (when-not (cancelled?) (log/warn "Tool search metadata unavailable"))
                       nil)))
        started? (begin-invocation! owner reg cancel key)
        body (when started?
               (try
                 (case kind
                   :tool
                   (let [ctx (cond-> (assoc (select-keys data [:tool-call-id :tool-name :traceparent :tracestate])
                                            :arguments arguments :session-id (:id reg) :request-id request-id
                                            :cancelled? cancelled?)
                               agent-id (assoc :agent-id agent-id)
                               metadata (assoc :available-tools metadata))]
                     (when-not (cancelled?)
                       {:result (tool-result (await-value (handler arguments ctx) cancel))}))
                   :permission
                   (let [ctx (merge ctx {:managed-settings-enabled? (:managed? reg)}
                                    (select-keys data [:request-id :prompt-request :assisted-approval-assessment :program-tool-call-id]))]
                     (results/permission-response (await-value (handler permission-request ctx) cancel)))
                   :elicitation
                   (let [ctx (cond-> (merge ctx (select-keys data [:request-id :message :requested-schema :mode
                                                                   :elicitation-source :url]))
                               (contains? data :meta) (assoc :_meta (results/elicitation-metadata (:meta data))))]
                     (results/elicitation-response ctx (await-value (handler ctx) cancel)))
                   :oauth (results/oauth-response data (await-value (handler data ctx) cancel))
                   :command
                   (if handler
                     (do (await-value (handler (merge ctx (select-keys data [:command-name :command :args]))) cancel)
                         {})
                     {:error (str "Unknown command: " (:command-name data))}))
                 (catch Throwable t
                   (when (fatal? t) (throw t))
                   (when-not (ap/closed? cancel)
                     (log/warn "Application callback failed" {:kind kind})
                     (failed-response kind "failed")))))]
    (if body
      (deliver-response! owner reg cancel key body)
      (when (and started? (= :permission kind))
        (permit-manual! owner reg cancel key)))))

(declare pump-lane!)

(def ^:private ^:dynamic *form-handler* nil)

(defn nested-form? [owner]
  (and *form-handler* (identical? (:forms owner) (:pool *form-handler*))
       @(:active? *form-handler*)))

(defn- run-job! [{:keys [sessions] :as owner} {:keys [active] :as lane} {:keys [reg k cancel ^FutureTask task] :as job}]
  (try
    (scope/invoke (:conn owner) (:token reg) :decision #(.run task))
    (catch Error fatal
      (locking sessions
        (fail-registration! reg (ex-info "Session callback worker failed" {:type :callback-worker-failed})))
      (throw fatal))
    ;; Cancellation is not worker release: an interrupted application may
    ;; keep running. Only release capacity when its actual invocation exits.
    (finally
      (locking sessions
        ((:interrupt job) :finish)
        (a/close! cancel)
        (swap! (:jobs reg) dissoc k)
        (vswap! active disj job)
        (pump-lane! owner lane)))))

(defn- start-job! [owner {:keys [active ^ThreadPoolExecutor executor] :as lane} job]
  (let [job (assoc job :interrupt (interruption/task (:interrupts owner) (:task job)))]
    (swap! (:jobs (:reg job)) assoc (:k job) job)
    (vswap! active conj job)
    (.execute executor ^Runnable #(run-job! owner lane job))))

(defn- session-slot? [active token]
  (< (count (filter #(identical? token (:token %)) active)) 2))

(defn- pump-lane! [{:keys [waiting] :as owner} {:keys [active limit form?] :as lane}]
  ;; Admissions and exits hold the same lock. Hand off the oldest eligible
  ;; job without relying on a departing pool thread already being idle.
  (when (and (< (count @active) limit) (seq @waiting))
    (when-let [job (some #(when (and (= (:form? %) form?) (session-slot? @active (:token %))) %) @waiting)]
      (vswap! waiting #(filterv (fn [x] (not (identical? job x))) %))
      (start-job! owner lane job)
      (recur owner lane))))

(def ^:private session-waiting-limit 16)

(defn- submit! [{:keys [waiting requests forms] :as owner} {:keys [jobs token] :as reg} k work]
  (let [form? (= :elicitation (first k))
        lane (if form? forms requests)
        active @(:active lane)
        immediate? (and (< (count active) (:limit lane)) (session-slot? active token))]
    (when (or immediate? (and (< (count @waiting) 128)
                              (< (count (filter #(identical? token (:token %)) @waiting)) session-waiting-limit)))
      (let [cancel (a/chan)
            task (interruption/worker-task
                  (fn []
                    (try
                      (when (and (nil? (decision-refusal owner reg)) (not (ap/closed? cancel)))
                        (if form?
                          (let [active? (volatile! true)]
                            (binding [*form-handler* {:pool (:forms owner) :active? active?}]
                              (try (work cancel) (finally (vreset! active? false)))))
                          (work cancel)))
                      (catch Exception _
                        (when-not (ap/closed? cancel)
                          (log/warn "Callback dispatch failed"))))))
            job {:token token :reg reg :k k :task task :cancel cancel :form? form?}]
        (swap! jobs assoc k job)
        (if immediate?
          (start-job! owner lane job)
          (vswap! waiting conj job))
        true))))

(def ^:private claim-limit 65536)

(defn- fail-session! [reg message cause-type]
  (let [cause (ex-info message {:type cause-type :session-id (:id reg)})]
    (if-let [failure (fail-registration! reg cause)]
      (do (log/warn message (ex-data cause)) (throw failure))
      (throw (ex-info "Session is no longer active" {:type :session-closed :session-id (:id reg)})))))

(defn- claim! [{:keys [claims] :as reg} k status]
  ;; Evicting an old request ID would let a replay invoke a handler twice.
  (when (and (>= (count @claims) claim-limit) (not (contains? @claims k)))
    (fail-session! reg "Session callback request history full" :callback-claim-limit))
  (swap! claims assoc k status))

(defn- retire! [reg key]
  (let [[status _ retired] (get @(:claims reg) key)]
    ;; Only an unsent refusal returns capacity here. An admitted RPC owns
    ;; its slot through its own response, closure or deadline.
    (when (= status :busy)
      (swap! (:busy-replies reg) dec)
      (a/close! retired)))
  (claim! reg key [:retired])
  (when-let [job (get @(:jobs reg) key)]
    (let [waiting (:waiting reg)]
      (when (some #(identical? job %) @waiting)
        (vswap! waiting #(filterv (fn [x] (not (identical? job x))) %))
        (swap! (:jobs reg) dissoc key)))
    (cancel! job))
  nil)

(defn- reserve-busy-reply! [{:keys [conn sessions] :as owner} {:keys [busy-replies closed claims] :as reg}
                            [kind request-id :as key] agent]
  ;; No data or code from an application is needed to refuse an excess
  ;; request. Park on its decision receipt without blocking the event router.
  (when (>= @busy-replies 16)
    (fail-session! reg "Session callback replies saturated" :callback-overflow))
  (let [retired (a/chan)
        reservation [:busy agent retired]
        offered? (volatile! false)
        method (response-method kind)
        params (assoc (failed-response kind "busy") :request-id request-id)
        submit! (fn []
                  (let [next-receipt (volatile! nil)
                        ch (send-session-request
                            owner reg method params
                            #(when-not (identical? reservation (get @claims key))
                               (throw (ex-info "Callback request is no longer pending" {:type :not-pending})))
                            (fn [offer!]
                              (let [[failure pending]
                                    (try-after-decisions! conn reg
                                                          #(locking sessions
                                                             (let [failure (offer!)]
                                                               (when-not failure
                                                                 (claim! reg key [:automatic agent])
                                                                 (vreset! offered? true))
                                                               failure)))]
                                (vreset! next-receipt pending)
                                failure)))]
                    ;; An intervening decision left this attempt unregistered.
                    (if-let [pending @next-receipt] (do (a/close! ch) [nil pending]) [ch])))]
    (claim! reg key reservation)
    (swap! busy-replies inc)
    (fn []
      (a/go
        (try
          (let [deadline (a/timeout 10000)]
            (loop []
              (let [[ch receipt] (submit!)]
                (if receipt
                  (let [[_ port] (a/alts! [closed retired deadline receipt] :priority true)]
                    (cond
                      (= port receipt) (recur)
                      (= port deadline) (fail-active-decision! owner reg #(identical? reservation (get @claims key))
                                                               (admission-timeout (:id reg)))))
                  (try
                    (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
                      (when (and (= port ch) (#{:permission :tool} kind) (invalid-params? response))
                        (permit-manual! owner reg closed key))
                      (when (and (not= port closed) (not (true? (get-in response [:result :success]))))
                        (log/warn "Runtime did not accept busy callback reply" {:kind kind :session-id (:id reg)})))
                    (finally (rpc/cancel-request! conn ch)))))))
          (catch Exception e
            (when-not (#{:session-closed :not-pending} (:type (ex-data e)))
              (fail-active-decision! owner reg #(identical? reservation (get @claims key)) e)))
          (finally
            (locking sessions
              (if @offered?
                (swap! busy-replies dec)
                (when (identical? reservation (get @claims key))
                  (retire! reg key))))))))))

(defn- start-request! [owner reg kind event key handler]
  (let [agent (:agent-id event)]
    (claim! reg key [:queued agent])
    (when-not (submit! owner reg key #(invoke-request! owner reg kind event handler %))
      (reserve-busy-reply! owner reg key agent))))

(defn- admit! [owner reg kind event key]
  (let [{:keys [agent-id data]} event
        {:keys [tool-name tool-call-id command-name permission-request]} data]
    (cond
      (contains? @(:claims reg) key) nil
      (and (= kind :tool) (or (not (string? tool-call-id))
                              (and (contains? data :session-id) (not= (:id reg) (:session-id data))))) nil
      (and (= kind :permission) (not (map? permission-request))) nil
      :else
      (let [handler (case kind
                      :tool (when-not (false? (:has-handler data)) (get (:tools reg) tool-name))
                      :command (get (:commands reg) command-name)
                      (get reg kind))
            automatic? (or handler (= kind :command))]
        (cond
          (and owner automatic?) (start-request! owner reg kind event key handler)
          automatic? (do (claim! reg key [:held agent-id event handler]) nil)
          (#{:tool :permission} kind) (do (claim! reg key [:manual agent-id]) nil))))))

(defn- dispatch! [owner reg {:keys [data agent-id checkpoint] :as event}]
  (when-let [[action kind] (decision-action event)]
    (case action
      :abort (let [root? (str/blank? agent-id)]
               (when root? (vreset! (:root-aborted? reg) true))
               (doseq [[key [status agent]] @(:claims reg)
                       :when (and (not= :retired status) (or root? (= agent agent-id)))]
                 (retire! reg key)))
      :request (admit! owner reg kind event [kind (:request-id data)])
      :settle (let [key [kind (:request-id data)]
                    [status agent] (get @(:claims reg) key)]
                ;; A checkpoint preserves an invocation in progress, not its right to retry.
                (cond
                  (not (true? checkpoint)) (retire! reg key)
                  (= status :checkpointed) nil
                  (#{:automatic :responding} status) (do (claim! reg key [:checkpointed agent]) nil)
                  :else (retire! reg key))))))

(defn- manual-body [kind opts]
  (let [{:keys [result error request-id]} opts
        ks (set (keys opts))]
    (when-not (and (string? request-id) (not (str/blank? request-id)))
      (throw (ex-info "A nonblank :request-id is required" {:type :invalid-request-id})))
    (case kind
      :tool (cond
              (= ks #{:request-id :result}) {:result (tool-result result)}
              (and (= ks #{:request-id :error}) (string? error) (not (empty? error))) {:error error}
              :else (throw (ex-info "Supply a tool result or a nonempty error" {})))
      :permission
      (do (when-not (and (#{#{:request-id :result} #{:request-id :result :decision-context}} ks)
                         (not= :attributed (:kind result)))
            (throw (ex-info "Supply a permission result and optional :decision-context" {})))
          (or (results/permission-response
               (if (contains? opts :decision-context)
                 {:kind :attributed :result result :decision-context (:decision-context opts)}
                 result))
              (throw (ex-info "Cannot resolve a pending request with :no-result" {})))))))

(defn respond!
  "Return a native result channel for a pending :tool or :permission decision
  by ID. Restored pending requests need not have been re-emitted.
  Reject locally known automatic, duplicate and completed decisions."
  [{:keys [sessions conn] :as owner} {:keys [closed] :as registration} kind opts]
  (try
    (let [id (:id registration)
          key [kind (:request-id opts)]
          method (response-method kind)
          reg (locking sessions (require-decision! owner registration) (get @sessions id))
          claims (:claims reg)
          body (manual-body kind opts)
          ch (rpc/send-request conn method (assoc body :request-id (:request-id opts) :session-id id)
                               {:with-admission
                                (fn [offer!]
                                  (after-decisions!!
                                   conn registration
                                   #(locking sessions
                                      (require-decision! owner registration)
                                      (let [[status agent] (get @claims key)]
                                        (when-not (or (= :manual status) (and (nil? status) (not @(:root-aborted? reg))))
                                          (throw (ex-info "Callback request is not pending" {:type :not-pending})))
                                        (claim! reg key [:responding agent])
                                        (offer!)))))
                                :callback-registry sessions :check-admission! #(require-decision! owner registration)})]
      (a/go
        (let [outcome (try
                        (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
                          (cond
                            (= port ch) (let [ack (rpc/response-result! method response)]
                                          (when-not (true? (:success ack))
                                            (throw (ex-info "Runtime did not accept the pending request response"
                                                            {:type :request-not-handled :method method})))
                                          {:ok ack})
                            (= port closed) {:error (or @(:failure registration)
                                                        (ex-info "Session is no longer active" {:type :session-closed}))}
                            :else {:error (ex-info "Callback RPC timed out" {:method method})}))
                        (catch Exception e {:error e})
                        (finally (rpc/cancel-request! conn ch)))]
          ;; A completion or abort can retire this attempt before its reply arrives.
          ;; A replacement session has a different claims atom.
          (swap! claims (fn [state]
                          (let [[status agent] (get state key)]
                            (if (= :responding status)
                              (assoc state key (if (invalid-params? (ex-data (:error outcome)))
                                                 [:manual agent] [:retired]))
                              state))))
          outcome)))
    (catch Exception e (a/go {:error e}))))

(defn- observe! [reg {:keys [queue pending terminal] :as lane} event]
  (if-let [outcome @terminal]
    (settle! (a/promise-chan) outcome)
    (let [receipt (a/promise-chan)]
      (swap! pending conj receipt)
      (when-not (a/offer! queue [receipt event])
        (let [cause (ex-info "Session event observer overflow"
                             {:type :observer-overflow :session-id (:id reg) :event-id (:id event)})]
          (log/warn "Disabling slow session event observer" (ex-data cause))
          (finish-observer! lane cause)))
      receipt)))

(defn- request-kind [event]
  (let [[action kind] (decision-action event)]
    (when (= action :request) kind)))

(defn- start-held! [owner reg event]
  ;; Its aborts, completions and manual claims have already taken effect.
  ;; Only the original request may consume a surviving deferred start.
  (when-let [kind (request-kind event)]
    (let [key [kind (get-in event [:data :request-id])]
          [status _ original handler] (get @(:claims reg) key)]
      (when (and (= status :held) (identical? original event))
        (start-request! owner reg kind event key handler)))))

(defn- hold-until-storage! [reg event]
  (when-let [kind (request-kind event)]
    (let [key [kind (get-in event [:data :request-id])]
          [status _ original] (get @(:claims reg) key)
          waiting (:awaiting-storage reg)]
      (when (and (= status :held) (identical? original event))
        ;; Retired claims no longer retain their events. Compact their keys
        ;; before applying the queue limit to requests that still need work.
        (when (>= (count @waiting) session-waiting-limit)
          (vswap! waiting #(filterv (fn [k] (= :held (first (get @(:claims reg) k)))) %))
          (when (>= (count @waiting) session-waiting-limit)
            (fail-session! reg "Too many pending decisions during storage setup" :callback-overflow)))
        (vswap! waiting conj key))))
  nil)

(defn- dispatch-observed! [owner reg event held?]
  (let [reason (decision-refusal owner reg)]
    (cond (nil? reason)
          (try (cond (some? @(:awaiting-storage reg)) (do (when-not held? (dispatch! nil reg event))
                                                          (hold-until-storage! reg event))
                     held? (start-held! owner reg event)
                     :else (dispatch! owner reg event))
               (catch clojure.lang.ExceptionInfo e
                 ;; Release can win during claim accounting. Stop the decision,
                 ;; not publication of the event the reader already accepted.
                 (when-not (= :session-closed (:type (ex-data e))) (throw e))))
          (not= reason ::retired) (throw reason))))

(defn storage-ready! [{:keys [sessions] :as owner} registration]
  (let [busy (locking sessions
               (when (registered? sessions registration)
                 (let [reg (get @sessions (:id registration))
                       waiting (:awaiting-storage reg)
                       keys @waiting]
                   (vreset! waiting nil)
                   (when-not (decision-refusal owner reg)
                     (into [] (keep (fn [[kind :as key]]
                                      (let [[status _ event handler] (get @(:claims reg) key)]
                                        (when (= :held status) (start-request! owner reg kind event key handler)))))
                           keys)))))]
    (doseq [send-busy! busy] (send-busy!))))

(defn publish-held!
  "Publish one pre-registration batch. Later subscriptions and turn waiters
  did not exist when these events arrived. Only the open-time observer gets it."
  [{:keys [sessions] :as owner} registration events]
  (doseq [event events]
    (when-let [busy! (locking sessions
                       (when (registered? sessions registration)
                         (dispatch-observed! owner (get @sessions (:id registration)) event true)))]
      (busy!)))
  (locking sessions
    (when-let [lane (:observer (get @sessions (publication-key sessions registration)))]
      (when-not (or @(:terminal lane) (a/offer! (:queue lane) [nil events]))
        (throw (ex-info "Cannot deliver initial session observations" {:type :observer-overflow}))))))

(defn publish!
  "Return [observer-receipt] for an admitted live event, or nil if its owner
  stopped publication. A nil receipt means there is no observer. Otherwise
  it settles after the handler returns, or on overflow, failure or teardown."
  [{:keys [sessions client-open?] :as owner} registration event]
  (when-let [[send-busy! receipt]
             (locking sessions
               (when (client-open?)
                 (when-let [reg (get @sessions (publication-key sessions registration))]
                   [(dispatch-observed! owner reg event false)
                    (when-let [lane (:observer reg)] (observe! reg lane event))])))]
    (when send-busy! (send-busy!))
    [receipt]))
