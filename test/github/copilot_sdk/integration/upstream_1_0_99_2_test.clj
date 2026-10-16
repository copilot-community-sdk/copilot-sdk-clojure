(ns github.copilot-sdk.integration.upstream-1-0-99-2-test
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.teardown :as teardown]
            [github.copilot-sdk.util :as util]))

(use-fixtures :each with-mock-server)

(def ^:private operations [:create :create-channel :resume :resume-channel :join])

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 15000)])]
    (when (or (not= port channel) (nil? value))
      (throw (ex-info "SDK operation did not produce a result" {:closed? (= port channel)})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- open-session [operation config]
  (case operation
    :create (sdk/create-session *test-client* config)
    :create-channel (read-result! (sdk/<create-session *test-client* config))
    (let [seed (sdk/create-session *test-client* {})
          session-id (sdk/session-id seed)]
      (sdk/disconnect! seed)
      (case operation
        :resume (sdk/resume-session *test-client* session-id config)
        :resume-channel (read-result! (sdk/<resume-session *test-client* session-id config))
        :join (:session
               (with-redefs-fn
                 {#'client/foreground-session-id (constantly session-id)
                  #'client/client (constantly *test-client*)}
                 #(sdk/join-session config)))))))

(defn- record-requests! []
  (let [requests (atom [])]
    (mock/set-request-hook! *mock-server*
                            (fn [method params] (swap! requests conj [method params])))
    requests))

(defn- session-params [requests]
  (second (last (filter #(contains? #{"session.create" "session.resume"} (first %))
                        requests))))

(deftest session-options-preserve-values-and-omission-in-every-builder
  (doseq [operation operations
          [config expected]
          [[{} {}]
           [{:interactive-user? false :enable-github-cache-store? false
             :skill-directories [] :instruction-directories []
             :agent-directories [] :hook-directories []}
            {:interactiveUser false :enableGitHubCacheStore false
             :skillDirectories [] :instructionDirectories []
             :agentDirectories [] :hookDirectories []}]
           [{:interactive-user? true :enable-github-cache-store? true
             :skill-directories ["relative/skills"]
             :instruction-directories ["/absolute/instructions"]
             :agent-directories ["relative/agents" "/absolute/agents"]
             :hook-directories ["relative/hooks" "/absolute/hooks"]}
            {:interactiveUser true :enableGitHubCacheStore true
             :skillDirectories ["relative/skills"] :instructionDirectories ["/absolute/instructions"]
             :agentDirectories ["relative/agents" "/absolute/agents"]
             :hookDirectories ["relative/hooks" "/absolute/hooks"]}]
           [{:skill-directories ["" "  " " spaced/skills "]
             :instruction-directories ["" "  " " spaced/instructions "]
             :agent-directories ["" "  " " spaced/agents "]
             :hook-directories ["" "  " " spaced/hooks "]}
            {:skillDirectories ["" "  " " spaced/skills "]
             :instructionDirectories ["" "  " " spaced/instructions "]
             :agentDirectories ["" "  " " spaced/agents "]
             :hookDirectories ["" "  " " spaced/hooks "]}]]]
    (testing (str operation " " config)
      (let [requests (record-requests!)
            session (open-session operation (merge {:enable-config-discovery false
                                                    :enable-file-hooks false} config))
            keys [:interactiveUser :enableGitHubCacheStore :skillDirectories :instructionDirectories
                  :agentDirectories :hookDirectories]]
        (teardown/call-with-cleanup
         #(do
            (is (= (select-keys (session-params @requests) keys) expected))
            (is (not (contains? (session-params @requests) :name)))
            (is (not (contains? (session-params @requests) :enableGithubCacheStore)))
            (is (false? (:enableConfigDiscovery (session-params @requests))))
            (is (false? (:enableFileHooks (session-params @requests))))
            (is (not (contains? (session-params @requests) :additionalDirectories)))
            (doseq [[method params] @requests :when (= method "session.options.update")]
              (is (= (select-keys params keys) {})))
            (is (nil? (#'client/build-session-options-update-patch *test-client* config))))
         #(sdk/disconnect! session))))))

(deftest session-options-reject-invalid-values-before-their-rpc
  (doseq [operation operations
          config [{:interactive-user? nil} {:interactive-user? "false"}
                  {:enable-github-cache-store? nil} {:enable-github-cache-store? 0}
                  {:agent-directories nil} {:agent-directories "agents"}
                  {:agent-directories [nil]} {:hook-directories nil}
                  {:hook-directories "hooks"} {:hook-directories [false]}]]
    (testing (str operation " " config)
      (let [requests (record-requests!)
            method (if (#{:create :create-channel} operation) "session.create" "session.resume")]
        (is (thrown? clojure.lang.ExceptionInfo (open-session operation config)))
        (is (empty? (filter #(= (first %) method) @requests)))))))

(deftest session-names-are-create-only
  (doseq [operation [:create :create-channel], name ["named session" (apply str (repeat 100 "x"))]]
    (let [requests (record-requests!)
          session (open-session operation {:name name})]
      (teardown/call-with-cleanup
       #(do
          (is (= (:name (session-params @requests)) name))
          (doseq [[method params] @requests :when (= method "session.options.update")]
            (is (not (contains? params :name)))))
       #(sdk/disconnect! session))))
  (doseq [spec [::specs/resume-session-config ::specs/join-session-config]]
    (is (not (s/valid? spec {:name "not a rename"}))))
  (doseq [name [nil false 1]]
    (is (not (s/valid? ::specs/session-config {:name name})))))

(def ^:private agent-hooks
  {"preToolUse" [{"type" "command"
                  "bash" "printf '{\"permissionDecision\":\"allow\"}'"
                  "Opaque_Field" {"MixedCase" [nil false 0 {"keep_me" "value"}]}}]
   "userPromptSubmitted" []})

(deftest custom-agent-hooks-preserve-opaque-json-in-every-builder
  (doseq [operation operations, hooks [::absent {} agent-hooks]]
    (let [agent (cond-> {:agent-name "reviewer" :agent-prompt "Review carefully."}
                  (not= hooks ::absent) (assoc :agent-hooks hooks))
          requests (record-requests!)
          session (open-session operation {:custom-agents [agent]})]
      (teardown/call-with-cleanup
       #(let [wire (first (:customAgents (session-params @requests)))]
          (is (= (dissoc wire :hooks) {:name "reviewer" :prompt "Review carefully."}))
          (if (= hooks ::absent)
            (is (not (contains? wire :hooks)))
            (is (= (:hooks wire) (json/read-str (json/write-str hooks) :key-fn keyword)))))
       #(sdk/disconnect! session))))
  (doseq [hooks [nil [] {"preToolUse" nil} {"preToolUse" [nil]}
                 {"preToolUse" [{"nested" {:keyword-key true}}]}
                 {"preToolUse" [{"nested" #{1}}]} {"preToolUse" [{"value" ##NaN}]}
                 {:preToolUse []}]]
    (let [requests (record-requests!)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/create-session *test-client*
                                       {:custom-agents [{:agent-name "invalid"
                                                         :agent-prompt "Invalid hooks"
                                                         :agent-hooks hooks}]})))
      (is (empty? @requests)))))

(deftest permission-callbacks-receive-derived-prompt-context
  (doseq [managed? [false true]
          prompt [::absent
                  {:kind "commands" :fullCommandText "echo safe" :intention ""
                   :commandIdentifiers ["echo"] :canOfferSessionApproval false
                   :assistedApproval {:recommendation "approve"}}
                  {:kind "commands" :fullCommandText "echo safe" :intention ""
                   :commandIdentifiers ["echo"] :canOfferSessionApproval false
                   :managedApprovalRequired false}
                  {:kind "commands" :fullCommandText "echo safe" :intention ""
                   :commandIdentifiers ["echo"] :canOfferSessionApproval true
                   :managedApprovalRequired true}]]
    (let [handled (promise)
          replied (promise)
          session (sdk/create-session
                   *test-client*
                   (cond-> {:on-permission-request (fn [request invocation]
                                                     (deliver handled [request invocation])
                                                     {:kind :approve-once})}
                     managed? (assoc :managed-settings {})))
          id (sdk/session-id session)
          request {:kind "shell" :fullCommandText "echo safe" :intention ""
                   :managedApprovalRequired true}
          data (cond-> {:requestId "permission" :permissionRequest request}
                 (not= prompt ::absent) (assoc :promptRequest prompt))]
      (teardown/call-with-cleanup
       #(do
          (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= method "session.permissions.handlePendingPermissionRequest")
               (deliver replied params))))
          (mock/send-v3-broadcast-event! *mock-server* id "permission.requested" data)
          (let [[actual invocation] (await-value! handled "permission callback" 5000)]
            (is (= actual (util/wire->clj request)))
            (is (s/valid? ::specs/permission-handler-invocation invocation))
            (is (= invocation
                   (cond-> {:session-id id :managed-settings-enabled? managed?}
                     (not= prompt ::absent) (assoc :prompt-request (util/wire->clj prompt))))))
          (is (= (:result (await-value! replied "permission response" 5000))
                 {:kind "approve-once"})))
       #(sdk/disconnect! session)))))

(deftest permission-invocation-spec-checks-derived-managed-markers
  (doseq [marker [false true]]
    (is (s/valid? ::specs/permission-handler-invocation
                  {:session-id "session"
                   :prompt-request {:kind "commands" :managed-approval-required marker}})))
  (doseq [marker [nil 0 "false"]]
    (is (not (s/valid? ::specs/permission-handler-invocation
                       {:session-id "session"
                        :prompt-request {:kind "commands" :managed-approval-required marker}})))))

(deftest every-hook-kind-preserves-actor-metadata-and-root-omission
  (let [hooks [["preToolUse" :on-pre-tool-use] ["preMcpToolCall" :on-pre-mcp-tool-call]
               ["postToolUse" :on-post-tool-use] ["postToolUseFailure" :on-post-tool-use-failure]
               ["userPromptSubmitted" :on-user-prompt-submitted]
               ["userPromptTransformed" :on-user-prompt-transformed]
               ["sessionStart" :on-session-start] ["sessionEnd" :on-session-end]
               ["errorOccurred" :on-error-occurred] ["agentStop" :on-agent-stop]
               ["subagentStart" :on-subagent-start] ["subagentStop" :on-subagent-stop]]
        received (atom nil)
        session (sdk/create-session
                 *test-client*
                 {:hooks (into {} (for [[_ key] hooks]
                                    [key (fn [input invocation]
                                           (reset! received [key input invocation])
                                           nil)]))})]
    (teardown/call-with-cleanup
     #(doseq [[hook key] hooks
              actor [{} {:agentId "child" :agentType "reviewer" :parentAgentId "parent"}]]
        (reset! received nil)
        (let [input (merge {:sessionId "reported-session" :timestamp 0 :cwd "/workspace"} actor)]
          (mock/send-rpc-request! *mock-server* "hooks.invoke"
                                  {:sessionId (sdk/session-id session) :hookType hook :input input})
          (is (= @received [key (util/wire->clj input) {:session-id (sdk/session-id session)}]))))
     #(sdk/disconnect! session))))

(deftest hook-errors-normalize-only-the-two-declared-string-inputs
  (doseq [[hook key] [["errorOccurred" :on-error-occurred] ["sessionEnd" :on-session-end]]
          [error expected] [[::absent ::absent] [nil nil] [false false] ["raw" "raw"] [[] []]
                            [{:message "failure" :name "Error" :stack "trace"} "failure"]
                            [{:message ""} ""]
                            [{:message 42 :Error_Code {:MixedCase false}}
                             (json/write-str {:message 42 :Error_Code {:MixedCase false}})]
                            [{"service/code" 1 "other/code" 2 "nested" {"vendor/detail" false}}
                             (json/write-str {"service/code" 1 "other/code" 2
                                              "nested" {"vendor/detail" false}})]
                            [{} "{}"]]]
    (let [received (promise)
          session (sdk/create-session *test-client*
                                      {:hooks {key (fn [input _] (deliver received input) nil)}})
          input (cond-> {:sessionId "child" :timestamp 0 :cwd "/workspace"
                         :agentId "" :agentType "reviewer" :parentAgentId "parent"}
                  (not= error ::absent) (assoc :error error))]
      (teardown/call-with-cleanup
       #(do
          (is (= (:result (mock/send-rpc-request!
                           *mock-server* "hooks.invoke"
                           {:sessionId (sdk/session-id session) :hookType hook :input input}))
                 {}))
          (is (= (await-value! received "hook input" 5000)
                 (cond-> {:session-id "child" :timestamp 0 :cwd "/workspace"
                          :agent-id "" :agent-type "reviewer" :parent-agent-id "parent"}
                   (not= expected ::absent) (assoc :error expected)))))
       #(sdk/disconnect! session))))
  (let [received (promise)
        session (sdk/create-session
                 *test-client*
                 {:hooks {:on-post-tool-use-failure
                          (fn [input _] (deliver received input) nil)}})]
    (teardown/call-with-cleanup
     #(do
        (mock/send-rpc-request! *mock-server* "hooks.invoke"
                                {:sessionId (sdk/session-id session) :hookType "postToolUseFailure"
                                 :input {:error {:message "not normalized"} :timestamp 0 :cwd "/workspace"}})
        (let [input (await-value! received "other hook input" 5000)]
          (is (= (:error input) {:message "not normalized"}))
          (is (not-any? (partial contains? input) [:agent-id :agent-type :parent-agent-id]))))
     #(sdk/disconnect! session))))

(deftest user-prompt-block-decisions-reach-the-runtime
  (let [session (sdk/create-session
                 *test-client*
                 {:hooks {:on-user-prompt-submitted
                          (fn [_ _] {:decision "block" :reason "Needs human review."})}})]
    (teardown/call-with-cleanup
     #(is (= (get-in (mock/send-rpc-request!
                      *mock-server* "hooks.invoke"
                      {:sessionId (sdk/session-id session) :hookType "userPromptSubmitted"
                       :input {:prompt "blocked prompt" :timestamp 0 :cwd "/workspace"}})
                     [:result :output])
             {:decision "block" :reason "Needs human review."}))
     #(sdk/disconnect! session))))

(def ^:private string-fields
  {:values ["" "future_value"] :invalid [nil false 1]})
(def ^:private count-fields
  {:values [0 1] :invalid [nil -1 0.5 false "1" ##NaN ##Inf]})
(def ^:private event-contracts
  [{:type :copilot/session.error :base {:error-type "policy" :message "Provider refused."}
    :specs [::specs/session.error-data ::gen/session.error-data]
    :fields {[:provider-error-param] string-fields [:provider-error-type] string-fields
             [:remediation] {:values ["rewind_conversation"] :invalid [nil "invented"]}}}
   {:type :copilot/session.compaction_start :base {}
    :specs [::specs/session.compaction_start-data ::gen/session.compaction_start-data]
    :fields {[:compaction-attempt-id] string-fields [:switch-target-model] string-fields
             [:target-prompt-token-limit] count-fields}}
   {:type :copilot/session.compaction_complete :base {:success false}
    :specs [::specs/session.compaction_complete-data ::gen/session.compaction_complete-data]
    :fields {[:compaction-attempt-id] string-fields [:model] string-fields
             [:initial-model] string-fields [:switch-target-model] string-fields
             [:failure-reason] string-fields [:outcome] string-fields [:outcome-reason] string-fields
             [:token-accounting-basis] string-fields [:target-prompt-token-limit] count-fields
             [:compaction-tokens-used :request-count] count-fields}}
   {:type :copilot/permission.requested
    :base {:request-id "request"
           :permission-request {:kind "write" :file-name "/file" :diff "" :intention ""
                                :can-offer-session-approval false}}
    :specs [::specs/permission.requested-data ::gen/permission.requested-data]
    :fields {[:owner-session-id] string-fields [:owner-agent-id] string-fields}}
   {:type :copilot/session.permission_recovery
    :base {:episode-id "episode" :status "recovering" :reason "permission_required"
           :on-blocked "ask" :max-attempts 1 :attempts []}
    :specs [::specs/session.permission_recovery-data ::gen/session.permission_recovery-data]
    :fields {[:owner-session-id] string-fields [:owner-agent-id] string-fields}}])

(deftest event-additions-have-exact-optional-specs
  (doseq [{:keys [type base fields specs]} event-contracts, spec specs]
    (testing (str type " " spec)
      (is (s/valid? spec base))
      (is (s/valid? spec (assoc base :future-field false)))
      (doseq [[path {:keys [values invalid]}] fields]
        (doseq [value values]
          (is (s/valid? spec (assoc-in base path value)) (str path " " value)))
        (doseq [value invalid]
          (is (not (s/valid? spec (assoc-in base path value))) (str path " " value)))))))

(deftest event-additions-preserve-values-on-live-and-history-paths
  (let [started (promise)
        session (sdk/create-session
                 *test-client*
                 {:on-event #(when (= (:type %) :copilot/session.start) (deliver started true))})]
    (teardown/call-with-cleanup
     #(do
        (await-value! started "session start" 5000)
        (let [events (sdk/subscribe-events session)
              id (sdk/session-id session)]
          (teardown/call-with-cleanup
           (fn []
             (doseq [{:keys [type base fields]} event-contracts
                     data (cons base (for [[path {:keys [values]}] fields, value values]
                                       (assoc-in base path value)))
                     :let [wire (util/clj->wire data)]]
               (mock/send-session-event! *mock-server* id type wire)
               (is (= (:data (await-event-type! events type 5000)) data))
               (mock/set-session-messages! *mock-server* id [{:type (name type) :data wire}])
               (is (= (:data (first (sdk/get-messages session))) data))))
           (fn [] (sdk/unsubscribe-events! session events)))))
     #(sdk/disconnect! session))))

(deftest retired-factory-id-is-not-a-curated-contract
  (is (nil? (s/get-spec ::specs/factory-run-id)))
  (is (s/valid? ::specs/workflow-run-id "workflow"))
  (doseq [spec [::specs/subagent.started-data ::gen/subagent.started-data]
          value ["legacy-run" nil false [] {}]]
    (is (s/valid? spec {:tool-call-id "task" :agent-name "worker"
                        :agent-display-name "Worker" :agent-description "Review"
                        :factory-run-id value}))))

(deftest passive-tool-usage-preserves-opaque-quota-keys
  (let [session (sdk/create-session *test-client* {})
        events (sdk/subscribe-events session)
        id (sdk/session-id session)
        quota {:Provider_Quota {:MixedCase [nil false 0 {:reset_at "unparsed"}]}}
        wire {:toolCallId "tool" :success false
              :toolUsage {:requestId "operation" :model "image-model"
                          :inputTokens 0 :quotaSnapshots quota}}
        expected {:tool-call-id "tool" :success false
                  :tool-usage {:request-id "operation" :model "image-model"
                               :input-tokens 0 :quota-snapshots quota}}]
    (teardown/call-with-cleanup
     #(do
        (mock/send-session-event! *mock-server* id :copilot/tool.execution_complete wire)
        (is (= (:data (await-event-type! events :copilot/tool.execution_complete 5000)) expected))
        (mock/set-session-messages! *mock-server* id [{:type "tool.execution_complete" :data wire}])
        (is (= (:data (first (sdk/get-messages session))) expected))
        (is (s/valid? ::gen/tool.execution_complete-data expected))
        (is (nil? (s/get-spec ::specs/tool-usage))))
     #(do (sdk/unsubscribe-events! session events)
          (sdk/disconnect! session)))))
