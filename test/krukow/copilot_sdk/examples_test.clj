(ns krukow.copilot-sdk.examples-test
  "Tests to ensure example applications compile and have valid structure."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]))

;; -----------------------------------------------------------------------------
;; Fixture - load examples once to avoid repeated load-file cost
;; -----------------------------------------------------------------------------

(defn- load-examples!
  []
  (load-file "examples/basic_chat.clj")
  (load-file "examples/tool_integration.clj")
  (load-file "examples/multi_agent.clj"))

(defn with-examples-loaded
  [test-fn]
  (load-examples!)
  (test-fn))

(use-fixtures :once with-examples-loaded)

;; -----------------------------------------------------------------------------
;; Compilation Tests - ensure examples load without errors
;; -----------------------------------------------------------------------------

(deftest test-examples-compiled
  (testing "examples compile successfully"
    (is (find-ns 'basic-chat) "Namespace basic-chat should be defined")
    (is (find-ns 'tool-integration) "Namespace tool-integration should be defined")
    (is (find-ns 'multi-agent) "Namespace multi-agent should be defined")))

;; -----------------------------------------------------------------------------
;; Structure Tests - verify examples have expected vars
;; -----------------------------------------------------------------------------

(deftest test-basic-chat-structure
  (testing "basic_chat has expected public functions"
    (let [ns-obj (find-ns 'basic-chat)]
      (is (some? (ns-resolve ns-obj '-main))
          "Should have -main function"))))

(deftest test-tool-integration-structure
  (testing "tool_integration has expected public functions and tools"
    (let [ns-obj (find-ns 'tool-integration)]
      (is (some? (ns-resolve ns-obj 'lookup-tool))
          "Should have lookup-tool")
      (is (some? (ns-resolve ns-obj '-main))
          "Should have -main function"))))

(deftest test-multi-agent-structure
  (testing "multi_agent has expected public functions"
    (let [ns-obj (find-ns 'multi-agent)]
      (is (some? (ns-resolve ns-obj 'create-agent))
          "Should have create-agent")
      (is (some? (ns-resolve ns-obj 'agent-respond!))
          "Should have agent-respond!")
      (is (some? (ns-resolve ns-obj 'run-parallel-research!))
          "Should have run-parallel-research!")
      (is (some? (ns-resolve ns-obj '-main))
          "Should have -main function"))))

;; -----------------------------------------------------------------------------
;; Tool Definition Tests - verify tools are properly defined
;; -----------------------------------------------------------------------------

(deftest test-tool-definitions-valid
  (testing "Tool definitions have required fields"
    (let [ns-obj (find-ns 'tool-integration)
          lookup-tool @(ns-resolve ns-obj 'lookup-tool)]
      ;; Check lookup-tool
      (is (string? (:tool-name lookup-tool)))
      (is (= "lookup_language" (:tool-name lookup-tool)))
      (is (string? (:tool-description lookup-tool)))
      (is (map? (:tool-parameters lookup-tool)))
      (is (fn? (:tool-handler lookup-tool))))))

(deftest test-tool-handlers-callable
  (testing "Tool handlers can be invoked with mock data"
    (let [ns-obj (find-ns 'tool-integration)
          lookup-tool @(ns-resolve ns-obj 'lookup-tool)
          lookup-handler (:tool-handler lookup-tool)]
      ;; Test lookup handler with known language
      (let [result (lookup-handler {:language "clojure"} {})]
        (is (map? result))
        (is (= "success" (:result-type result)))
        (is (clojure.string/includes? (:text-result-for-llm result) "Clojure"))))))

;; -----------------------------------------------------------------------------
;; Agent Definition Tests - verify agent factory function works
;; -----------------------------------------------------------------------------

(deftest test-multi-agent-functions-defined
  (testing "Multi-agent helper functions are callable"
    (let [ns-obj (find-ns 'multi-agent)
          create-agent-fn (ns-resolve ns-obj 'create-agent)
          agent-respond-fn (ns-resolve ns-obj 'agent-respond!)
          parallel-fn (ns-resolve ns-obj 'run-parallel-research!)]
      (is (fn? @create-agent-fn) "create-agent should be a function")
      (is (fn? @agent-respond-fn) "agent-respond! should be a function")
      (is (fn? @parallel-fn) "run-parallel-research! should be a function"))))
