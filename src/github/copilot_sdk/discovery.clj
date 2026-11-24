(ns ^:no-doc github.copilot-sdk.discovery
  "Model discovery and per-request W3C trace context for one native connection."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.spec.alpha :as s]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.specs :as specs])
  (:import [java.util.concurrent FutureTask RejectedExecutionException SynchronousQueue
            ThreadFactory ThreadPoolExecutor TimeUnit]))

(def ^:private timeout-ms 10000)

(defn- closed-error [] (ex-info "Discovery connection is closed" {:type :connection-closed}))

(defn- ready [value]
  (let [ch (a/chan 1)]
    (a/offer! ch value)
    (a/close! ch)
    ch))

(defn close!
  "Discard the cache, wake waiters and interrupt providers. A provider that
  ignores interruption cannot block close or publish a late result."
  [{:keys [stop conn cache ^ThreadPoolExecutor workers]}]
  (locking cache
    (when-not (= ::closed @cache)
      (a/close! stop)
      (remove-watch (:state-atom conn) stop)
      (reset! cache ::closed)
      (when workers (.shutdownNow workers))))
  nil)

(defn open!
  "Bind discovery to this connection identity. Only configured providers need
  an executor. It starts workers on demand and never queues application work."
  [conn opts]
  (let [on-list (get-in opts [:models :on-list])
        on-trace (get-in opts [:observability :on-get-trace-context])
        workers (when (or on-list on-trace)
                  (ThreadPoolExecutor.
                   0 4 30 TimeUnit/SECONDS (SynchronousQueue.)
                   (reify ThreadFactory
                     (newThread [_ r] (doto (Thread. r "copilot-discovery") (.setDaemon true))))))
        stop (a/chan)
        owner {:conn conn :cache (atom nil) :on-list on-list :on-trace on-trace
               :workers workers :stop stop}
        connected? (fn [{:keys [connection]}]
                     (and (:running? connection)
                          (identical? (:connection-id conn) (:connection-id connection))))]
    ;; A state watch can run under the protocol lock. Signal only; never run
    ;; teardown or wait for application code on the reader.
    (a/take! stop (fn [_] (close! owner)) false)
    (add-watch (:state-atom conn) stop
               (fn [_ _ _ state] (when-not (connected? state) (a/close! stop))))
    (when-not (connected? @(:state-atom conn))
      (close! owner)
      (throw (closed-error)))
    owner))

(defn- invoke-provider [{:keys [stop ^ThreadPoolExecutor workers]} provider project retired]
  (let [done (a/chan 1)
        cancel (a/chan)
        deadline (a/timeout timeout-ms)
        task (FutureTask.
              ^Runnable
              (bound-fn []
                (try
                  (let [v (provider)
                        v (if (satisfies? ap/ReadPort v)
                            (first (a/alts!! [cancel v] :priority true)) v)]
                    (if (instance? Throwable v) (throw v)
                        (a/offer! done {:ok (project v)})))
                  (catch Throwable t (a/offer! done {:error t}))
                  (finally (a/close! cancel))))
              nil)]
    (try
      (.execute workers task)
      (a/go
        (let [[result port] (a/alts! (if retired [stop retired done deadline] [stop done deadline]) :priority true)]
          (if (= port done)
            result
            (do (a/close! cancel)
                (.cancel task true)
                (cond
                  (= port retired) {:ok {}}
                  (= port stop) {:error (closed-error)}
                  :else {:error (ex-info "Discovery provider timed out" {:type :provider-timeout})})))))
      (catch RejectedExecutionException e
        (ready {:error (if (ap/closed? stop) (closed-error)
                           (ex-info "Discovery providers are busy" {:type :provider-capacity} e))})))))

(defn- model-data? [v]
  (cond (map? v) (and (every? keyword? (keys v)) (every? model-data? (vals v)))
        (vector? v) (every? model-data? v)
        :else (s/valid? ::specs/json-scalar v)))

(defn- model-vector [models]
  ;; Reject mutable or deferred provider values before publishing a shared result.
  (when-not (and (vector? models)
                 (every? #(and (map? %) (string? (:id %)) (string? (:name %))
                               (map? (:capabilities %))
                               (or (not (contains? % :metadata))
                                   (s/valid? ::specs/json-object (:metadata %)))
                               (model-data? (dissoc % :metadata))) models))
    (throw (ex-info "Expected a vector of model records" {:type :invalid-models})))
  models)

(defn- rpc-models [{:keys [conn stop]}]
  (let [method "models.list"
        response (rpc/send-request conn method {})]
    (a/go
      (try
        (let [[reply port] (a/alts! [stop response (a/timeout timeout-ms)] :priority true)]
          (when-not (= port response)
            (throw (if (= port stop) (closed-error) (ex-info "Model listing timed out" {:method method}))))
          (let [models (:models (rpc/response-result! method reply))]
            (when-not (vector? models)
              (throw (ex-info "Runtime omitted the model listing" {:method method})))
            {:ok (model-vector
                  (mapv #(-> %
                             (update-in [:capabilities :supports] (fnil identity {}))
                             (update-in [:capabilities :limits] (partial merge {:max-context-window-tokens 0})))
                        models))}))
        (catch Throwable t {:error t})
        (finally (rpc/cancel-request! conn response))))))

(defn models
  "Return a fresh one-shot outcome channel. Calls share one pending fetch or
  immutable result until disconnect. A failed fetch is not cached."
  [{:keys [on-list cache stop] :as owner}]
  (let [shared (locking cache
                 (cond
                   (ap/closed? stop) (ready {:error (closed-error)})
                   @cache @cache
                   :else
                   (let [slot (a/promise-chan)
                         source (if on-list (invoke-provider owner on-list model-vector nil) (rpc-models owner))]
                     (reset! cache slot)
                     (a/go
                       (let [value (a/<! source)
                             outcome (if (ap/closed? stop) {:error (closed-error)} value)]
                         (when (:error outcome) (compare-and-set! cache slot nil))
                         (a/>! slot outcome)
                         (a/close! slot)))
                     slot)))]
    ;; A caller can close its result without poisoning another call or the cache.
    (a/go (a/<! shared))))

(defn- headers [m]
  (if (and (map? m) (every? #{:traceparent :tracestate} (keys m)) (every? string? (vals m)))
    (update-keys m name)
    {}))

(defn trace-context
  "Return a channel of wire headers for one create, resume or send. Provider
  errors, nil and overload omit context; they must not fail the application
  request. Clojure dynamic bindings are captured at the call site. Closing
  retired interrupts the provider and withdraws its take from a returned channel."
  ([owner] (trace-context owner nil))
  ([{:keys [on-trace stop] :as owner} retired]
   (if (and on-trace (not (ap/closed? stop)) (not (and retired (ap/closed? retired))))
     (let [source (invoke-provider owner on-trace headers retired)]
       (a/go
         (let [{:keys [ok error]} (a/<! source)]
           (when error (log/debug "Trace context provider unavailable"))
           (or ok {}))))
     (ready {}))))

(defn trace-context!!
  "Blocking form for synchronous session creation/resume. Call outside locks."
  [owner]
  (if (:on-trace owner) (a/<!! (trace-context owner)) {}))
