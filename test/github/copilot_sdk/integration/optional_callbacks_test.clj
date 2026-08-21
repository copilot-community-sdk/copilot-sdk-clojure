(ns github.copilot-sdk.integration.optional-callbacks-test
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

(deftest test-create-session-without-permission-handler
  (testing "create-session accepts omission of :on-permission-request (upstream PR #1308)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)) "session.create still issued")
      (is (some? (sdk/session-id session))))))

(deftest test-resume-session-without-permission-handler
  (testing "resume-session accepts omission of :on-permission-request"
    (let [requests (atom [])
          _ (sdk/create-session *test-client* {})
          session-id (sdk/get-last-session-id *test-client*)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          resumed (sdk/resume-session *test-client* session-id {})
          resume-rpcs (filter #(= "session.resume" (:method %)) @requests)]
      (is (= 1 (count resume-rpcs)))
      (is (some? (sdk/session-id resumed))))))

(deftest test-define-tool-without-handler
  (testing "define-tool accepts omission of :handler (declaration-only tool, upstream PR #1308)"
    (let [tool (tools/define-tool "manual_lookup"
                 {:description "Look up status manually"
                  :parameters {:type "object"
                               :properties {:id {:type "string"}}
                               :required ["id"]}})]
      (is (= "manual_lookup" (:tool-name tool)))
      (is (not (contains? tool :tool-handler))
          "tool map must NOT contain :tool-handler key when handler omitted")
      ;; Must pass tool spec validation (handler now optional)
      (is (s/valid? :github.copilot-sdk.specs/tool tool))))

  (testing "define-tool-from-spec accepts omission of :handler (upstream PR #1308)"
    (let [tool (tools/define-tool-from-spec "manual_spec_tool"
                 {:description "Declaration-only spec tool"})]
      (is (= "manual_spec_tool" (:tool-name tool)))
      (is (not (contains? tool :tool-handler))
          "define-tool-from-spec must NOT install a wrapper handler when :handler omitted")
      (is (s/valid? :github.copilot-sdk.specs/tool tool)))))

(deftest test-declaration-only-tool-not-stored-in-handler-map
  (testing "declaration-only tools don't populate :tool-handlers"
    (let [decl-tool (tools/define-tool "decl_only" {:description "decl-only"})
          handled-tool (tools/define-tool "with_handler"
                         {:description "has handler"
                          :handler (fn [_ _] "ok")})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [decl-tool handled-tool]})
          session-id (sdk/session-id session)
          tool-handlers (get-in @(:state *test-client*)
                                [:sessions session-id :tool-handlers])]
      (is (not (contains? tool-handlers "decl_only"))
          "declaration-only tools must not be in :tool-handlers")
      (is (contains? tool-handlers "with_handler")
          "tools with handlers must remain in :tool-handlers"))))

(deftest test-handle-pending-permission-request!-sends-rpc
  (testing "handle-pending-permission-request! issues session.permissions.handlePendingPermissionRequest"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-42"
                                               :result {:kind :approve-once}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "req-42" (get-in (first pending-rpcs) [:params :requestId])))
        ;; Result is wire-serialized via clj->wire: keyword :kind → "approve-once"
        (is (= "approve-once" (get-in (first pending-rpcs)
                                      [:params :result :kind]))))))

  (testing "handle-pending-permission-request! accepts :user-not-available"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-una"
                                               :result {:kind :user-not-available}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "user-not-available" (get-in (first pending-rpcs)
                                            [:params :result :kind]))))))

  (testing "handle-pending-permission-request! accepts :approve-permanently"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-permission-request! session
                                              {:request-id "req-perm"
                                               :result {:kind :approve-permanently}})
      (let [pending-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                     (:method %))
                                 @requests)]
        (is (= 1 (count pending-rpcs)))
        (is (= "approve-permanently" (get-in (first pending-rpcs)
                                             [:params :result :kind]))))))

  (testing "handle-pending-permission-request! rejects :no-result"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-permission-request!
                    session
                    {:request-id "req-1" :result {:kind :no-result}})))))

  (testing "handle-pending-permission-request! rejects malformed result"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-permission-request!
                    session
                    {:request-id "req-1" :result "not-a-map"})))))

  (testing "handle-pending-permission-request! rejects blank :request-id"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "" :result {:kind :approve-once}})))
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "   " :result {:kind :approve-once}})))))

  (testing "handle-pending-permission-request! rejects non-keyword :kind"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":kind must be a keyword"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind "approve-once"}})))
      (is (thrown-with-msg? Exception #":kind must be a keyword"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind 42}})))))

  (testing "handle-pending-permission-request! rejects unsupported :kind"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"not a supported permission decision"
                            (sdk/handle-pending-permission-request!
                             session
                             {:request-id "req-1" :result {:kind :totally-made-up}}))))))

(deftest test-handle-pending-tool-call!-sends-rpc
  (testing "handle-pending-tool-call! with :result issues session.tools.handlePendingToolCall"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-tool-call! session
                                     {:request-id "tool-req-7"
                                      :result "MANUAL_STATUS_OK"})
      (let [pending (filter #(= "session.tools.handlePendingToolCall" (:method %))
                            @requests)]
        (is (= 1 (count pending)))
        (is (= "tool-req-7" (get-in (first pending) [:params :requestId])))
        ;; String result is normalized through normalize-tool-result
        (is (= "MANUAL_STATUS_OK"
               (get-in (first pending) [:params :result :textResultForLlm]))))))

  (testing "handle-pending-tool-call! with :error forwards error string"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client* {})]
      (sdk/handle-pending-tool-call! session
                                     {:request-id "tool-req-8"
                                      :error "manual failure"})
      (let [pending (first (filter #(= "session.tools.handlePendingToolCall" (:method %))
                                   @requests))]
        (is (= "manual failure" (get-in pending [:params :error])))
        (is (not (contains? (:params pending) :result))))))

  (testing "handle-pending-tool-call! rejects :result and :error together"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown? Exception
                   (sdk/handle-pending-tool-call!
                    session
                    {:request-id "x" :result "a" :error "b"})))))

  (testing "handle-pending-tool-call! rejects non-string :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":error must be a string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x" :error {:code 1}})))
      (is (thrown-with-msg? Exception #":error must be a string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x" :error 42})))))

  (testing "handle-pending-tool-call! requires :result or :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"exactly one of :result or :error"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "x"})))))

  (testing "<handle-pending-tool-call! requires :result or :error"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #"exactly one of :result or :error"
                            (sdk/<handle-pending-tool-call!
                             session
                             {:request-id "x"})))))

  (testing "handle-pending-tool-call! rejects blank :request-id"
    (let [session (sdk/create-session *test-client* {})]
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "" :result "ok"})))
      (is (thrown-with-msg? Exception #":request-id must be a non-blank string"
                            (sdk/handle-pending-tool-call!
                             session
                             {:request-id "   " :result "ok"}))))))

(deftest test-send-agent-mode-on-wire
  (testing "send! forwards :agent-mode as wire :agentMode (upstream PR #1438)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (doseq [mode [:interactive :plan :autopilot :shell]]
        (reset! seen {})
        (sdk/send! session {:prompt "hi" :agent-mode mode})
        (is (= (name mode) (:agentMode (get @seen "session.send")))
            (str "agentMode should be " (name mode))))))

  (testing "send! omits agentMode when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "hi"})
          send-params (get @seen "session.send")]
      (is (not (contains? send-params :agentMode))))))

(deftest test-send-display-prompt-on-wire
  (testing "send! forwards :display-prompt as wire :displayPrompt (upstream PR #1470)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.send" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "internal model prompt"
                                :display-prompt "What you see in the timeline"})
          send-params (get @seen "session.send")]
      (is (= "What you see in the timeline" (:displayPrompt send-params))))))

(deftest test-spec-send-options-accepts-agent-mode-and-display-prompt
  (testing "::send-options accepts :agent-mode keyword (PR #1438)"
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :interactive}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :plan}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :autopilot}))
    (is (s/valid? ::specs/send-options {:prompt "x" :agent-mode :shell}))
    (is (not (s/valid? ::specs/send-options {:prompt "x" :agent-mode :invalid}))))
  (testing "::send-options accepts :display-prompt string (PR #1470)"
    (is (s/valid? ::specs/send-options {:prompt "x" :display-prompt "shown"}))
    (is (not (s/valid? ::specs/send-options {:prompt "x" :display-prompt 42})))))

(deftest test-mcp-oauth-token-storage-on-wire
  (testing ":mcp-oauth-token-storage forwarded as wire string (upstream PR #1326)"
    (doseq [[kw expected-wire] [[:persistent "persistent"]
                                [:in-memory "in-memory"]]]
      (let [seen (atom {})
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (when (= "session.create" method)
                                          (swap! seen assoc method params))))
            _ (sdk/create-session *test-client*
                                  {:on-permission-request sdk/approve-all
                                   :mcp-oauth-token-storage kw})
            create-params (get @seen "session.create")]
        (is (= expected-wire (:mcpOAuthTokenStorage create-params))
            (str ":mcp-oauth-token-storage " kw " should wire as \"" expected-wire "\" "
                 "(not csk-camelized — must preserve hyphen)")))))

  (testing ":mcp-oauth-token-storage forwarded on session.resume too"
    (let [seen (atom {})
          ;; First create a session to resume
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :mcp-oauth-token-storage :in-memory})
          resume-params (get @seen "session.resume")]
      (is (= "in-memory" (:mcpOAuthTokenStorage resume-params))))))

(deftest test-spec-mcp-oauth-token-storage
  (testing "::mcp-oauth-token-storage spec accepts :persistent / :in-memory"
    (is (s/valid? ::specs/mcp-oauth-token-storage :persistent))
    (is (s/valid? ::specs/mcp-oauth-token-storage :in-memory))
    (is (not (s/valid? ::specs/mcp-oauth-token-storage :invalid)))
    (is (not (s/valid? ::specs/mcp-oauth-token-storage "in-memory")))))

(deftest test-multitenancy-flags-on-wire
  (testing "All multitenancy flags forwarded on session.create (upstream PR #1474)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :skip-embedding-retrieval true
                                 :embedding-cache-storage :in-memory
                                 :organization-custom-instructions "You are an Acme assistant."
                                 :enable-on-demand-instruction-discovery true
                                 :enable-file-hooks false
                                 :enable-host-git-operations false
                                 :enable-session-store false
                                 :enable-skills false})
          create-params (get @seen "session.create")]
      (is (= true (:skipEmbeddingRetrieval create-params)))
      (is (= "in-memory" (:embeddingCacheStorage create-params))
          ":embedding-cache-storage must preserve hyphen on wire")
      (is (= "You are an Acme assistant." (:organizationCustomInstructions create-params)))
      (is (= true (:enableOnDemandInstructionDiscovery create-params)))
      (is (= false (:enableFileHooks create-params)))
      (is (= false (:enableHostGitOperations create-params)))
      (is (= false (:enableSessionStore create-params)))
      (is (= false (:enableSkills create-params)))))

  (testing "Multitenancy flags omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (doseq [k [:skipEmbeddingRetrieval :embeddingCacheStorage
                 :organizationCustomInstructions :enableOnDemandInstructionDiscovery
                 :enableFileHooks :enableHostGitOperations :enableSessionStore
                 :enableSkills]]
        (is (not (contains? create-params k))
            (str "Should NOT contain " k " when not set"))))))

(deftest test-spec-multitenancy-flags
  (testing "All multitenancy flag specs accept correct values (PR #1474)"
    (is (s/valid? ::specs/skip-embedding-retrieval true))
    (is (s/valid? ::specs/embedding-cache-storage :in-memory))
    (is (s/valid? ::specs/embedding-cache-storage :persistent))
    (is (s/valid? ::specs/organization-custom-instructions "anything"))
    (is (s/valid? ::specs/enable-on-demand-instruction-discovery true))
    (is (s/valid? ::specs/enable-file-hooks true))
    (is (s/valid? ::specs/enable-host-git-operations true))
    (is (s/valid? ::specs/enable-session-store true))
    (is (s/valid? ::specs/enable-skills true))
    (is (not (s/valid? ::specs/embedding-cache-storage "in-memory")))
    (is (not (s/valid? ::specs/enable-file-hooks "true")))))

(deftest test-plugin-directories-on-wire
  (testing ":plugin-directories forwarded as wire :pluginDirectories (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :plugin-directories ["/etc/copilot/plugins"
                                                      "/usr/local/share/copilot/plugins"]})
          create-params (get @seen "session.create")]
      (is (= ["/etc/copilot/plugins" "/usr/local/share/copilot/plugins"]
             (:pluginDirectories create-params)))))

  (testing ":plugin-directories also forwarded on session.resume"
    (let [seen (atom {})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :plugin-directories ["/tmp/plugins"]})
          resume-params (get @seen "session.resume")]
      (is (= ["/tmp/plugins"] (:pluginDirectories resume-params))))))

(deftest test-spec-plugin-directories
  (testing "::plugin-directories spec rejects empty strings and non-collections"
    (is (s/valid? ::specs/plugin-directories ["/a/b"]))
    (is (s/valid? ::specs/plugin-directories []))
    (is (not (s/valid? ::specs/plugin-directories ["" "/a"])))
    (is (not (s/valid? ::specs/plugin-directories "/a/b")))))

(deftest test-reasoning-summary-on-wire
  (testing ":reasoning-summary forwarded on create and resume (parity gap)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! seen assoc method params)))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :reasoning-summary "detailed"})
          session-id (sdk/session-id session)]
      (is (= "detailed" (:reasoningSummary (get @seen "session.create"))))
      (reset! seen {})
      (sdk/resume-session *test-client* session-id
                          {:on-permission-request sdk/approve-all
                           :reasoning-summary "concise"})
      (is (= "concise" (:reasoningSummary (get @seen "session.resume")))))))

(deftest test-context-tier-on-wire
  (testing ":context-tier forwarded on create and resume (parity gap)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! seen assoc method params)))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :context-tier :long-context})
          session-id (sdk/session-id session)]
      ;; Wire enum uses underscore per schema: "default" | "long_context"
      (is (= "long_context" (:contextTier (get @seen "session.create"))))
      (reset! seen {})
      (sdk/resume-session *test-client* session-id
                          {:on-permission-request sdk/approve-all
                           :context-tier :default})
      (is (= "default" (:contextTier (get @seen "session.resume")))))))

(deftest test-resume-large-output-on-wire
  (testing ":large-output forwarded on session.resume (parity gap; was create-only)"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :max-size-bytes 16384}})
          resume-params (get @seen "session.resume")]
      (is (= true (get-in resume-params [:largeOutput :enabled])))
      (is (= 16384 (get-in resume-params [:largeOutput :maxSizeBytes]))))))

(deftest test-spec-reasoning-summary-and-context-tier
  (testing "::reasoning-summary accepts upstream enum values"
    (is (s/valid? ::specs/reasoning-summary "none"))
    (is (s/valid? ::specs/reasoning-summary "concise"))
    (is (s/valid? ::specs/reasoning-summary "detailed"))
    (is (not (s/valid? ::specs/reasoning-summary "auto")))
    (is (not (s/valid? ::specs/reasoning-summary 42))))
  (testing "::context-tier accepts :default and :long-context"
    (is (s/valid? ::specs/context-tier :default))
    (is (s/valid? ::specs/context-tier :long-context))
    (is (not (s/valid? ::specs/context-tier :other)))
    (is (not (s/valid? ::specs/context-tier "default")))))

(deftest test-config-directory-rename-on-wire
  (testing ":config-directory (new spelling) wires as :configDir (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :config-directory "/my/config"})
          create-params (get @seen "session.create")]
      (is (= "/my/config" (:configDir create-params)))
      (is (not (contains? create-params :configDirectory))
          "Wire must NOT include configDirectory (wire stays configDir)")))

  (testing ":config-dir (deprecated alias) continues to work as :configDir"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :config-dir "/legacy/config"})
          create-params (get @seen "session.create")]
      (is (= "/legacy/config" (:configDir create-params))
          "Backward-compat: :config-dir still wires as :configDir")))

  (testing "On resume, :config-directory also wires as :configDir"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :config-directory "/resumed/config"})
          resume-params (get @seen "session.resume")]
      (is (= "/resumed/config" (:configDir resume-params))))))

(deftest test-output-directory-rename-on-wire
  (testing ":output-directory inside :large-output wires as :outputDir (upstream PR #1482)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :output-directory "/tmp/out"}})
          create-params (get @seen "session.create")]
      (is (= "/tmp/out" (get-in create-params [:largeOutput :outputDir])))
      (is (not (contains? (:largeOutput create-params) :outputDirectory)))))

  (testing ":output-dir (deprecated alias) continues to work as :outputDir"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :large-output {:enabled true
                                                :output-dir "/legacy/out"}})
          create-params (get @seen "session.create")]
      (is (= "/legacy/out" (get-in create-params [:largeOutput :outputDir]))))))

(deftest test-spec-config-and-output-directory-aliases
  (testing "::session-config accepts both :config-directory and :config-dir"
    (is (s/valid? ::specs/session-config {:config-directory "/a"}))
    (is (s/valid? ::specs/session-config {:config-dir "/a"})))
  (testing "::large-output accepts both :output-directory and :output-dir"
    (is (s/valid? ::specs/large-output {:enabled true :output-directory "/a"}))
    (is (s/valid? ::specs/large-output {:enabled true :output-dir "/a"}))))

(deftest test-new-event-types-in-public-set
  (testing "Schema 1.0.56-1 event types are exposed in public event-types set"
    (is (contains? sdk/event-types :copilot/session.autopilot_objective_changed))
    (is (contains? sdk/event-types :copilot/session.permissions_changed))
    (is (contains? sdk/event-types :copilot/hook.progress))))

(deftest test-spec-session-permissions-changed-data
  (testing "::session.permissions_changed-data accepts the current experimental shape"
    (let [evt {:mode "assisted"
               :previous-mode "manual"
               :assisted-approval-model "gpt-5.4"}]
      (is (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data evt)))
    (is (not (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                       {:mode "automatic"
                        :previous-mode "manual"})))
    (is (not (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                       {:mode "allow-all"
                        :previous-mode "manual"
                        :assisted-approval-model 42}))))
  (testing "the removed pre-1.0.81 shape is rejected"
    (is (not (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                       {:allow-all-permissions true
                        :previous-allow-all-permissions false})))))

(deftest test-spec-hook-progress-data
  (testing "::hook.progress-data accepts a non-blank :message string"
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "uploading 50%"}))
    (is (not (s/valid? :github.copilot-sdk.specs/hook.progress-data
                       {:message ""})))
    (is (not (s/valid? :github.copilot-sdk.specs/hook.progress-data
                       {:message 42})))))

(deftest test-generated-session-resume-data-context-tier
  (testing "generated session.resume-data accepts wire :context-tier values"
    ;; The curated ::specs/session.resume-data deliberately does NOT lift
    ;; :context-tier into the idiom layer (matches the round-5 pattern for
    ;; ::session.model_change-data: event payloads carry the wire string).
    ;; The generated wire spec is the source of truth for the field shape.
    (let [spec :github.copilot-sdk.generated.event-specs/session.resume-data]
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier "long_context"}))
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier "default"}))
      (is (s/valid? spec
                    {:resume-time "2026-05-01T00:00:00Z"
                     :event-count 0
                     :context-tier nil}))
      (is (not (s/valid? spec
                         {:resume-time "2026-05-01T00:00:00Z"
                          :event-count 0
                          :context-tier "bogus"}))))))

(deftest test-cloud-session-omits-session-id-on-wire
  (testing "Cloud session without :session-id omits sessionId from session.create (upstream PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :cloud {:repository {:owner "octocat"
                                                            :name "hello"
                                                            :branch "main"}}})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :sessionId))
          "Cloud-no-id path must omit sessionId; server assigns one")
      ;; The mock-server returns whatever id it generates; verify our session
      ;; was registered with that server-assigned id
      (is (string? (sdk/session-id session)))
      (is (.startsWith ^String (sdk/session-id session) "session-")
          "Session id should come from mock server (prefix 'session-'), not a UUID"))))

(deftest test-cloud-session-with-caller-supplied-id-is-sent
  (testing "Cloud session WITH :session-id sends sessionId on wire (PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          custom-id "caller-supplied-id-xyz"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :cloud {}
                                       :session-id custom-id})
          create-params (get @seen "session.create")]
      (is (= custom-id (:sessionId create-params)))
      (is (= custom-id (sdk/session-id session))))))

(deftest test-non-cloud-session-still-generates-uuid
  (testing "Non-cloud session still gets a client-generated UUID (unchanged path)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (string? (:sessionId create-params)))
      ;; Should be a UUID, not a server-generated id
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                      (:sessionId create-params)))
      (is (= (:sessionId create-params) (sdk/session-id session))))))

(deftest test-async-cloud-session-omits-session-id-on-wire
  (testing "<create-session cloud-no-id path omits sessionId and adopts server-assigned id (PR #1479)"
    ;; Async branch has separate promise/cleanup logic and uses the 4-arity
    ;; proto/send-request options path, so the sync cloud-no-id coverage does
    ;; not exercise it. Mirror test-cloud-session-omits-session-id-on-wire for
    ;; <create-session.
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          result-ch (sdk/<create-session *test-client*
                                         {:on-permission-request sdk/approve-all
                                          :cloud {:repository {:owner "octocat"
                                                               :name "hello"
                                                               :branch "main"}}})
          [session _] (alts!! [result-ch (timeout 5000)])
          create-params (get @seen "session.create")]
      (is (some? session) "<create-session should deliver a session")
      (is (not (instance? Throwable session))
          (str "<create-session cloud-no-id should not return an error: " session))
      (is (not (contains? create-params :sessionId))
          "Async cloud-no-id path must omit sessionId; server assigns one")
      (is (string? (sdk/session-id session)))
      (is (.startsWith ^String (sdk/session-id session) "session-")
          "Session id should come from mock server (prefix 'session-'), not a UUID")
      (sdk/destroy! session))))

(deftest test-async-cloud-session-with-caller-supplied-id-is-sent
  (testing "<create-session WITH :session-id sends sessionId on wire (PR #1479)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          custom-id "async-caller-supplied-id-xyz"
          result-ch (sdk/<create-session *test-client*
                                         {:on-permission-request sdk/approve-all
                                          :cloud {}
                                          :session-id custom-id})
          [session _] (alts!! [result-ch (timeout 5000)])
          create-params (get @seen "session.create")]
      (is (not (instance? Throwable session))
          (str "<create-session with caller-supplied id should not return an error: " session))
      (is (= custom-id (:sessionId create-params)))
      (is (= custom-id (sdk/session-id session)))
      (sdk/destroy! session))))
