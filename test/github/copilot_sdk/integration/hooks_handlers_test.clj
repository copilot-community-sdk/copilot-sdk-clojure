(ns github.copilot-sdk.integration.hooks-handlers-test
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
            [github.copilot-sdk.factory :as factory]
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

(deftest test-hooks-pre-tool-use
  (testing "hooks.invoke preToolUse calls registered handler and returns result"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:permission-decision "allow"
                                                  :additional-context "extra info"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "echo hi"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      ;; Input keys are converted to kebab-case by wire->clj
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= {:command "echo hi"} (get-in @handler-called [:input :tool-args])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      ;; HookInvokeResponse wraps the handler's return value under output.
      (is (= "allow" (get-in response [:result :output :permissionDecision]))))))

(deftest test-hooks-agent-stop
  (testing "hooks.invoke agentStop calls the registered handler and returns a block decision"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-agent-stop
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:decision "block"
                                                  :reason "fix the remaining findings"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "agentStop"
                                            :input {:stopReason "end_turn"
                                                    :transcriptPath "/tmp/transcript.jsonl"
                                                    :stop_hook_active true
                                                    :timestamp 1700000000000
                                                    :cwd "/workspace"}})]
      (is (s/get-spec ::specs/on-agent-stop))
      (is (= {:stop-reason "end_turn"
              :transcript-path "/tmp/transcript.jsonl"
              :stop-hook-active true
              :timestamp 1700000000000
              :cwd "/workspace"
              :session-id session-id}
             (:input @handler-called)))
      (is (= {:session-id session-id} (:ctx @handler-called)))
      (is (= {:decision "block" :reason "fix the remaining findings"}
             (get-in response [:result :output])))))
  (testing "nil and handler errors both let the agent stop"
    (doseq [handler [(fn [_ _] nil)
                     (fn [_ _] (throw (Exception. "agent-stop failed")))]]
      (let [session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all
                                         :hooks {:on-agent-stop handler}})
            response (mock/send-rpc-request! *mock-server*
                                             "hooks.invoke"
                                             {:sessionId (sdk/session-id session)
                                              :hookType "agentStop"
                                              :input {:timestamp 1700000000000
                                                      :cwd "/workspace"}})]
        (is (= {} (:result response)))))))

(deftest test-hooks-user-prompt-transformed
  (testing "hooks.invoke userPromptTransformed calls the registered handler"
    (let [handler-called (atom nil)
          copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all
            :hooks {:on-user-prompt-transformed
                    (fn [input ctx]
                      (reset! handler-called {:input input :ctx ctx})
                      {:modified-transformed-prompt "rewritten prompt"})}})
          session-id (sdk/session-id copilot-session)
          response (mock/send-rpc-request!
                    *mock-server*
                    "hooks.invoke"
                    {:sessionId session-id
                     :hookType "userPromptTransformed"
                     :input {:prompt "original"
                             :transformedPrompt "generated context\noriginal"
                             :timestamp 1700000000000
                             :cwd "/workspace"}})]
      (is (s/get-spec ::specs/on-user-prompt-transformed))
      (is (= "original" (get-in @handler-called [:input :prompt])))
      (is (= "generated context\noriginal"
             (get-in @handler-called [:input :transformed-prompt])))
      (is (= {:session-id session-id} (:ctx @handler-called)))
      (is (= "rewritten prompt"
             (get-in response [:result :output :modifiedTransformedPrompt]))))))

(deftest test-hooks-post-tool-use
  (testing "hooks.invoke postToolUse calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :toolResult {:textResultForLlm "ok"
                                                                 :resultType "success"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      ;; Handler returned nil, so the response has no output.
      (is (= {} (:result response))))))

(deftest test-hooks-post-tool-use-failure
  (testing "hooks.invoke postToolUseFailure calls registered handler (upstream PR #1421)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use-failure
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:additional-context "noted"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "false"}
                                                    :error "command exited 1"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= "command exited 1" (get-in @handler-called [:input :error])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= "noted" (get-in response [:result :output :additionalContext]))))))

(deftest test-hooks-post-tool-use-failure-no-handler
  (testing "hooks.invoke postToolUseFailure with no handler returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       ;; Only success hook registered; failure should pass through without output.
                                       :hooks {:on-post-tool-use
                                               (fn [_ _] nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :error "boom"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-session-start
  (testing "hooks.invoke sessionStart calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-session-start
                                               (fn [input ctx]
                                                 (reset! handler-called input)
                                                 {:additional-context "welcome"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "sessionStart"
                                            :input {:source "new"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "new" (:source @handler-called)))
      (is (= "welcome" (get-in response [:result :output :additionalContext]))))))

(deftest test-hooks-unknown-type-returns-empty-response
  (testing "hooks.invoke with unknown hook type returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] {:permission-decision "allow"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "unknownHookType"
                                            :input {:timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-handler-exception-returns-empty-response
  (testing "hooks.invoke handler exception returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] (throw (Exception. "oops")))}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-no-hooks-registered
  (testing "hooks.invoke with no hooks registered returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-unknown-session
  (testing "hooks.invoke with an unknown session returns an RPC error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown session: missing-session"
         (mock/send-rpc-request! *mock-server*
                                 "hooks.invoke"
                                 {:sessionId "missing-session"
                                  :hookType "agentStop"
                                  :input {:timestamp 1700000000000
                                          :cwd "/workspace"}})))))

(deftest test-hooks-input-exposes-session-id
  (testing "hook input includes :session-id (upstream PR #1290 — BaseHookInput.sessionId)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called)))))

  (testing "hook input :session-id preserves wire-provided value (sub-agent case)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          parent-session-id (sdk/session-id session)
          sub-agent-session-id "sub-agent-session-xyz"
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId parent-session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId sub-agent-session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= sub-agent-session-id (:session-id @handler-called)))))

  (testing "hook input :session-id falls back to outer session-id when wire omits it"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called))))))

(deftest test-hooks-pre-mcp-tool-call-input-shape
  (testing "preMcpToolCall: handler receives kebab-cased base fields + opaque arguments/_meta"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          opaque-args {:filePath "/tmp/foo.txt"
                       :user_id 42
                       :nested {:keepCamelCase true}}
          opaque-meta {:traceId "abc-123" :foo_bar "ok"}
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preMcpToolCall"
                                     :input {:serverName "my-mcp"
                                             :toolName "fetch"
                                             :toolCallId "call-42"
                                             :arguments opaque-args
                                             :_meta opaque-meta
                                             :timestamp 12345
                                             :cwd "/workspace"
                                             :sessionId session-id}})]
      (is (some? @handler-called))
      ;; Base fields are kebab-cased
      (is (= "my-mcp" (get-in @handler-called [:input :server-name])))
      (is (= "fetch" (get-in @handler-called [:input :tool-name])))
      (is (= "call-42" (get-in @handler-called [:input :tool-call-id])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= 12345 (get-in @handler-called [:input :timestamp])))
      ;; Opaque arguments preserved verbatim (wire-keyword shape, NOT kebab-cased)
      (is (= opaque-args (get-in @handler-called [:input :arguments])))
      ;; _meta key preserved verbatim (kebab conversion would strip leading _)
      (is (= opaque-meta (get-in @handler-called [:input :_meta]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-object
  (testing "preMcpToolCall: :meta-to-use map becomes metaToUse on wire with opaque inner contents"
    (let [opaque-replacement {:newTraceId "xyz-789" :keep_snake "yes"}
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use opaque-replacement})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The wire field name is metaToUse, NOT meta-to-use
      (is (contains? (get-in response [:result :output]) :metaToUse))
      (is (not (contains? (get-in response [:result :output]) :meta-to-use)))
      ;; Inner map preserved verbatim — inner keys NOT camelCased
      (is (= opaque-replacement (get-in response [:result :output :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-null
  (testing "preMcpToolCall: :meta-to-use nil serializes as JSON null (key present with null value)"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use nil})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The metaToUse key MUST be present (not absent) and its value MUST be null.
      (is (contains? (get-in response [:result :output]) :metaToUse))
      (is (nil? (get-in response [:result :output :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-no-meta-to-use
  (testing "preMcpToolCall: handler returning {} omits metaToUse field"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _] {})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      (is (= {:output {}} (:result response)))
      (is (not (contains? (get-in response [:result :output]) :metaToUse))))))

(deftest test-user-input-handler-invoked
  (testing "userInput.request calls registered handler with correct shape"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-user-input-request
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         {:answer "option A" :was-freeform false})})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "userInput.request"
                                           {:sessionId session-id
                                            :question "Which option?"
                                            :choices ["option A" "option B"]
                                            :allowFreeform true})]
      (is (some? @handler-called))
      (is (= "Which option?" (get-in @handler-called [:request :question])))
      (is (= "option A" (get-in response [:result :answer])))
      (is (false? (get-in response [:result :wasFreeform]))))))

(deftest test-user-input-no-handler-errors
  (testing "userInput.request without handler returns error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User input requested but no handler registered"
                            (mock/send-rpc-request! *mock-server*
                                                    "userInput.request"
                                                    {:sessionId session-id
                                                     :question "Which option?"}))))))

(deftest test-system-message-transform-callback
  (testing "systemMessage.transform invokes registered transform callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [content]
                                                                                        (str content " EXTRA"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "I am an agent."}}})]
      (is (= "I am an agent. EXTRA"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-error-returns-original
  (testing "systemMessage.transform returns original content on callback error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [_] (throw (Exception. "fail")))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "original text"}}})]
      (is (= "original text"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-no-callback-passthrough
  (testing "systemMessage.transform passes through sections without callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [c] (str c "!"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "hello"}
                                                       :tone {:content "be nice"}}})]
      (is (= "hello!" (get-in response [:result :sections :identity :content])))
      (is (= "be nice" (get-in response [:result :sections :tone :content]))))))

(deftest test-tool-search-invocation-metadata
  (letfn [(invoke-tool! [tool-name metadata-response]
            (let [requests (atom [])
                  invocation-promise (promise)
                  rpc-latch (java.util.concurrent.CountDownLatch. 1)
                  _ (mock/set-current-tool-metadata-response!
                     *mock-server*
                     metadata-response)
                  _ (mock/set-request-hook!
                     *mock-server*
                     (fn [method params]
                       (swap! requests conj {:method method :params params})
                       (when (= "session.tools.handlePendingToolCall" method)
                         (.countDown rpc-latch))))
                  session (sdk/create-session
                           *test-client*
                           {:on-permission-request sdk/approve-all
                            :tools [{:tool-name tool-name
                                     :tool-handler
                                     (fn [_args invocation]
                                       (deliver invocation-promise invocation)
                                       "ok")}]})
                  session-id (sdk/session-id session)]
              (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
              (mock/send-v3-broadcast-event!
               *mock-server*
               session-id
               "external_tool.requested"
               {:requestId (str "request-" tool-name)
                :toolName tool-name
                :toolCallId (str "call-" tool-name)
                :arguments {}})
              (let [invocation (deref invocation-promise 5000
                                      :github.copilot-sdk.integration-test/timeout)]
                (is (not= :github.copilot-sdk.integration-test/timeout invocation))
                (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
                {:invocation invocation
                 :requests @requests})))]
    (testing "tool_search_tool receives the current tool metadata snapshot"
      (let [metadata [{:name "github-tool"
                       :namespacedName "github/github-tool"
                       :mcpServerName "github"
                       :mcpToolName "github_tool"
                       :description "Search GitHub"
                       :input_schema {:type "object"}
                       :deferLoading true}]
            {:keys [invocation requests]}
            (invoke-tool! "tool_search_tool" {:tools metadata})]
        (is (= [{:name "github-tool"
                 :namespaced-name "github/github-tool"
                 :mcp-server-name "github"
                 :mcp-tool-name "github_tool"
                 :description "Search GitHub"
                 :input-schema {:type "object"}
                 :defer-loading true}]
               (:available-tools invocation)))
        (is (= 1 (count (filter #(= "session.tools.getCurrentMetadata"
                                    (:method %))
                                requests))))))

    (testing "ordinary tools do not request current tool metadata"
      (let [{:keys [invocation requests]}
            (invoke-tool! "ordinary-tool"
                          {:tools [{:name "unused"
                                    :description "Unused"}]})]
        (is (not (contains? invocation :available-tools)))
        (is (empty? (filter #(= "session.tools.getCurrentMetadata"
                                (:method %))
                            requests)))))

    (testing "metadata lookup failure does not fail tool invocation"
      (let [{:keys [invocation requests]}
            (invoke-tool! "tool_search_tool"
                          (ex-info "metadata unavailable" {:code -32000}))]
        (is (not (contains? invocation :available-tools)))
        (is (= 1 (count (filter #(= "session.tools.getCurrentMetadata"
                                    (:method %))
                                requests))))))))

(deftest test-tool-result-normalization
  (testing "tool handler return values are normalized into the handlePendingToolCall result"
    (doseq [[desc tool-handler req-id tc-id assert-result]
            [["string is normalized to success"
              (fn [_args _inv] "hello world") "tool-req-1" "tc-1"
              (fn [result]
                (is (= "hello world" (:textResultForLlm result)))
                (is (= "success" (:resultType result))))]
             ["nil is normalized to failure"
              (fn [_args _inv] nil) "tool-req-2" "tc-2"
              (fn [result]
                (is (= "Tool returned no result" (:textResultForLlm result)))
                (is (= "failure" (:resultType result))))]
             ["structured ToolResultObject is forwarded with telemetry"
              (fn [_args _inv] {:text-result-for-llm "all good"
                                :result-type "success"
                                :tool-telemetry {:latency-ms 42}})
              "tool-req-3" "tc-3"
              (fn [result]
                (is (= "all good" (:textResultForLlm result)))
                (is (= "success" (:resultType result)))
                (is (= 42 (get-in result [:toolTelemetry :latencyMs]))))]
             ["structured ToolResultObject forwards tool references"
              (fn [_args _inv] {:text-result-for-llm "found tools"
                                :result-type "success"
                                :tool-references ["github/search" "github/get"]})
              "tool-req-4" "tc-4"
              (fn [result]
                (is (= ["github/search" "github/get"]
                       (:toolReferences result))))]]]
      (testing desc
        (let [requests (atom [])
              rpc-latch (java.util.concurrent.CountDownLatch. 1)
              _ (mock/set-request-hook! *mock-server*
                                        (fn [method params]
                                          (swap! requests conj {:method method :params params})
                                          (when (= "session.tools.handlePendingToolCall" method)
                                            (.countDown rpc-latch))))
              session (sdk/create-session *test-client*
                                          {:on-permission-request sdk/approve-all
                                           :tools [{:tool-name "test-tool"
                                                    :tool-handler tool-handler}]})
              session-id (sdk/session-id session)]
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
          (reset! requests [])
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "external_tool.requested"
                                         {:requestId req-id
                                          :toolName "test-tool"
                                          :toolCallId tc-id
                                          :arguments {}})
          (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
                result (get-in (first rpcs) [:params :result])]
            (is (= 1 (count rpcs)))
            (is (map? result))
            (assert-result result)))))))
