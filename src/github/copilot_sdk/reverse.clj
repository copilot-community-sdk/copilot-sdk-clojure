(ns ^:no-doc github.copilot-sdk.reverse
  "One reverse dispatcher per protocol-4 connection. The reader captures the
  registration before enqueue. Human prompts and short callbacks have separate
  bounded application pools. Neither runs on the reader or go dispatch."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.string :as str]
            [github.copilot-sdk.callback-scope :as scope]
            [github.copilot-sdk.channels :as channels]
            [github.copilot-sdk.credentials :as credentials]
            [github.copilot-sdk.interruption :as interruption]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.native-interaction :as interaction])
  (:import [java.util.concurrent ArrayBlockingQueue BlockingQueue Callable CancellationException CompletableFuture ExecutionException
            Future FutureTask RejectedExecutionException Semaphore ThreadFactory ThreadPoolExecutor
            TimeUnit TimeoutException]
           [java.util.concurrent.locks ReentrantReadWriteLock]))

;; Registry/client monitors may nest this barrier, never the reverse. Offers
;; share the read side; only short authority transitions take the write side.
;; No wire conversion, application work, job cancellation or I/O under it.
(defmacro with-reply-revocation [owner & body]
  `(let [lock# (.writeLock ^ReentrantReadWriteLock (:reply-barrier ~owner))]
     (.lock lock#)
     (try ~@body (finally (.unlock lock#)))))

(defn- connected? [conn state]
  (and (get-in state [:connection :running?])
       (identical? (:connection-id conn) (get-in state [:connection :connection-id]))))

(defn- active? [{:keys [registry conn client-open?]} reg]
  (let [entries @registry
        id (some-> reg :id deref)]
    (and reg (not (some-> reg :retired? deref))
         (or (identical? reg (get-in entries [:cleanup id]))
             (identical? reg (some-> (get-in entries [:cleanup id :end-hook]) deref))
             (and (client-open?)
                  (or (identical? reg (:ui entries))
                      (identical? reg (get-in entries [:sessions id]))
                      (identical? reg (get-in entries [:credentials (get-in reg [:credential :registration-id])])))))
         (connected? conn @(:state-atom conn)))))

(defn- inactive [] (ex-info "Reverse registration is not active" {::code -32001}))

(defn- deadline [seconds] (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS seconds)))

(defn- check-deadline! [until]
  (when-not (pos? (- until (System/nanoTime))) (throw (TimeoutException.))))

(def ^:private human-handlers
  {"shellAuthentication.request" :on-shell-authentication-request "userInput.request" :on-user-input-request
   "exitPlanMode.request" :on-exit-plan-mode-request "autoModeSwitch.request" :on-auto-mode-switch-request})

(defn- unavailable [method]
  (case method
    "shellAuthentication.request" {:result interaction/shell-cancel}
    "exitPlanMode.request" {:result {:approved false}}
    "autoModeSwitch.request" {:result {:response "no"}}
    "userInput.request" {:error {:code -32000 :message "User input handler is unavailable"}}))

(defn- check-live! [owner reg prompt]
  (when-not (and (active? owner reg) (not (some-> prompt :ch ap/closed?)))
    (throw (inactive))))

(defn- signal []
  (let [ch (a/chan)
        port (reify ap/ReadPort
               (take! [_ handler] (channels/take-dispatched! ch handler)))]
    {:ch ch :port port}))

(defn- cancel-prompt! [{:keys [ch task]}]
  (a/close! ch)
  (when-let [^CompletableFuture waiting @task]
    (.completeExceptionally waiting (inactive))))

(defn- release-prompt-side! [^Semaphore slots prompt side]
  (let [remaining (if (= side :protocol) :application :protocol)
        [before _] (swap-vals! (:permit prompt) #(cond (= % :both) remaining (= % side) :released :else %))]
    (when (= before side) (.release slots))))

(defn- run-prompt! [^Semaphore slots prompt work]
  ;; FutureTask.get can return on cancellation while the callback still runs.
  ;; The last of the protocol worker and the application worker returns the
  ;; permit. Before the application starts, only the protocol worker owns it.
  (let [permit (:permit prompt)]
    (when-not (compare-and-set! permit :protocol :both) (throw (inactive)))
    (try (work) (finally (release-prompt-side! slots prompt :application)))))

(defn- run-job! [{:keys [registry pool human-pool interrupts] :as owner} reg until prompt kind work]
  (let [^ThreadPoolExecutor pool (if prompt human-pool pool)
        result (CompletableFuture.)
        completed? (volatile! false)
        job (FutureTask. ^Callable
             (fn []
               (try
                 (check-deadline! until)
                 (check-live! owner reg prompt)
                 (.complete result (scope/invoke (:conn owner) (:retired? reg) kind
                                                 #(if prompt (run-prompt! (:human-slots owner) prompt work) (work))))
                 (catch Throwable t (.completeExceptionally result t) (throw t)))))
        interrupt (interruption/task interrupts job)
        runner (fn [] (try (.run job) (finally (interrupt :finish))))
        cancel! (fn []
                  (if (.remove pool ^Runnable runner) (.cancel job false) (interrupt :cancel)))]
    (locking registry
      (check-live! owner reg prompt)
      (.execute pool ^Runnable runner)
      (swap! (:jobs reg) assoc job [result cancel!])
      (when-let [task (:task prompt)] (vreset! task result)))
    (try
      (let [v (.get result (- until (System/nanoTime)) TimeUnit/NANOSECONDS)]
        (locking registry
          (check-live! owner reg prompt)
          (check-deadline! until)
          (vreset! completed? true)
          v))
      (catch CancellationException _ (throw (inactive)))
      ;; An application can throw InterruptedException itself. It must not
      ;; masquerade as interruption of the protocol worker and suppress its reply.
      (catch ExecutionException e
        (let [cause (.getCause e)]
          (if (instance? InterruptedException cause)
            (throw (ex-info "Reverse callback interrupted" {}))
            (throw cause))))
      (finally
        ;; A valid answer can be published before its FutureTask returns.
        ;; Only a failed or abandoned wait needs to interrupt application work.
        (when-not @completed?
          (when-let [ch (:ch prompt)] (a/close! ch))
          (cancel!))
        (swap! (:jobs reg) dissoc job)))))

(defn- call [f & args]
  (let [v (apply f args)
        result (cond
                 (satisfies? ap/ReadPort v)
                 ;; An interrupted <!! can leave a pending take on a host's
                 ;; channel. Closing the other alts!! port revokes that take.
                 (let [cancel (a/chan)]
                   (try (first (a/alts!! [v cancel])) (finally (a/close! cancel))))
                 (instance? Future v)
                 (try (.get ^Future v) (catch ExecutionException e (throw (.getCause e))))
                 (and (instance? clojure.lang.IPending v) (instance? clojure.lang.IDeref v)) @v
                 :else v)]
    (if (instance? Throwable result) (throw result) result)))

(defn- call-interaction [method f args]
  ;; Only application exceptions mean "no hook output" or "keep the section".
  (if (#{"hooks.invoke" "systemMessage.transform"} method)
    (try (apply call f args)
         (catch InterruptedException t (throw t))
         (catch Exception _ nil))
    (apply call f args)))

(defn- cancel-jobs! [reg]
  ;; Settle the protocol wait and schedule physical interruption separately:
  ;; neither the reader nor the request worker may run application stream close.
  (doseq [[^CompletableFuture result cancel!] (vals @(:jobs reg))]
    (.completeExceptionally result (inactive))
    (cancel!)))

(defn- cancel-cleanup! [cleanup]
  (when cleanup
    (cancel-jobs! cleanup)
    (when-let [hook (some-> cleanup :end-hook deref)] (cancel-jobs! hook))))

(defn initialized-storage? [reg]
  (some? (some-> reg :cleanup :provider (deref 0 nil))))

(defn retire-end-hook! [{:keys [registry] :as owner} reg]
  (when-let [slot (get-in reg [:cleanup :end-hook])]
    (locking registry
      (when-let [hook @slot]
        (with-reply-revocation owner (vreset! slot nil))
        (cancel-jobs! hook)))))

(defn retire-generation! [{:keys [registry] :as owner} id retired?]
  (locking registry
    (let [route (get-in @registry [:cleanup id])
          cleanup (when (identical? retired? (:retired? route)) route)]
      (with-reply-revocation owner
        (vreset! retired? true)
        (when cleanup (swap! registry update :cleanup dissoc id)))
      (cancel-cleanup! cleanup))))

(defn drain!
  "Stop session decisions and initialization. The cleanup registration survives
   until detach: it owns filesystem I/O and the confirmed session's end hook."
  [{:keys [registry] :as owner} reg]
  (locking registry
    (when-let [id-slot (:id reg)]
      (let [id @id-slot
            credential-id (get-in reg [:credential :registration-id])]
        (when @registry
          (with-reply-revocation owner
            (swap! registry (fn [r] (cond-> r
                                      (identical? reg (get-in r [:sessions id])) (update :sessions dissoc id)
                                      (identical? reg (get-in r [:credentials credential-id]))
                                      (update :credentials dissoc credential-id)))))))
      (when-let [provider (get-in reg [:cleanup :provider])] (deliver provider nil))
      (doseq [prompt (vals @(:prompts reg))] (cancel-prompt! prompt))
      (reset! (:prompts reg) {})
      (cancel-jobs! reg)))
  nil)

(defn unregister! [{:keys [registry] :as owner} reg]
  (locking registry
    (drain! owner reg)
    (when-let [cleanup (:cleanup reg)]
      (let [id @(:id cleanup)]
        (with-reply-revocation owner
          (swap! registry #(if (identical? cleanup (get-in % [:cleanup id]))
                             (update % :cleanup dissoc id) %))))
      (cancel-cleanup! cleanup)))
  nil)

(defn drain-client! [{:keys [registry] :as owner}]
  (locking registry
    (when-let [{:keys [ui sessions credentials]} @registry]
      (with-reply-revocation owner (swap! registry #(-> % (assoc :draining? true) (dissoc :ui))))
      (when ui (cancel-jobs! ui))
      (doseq [reg (distinct (concat (vals sessions) (vals credentials)))]
        (drain! owner reg))))
  nil)

(defn seal-cleanup! [{:keys [registry] :as owner}]
  (locking registry
    (let [routes (vals (:cleanup @registry))]
      (with-reply-revocation owner (swap! registry dissoc :cleanup))
      (doseq [route routes] (cancel-cleanup! route)))))

(defn prepare!
  "Admit a token provider before the runtime knows the cloud session ID."
  [{:keys [registry fs-config conn]} id opts]
  (let [source (get-in opts [:state :create-session-fs-provider])
        credential (credentials/prepare id opts)
        end-hook (get-in opts [:hooks :handlers :on-session-end])
        id-slot (volatile! id)
        retired? (volatile! false)
        reg (cond-> {:id id-slot :retired? retired? :credential credential :callbacks (interaction/callbacks opts)
                     :jobs (atom {}) :prompts (atom {})}
              (or source end-hook)
              (assoc :cleanup (cond-> {:id id-slot :retired? retired? :jobs (atom {})}
                                source (assoc :provider (promise))
                                end-hook (assoc :end-hook (volatile! {:id id-slot :retired? retired? :jobs (atom {})
                                                                      :confirmed? (volatile! false)
                                                                      :callbacks {:hooks {:on-session-end end-hook}}})))))]
    (when-not (= (some? fs-config) (some? source))
      (throw (ex-info "Configure a filesystem on the client and a provider on each session"
                      {:path [:state :create-session-fs-provider]})))
    (locking registry
      (when-not (and @registry (not (:draining? @registry)) (connected? conn @(:state-atom conn)))
        (throw (inactive)))
      (when credential (swap! registry assoc-in [:credentials (:registration-id credential)] reg)))
    reg))

(defn bind! [{:keys [registry conn]} reg id]
  (locking registry
    (when-not (and @registry (not (:draining? @registry)) (connected? conn @(:state-atom conn))) (throw (inactive)))
    (when-not (and (string? id) (not (str/blank? id))) (throw (ex-info "Expected a session ID" {})))
    (when (or (get-in @registry [:sessions id]) (get-in @registry [:cleanup id]))
      (throw (ex-info "Session already owns reverse callbacks" {:type :session-already-open})))
    (vreset! (:id reg) id)
    (swap! registry #(cond-> (assoc-in % [:sessions id] reg)
                       (:cleanup reg) (assoc-in [:cleanup id] (:cleanup reg))))))

(defn confirm-open! [reg]
  (when-let [confirmed? (some-> reg :cleanup :end-hook deref :confirmed?)]
    (vreset! confirmed? true)))

(defn initialize-fs!! [{:keys [fs-config] :as owner} reg session opts]
  (when-let [source (get-in opts [:state :create-session-fs-provider])]
    (deliver (get-in reg [:cleanup :provider])
             (run-job! owner reg (deadline 60) nil :reverse
                       #(fs/validate-provider (:capabilities fs-config)
                                              (if (fn? source)
                                                (scope/invoke (:conn owner) nil :factory
                                                              (partial source session))
                                                source))))))

(defn register!
  "Register a known session and initialize its filesystem before its first RPC."
  [owner id session opts]
  (let [reg (prepare! owner id opts)]
    (try
      (bind! owner reg id)
      (initialize-fs!! owner reg session opts)
      reg
      (catch Throwable t (unregister! owner reg) (throw t)))))

(defn open-params [reg params]
  (cond-> params
    (:credential reg) (assoc-in ["github" "tokenProviderRegistrationId"] (get-in reg [:credential :registration-id]))))

(defn- release-prompt! [{:keys [registry ^Semaphore human-slots]} reg id prompt]
  (when prompt
    (locking registry
      (when (and (:ch prompt) (identical? prompt (get @(:prompts reg) id)))
        (swap! (:prompts reg) dissoc id))
      (release-prompt-side! human-slots prompt :protocol))))

(defn- offer-reply! [{:keys [registry reply-barrier] :as owner} reg id shell-prompt reply offer!]
  (let [lock (.readLock ^ReentrantReadWriteLock reply-barrier)
        offer (fn []
                (.lock lock)
                (try
                  (offer! (cond
                            (not (active? owner reg)) {:error {:code -32001 :message "Reverse registration is not active"}}
                            (and shell-prompt (or (not (identical? shell-prompt (get @(:prompts reg) id)))
                                                  (ap/closed? (:ch shell-prompt)))) {:result interaction/shell-cancel}
                            :else reply))
                  (finally (.unlock lock))))]
    (if shell-prompt
      (locking registry
        (try (offer) (finally (release-prompt! owner reg id shell-prompt))))
      (offer))))

(defn- run-ui! [owner {:keys [schedule run!] :as reg} id until]
  (cond
    (nil? reg) {:error {:code -32601 :message "No native UI scheduler is configured"}}
    (not (and (integer? id) (<= 0 id 0xffffffff)))
    {:error {:code -32602 :message "Invalid native UI action ID"}}
    :else
    (let [available? (atom true)
          completed? (volatile! false)
          action (fn []
                   (when-not (and (pos? (- until (System/nanoTime))) (active? owner reg)
                                  (compare-and-set! available? true false))
                     (throw (inactive)))
                   ;; Must execute synchronously on the application-selected thread.
                   (run! id)
                   (vreset! completed? true)
                   nil)]
      (try
        (run-job! owner reg until nil :reverse
                  #(do (call schedule action)
                       (when-not @completed?
                         (throw (ex-info "UI scheduler returned before running the native action" {})))
                       {:result nil}))
        (finally (reset! available? false))))))

(defn- prepare [{:keys [registry ^Semaphore human-slots] :as owner}
                method {:keys [request-id session-id registration-id hook-type] :as params}]
  (locking registry
    (let [routes @registry
          session (get-in routes [:sessions session-id])
          cleanup (get-in routes [:cleanup session-id])
          end-hook (some-> cleanup :end-hook deref)
          reg (cond
                (fs/method->op method) (when (:provider cleanup) cleanup)
                (and (= method "hooks.invoke") (= hook-type "sessionEnd") end-hook)
                (when @(:confirmed? end-hook) end-hook)
                (= method "gitHubToken.getToken") (get-in routes [:credentials registration-id])
                (= method "uiThread.run") (:ui routes)
                :else session)
          shell? (= method "shellAuthentication.request")
          existing (when (and reg (or shell? (= method "shellAuthentication.cancel")))
                     (get @(:prompts reg) request-id))
          handler-key (human-handlers method)
          eligible? (and handler-key (active? owner reg) (get-in reg [:callbacks :interaction handler-key])
                         (or (not shell?) (and (not existing) (interaction/shell-request? params))))
          prompt (when (and eligible? (.tryAcquire human-slots))
                   (cond-> {:permit (atom :protocol)}
                     shell? (merge (signal) {:task (volatile! nil)})))]
      (when (and shell? prompt) (swap! (:prompts reg) assoc request-id prompt))
      ;; Cancellation cannot queue behind the prompt it must wake. Ingress
      ;; order also prevents a delayed cancel from aborting a reused request ID.
      (when (and (= method "shellAuthentication.cancel") existing)
        (cancel-prompt! existing))
      (cond-> {:params [reg params prompt (deadline (if prompt 300 60)) (and eligible? (nil? prompt))]}
        reg (assoc :offer-reply! #(offer-reply! owner reg request-id (when shell? prompt) %1 %2))
        prompt (assoc :release! #(release-prompt! owner reg request-id prompt))))))

(defn- handle [{:keys [fs-config human-slots] :as owner} method [reg params prompt until surplus?]]
  (let [op (fs/method->op method)
        shell? (= "shellAuthentication.request" method)
        reply (try
                (cond
                  (= "uiThread.run" method)
                  (run-ui! owner reg (:action-id params) until)
                  (= "shellAuthentication.cancel" method) {:result {}}
                  surplus? (unavailable method)
                  (and shell? (nil? prompt)) {:result interaction/shell-cancel}
                  (and op (nil? fs-config)) {:error {:code -32601 :message "Method not found"}}
                  ;; Wait on the protocol worker, not the reader or the pool
                  ;; that must run the filesystem factory. Revocation delivers nil.
                  op (let [provider (when reg (deref (:provider reg)
                                                     (max 0 (.toMillis TimeUnit/NANOSECONDS (- until (System/nanoTime))))
                                                     ::timeout))]
                       (when (= ::timeout provider) (throw (TimeoutException.)))
                       (when-not provider (throw (inactive)))
                       (run-job! owner reg until nil :filesystem #(fs/handle call provider (:capabilities fs-config) op params)))
                  (= method "gitHubToken.getToken")
                  (run-job! owner reg until nil :reverse
                            #(credentials/handle (assoc (:credential reg) :session-id @(:id reg)) params))
                  (interaction/method-names method)
                  (run-job! owner reg until prompt
                            (if (and (= method "hooks.invoke") (= "sessionEnd" (:hook-type params))
                                     (some-> reg :confirmed? deref)) :end-hook :reverse)
                            #(interaction/handle
                              (fn [f & args]
                                (check-live! owner reg prompt)
                                (check-deadline! until)
                                (call-interaction method f args))
                              (:callbacks reg) method params (:port prompt)))
                  :else {:error {:code -32601 :message "Method not found"}})
                (catch InterruptedException t (throw t))
                (catch Throwable t
                  (if (or shell? (and prompt (or (instance? RejectedExecutionException t)
                                                 (instance? TimeoutException t))))
                    (unavailable method)
                    {:error (cond
                              (= -32001 (::code (ex-data t))) {:code -32001 :message "Reverse registration is not active"}
                              (instance? RejectedExecutionException t) {:code -32000 :message "Reverse callback queue full"}
                              (instance? TimeoutException t) {:code -32000 :message "Reverse callback timed out"}
                              :else {:code -32603 :message "Reverse callback failed"})}))
                (finally
                  ;; Only shell prompts retain an ID through the outgoing offer.
                  (when (and prompt (not shell?))
                    (release-prompt-side! human-slots prompt :protocol))))
        out (a/chan 1)]
    (a/offer! out reply)
    (a/close! out)
    out))

(defn- stop! [{:keys [registry conn ^ThreadPoolExecutor pool ^ThreadPoolExecutor human-pool
                      ^ThreadPoolExecutor interrupts handler] :as owner}]
  (locking registry
    (when @registry
      (remove-watch (:state-atom conn) registry)
      (doseq [reg (distinct (concat (vals (:sessions @registry)) (vals (:credentials @registry))))]
        (unregister! owner reg))
      (doseq [cleanup (vals (:cleanup @registry))] (cancel-cleanup! cleanup))
      (when-let [ui (:ui @registry)] (cancel-jobs! ui))
      (with-reply-revocation owner (reset! registry nil))
      ;; Revoked tasks already have a bounded interrupt owner. shutdownNow
      ;; would repeat the physical interrupt under the registry.
      (.shutdown pool)
      (.shutdown human-pool)
      (.shutdown interrupts)
      (swap! (:state-atom conn)
             (fn [state]
               (if (identical? handler (get-in state [:connection :request-handler]))
                 (update state :connection assoc :request-handler nil :request-preparer nil)
                 state))))))

(defn- worker-pool [threads ^BlockingQueue queue thread-name]
  (doto (ThreadPoolExecutor. threads threads 60 TimeUnit/SECONDS queue
                             (reify ThreadFactory
                               (newThread [_ r] (doto (Thread. nil r thread-name 0 false) (.setDaemon true)))))
    (.allowCoreThreadTimeOut true)))

(defn open!
  "Install the sole reverse boundary before connect. Short callbacks have 4
  workers, 32 queue slots and 60 seconds. User input, plan exit, auto-mode and
  shell prompts share at most 4 reservations and a 5-minute deadline. Only
  reserved work enters the bounded handoff queue. Reserve a protocol worker
  for other requests, except in a single-worker test connection.
  Reject surplus prompts without waiting for a human or inventing consent."
  ([conn opts] (open! conn opts nil))
  ([conn opts run-ui-action!] (open! conn opts run-ui-action! (constantly true)))
  ([conn opts run-ui-action! client-open?]
   (when (contains? opts :session-fs) (fs/provider-params (:session-fs opts)))
   (let [ui (when-let [schedule (:run-on-ui-thread opts)]
              (when-not (and (fn? schedule) (fn? run-ui-action!))
                (throw (ex-info "Native UI scheduling requires a host dispatcher and action runner" {})))
              {:schedule schedule :run! run-ui-action! :jobs (atom {})})
         registry (atom {:sessions {} :credentials {} :ui ui})
         human-limit (max 1 (min 4 (dec (.getMaximumPoolSize ^ThreadPoolExecutor (:request-executor conn)))))
         base {:registry registry :client-open? client-open? :reply-barrier (:reply-barrier conn)
               :pool (worker-pool 4 (ArrayBlockingQueue. 32) "copilot-native-reverse")
               :human-pool (worker-pool human-limit (ArrayBlockingQueue. human-limit) "copilot-native-prompt")
               :interrupts (interruption/owner (+ 4 human-limit))
               :human-slots (Semaphore. human-limit) :fs-config (:session-fs opts) :conn conn}
         owner (assoc base :handler (partial handle base))
         lost (a/chan)]
     (a/take! lost (fn [_] (stop! owner)) false)
     (add-watch (:state-atom conn) registry
                (fn [_ _ prev next]
                  (when (and (connected? conn prev) (not (connected? conn next))) (a/close! lost))))
     (try
       (swap! (:state-atom conn)
              (fn [state]
                (when-not (connected? conn state) (throw (inactive)))
                (when (or (get-in state [:connection :request-handler]) (get-in state [:connection :request-preparer]))
                  (throw (ex-info "Connection already has a reverse request dispatcher" {})))
                (update state :connection assoc :request-handler (:handler owner)
                        :request-preparer (partial prepare base))))
       owner
       (catch Throwable t (stop! owner) (a/close! lost) (throw t))))))

(defn close! [{:keys [pool human-pool interrupts] :as owner}]
  (stop! owner)
  (let [until (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS 500))]
    (doseq [^ThreadPoolExecutor executor [pool human-pool interrupts]]
      (when-not (.awaitTermination executor (- until (System/nanoTime)) TimeUnit/NANOSECONDS)
        (throw (ex-info "Reverse workers did not stop before the shutdown deadline"
                        {:type :reverse-worker-timeout :timeout-ms 500})))))
  nil)
