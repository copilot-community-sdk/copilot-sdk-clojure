(ns ^:no-doc github.copilot-sdk.session-options
  "Pure builders for native session open, post-open options and message requests.
  Protocol 4, runtime 1.0.116, microsoft/copilot-sdk 2.0.3:
  5671812ecd4ce58ee040e9a41d98da2f849c8095, nodejs/src/{types,client,session}.ts.

  Only declared keys are accepted. SDK keys become exact JSON strings before
  the protocol's recursive key conversion. Host metadata and schemas keep
  their own spellings. The client owns session IDs, callback registration
  before the first RPC, event delivery and cleanup; no builder calls a handler."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [copilot.sdk.specs :as specs]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.util :as util]))

(defn- invalid! [path message]
  ;; Option values can contain credentials; do not attach them to errors.
  (throw (ex-info (str "Invalid session option at " (pr-str path) ": " message)
                  {:path path})))

(defn- scalar [pred expected]
  (fn [path value]
    (if (pred value) value (invalid! path expected))))

(def ^:private text (scalar string? "expected a string"))
(def ^:private identifier (scalar #(and (string? %) (not (str/blank? %))) "expected a nonblank string"))
(def ^:private bool (scalar boolean? "expected a boolean"))
(def ^:private nonnegative (scalar nat-int? "expected a nonnegative integer"))
(def ^:private positive (scalar pos-int? "expected a positive integer"))
(def ^:private fraction (scalar #(and (number? %) (not (ratio? %)) (<= 0 % 1))
                                "expected a number between 0 and 1"))

(defn- nullable [encode]
  (fn [path value] (when-not (nil? value) (encode path value))))

(defn- choice [members]
  (fn [path value]
    (if (contains? members value)
      (name value)
      (invalid! path (str "expected one of " (pr-str (sort members)))))))

(defn- underscore-choice [members]
  (let [encode (choice members)]
    (fn [path value] (str/replace (encode path value) "-" "_"))))

(defn- array-of [encode]
  (fn [path xs]
    (when-not (vector? xs) (invalid! path "expected a vector"))
    (mapv #(encode (conj path %1) %2) (range) xs)))

(def ^:private strings (array-of text))
(def ^:private tool-filters
  (array-of (scalar #(and (string? %) (not= "*" %))
                    "expected a tool name or a source-qualified pattern, not a bare *")))

(defn- object
  "Fields map input keys to [wire-name encoder]. A nil wire name is local-only."
  ([fields] (object fields []))
  ([fields required]
   (fn [path value]
     (when-not (map? value) (invalid! path "expected an option map"))
     (let [encoded (reduce-kv
                    (fn [out k v]
                      (when-not (keyword? k) (invalid! path "expected keyword option names"))
                      (let [at (conj path k)]
                        (if-let [[wire-name encode] (get fields k)]
                          (let [result (encode at v)]
                            (if wire-name (assoc out wire-name result) out))
                          (invalid! at "unsupported option"))))
                    {} value)]
       (doseq [k required :when (not (contains? value k))]
         (invalid! (conj path k) "required option is missing"))
       encoded))))

(defn- callback [path value]
  (if (fn? value) true (invalid! path "expected a callback function")))

(defn- json-value [path value]
  (try (util/opaque-json->wire value)
       (catch clojure.lang.ExceptionInfo _ (invalid! path "expected JSON data with unique member names"))))

(defn- json-object [path value]
  (when-not (map? value) (invalid! path "expected a JSON object"))
  (json-value path value))

(def ^:private string-dictionary
  (scalar #(and (map? %) (every? string? (keys %)) (every? string? (vals %)))
          "expected a map of string names to string values"))

(defn- dictionary [encode]
  (fn [path entries]
    (when-not (and (map? entries) (every? string? (keys entries)))
      (invalid! path "expected a map with string names"))
    (reduce-kv (fn [out k v] (assoc out k (encode (conj path k) v))) {} entries)))

(defn- declarations [encode]
  (let [all (array-of encode)]
    (fn [path definitions]
      (let [wire (all path definitions)]
        (when-not (= (count wire) (count (into #{} (map :name) definitions)))
          (invalid! path "declaration names must be unique"))
        wire))))

;; The named BYOK catalog is explicitly supported here, but still experimental upstream.
(def ^:private provider
  (object
   {:name ["name" (scalar #(and (string? %) (not (str/blank? %)) (not (str/includes? % "/")))
                          "expected a provider name without /")]
    :base-url ["baseUrl" identifier]
    :provider-type ["type" (choice #{:openai :azure :anthropic :winml})]
    :wire-api ["wireApi" (choice #{:completions :responses})]
    :transport ["transport" (choice #{:http :websockets})]
    :model-provider ["modelProvider" (underscore-choice #{:openai :anthropic :azure-openai :ollama
                                                          :lm-studio :foundry-local :llama-cpp})]
    :api-key ["apiKey" text] :api-key-command ["apiKeyCommand" text] :bearer-token ["bearerToken" text]
    :headers ["headers" string-dictionary] :local-model ["localModel" json-value]
    :azure ["azure" (object {:api-version ["apiVersion" text]})]}
   [:name :base-url]))

(def ^:private provider-model
  (object
   {:provider ["provider" identifier] :id ["id" identifier] :model-id ["modelId" text]
    :wire-model ["wireModel" text] :name ["name" text]
    :max-prompt-tokens ["maxPromptTokens" positive] :max-output-tokens ["maxOutputTokens" positive]
    :max-context-window-tokens ["maxContextWindowTokens" positive]
    :metadata ["metadata" json-object] :auto-local ["autoLocal" json-value]}
   [:provider :id]))

(def ^:private tool-fields
  (object
   {:name ["name" identifier] :title ["title" text] :description ["description" text]
    :parameters ["parameters" json-object] :metadata ["metadata" json-object]
    :handler ["hasHandler" callback] :defer ["defer" (choice #{:auto :never})]
    :overrides-built-in-tool? ["overridesBuiltInTool" bool] :skip-permission? ["skipPermission" bool]
    :is-terminal? ["isTerminal" bool] :idempotent? ["idempotent" bool]}
   [:name]))

(defn- tool [path value]
  (merge {"hasHandler" false} (tool-fields path value)))

(def ^:private command-fields
  (object {:name ["name" identifier] :description ["description" text] :handler [nil callback]}
          [:name :handler]))

(defn- command [path value]
  (merge {"description" ""} (command-fields path value)))

(def ^:private section-action (choice #{:append :prepend :remove :replace :preserve}))
(def ^:private section-fields
  (object {:action ["action" (fn [path v] (if (fn? v) "transform" (section-action path v)))]
           :content ["content" text]} [:action]))

(defn- section-override [path value]
  (cond-> (section-fields path value)
    (fn? (:action value)) (dissoc "content")))

(def ^:private system-sections
  (object (into {} (map (fn [[k id]] [k [id section-override]])) util/section-key->wire)))

(defn- system-message [path value]
  (let [mode (:mode value)
        fields (cond-> {:mode ["mode" (choice #{:append :replace :customize})] :content ["content" text]}
                 (= :customize mode) (assoc :sections ["sections" system-sections]))]
    ((object fields (when (= :replace mode) [:content])) path value)))

(defn- empty-system-message [message]
  (if (= "replace" (get message "mode"))
    message
    (-> message
        (assoc "mode" "customize")
        (update "sections" #(merge {"environment_context" {"action" "remove"}} %)))))

(def ^:private storage (choice #{:persistent :in-memory}))

(def ^:private mcp-common
  {:tools ["tools" strings] :timeout ["timeout" nonnegative]
   :defer-tools ["deferTools" (choice #{:auto :never})]})

(def ^:private stdio-server
  (object (merge mcp-common {:type ["type" (choice #{:stdio :local})] :command ["command" text]
                             :args ["args" strings] :env ["env" string-dictionary]
                             :working-directory ["cwd" text]})
          [:command]))

(def ^:private http-server
  (object (merge mcp-common {:type ["type" (choice #{:http :sse})] :url ["url" text]
                             :headers ["headers" string-dictionary]})
          [:type :url]))

(def ^:private mcp-servers
  (dictionary (fn [path server]
                ((if (#{:http :sse} (:type server)) http-server stdio-server) path server))))

(def ^:private custom-agent
  (object {:name ["name" identifier] :prompt ["prompt" text] :display-name ["displayName" text]
           :description ["description" text] :tools ["tools" (nullable strings)] :infer? ["infer" bool]
           :skills ["skills" strings] :model ["model" text] :reasoning-effort ["reasoningEffort" text]
           :model-policy ["modelPolicy" (choice #{:preferred :required})] :mcp-servers ["mcpServers" mcp-servers]
           :hooks ["hooks" (dictionary (array-of json-object))]}
          [:name :prompt]))

(def ^:private managed-settings
  (object {:permissions ["permissions"
                         (object {:disable-bypass-permissions-mode ["disableBypassPermissionsMode" text]
                                  :disable-assisted-permissions-mode? ["disableAssistedPermissionsMode" bool]
                                  :deny ["deny" strings] :ask ["ask" strings]
                                  :allow ["allow" strings] :limit-to ["limitTo" strings]})]}))

(def ^:private github-mcp
  (object {:enable-all-tools? ["enableAllTools" bool] :additional-toolsets ["additionalToolsets" strings]
           :additional-tools ["additionalTools" strings] :enable-insiders-mode? ["enableInsidersMode" bool]
           :disable-form-deferral? ["disableFormDeferral" bool]}))

(def ^:private cloud
  (object {:repository ["repository" (object {:owner ["owner" identifier] :name ["name" identifier]
                                              :branch ["branch" text]} [:owner :name])]}))

(def ^:private hook-handlers
  (object (zipmap [:on-pre-tool-use :on-pre-mcp-tool-call :on-post-tool-use :on-post-tool-use-failure
                   :on-user-prompt-submitted :on-user-prompt-transformed :on-session-start :on-session-end
                   :on-error-occurred :on-agent-stop :on-subagent-start :on-subagent-stop]
                  (repeat [nil callback]))))

(defn- has-hooks? [path handlers]
  (hook-handlers path handlers)
  (boolean (seq handlers)))

;; These options belong to the post-open update. localDiscoveryOnly is sent
;; in both requests; all other fields here are absent from the first request.
(def ^:private patch-fields
  {:instructions {:skip-custom-instructions? ["skipCustomInstructions" bool]}
   :agents {:local-discovery-only? ["localDiscoveryOnly" bool]}
   :git {:coauthor-enabled? ["coauthorEnabled" bool]}
   :tools {:manage-schedule-enabled? ["manageScheduleEnabled" bool]}
   :skills {:included-builtin-skills ["includedBuiltinSkills" strings]}})

(def ^:private common-fields
  {:model ["model" text] :client-name ["clientName" text] :working-directory ["workingDirectory" text]
   :config-directory ["configDirectory" text] :enable-config-discovery? ["enableConfigDiscovery" bool]
   :system-message ["systemMessage" system-message] :extension-paths ["extensionPaths" strings]})

(def ^:private vision-limits
  (object {:supported-media-types ["supported_media_types" strings]
           :max-prompt-images ["max_prompt_images" positive] :max-prompt-image-size ["max_prompt_image_size" nonnegative]}))

(def ^:private model-capabilities
  (object {:supports ["supports" (object {:vision? ["vision" bool] :tool-calls? ["toolCalls" bool]
                                          :reasoning-effort? ["reasoningEffort" bool]
                                          :adaptive-thinking ["adaptive_thinking" (underscore-choice
                                                                                   #{:unsupported :optional :required :adaptive-only})]})]
           :limits ["limits" (object {:max-prompt-tokens ["max_prompt_tokens" nonnegative]
                                      :max-output-tokens ["max_output_tokens" nonnegative]
                                      :max-context-window-tokens ["max_context_window_tokens" nonnegative]
                                      :vision ["vision" vision-limits]})]}))

(def ^:private model-tuning
  {:reasoning-effort ["reasoningEffort" text] :reasoning-summary ["reasoningSummary" (choice #{:none :concise :detailed})]
   :context-tier ["contextTier" (underscore-choice #{:default :long-context})]})

(def ^:private switch-tuning (object (assoc model-tuning :model-capabilities ["modelCapabilities" model-capabilities])))

(defn model-switch-params
  "Encode the high-level setModel arguments for session.model.switchTo.
  Omitted options remain absent. The runtime resolves the model ID."
  [session-id model tuning-map]
  (assoc (switch-tuning [] tuning-map) "sessionId" (identifier [:session-id] session-id)
         "modelId" (text [:model] model)))

(def ^:private groups
  {:inference (assoc model-tuning :providers ["providers" (array-of provider)] :models ["models" (array-of provider-model)]
                     :default-provider ["defaultProvider" (nullable identifier)]
                     :allow-multiple-tool-calls? ["allowMultipleToolCalls" bool]
                     :capabilities ["capabilities" model-capabilities])
   :tools {:definitions ["definitions" (declarations tool)]
           :available-tools ["availableTools" tool-filters] :excluded-tools ["excludedTools" tool-filters]
           :search ["search" (object {:enabled? ["enabled" bool] :defer-threshold ["deferThreshold" nonnegative]})]
           :large-output ["largeOutput" (object {:enabled? ["enabled" bool] :max-size-bytes ["maxSizeBytes" nonnegative]
                                                 :output-directory ["outputDir" text]})]}
   :events {:streaming? ["streaming" bool] :include-sub-agent-streaming-events? ["includeSubAgentStreamingEvents" bool]
            :on-event [nil callback]}
   :permissions {:on-request ["request" callback]}
   :github {:capi ["capi" (object {:enabled? ["enabled" bool]
                                   :auto-tier ["autoTier" (scalar util/auto-tier-identifier? "expected a tier identifier")]
                                   :enable-web-socket-responses? ["enableWebSocketResponses" bool]})]
            :mcp ["mcp" github-mcp] :token ["token" text] :token-provider [nil callback]
            :telemetry-enabled? ["telemetryEnabled" bool]
            :interactive-user? ["interactiveUser" bool] :fetch-managed-settings? ["fetchManagedSettings" bool]}
   :extensions {:enabled? ["enabled" bool] :sdk-path ["sdkPath" text]
                :identity ["identity" (object {:source ["source" text] :name ["name" text]} [:source :name])]}
   :interaction {:commands ["commands" (declarations command)]
                 :ask-user-variant ["askUserVariant" (choice #{:legacy :elicitation})]
                 :on-user-input-request ["requestUserInput" callback]
                 :on-shell-authentication-request ["requestShellAuthentication" callback]
                 :on-elicitation-request ["requestElicitation" callback]
                 :on-exit-plan-mode-request ["requestExitPlanMode" callback]
                 :on-auto-mode-switch-request ["requestAutoModeSwitch" callback]
                 :auto-mode-recovery? [nil bool]}
   :filesystem {:additional-directories ["additionalDirectories" strings] :track-file-changes? ["trackFileChanges" bool]}
   :instructions {:directories ["directories" strings] :on-demand-discovery? ["onDemandDiscovery" bool]
                  :organization-text ["organizationText" text]}
   :agents {:directories ["directories" strings] :selected ["selected" text]
            :definitions ["definitions" (array-of custom-agent)]
            :default ["default" (object {:excluded-tools ["excludedTools" strings]})]
            :included-builtin-agents ["includedBuiltinAgents" strings] :excluded-builtin-agents ["excludedBuiltinAgents" strings]
            :local-discovery-only? ["localDiscoveryOnly" bool]}
   :git {:enable-host-git-operations? ["enableHostGitOperations" bool]}
   :skills {:directories ["directories" strings] :disabled-skills ["disabledSkills" strings] :enabled? ["enabled" bool]}
   :hooks {:directories ["directories" strings] :enable-repository-hooks? ["enableRepositoryHooks" bool]
           :handlers ["hasHandlers" has-hooks?]}
   :plugins {:directories ["directories" strings]}
   :mcp {:oauth-token-storage ["oauthTokenStorage" storage] :auth-client-id-metadata-url ["authClientIdMetadataUrl" text]
         :servers ["servers" mcp-servers] :disabled-servers ["disabledServers" strings] :on-auth-request [nil callback]}
   :state {:create-session-fs-provider [nil (scalar #(or (fn? %) (map? %))
                                                    "expected a filesystem provider or factory")]
           :memory ["memory" (object {:enabled? ["enabled" bool]} [:enabled?])]
           :infinite-sessions ["infiniteSessions" (object {:enabled? ["enabled" bool]
                                                           :background-compaction-threshold ["backgroundCompactionThreshold" fraction]
                                                           :buffer-exhaustion-threshold ["bufferExhaustionThreshold" fraction]})]
           :enable-cross-session-store? ["enableCrossSessionStore" bool] :skip-embedding-retrieval? ["skipEmbeddingRetrieval" bool]
           :embedding-cache-storage ["embeddingCacheStorage" storage]}
   :experiments {:enable-experimental-mode? ["enableExperimentalMode" bool]
                 :flags ["flags" (scalar #(and (map? %) (every? string? (keys %)) (every? boolean? (vals %)))
                                         "expected a map of flag names to booleans")]}
   :policy {:allowed-models ["allowedModels" strings] :excluded-managed-settings ["excludedManagedSettings" strings]
            :managed-settings ["managedSettings" managed-settings]
            :enforce-managed-model-defaults? ["enforceManagedModelDefaults" bool]}})

(defn- open-contract [create?]
  (let [root (merge common-fields (if create?
                                    {:session-id ["sessionId" identifier] :name ["name" text]
                                     :cloud ["cloud" cloud]}
                                    {:suppress-resume-event? ["suppressResumeEvent" bool]
                                     :allow-transcript-recovery? ["allowTranscriptRecovery" bool]
                                     :continue-pending-work? ["continuePendingWork" bool]}))
        fields (cond-> groups
                 create? (assoc-in [:filesystem :trust-working-directory] ["trustWorkingDirectory" (choice #{:session :user})])
                 create? (assoc-in [:instructions :refresh-custom-instructions?] ["refreshCustomInstructions" bool]))]
    (object
     (reduce-kv (fn [root group startup]
                  (let [patch-only (into {} (map (fn [[k [_ encode]]] [k [nil encode]])) (get patch-fields group))]
                    (assoc root group [(name group) (object (merge patch-only startup))])))
                root fields))))

(def ^:private create-contract (open-contract true))
(def ^:private resume-contract (open-contract false))

(def ^:private open-defaults
  (merge (zipmap (concat ["canvases" "execution"] (map name (keys groups))) (repeat {}))
         {"events" {"includeSubAgentStreamingEvents" true} "mcp" {"envValueMode" "direct"}
          "permissions" {"request" false} "hooks" {"hasHandlers" false}
          "interaction" {"requestUserInput" false "requestElicitation" false
                         "requestExitPlanMode" false "requestAutoModeSwitch" false}}))

(def ^:private empty-defaults
  {"github" {"telemetryEnabled" false} "mcp" {"oauthTokenStorage" "in-memory"}
   "state" {"skipEmbeddingRetrieval" true "embeddingCacheStorage" "in-memory"
            "enableCrossSessionStore" false "memory" {"enabled" false}}
   "experiments" {"enableExperimentalMode" false} "instructions" {"onDemandDiscovery" false}
   "hooks" {"enableRepositoryHooks" false} "git" {"enableHostGitOperations" false}
   "skills" {"enabled" false} "agents" {"localDiscoveryOnly" true}})

(defn- empty-mode? [{:keys [mode]}]
  (case mode
    :empty true
    :coding-agent false
    (invalid! [:mode] "select :coding-agent or :empty in client options")))

(defn- open-params [contract client-options options]
  (let [isolated? (empty-mode? client-options)
        encoded (contract [] options)
        auto-recovery? (get-in options [:interaction :auto-mode-recovery?])]
    (when (and auto-recovery? (not (get-in encoded ["interaction" "requestAutoModeSwitch"])))
      (invalid! [:interaction :auto-mode-recovery?] "requires :on-auto-mode-switch-request"))
    (when (and (get-in options [:github :token-provider]) (contains? (:github options) :token))
      (invalid! [:github :token-provider] "cannot combine with :token"))
    (when (and (contains? options :cloud) (contains? options :name))
      (invalid! [:name] "only local sessions support a name at creation"))
    (when (and isolated? (not (contains? (:tools options) :available-tools)))
      (invalid! [:tools :available-tools] ":empty mode requires an explicit allowlist; use [] for no tools"))
    (cond-> (merge-with merge open-defaults (when isolated? empty-defaults) encoded)
      auto-recovery? (assoc-in ["interaction" "requestAutoModeRecovery"] true)
      isolated? (update "systemMessage" empty-system-message))))

(defn create-params
  "Encode session.create. The owner supplies a local ID before registration;
  for cloud creation it can omit the ID and initialize from the reply."
  [client-options session-options]
  (open-params create-contract client-options session-options))

(defn conversation-config [config]
  (when-not (s/valid? ::specs/conversation-config config)
    (throw (ex-info "Expected :client and :session option maps and an explicit client :mode"
                    {:type :invalid-conversation-config})))
  (let [{:keys [client session]} config]
    (create-params client session)
    (when (contains? client :session-fs) (fs/provider-params (:session-fs client)))
    (fs/check-session-provider! (:session-fs client) (get-in session [:state :create-session-fs-provider])))
  config)

(defn resume-params
  "Encode initial resume options. The session ID is an argument, not an option."
  [client-options session-id session-options]
  (-> (open-params resume-contract client-options session-options)
      (assoc "sessionId" (identifier [:session-id] session-id))
      ;; A resumed session can settle new permission requests manually.
      (assoc-in ["permissions" "request"] true)))

(def ^:private empty-patch
  {"instructions" {"skipCustomInstructions" true} "agents" {"localDiscoveryOnly" true}
   "git" {"coauthorEnabled" false} "tools" {"manageScheduleEnabled" false}
   "plugins" {"installed" []} "skills" {"includedBuiltinSkills" []}})

(defn open-options-patch
  "Project the same validated create/resume options to the post-open patch, or
  nil when no RPC is needed. Attach the returned session ID as \"sessionId\".
  Apply before exposing the session; detach and surface errors on failure.
  This is not a general-purpose encoder for arbitrary live updates."
  [client-options session-options]
  (let [patch (reduce-kv (fn [out group fields]
                           (let [supplied (select-keys (get session-options group) (keys fields))]
                             (if (seq supplied)
                               (assoc out (name group) ((object fields) [group] supplied))
                               out)))
                         {} patch-fields)
        result (merge-with merge (when (empty-mode? client-options) empty-patch) patch)]
    (when (seq result) result)))

(defn after-open-requests
  "After an acknowledged create/resume, check requested capabilities and return
  ordered [method params] pairs. The owner must detach on failure before it
  exposes the session. Use the canonical session ID from the acknowledgement."
  [client-options options {:keys [session-id capabilities]}]
  (when (and (get-in options [:interaction :auto-mode-recovery?])
             (not (true? (get-in capabilities [:ui :auto-mode-recovery]))))
    (throw (ex-info "Runtime did not acknowledge Auto mode recovery"
                    {:type :unsupported-auto-mode-recovery :session-id session-id})))
  (let [patch (open-options-patch client-options options)]
    (cond-> []
      (get-in options [:mcp :on-auth-request])
      (conj ["session.eventLog.registerInterest" {"sessionId" session-id "eventType" "mcp.oauth_required"}])
      patch (conj ["session.options.update" (assoc patch "sessionId" session-id)]))))

(def ^:private position
  (object {:line ["line" nonnegative] :character ["character" nonnegative]} [:line :character]))

(def ^:private path-attachment
  (object {:path ["path" text] :display-name ["displayName" text]} [:path]))

(def ^:private attachments-by-type
  {:file path-attachment :directory path-attachment
   :selection (object {:file-path ["filePath" text] :display-name ["displayName" text]
                       :selection-range ["selection" (object {:start ["start" position] :end ["end" position]} [:start :end])]
                       :text ["text" text]}
                      [:file-path :display-name])
   :blob (object {:data ["data" text] :mime-type ["mimeType" text] :display-name ["displayName" text]}
                 [:data :mime-type])
   :extension-context (object {:extension-id ["extensionId" text] :canvas-id ["canvasId" text]
                               :instance-id ["instanceId" text] :title ["title" text]
                               :payload ["payload" json-value] :captured-at ["capturedAt" text]}
                              [:extension-id :title :captured-at])})

(defn- attachment [path value]
  (when-not (and (map? value) (every? keyword? (keys value)))
    (invalid! path "expected an attachment map with keyword fields"))
  (let [kind (:type value)]
    (if-let [encode (get attachments-by-type kind)]
      (assoc (encode path (dissoc value :type)) "type" (if (= kind :extension-context) "extension_context" (name kind)))
      (invalid! (conj path :type) "unsupported message attachment type"))))

(defn- message-source [path source]
  (cond
    (#{:user :system} source) (name source)
    (and (map? source) (= [:agent-id] (keys source)))
    (str "agent-" (text (conj path :agent-id) (:agent-id source)))
    (and (map? source) (= [:automation-id] (keys source)))
    (let [at (conj path :automation-id)
          id (text at (:automation-id source))]
      (when (empty? id) (invalid! at "expected a nonempty automation ID"))
      (str "automation-" id))
    :else (invalid! path "expected :user, :system, {:agent-id string}, or {:automation-id string}")))

(def ^:private message-options
  (object {:prompt ["prompt" text] :source ["source" message-source] :display-prompt ["displayPrompt" text]
           :client-turn-id ["clientTurnId" text] :attachments ["attachments" (array-of attachment)]
           :mode ["mode" (choice #{:enqueue :immediate})] :agent-mode ["agentMode" (choice #{:interactive :plan :autopilot :shell})]
           :request-headers ["requestHeaders" string-dictionary]
           :response-schema ["responseFormat" (fn [path schema]
                                                {"type" "json_schema"
                                                 "jsonSchema" {"name" "response" "strict" true "schema" (json-object path schema)}})]}
          [:prompt]))

(defn encode-message
  "Check and encode a message without acquiring a session or invoking handlers."
  [message-opts]
  (try
    (let [opts (if (string? message-opts) {:prompt message-opts} message-opts)
          params (message-options [] opts)]
      (when (and (= :immediate (:mode opts)) (contains? opts :response-schema))
        (invalid! [:response-schema] "cannot request structured output on an immediate steering message"))
      params)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e) (assoc (select-keys (ex-data e) [:path]) :type :invalid-message))))))

(defn send-params
  "Encode a prompt string or a MessageOptions map for session.send and
  session.sendAndWait. :response-schema is raw JSON Schema. Timeouts, trace
  context and typed result parsing belong to the caller, not MessageOptions."
  [session-id message-opts]
  (assoc (encode-message message-opts) "sessionId" (identifier [:session-id] session-id)))
