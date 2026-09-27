(ns github.copilot-sdk.workflow
  "Experimental Dynamic Workflows API."
  (:refer-clojure :exclude [run!])
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [github.copilot-sdk.protocol :as proto]))

(def ^:private terminal-statuses
  #{:completed :halted :paused :cancelled :error})

(def ^:private max-timeout-seconds 2147483.647)
(def ^:private nano-aiu-per-aiu 1000000000)
(def ^:private workflow-limit-keys
  #{:max-concurrent-subagents :max-total-subagents
    :max-ai-credits :timeout-seconds})

(defrecord ^:private WorkflowHandle [meta run])

(def json-null
  "Sentinel for an explicit JSON null workflow result. A nil run result means no result."
  (Object.))

(defn workflow-handle?
  "Return true when value is a workflow handle created by [[define-workflow]]."
  [value]
  (instance? WorkflowHandle value))

(defn terminal-status?
  "Return true when a workflow attempt has settled, including a paused attempt."
  [status]
  (contains? terminal-statuses
             (if (string? status) (keyword status) status)))

(defn- positive-integer! [limits key]
  (when (contains? limits key)
    (let [value (get limits key)]
      (when-not (and (integer? value) (pos? value))
        (throw (ex-info (str "Workflow limit " (pr-str key) " must be a positive integer")
                        {:field key :value value}))))))

(defn- validate-limits! [limits]
  (when-not (map? limits)
    (throw (ex-info "Workflow limits must be a map" {:limits limits})))
  (let [unknown-keys (set/difference (set (keys limits)) workflow-limit-keys)]
    (when (seq unknown-keys)
      (throw (ex-info "Workflow limits contain unknown keys"
                      {:unknown-keys unknown-keys}))))
  (positive-integer! limits :max-concurrent-subagents)
  (positive-integer! limits :max-total-subagents)
  (when (contains? limits :timeout-seconds)
    (let [timeout (:timeout-seconds limits)]
      (when-not (and (number? timeout)
                     (Double/isFinite (double timeout))
                     (pos? timeout)
                     (<= timeout max-timeout-seconds))
        (throw (ex-info
                (str "Workflow limit :timeout-seconds must be positive, finite, and at most "
                     max-timeout-seconds)
                {:field :timeout-seconds :value timeout})))))
  (when (contains? limits :max-ai-credits)
    (let [credits (:max-ai-credits limits)]
      (let [nano-aiu (when (number? credits)
                       (Math/round (* (double credits) nano-aiu-per-aiu)))]
        (when-not (and (number? credits)
                       (Double/isFinite (double credits))
                       (pos? credits)
                       (pos? nano-aiu))
          (throw (ex-info
                  "Workflow limit :max-ai-credits must round to a positive nano-AIU value"
                  {:field :max-ai-credits :value credits}))))))
  limits)

(defn- validate-limit-overrides! [limits]
  ;; Null clears an invocation ceiling, but cannot appear in declared limits.
  (validate-limits!
   (if (map? limits)
     (reduce (fn [values key]
               (if (nil? (get values key))
                 (dissoc values key)
                 values))
             limits
             workflow-limit-keys)
     limits))
  limits)

(defn- validate-meta! [{:keys [name description phases limits] :as meta}]
  (when-not (and (string? name) (not (str/blank? name)))
    (throw (ex-info "Workflow :name must be a non-blank string" {:meta meta})))
  (when-not (and (string? description) (not (str/blank? description)))
    (throw (ex-info "Workflow :description must be a non-blank string" {:meta meta})))
  (when-not (vector? phases)
    (throw (ex-info "Workflow :phases must be a vector" {:meta meta})))
  (let [titles (mapv :title phases)]
    (doseq [[index phase] (map-indexed vector phases)]
      (when-not (and (map? phase)
                     (string? (:title phase))
                     (not (str/blank? (:title phase)))
                     (or (not (contains? phase :detail))
                         (string? (:detail phase))))
        (throw (ex-info "Workflow phases require a non-blank :title and optional string :detail"
                        {:index index :phase phase}))))
    (when-not (= (count titles) (count (distinct titles)))
      (throw (ex-info "Workflow phase title is declared more than once"
                      {:titles titles}))))
  (when (contains? meta :limits)
    (validate-limits! limits))
  meta)

(defn define-workflow
  "Define an experimental Dynamic Workflow and return an immutable handle."
  [{:keys [meta run] :as definition}]
  (when-not (map? definition)
    (throw (ex-info "Workflow definition must be a map" {:definition definition})))
  (validate-meta! meta)
  (when-not (fn? run)
    (throw (ex-info "Workflow :run must be a function" {:definition definition})))
  (->WorkflowHandle meta run))

(defn workflow-meta
  "Return a workflow handle's immutable metadata."
  [handle]
  (when-not (workflow-handle? handle)
    (throw (ex-info "Invalid workflow handle" {:handle handle})))
  (:meta handle))

(defn ^:no-doc workflow-run-function [handle]
  (when-not (workflow-handle? handle)
    (throw (ex-info "Invalid workflow handle" {:handle handle})))
  (:run handle))

(defn ^:no-doc definitions-by-name [handles]
  (reduce
   (fn [definitions handle]
     (let [name (:name (workflow-meta handle))]
       (when (contains? definitions name)
         (throw (ex-info
                 (str "Duplicate workflow name " (pr-str name)
                      ". Workflow names must be unique within a join-session call.")
                 {:name name})))
       (assoc definitions name handle)))
   {}
   (or handles [])))

(defn- connection [session]
  (get @(:state (:client session)) :connection-io))

(defn- normalize-run [{:keys [status] :as run}]
  (cond-> run
    (string? status) (assoc :status (keyword status))))

(declare wait-for-run! resume!)

(defn run!
  "Run a registered workflow by name or handle and return its terminal envelope.

   In :limits, omission preserves a ceiling and nil explicitly removes it."
  ([session name-or-handle]
   (run! session name-or-handle {}))
  ([session name-or-handle {:keys [args limits resume-from-run-id] :as options}]
   (when (contains? options :limits)
     (validate-limit-overrides! limits))
   (if resume-from-run-id
     (resume! session resume-from-run-id (cond-> {} limits (assoc :limits limits)))
     (let [name (if (string? name-or-handle)
                  name-or-handle
                  (:name (workflow-meta name-or-handle)))
           run (-> (proto/send-request!
                    (connection session)
                    "session.workflow.run"
                    {:session-id (:session-id session)
                     :name name
                     :args (if (contains? options :args) args {})
                     :options (cond-> {} limits (assoc :limits limits))})
                   normalize-run)]
       (if (terminal-status? (:status run))
         run
         (wait-for-run! session (:run-id run)))))))

(defn resume!
  "Resume a durable workflow run and return its terminal envelope.

   In :limits, omission preserves a ceiling and nil explicitly removes it."
  ([session run-id]
   (resume! session run-id {}))
  ([session run-id {:keys [limits] :as options}]
   (when (contains? options :limits)
     (validate-limit-overrides! limits))
   (try
     (let [response (proto/send-request!
                     (connection session)
                     "session.workflow.resume"
                     (cond-> {:session-id (:session-id session)
                              :run-id run-id}
                       limits (assoc :limits limits)))
           run (normalize-run (:run response))]
       (if (terminal-status? (:status run))
         run
         (wait-for-run! session run-id)))
     (catch clojure.lang.ExceptionInfo error
       (let [wire-code (get-in (ex-data error) [:error :data :code])
             code (some-> wire-code keyword)]
         (if (contains? #{:not_found :non_resumable :workflow_run_not_resumable
                          :already_active :workflow_already_running :workflow_limits_invalid
                          :workflow_session_disposed :workflow_storage_unavailable
                          :workflow_storage_corrupt}
                        code)
           (throw (ex-info (ex-message error)
                           {:type :workflow-resume-error
                            :code (keyword (str/replace (name code) "_" "-"))}
                           error))
           (throw error)))))))

(defn get-run
  "Read the latest durable envelope for a workflow run."
  [session run-id]
  (normalize-run
   (proto/send-request! (connection session)
                        "session.workflow.getRun"
                        {:session-id (:session-id session)
                         :run-id run-id})))

(defn wait-for-run!
  "Wait until a workflow run reaches a terminal status.

   Options:
   - :cancel-chan      channel whose close/value aborts the wait, not the run
   - :poll-interval-ms polling safety-net interval (default 5000)"
  ([session run-id]
   (wait-for-run! session run-id {}))
  ([session run-id {:keys [cancel-chan poll-interval-ms]
                    :or {poll-interval-ms 5000}}]
   (let [client (:client session)
         session-id (:session-id session)
         event-mult (get-in @(:state client) [:session-io session-id :event-mult])
         event-chan (async/chan (async/sliding-buffer 64))]
     (when-not event-mult
       (throw (ex-info "Session is disconnected" {:session-id session-id})))
     (async/tap event-mult event-chan)
     (try
       (loop [run (get-run session run-id)]
         (if (terminal-status? (:status run))
           run
           (let [poll-chan (async/timeout poll-interval-ms)
                 ports (cond-> [event-chan poll-chan]
                         cancel-chan (conj cancel-chan))
                 [event port] (async/alts!! ports)]
             (cond
               (and cancel-chan (= port cancel-chan))
               (throw (ex-info "Workflow run wait was cancelled"
                               {:type :workflow-wait-cancelled
                                :run-id run-id}))

               (nil? event)
               (if (= port poll-chan)
                 (recur (get-run session run-id))
                 (throw (ex-info "Session event stream closed"
                                 {:session-id session-id :run-id run-id})))

               (and (= :copilot/workflow.run_updated (:type event))
                    (= run-id (get-in event [:data :run-id])))
               (recur (get-run session run-id))

               (= port poll-chan)
               (recur (get-run session run-id))

               :else
               (recur run)))))
       (finally
         (async/untap event-mult event-chan)
         (async/close! event-chan))))))

(defn list-runs
  "List this session's durable workflow runs in creation order."
  [session]
  (mapv normalize-run
        (:runs (proto/send-request! (connection session)
                                    "session.workflow.listRuns"
                                    {:session-id (:session-id session)}))))

(defn get-run-detail
  "Read durable phases, agents, and recent progress for a run."
  [session run-id]
  (proto/send-request! (connection session)
                       "session.workflow.getRunDetail"
                       {:session-id (:session-id session)
                        :run-id run-id}))

(defn get-run-progress
  "Page durable progress for a workflow run."
  ([session run-id]
   (get-run-progress session run-id {}))
  ([session run-id options]
   (proto/send-request! (connection session)
                        "session.workflow.getRunProgress"
                        (merge {:session-id (:session-id session)
                                :run-id run-id}
                               options))))

(defn cancel!
  "Cancel a workflow run and return its terminal envelope."
  [session run-id]
  (normalize-run
   (proto/send-request! (connection session)
                        "session.workflow.cancel"
                        {:session-id (:session-id session)
                         :run-id run-id})))

(defn- async-call [f & args]
  (async/thread
    (try
      (apply f args)
      (catch Throwable error
        error))))

(defn <run!
  ([session name-or-handle] (async-call run! session name-or-handle))
  ([session name-or-handle options] (async-call run! session name-or-handle options)))

(defn <resume!
  ([session run-id] (async-call resume! session run-id))
  ([session run-id options] (async-call resume! session run-id options)))

(defn <get-run [session run-id]
  (async-call get-run session run-id))

(defn <wait-for-run!
  ([session run-id] (async-call wait-for-run! session run-id))
  ([session run-id options] (async-call wait-for-run! session run-id options)))

(defn <list-runs [session]
  (async-call list-runs session))

(defn <get-run-detail [session run-id]
  (async-call get-run-detail session run-id))

(defn <get-run-progress
  ([session run-id] (async-call get-run-progress session run-id))
  ([session run-id options] (async-call get-run-progress session run-id options)))

(defn <cancel! [session run-id]
  (async-call cancel! session run-id))
