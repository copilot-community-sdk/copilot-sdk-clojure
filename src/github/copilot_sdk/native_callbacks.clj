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
  Two requests per session and eight per connection can run concurrently,
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
  retains its worker until it exits; new observers cannot exceed the limit."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.callback-results :as results]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import [java.util.concurrent ArrayBlockingQueue Callable ExecutionException FutureTask SynchronousQueue
            RejectedExecutionException ThreadFactory ThreadPoolExecutor TimeUnit]))

(defn- pool [size queue label]
  (doto (ThreadPoolExecutor.
         size size 60 TimeUnit/SECONDS queue
         (reify ThreadFactory
           (newThread [_ r] (doto (Thread. r label) (.setDaemon true)))))
    (.allowCoreThreadTimeOut true)))

(defn- connected? [{:keys [connection-id]} state]
  (and (true? (get-in state [:connection :running?]))
       (identical? connection-id (get-in state [:connection :connection-id]))))

(defn- current? [{:keys [conn sessions]} reg]
  (and (:token reg)
       (identical? (:token reg) (:token (get @sessions (:id reg))))
       (connected? conn @(:state-atom conn))))

(defn- fatal? [t] (and (instance? Error t) (not (instance? AssertionError t))))

(defn- worker-task [f]
  ;; FutureTask captures Throwable. Do not turn a fatal VM error into an
  ;; invisible, unobserved future just because cancellation needs a handle.
  (proxy [FutureTask] [(cast Callable f)]
    (done []
      (when-not (.isCancelled ^FutureTask this)
        (try (.get ^FutureTask this)
             (catch ExecutionException e (throw (.getCause e))))))))

(defn- cancel! [{:keys [^FutureTask task cancel]}]
  (a/close! cancel)
  (.cancel task true))

(defn- settle! [receipt outcome]
  (a/put! receipt outcome)
  (a/close! receipt)
  receipt)

(defn- finish-observer! [{:keys [^ArrayBlockingQueue queue pending terminal]} cause]
  (let [outcome {:error cause}]
    (when (compare-and-set! terminal nil outcome)
      (doseq [receipt @pending] (settle! receipt outcome))
      (reset! pending #{})
      (.clear queue)
      (.offer queue [nil outcome]))))

(defn- release! [reg]
  (a/close! (:closed reg))
  (let [token (:token reg)]
    (vswap! (:waiting reg) #(filterv (fn [job] (not (identical? token (:token job)))) %)))
  (when-let [lane (:observer reg)]
    (finish-observer! lane (ex-info "Session observer closed" {:type :session-closed}))
    (when-let [^FutureTask t @(:task lane)] (.cancel t true)))
  (doseq [job (vals (first (reset-vals! (:jobs reg) {})))]
    (cancel! job)))

(defn- stop! [{:keys [sessions requests observers conn]}]
  (locking sessions
    (when-some [regs @sessions]
      (reset! sessions nil)
      (remove-watch (:state-atom conn) sessions)
      (doseq [reg (vals regs)] (release! reg))
      (doseq [^ThreadPoolExecutor executor [requests observers]]
        (.shutdownNow executor)))))

(defn open!
  "Create a bounded broadcast owner. SessionFs and host reverse requests
  belong to their own dispatcher, not the event worker."
  [conn _client-options]
  (let [sessions (atom {})
        owner {:conn conn :sessions sessions :waiting (volatile! []) :running (volatile! #{})
               ;; Up to eight assigned successors can await executor handoff
               ;; while their predecessors finish returning from run-job!.
               :requests (pool 8 (ArrayBlockingQueue. 8) "copilot-native-request")
               :observers (pool 16 (SynchronousQueue.) "copilot-native-observer")}
        lost (a/chan)]
    ;; The protocol can change its state while holding the reader lock. Signal
    ;; the transition here; never acquire our admission lock from a watch.
    (a/take! lost (fn [_] (stop! owner)) false)
    (add-watch (:state-atom conn) sessions
               (fn [_ _ prev next]
                 (when (and (connected? conn prev) (not (connected? conn next)))
                   (a/close! lost))))
    (when-not (connected? conn @(:state-atom conn))
      (stop! owner)
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
  (when handler {:handler handler :queue (ArrayBlockingQueue. 64) :pending (atom #{})
                 :task (atom nil) :terminal (atom nil)}))

(defn- start-observer! [{:keys [sessions observers] :as owner} reg]
  (when-let [{:keys [handler ^ArrayBlockingQueue queue pending terminal task]} (:observer reg)]
    (let [run (fn []
                (try
                  (loop []
                    (let [[receipt event] (.take queue)]
                      (when (and (current? owner reg) (or (nil? receipt) (nil? @terminal)))
                        (let [outcome (try (handler event) {:ok nil}
                                           (catch Throwable t
                                             (when (fatal? t) (throw t))
                                             (log/warn "Event observer failed" {:session-id (:id reg)})
                                             {:error (ex-info "Event observer failed" {:type :observer-failed})}))]
                          (when receipt
                            (locking sessions
                              (when (contains? @pending receipt)
                                (swap! pending disj receipt)
                                (settle! receipt outcome))))))
                      (when (and receipt (not (ap/closed? (:closed reg)))) (recur))))
                  (catch InterruptedException _ nil)
                  (finally
                    (locking sessions
                      (finish-observer! (:observer reg)
                                        (ex-info "Session event observer stopped" {:type :observer-stopped}))))))
          t (worker-task run)]
      (reset! task t)
      (try (.execute ^ThreadPoolExecutor observers t)
           (catch RejectedExecutionException _
             (throw (ex-info "No observer workers available" {:type :observer-capacity})))))))

(defn register!
  "Admit callbacks before session.create/resume. The caller must first validate
  session options with the closed create/resume builder; it rejects unimplemented
  callback groups. Keep the returned token, not just the session ID."
  [{:keys [sessions conn waiting] :as owner} id opts]
  (let [definitions (get-in opts [:tools :definitions])
        tools (into {} (map (juxt :name tool-handler)) definitions)
        registration {:id id :token (Object.)}
        reg (assoc registration :jobs (atom {}) :claims (atom {}) :closed (a/chan) :tools tools
                   :waiting waiting
                   :failure (atom nil) :busy-replies (atom 0)
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

(defn unregister! [{:keys [sessions]} {:keys [id token]}]
  (locking sessions
    (when-let [reg (get @sessions id)]
      (when (identical? token (:token reg))
        (swap! sessions dissoc id)
        (release! reg))))
  nil)

(defn close!
  "Fence the owner and interrupt its work. An uncooperative handler cannot
  delay teardown indefinitely. Report remaining workers without user data."
  [{:keys [requests observers] :as owner}]
  (stop! owner)
  (doseq [^ThreadPoolExecutor executor [requests observers]]
    (when-not (.awaitTermination executor 500 TimeUnit/MILLISECONDS)
      (log/warn "Callback workers did not stop before the shutdown deadline")))
  nil)

(defn- tool-result [value]
  (cond
    (nil? value) ""
    (string? value) value
    (and (map? value) (string? (:text-result-for-llm value))
         (s/valid? ::specs/result-type (:result-type value)))
    (-> value results/structured-tool-result util/clj->wire util/opaque-json->wire)
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

(defn- request-rpc!
  "Serialize submission, not response waiting, with unregister. The worker
  owns its pending RPC until response, cancellation or deadline."
  [{:keys [sessions conn] :as owner} reg cancel method params]
  (let [ch (locking sessions
             (when-not (and (current? owner reg) (not (ap/closed? cancel)))
               (throw (ex-info "Callback is no longer active" {:type :callback-cancelled})))
             (rpc/send-request conn method (assoc params :session-id (:id reg))))]
    (await-rpc! conn cancel method ch)))

(defn- await-value [value cancel]
  (let [v (if (satisfies? ap/ReadPort value)
            (let [[result port] (a/alts!! [cancel value] :priority true)]
              (if (= port value) result
                  (throw (ex-info "Callback cancelled" {:type :callback-cancelled}))))
            value)]
    (if (instance? Throwable v) (throw v) v)))

(defn- invalid-params? [error]
  ;; Other server failures may occur after the result was applied.
  (= -32602 (get-in (ex-data error) [:error :code])))

(defn- permit-manual! [owner reg cancel key agent-id]
  (locking (:sessions owner)
    (when (and (current? owner reg) (not (ap/closed? cancel))
               (= :automatic (first (get @(:claims reg) key))))
      (swap! (:claims reg) assoc key [:manual agent-id])
      true)))

(defn- deliver-response! [owner reg cancel [kind request-id :as key] agent-id body]
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
                    (not (and terminal (invalid-params? e))) (throw e)
                    (not= reply terminal) (deliver! terminal)
                    (#{:oauth :elicitation} kind)
                    (log/warn "Runtime rejected callback cancellation"
                              {:kind kind :session-id (:id reg) :request-id request-id})
                    (permit-manual! owner reg cancel key agent-id)
                    (log/warn "Runtime rejected terminal callback reply; manual response required"
                              {:kind kind :session-id (:id reg) :request-id request-id})))))]
      (deliver! body))))

(defn- invoke-request! [owner reg kind event handler cancel]
  (let [{:keys [data agent-id]} event
        {:keys [request-id permission-request arguments tool-name]} data
        cancelled? #(ap/closed? cancel)
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
                   (when-not (ap/closed? cancel)
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
      (deliver-response! owner reg cancel [kind request-id] agent-id body)
      (when (= :permission kind)
        (permit-manual! owner reg cancel [kind request-id] agent-id)))))

(declare pump!)

(defn- run-job! [{:keys [sessions running] :as owner} {:keys [reg k cancel ^FutureTask task] :as job}]
  (try
    (.run task)
    (catch Error fatal
      (locking sessions
        (reset! (:failure reg) (ex-info "Session callback worker failed" {:type :callback-worker-failed}))
        (release! reg))
      (throw fatal))
    ;; Cancellation is not worker release: an interrupted application may
    ;; keep running. Only release capacity when its actual invocation exits.
    (finally
      (locking sessions
        (a/close! cancel)
        (swap! (:jobs reg) dissoc k)
        (vswap! running disj job)
        (pump! owner)))))

(defn- pump! [{:keys [^ThreadPoolExecutor requests waiting running] :as owner}]
  ;; Admissions and exits hold the same lock. Hand off the oldest eligible
  ;; job without relying on a departing pool thread already being idle.
  (when (and (< (count @running) 8) (seq @waiting))
    (let [active (frequencies (map :token @running))]
      (when-let [job (some #(when (< (get active (:token %) 0) 2) %) @waiting)]
        (vswap! waiting #(filterv (fn [x] (not (identical? job x))) %))
        (vswap! running conj job)
        (.execute requests ^Runnable #(run-job! owner job))
        (recur owner)))))

(defn- submit! [{:keys [waiting] :as owner} {:keys [jobs token] :as reg} k work]
  (when (and (< (count @waiting) 128)
             (< (count (filter #(identical? token (:token %)) @waiting)) 16))
    (let [cancel (a/chan)
          task (worker-task (fn []
                              (try
                                (when (and (current? owner reg) (not (ap/closed? cancel)))
                                  (work cancel))
                                (catch Exception _
                                  (when-not (ap/closed? cancel)
                                    (log/warn "Callback dispatch failed"))))))
          job {:token token :reg reg :k k :task task :cancel cancel}]
      (swap! jobs assoc k job)
      (vswap! waiting conj job)
      (pump! owner)
      true)))

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
    (reset! (:failure reg) cause)
    (log/warn message (ex-data cause))
    (release! reg)
    (throw cause)))

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
    (cancel! job)))

(defn- reject-busy! [conn {:keys [busy-replies closed] :as reg} [kind request-id]]
  (when (>= @busy-replies 16)
    (fail-session! reg "Session callback replies saturated" :callback-overflow))
  (let [method (response-method kind)
        params (assoc (failed-response kind "busy") :session-id (:id reg) :request-id request-id)
        ch (rpc/send-request conn method params)]
    (swap! busy-replies inc)
    (a/go
      (try
        (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
          (when (and (not= port closed) (not (true? (get-in response [:result :success]))))
            (log/warn "Runtime did not accept busy callback reply" {:kind kind :session-id (:id reg)})))
        (finally (rpc/cancel-request! conn ch) (swap! busy-replies dec))))))

(defn- admit! [{:keys [conn] :as owner} reg kind event key]
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
                (reject-busy! conn reg key)))
          (when (#{:tool :permission} kind)
            (claim! reg key [:manual agent-id])))))))

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
          [{:keys [closed claims]} attempt ch]
          (locking sessions
            (when-not (current? owner registration)
              (throw (ex-info "Session is no longer active" {:type :session-closed})))
            (let [{:keys [claims] :as reg} (get @sessions id)
                  [status agent-id] (get @claims key)
                  attempt [:responding agent-id]]
              (when-let [cause @(:failure reg)] (throw cause))
              (when-not (or (nil? status) (= :manual status))
                (throw (ex-info "Callback request is not pending" {:type :not-pending})))
              (claim! reg key attempt)
              [reg attempt (rpc/send-request conn method (assoc body :session-id id :request-id (:request-id opts)))]))]
      (a/go
        (let [outcome (try
                        (let [[response port] (a/alts! [ch closed (a/timeout 10000)] :priority true)]
                          (cond
                            (= port ch) (let [ack (rpc/response-result! method response)]
                                          (when-not (true? (:success ack))
                                            (throw (ex-info "Runtime did not accept the pending request response"
                                                            {:type :request-not-handled :method method})))
                                          {:ok ack})
                            (= port closed) {:error (ex-info "Session is no longer active" {:type :session-closed})}
                            :else {:error (ex-info "Callback RPC timed out" {:method method})}))
                        (catch Exception e {:error e})
                        (finally (rpc/cancel-request! conn ch)))]
          ;; A completion or abort can retire this attempt before its reply arrives.
          ;; A replacement session has a different claims atom.
          (swap! claims (fn [state]
                          (if (identical? attempt (get state key))
                            (assoc state key (if (invalid-params? (:error outcome))
                                               [:manual (second attempt)] [:retired]))
                            state)))
          outcome)))
    (catch Exception e (a/go {:error e}))))

(defn- observe! [reg {:keys [^ArrayBlockingQueue queue pending terminal] :as lane} event]
  (if-let [outcome @terminal]
    (settle! (a/promise-chan) outcome)
    (let [receipt (a/promise-chan)]
      (swap! pending conj receipt)
      (when-not (.offer queue [receipt event])
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
  A session callback failure throws for the router's session-local turn/close!."
  [{:keys [sessions] :as owner} registration event]
  (locking sessions
    (when (current? owner registration)
      (let [reg (get @sessions (:id registration))]
        (when-let [cause @(:failure reg)] (throw cause))
        (dispatch! owner reg event)
        (when-let [lane (:observer reg)]
          (observe! reg lane event))))))
