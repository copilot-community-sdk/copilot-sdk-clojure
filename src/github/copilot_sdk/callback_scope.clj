(ns ^:no-doc github.copilot-sdk.callback-scope
  "Lifecycle ownership of an application callback on one native connection.")

(def ^:private ^:dynamic *callbacks* [])

(defn- owned-scopes []
  (let [active (filterv #(some? @%) *callbacks*)]
    (if (or (empty? active) (some #(identical? (Thread/currentThread) (:thread @%)) active))
      active
      (filterv #(#{:factory :filesystem :end-hook} (:kind @%)) active))))

(defn- invoke-with [parents conn token kind f]
  (let [scope (volatile! {:connection-id (:connection-id conn) :token token :kind kind
                          :thread (Thread/currentThread)})]
    (binding [*callbacks* (conj parents scope)]
      (try (f) (finally (vreset! scope nil))))))

(defn invoke [conn token kind f]
  (invoke-with [] conn token kind f))

(defn discovery-task [conn f]
  ;; The SDK dispatches on behalf of its caller. Capture ownership before
  ;; changing threads; keep the cells so returned callbacks still expire.
  (let [parents (owned-scopes)]
    (bound-fn [] (invoke-with parents conn nil :discovery f))))

(defn- check-owner! [conn operation target {owner :connection-id :keys [token kind]}]
  (when (identical? owner (:connection-id conn))
    (let [own-session? (and target (identical? target token))]
      (cond
        (= kind :factory)
        (throw (ex-info "A filesystem factory cannot change the lifecycle of its own client"
                        {:type :session-fs-factory-reentry}))
        (= kind :end-hook)
        (if (and (= operation :detach) own-session?)
          :ending
          (throw (ex-info "A session-end hook cannot change another lifecycle on its client"
                          {:type :session-end-hook-reentry})))
        (= operation :shutdown)
        (throw (ex-info "A callback cannot shut down the client that owns its work"
                        {:type (if (#{:reverse :filesystem} kind) :reverse-callback-client-close :callback-client-close)}))
        (and own-session? (not= kind :observer))
        (throw (ex-info "A callback cannot detach or delete the session that owns its decision"
                        {:type :callback-session-close}))))))

(defn check!
  ([conn operation] (check! conn operation nil))
  ([conn operation target]
   (some #(when-let [owner @%] (check-owner! conn operation target owner))
         (owned-scopes))))
