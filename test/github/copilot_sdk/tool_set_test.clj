(ns github.copilot-sdk.tool-set-test
  "Unit tests for github.copilot-sdk.tool-set — the source-qualified tool
   filter helpers introduced by upstream PR #1428."
  (:require [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.tool-set :as tool-set]))

(deftest valid-name?-tests
  (testing "alphanumeric, underscore, hyphen are valid"
    (is (true? (tool-set/valid-name? "ask_user")))
    (is (true? (tool-set/valid-name? "task-complete")))
    (is (true? (tool-set/valid-name? "Tool123")))
    (is (true? (tool-set/valid-name? "a"))))
  (testing "literal wildcard is valid"
    (is (true? (tool-set/valid-name? "*"))))
  (testing "wildcards in the middle of a name are invalid"
    (is (false? (tool-set/valid-name? "ask*"))))
  (testing "spaces, colons, dots, slashes are invalid"
    (is (false? (tool-set/valid-name? "ask user")))
    (is (false? (tool-set/valid-name? "builtin:ask_user")))
    (is (false? (tool-set/valid-name? "ask.user")))
    (is (false? (tool-set/valid-name? "ask/user"))))
  (testing "empty string and non-strings are invalid"
    (is (false? (tool-set/valid-name? "")))
    (is (false? (tool-set/valid-name? nil)))
    (is (false? (tool-set/valid-name? :ask_user)))
    (is (false? (tool-set/valid-name? 42)))))

(deftest builtin-tests
  (testing "produces source-qualified pattern"
    (is (= "builtin:ask_user" (tool-set/builtin "ask_user")))
    (is (= "builtin:*" (tool-set/builtin "*"))))
  (testing "rejects invalid name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid builtin tool name"
                          (tool-set/builtin "bad name")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid builtin tool name"
                          (tool-set/builtin "")))))

(deftest mcp-tests
  (testing "produces source-qualified pattern"
    (is (= "mcp:my_server" (tool-set/mcp "my_server")))
    (is (= "mcp:*" (tool-set/mcp "*"))))
  (testing "rejects invalid name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid mcp tool name"
                          (tool-set/mcp "bad:name")))))

(deftest custom-tests
  (testing "produces source-qualified pattern"
    (is (= "custom:reviewer" (tool-set/custom "reviewer")))
    (is (= "custom:*" (tool-set/custom "*"))))
  (testing "rejects invalid name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid custom tool name"
                          (tool-set/custom "has space")))))

(deftest builtins-tests
  (testing "maps over names and returns a vector"
    (is (= ["builtin:task" "builtin:skill"]
           (tool-set/builtins ["task" "skill"])))
    (is (vector? (tool-set/builtins ["task"]))))
  (testing "empty input yields empty vector"
    (is (= [] (tool-set/builtins []))))
  (testing "throws on the first invalid entry"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid builtin tool name"
                          (tool-set/builtins ["ok" "bad name"])))))

(deftest isolated-builtins-tests
  (testing "matches upstream BuiltInTools.Isolated exactly"
    (is (= ["ask_user"
            "task_complete"
            "exit_plan_mode"
            "task"
            "read_agent"
            "write_agent"
            "list_agents"
            "send_inbox"
            "context_board"
            "skill"]
           tool-set/isolated-builtins))))

(deftest isolated-tests
  (testing "is the source-qualified form of isolated-builtins"
    (is (= (mapv #(str "builtin:" %) tool-set/isolated-builtins)
           tool-set/isolated)))
  (testing "every entry starts with builtin: prefix"
    (is (every? #(.startsWith ^String % "builtin:") tool-set/isolated))))
