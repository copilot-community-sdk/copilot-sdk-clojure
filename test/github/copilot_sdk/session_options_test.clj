(ns github.copilot-sdk.session-options-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.util :as util]))

(def ^:private coding {:mode :coding-agent})
(def ^:private isolated {:mode :empty})
(def ^:private no-tools {:tools {:available-tools []}})

(defn- open-params [resume? client opts]
  (if resume?
    (options/resume-params client "s1" opts)
    (options/create-params client opts)))

(defn- wire [params]
  ;; The existing protocol converts keyword keys before its JSON writer runs.
  (json/read-str (json/write-str (util/clj->wire params))))

(defn- error-path [f]
  (try (f) ::no-error
       (catch clojure.lang.ExceptionInfo e (:path (ex-data e)))))

(deftest grouped-native-options-cross-the-json-boundary
  (let [handler (fn [& _] (throw (AssertionError. "A builder called a handler")))
        opts {:model "fixture/chat" :working-directory "/project"
              :inference {:providers [{:name "fixture" :provider-type :openai
                                       :base-url "http://127.0.0.1:1/v1"
                                       :headers {"X-Mixed_CASE" "test"} :transport :http
                                       :model-provider :lm-studio}]
                          :models [{:provider "fixture" :id "chat" :model-id "base"
                                    :metadata {:vendor/stay-HERE [nil false {"HTTP_Value" 1}]}}]
                          :context-tier :long-context}
              :github {:capi {:enabled? false}}
              :events {:streaming? true :on-event handler}
              :tools {:available-tools []
                      :definitions [{:name "read" :parameters {:type "object" :properties {:User-ID {}}}
                                     :handler handler :skip-permission? false :idempotent? true}
                                    {:name "manual"}]}
              :permissions {:on-request handler}}]
    (doseq [resume? [false true]]
      (let [params (wire (open-params resume? coding opts))]
        (is (= (select-keys params ["model" "workingDirectory" "github" "events" "permissions"])
               {"model" "fixture/chat" "workingDirectory" "/project" "github" {"capi" {"enabled" false}}
                "events" {"streaming" true "includeSubAgentStreamingEvents" true}
                "permissions" {"request" true}}))
        (is (= (get params "inference")
               {"providers" [{"name" "fixture" "type" "openai" "baseUrl" "http://127.0.0.1:1/v1"
                              "headers" {"X-Mixed_CASE" "test"} "transport" "http" "modelProvider" "lm_studio"}]
                "models" [{"provider" "fixture" "id" "chat" "modelId" "base"
                           "metadata" {"vendor/stay-HERE" [nil false {"HTTP_Value" 1}]}}]
                "contextTier" "long_context"}))
        (is (= (get params "tools")
               {"availableTools" []
                "definitions" [{"name" "read" "hasHandler" true "skipPermission" false "idempotent" true
                                "parameters" {"type" "object" "properties" {"User-ID" {}}}}
                               {"name" "manual" "hasHandler" false}]}))
        (is (= (contains? params "sessionId") resume?))
        (is (identical? (get-in opts [:tools :definitions 0 :handler]) handler))))))

(deftest absent-null-false-and-empty-are-not-interchangeable
  (doseq [resume? [false true]]
    (let [build #(wire (open-params resume? coding %))]
      (is (= (get (build {}) "inference") {}))
      (is (= (get (build {:inference {}}) "inference") {}))
      (is (= (get (build {:inference {:default-provider nil :models [] :allow-multiple-tool-calls? false}})
                  "inference")
             {"defaultProvider" nil "models" [] "allowMultipleToolCalls" false}))
      (is (= (get (build {:events {:include-sub-agent-streaming-events? false}}) "events")
             {"includeSubAgentStreamingEvents" false}))
      (is (= (get (build {}) "permissions") {"request" resume?}))
      (is (= (get (build {}) "mcp") {"envValueMode" "direct"}))
      (is (= (get (build {:github {:token ""}}) "github") {"token" ""}))
      (is (= (select-keys (build {}) ["state" "sessionFs"]) {"state" {}})))))

(deftest stable-agent-mcp-policy-and-provider-routing
  (let [servers {"Build-TOOLS_2" {:command "server" :working-directory "." :tools []
                                  :env {"User_KEY" "${LITERAL}"}}
                 "remote" {:type :sse :url "https://example.invalid" :timeout 0
                           :headers {"X-Header_Case" "value"}}}
        wire-servers {"Build-TOOLS_2" {"command" "server" "cwd" "." "tools" [] "env" {"User_KEY" "${LITERAL}"}}
                      "remote" {"type" "sse" "url" "https://example.invalid" "timeout" 0
                                "headers" {"X-Header_Case" "value"}}}
        agent {:name "review" :prompt "" :infer? false :tools nil :mcp-servers servers
               :hooks {"Before-Invoke" [{:Vendor/keep_Name [nil {"x-Y" false}]}]}
               :model-policy :required}
        opts {:agents {:definitions [agent] :default {:excluded-tools []}}
              :mcp {:servers servers}
              :github {:mcp {:enable-all-tools? false :additional-tools []}
                       :capi {:auto-tier "Provider/Fast_v2"}}
              :policy {:managed-settings {:permissions {:limit-to [] :allow [] :ask ["Bash(*)"]
                                                        :disable-assisted-permissions-mode? false
                                                        :disable-bypass-permissions-mode "future-policy"}}}
              :extensions {:enabled? false :sdk-path "" :identity {:source "sdk" :name "x"}}
              :state {:infinite-sessions {:enabled? false :buffer-exhaustion-threshold 1.0
                                          :background-compaction-threshold 0.0}}}]
    (doseq [resume? [false true]]
      (let [p (wire (open-params resume? coding opts))]
        (is (= (get p "agents") {"definitions" [{"name" "review" "prompt" "" "infer" false "tools" nil
                                                 "hooks" {"Before-Invoke" [{"Vendor/keep_Name" [nil {"x-Y" false}]}]}
                                                 "modelPolicy" "required" "mcpServers" wire-servers}]
                                 "default" {"excludedTools" []}}))
        (is (= (get-in p ["mcp" "servers"]) wire-servers))
        (is (= (get p "policy") {"managedSettings" {"permissions" {"limitTo" [] "allow" [] "ask" ["Bash(*)"]
                                                                   "disableAssistedPermissionsMode" false
                                                                   "disableBypassPermissionsMode" "future-policy"}}}))
        (is (= (get p "github") {"mcp" {"enableAllTools" false "additionalTools" []}
                                 "capi" {"autoTier" "Provider/Fast_v2"}}))
        (is (= (get p "extensions") {"enabled" false "sdkPath" "" "identity" {"source" "sdk" "name" "x"}}))
        (is (= (get-in p ["state" "infiniteSessions"])
               {"enabled" false "bufferExhaustionThreshold" 1.0 "backgroundCompactionThreshold" 0.0}))))
    (is (nil? (options/open-options-patch coding opts)))))

(deftest only-registered-callbacks-are-advertised
  (let [handler (fn [& _] (throw (AssertionError. "a pure builder called the handler")))
        opts {:hooks {:handlers {:on-pre-mcp-tool-call handler :on-subagent-stop handler}}
              :github {:token-provider handler} :mcp {:on-auth-request handler}
              :system-message {:mode :customize :sections {:tool-efficiency {:action handler :content "ignored"}}}
              :interaction {:commands [{:name "status" :handler handler}]
                            :on-shell-authentication-request handler :on-user-input-request handler
                            :on-auto-mode-switch-request handler :on-exit-plan-mode-request handler
                            :on-elicitation-request handler :ask-user-variant :elicitation :auto-mode-recovery? true}}]
    (doseq [resume? [false true]]
      (let [p (wire (open-params resume? coding opts))
            idle (wire (open-params resume? coding {:hooks {:handlers {}} :interaction {:auto-mode-recovery? false}}))]
        (is (= (get p "hooks") {"hasHandlers" true}))
        (is (= (get p "github") {}))
        (is (= (get p "mcp") {"envValueMode" "direct"}))
        (is (= (get-in p ["systemMessage" "sections"]) {"tool_efficiency" {"action" "transform"}}))
        (is (= (get p "interaction")
               {"commands" [{"name" "status" "description" ""}] "askUserVariant" "elicitation"
                "requestUserInput" true "requestExitPlanMode" true "requestAutoModeSwitch" true
                "requestAutoModeRecovery" true "requestShellAuthentication" true "requestElicitation" true}))
        (is (= (get idle "hooks") {"hasHandlers" false}))
        (is (= (get idle "interaction") {"requestUserInput" false "requestElicitation" false
                                         "requestExitPlanMode" false "requestAutoModeSwitch" false}))))))

(deftest empty-mode-is-two-distinct-requests
  (let [opts (merge no-tools {:system-message {:mode :append :content "notes"}
                              :state {:enable-cross-session-store? true}
                              :git {:coauthor-enabled? true}
                              :skills {:included-builtin-skills ["s"]}
                              :agents {:local-discovery-only? false}})]
    (doseq [resume? [false true]]
      (let [params (wire (open-params resume? isolated opts))]
        (is (= (select-keys params ["tools" "skills" "agents" "instructions" "git" "github"
                                    "experiments" "hooks" "mcp" "state" "systemMessage"])
               {"tools" {"availableTools" []} "skills" {"enabled" false} "agents" {"localDiscoveryOnly" false}
                "instructions" {"onDemandDiscovery" false} "git" {"enableHostGitOperations" false}
                "github" {"telemetryEnabled" false} "experiments" {"enableExperimentalMode" false}
                "hooks" {"hasHandlers" false "enableRepositoryHooks" false}
                "mcp" {"envValueMode" "direct" "oauthTokenStorage" "in-memory"}
                "state" {"skipEmbeddingRetrieval" true "embeddingCacheStorage" "in-memory"
                         "enableCrossSessionStore" true "memory" {"enabled" false}}
                "systemMessage" {"mode" "customize" "content" "notes"
                                 "sections" {"environment_context" {"action" "remove"}}}}))
        (is (= (get-in (open-params resume? isolated
                                    (assoc no-tools :system-message
                                           {:mode :customize :sections {:environment-context {:action :preserve}}}))
                       ["systemMessage" "sections" "environment_context"])
               {"action" "preserve"}))
        (is (= (get (open-params resume? isolated (assoc no-tools :system-message {:mode :replace :content ""}))
                    "systemMessage")
               {"mode" "replace" "content" ""}))))
    (is (= (options/open-options-patch isolated opts)
           {"instructions" {"skipCustomInstructions" true} "agents" {"localDiscoveryOnly" false}
            "git" {"coauthorEnabled" true} "tools" {"manageScheduleEnabled" false}
            "plugins" {"installed" []} "skills" {"includedBuiltinSkills" ["s"]}}))
    (is (= (options/open-options-patch coding {:git {:coauthor-enabled? false}
                                               :skills {:included-builtin-skills []}
                                               :model "unchanged"})
           {"git" {"coauthorEnabled" false} "skills" {"includedBuiltinSkills" []}}))
    (is (nil? (options/open-options-patch coding no-tools)))))

(deftest create-and-resume-have-distinct-contracts
  (is (= (select-keys (options/create-params coding {:name "saved" :session-id "s"
                                                     :filesystem {:trust-working-directory :session}
                                                     :instructions {:refresh-custom-instructions? false}})
                      ["sessionId" "name" "filesystem" "instructions"])
         {"sessionId" "s" "name" "saved" "filesystem" {"trustWorkingDirectory" "session"}
          "instructions" {"refreshCustomInstructions" false}}))
  (is (= (select-keys (options/resume-params coding "s" {:continue-pending-work? true
                                                         :allow-transcript-recovery? false
                                                         :suppress-resume-event? false})
                      ["sessionId" "continuePendingWork" "allowTranscriptRecovery" "suppressResumeEvent"])
         {"sessionId" "s" "continuePendingWork" true "allowTranscriptRecovery" false "suppressResumeEvent" false}))
  (is (= (select-keys (options/create-params coding {:cloud {:repository {:owner "app" :name "demo"}}})
                      ["sessionId" "cloud"])
         {"cloud" {"repository" {"owner" "app" "name" "demo"}}}))
  (doseq [[resume? opts path] [[false {:suppress-resume-event? true} [:suppress-resume-event?]]
                               [false {:cloud {} :name "local-only"} [:name]]
                               [true {:cloud {}} [:cloud]]
                               [true {:session-id "same"} [:session-id]]
                               [true {:name "new"} [:name]]
                               [true {:filesystem {:trust-working-directory :user}} [:filesystem :trust-working-directory]]
                               [true {:instructions {:refresh-custom-instructions? false}} [:instructions :refresh-custom-instructions?]]]]
    (is (= (error-path #(open-params resume? coding opts)) path))))

(deftest after-open-checks-capabilities-before-registering-interests-and-patching
  (let [opts {:mcp {:on-auth-request identity}
              :interaction {:on-auto-mode-switch-request identity :auto-mode-recovery? true}
              :tools {:manage-schedule-enabled? false}}
        ack {:session-id "canonical" :capabilities {:ui {:auto-mode-recovery true}}}]
    (is (= (options/after-open-requests coding opts ack)
           [["session.eventLog.registerInterest" {"sessionId" "canonical" "eventType" "mcp.oauth_required"}]
            ["session.options.update" {"sessionId" "canonical" "tools" {"manageScheduleEnabled" false}}]]))
    (is (= (options/after-open-requests coding {} {:session-id "s"}) []))
    (doseq [capabilities [nil {:ui {}} {:ui {:auto-mode-recovery false}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not acknowledge"
                            (options/after-open-requests coding opts (assoc ack :capabilities capabilities)))))))

(deftest invalid-options-fail-at-the-caller-path
  (doseq [resume? [false true]
          [client opts path]
          [[{} {} [:mode]]
           [{:mode :copilot-cli} {} [:mode]]
           [isolated {} [:tools :available-tools]]
           [isolated {:tools {:available-tools nil}} [:tools :available-tools]]
           [coding {:tools {:excluded-tools ["*"]}} [:tools :excluded-tools 0]]
           [coding {:tools nil} [:tools]]
           [coding {:available-tools []} [:available-tools]]
           [coding {:inference {:provider {}}} [:inference :provider]]
           [coding {:inference {:providers [{:name "p"}]}} [:inference :providers 0 :base-url]]
           [coding {:inference {:providers [{:name "p" :base-url "u" :provider-type :google}]}} [:inference :providers 0 :provider-type]]
           [coding {:inference {:models nil}} [:inference :models]]
           [coding {:tools {:definitions [{:tool-name "old"}]}} [:tools :definitions 0 :tool-name]]
           [coding {:tools {:definitions [{:name "t" :metadata {:x #'identity}}]}} [:tools :definitions 0 :metadata]]
           [coding {:tools {:definitions [{:name "t" :parameters {:x 1 "x" 2}}]}} [:tools :definitions 0 :parameters]]
           [coding {:tools {:manage-schedule-enabled? nil}} [:tools :manage-schedule-enabled?]]
           [coding {:permissions {:on-request :not-a-function}} [:permissions :on-request]]
           [coding {:permissions {:request true}} [:permissions :request]]
           [coding {:permissions {:mode :manual}} [:permissions :mode]]
           [coding {:diagnostics {}} [:diagnostics]]
           [coding {:state {:create-session-fs-provider nil}} [:state :create-session-fs-provider]]
           [coding {:hooks {:handlers {:on-new-hook identity}}} [:hooks :handlers :on-new-hook]]
           [coding {:interaction {:auto-mode-recovery? true}} [:interaction :auto-mode-recovery?]]
           [coding {:interaction {:commands [{:name "cmd"}]}} [:interaction :commands 0 :handler]]
           [coding {:interaction {:commands (vec (repeat 2 {:name "cmd" :handler identity}))}} [:interaction :commands]]
           [coding {:github {:token "" :token-provider identity}} [:github :token-provider]]
           [coding {:github {:token-provider-registration-id "forged"}} [:github :token-provider-registration-id]]
           [coding {:mcp {:servers {"s" {:type :sse :command "bad"}}}} [:mcp :servers "s" :command]]
           [coding {:mcp {:servers {"s" {:type :http :url "u" :oauth {}}}}} [:mcp :servers "s" :oauth]]
           [coding {:agents {:definitions [{:prompt "" :name "a" :tools false}]}} [:agents :definitions 0 :tools]]
           [coding {:policy {:managed-settings {:permissions {:limit-to nil}}}} [:policy :managed-settings :permissions :limit-to]]
           [coding {:github {:capi {:fusion-tier :max}}} [:github :capi :fusion-tier]]
           [coding {:github {:capi {:auto-tier "provider uncontrolled"}}} [:github :capi :auto-tier]]
           [coding {:inference {:capabilities nil}} [:inference :capabilities]]
           [coding {:permissions {:sandbox {:enabled true}}} [:permissions :sandbox]]
           [coding {:events {:on-event nil}} [:events :on-event]]
           [coding {:canvases {}} [:canvases]]]]
    (testing (pr-str [resume? path])
      (is (= (error-path #(open-params resume? client opts)) path)))))

(deftest invalid-opaque-data-fails-without-realizing-or-printing-it
  (let [cycle (java.util.HashMap.)
        _ (.put cycle "self" cycle)
        lazy-items (lazy-seq (throw (AssertionError. "Opaque arrays must not evaluate a lazy sequence")))]
    (doseq [value [cycle lazy-items ##NaN]]
      (is (= (error-path #(options/create-params coding {:tools {:definitions [{:name "t" :metadata {"x" value}}]}}))
             [:tools :definitions 0 :metadata])))))

(deftest filesystem-provider-factory-is-local-only
  (let [factory (fn [_] (throw (AssertionError. "Option encoding invoked the factory")))]
    (doseq [resume? [true false]]
      (is (= (get (wire (open-params resume? coding {:state {:create-session-fs-provider factory}})) "state")
             {})))))

(deftest model-switch-shares-tuning-but-not-the-open-options-contract
  (let [capabilities {:supports {:vision? false :tool-calls? true :reasoning-effort? false
                                 :adaptive-thinking :adaptive-only}
                      :limits {:max-prompt-tokens 0 :max-output-tokens 64 :max-context-window-tokens 128
                               :vision {:supported-media-types [] :max-prompt-images 1 :max-prompt-image-size 0}}}
        tuning {:reasoning-effort "vendor-defined" :reasoning-summary :none :context-tier :long-context}
        expected {"supports" {"vision" false "toolCalls" true "reasoningEffort" false "adaptive_thinking" "adaptive_only"}
                  "limits" {"max_prompt_tokens" 0 "max_output_tokens" 64 "max_context_window_tokens" 128
                            "vision" {"supported_media_types" [] "max_prompt_images" 1 "max_prompt_image_size" 0}}}
        wire-tuning {"reasoningEffort" "vendor-defined" "reasoningSummary" "none" "contextTier" "long_context"}
        switch #(wire (options/model-switch-params "s" "vendor/model" %))
        required {"modelId" "vendor/model" "sessionId" "s"}]
    (is (= (switch {}) required))
    (is (= (switch {:model-capabilities {}}) (assoc required "modelCapabilities" {})))
    (is (= (switch (assoc tuning :model-capabilities capabilities))
           (merge required wire-tuning {"modelCapabilities" expected})))
    (doseq [resume? [false true]]
      (is (= (get (wire (open-params resume? coding {:inference (assoc tuning :capabilities capabilities)}))
                  "inference")
             (assoc wire-tuning "capabilities" expected))))
    (doseq [k [:default-provider :allow-multiple-tool-calls? :inference :capabilities :auto-tier :fusion-tier
               :provider-id :verbosity :reasoning-effort :reasoning-summary :context-tier :model-capabilities]]
      (is (= (error-path #(switch {k nil})) [k])))
    (is (= (error-path #(switch {:model-capabilities {:supports {:thinking? true}}}))
           [:model-capabilities :supports :thinking?]))
    (is (= (error-path #(switch {:model-capabilities {:limits {:vision {:max-prompt-images 0}}}}))
           [:model-capabilities :limits :vision :max-prompt-images]))))

(deftest send-parameters-match-high-level-message-options
  (let [params (options/send-params
                "s1" {:prompt "model input" :display-prompt "" :client-turn-id "Turn/17"
                      :mode :enqueue :agent-mode :plan :source {:automation-id " Morning/Report "}
                      :request-headers {"X-Trace_Case" "id"}
                      :attachments [{:type :file :path "src/a" :display-name "a"}
                                    {:type :directory :path "src"}
                                    {:type :selection :file-path "a.clj" :display-name "range"
                                     :selection-range {:start {:line 0 :character 0}
                                                       :end {:line 0 :character 3}} :text "abc"}
                                    {:type :blob :data "YQ==" :mime-type "text/plain"}
                                    {:type :extension-context :extension-id "ext" :title "context"
                                     :canvas-id "canvas" :instance-id "one" :captured-at "2026-01-17T00:00:00Z"
                                     :payload {:ext/Key_Name [nil false]}}]
                      :response-schema {:type "object" :properties {:Owner-ID {:type "string"}}
                                        :additionalProperties false}})]
    (is (= params
           {"sessionId" "s1" "prompt" "model input" "displayPrompt" "" "clientTurnId" "Turn/17"
            "source" "automation- Morning/Report " "agentMode" "plan" "mode" "enqueue"
            "requestHeaders" {"X-Trace_Case" "id"}
            "attachments" [{"type" "file" "path" "src/a" "displayName" "a"}
                           {"type" "directory" "path" "src"}
                           {"type" "selection" "filePath" "a.clj" "displayName" "range"
                            "selection" {"start" {"line" 0 "character" 0} "end" {"line" 0 "character" 3}}
                            "text" "abc"}
                           {"type" "blob" "data" "YQ==" "mimeType" "text/plain"}
                           {"type" "extension_context" "extensionId" "ext" "title" "context"
                            "canvasId" "canvas" "instanceId" "one" "capturedAt" "2026-01-17T00:00:00Z"
                            "payload" {"ext/Key_Name" [nil false]}}]
            "responseFormat" {"type" "json_schema"
                              "jsonSchema" {"name" "response" "strict" true
                                            "schema" {"type" "object" "properties" {"Owner-ID" {"type" "string"}}
                                                      "additionalProperties" false}}}}))
    (is (= (wire params) params)))
  (is (= (options/send-params "s" "") {"sessionId" "s" "prompt" ""}))
  (is (= (options/send-params "s" {:prompt "" :mode :immediate :attachments [] :request-headers {} :client-turn-id ""})
         {"sessionId" "s" "prompt" "" "mode" "immediate" "attachments" [] "requestHeaders" {} "clientTurnId" ""}))
  (doseq [[source expected] [[:user "user"] [:system "system"]
                             [{:agent-id ""} "agent-"] [{:agent-id "agent-X "} "agent-agent-X "]]]
    (is (= (get (options/send-params "s" {:prompt "p" :source source}) "source") expected))))

(deftest send-rejects-nonpublic-fields-and-invalid-combinations
  (doseq [[opts path]
          [[{} [:prompt]] [{:prompt nil} [:prompt]]
           [{:prompt "p" :message {}} [:message]]
           [{:prompt "p" :source "system"} [:source]]
           [{:prompt "p" :source {:automation-id ""}} [:source :automation-id]]
           [{:prompt "p" :source {:agent-id "a" :automation-id "b"}} [:source]]
           [{:prompt "p" :client-turn-id false} [:client-turn-id]]
           [{:prompt "p" :attachments nil} [:attachments]]
           [{:prompt "p" :attachments [{:type :github-reference}]} [:attachments 0 :type]]
           [{:prompt "p" :attachments [{:type :file :path "a" :line-range {}}]} [:attachments 0 :line-range]]
           [{:prompt "p" :attachments [{:type :selection :file-path "a"}]} [:attachments 0 :display-name]]
           [{:prompt "p" :attachments [{:type :blob :data "YQ=="}]} [:attachments 0 :mime-type]]
           [{:prompt "p" :request-headers {:X-Custom "x"}} [:request-headers]]
           [{:prompt "p" :timeout-ms 100} [:timeout-ms]]
           [{:prompt "p" :response-format {}} [:response-format]]
           [{:prompt "p" :response-schema nil} [:response-schema]]
           [{:prompt "p" :response-schema {:properties {:x {} "x" {}}}} [:response-schema]]
           [{:prompt "p" :response-schema {:to-json-schema identity :parse identity}} [:response-schema]]
           [{:prompt "p" :mode :immediate :response-schema {}} [:response-schema]]]]
    (is (= (error-path #(options/send-params "s" opts)) path))))
