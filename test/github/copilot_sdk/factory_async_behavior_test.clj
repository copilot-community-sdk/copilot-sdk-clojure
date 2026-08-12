(ns github.copilot-sdk.factory-async-behavior-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.public-behavior-support :as support]))

(use-fixtures :each support/with-piped-client)

(defn- thrown-by [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(deftest async-factory-facade-routes-all-wrappers-and-arities
  (let [session (sdk/create-session support/*test-client* {:session-id "factory-session"})
        session-id (sdk/session-id session)
        requests (atom [])
        cases
        [{:label "<run-factory! default arity"
          :call #(sdk/<run-factory! session "review")
          :requests [["session.factory.run"
                      {:sessionId session-id :name "review" :args {} :options {}}]]}
         {:label "<run-factory! options arity"
          :call #(sdk/<run-factory! session "review"
                                    {:args {:topic "routing"}
                                     :limits {:max-ai-credits 2}})
          :requests [["session.factory.run"
                      {:sessionId session-id
                       :name "review"
                       :args {:topic "routing"}
                       :options {:limits {:maxAiCredits 2}}}]]}
         {:label "<resume-factory! default arity"
          :call #(sdk/<resume-factory! session "run-1")
          :requests [["session.factory.resume"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<resume-factory! options arity"
          :call #(sdk/<resume-factory! session "run-1"
                                       {:limits {:timeout-seconds 30}})
          :requests [["session.factory.resume"
                      {:sessionId session-id
                       :runId "run-1"
                       :limits {:timeoutSeconds 30}}]]}
         {:label "<get-factory-run"
          :call #(sdk/<get-factory-run session "run-1")
          :requests [["session.factory.getRun"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<wait-for-factory-run! default arity"
          :call #(sdk/<wait-for-factory-run! session "run-1")
          :requests [["session.factory.getRun"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<list-factory-runs"
          :call #(sdk/<list-factory-runs session)
          :requests [["session.factory.listRuns" {:sessionId session-id}]]}
         {:label "<get-factory-run-detail"
          :call #(sdk/<get-factory-run-detail session "run-1")
          :requests [["session.factory.getRunDetail"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<get-factory-run-progress default arity"
          :call #(sdk/<get-factory-run-progress session "run-1")
          :requests [["session.factory.getRunProgress"
                      {:sessionId session-id :runId "run-1"}]]}
         {:label "<get-factory-run-progress options arity"
          :call #(sdk/<get-factory-run-progress session "run-1"
                                                {:cursor "next" :limit 2})
          :requests [["session.factory.getRunProgress"
                      {:sessionId session-id
                       :runId "run-1"
                       :cursor "next"
                       :limit 2}]]}
         {:label "<cancel-factory-run!"
          :call #(sdk/<cancel-factory-run! session "run-1")
          :requests [["session.factory.cancel"
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
      {#'factory/<wait-for-run!
       (fn [actual-session run-id actual-options]
         (swap! calls conj [actual-session run-id actual-options])
         (async/to-chan! [delivered]))}
      (fn []
        (is (= delivered
               (support/read-value-then-close!!
                (sdk/<wait-for-factory-run! session "run-1" options))))))
    (is (= [[session "run-1" options]] @calls))))

(deftest async-factory-facade-delivers-rpc-failure-then-closes
  (let [session (sdk/create-session support/*test-client* {:session-id "factory-error-session"})]
    (mock/set-request-hook!
     support/*mock-server*
     (fn [method _]
       (when (= "session.factory.getRun" method)
         (throw (ex-info "factory lookup failed" {:code -32050})))))
    (let [value (support/read-value-then-close!!
                 (sdk/<get-factory-run session "missing"))]
      (is (instance? Throwable value))
      (is (= "factory lookup failed" (ex-message value))))))

(deftest factory-resume-maps-known-errors-and-passes-through-unknown-errors
  (let [session (sdk/create-session support/*test-client* {:session-id "factory-resume-session"})]
    (doseq [{:keys [wire-code expected-code]}
            [{:wire-code "not_found" :expected-code :not-found}
             {:wire-code "future_factory_error" :expected-code nil}]]
      (testing wire-code
        (mock/set-request-hook!
         support/*mock-server*
         (fn [method _]
           (when (= "session.factory.resume" method)
             (throw (ex-info "resume failed"
                             {:code -32051
                              :data {:code wire-code :detail "preserved"}})))))
        (let [error (thrown-by #(factory/resume! session "run-1"))]
          (is (instance? clojure.lang.ExceptionInfo error))
          (if expected-code
            (is (= {:type :factory-resume-error :code expected-code}
                   (ex-data error)))
            (is (= {:code "future_factory_error" :detail "preserved"}
                   (get-in (ex-data error) [:error :data])))))))))
