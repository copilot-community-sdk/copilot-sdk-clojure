(ns github.copilot-sdk.workflow-async-behavior-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.workflow :as workflow]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.public-behavior-support :as support]
            [github.copilot-sdk.specs :as specs]))

(use-fixtures :each support/with-piped-client)

(defn- thrown-by [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(deftest async-workflow-facade-routes-all-wrappers-and-arities
  (let [session (sdk/create-session support/*test-client* {:session-id "workflow-session"})
        session-id (sdk/session-id session)
        requests (atom [])
        cases
        [{:label "<run-workflow! default arity"
          :call #(sdk/<run-workflow! session "review")
          :requests [["session.workflow.run"
                      {:sessionId session-id :name "review" :args {} :options {}}]]}
         {:label "<run-workflow! options arity"
          :call #(sdk/<run-workflow! session "review"
                                     {:args {:topic "routing"}
                                      :limits {:max-ai-credits 2}})
          :requests [["session.workflow.run"
                      {:sessionId session-id
                       :name "review"
                       :args {:topic "routing"}
                       :options {:limits {:maxAiCredits 2}}}]]}
         {:label "<resume-workflow! default arity"
          :call #(sdk/<resume-workflow! session "run-1")
          :requests [["session.workflow.resume"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<resume-workflow! options arity"
          :call #(sdk/<resume-workflow! session "run-1"
                                        {:limits {:timeout-seconds 30}})
          :requests [["session.workflow.resume"
                      {:sessionId session-id
                       :runId "run-1"
                       :limits {:timeoutSeconds 30}}]]}
         {:label "<get-workflow-run"
          :call #(sdk/<get-workflow-run session "run-1")
          :requests [["session.workflow.getRun"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<wait-for-workflow-run! default arity"
          :call #(sdk/<wait-for-workflow-run! session "run-1")
          :requests [["session.workflow.getRun"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<list-workflow-runs"
          :call #(sdk/<list-workflow-runs session)
          :requests [["session.workflow.listRuns" {:sessionId session-id}]]}
         {:label "<get-workflow-run-detail"
          :call #(sdk/<get-workflow-run-detail session "run-1")
          :requests [["session.workflow.getRunDetail"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<get-workflow-run-progress default arity"
          :call #(sdk/<get-workflow-run-progress session "run-1")
          :requests [["session.workflow.getRunProgress"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<get-workflow-run-progress options arity"
          :call #(sdk/<get-workflow-run-progress session "run-1"
                                                 {:cursor "next" :limit 2})
          :requests [["session.workflow.getRunProgress"
                      {:sessionId session-id
                       :runId "run-1"
                       :cursor "next"
                       :limit 2}]]}
         {:label "<cancel-workflow-run!"
          :call #(sdk/<cancel-workflow-run! session "run-1")
          :requests [["session.workflow.cancel"
                      {:sessionId session-id :runId "run-1"}]]}]]
    (mock/set-request-hook!
     support/*mock-server*
     (fn [method params]
       (swap! requests conj [method params])))
    (doseq [{:keys [label call] :as case} cases]
      (testing label
        (reset! requests [])
        (is (some? (support/read-value-then-close!! (call))))
        (is (= (:requests case) @requests))))))

(deftest async-wait-facade-preserves-options-arity
  (let [session ::session
        options {:cancel-chan ::cancel-chan :poll-interval-ms 17}
        calls (atom [])
        delivered {:run-id "run-1" :status :completed}]
    (with-redefs-fn
      {#'workflow/<wait-for-run!
       (fn [actual-session run-id actual-options]
         (swap! calls conj [actual-session run-id actual-options])
         (async/to-chan! [delivered]))}
      (fn []
        (is (= delivered
               (support/read-value-then-close!!
                (sdk/<wait-for-workflow-run! session "run-1" options))))))
    (is (= [[session "run-1" options]] @calls))))

(deftest async-workflow-facade-delivers-rpc-failure-then-closes
  (let [session (sdk/create-session support/*test-client* {:session-id "workflow-error-session"})]
    (mock/set-request-hook!
     support/*mock-server*
     (fn [method _]
       (when (= "session.workflow.getRun" method)
         (throw (ex-info "workflow lookup failed" {:code -32050})))))
    (let [value (support/read-value-then-close!!
                 (sdk/<get-workflow-run session "missing"))]
      (is (instance? Throwable value))
      (is (= "workflow lookup failed" (ex-message value))))))

(deftest workflow-resume-maps-known-errors-and-passes-through-unknown-errors
  (let [session (sdk/create-session support/*test-client* {:session-id "workflow-resume-session"})]
    (doseq [[wire-code expected-code]
            [["not_found" :not-found]
             ["non_resumable" :non-resumable]
             ["workflow_run_not_resumable" :workflow-run-not-resumable]
             ["already_active" :already-active]
             ["workflow_already_running" :workflow-already-running]
             ["workflow_limits_invalid" :workflow-limits-invalid]
             ["workflow_session_disposed" :workflow-session-disposed]
             ["workflow_storage_unavailable" :workflow-storage-unavailable]
             ["workflow_storage_corrupt" :workflow-storage-corrupt]
             ["reapproval_declined" nil]
             ["no_approval_provider" nil]
             ["future_workflow_error" nil]]]
      (testing wire-code
        (mock/set-request-hook!
         support/*mock-server*
         (fn [method _]
           (when (= "session.workflow.resume" method)
             (throw (ex-info "resume failed"
                             {:code -32051
                              :data {:code wire-code :detail "preserved"}})))))
        (let [error (thrown-by #(workflow/resume! session "run-1"))]
          (is (instance? clojure.lang.ExceptionInfo error))
          (if expected-code
            (do
              (is (s/valid? ::specs/workflow-resume-error-code expected-code))
              (is (= {:type :workflow-resume-error :code expected-code}
                     (ex-data error))))
            (is (= {:code wire-code :detail "preserved"}
                   (get-in (ex-data error) [:error :data])))))))))

(deftest workflow-overrides-preserve-unlimited-versus-omitted-ceilings
  (let [session (sdk/create-session support/*test-client* {:session-id "workflow-limits"})
        requests (atom [])
        calls [[#(sdk/run-workflow! session "review" %) "session.workflow.run" [:options]]
               [#(support/read-value-then-close!! (sdk/<run-workflow! session "review" %))
                "session.workflow.run" [:options]]
               [#(sdk/resume-workflow! session "run-1" %) "session.workflow.resume" []]
               [#(support/read-value-then-close!! (sdk/<resume-workflow! session "run-1" %))
                "session.workflow.resume" []]]
        cases (concat
               [[{} {}] [{:limits {}} {:limits {}}]]
               (for [[field wire-field] [[:max-concurrent-subagents :maxConcurrentSubagents]
                                         [:max-total-subagents :maxTotalSubagents]
                                         [:timeout-seconds :timeoutSeconds]
                                         [:max-ai-credits :maxAiCredits]]
                     value [nil 1]]
                 [{:limits {field value}} {:limits {wire-field value}}]))]
    (mock/set-request-hook! support/*mock-server*
                            (fn [method params] (swap! requests conj [method params])))
    (doseq [[call method path] calls
            [options expected] cases]
      (testing (str method " " options)
        (reset! requests [])
        (let [result (try (call options)
                          (catch clojure.lang.ExceptionInfo failure failure))
              [[actual-method params]] @requests]
          (is (not (instance? Throwable result)))
          (is (= actual-method method))
          (is (= (select-keys (get-in params path) [:limits]) expected)))))))

(deftest declared-limits-and-invocation-overrides-have-distinct-null-contracts
  (doseq [field [:max-concurrent-subagents :max-total-subagents
                 :timeout-seconds :max-ai-credits]]
    (let [limits {field nil}]
      (is (not (s/valid? ::specs/workflow-limits limits)))
      (is (and (s/get-spec ::specs/workflow-limit-overrides)
               (s/valid? ::specs/workflow-limit-overrides limits)))
      (is (instance? clojure.lang.ExceptionInfo
                     (thrown-by
                      #(workflow/define-workflow
                         {:meta {:name "declared" :description "Declared limits"
                                 :phases [] :limits limits}
                          :run (fn [_] nil)}))))))
  (let [session (sdk/create-session support/*test-client* {:session-id "workflow-invalid-limits"})
        requests (atom [])]
    (mock/set-request-hook! support/*mock-server*
                            (fn [method _] (swap! requests conj method)))
    (doseq [limits [nil false {:unknown nil} {:max-total-subagents false}
                    {:max-total-subagents 0} {:max-concurrent-subagents -1}
                    {:timeout-seconds ##NaN} {:timeout-seconds 2147484}
                    {:max-ai-credits ##Inf} {:max-ai-credits 0.00000000001}]
            call [#(workflow/run! session "review" {:limits %})
                  #(workflow/resume! session "run-1" {:limits %})]]
      (is (not (s/valid? ::specs/workflow-limit-overrides limits)))
      (is (instance? clojure.lang.ExceptionInfo (thrown-by #(call limits)))))
    (is (= @requests []))))
