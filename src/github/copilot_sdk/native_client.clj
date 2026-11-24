(ns ^:no-doc github.copilot-sdk.native-client
  "Owned protocol-4 clients and sessions. Only the native host opens a connection."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.string :as str]
            [github.copilot-sdk.discovery :as discovery]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.native-callbacks :as callbacks]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.reverse :as reverse]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.teardown :as teardown]
            [github.copilot-sdk.turn :as turn]
            [github.copilot-sdk.util :as util])
  (:import [java.lang AutoCloseable]
           [java.time Instant]
           [java.util UUID]))

(declare close-client! close-session! client-connection await!!)

(deftype Client [connection options callbacks reverseOwner discovery lifecycle sessions closeHost]
  AutoCloseable
  (close [this] (close-client! this)))

(deftype Session [^Client client id turns registration lifecycle]
  AutoCloseable
  (close [this] (close-session! this)))

(defn client? [x] (instance? Client x))
(defn session? [x] (instance? Session x))

(defn- cleanup!
  "Attempt every release. Keep the first failure and attach subsequent ones."
  [actions]
  (let [failure (reduce (fn [^Throwable primary action]
                          (try
                            (action)
                            primary
                            (catch Throwable t
                              (when (instance? InterruptedException t)
                                (.interrupt (Thread/currentThread)))
                              (if primary
                                (do (when-not (identical? t primary)
                                      (.addSuppressed primary t))
                                    primary)
                                t))))
                        nil actions)]
    (when failure (throw failure))))

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
          (when-let [^Session s (when (and (session? captured)
                                           (identical? captured (get @(.-sessions client) (:session-id params))))
                                  captured)]
            (try
              (let [event (turn/normalize-event (:event params))]
                (when (= :copilot/capabilities.changed (:type event))
                  (swap! (.-lifecycle s) update-in [:info :capabilities] merge-capabilities (:data event)))
                (turn/publish! (.-turns s) event
                               (callbacks/publish! (.-callbacks client) (.-registration s) event)))
              (catch Exception e
                (swap! (.-lifecycle s) assoc :callback-failure e)
                (turn/close! (.-turns s) e))))
          nil)
        (recur))
      (let [error (get-in @(:state-atom (.-connection client)) [:connection :terminal-error])
            cause (ex-info (or (:message error) "Runtime connection closed")
                           {:type :connection-closed :error error})]
        (turn/close! (.-lifecycle client) cause)
        (doseq [^Session s (filter session? (vals @(.-sessions client)))]
          (turn/close! (.-turns s) cause))))))

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
                        (= method "session.event") (assoc ::session (get @sessions (:session-id params)))))
            conn (rpc/connect in out (atom {:connection (rpc/initial-connection-state)
                                            :options {:prepare-notification prepare}}))
            _ (vswap! rollback conj #(release-connection! conn))
            owner (callbacks/open! conn (dissoc opts :session-fs))
            _ (vswap! rollback conj #(callbacks/close! owner))
            reverse-owner (reverse/open! conn opts run-ui-action!)
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
        (let [client (Client. conn (select-keys opts [:mode]) owner reverse-owner discover (turn/new-state) sessions
                              (atom {:open? true :release! close!}))]
          (route! client)
          client))
      (catch Throwable t
        (doseq [release (rseq @rollback)]
          (teardown/cleanup-preserving! t release))
        (throw t)))))

(defn- detach! [conn id]
  (let [reply (rpc/send-request-with-timeout conn "session.detach" {:session-id id} 10000)
        result (rpc/response-result! "session.detach" (rpc/await-bounded-response!! reply))]
    (when-not (true? (:success result))
      (throw (ex-info "Runtime did not detach the session" {:session-id id})))))

(defn- await-transition!!
  ([done] (await-transition!! done (a/timeout 65000)))
  ([done deadline]
   (let [[outcome port] (a/alts!! [done deadline] :priority true)]
     (when (= port deadline)
       (throw (ex-info "Timed out waiting for lifecycle transition" {:type :lifecycle-timeout})))
     (when-let [error (:error outcome)] (throw error)))))

(defn- settled! [done outcome]
  (a/offer! done outcome)
  (a/close! done))

(defn- begin-detach [^Client client deadline entry]
  (let [conn (.-connection client)
        {:keys [status done reverse-token]} (when (session? entry) @(.-lifecycle ^Session entry))]
    (when (= :opening status)
      ;; Cancel a blocked filesystem factory without dropping ownership of
      ;; the ID. The opener alone can settle its create/resume and rollback.
      (reverse/unregister! (.-reverseOwner client) reverse-token))
    (cond
      done #(await-transition!! done deadline)
      (satisfies? ap/ReadPort entry) #(await-transition!! entry deadline)
      (= :open status)
      (let [id (.-id ^Session entry)
            reply (rpc/send-request conn "session.detach" {:session-id id})]
        #(let [[v port] (a/alts!! [reply deadline] :priority true)]
           (when (= port deadline)
             (rpc/cancel-request! conn reply)
             (throw (ex-info "Session detach deadline expired" {:session-id id})))
           (when-not (true? (:success (rpc/response-result! "session.detach" v)))
             (throw (ex-info "Runtime did not detach the session" {:session-id id}))))))))

(defn- detach-for-shutdown! [^Client c]
  ;; Enqueue each detach before waiting for any reply. All share one deadline.
  (let [waits (locking c
                (into [] (keep (partial begin-detach c (a/timeout 10000))) (vals @(.-sessions c))))]
    (cleanup! waits)))

(defn- unregister! [^Session s]
  (let [client (.-client s)
        [{:keys [status reverse-token]} _] (reset-vals! (.-lifecycle s) {:status :closed})]
    (when-not (= status :closed)
      (cleanup! [#(turn/close! (.-turns s) (ex-info "Session closed" {:type :session-closed :session-id (.-id s)}))
                 #(callbacks/unregister! (.-callbacks client) (.-registration s))
                 #(when reverse-token (reverse/unregister! (.-reverseOwner client) reverse-token))
                 #(swap! (.-sessions client)
                         (fn [sessions]
                           (if (identical? s (get sessions (.-id s))) (dissoc sessions (.-id s)) sessions)))]))))

(defn- uncertain-response? [^Client client failure]
  (or (::rpc/timeout (meta (:error (ex-data failure))))
      (not (get-in @(:state-atom (.-connection client)) [:connection :running?]))))

(defn- fence-uncertain! [^Client client id owner failure]
  (when (uncertain-response? client failure)
    (let [[before _] (swap-vals! (.-sessions client)
                                 #(if (identical? owner (get % id)) (assoc % id ::unconfirmed-cleanup) %))]
      (when (and (identical? owner (get before id)) (session? owner))
        (teardown/cleanup-preserving! failure #(unregister! owner))))))

(defn close-session! [^Session s]
  (let [client (.-client s)
        _ (reverse/assert-lifecycle-allowed! (.-reverseOwner client))
        [role done]
        (locking client
          (when (identical? s (get @(.-sessions client) (.-id s)))
            (let [{:keys [status done]} @(.-lifecycle s)]
              (if-let [shutting-down (:done @(.-closeHost client))]
                [:wait shutting-down]
                (case status
                  (:opening :deleting) (throw (ex-info "Session lifecycle operation in progress"
                                                       {:type :session-busy :session-id (.-id s)}))
                  :closing [:wait done]
                  :open (let [d (a/promise-chan)]
                          (swap! (.-lifecycle s) assoc :status :closing :done d)
                          [:detach d])
                  nil)))))]
    (case role
      :wait (await-transition!! done)
      :detach (do
                (try
                  (detach! (.-connection client) (.-id s))
                  (unregister! s)
                  (settled! done {})
                  (catch Throwable t
                    (fence-uncertain! client (.-id s) s t)
                    (locking client
                      (when (and (:open? @(.-closeHost client))
                                 (identical? s (get @(.-sessions client) (.-id s))))
                        (swap! (.-lifecycle s) #(-> % (assoc :status :open) (dissoc :done)))))
                    (settled! done {:error t})
                    (throw t)))
                (when (.isInterrupted (Thread/currentThread))
                  (throw (InterruptedException. "Interrupted while detaching the session"))))
      nil)))

(defn close-client!
  ([client] (close-client! client true))
  ([^Client client graceful?]
   (reverse/assert-lifecycle-allowed! (.-reverseOwner client))
   (let [slot (.-closeHost client)
         [role done state] (locking client
                             (when-let [state @slot]
                               (if-let [d (:done state)]
                                 [:wait d]
                                 (let [d (a/promise-chan)]
                                   (reset! slot (assoc state :open? false :done d))
                                   [:release d state]))))]
     (case role
       :wait (await-transition!! done)
       :release
       (try
         (cleanup!
          (concat
           (when (:open? state)
             (concat (when graceful? [#(detach-for-shutdown! client)])
                     (map (fn [^Session s] #(unregister! s))
                          (filter session? (vals @(.-sessions client))))
                     (when graceful? [#(rpc/send-request! (.-connection client) "runtime.shutdown" {} 10000)])
                     [#(turn/close! (.-lifecycle client) (ex-info "Client closed" {:type :client-closed}))
                      #(discovery/close! (.-discovery client))
                      #(reverse/close! (.-reverseOwner client))
                      #(callbacks/close! (.-callbacks client))
                      #(release-connection! (.-connection client))]))
           [#(do ((:release! state)) (reset! slot nil))]))
         (settled! done {})
         nil
         (catch Throwable t
           (locking client (when @slot (reset! slot (select-keys state [:release!]))))
           (settled! done {:error t})
           (throw t)))
       nil))))

(defn- assert-opening! [^Client client ^Session s]
  (when-not (and (:open? @(.-closeHost client))
                 (identical? s (get @(.-sessions client) (.-id s)))
                 (= :opening (:status @(.-lifecycle s))))
    (throw (ex-info "Session setup no longer owns this connection" {:type :session-closed}))))

(defn- opening-request!!
  [^Client client s method params opts on-interrupt]
  (let [reply (locking client
                (when s (assert-opening! client s))
                (rpc/send-request-with-timeout (client-connection client) method params 60000 opts))]
    (rpc/response-result! method (rpc/await-bounded-response!! reply on-interrupt))))

(defn- assert-available! [^Client client id]
  (when-let [current (get @(.-sessions client) id)]
    (if (= current ::unconfirmed-cleanup)
      (throw (ex-info "Session ownership is unconfirmed; this client cannot reuse the ID"
                      {:type :session-cleanup-unconfirmed :session-id id}))
      (throw (ex-info "This client already owns a handle for the session"
                      {:type :session-already-open :session-id id})))))

(defn- register-opening! [^Client client id token config abandoned handle done]
  (when-not (and (string? id) (not (str/blank? id)))
    (throw (ex-info "Runtime did not return a session ID" {:type :missing-session-id})))
  (locking client
    (assert-available! client id)
    ;; Reserve first: callback admission itself can fail (e.g. observer
    ;; capacity). Retain the ID until the opening thread finishes rollback.
    (swap! (.-sessions client) assoc id done)
    (when-not @abandoned
      (client-connection client)
      (reverse/bind! (.-reverseOwner client) token id)
      (let [reg (callbacks/register! (.-callbacks client) id config)
            s (Session. client id (turn/new-state) reg (atom {:status :opening :reverse-token token :done done}))]
        (swap! (.-sessions client) assoc id s)
        (vreset! handle s)))))

(defn- finish-open! [^Client client ^Session s opened config on-interrupt]
  (doseq [[m p] (options/after-open-requests (.-options client) config opened)]
    (let [result (opening-request!! client s m p {} on-interrupt)]
      (when (and (= "session.options.update" m) (not (true? (:success result))))
        (throw (ex-info "Runtime rejected the session options patch" {:session-id (.-id s)})))))
  (locking client
    (assert-opening! client s)
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException. "Session opening interrupted")))
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
      (unregister! s))
    (reverse/unregister! (.-reverseOwner client) token)))

(defn- rollback-opening! [^Client client ^Session s token done id release-method uncertain-open? primary]
  (let [sessions (.-sessions client)
        conn (.-connection client)]
    (teardown/cleanup-preserving! primary #(fence-opening! client s token done))
    (when (and release-method (identical? done (get @sessions id)))
      (teardown/cleanup-preserving!
       primary
       #(try
          (if (= release-method "session.detach")
            (detach! conn id)
            (let [reply (rpc/send-request-with-timeout conn "session.delete" {:session-id id} 10000)]
              (when-not (true? (:success (rpc/response-result! "session.delete" (rpc/await-bounded-response!! reply))))
                (throw (ex-info "Runtime did not delete the session" {:session-id id})))))
          (catch Throwable failure
            (fence-uncertain! client id done failure)
            (throw failure)))))
    (swap! sessions #(if (identical? done (get % id))
                       (if uncertain-open? (assoc % id ::unconfirmed-cleanup) (dissoc % id))
                       %))))

(defn- open-session* [^Client client method id config params]
  (client-connection client)
  (let [params (merge (discovery/trace-context!! (.-discovery client)) params)
        reverse-owner (.-reverseOwner client)
        done (a/promise-chan)
        token (reverse/prepare! reverse-owner id config)
        handle (volatile! nil)
        acknowledged (volatile! nil)
        interrupted (volatile! nil)
        register! #(register-opening! client % token config interrupted handle done)
        abandon! (fn [^InterruptedException cause]
                   (locking client
                     (vreset! interrupted cause)
                     (try (fence-opening! client @handle token done)
                          (catch Throwable release-error (.addSuppressed cause release-error)))))]
    (try
      (when id (reverse/initialize-fs!! reverse-owner token (register! id) config))
      (let [inline-error (volatile! nil)
            opts (when-not id
                   {:on-response-inline (fn [{:keys [session-id]}]
                                          (vreset! acknowledged session-id)
                                          (try (register! session-id)
                                               (catch Throwable t (vreset! inline-error t))))})
            opened (opening-request!! client @handle method (reverse/open-params token params) opts abandon!)]
        (when-let [t @inline-error] (throw t))
        (when (and id (not= id (:session-id opened)))
          (throw (ex-info "Runtime returned a different session ID" {:type :session-id-mismatch :session-id id})))
        (vreset! acknowledged (:session-id opened))
        (when-let [cause @interrupted] (throw cause))
        (when-not id (reverse/initialize-fs!! reverse-owner token @handle config))
        (let [s (finish-open! client @handle opened config abandon!)]
          (settled! done {})
          s))
      (catch Throwable t
        (let [^Throwable primary (or @interrupted t)
              uncertain-open? (and id (= method (:method (ex-data t))) (uncertain-response? client t))]
          (when-not (identical? primary t) (.addSuppressed primary t))
          (try
            (rollback-opening! client @handle token done (or id @acknowledged)
                               (when @acknowledged (if id "session.detach" "session.delete"))
                               uncertain-open? primary)
            (finally (settled! done (if (or uncertain-open? (seq (.getSuppressed primary))) {:error primary} {}))))
          (throw primary))))))

(defn open-session! [^Client client config]
  (reverse/assert-lifecycle-allowed! (.-reverseOwner client))
  (when-not (map? config)
    (throw (ex-info "Expected a session options map" {:path []})))
  (let [params (options/create-params (.-options client) config)
        id (or (:session-id config) (when-not (:cloud config) (str (UUID/randomUUID))))]
    (open-session* client "session.create" id config (cond-> params id (assoc "sessionId" id)))))

(defn- abbreviated-uuid? [id]
  ;; More than eight consecutive hex digits are not a prefix of a dashed UUID.
  (and (string? id) (<= 7 (count id) 35)
       (str/starts-with? "00000000-0000-0000-0000-000000000000" (str/replace id #"(?i)[0-9a-f]" "0"))))

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
  (reverse/assert-lifecycle-allowed! (.-reverseOwner client))
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
        {:keys [status callback-failure]} @(.-lifecycle s)]
    (when-not (and (= :open status) (:open? @(.-closeHost client))
                   (identical? s (get @(.-sessions client) (.-id s))))
      (throw (ex-info "Session is closed" {:type :session-closed :session-id (.-id s)})))
    (when callback-failure (throw callback-failure))
    (.-connection client)))

(defn- admission-queue [^Session s]
  ;; All queue access is under the client lock. Detach only signals the lane.
  (let [state (.-lifecycle s)]
    (or (::admission @state)
        (let [queue (java.util.LinkedHashSet.)]
          (add-watch state ::admission
                     (fn [_ _ _ v]
                       (when (= :closed (:status v))
                         (locking (.-client s)
                           (doseq [[_ retired] queue] (a/close! retired))))))
          (swap! state assoc ::admission queue)
          queue))))

(defn- reserve-admission! [^Session s enqueue trace?]
  (let [c (.-client s)
        ^java.util.LinkedHashSet queue (admission-queue s)]
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
          headers (when trace? (discovery/trace-context (.-discovery c) retired))
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
                        params (if (seq headers) (merge headers params) params)]
                    (if timeout-ms
                      {:reply (rpc/send-request-with-timeout conn method params timeout-ms opts)}
                      (let [ch (rpc/send-request conn method params opts)]
                        {:reply ch :cancel! #(rpc/cancel-request! conn ch)}))))]
    (locking c
      (if (and (:on-trace owner) (or trace? (seq (::admission @(.-lifecycle s)))))
        (do (live-connection s) (reserve-admission! s enqueue trace?))
        (enqueue nil)))))

(defn- session-request [s method params timeout-ms opts]
  (:reply (submit s method params timeout-ms opts false)))

(defn- submit-turn [^Session s message]
  (submit s "session.sendAndWait" (options/send-params (.-id s) message) nil {} true))

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

(defn elicit! [^Session s {:keys [message requested-schema] :as form}]
  (let [method "session.ui.elicitation"]
    (operation method
               #(do
                  (when-not (true? (get-in (session-info s) [:capabilities :ui :elicitation]))
                    (throw (ex-info "Host does not support elicitation" {:method method})))
                  (when-not (and (= (set (keys form)) #{:message :requested-schema})
                                 (string? message) (map? requested-schema))
                    (throw (ex-info "Expected :message and :requested-schema" {:method method})))
                  (session-request s method {"sessionId" (.-id s) "message" message
                                             "requestedSchema" (util/opaque-json->wire requested-schema)} 60000 {}))
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
                         #(submit-turn s %)
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
  (when-not (:open? @(.-closeHost c)) (throw (ex-info "Client is closed" {:type :client-closed})))
  (.-connection c))

(defn list-models [^Client c] (discovery/models (.-discovery c)))
(defn lifecycle-events [^Client c] (client-connection c) (turn/subscribe (.-lifecycle c)))

(defn respond!! [^Session s kind answer]
  (live-connection s)
  (await!! (callbacks/respond! (.-callbacks (.-client s)) (.-registration s) kind answer)))

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
    (operation method #(rpc/send-request-with-timeout (client-connection c) method
                                                      {:session-id id} 10000)
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

(defn delete-session!! [^Client c id]
  (reverse/assert-lifecycle-allowed! (.-reverseOwner c))
  (when (:fs-config (.-reverseOwner c))
    (throw (ex-info "Delete application-owned session data through its filesystem provider"
                    {:type :session-fs-delete-unsupported :session-id id})))
  (when-not (and (string? id) (not (str/blank? id)))
    (throw (ex-info "Expected a nonblank session ID" {:type :invalid-session-id})))
  ;; The public delete RPC can expand a short ID on the runtime's side,
  ;; even if that exact ID existed when we last looked.
  (when (abbreviated-uuid? id)
    (throw (ex-info "Cannot delete a session ID that can be interpreted as a UUID prefix"
                    {:type :ambiguous-delete-id :session-id id})))
  (let [sessions (.-sessions c)
        done (a/promise-chan)
        method "session.delete"
        [owner reply]
        (locking c
          (let [conn (client-connection c)
                existing (get @sessions id)
                owner (or existing done)]
            (when (and existing
                       (not (and (session? existing) (= :open (:status @(.-lifecycle ^Session existing))))))
              (throw (ex-info "Session lifecycle operation in progress" {:type :session-busy :session-id id})))
            (if existing (swap! (.-lifecycle ^Session existing) assoc :status :deleting :done done)
                (swap! sessions assoc id owner))
            [owner (rpc/send-request-with-timeout conn method {:session-id id} 10000)]))]
    (try
      (let [result (rpc/response-result! method (rpc/await-bounded-response!! reply))]
        (when-not (true? (:success result))
          (throw (ex-info "Runtime did not delete the session" {:session-id id})))
        (when (session? owner) (unregister! owner))
        (settled! done {}))
      (catch Throwable t
        (fence-uncertain! c id owner t)
        (settled! done {:error t})
        (throw t))
      (finally
        (locking c
          (when (identical? owner (get @sessions id))
            (if (session? owner)
              (swap! (.-lifecycle ^Session owner) #(-> % (assoc :status :open) (dissoc :done)))
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
  (let [outcome (a/<!! result)]
    (cond
      (contains? outcome :ok) (:ok outcome)
      (:error outcome) (throw (:error outcome))
      :else (throw (ex-info "Operation closed without a result" {:type :missing-result})))))
