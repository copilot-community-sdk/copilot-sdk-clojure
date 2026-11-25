(ns ^:no-doc github.copilot-sdk.native-client
  "Owned protocol-4 clients and sessions. Only the native host opens a connection."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.spec.alpha :as spec]
            [clojure.string :as str]
            [github.copilot-sdk.callback-scope :as scope]
            [github.copilot-sdk.discovery :as discovery]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.native-callbacks :as callbacks]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.reverse :as reverse]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.teardown :as teardown]
            [github.copilot-sdk.turn :as turn]
            [github.copilot-sdk.util :as util])
  (:import [java.lang AutoCloseable]
           [java.time Instant]
           [java.util UUID]
           [java.util.concurrent CompletableFuture TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(declare close-client! close-session! client-connection)
(declare ^:private finish-input!)

(deftype Client [connection options callbacks reverseOwner discovery lifecycle sessions closeHost]
  AutoCloseable
  (close [this] (close-client! this)))

(deftype Session [^Client client id turns registration lifecycle ^AtomicInteger publications ^CompletableFuture published]
  AutoCloseable
  (close [this] (close-session! this)))

(defn client? [x] (instance? Client x))
(defn session? [x] (instance? Session x))

(def ^:private ^:dynamic *close-interrupted* nil)

(defn- failure-of [action]
  (let [failure (try (action) nil (catch Throwable t t))]
    (if *close-interrupted*
      ;; A lower-level cleanup can restore the flag instead of throwing.
      ;; Do not let that interrupt the owner's next independent cleanup step.
      (when (or (Thread/interrupted) (instance? InterruptedException failure))
        (vreset! *close-interrupted* true))
      (when (instance? InterruptedException failure) (.interrupt (Thread/currentThread))))
    failure))

(defn- combined-failure [failures]
  (let [[primary & more] (remove nil? failures)]
    (if (seq more)
      ;; A joined transition can also report its error to another caller.
      ;; Attach additional failures to our own exception, never to theirs.
      (let [error (if (instance? InterruptedException primary)
                    (ex-info "Lifecycle owner was interrupted" {:type :lifecycle-interrupted} primary)
                    (ex-info (ex-message primary) (or (ex-data primary) {}) primary))]
        (doseq [t more] (.addSuppressed error t))
        error)
      primary)))

(defn- cleanup! [actions]
  (when-let [failure (combined-failure (mapv failure-of actions))] (throw failure)))

(defn- release-connection! [conn]
  (let [[^Throwable primary & more] (rpc/disconnect conn)]
    (when primary
      (doseq [^Throwable t more] (.addSuppressed primary t))
      (throw primary))))

(defn- instant [s] (Instant/parse s))

(defn- merge-capabilities [base patch]
  (cond-> (merge base patch)
    (:ui patch) (assoc :ui (merge (:ui base) (:ui patch)))))

(defn- lifecycle-event [{:keys [type session-id metadata]}]
  (when (and (string? type) (string? session-id))
    (cond-> {:type (keyword type) :session-id session-id}
      (and (string? (:start-time metadata)) (string? (:modified-time metadata)))
      (assoc :metadata (-> (select-keys metadata [:start-time :modified-time :summary])
                           (update :start-time instant)
                           (update :modified-time instant))))))

(defn- capture-event [sessions id]
  ;; Only the reader increments this count or sets the generation's ACK bit.
  ;; The router returns each accepted frame after publication, not after user code.
  (let [s (get @sessions id)]
    (when (and (session? s) (not @(:token (.-registration ^Session s))))
      (.incrementAndGet (.-publications ^Session s))
      s)))

(defn- route! [^Client client]
  (a/go-loop []
    (if-let [{:keys [method params] captured ::session} (a/<! (rpc/notifications (.-connection client)))]
      (do
        (case method
          "session.lifecycle"
          (try
            (if-let [event (lifecycle-event params)]
              (turn/publish! (.-lifecycle client) event)
              (log/warn "Ignoring invalid session lifecycle notification"))
            (catch java.time.DateTimeException _
              (log/warn "Ignoring invalid session lifecycle timestamp")))
          "session.event"
          (when-let [^Session s captured]
            (try
              (when (identical? s (get @(.-sessions client) (:session-id params)))
                (let [event (turn/normalize-event (:event params))]
                  (when (= :copilot/capabilities.changed (:type event))
                    (swap! (.-lifecycle s) update-in [:info :capabilities] merge-capabilities (:data event)))
                  (turn/publish! (.-turns s) event
                                 (callbacks/publish! (.-callbacks client) (.-registration s) event))))
              (catch Exception e
                (callbacks/fail! (.-callbacks client) (.-registration s) e))
              (finally
                (.decrementAndGet (.-publications s))
                (when (and @(:token (.-registration s)) (zero? (.get (.-publications s))))
                  (.complete (.-published s) nil)))))
          nil)
        (recur))
      (finish-input! client))))

(defn- absolute-path? [path]
  (and (string? path)
       (try (.isAbsolute (.toPath (java.io.File. ^String path)))
            (catch java.nio.file.InvalidPathException _ false))))

(defn- client-settings [opts]
  (let [model-options (:models opts)
        plugins (:plugins opts)
        trace (get-in opts [:observability :on-get-trace-context])
        info-keys {:application-name "editorName" :application-version "editorVersion"
                   :integration-name "extensionName" :integration-version "extensionVersion"}
        info (:client-info opts)]
    (when (and (contains? opts :models)
               (not (and (map? model-options) (every? #{:on-list} (keys model-options))
                         (or (not (contains? model-options :on-list)) (fn? (:on-list model-options))))))
      (throw (ex-info "Invalid model listing callback configuration" {:path [:models]})))
    (when (and (contains? (:observability opts) :on-get-trace-context) (not (fn? trace)))
      (throw (ex-info "Expected a trace context function" {:path [:observability :on-get-trace-context]})))
    (when (and (contains? opts :client-info)
               (not (and (map? info) (every? info-keys (keys info)) (every? string? (vals info)))))
      (throw (ex-info "Invalid client identity" {:path [:client-info]})))
    (when (and (contains? opts :run-on-ui-thread) (not (fn? (:run-on-ui-thread opts))))
      (throw (ex-info "Expected a native UI scheduler" {:path [:run-on-ui-thread]})))
    (when (contains? opts :plugins)
      (when-not (and (map? plugins) (every? #{:builtin-directories} (keys plugins)))
        (throw (ex-info "Invalid client plugin options" {:path [:plugins]})))
      (when (contains? plugins :builtin-directories)
        (let [paths (:builtin-directories plugins)]
          (when-not (and (vector? paths) (every? absolute-path? paths))
            (throw (ex-info "Expected a vector of absolute host paths" {:path [:plugins :builtin-directories]}))))))
    {:host (cond-> (dissoc opts :models :client-info :run-on-ui-thread :plugins)
             (some? trace) (update :observability dissoc :on-get-trace-context))
     :identity (into {} (keep (fn [[k v]] (when (seq v) [(info-keys k) v]))) info)}))

(defn open-client! [opts]
  (let [{:keys [identity] host-opts :host} (client-settings opts)
        fs-config (:session-fs opts)
        fs-params (when (contains? opts :session-fs) (fs/provider-params fs-config))
        rollback (volatile! [])]
    (try
      (let [{:keys [in out close! configure-ui! run-ui-action!]} (host/open! host-opts)
            _ (vswap! rollback conj close!)
            sessions (atom {})
            prepare (fn [{:keys [method params] :as notification}]
                      (cond-> notification
                        (= method "session.event") (assoc ::session (capture-event sessions (:session-id params)))))
            conn (rpc/connect in out (atom {:connection (rpc/initial-connection-state)
                                            :options {:prepare-notification prepare}}))
            _ (vswap! rollback conj #(release-connection! conn))
            close-host (atom {:open? true :release! close!})
            client-open? #(:open? @close-host)
            owner (callbacks/open! conn client-open?)
            _ (vswap! rollback conj #(callbacks/close! owner))
            reverse-owner (reverse/open! conn opts run-ui-action! client-open?)
            _ (vswap! rollback conj #(reverse/close! reverse-owner))
            discover (discovery/open! conn opts)
            _ (vswap! rollback conj #(discovery/close! discover))
            _ (configure-ui! (boolean (:run-on-ui-thread opts)))
            hello (rpc/send-request! conn "connect" (if (seq identity) {"clientInfo" identity} {}) 10000)
            v (:protocol-version hello)]
        (when-not (= v 4)
          (throw (ex-info "Native runtime does not speak SDK protocol 4"
                          {:type :protocol-version-mismatch :expected 4 :actual v})))
        (let [paths (get-in opts [:plugins :builtin-directories])]
          (when (seq paths)
            (rpc/send-request! conn "plugins.builtin.set" {:paths paths} 10000)))
        (when fs-config
          (when-not (true? (:success (rpc/send-request! conn "sessionFs.setProvider" fs-params 10000)))
            (throw (ex-info "Runtime did not register the session filesystem" {:type :session-fs-rejected}))))
        (let [client (Client. conn (select-keys opts [:mode :base-directory]) owner reverse-owner discover (turn/new-state) sessions close-host)]
          (route! client)
          client))
      (catch Throwable t
        (binding [*close-interrupted* (volatile! (or (Thread/interrupted) (instance? InterruptedException t)))]
          (try
            (doseq [release (rseq @rollback)]
              (when-let [failure (failure-of release)]
                (when-not (identical? t failure) (.addSuppressed t failure))))
            (finally (when @*close-interrupted* (.interrupt (Thread/currentThread))))))
        (throw t)))))

(defn- release-opts [^Client client token]
  ;; A positive ACK retires just this generation before the next frame.
  ;; The lifecycle owner still releases registrations and interrupts work.
  (when-let [retired? (:retired? token)]
    (let [owner (.-reverseOwner client)
          conn (.-connection client)
          id @(:id token)
          release! (fn [success]
                     (when (true? success)
                       (reverse/retire-generation! owner id retired?)
                       (callbacks/retire-forms! conn id retired?)))]
      {:on-response-inline #(release! (:success %))
       :on-late-response (fn [result _] (release! (get result "success")))})))

(defn- check-release! [method id response]
  (let [{:keys [error] :as result} (rpc/response-result! method response)]
    (when-not (true? (:success result))
      (throw (ex-info (case method
                        "session.detach" "Runtime did not detach the session"
                        "session.delete" "Runtime did not delete the session")
                      (cond-> {:session-id id} (string? error) (assoc :reason error)))))))

(defn- detach! [^Client client id token]
  (let [reply (rpc/send-request-with-timeout (.-connection client) "session.detach" {:session-id id} 10000
                                             (release-opts client token))]
    (check-release! "session.detach" id (rpc/await-bounded-response!! reply))))

(defn- check-transition! [{:keys [error]}]
  (when error
    (if (instance? InterruptedException error)
      (throw (ex-info "Lifecycle owner was interrupted" {:type :lifecycle-interrupted} error))
      (throw error))))

(defn- await-transition!! [done]
  (let [[outcome port] (try (a/alts!! [done (a/timeout 65000)] :priority true)
                            (catch InterruptedException t
                              (when-not *close-interrupted* (.interrupt (Thread/currentThread)))
                              (throw t)))]
    (when-not (= port done)
      (throw (ex-info "Timed out waiting for lifecycle transition" {:type :lifecycle-timeout})))
    (check-transition! outcome)))

(defn- settled! [done outcome]
  (a/offer! done outcome)
  (a/close! done))

(defn- shutdown-entries [^Client client]
  ;; Capture transitions when admission closes, not after rollback has had a
  ;; chance to remove an ID and its completion channel.
  (mapv (fn [[id entry]] [id entry (if (session? entry) @(.-lifecycle ^Session entry) entry)])
        @(.-sessions client)))

(defn- retire-session!
  ([^Session s keep-cleanup?]
   (retire-session! s keep-cleanup? false (ex-info "Session closed" {:type :session-closed :session-id (.-id s)})))
  ([^Session s keep-cleanup? drain-events? cause]
   (let [client (.-client s)]
     (locking client
       (let [reverse-token (:reverse-token @(.-lifecycle s))]
         (when reverse-token
           (reset! (.-lifecycle s) (cond-> {:status :closed} keep-cleanup? (assoc :reverse-token reverse-token))))
         (cleanup! [#(turn/close! (.-turns s) cause (= :connection-closed (:type (ex-data cause))))
                    ;; An observer can still be draining after EOF released the reverse token.
                    #((if drain-events? callbacks/drain! callbacks/unregister!) (.-callbacks client) (.-registration s))
                    #(.complete (.-published s) nil)
                    #(when reverse-token
                       ((if keep-cleanup? reverse/drain! reverse/unregister!) (.-reverseOwner client) reverse-token))
                    ;; A pending cloud create can still return this detached ID.
                    #(when (:open? @(.-closeHost client))
                       (swap! (.-sessions client)
                              (fn [sessions]
                                (if (identical? s (get sessions (.-id s))) (dissoc sessions (.-id s)) sessions))))]))))))

(defn- finish-input! [^Client client]
  (let [sessions (.-sessions client)
        error (get-in @(:state-atom (.-connection client)) [:connection :terminal-error])
        cause (ex-info (or (:message error) "Runtime connection closed") {:type :connection-closed :error error})]
    (locking client
      (when (:open? @(.-closeHost client))
        (callbacks/finish-input! (.-callbacks client))
        (swap! (.-closeHost client) assoc :input-error cause)
        (doseq [[id s] @sessions :when (session? s)]
          (let [{:keys [status done]} @(.-lifecycle ^Session s)]
            ;; The opening thread still owns rollback and its completion.
            (when (= status :opening) (swap! sessions assoc id done))
            (retire-session! s false true cause))))
      (turn/close! (.-lifecycle client) cause))))

(defn- unregister! [s]
  (retire-session! s false))

(defn- retain-storage! [^Session s]
  ;; The registry, not a stale handle, owns cleanup through late release or shutdown.
  (let [c (.-client s) state (.-lifecycle s)]
    (locking c
      (reverse/retire-end-hook! (.-reverseOwner c) (:reverse-token @state))
      (swap! state dissoc :reverse-token))))

(defn- finish-acknowledged! [^Session s]
  ;; The ACK has sealed ingress. Wait only for the router, never the observer:
  ;; an observer can synchronously detach its own session.
  (teardown/with-cleanup
    #(retire-session! s false true (ex-info "Session closed" {:type :session-closed :session-id (.-id s)}))
    (when (pos? (.get (.-publications s)))
      (.join (.orTimeout (.-published s) 10 TimeUnit/SECONDS)))))

(defn- detach-for-shutdown! [^Client c entries forced]
  (let [conn (.-connection c)
        deadline (a/timeout 10000)
        pending (volatile! {})
        errors (volatile! [])
        detach (fn [id entry]
                 (let [check! #(cond (ap/closed? forced) (throw (ex-info "Force close interrupted graceful shutdown"
                                                                         {:type :graceful-close-interrupted}))
                                     (ap/closed? deadline) (throw (ex-info "Session detach deadline expired" {:session-id id})))
                       reply (rpc/send-request conn "session.detach" {:session-id id}
                                               (assoc (release-opts c (:reverse-token entry)) :check-admission! check!))]
                   (vswap! pending assoc reply [id :detach entry])))]
    ;; Only this closing thread admits detaches. A failed delete lookup becomes
    ;; eligible as soon as its own transition settles, not when a prior ACK arrives.
    (try
      (doseq [[id _ {:keys [status done] :as entry}] entries]
        (cond
          (= status :open) (detach id entry)
          done (vswap! pending assoc done [id status entry])
          (satisfies? ap/ReadPort entry) (vswap! pending assoc entry [id nil nil])))
      (loop []
        (when (seq @pending)
          (let [[outcome port] (a/alts!! (into (vec (keys @pending)) [deadline forced]) :priority true)]
            (cond
              (= port forced)
              (throw (ex-info "Force close interrupted graceful shutdown" {:type :graceful-close-interrupted}))
              (= port deadline)
              (if-let [[id] (some #(when (= :detach (second %)) %) (vals @pending))]
                (throw (ex-info "Session detach deadline expired" {:session-id id}))
                (throw (ex-info "Timed out waiting for lifecycle transition" {:type :lifecycle-timeout})))
              :else
              (let [[id kind entry] (get @pending port)]
                (vswap! pending dissoc port)
                (try
                  (if (= kind :detach)
                    (check-release! "session.detach" id outcome)
                    (do (check-transition! outcome)
                        (when (and (= kind :deleting) (:delete-not-submitted? outcome))
                          (detach id entry))))
                  (catch Exception e (vswap! errors conj e)))
                (recur))))))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (vswap! errors conj e))
      (catch Exception e (vswap! errors conj e))
      (finally
        (doseq [[ch [_ kind entry]] @pending]
          (when (= kind :detach) (rpc/cancel-request! conn ch))
          (reverse/retire-end-hook! (.-reverseOwner c) (:reverse-token entry)))))
    (when-let [failure (combined-failure @errors)] (throw failure))))

(defn- uncertain-response? [^Client client failure]
  (or (::rpc/timeout (meta (:error (ex-data failure))))
      (not (get-in @(:state-atom (.-connection client)) [:connection :running?]))))

(defn- fence-uncertain! [^Client client id owner failure]
  (when (uncertain-response? client failure)
    (swap! (.-sessions client) #(if (identical? owner (get % id)) (assoc % id ::unconfirmed-cleanup) %))
    (when (session? owner)
      (let [token (:reverse-token @(.-lifecycle ^Session owner))]
        (teardown/cleanup-preserving!
         failure
         #(if (and (get-in @(:state-atom (.-connection client)) [:connection :running?]) (reverse/initialized-storage? token))
            (do (retire-session! owner true) (retain-storage! owner))
            (unregister! owner)))))))

(defn- close-session* [^Session s]
  (let [client (.-client s)
        own-end-hook? (= :ending (scope/check! (.-connection client) :detach (:token (.-registration s))))
        [role done]
        (locking client
          (let [connected? (get-in @(:state-atom (.-connection client)) [:connection :running?])
                shutdown (:done @(.-closeHost client))
                {:keys [status done]} @(.-lifecycle s)]
            (cond
              own-end-hook? nil
              (not (identical? s (get @(.-sessions client) (.-id s))))
              [:close-retired-observer]
              shutdown (do (scope/check! (.-connection client) :shutdown)
                           [:wait shutdown])
              :else (case status
                      (:opening :deleting) (throw (ex-info "Session lifecycle operation in progress"
                                                           {:type :session-busy :session-id (.-id s)}))
                      :closing [:wait done]
                      :open (if connected?
                              (let [d (a/promise-chan)]
                                (swap! (.-lifecycle s) assoc :status :closing :done d)
                                [:detach d])
                              (unregister! s))
                      nil))))]
    (case role
      :close-retired-observer (callbacks/close-retired-observer! (.-callbacks client) (.-registration s))
      :wait (await-transition!! done)
      :detach (do
                (try
                  (detach! client (.-id s) (:reverse-token @(.-lifecycle s)))
                  (finish-acknowledged! s)
                  (settled! done {})
                  (catch Throwable t
                    (locking client
                      (fence-uncertain! client (.-id s) s t)
                      (when (and (:open? @(.-closeHost client))
                                 (identical? s (get @(.-sessions client) (.-id s))))
                        (swap! (.-lifecycle s) #(-> % (assoc :status :open) (dissoc :done)))))
                    (settled! done {:error t})
                    (throw t)))
                (when (.isInterrupted (Thread/currentThread))
                  (throw (InterruptedException. "Interrupted while detaching the session"))))
      nil)))

(defn close-session! [^Session s]
  ;; An interrupt inherited from the scope body is not a failed detach.
  (let [already-interrupted? (Thread/interrupted)]
    (try (close-session* s)
         (finally (when already-interrupted? (.interrupt (Thread/currentThread)))))))

(defn- release-client-resources! [^Client client entries]
  (cleanup!
   (concat
    ;; A failed detach may need the retained provider right up to runtime.shutdown.
    (for [[_ s _] entries :when (session? s)] #(unregister! s))
    [#(turn/close! (.-lifecycle client) (ex-info "Client closed" {:type :client-closed}))
     #(reverse/close! (.-reverseOwner client))
     #(callbacks/close! (.-callbacks client))
     #(release-connection! (.-connection client))
     ;; No remaining create reply can turn a retired ID into a cleanup RPC.
     #(reset! (.-sessions client) {})])))

(defn close-client!
  ([client] (close-client! client true))
  ([^Client client graceful?]
   (scope/check! (.-connection client) :shutdown)
   (let [slot (.-closeHost client)
         [owner? state entries]
         (locking client
           (when-let [state @slot]
             (let [owner? (not (:done state))
                   state (if owner?
                           (assoc state :done (a/promise-chan) :released (a/promise-chan) :forced (a/chan))
                           state)]
               (when owner?
                 (reverse/with-reply-revocation (.-reverseOwner client) (reset! slot (assoc state :open? false))))
               (when (and (not graceful?) (not (ap/closed? (:forced state))))
                 ;; Wake protocol waiters and joined transitions. Only the
                 ;; release owner touches the streams, executors and native host.
                 (rpc/end-input! (.-connection client) {:code -32000 :message "Client force closed"})
                 (a/close! (:forced state)))
               [owner? state (when (and owner? (:open? state)) (shutdown-entries client))])))]
     (when state
       (if-not owner?
         (await-transition!! (if graceful? (:done state) (:released state)))
         (binding [*close-interrupted* (volatile! (Thread/interrupted))]
           (try
             (let [{:keys [open? forced done released release! input-error]} state
                   fence-error (when open?
                                 (failure-of
                                  #(cleanup! (concat [(fn [] (discovery/close! (.-discovery client)))
                                                      (fn [] (callbacks/cancel-all! (.-callbacks client)))
                                                      (fn [] (reverse/drain-client! (.-reverseOwner client)))]
                                                     (for [[_ s _] entries :when (session? s)]
                                                       (fn [] (retire-session! s graceful?)))))))
                   graceful-error (when open?
                                    (if graceful?
                                      (combined-failure
                                       [(failure-of #(detach-for-shutdown! client entries forced))
                                        (or input-error
                                            (failure-of #(rpc/send-request! (.-connection client) "runtime.shutdown" {} 10000
                                                                            {:on-response-inline
                                                                             (fn [_] (reverse/seal-cleanup! (.-reverseOwner client)))})))])
                                      (ex-info "Force close skipped graceful shutdown" {:type :graceful-close-interrupted})))
                   local-error (when open? (failure-of #(release-client-resources! client entries)))
                   native-error (failure-of release!)
                   release-error (combined-failure [fence-error local-error native-error])
                   graceful-result (combined-failure [graceful-error release-error])]
               (locking client
                 (settled! released {:error release-error})
                 (settled! done {:error graceful-result})
                 (reset! slot (when native-error {:release! release!})))
               (when-let [error (if graceful? graceful-result release-error)] (throw error)))
             (finally
               (when @*close-interrupted* (.interrupt (Thread/currentThread)))))))))))

(defn- check-callback-failure! [^Session s]
  (when-let [failure @(:failure (.-registration s))] (throw failure)))

(defn- session-admission [^Client client ^Session s opts]
  ;; The failure transition can run while a different thread prepares a request.
  (let [check! (:check-admission! opts)]
    (assoc opts :callback-registry (:sessions (.-callbacks client))
           :check-admission! #(do (check-callback-failure! s) (when check! (check!))))))

(defn- assert-opening! [^Client client ^Session s]
  (when-not (and (:open? @(.-closeHost client))
                 (identical? s (get @(.-sessions client) (.-id s)))
                 (= :opening (:status @(.-lifecycle s))))
    (throw (ex-info "Session setup no longer owns this connection" {:type :session-closed})))
  (check-callback-failure! s))

(defn- opening-request!!
  [^Client client s method params opts on-interrupt]
  (let [reply (locking client
                (when s (assert-opening! client s))
                (rpc/send-request-with-timeout (client-connection client) method params 60000
                                               (if s (session-admission client s opts) opts)))]
    (rpc/response-result! method (rpc/await-bounded-response!! reply on-interrupt))))

(defn- alternate-id? [^String id candidate]
  (and (string? candidate) (not= id candidate) (.equalsIgnoreCase id candidate)))

(defn- reject-alternate-id! [id]
  (throw (ex-info "Use the exact spelling of the session ID"
                  {:type :session-id-case-conflict :session-id id})))

(defn- check-id-spelling! [sessions id]
  ;; A case-insensitive store can target this handle even on an exact map miss.
  ;; Never redirect a mutation to a different spelling.
  (when (and (not (contains? sessions id))
             (some #(alternate-id? id %) (keys sessions)))
    (reject-alternate-id! id)))

(defn- assert-available!
  ([^Client client id] (assert-available! client id @(.-sessions client)))
  ([^Client client id sessions]
   (when-not (:fs-config (.-reverseOwner client)) (check-id-spelling! sessions id))
   (when-let [current (get sessions id)]
     (if (= current ::unconfirmed-cleanup)
       (throw (ex-info "Session ownership is unconfirmed; this client cannot reuse the ID"
                       {:type :session-cleanup-unconfirmed :session-id id}))
       (throw (ex-info "This client already owns a handle for the session"
                       {:type :session-already-open :session-id id}))))))

(defn- lost-reservation [id]
  (ex-info "Session opening no longer owns its reservation" {:type :session-cleanup-unconfirmed :session-id id}))

(defn- register-opening! [^Client client id token config abandoned handle done server-acquired-id]
  (when-not (spec/valid? ::specs/session-id id)
    (throw (ex-info "Runtime did not return a session ID" {:type :missing-session-id})))
  (locking client
    (if server-acquired-id
      (do
        (when-not (identical? done (get @(.-sessions client) done))
          (throw (lost-reservation id)))
        (assert-available! client id)
        ;; Transfer the anonymous reservation before callback admission can fail.
        (swap! (.-sessions client) #(-> % (dissoc done) (assoc id done)))
        (vreset! server-acquired-id id))
      (when-not (identical? done (get @(.-sessions client) id))
        (throw (lost-reservation id))))
    (when-not @abandoned
      (client-connection client)
      (reverse/bind! (.-reverseOwner client) token id)
      (let [turns (turn/new-state)
            reg (callbacks/register! (.-callbacks client) id config (:retired? token)
                                     (fn [cause]
                                       (reverse/drain! (.-reverseOwner client) token)
                                       (turn/close! turns cause)))
            s (Session. client id turns reg (atom {:status :opening :reverse-token token :done done
                                                   :config-directory (:config-directory config)})
                        (AtomicInteger.) (CompletableFuture.))]
        (swap! (.-sessions client) assoc id s)
        (vreset! handle s)))))

(defn- reserve-mismatch! [^Client client requested returned token handle done]
  ;; The requested ID still owns the callbacks and shutdown join. The returned
  ;; ID reserves only the right to detach, never a handle or callback binding.
  (locking client
    (reverse/with-reply-revocation (.-reverseOwner client) (vreset! (:retired? token) true))
    (when (spec/valid? ::specs/session-id returned)
      (swap! (.-sessions client)
             (fn [sessions]
               (let [owner (get sessions requested)]
                 (when-not (and owner (or (identical? handle owner) (identical? done owner)))
                   (throw (lost-reservation returned)))
                 (assert-available! client returned (dissoc sessions requested))
                 (assoc sessions returned token))))
      returned)))

(defn- finish-open! [^Client client ^Session s opened config on-interrupt]
  (doseq [[m p] (options/after-open-requests (.-options client) config opened)]
    (let [result (opening-request!! client s m p {} on-interrupt)]
      (when (and (= "session.options.update" m) (not (true? (:success result))))
        (throw (ex-info "Runtime rejected the session options patch" {:session-id (.-id s)})))))
  (locking client
    (assert-opening! client s)
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException. "Session opening interrupted")))
    (reverse/confirm-open! (:reverse-token @(.-lifecycle s)))
    (swap! (.-lifecycle s)
           (fn [state] (assoc (dissoc state :done) :status :open
                              :info (assoc (select-keys opened [:session-id :workspace-path :transcript-recovery
                                                                :permission-mode-result])
                                           :capabilities (merge-capabilities (:capabilities opened)
                                                                             (get-in state [:info :capabilities])))))))
  s)

(defn- fence-opening! [^Client client ^Session s token done]
  ;; Stop all application callbacks, but reserve the ID until rollback settles.
  (if s
    (let [id (.-id s)]
      (locking client (swap! (.-sessions client) #(if (identical? s (get % id)) (assoc % id done) %)))
      (retire-session! s true))
    (reverse/drain! (.-reverseOwner client) token)))

(defn- opening-cleanup! [^Throwable primary errors action]
  (teardown/cleanup-preserving!
   primary
   #(try (action)
         (catch Throwable failure
           (vswap! errors conj failure)
           (throw failure)))))

(defn- abbreviated-uuid? [id]
  ;; More than eight consecutive hex digits are not a prefix of a dashed UUID.
  (and (string? id) (<= 7 (count id) 35)
       (str/starts-with? "00000000-0000-0000-0000-000000000000" (str/replace id #"(?i)[0-9a-f]" "0"))))

(defn- delete-restriction [^Client client id config-directory]
  (cond
    (:fs-config (.-reverseOwner client))
    (ex-info "Delete application-owned session data through its filesystem provider"
             {:type :session-fs-delete-unsupported :session-id id})
    (not (spec/valid? ::specs/session-id id))
    (ex-info "Expected a nonblank session ID" {:type :invalid-session-id})
    ;; The public delete request has no configDirectory field. Do not guess
    ;; that an override and the client's default identify the same store.
    (and config-directory (not= config-directory (:base-directory (.-options client))))
    (ex-info "An ID-only delete cannot select this session's configuration directory"
             {:type :session-delete-store-mismatch :session-id id})
    ;; Even an acknowledged exact name can expand to a different runtime session.
    (abbreviated-uuid? id)
    (ex-info "Cannot delete a session ID that can be interpreted as a UUID prefix"
             {:type :ambiguous-delete-id :session-id id})))

(defn- rollback-opening! [^Client client ^Session s token done requested acquired-id config-directory uncertain-open? errors ^Throwable primary]
  (let [sessions (.-sessions client)
        conn (.-connection client)
        id (or acquired-id requested)
        mismatch? (and requested acquired-id (not= requested acquired-id))
        reservation (if mismatch? token done)
        restriction (when (and acquired-id (not requested)) (delete-restriction client id config-directory))
        release-method (cond
                         (nil? acquired-id) nil
                         requested "session.detach"
                         (nil? restriction) "session.delete"
                         (not (abbreviated-uuid? id)) "session.detach")
        live-unconfirmed? (volatile! (or uncertain-open? (some? acquired-id)))]
    (opening-cleanup! primary errors #(fence-opening! client s token done))
    (when restriction
      (let [failure (ex-info "Cloud allocation cannot be deleted safely; this client cannot reuse the ID"
                             {:type :session-cleanup-unconfirmed :session-id id
                              :reason (:type (ex-data restriction))} restriction)]
        (.addSuppressed primary failure)
        (vswap! errors conj failure)))
    (when release-method
      (opening-cleanup!
       primary errors
       #(do
          (when-not (identical? reservation (get @sessions id))
            (throw (lost-reservation id)))
          (if (= release-method "session.detach")
            (detach! client id token)
            (let [reply (rpc/send-request-with-timeout conn "session.delete" {:session-id id} 10000
                                                       (release-opts client token))]
              (check-release! "session.delete" id (rpc/await-bounded-response!! reply))))
          (vreset! live-unconfirmed? false))))
    ;; A confirmed or unanswered open can still need its initialized filesystem.
    ;; Only the exact reverse registration owns cleanup, not the stale handle.
    (if (and (or acquired-id (and requested uncertain-open?)) (not mismatch?) @live-unconfirmed?
             (not @(:retired? token)) (reverse/initialized-storage? token))
      (when s (retain-storage! s))
      (opening-cleanup! primary errors #(if s (unregister! s) (reverse/unregister! (.-reverseOwner client) token))))
    ;; A joiner owns its own exception. It must not inherit the opener's
    ;; interrupt or attach later shutdown errors to the opener's exception.
    (let [[cause & more] @errors
          outcome (when cause (ex-info (ex-message cause) (or (ex-data cause) {}) cause))]
      (doseq [failure more] (.addSuppressed outcome failure))
      (locking client
        (swap! sessions dissoc done)
        (when (and mismatch? (identical? done (get @sessions requested)))
          (swap! sessions dissoc requested))
        (when (identical? reservation (get @sessions id))
          (if (or @live-unconfirmed? restriction) (swap! sessions assoc id ::unconfirmed-cleanup) (swap! sessions dissoc id)))
        (settled! done (if outcome {:error outcome} {}))))))

(defn- open-session* [^Client client method id config params]
  (client-connection client)
  (let [reverse-owner (.-reverseOwner client)
        done (a/promise-chan)
        token (reverse/prepare! reverse-owner id config)
        handle (volatile! nil)
        acquired-id (volatile! nil)
        interrupted (volatile! nil)
        ownership-errors (volatile! [])
        register! #(register-opening! client % token config interrupted handle done (when-not id acquired-id))
        abandon! (fn [^InterruptedException cause]
                   (locking client
                     (vreset! interrupted cause)
                     (opening-cleanup! cause ownership-errors #(fence-opening! client @handle token done))))]
    (try
      ;; Reserve before application tracing, but do not install session
      ;; callbacks or hold the client lock while the provider is running.
      (locking client
        (client-connection client)
        (when id (assert-available! client id))
        (swap! (.-sessions client) assoc (or id done) done))
      (let [params (merge (discovery/trace-context!! (.-discovery client)) params)
            _ (when id (reverse/initialize-fs!! reverse-owner token (register! id) config))
            inline-error (volatile! nil)
            accept! (fn [{returned :session-id}]
                      (try
                        (cond (nil? id) (register! returned)
                              (= id returned) (vreset! acquired-id id)
                              :else (vreset! acquired-id (reserve-mismatch! client id returned token @handle done)))
                        (catch Throwable t (vreset! inline-error t))))
            retired? (:retired? token)
            opts (cond-> {:on-response-inline accept!
                          :on-error-inline (fn [_] (reverse/retire-generation! reverse-owner id retired?))}
                   id
                   (assoc :on-late-response (fn [result error]
                                              (when (or error (not= id (get result "sessionId")))
                                                (reverse/retire-generation! reverse-owner id retired?)))))
            opened (opening-request!! client @handle method (reverse/open-params token params) opts abandon!)
            returned (:session-id opened)]
        (when-not (spec/valid? ::specs/session-id returned)
          (throw (ex-info "Runtime did not return a valid session ID" {:type :missing-session-id :method method})))
        (when (and id (not= id returned))
          (let [failure (ex-info "Runtime returned a different session ID"
                                 {:type :session-id-mismatch :session-id id :returned-session-id returned})]
            (when-let [collision @inline-error]
              (.addSuppressed failure collision)
              (vswap! ownership-errors conj collision))
            (throw failure)))
        (when-let [t @inline-error] (throw t))
        (when-let [cause @interrupted] (throw cause))
        (when-not id (reverse/initialize-fs!! reverse-owner token @handle config))
        (let [s (finish-open! client @handle opened config abandon!)]
          (settled! done {})
          s))
      (catch Throwable t
        (let [^Throwable primary (or @interrupted t)
              uncertain-open? (and (nil? @acquired-id)
                                   (= method (:method (ex-data t)))
                                   (or (= :missing-session-id (:type (ex-data t))) (uncertain-response? client t)))]
          (when-not (identical? primary t) (.addSuppressed primary t))
          (when uncertain-open? (vswap! ownership-errors conj t))
          (rollback-opening! client @handle token done id @acquired-id (:config-directory config)
                             uncertain-open? ownership-errors primary)
          (throw primary))))))

(defn open-session! [^Client client config]
  (scope/check! (.-connection client) :open)
  (when-not (map? config)
    (throw (ex-info "Expected a session options map" {:path []})))
  (let [params (options/create-params (.-options client) config)
        id (or (:session-id config) (when-not (:cloud config) (str (UUID/randomUUID))))]
    (open-session* client "session.create" id config (cond-> params id (assoc "sessionId" id)))))

(defn- resolve-prefix! [^Client client id config]
  ;; An exact resident ID may not yet be visible to inventory.
  (assert-available! client id)
  (let [result (rpc/send-request! (client-connection client) "sessions.findByPrefix"
                                  (cond-> {:prefix id}
                                    (:config-directory config) (assoc :configDir (:config-directory config)))
                                  10000)
        resolved (:session-id result)]
    (when-not (and (string? resolved) (str/starts-with? (str/lower-case resolved) (str/lower-case id)))
      (throw (ex-info "No unique session matches the requested prefix" {:session-id id})))
    resolved))

(defn resume-session! [^Client client id config]
  (scope/check! (.-connection client) :resume)
  (let [params (options/resume-params (.-options client) id config)
        ;; Never resolve provider IDs against the host's inventory.
        canonical (if (and (abbreviated-uuid? id)
                           (not (:fs-config (.-reverseOwner client)))
                           (not (get-in config [:state :create-session-fs-provider])))
                    (resolve-prefix! client id config)
                    id)]
    (open-session* client "session.resume" canonical config (assoc params "sessionId" canonical))))

(defn session-id [^Session s] (.-id s))
(defn session-info [^Session s] (:info @(.-lifecycle s)))

(defn- live-connection [^Session s]
  (let [client (.-client s)
        status (:status @(.-lifecycle s))]
    (when-not (and (= :open status) (:open? @(.-closeHost client))
                   (identical? s (get @(.-sessions client) (.-id s))))
      (throw (ex-info "Session is closed" {:type :session-closed :session-id (.-id s)})))
    (check-callback-failure! s)
    (.-connection client)))

(defn- admission-queue [^Session s]
  ;; Registration close also covers failure outside the event router. Dispatch
  ;; cancellation to avoid taking the client lock from the callback registry.
  (let [state (.-lifecycle s)]
    (or (::admission @state)
        (let [queue (java.util.LinkedHashSet.)]
          (a/take! (:closed (.-registration s))
                   (fn [_] (locking (.-client s)
                             (doseq [[_ retired] queue] (a/close! retired))))
                   false)
          (swap! state assoc ::admission queue)
          queue))))

(def ^:private ^:dynamic *trace-slots* [])

(defn- reserve-admission! [^Session s enqueue trace?]
  (let [c (.-client s)
        ^java.util.LinkedHashSet queue (admission-queue s)]
    ;; Only a live reservation in this queue can block its own provider.
    (when (some #(.contains queue %) *trace-slots*)
      (throw (ex-info "A trace provider cannot queue a request behind its own send"
                      {:type :trace-provider-reentry :session-id (.-id s)})))
    (when (>= (.size queue) 64)
      (throw (ex-info "Too many session requests waiting for trace context" {:type :admission-capacity})))
    ;; Retirement cancels the provider even while a different slot owns the FIFO head.
    (let [head (a/chan)
          retired (a/chan)
          slot [head retired]
          raw (volatile! nil)
          remove! (fn []
                    (let [head? (identical? slot (first queue))]
                      (when (and (.remove queue slot) head?)
                        (when-let [[next-head] (first queue)] (a/close! next-head)))))
          headers (when trace?
                    (binding [*trace-slots* (conj *trace-slots* slot)]
                      (discovery/trace-context (.-discovery c) retired)))
          reply (a/go
                  (let [request (try
                                  (a/alts! [retired head] :priority true)
                                  (let [h (when headers (a/<! headers))]
                                    (locking c
                                      (when (.contains queue slot)
                                        (vreset! raw (enqueue h)))))
                                  (catch Exception e e)
                                  (finally (locking c (remove!))))]
                    (if-let [ch (:reply request)] (a/<! ch) request)))]
      (.add queue slot)
      (when (= 1 (.size queue)) (a/close! head))
      {:reply reply
       :cancel! #(locking c
                   (remove!)
                   (a/close! retired)
                   (when-let [cancel (:cancel! @raw)] (cancel)))})))

(defn- submit [^Session s method params timeout-ms opts trace?]
  (let [c (.-client s)
        owner (.-discovery c)
        enqueue (fn [headers]
                  (let [conn (live-connection s)
                        opts (session-admission c s opts)
                        params (if (seq headers) (merge headers params) params)]
                    (if timeout-ms
                      {:reply (rpc/send-request-with-timeout conn method params timeout-ms opts)}
                      (let [ch (rpc/send-request conn method params opts)]
                        {:reply ch :cancel! #(rpc/cancel-request! conn ch)}))))]
    (locking c
      (if (and (discovery/trace-provider owner) (or trace? (seq (::admission @(.-lifecycle s)))))
        (do (live-connection s)
            (when-let [check! (:check-admission! opts)] (check!))
            (reserve-admission! s enqueue trace?))
        (enqueue nil)))))

(defn- session-request [s method params timeout-ms opts]
  (:reply (submit s method params timeout-ms opts false)))

(defn- submit-turn [^Session s message check-deadline!]
  (let [request (submit s "session.sendAndWait" (options/send-params (.-id s) message) nil
                        {:check-admission! check-deadline!} true)]
    (if-let [receipt (callbacks/current-observer-receipt (:token (.-registration s)))]
      (assoc request :observer-delivery receipt)
      request)))

(defn- operation [method f project]
  (try
    (let [ch (f)]
      (a/go
        (try (let [reply (a/<! ch)]
               (when (instance? Throwable reply) (throw reply))
               {:ok (project (rpc/response-result! method reply))})
             (catch Exception e {:error e}))))
    (catch Exception e (a/go {:error e}))))

(defn log! [^Session s message opts]
  (let [method "session.log"]
    (operation method
               #(do
                  (when-not (and (string? message) (map? opts)
                                 (every? #{:level :ephemeral?} (keys opts))
                                 (or (not (contains? opts :level)) (#{:info :warning :error} (:level opts)))
                                 (or (not (contains? opts :ephemeral?)) (boolean? (:ephemeral? opts))))
                    (throw (ex-info "Invalid session log entry" {:method method})))
                  (session-request s method (cond-> {:session-id (.-id s) :message message}
                                              (:level opts) (assoc :level (name (:level opts)))
                                              (contains? opts :ephemeral?) (assoc :ephemeral (:ephemeral? opts)))
                                   10000 {}))
               (constantly nil))))

(def ^:private outbound-form-limit 64)

(defn- check-form-capacity! [^Client c]
  (let [pending (get-in @(:state-atom (.-connection c)) [:connection :pending-requests])]
    (when (>= (bounded-count outbound-form-limit (filter #(= "session.ui.elicitation" (:method %)) (vals pending)))
              outbound-form-limit)
      (throw (ex-info "Too many unanswered forms on this client" {:type :elicitation-capacity :limit outbound-form-limit})))))

(defn elicit! [^Session s {:keys [message requested-schema] :as form}]
  (let [method "session.ui.elicitation"
        check-host! #(when-not (true? (get-in (session-info s) [:capabilities :ui :elicitation]))
                       (throw (ex-info "Host does not support elicitation" {:method method})))]
    (operation method
               #(do
                  (locking (.-client s)
                    (live-connection s)
                    (check-host!))
                  (when (callbacks/nested-form? (.-callbacks (.-client s)))
                    (throw (ex-info "A form handler cannot request another form on its own client"
                                    {:type :recursive-elicitation :session-id (.-id s)})))
                  (when-not (and (= (set (keys form)) #{:message :requested-schema})
                                 (string? message) (map? requested-schema))
                    (throw (ex-info "Expected :message and :requested-schema" {:method method})))
                  (session-request s method {"sessionId" (.-id s) "message" message
                                             "requestedSchema" (util/opaque-json->wire requested-schema)}
                                   nil {:request-owner (:token (.-registration s))
                                        :check-admission! (fn [] (check-host!) (check-form-capacity! (.-client s)))}))
               (fn [result]
                 (when-not (#{"accept" "cancel" "decline"} (:action result))
                   (throw (ex-info "Invalid elicitation result" {:method method})))
                 (update result :action keyword)))))

(defn send! [^Session s message]
  (operation "session.send" #(:reply (submit s "session.send" (options/send-params (.-id s) message) 60000 {} true))
             (fn [{:keys [message-id]}]
               (or message-id (throw (ex-info "Runtime omitted messageId" {}))))))

(defn send-and-wait! [^Session s message]
  (let [opts (if (string? message) {:prompt message} message)]
    (turn/send-and-wait! (.-turns s)
                         #(submit-turn s %1 %2)
                         opts)))

(defn abort! [^Session s]
  (operation "session.abort"
             #(session-request s "session.abort" {:session-id (.-id s)} 10000 {})
             (fn [result]
               (when-not (true? (:success result))
                 (throw (ex-info "Runtime did not abort the session" {:method "session.abort" :result result}))))))

(defn history [^Session s raw?]
  (let [method "session.getMessages"]
    (operation method
               #(session-request s method {:session-id (.-id s)} 60000 {:raw-json? raw?})
               (fn [result]
                 (let [events (get result (if raw? "events" :events))]
                   (when-not (vector? events)
                     (throw (ex-info "Runtime omitted the session history" {:method method})))
                   (if raw? events (mapv turn/normalize-event events)))))))

(defn- client-connection [^Client c]
  (let [{:keys [open? input-error]} @(.-closeHost c)]
    (when-not open? (throw (ex-info "Client is closed" {:type :client-closed})))
    (when input-error (throw input-error))
    (.-connection c)))

(defn list-models [^Client c]
  (try
    (locking c
      (client-connection c)
      (discovery/models (.-discovery c)))
    (catch clojure.lang.ExceptionInfo e (a/go {:error e}))))
(defn lifecycle-events [^Client c] (client-connection c) (turn/subscribe (.-lifecycle c)))

(defn- result-value [outcome]
  (cond
    (contains? outcome :ok) (:ok outcome)
    (:error outcome) (throw (:error outcome))
    :else (throw (ex-info "Operation closed without a result" {:type :missing-result}))))

(defn respond!! [^Session s kind answer]
  (live-connection s)
  (let [reply (callbacks/respond! (.-callbacks (.-client s)) (.-registration s) kind answer)]
    (result-value (rpc/await-bounded-response!! reply))))

(defn status [c]
  (operation "status.get" #(rpc/send-request-with-timeout (client-connection c) "status.get" {} 10000)
             #(select-keys % [:version :protocol-version :host-diagnostics])))

(defn ping [c message]
  (let [method "ping"]
    (operation method
               #(do
                  (when-not (or (nil? message) (string? message))
                    (throw (ex-info "Ping message must be a string" {:path [:message]})))
                  (rpc/send-request-with-timeout (client-connection c) method
                                                 (if (nil? message) {} {:message message}) 10000))
               #(select-keys % [:message :timestamp :protocol-version]))))

(defn last-session-id [c]
  (let [method "session.getLastId"]
    (operation method #(rpc/send-request-with-timeout (client-connection c) method {} 10000) :session-id)))

(defn auth-status [c]
  (operation "auth.getStatus" #(rpc/send-request-with-timeout (client-connection c) "auth.getStatus" {} 10000)
             (fn [info]
               (cond-> (select-keys info [:host :login :status-message])
                 (contains? info :is-authenticated) (assoc :authenticated? (:is-authenticated info))
                 (some? (:auth-type info)) (assoc :auth-type (keyword (:auth-type info)))))))

(defn- metadata-map [record]
  (when record
    (-> record
        (assoc :remote? (:is-remote record))
        (dissoc :is-remote)
        (update :start-time instant)
        (update :modified-time instant))))

(defn session-metadata [c id]
  (let [method "session.getMetadata"]
    (operation method #(do (when-not (spec/valid? ::specs/session-id id)
                             (throw (ex-info "Expected a nonblank session ID" {:type :invalid-session-id})))
                           (rpc/send-request-with-timeout (client-connection c) method {:session-id id} 10000))
               #(metadata-map (:session %)))))

(defn list-sessions [c filters]
  (let [method "session.list"]
    (operation method
               #(do (when-not (and (map? filters)
                                   (every? #{:cwd :git-root :repository :branch} (keys filters))
                                   (every? string? (vals filters)))
                      (throw (ex-info "Invalid session filter" {:type :invalid-filter})))
                    (rpc/send-request-with-timeout (client-connection c) method
                                                   (if (seq filters) {:filter filters} {}) 10000))
               (fn [{:keys [sessions]}]
                 (when-not (vector? sessions)
                   (throw (ex-info "Runtime omitted the session listing" {:method method})))
                 (mapv metadata-map sessions)))))

(defn- confirm-saved-id! [conn id]
  ;; Metadata and create can echo the caller's spelling on a case-insensitive
  ;; store. Only the default store's inventory supplies the persisted spelling.
  (let [{:keys [sessions]} (rpc/send-request! conn "session.list" {} 10000)
        ids (when (vector? sessions) (mapv :session-id sessions))]
    (when (some #(alternate-id? id %) ids) (reject-alternate-id! id))
    (when-not (and (every? string? ids) (= 1 (count (filter #{id} ids))))
      (throw (ex-info "The default session store did not confirm a unique exact session ID"
                      {:type :session-delete-unconfirmed :session-id id})))))

(defn- verify-delete! [conn id done]
  (try
    (confirm-saved-id! conn id)
    (when (.isInterrupted (Thread/currentThread))
      (throw (InterruptedException. "Interrupted before deleting the session")))
    (catch Throwable t
      ;; No delete was submitted. A concurrent client close still owns the
      ;; detach of a live handle, not this caller's read-only lookup failure.
      (settled! done {:delete-not-submitted? true})
      (throw t))))

(defn delete-session!! [^Client c id]
  (scope/check! (.-connection c) :delete)
  (let [sessions (.-sessions c)
        done (a/promise-chan)
        method "session.delete"
        [owner opts]
        (locking c
          (let [conn (client-connection c)
                existing (get @sessions id)]
            (when-let [restriction (delete-restriction c id (when (session? existing)
                                                              (:config-directory @(.-lifecycle ^Session existing))))]
              (throw restriction))
            (check-id-spelling! @sessions id)
            (when (session? existing)
              (scope/check! conn :delete (:token (.-registration ^Session existing))))
            (when (and existing
                       (not (and (session? existing) (= :open (:status @(.-lifecycle ^Session existing))))))
              (throw (ex-info "Session lifecycle operation in progress" {:type :session-busy :session-id id})))
            (if existing (swap! (.-lifecycle ^Session existing) assoc :status :deleting :done done)
                (swap! sessions assoc id done))
            [(or existing done) (when existing (release-opts c (:reverse-token @(.-lifecycle ^Session existing))))]))]
    (try
      (let [conn (.-connection c)]
        (verify-delete! conn id done)
        (try
          (let [reply (rpc/send-request-with-timeout conn method {:session-id id} 10000 opts)]
            (check-release! method id (rpc/await-bounded-response!! reply)))
          (when (session? owner) (finish-acknowledged! owner))
          (settled! done {})
          (catch Throwable t
            ;; Shutdown must see the deleting handle or its completed local retirement.
            (locking c
              (fence-uncertain! c id owner t)
              (settled! done {:error t}))
            (throw t))))
      (finally
        (locking c
          (when (identical? owner (get @sessions id))
            (if (session? owner)
              (when (:open? @(.-closeHost c))
                (swap! (.-lifecycle ^Session owner) #(-> % (assoc :status :open) (dissoc :done))))
              (swap! sessions dissoc id))))))
    (when (.isInterrupted (Thread/currentThread))
      (throw (InterruptedException. "Interrupted while deleting the session")))))

(defn set-model! [^Session s model tuning]
  (let [method "session.model.switchTo"]
    (operation method #(session-request s method (options/model-switch-params (.-id s) model tuning)
                                        60000 {}) (constantly nil))))

(defn subscribe [^Session s]
  (live-connection s)
  (turn/subscribe (.-turns s)))

(defn await!! [result]
  ;; Withdraw an interrupted take without cancelling the underlying operation.
  ;; If delivery already won, return that result and preserve the interrupt.
  (let [cancel (a/chan)
        selected (CompletableFuture.)
        immediate (a/do-alts #(.complete selected %) [result cancel] {:priority true})
        [outcome] (if immediate
                    @immediate
                    (try (.get selected)
                         (catch InterruptedException e
                           (a/close! cancel)
                           (let [[_ port :as choice] (.join selected)]
                             (if (identical? port cancel)
                               (throw e)
                               (do (.interrupt (Thread/currentThread)) choice))))))]
    (result-value outcome)))
