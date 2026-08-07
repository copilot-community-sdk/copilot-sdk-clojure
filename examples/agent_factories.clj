(ns agent-factories
  "Experimental Agent Factories API: define a factory, join the parent CLI
   session as a child extension, and service reverse-RPC factory runs until
   the parent session ends.

   An Agent Factory is a named, reusable multi-step routine (with declared
   phases and resource limits) that an extension registers on join. The
   *parent* Copilot CLI session (or another script driving that session)
   triggers runs by name; this process only defines and services them - it
   never calls the run! side of the API itself."
  (:require [github.copilot-sdk :as copilot]
            [clojure.string :as str]))

;; See examples/README.md for usage. This example must run as a child
;; process of a live Copilot CLI session (SESSION_ID set in the
;; environment), so it is intentionally excluded from run-all-examples.sh.

(def defaults
  {:factory-name "clj-example-review"
   :shutdown-timeout-ms 5000})

;; -----------------------------------------------------------------------
;; Factory definition
;; -----------------------------------------------------------------------

(defn- validate-args!
  "Validate the map a caller passes as factory run args (`(:args context)`).
   `define-factory` already validates :meta/:phases/:limits; a factory's
   :run function is responsible for validating its own runtime args."
  [args]
  (when-not (map? args)
    (throw (ex-info "Factory args must be a map" {:args args})))
  (let [topic (:topic args)]
    (when-not (and (string? topic) (not (str/blank? topic)))
      (throw (ex-info "Factory args require a non-blank :topic string" {:args args})))
    topic))

(defn- run-review-factory
  "The factory's :run function. Receives the factory-execution context map
   (:args :agent :step :parallel :phase :log, among others) and returns a
   JSON-safe result. Demonstrates phase, log, agent, step, and parallel."
  [{:keys [args agent step parallel phase log]}]
  (let [topic (validate-args! args)]
    (phase "Plan")
    (log (str "Planning review of: " topic))
    (let [plan (agent (str "In one short sentence, outline how to review: " topic)
                      {:schema {"type" "object"
                                "properties" {"approach" {"type" "string"}}
                                "required" ["approach"]}})]
      (phase "Gather")
      (log "Collecting context and risks in parallel")
      (let [[context risks]
            (parallel [(fn [] (step "fetch-context"
                                    (fn [] {:note (str "Context for " topic)})))
                       (fn [] (step "fetch-risks"
                                    (fn [] {:note (str "Risks for " topic)})))])]
        (phase "Summarize")
        (log "Composing final summary")
        {:topic topic
         :plan plan
         :context context
         :risks risks}))))

(def review-factory
  "A FactoryHandle registered with the parent session in `run` below.
   :limits are optional on `:meta` - included here to show the accepted keys."
  (copilot/define-factory
    {:meta {:name (:factory-name defaults)
            :description "Reviews a topic across plan, gather, and summarize phases."
            :phases [{:title "Plan" :detail "Outline the review approach"}
                     {:title "Gather" :detail "Collect context and risks in parallel"}
                     {:title "Summarize" :detail "Compose the final result"}]
            :limits {:max-concurrent-subagents 2
                     :max-total-subagents 4
                     :timeout-seconds 120
                     :max-ai-credits 5}}
     :run run-review-factory}))

;; -----------------------------------------------------------------------
;; Extension entry point
;; -----------------------------------------------------------------------

(defn- run-with-timeout
  "Run f with a timeout. Returns true if it completed, false otherwise."
  [f timeout-ms]
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (f)
                            (deliver result :ok)
                            (catch Exception _
                              (deliver result :error)))))]
    (.start thread)
    (let [r (deref result timeout-ms :timeout)]
      (when (= r :timeout)
        (try (.interrupt thread) (catch Exception _)))
      (= r :ok))))

(defn- stop-once!
  "Stop client at most once, guarded by the cleaned? atom so the graceful
   exit path (below) and the JVM shutdown hook never both run stop! on an
   already-stopped client."
  [cleaned? client]
  (when (compare-and-set! cleaned? false true)
    (println "Cleaning up client...")
    (let [stopped? (run-with-timeout #(copilot/stop! client)
                                     (:shutdown-timeout-ms defaults))]
      (when-not stopped?
        (run-with-timeout #(copilot/force-stop! client)
                          (:shutdown-timeout-ms defaults))))))

(defn run
  "Join the parent Copilot CLI session, register `review-factory`, and block
   until the parent session ends (or the process is interrupted).

   Requires the SESSION_ID environment variable - see examples/README.md."
  [_]
  (when-not (System/getenv "SESSION_ID")
    (throw (ex-info (str "agent-factories requires SESSION_ID: run this example as a child "
                         "process of a live Copilot CLI extension session (see examples/README.md).")
                    {})))
  (let [{:keys [client session]} (copilot/join-session {:factories [review-factory]})
        parent-session-id (copilot/session-id session)
        cleaned? (atom false)
        done (promise)]
    (println "Registered factory" (str "\"" (:factory-name defaults) "\"")
             "on session" parent-session-id)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (stop-once! cleaned? client))))
    (copilot/on-lifecycle-event client :session.deleted
                                (fn [event]
                                  (when (= (:session-id event) parent-session-id)
                                    (println "Parent session ended.")
                                    (deliver done :session-ended))))
    (println "Waiting for the parent session to trigger a factory run")
    (println "   (e.g. via run-factory! from another script or the CLI itself).")
    (println "   Ctrl-C, or end the parent session, to exit.")
    (try
      (deref done)
      (finally
        (stop-once! cleaned? client)))))
