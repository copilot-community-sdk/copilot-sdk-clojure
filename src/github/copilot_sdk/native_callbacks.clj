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
  :accept/:decline/:cancel} with optional :content and :decision-context.
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
  Each buffers 64 events and ignores handler return values (including async
  results), as Node does. Overflow stops that observer only. Once an active
  handler returns it receives a terminal {:error ex} instead of more events.
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
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import [java.util.concurrent ArrayBlockingQueue Callable CancellationException ExecutionException FutureTask SynchronousQueue
            RejectedExecutionException ThreadFactory ThreadPoolExecutor TimeUnit TimeoutException]))

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
  (let [live (get @sessions (:id reg))]
    (if-not (and live (identical? (:token live) (:token reg)))
      ::retired
      (or @(:failure live)
          (when-not (and (client-open?) (not @(:token live)) (not (ap/closed? (:closed live)))
                         (connected? conn @(:state-atom conn)))
            ::retired)))))

(defn- require-decision! [owner reg]
  (when-let [reason (decision-refusal owner reg)]
    (throw (if (= reason ::retired)
             (ex-info "Session is no longer active" {:type :session-closed})
             reason))))

(defn- fatal? [t] (and (instance? Error t) (not (instance? AssertionError t))))

(defn- worker-task [f]
  ;; FutureTask captures Throwable. Do not turn a fatal VM error into an
  ;; invisible, unobserved future just because cancellation needs a handle.
  (proxy [FutureTask] [(cast Callable f)]
    (done []
      (when-not (.isCancelled ^FutureTask this)
        (try (.get ^FutureTask this)
             (catch ExecutionException e (throw (.getCause e))))))))

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

(defn retire-forms! [conn id token]
  (when-let [forms (rpc/withdraw-requests! conn token)]
    (let [cause (ex-info "Session closed" {:type :session-closed :session-id id})]
      (doseq [reply forms] (settle! reply cause)))))

(defn- cancel-requests! [{:keys [conn id token closed failure] :as reg}]
  (when-not (ap/closed? closed)
    (when-not @failure (retire-forms! conn id token))
    (a/close! closed))
  (vswap! (:waiting reg) #(filterv (fn [job] (not (identical? token (:token job)))) %))
  (doseq [job (vals (first (reset-vals! (:jobs reg) {})))]
    (cancel! job)))

(defn- observer-thread? [lane]
  (identical? (Thread/currentThread) @(:worker lane)))

(defn- cancel-observer! [lane cause]
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

(defn- fail-registration! [{:keys [failure on-failure] :as reg} cause]
  ;; All callers hold the registry. Fence ordinary reverse work before the
  ;; terminal cause is observable, including from a public handle on another thread.
  (when-not @failure
    (let [forms (rpc/withdraw-requests! (:conn reg) (:token reg))]
      (when on-failure (on-failure cause))
      (reset! failure cause)
      (doseq [reply forms] (settle! reply cause))
      (release! reg)))
  @failure)

(defn fail!
  "Fail only the captured generation when publication cannot continue."
  [{:keys [sessions]} registration cause]
  (locking sessions
    (when (registered? sessions registration)
      (fail-registration! (get @sessions (:id registration)) cause))))

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
  (when (and observer (not (.isDone ^FutureTask @(:task observer))))
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
                 :task (atom nil) :worker (volatile! nil) :interrupt (volatile! nil) :terminal (atom nil)}))

(def ^:private ^:dynamic *observer-delivery* nil)

(defn current-observer-receipt [token]
  (when (identical? token (first *observer-delivery*)) (second *observer-delivery*)))

(defn- start-observer! [{:keys [conn sessions observers draining interrupts]} {:keys [id token observer]}]
  (when-let [{:keys [handler queue pending terminal task worker]} observer]
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
                    (if-let [[receipt event] (a/<!! queue)]
                      (do
                        (when-not @terminal
                          (let [outcome (binding [*observer-delivery* [token receipt]] (notify! event))]
                            (locking sessions
                              (when (contains? @pending receipt)
                                (swap! pending disj receipt)
                                (settle! receipt outcome)))))
                        (recur))
                      (when (and (not (.isCancelled ^FutureTask @task))
                                 (= :observer-overflow (-> @terminal :error ex-data :type)))
                        (notify! @terminal))))
                  (catch InterruptedException _ nil)
                  (finally
                    (locking sessions
                      (vreset! worker nil)
                      (vswap! draining dissoc token)
                      (finish-observer! observer (ex-info "Session event observer stopped" {:type :observer-stopped}))))))
          t (worker-task #(scope/invoke conn token :observer run))
          interrupt (interruption/task interrupts t)]
      (reset! task t)
      (vreset! (:interrupt observer) interrupt)
      (try (.execute ^ThreadPoolExecutor observers ^Runnable #(try (.run t) (finally (interrupt :finish))))
           (catch RejectedExecutionException _
             (throw (ex-info "No observer workers available" {:type :observer-capacity})))))))

(defn register!
  "Admit callbacks before session.create/resume. The caller must first validate
  session options with the closed create/resume builder; it rejects unimplemented
  callback groups. Keep the returned token, not just the session ID."
  [{:keys [sessions conn waiting] :as owner} id opts & [retired? on-failure]]
  (let [definitions (get-in opts [:tools :definitions])
        tools (into {} (map (juxt :name tool-handler)) definitions)
        registration {:id id :token (or retired? (volatile! false)) :closed (a/chan) :failure (atom nil)}
        reg (assoc registration :conn conn :jobs (atom {}) :claims (atom {}) :tools tools
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
    (locking sessions
      (when-not (and @sessions (connected? conn @(:state-atom conn)))
        (throw (ex-info "Callback owner is closed" {:type :connection-closed})))
      (when (contains? @sessions id)
        (throw (ex-info "Session already owns callbacks" {:type :session-already-open})))
      (start-observer! owner reg)
      (swap! sessions assoc id reg))
    registration))

(defn drain! [{:keys [sessions] :as owner} {:keys [id] :as registration}]
  (locking sessions
    (when (registered? sessions registration)
      (let [{:keys [observer] :as reg} (get @sessions id)]
        (swap! sessions dissoc id)
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

(defn close-retired-observer! [{:keys [sessions draining conn]} {:keys [token]}]
  (await-observer-interrupt!
   (locking sessions
     (when-let [lane (get @draining token)]
       ;; An ordinary acknowledged detach still owes already accepted events.
       (when (or (:self-release lane) (not (connected? conn @(:state-atom conn))))
         (close-observer! draining token lane)))))
  nil)

(defn unregister! [{:keys [sessions draining]} {:keys [id token] :as registration}]
  (await-observer-interrupt!
   (locking sessions
     (let [reg (when (registered? sessions registration) (get @sessions id))]
       (when reg
         (swap! sessions dissoc id)
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

(defn- send-session-request [{:keys [conn sessions] :as owner} reg method params check!]
  (rpc/send-request conn method (assoc params :session-id (:id reg))
                    {:callback-registry sessions
                     :check-admission! #(do (require-decision! owner reg)
                                            (when check! (check!)))}))

(defn- request-rpc!
  "Serialize submission, not response waiting, with unregister. The worker
  owns its pending RPC until response, cancellation or deadline."
  [{:keys [conn] :as owner} reg cancel method params]
  (let [ch (send-session-request owner reg method params
                                 #(when (ap/closed? cancel)
                                    (throw (ex-info "Callback is no longer active" {:type :callback-cancelled}))))]
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
    (letfn [(deliver! [reply]
              (try
                (let [ack (request-rpc! owner reg cancel (response-method kind)
                                        (assoc reply :request-id request-id))]
                  (when (and (not (ap/closed? cancel)) (not (true? (:success ack))))
                    (log/warn "Runtime did not accept callback reply" {:kind kind})))
                (catch clojure.lang.ExceptionInfo e
                  (cond
                    (not (and terminal (invalid-params? (ex-data e)))) (throw e)
                    (not= reply terminal) (deliver! terminal)
                    (#{:oauth :elicitation} kind)
                    (log/warn "Runtime rejected callback cancellation"
                              {:kind kind :session-id (:id reg) :request-id request-id})
                    (permit-manual! owner reg cancel key)
                    (log/warn "Runtime rejected terminal callback reply; manual response required"
                              {:kind kind :session-id (:id reg) :request-id request-id})))))]
      (deliver! body))))

(defn- invoke-request! [{:keys [lost client-open?] :as owner} {:keys [token] :as reg} kind event handler cancel]
  (let [{:keys [data agent-id]} event
        {:keys [request-id permission-request arguments tool-name]} data
        cancelled? #(or (ap/closed? cancel) (ap/closed? lost) @token (not (client-open?)))
        ctx {:session-id (:id reg) :cancelled? cancelled?}
        body (try
               (case kind
                 :tool
                 (let [metadata (when (= tool-name "tool_search_tool")
                                  (try
                                    (:tools (request-rpc! owner reg cancel "session.tools.getCurrentMetadata" {}))
                                    (catch Exception _
                                      (log/warn "Tool search metadata unavailable")
                                      nil)))
                       ctx (cond-> (assoc (select-keys data [:tool-call-id :tool-name :traceparent :tracestate])
                                          :arguments arguments :session-id (:id reg) :request-id request-id
                                          :cancelled? cancelled?)
                             agent-id (assoc :agent-id agent-id)
                             metadata (assoc :available-tools metadata))]
                   (when-not (cancelled?)
                     {:result (tool-result (await-value (handler arguments ctx) cancel))}))
                 :permission
                 (let [ctx (merge ctx {:managed-settings-enabled? (:managed? reg)}
                                  (select-keys data [:prompt-request :assisted-approval-assessment :program-tool-call-id]))]
                   (results/permission-response (await-value (handler permission-request ctx) cancel)))
                 :elicitation
                 (results/elicitation-response
                  (await-value (handler (merge ctx (select-keys data [:message :requested-schema :mode
                                                                      :elicitation-source :url]))) cancel))
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
                   (failed-response kind "failed"))))]
    (if body
      (deliver-response! owner reg cancel [kind request-id] body)
      (when (= :permission kind)
        (permit-manual! owner reg cancel [kind request-id])))))

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

(defn- submit! [{:keys [waiting requests forms] :as owner} {:keys [jobs token] :as reg} k work]
  (let [form? (= :elicitation (first k))
        lane (if form? forms requests)
        active @(:active lane)
        immediate? (and (< (count active) (:limit lane)) (session-slot? active token))]
    (when (or immediate? (and (< (count @waiting) 128)
                              (< (count (filter #(identical? token (:token %)) @waiting)) 16)))
      (let [cancel (a/chan)
            task (worker-task (fn []
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

(def ^:private requested
  {:copilot/external_tool.requested :tool :copilot/permission.requested :permission
   :copilot/command.execute :command :copilot/elicitation.requested :elicitation
   :copilot/mcp.oauth_required :oauth})

(def ^:private completed
  {:copilot/external_tool.completed :tool :copilot/permission.completed :permission
   :copilot/command.completed :command :copilot/elicitation.completed :elicitation
   :copilot/mcp.oauth_completed :oauth})

(def ^:private claim-limit 65536)

(defn- fail-session! [reg message cause-type]
  (let [cause (ex-info message {:type cause-type :session-id (:id reg)})]
    (log/warn message (ex-data cause))
    (throw (fail-registration! reg cause))))

(defn- claim! [{:keys [claims] :as reg} k status]
  ;; Evicting an old request ID would let a replay invoke a handler twice.
  (when (and (>= (count @claims) claim-limit) (not (contains? @claims k)))
    (fail-session! reg "Session callback request history full" :callback-claim-limit))
  (swap! claims assoc k status))

(defn- retire! [reg key]
  (claim! reg key [:retired])
  (when-let [job (get @(:jobs reg) key)]
    (let [waiting (:waiting reg)]
      (when (some #(identical? job %) @waiting)
        (vswap! waiting #(filterv (fn [x] (not (identical? job x))) %))
        (swap! (:jobs reg) dissoc key)))
    (cancel! job))
  nil)

(defn- reserve-busy-reply! [{:keys [conn] :as owner} {:keys [busy-replies closed claims] :as reg} [kind request-id :as key]]
  ;; Reserve under the event registry; prepare and submit after publication
  ;; releases it, just as for an automatic or manual application decision.
  (when (>= @busy-replies 16)
    (fail-session! reg "Session callback replies saturated" :callback-overflow))
  (let [method (response-method kind)
        params (assoc (failed-response kind "busy") :request-id request-id)]
    (swap! busy-replies inc)
    (fn []
      (when-let [ch (try
                      (send-session-request owner reg method params
                                            #(when-not (= :automatic (first (get @claims key)))
                                               (throw (ex-info "Callback request is no longer pending" {:type :not-pending}))))
                      (catch Throwable t
                        (swap! busy-replies dec)
                        ;; A retired decision is not a publication error. A
                        ;; failed callback owner, however, retains its cause.
                        (when-not (= :session-closed (:type (ex-data t)))
                          (throw t))))]
        (a/go
          (try
            (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
              (when (and (= port ch) (#{:permission :tool} kind) (invalid-params? response))
                (permit-manual! owner reg closed key))
              (when (and (not= port closed) (not (true? (get-in response [:result :success]))))
                (log/warn "Runtime did not accept busy callback reply" {:kind kind :session-id (:id reg)})))
            (finally (rpc/cancel-request! conn ch) (swap! busy-replies dec))))))))

(defn- admit! [owner reg kind event key]
  (let [{:keys [agent-id data]} event
        {:keys [tool-name tool-call-id command-name permission-request resolved-by-hook]} data]
    (cond
      (and (= kind :permission) resolved-by-hook) (retire! reg key)
      (contains? @(:claims reg) key) nil
      (and (= kind :tool) (or (not (string? tool-call-id))
                              (and (contains? data :session-id) (not= (:id reg) (:session-id data))))) nil
      (and (= kind :permission) (not (map? permission-request))) nil
      :else
      (let [handler (case kind
                      :tool (when-not (false? (:has-handler data)) (get (:tools reg) tool-name))
                      :command (get (:commands reg) command-name)
                      (get reg kind))]
        (if (or handler (= kind :command))
          (do (claim! reg key [:automatic agent-id])
              (when-not (submit! owner reg key #(invoke-request! owner reg kind event handler %))
                (reserve-busy-reply! owner reg key)))
          (when (#{:tool :permission} kind)
            (claim! reg key [:manual agent-id])
            nil))))))

(defn- dispatch! [owner reg {:keys [type data agent-id] :as event}]
  (let [request-id (:request-id data)]
    (cond
      ;; A replay checkpoint is observable but cannot claim live work.
      (true? (:checkpoint event)) nil
      (= type :copilot/abort)
      (doseq [[key [status agent]] @(:claims reg)
              :when (and (not= :retired status) (or (str/blank? agent-id) (= agent agent-id)))]
        (retire! reg key))
      (and (string? request-id) (not (str/blank? request-id)))
      (if-let [kind (completed type)]
        (retire! reg [kind request-id])
        (when-let [kind (requested type)]
          (admit! owner reg kind event [kind request-id]))))))

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
  [{:keys [sessions conn] :as owner} registration kind opts]
  (try
    (let [body (manual-body kind opts)
          id (:id registration)
          key [kind (:request-id opts)]
          method (response-method kind)
          [{:keys [closed claims] :as reg} attempt]
          (locking sessions
            (require-decision! owner registration)
            (let [{:keys [claims] :as reg} (get @sessions id)
                  [status agent-id] (get @claims key)
                  attempt [:responding agent-id]]
              (when-not (or (nil? status) (= :manual status))
                (throw (ex-info "Callback request is not pending" {:type :not-pending})))
              (claim! reg key attempt)
              [reg attempt]))
          ch (send-session-request owner reg method (assoc body :request-id (:request-id opts))
                                   #(when-not (identical? attempt (get @claims key))
                                      (throw (ex-info "Callback request is no longer pending" {:type :not-pending}))))]
      (a/go
        (let [outcome (try
                        (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
                          (cond
                            (= port ch) (let [ack (rpc/response-result! method response)]
                                          (when-not (true? (:success ack))
                                            (throw (ex-info "Runtime did not accept the pending request response"
                                                            {:type :request-not-handled :method method})))
                                          {:ok ack})
                            (= port closed) {:error (or @(:failure reg)
                                                        (ex-info "Session is no longer active" {:type :session-closed}))}
                            :else {:error (ex-info "Callback RPC timed out" {:method method})}))
                        (catch Exception e {:error e})
                        (finally (rpc/cancel-request! conn ch)))]
          ;; A completion or abort can retire this attempt before its reply arrives.
          ;; A replacement session has a different claims atom.
          (swap! claims (fn [state]
                          (if (identical? attempt (get state key))
                            (assoc state key (if (invalid-params? (ex-data (:error outcome)))
                                               [:manual (second attempt)] [:retired]))
                            state)))
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

(defn publish!
  "Admit a normalized live event and return its optional observer receipt.
  A receipt delivers {:ok nil} after the synchronous observer returns, or
  {:error cause} on overflow, failure or teardown. Pass it to turn/publish!;
  never wait here. Observer overflow does not close event subscriptions.
  A session callback failure closes only its registered turn state."
  [{:keys [sessions client-open?] :as owner} registration event]
  (let [[send-busy! receipt]
        (locking sessions
          (when (and (client-open?) (registered? sessions registration))
            (let [reg (get @sessions (:id registration))]
              [(let [reason (decision-refusal owner reg)]
                 (cond (nil? reason) (dispatch! owner reg event)
                       (not= reason ::retired) (throw reason)))
               (when-let [lane (:observer reg)] (observe! reg lane event))])))]
    (when send-busy! (send-busy!))
    receipt))
