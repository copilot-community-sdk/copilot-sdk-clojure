(ns github.copilot-sdk.integration.workflow-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.workflow :as workflow]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

(deftest test-agent-workflow-definition
  (let [define-workflow (try
                          (requiring-resolve 'github.copilot-sdk.workflow/define-workflow)
                          (catch java.io.FileNotFoundException _ nil))
        terminal-status? (try
                           (requiring-resolve 'github.copilot-sdk.workflow/terminal-status?)
                           (catch java.io.FileNotFoundException _ nil))]
    (is (some? define-workflow))
    (is (some? terminal-status?))
    (when (and define-workflow terminal-status?)
      (let [definition {:meta {:name "review"
                               :description "Review files"
                               :phases [{:title "Review"}]
                               :limits {:max-concurrent-subagents 2
                                        :max-total-subagents 4
                                        :timeout-seconds 30.5
                                        :max-ai-credits 2}}
                        :run (fn [_] {"ok" true})}
            handle (define-workflow definition)]
        (is (= (:meta definition) (:meta handle)))
        (is (true? (terminal-status? :completed)))
        (is (true? (terminal-status? "cancelled")))
        (is (false? (terminal-status? :running)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"declared more than once"
             (define-workflow
               (assoc-in definition [:meta :phases]
                         [{:title "Review"} {:title "Review"}]))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"positive integer"
             (define-workflow
               (assoc-in definition [:meta :limits :max-total-subagents] 0))))
        (doseq [invalid [nil false "2"]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (define-workflow
                         (assoc-in definition
                                   [:meta :limits :max-concurrent-subagents]
                                   invalid)))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"unknown keys"
             (define-workflow
               (assoc-in definition [:meta :limits :max-ai-credit] 2))))))))

(deftest test-agent-workflow-join-wire
  (let [handle (workflow/define-workflow
                 {:meta {:name "review"
                         :description "Review files"
                         :phases [{:title "Review" :detail "Inspect changes"}]
                         :limits {:max-concurrent-subagents 2 :max-ai-credits 1/2}}
                  :run (fn [_] {"ok" true})})
        join-config {:workflows [handle]}
        wire (util/clj->wire
              (#'client/build-resume-session-params "s-1" join-config))]
    (is (s/valid? ::specs/join-session-config join-config))
    (is (not (s/valid? ::specs/resume-session-config join-config)))
    (is (= [{:name "review"
             :description "Review files"
             :phases [{:title "Review" :detail "Inspect changes"}]
             :limits {:maxConcurrentSubagents 2 :maxAiCredits 1/2}}]
           (:workflows wire)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Duplicate workflow name"
         (session/create-session
          *test-client*
          "duplicate-workflow-session"
          {:config {:workflows [handle handle]}})))
    (let [base (sdk/create-session *test-client*
                                   {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id base)
          seen (atom nil)
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (when (= "session.resume" method)
                 (reset! seen params))))
          _ (#'client/resume-session*
             *test-client*
             session-id
             {:on-permission-request sdk/default-join-session-permission-handler
              :workflows [handle]})]
      (is (= "review" (get-in @seen [:workflows 0 :name])))
      (is (= 0.5 (get-in @seen [:workflows 0 :limits :maxAiCredits])))
      (is (workflow/workflow-handle?
           (get-in @(:state *test-client*)
                   [:sessions session-id :workflows "review"]))))))

(deftest test-agent-workflow-run-api
  (let [copilot-session
        (sdk/create-session *test-client*
                            {:on-permission-request sdk/approve-all})
        seen (atom [])]
    (mock/set-request-hook!
     *mock-server*
     (fn [method params]
       (when (= "session.workflow.run" method)
         (swap! seen conj params))))
    (let [run (workflow/run! copilot-session "review"
                             {:args {:snake_key 1}
                              :limits {:max-ai-credits 2}})
          null-args-run (workflow/run! copilot-session "review" {:args nil})
          async-run (<!! (workflow/<run! copilot-session "review"))]
      (is (= :completed (:status run)))
      (is (= {:snake_key 1} (:result run)))
      (is (= {:snapshot_key true} (:snapshot run)))
      (is (= {:snake_key 1} (:args (first @seen))))
      (is (= {:maxAiCredits 2} (get-in (first @seen) [:options :limits])))
      (is (= :completed (:status null-args-run)))
      (is (nil? (:args (second @seen))))
      (is (= :completed (:status async-run))))
    (is (= [{:run-id "run-1" :name "workflow" :status :completed}]
           (workflow/list-runs copilot-session)))
    (let [resumed (workflow/resume! copilot-session "run-1")
          fetched (workflow/get-run copilot-session "run-1")
          cancelled (workflow/cancel! copilot-session "run-1")]
      (is (= {:resume_snapshot_key true} (:snapshot resumed)))
      (is (= "run-1" (:run-id fetched)))
      (is (= {:get_snapshot_key true} (:snapshot fetched)))
      (is (= :cancelled (:status cancelled)))
      (is (= {:cancel_snapshot_key true} (:snapshot cancelled))))
    (is (= [] (:phases (workflow/get-run-detail copilot-session "run-1"))))
    (is (= [] (:records (workflow/get-run-progress copilot-session "run-1"))))
    (doseq [operation [(fn []
                         (workflow/run! copilot-session "review"
                                        {:limits {:max-ai-credits ##NaN}}))
                       (fn []
                         (workflow/resume! copilot-session "run-1"
                                           {:limits {:timeout-seconds false}}))
                       (fn []
                         (workflow/run! copilot-session "review"
                                        {:limits {:max-ai-credit 2}}))]]
      (is (thrown? clojure.lang.ExceptionInfo (operation))))))

(deftest test-agent-workflow-wait-polls-and-cancels-cleanly
  (let [copilot-session
        (sdk/create-session *test-client*
                            {:on-permission-request sdk/approve-all})
        reads (atom 0)]
    (mock/set-request-hook!
     *mock-server*
     (fn [method _params]
       (when (= "session.workflow.getRun" method)
         (let [read-number (swap! reads inc)]
           {::mock/merge-response
            {:status (if (< read-number 3) "running" "completed")}}))))
    (is (= :completed
           (:status (workflow/wait-for-run!
                     copilot-session "run-1" {:poll-interval-ms 10}))))
    (is (<= 3 @reads))

    (reset! reads 0)
    (let [cancel-chan (chan)
          wait-result
          (future
            (try
              (workflow/wait-for-run!
               copilot-session "run-2"
               {:cancel-chan cancel-chan :poll-interval-ms 1000})
              (catch clojure.lang.ExceptionInfo error
                error)))]
      (close! cancel-chan)
      (let [error (deref wait-result 1000 :github.copilot-sdk.integration-test/timeout)]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= :workflow-wait-cancelled (:type (ex-data error))))))))

(deftest test-agent-workflow-pipeline-preserves-fatal-errors-and-fanout
  (testing "fatal pipeline failures survive nesting inside parallel"
    (let [fatal (ex-info "workflow transport failed"
                         {:method "session.workflow.agent"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"workflow transport failed"
           (#'session/workflow-parallel
            [(fn []
               (#'session/workflow-pipeline
                [1]
                (fn [_ _ _] (throw fatal))))])))))

  (testing "pipeline starts all admitted items without chunk barriers"
    (let [started (atom 0)
          all-started (java.util.concurrent.CountDownLatch. 64)
          release (promise)
          execution
          (future
            (#'session/workflow-pipeline
             (vec (range 64))
             (fn [_ item _]
               (swap! started inc)
               (.countDown all-started)
               @release
               item)))]
      (try
        (is (and (.await all-started 1 java.util.concurrent.TimeUnit/SECONDS)
                 (= 64 @started))
            "all pipeline items should start within the deadline")
        (finally
          (deliver release true)))
      (is (= (vec (range 64))
             (deref execution 2000 :github.copilot-sdk.integration-test/timeout))))))

(deftest test-agent-workflow-reverse-execution
  (let [methods (atom [])
        handle
        (workflow/define-workflow
          {:meta {:name "review"
                  :description "Review files"
                  :phases [{:title "Review"}]}
           :run
           (fn [{:keys [args agent step parallel pipeline phase log] :as context}]
             (phase "Review")
             (log "starting")
             (let [agent-result (agent "Review it" {:schema {"type" "object"}})
                   step-result (step "durable" (fn [] {:step_key "cached"}))
                   parallel-result
                   (parallel [(fn [] "first")
                              (fn [] (throw (Exception. "ordinary failure")))])
                   pipeline-result
                   (pipeline [1 2]
                             (fn [_ item _] (* item 2))
                             (fn [previous _ _] (inc previous)))]
               {:input args
                :pause-advertised? (contains? context :pause)
                :agent agent-result
                :step step-result
                :parallel parallel-result
                :pipeline pipeline-result}))})
        session-id "workflow-reverse-session"
        _ (mock/set-request-hook! *mock-server*
                                  (fn [method _params]
                                    (swap! methods conj method)))
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        response
        (mock/send-rpc-request!
         *mock-server*
         "workflow.execute"
         {:sessionId session-id
          :name "review"
          :runId "run-1"
          :executionToken "attempt-1"
          :args {:snake_key 7}})]
    (is (= {:input {:snake_key 7}
            :pause-advertised? false
            :agent {:agent_key "ok"}
            :step {:step_key "cached"}
            :parallel ["first" nil]
            :pipeline [3 5]}
           (get-in response [:result :result])))
    (doseq [method ["session.workflow.agent"
                    "session.workflow.journal.get"
                    "session.workflow.journal.put"
                    "session.workflow.log"]]
      (is (some #{method} @methods)))))

(deftest test-agent-workflow-abort-closes-cancellation-channel
  (let [started (promise)
        handle
        (workflow/define-workflow
          {:meta {:name "wait"
                  :description "Wait for cancellation"
                  :phases []}
           :run (fn [{:keys [cancel-chan]}]
                  (deliver started true)
                  (<!! cancel-chan)
                  {:cancelled true})})
        session-id "workflow-abort-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        execution
        (future
          (mock/send-rpc-request!
           *mock-server*
           "workflow.execute"
           {:sessionId session-id
            :name "wait"
            :runId "run-abort"
            :executionToken "attempt-1"
            :args {}}))]
    (is (true? (deref started 1000 false)))
    (is (= {} (:result
               (mock/send-rpc-request!
                *mock-server*
                "workflow.abort"
                {:sessionId session-id
                 :runId "run-abort"
                 :executionToken "attempt-1"}))))
    (is (= {:cancelled true}
           (get-in (deref execution 1000 :github.copilot-sdk.integration-test/timeout)
                   [:result :result])))))

(deftest test-agent-workflow-abort-before-registration-is-observed
  (let [handler-entered (promise)
        allow-handler (promise)
        observed-cancellation (promise)
        handle
        (workflow/define-workflow
          {:meta {:name "early-abort"
                  :description "Observe an abort that overtakes registration"
                  :phases []}
           :run
           (fn [{:keys [cancel-chan cancelled?]}]
             (let [observed {:cancelled? (cancelled?)
                             :channel-closed?
                             (async-protocols/closed? cancel-chan)}]
               (deliver observed-cancellation observed)
               observed))})
        session-id "workflow-early-abort-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        original-execute @#'session/handle-workflow-execute!]
    (with-redefs-fn
      {#'session/handle-workflow-execute!
       (fn [& args]
         (deliver handler-entered true)
         @allow-handler
         (apply original-execute args))}
      (fn []
        (let [execution
              (future
                (mock/send-rpc-request!
                 *mock-server*
                 "workflow.execute"
                 {:sessionId session-id
                  :name "early-abort"
                  :runId "run-early-abort"
                  :executionToken "attempt-1"
                  :args {}}))]
          (try
            (is (true? (deref handler-entered 1000 false)))
            (let [registered
                  (get-in @(:state *test-client*)
                          [:sessions session-id :workflow-executions
                           "run-early-abort" "attempt-1"])]
              (is (some? registered))
              (is (false? @(:cancelled? registered)))
              (is (= {}
                     (:result
                      (mock/send-rpc-request!
                       *mock-server*
                       "workflow.abort"
                       {:sessionId session-id
                        :runId "run-early-abort"
                        :executionToken "attempt-1"}))))
              (is (true? @(:cancelled? registered)))
              (is (true? (async-protocols/closed? (:cancel-chan registered)))))
            (finally
              (deliver allow-handler true)))
          (is (= {:cancelled? true
                  :channel-closed? true}
                 (deref observed-cancellation
                        1000
                        :github.copilot-sdk.integration-test/timeout)))
          (is (= {:cancelled? true
                  :channel-closed? true}
                 (get-in (deref execution
                                1000
                                :github.copilot-sdk.integration-test/timeout)
                         [:result :result])))
          (is (empty? (get-in @(:state *test-client*)
                              [:sessions session-id :workflow-executions]))))))))

(deftest test-agent-workflow-abort-miss-does-not-retain-state
  (let [handle
        (workflow/define-workflow
          {:meta {:name "abort-miss"
                  :description "No execution is active"
                  :phases []}
           :run (fn [_] {:unreachable true})})
        session-id "workflow-abort-miss-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})]
    (doseq [execution-token ["missing-attempt" nil]]
      (is (= {:result {}}
             (<!! (session/handle-workflow-abort!
                   *test-client*
                   session-id
                   "run-abort-miss"
                   execution-token))))
      (is (empty? (get-in @(:state *test-client*)
                          [:sessions session-id :workflow-executions]))))))

(deftest test-agent-workflow-abort-targets-one-execution-attempt
  (let [invocations (atom 0)
        first-started (promise)
        second-started (promise)
        handle
        (workflow/define-workflow
          {:meta {:name "targeted-abort"
                  :description "Abort one execution attempt"
                  :phases []}
           :run
           (fn [{:keys [cancel-chan]}]
             (let [attempt (swap! invocations inc)]
               (deliver (if (= 1 attempt) first-started second-started) true)
               (<!! cancel-chan)
               {:cancelled-attempt attempt}))})
        session-id "workflow-targeted-abort-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        first-execution
        (future
          (mock/send-rpc-request!
           *mock-server*
           "workflow.execute"
           {:sessionId session-id
            :name "targeted-abort"
            :runId "shared-run"
            :executionToken "attempt-1"
            :args {}}))
        _ (is (true? (deref first-started 1000 false)))
        second-execution
        (future
          (mock/send-rpc-request!
           *mock-server*
           "workflow.execute"
           {:sessionId session-id
            :name "targeted-abort"
            :runId "shared-run"
            :executionToken "attempt-2"
            :args {}}))
        _ (is (true? (deref second-started 1000 false)))
        second-state
        (get-in @(:state *test-client*)
                [:sessions session-id :workflow-executions
                 "shared-run" "attempt-2"])]
    (is (= {} (:result
               (mock/send-rpc-request!
                *mock-server*
                "workflow.abort"
                {:sessionId session-id
                 :runId "shared-run"
                 :executionToken "attempt-1"}))))
    (is (= {:cancelled-attempt 1}
           (get-in (deref first-execution 1000 :github.copilot-sdk.integration-test/timeout)
                   [:result :result])))
    (is (false? @(:cancelled? second-state)))
    (is (false? (async-protocols/closed? (:cancel-chan second-state))))
    (is (= {} (:result
               (mock/send-rpc-request!
                *mock-server*
                "workflow.abort"
                {:sessionId session-id
                 :runId "shared-run"
                 :executionToken "attempt-2"}))))
    (is (= {:cancelled-attempt 2}
           (get-in (deref second-execution 1000 :github.copilot-sdk.integration-test/timeout)
                   [:result :result])))))

(deftest test-agent-workflow-context-failure-returns-error-and-cleans-up-once
  (let [handle
        (workflow/define-workflow
          {:meta {:name "context-failure"
                  :description "Fail while constructing execution context"
                  :phases []}
           :run (fn [_] {"unreachable" true})})
        session-id "workflow-context-failure-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        cleanup-count (atom 0)
        original-remove @#'session/remove-workflow-execution!]
    (with-redefs-fn
      {#'session/workflow-context
       (fn [& _]
         (throw (ex-info "context construction failed" {:stage "context"})))
       #'session/remove-workflow-execution!
       (fn [& args]
         (swap! cleanup-count inc)
         (apply original-remove args))}
      (fn []
        (is (= {:error {:code -32603
                        :message "context construction failed"
                        :data {:stage "context"}}}
               (<!! (session/handle-workflow-execute!
                     *test-client*
                     session-id
                     {:name "context-failure"
                      :run-id "run-context-failure"
                      :execution-token "attempt-1"
                      :args {}}))))))
    (is (= 1 @cleanup-count))
    (is (empty? (get-in @(:state *test-client*)
                        [:sessions session-id :workflow-executions])))))

(deftest test-agent-workflow-overlapping-execution-cleanup-preserves-replacement
  (let [invocations (atom 0)
        first-started (promise)
        first-release (promise)
        second-started (promise)
        handle
        (workflow/define-workflow
          {:meta {:name "overlap"
                  :description "Exercise overlapping execution tokens"
                  :phases []}
           :run
           (fn [{:keys [cancel-chan]}]
             (case (swap! invocations inc)
               1 (do
                   (deliver first-started true)
                   @first-release
                   {:first true})
               2 (do
                   (deliver second-started true)
                   (<!! cancel-chan)
                   {:second true})))})
        session-id "workflow-overlap-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})
        params {:sessionId session-id
                :name "overlap"
                :runId "run-overlap"
                :executionToken "same-token"
                :args {}}
        first-execution
        (future (mock/send-rpc-request! *mock-server* "workflow.execute" params))
        _ (is (true? (deref first-started 1000 false)))
        second-execution
        (future (mock/send-rpc-request! *mock-server* "workflow.execute" params))]
    (is (true? (deref second-started 1000 false)))
    (deliver first-release true)
    (is (= {:first true}
           (get-in (deref first-execution 1000 :github.copilot-sdk.integration-test/timeout)
                   [:result :result])))
    (mock/send-rpc-request! *mock-server* "workflow.abort"
                            {:sessionId session-id
                             :runId "run-overlap"
                             :executionToken "same-token"})
    (is (= {:second true}
           (get-in (deref second-execution 1000 :github.copilot-sdk.integration-test/timeout)
                   [:result :result])))))

(deftest test-agent-workflow-invalid-result-returns-serializable-error
  (doseq [number [##NaN ##Inf ##-Inf]]
    (is (false? (#'session/json-value? number))))
  (let [handle
        (workflow/define-workflow
          {:meta {:name "invalid-result"
                  :description "Return a non-JSON value"
                  :phases []}
           :run (fn [_] (fn [] :not-json))})
        session-id "workflow-invalid-result-session"
        _ (session/create-session *test-client* session-id
                                  {:config {:workflows [handle]}})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Workflow result must be a JSON value"
         (mock/send-rpc-request!
          *mock-server*
          "workflow.execute"
          {:sessionId session-id
           :name "invalid-result"
           :runId "run-invalid"
           :executionToken "attempt-1"
           :args {}}
          :timeout-ms 1000)))))
