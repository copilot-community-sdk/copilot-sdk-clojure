(ns github.copilot-sdk.specs
  "Clojure specs for Copilot SDK data structures."
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Common specs
;; -----------------------------------------------------------------------------

(defn- closed-keys
  "Returns a spec that validates the keys spec and rejects unknown keys.
   allowed-keys should be the set of allowed keyword names (unqualified)."
  [keys-spec allowed-keys]
  (s/and keys-spec
         (fn [m]
           (let [unknown (set/difference (set (keys m)) allowed-keys)]
             (empty? unknown)))))

(defn unknown-keys
  "Returns the set of unknown keys in a map, given the allowed keys."
  [m allowed-keys]
  (set/difference (set (keys m)) allowed-keys))

(s/def ::non-blank-string (s/and string? (complement clojure.string/blank?)))
(s/def ::timestamp string?)
(s/def ::session-id ::non-blank-string)
(s/def ::workspace-path (s/nilable ::non-blank-string))
(s/def ::instant #(instance? java.time.Instant %))

;; -----------------------------------------------------------------------------
;; Client options
;; -----------------------------------------------------------------------------

(s/def ::cli-path ::non-blank-string)
(s/def ::cli-args (s/coll-of string?))
(s/def ::cli-url ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::port (s/and int? #(<= 0 % 65535)))
(s/def ::use-stdio? boolean?)
(s/def ::log-level #{:none :error :warning :info :debug :all})
(s/def ::auto-start? boolean?)
(s/def ::auto-restart? boolean?)
(s/def ::notification-queue-size pos-int?)
(s/def ::router-queue-size pos-int?)
(s/def ::tool-timeout-ms pos-int?)
(s/def ::env (s/map-of string? (s/nilable string?)))
;; Authentication options (PR #237)
(s/def ::github-token ::non-blank-string)
(s/def ::use-logged-in-user? boolean?)
;; Child process mode (upstream PR #737)
(s/def ::is-child-process? boolean?)
;; Custom model listing handler (upstream PR #730)
(s/def ::on-list-models fn?)

;; copilotHome — base directory for Copilot data (sets COPILOT_HOME on spawn).
;; Upstream PR #1191 (@github/copilot-sdk).
(s/def ::copilot-home ::non-blank-string)

;; tcpConnectionToken — connection token sent on the `connect` RPC and via the
;; COPILOT_CONNECTION_TOKEN env var. Auto-generated as a UUID when the SDK
;; spawns the CLI in TCP mode and the caller did not provide one.
;; Upstream PR #1176 (@github/copilot-sdk).
(s/def ::tcp-connection-token ::non-blank-string)

;; OpenTelemetry configuration (upstream PR #785)
(s/def ::otlp-endpoint string?)
(s/def ::exporter-type string?)
(s/def ::source-name string?)
(s/def ::capture-content? boolean?)

(s/def ::telemetry
  (s/keys :opt-un [::otlp-endpoint ::file-path ::exporter-type ::source-name ::capture-content?]))

;; Trace context provider: 0-arity fn returning {:traceparent ... :tracestate ...}
(s/def ::on-get-trace-context fn?)

;; Session filesystem provider (upstream PR #917)
(s/def ::initial-cwd ::non-blank-string)
(s/def ::session-state-path ::non-blank-string)
(s/def ::conventions #{"windows" "posix"})
(s/def ::session-fs
  (s/keys :req-un [::initial-cwd ::session-state-path ::conventions]))

;; SessionFs handler — map of keyword→fn for each FS operation.
;; Each fn receives a params map (with :session-id, :path, etc.) and returns a result map (or nil for void ops).
(s/def ::read-file fn?)
(s/def ::write-file fn?)
(s/def ::append-file fn?)
(s/def ::exists fn?)
(s/def ::stat fn?)
(s/def ::mkdir fn?)
(s/def ::readdir fn?)
(s/def ::readdir-with-types fn?)
(s/def ::rm fn?)
(s/def ::rename fn?)

(s/def ::session-fs-handler
  (s/keys :req-un [::read-file ::write-file ::append-file ::exists ::stat
                   ::mkdir ::readdir ::readdir-with-types ::rm ::rename]))

(defn- fn-accepts-arity?
  [f n]
  (and (fn? f)
       (let [fixed-arity? (boolean
                           (some (fn [^java.lang.reflect.Method method]
                                   (and (= "invoke" (.getName method))
                                        (= n (.getParameterCount method))))
                                 (.getDeclaredMethods (class f))))
             variadic-min-arity (when (instance? clojure.lang.RestFn f)
                                  (.getRequiredArity ^clojure.lang.RestFn f))]
         (or fixed-arity?
             (and (some? variadic-min-arity)
                  (<= variadic-min-arity n))))))

(def ^:private session-fs-provider-arities
  {:read-file 1
   :write-file 3
   :append-file 3
   :exists 1
   :stat 1
   :mkdir 3
   :readdir 1
   :readdir-with-types 1
   :rm 3
   :rename 2})

(defn- session-fs-provider?
  [provider]
  (and (map? provider)
       (every? (fn [[operation arity]]
                 (fn-accepts-arity? (get provider operation) arity))
               session-fs-provider-arities)))

;; Provider-style session filesystem implementation. Same operation keys as
;; ::session-fs-handler, but functions take direct positional arguments and
;; throw on errors instead of returning RPC-shaped success/error maps.
(s/def ::session-fs-provider
  (s/and ::session-fs-handler session-fs-provider?))

;; Factory fn: (session) → session-fs-handler
(s/def ::create-session-fs-handler fn?)

(def client-options-keys
  #{:cli-path :cli-args :cli-url :cwd :port
    :use-stdio? :log-level :auto-start? :auto-restart?
    :notification-queue-size :router-queue-size
    :tool-timeout-ms :env :github-token :use-logged-in-user?
    :is-child-process? :on-list-models :telemetry :on-get-trace-context
    :session-fs :copilot-home :tcp-connection-token :remote?})

(s/def ::client-options
  (closed-keys
   (s/keys :opt-un [::cli-path ::cli-args ::cli-url ::cwd ::port
                    ::use-stdio? ::log-level ::auto-start? ::auto-restart?
                    ::notification-queue-size ::router-queue-size
                    ::tool-timeout-ms ::env ::github-token ::use-logged-in-user?
                    ::is-child-process? ::on-list-models ::telemetry ::on-get-trace-context
                    ::session-fs ::copilot-home ::tcp-connection-token ::remote?])
   client-options-keys))

;; -----------------------------------------------------------------------------
;; Tool definitions
;; -----------------------------------------------------------------------------

(s/def ::tool-name ::non-blank-string)
(s/def ::tool-description (s/nilable string?))
(s/def ::json-schema map?)
(s/def ::tool-parameters (s/nilable ::json-schema))
(s/def ::tool-handler fn?)
(s/def ::overrides-built-in-tool boolean?)
(s/def ::skip-permission? boolean?)

(s/def ::tool
  (s/keys :req-un [::tool-name ::tool-handler]
          :opt-un [::tool-description ::tool-parameters ::overrides-built-in-tool ::skip-permission?]))

(s/def ::tools (s/coll-of ::tool))

;; -----------------------------------------------------------------------------
;; System message configuration
;; -----------------------------------------------------------------------------

(s/def ::system-message-mode #{:append :replace :customize})
(s/def ::system-message-content string?)

;; System prompt sections for customize mode (upstream PR #816)
(def system-prompt-sections
  "Known system prompt section identifiers for the customize mode.
   Each section corresponds to a distinct part of the system prompt."
  {:identity            {:description "Agent identity preamble and mode statement"}
   :tone                {:description "Response style, conciseness rules, output formatting preferences"}
   :tool-efficiency     {:description "Tool usage patterns, parallel calling, batching guidelines"}
   :environment-context {:description "CWD, OS, git root, directory listing, available tools"}
   :code-change-rules   {:description "Coding rules, linting/testing, ecosystem tools, style"}
   :guidelines          {:description "Tips, behavioral best practices, behavioral guidelines"}
   :safety              {:description "Environment limitations, prohibited actions, security policies"}
   :tool-instructions   {:description "Per-tool usage instructions"}
   :custom-instructions {:description "Repository and organization custom instructions"}
   :last-instructions   {:description "End-of-prompt instructions: parallel tool calling, persistence, task completion"}})

(s/def ::system-prompt-section (set (keys system-prompt-sections)))

;; Section override: action can be a keyword for static overrides, or a fn for transforms
(s/def ::section-action
  (s/or :static #{:replace :remove :append :prepend}
        :transform fn?))

(s/def ::section-override
  (s/and map?
         #(contains? % :action)
         #(s/valid? ::section-action (:action %))
         ;; If :content is present, it must be a string
         #(if-let [c (:content %)] (string? c) true)
         ;; For static content-bearing actions, :content is required
         #(if (and (keyword? (:action %))
                   (#{:replace :append :prepend} (:action %)))
            (string? (:content %))
            true)))

;; Customize config: mode :customize with optional sections map and content
;; Sections map allows any keyword key — unknown sections gracefully fall back
;; to appending content to additional instructions (upstream behavior).
(s/def ::sections (s/map-of keyword? ::section-override))

;; System message: supports :append (default), :replace, and :customize modes
(s/def ::system-message
  (s/and map?
         #(if-let [m (:mode %)] (#{:append :replace :customize} m) true)
         #(if-let [c (:content %)] (string? c) true)
         ;; :replace mode requires :content
         #(if (= :replace (:mode %)) (string? (:content %)) true)
         #(if (= :customize (:mode %))
            (if-let [s (:sections %)]
              (s/valid? ::sections s)
              true)
            true)))

;; -----------------------------------------------------------------------------
;; MCP Server configuration
;; -----------------------------------------------------------------------------

(s/def ::mcp-server-type #{:local :stdio :http :sse})
(s/def ::mcp-tools (s/or :list (s/coll-of string?) :all #{"*"}))
(s/def ::mcp-timeout pos-int?)
(s/def ::mcp-command ::non-blank-string)
(s/def ::mcp-args (s/coll-of string?))
(s/def ::mcp-url ::non-blank-string)
(s/def ::mcp-headers (s/map-of string? string?))

(s/def ::mcp-local-server
  (s/keys :req-un [::mcp-command ::mcp-args ::mcp-tools]
          :opt-un [::mcp-server-type ::mcp-timeout ::env ::cwd]))

(s/def ::mcp-remote-server
  (s/keys :req-un [::mcp-server-type ::mcp-url ::mcp-tools]
          :opt-un [::mcp-timeout ::mcp-headers]))

;; New canonical names (upstream PR #1051)
(s/def ::mcp-stdio-server ::mcp-local-server)
(s/def ::mcp-http-server ::mcp-remote-server)

(s/def ::mcp-server (s/or :local ::mcp-local-server :remote ::mcp-remote-server))
(s/def ::mcp-servers (s/map-of #(or (keyword? %) (string? %)) ::mcp-server))

;; -----------------------------------------------------------------------------
;; Custom agent configuration
;; -----------------------------------------------------------------------------

(s/def ::agent-name ::non-blank-string)
(s/def ::agent-display-name string?)
(s/def ::agent-description string?)
(s/def ::agent-tools (s/nilable (s/coll-of string?)))
(s/def ::agent-prompt ::non-blank-string)
(s/def ::agent-infer? boolean?)
(s/def ::agent-skills (s/coll-of string?))

(s/def ::custom-agent
  (s/keys :req-un [::agent-name ::agent-prompt]
          :opt-un [::agent-display-name ::agent-description ::agent-tools
                   ::mcp-servers ::agent-infer? ::agent-skills]))

(s/def ::custom-agents (s/coll-of ::custom-agent))

;; Default agent configuration (upstream PR #1098). This controls tool
;; visibility for the built-in/default agent independently of custom agents.
(s/def ::default-agent
  (s/keys :opt-un [::excluded-tools]))

;; Agent selection (upstream PR #722)
(s/def ::agent ::non-blank-string)

;; -----------------------------------------------------------------------------
;; Provider configuration (BYOK)
;; -----------------------------------------------------------------------------

(s/def ::provider-type #{:openai :azure :anthropic})
(s/def ::wire-api #{:completions :responses})
(s/def ::base-url ::non-blank-string)
(s/def ::api-key string?)
(s/def ::bearer-token string?)
(s/def ::azure-api-version string?)

(s/def ::azure-options
  (s/keys :opt-un [::azure-api-version]))

;; Provider :headers — extra HTTP headers forwarded with each model request
;; (upstream PR #1094, available since CLI 1.0.32). Map of header name → value.
(s/def ::headers (s/map-of string? string?))

;; Provider model override (upstream PR #966).
;; Wire model name sent to the provider API for inference; falls back to
;; :model-id, then SessionConfig :model. Note: ::max-input-tokens and
;; ::max-output-tokens are reused here for the new ProviderConfig override
;; fields. ::model-id is *not* reused: the model-info response spec at line
;; ~1237 below registers ::model-id as `(s/nilable string?)` because the
;; current-model getter can return nil. Provider overrides, in contrast,
;; correspond to upstream's `modelId?: string` (optional but never null), so
;; we validate `:model-id` explicitly via an extra predicate below rather
;; than letting the nilable leaf weaken the provider config.
(s/def ::wire-model ::non-blank-string)

(s/def ::provider
  (s/and
   (s/keys :req-un [::base-url]
           :opt-un [::provider-type ::wire-api ::api-key ::bearer-token ::azure-options
                    ::headers
                    ;; ProviderConfig overrides (upstream PR #966).
                    ;; ::model-id ↦ wire `modelId` (well-known model name for
                    ;; agent config + token-limit lookup; default wire model
                    ;; when ::wire-model is unset).
                    ;; ::wire-model ↦ wire `wireModel` (provider-API model name).
                    ;; ::max-input-tokens  ↦ wire `maxPromptTokens` (compaction
                    ;; trigger; SDK↔wire key disagreement is handled by
                    ;; provider->wire in client.clj).
                    ;; ::max-output-tokens ↦ wire `maxOutputTokens`.
                    ::model-id ::wire-model
                    ::max-input-tokens ::max-output-tokens])
   ;; Provider override `:model-id` must be a non-blank string when present
   ;; (upstream `modelId?: string`, never null). The shared ::model-id leaf
   ;; is `(s/nilable string?)` for the model-info getter, so we re-validate
   ;; here to avoid sending `modelId: null` on the wire.
   #(or (not (contains? % :model-id))
        (s/valid? ::non-blank-string (:model-id %)))
   ;; Token-limit overrides must be strictly positive when present (upstream
   ;; semantics: a `≤ 0` budget would either disable the feature or trigger
   ;; immediate compaction/truncation, both nonsensical).
   #(or (not (contains? % :max-input-tokens))
        (pos-int? (:max-input-tokens %)))
   #(or (not (contains? % :max-output-tokens))
        (pos-int? (:max-output-tokens %)))))

;; -----------------------------------------------------------------------------
;; Session configuration
;; -----------------------------------------------------------------------------

(s/def ::model ::non-blank-string)
(s/def ::available-tools (s/coll-of string?))
(s/def ::excluded-tools (s/coll-of string?))
(s/def ::streaming? boolean?)
(s/def ::on-permission-request fn?)
(s/def ::config-dir ::non-blank-string)
(s/def ::skill-directories (s/coll-of ::non-blank-string))
;; instructionDirectories — additional directories to search for custom
;; instruction files. Upstream PR #1190 (@github/copilot-sdk).
(s/def ::instruction-directories (s/coll-of ::non-blank-string))
(s/def ::disabled-skills (s/coll-of ::non-blank-string))
(s/def ::enabled boolean?)
(s/def ::max-size-bytes pos-int?)
(s/def ::output-dir ::non-blank-string)
(s/def ::large-output
  (s/keys :opt-un [::enabled ::max-size-bytes ::output-dir]))

;; Working directory
(s/def ::working-directory ::non-blank-string)

;; Infinite sessions configuration
(s/def ::background-compaction-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::buffer-exhaustion-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::infinite-sessions
  (s/keys :opt-un [::enabled ::background-compaction-threshold ::buffer-exhaustion-threshold]))

;; Reasoning effort support (PR #302)
(s/def ::reasoning-effort #{"low" "medium" "high" "xhigh"})

;; Hooks and user input handlers (PR #269)
(s/def ::on-user-input-request fn?)
(s/def ::on-pre-tool-use fn?)
(s/def ::on-post-tool-use fn?)
(s/def ::on-user-prompt-submitted fn?)
(s/def ::on-session-start fn?)
(s/def ::on-session-end fn?)
(s/def ::on-error-occurred fn?)
(s/def ::hooks
  (s/keys :opt-un [::on-pre-tool-use ::on-post-tool-use ::on-user-prompt-submitted
                   ::on-session-start ::on-session-end ::on-error-occurred]))

;; Disable resume flag
(s/def ::disable-resume? boolean?)
(s/def ::continue-pending-work? boolean?)

;; Event handler — 1-arity fn receiving event map. Uses fn? for consistency
;; with ::on-permission-request and ::on-user-input-request specs.
(s/def ::on-event fn?)

;; Command definitions — slash commands registered with a session
(s/def ::command-name ::non-blank-string)
(s/def ::command-handler fn?)
(s/def ::command-definition
  (s/and (s/keys :req-un [::name ::command-handler]
                 :opt-un [::description])
         #(not (str/blank? (:name %)))))
(s/def ::commands (s/coll-of ::command-definition))

;; Session capabilities — reported by the CLI host
(s/def ::elicitation boolean?)
(s/def ::ui (s/keys :opt-un [::elicitation]))
(s/def ::session-capabilities (s/keys :opt-un [::ui]))

;; Elicitation types — action values are strings on the wire
(s/def ::elicitation-action #{"accept" "decline" "cancel"})
(s/def ::elicitation-field-value (s/or :string string? :number number? :boolean boolean?
                                       :strings (s/coll-of string?)))
(s/def ::elicitation-content (s/map-of keyword? ::elicitation-field-value))
(s/def ::action ::elicitation-action)
(s/def ::content string?)
(s/def ::elicitation-result
  (s/and (s/keys :req-un [::action])
         #(or (not (contains? % :content))
              (s/valid? ::elicitation-content (:content %)))))
(s/def ::requested-schema map?)
(s/def ::message ::non-blank-string)
(s/def ::elicitation-params
  (s/keys :req-un [::message ::requested-schema]))

;; Elicitation context — passed to :on-elicitation-request handler (upstream PR #960).
;; Single-arg pattern: context includes session-id alongside request fields.
;; Note: :mode here is "form"/"url" (different from message-options ::mode which is :enqueue/:immediate)
(s/def ::elicitation-source string?)
(s/def ::url string?)
(s/def ::elicitation-context
  (s/and (s/keys :req-un [::session-id ::message]
                 :opt-un [::requested-schema ::elicitation-source ::url])
         #(if-let [m (:mode %)]
            (contains? #{"form" "url"} m)
            true)))

(s/def ::on-elicitation-request fn?)

;; Input options for the input! convenience method
(s/def ::title string?)
(s/def ::min-length nat-int?)
(s/def ::max-length nat-int?)
(s/def ::format #{"email" "uri" "date" "date-time"})
(s/def ::default string?)
(s/def ::input-options
  (s/keys :opt-un [::title ::description ::min-length ::max-length ::format ::default]))

(s/def ::client-name ::non-blank-string)

;; enableConfigDiscovery: auto-discover MCP configs, skills, instruction files (upstream PR #1044)
(s/def ::enable-config-discovery boolean?)

;; includeSubAgentStreamingEvents: forward streaming events from sub-agents to the parent
;; session's event stream (upstream PR #1108). Defaults to true on the wire.
(s/def ::include-sub-agent-streaming-events? boolean?)

;; enableSessionTelemetry: enable/disable internal session telemetry (upstream PR #1224).
;; When omitted (the default) or true, telemetry is enabled for GitHub-authenticated
;; sessions. When false, internal session telemetry is disabled. With a custom
;; ::provider (BYOK), session telemetry is always disabled regardless of this setting.
;; Independent of the OpenTelemetry config in ::telemetry.
(s/def ::enable-session-telemetry? boolean?)

;; Exit Plan Mode handler (upstream PR #1228). Server may ask the client to
;; approve leaving plan mode. Idiom: request map has :summary, optional
;; :plan-content, :actions (vec string), :recommended-action. Result map has
;; :approved? (required boolean), optional :selected-action and :feedback.
(s/def ::plan-content string?)
(s/def ::actions (s/coll-of string? :kind vector?))
(s/def ::recommended-action string?)
(s/def ::exit-plan-mode-request
  (s/keys :req-un [::summary ::actions ::recommended-action]
          :opt-un [::plan-content]))
(s/def ::approved? boolean?)
(s/def ::selected-action string?)
(s/def ::feedback string?)
(s/def ::exit-plan-mode-result
  (s/keys :req-un [::approved?]
          :opt-un [::selected-action ::feedback]))
(s/def ::on-exit-plan-mode fn?)

;; Auto Mode Switch handler (upstream PR #1228). Server may ask the client to
;; switch the agent to auto mode after a rate-limit event. Idiom: request map
;; has optional :error-code and :retry-after-seconds; response is a keyword
;; :yes | :yes-always | :no (or the matching wire string).
(s/def ::error-code string?)
(s/def ::retry-after-seconds (s/and number? #(>= % 0)))
(s/def ::auto-mode-switch-request
  (s/keys :opt-un [::error-code ::retry-after-seconds]))
(s/def ::auto-mode-switch-response
  (s/or :keyword #{:yes :yes-always :no}
        :string  #{"yes" "yes_always" "no"}))
(s/def ::on-auto-mode-switch fn?)

;; modelCapabilities override for session config / setModel (upstream PR #1029).
;; DeepPartial<ModelCapabilities> — same shape as ::model-capabilities since all fields are already optional.

(def session-config-keys
  #{:session-id :client-name :model :tools :commands :system-message
    :available-tools :excluded-tools :provider
    :on-permission-request :streaming? :mcp-servers
    :custom-agents :default-agent :config-dir :skill-directories
    :instruction-directories
    :disabled-skills :large-output :infinite-sessions
    :reasoning-effort :on-user-input-request :on-elicitation-request :hooks
    :on-exit-plan-mode :on-auto-mode-switch
    :working-directory :agent :on-event :create-session-fs-handler
    :enable-config-discovery :model-capabilities :github-token
    :enable-session-telemetry?
    :include-sub-agent-streaming-events?})

(s/def ::session-config
  (closed-keys
   (s/keys :req-un [::on-permission-request]
           :opt-un [::session-id ::client-name ::model ::tools ::commands ::system-message
                    ::available-tools ::excluded-tools ::provider
                    ::streaming? ::mcp-servers
                    ::custom-agents ::default-agent ::config-dir ::skill-directories
                    ::instruction-directories
                    ::disabled-skills ::large-output ::infinite-sessions
                    ::reasoning-effort ::on-user-input-request ::on-elicitation-request ::hooks
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::working-directory ::agent ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::model-capabilities ::github-token
                    ::enable-session-telemetry?
                    ::include-sub-agent-streaming-events?])
   session-config-keys))

(def ^:private resume-session-config-keys
  #{:client-name :model :tools :commands :system-message :available-tools :excluded-tools
    :provider :streaming? :on-permission-request
    :mcp-servers :custom-agents :default-agent :config-dir :skill-directories
    :instruction-directories
    :disabled-skills :infinite-sessions :reasoning-effort
    :on-user-input-request :on-elicitation-request :hooks :working-directory :disable-resume? :agent :on-event
    :on-exit-plan-mode :on-auto-mode-switch
    :continue-pending-work?
    :create-session-fs-handler :enable-config-discovery :model-capabilities :github-token
    :enable-session-telemetry?
    :include-sub-agent-streaming-events?})

(s/def ::resume-session-config
  (closed-keys
   (s/keys :req-un [::on-permission-request]
           :opt-un [::client-name ::model ::tools ::commands ::system-message ::available-tools ::excluded-tools
                    ::provider ::streaming?
                    ::mcp-servers ::custom-agents ::default-agent ::config-dir ::skill-directories
                    ::instruction-directories
                    ::disabled-skills ::infinite-sessions ::reasoning-effort
                    ::on-user-input-request ::on-elicitation-request ::hooks ::working-directory ::disable-resume? ::agent
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::model-capabilities ::github-token
                    ::continue-pending-work?
                    ::enable-session-telemetry?
                    ::include-sub-agent-streaming-events?])
   resume-session-config-keys))

;; join-session config: same as resume-session-config but :on-permission-request is optional.
;; When omitted, join-session defaults to a handler that returns {:kind :no-result}.
(s/def ::join-session-config
  (closed-keys
   (s/keys :opt-un [::on-permission-request
                    ::client-name ::model ::tools ::commands ::system-message ::available-tools ::excluded-tools
                    ::provider ::streaming?
                    ::mcp-servers ::custom-agents ::default-agent ::config-dir ::skill-directories
                    ::instruction-directories
                    ::disabled-skills ::infinite-sessions ::reasoning-effort
                    ::on-user-input-request ::on-elicitation-request ::hooks ::working-directory ::disable-resume? ::agent
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::model-capabilities ::github-token
                    ::continue-pending-work?
                    ::enable-session-telemetry?
                    ::include-sub-agent-streaming-events?])
   resume-session-config-keys))

;; -----------------------------------------------------------------------------
;; Message options
;; -----------------------------------------------------------------------------

(s/def ::prompt ::non-blank-string)
(s/def ::attachment-type #{:file :directory :selection :github-reference})
(s/def ::type ::attachment-type)
(s/def ::path ::non-blank-string)
(s/def ::file-path ::non-blank-string)
(s/def ::display-name string?)

;; Selection range (line/character positions)
(s/def ::line nat-int?)
(s/def ::character nat-int?)
(s/def ::position (s/keys :req-un [::line ::character]))
(s/def ::start ::position)
(s/def ::end ::position)
(s/def ::selection-range (s/keys :req-un [::start ::end]))
(s/def ::text string?)

;; Line range for file/directory attachments (simple start/end line numbers)
(s/def ::line-range (s/and map?
                           #(contains? % :start)
                           #(contains? % :end)
                           #(nat-int? (:start %))
                           #(nat-int? (:end %))))

;; File/directory attachment
(s/def ::file-or-directory-attachment
  (s/and (s/keys :req-un [::type ::path]
                 :opt-un [::display-name ::line-range])
         #(#{:file :directory} (:type %))))

;; Selection attachment
(s/def ::selection-attachment
  (s/and (s/keys :req-un [::type ::file-path ::display-name]
                 :opt-un [::selection-range ::text])
         #(= :selection (:type %))))

;; GitHub reference attachment (issue, PR, or discussion)
;; Note: ::state is already defined as (instance? Atom) for the client record,
;; so we cannot use s/keys here — manual predicates validate the :state field instead.
(s/def ::number nat-int?)
(s/def ::reference-type #{"issue" "pr" "discussion"})
(s/def ::url string?)
(s/def ::attachment-state string?)
(s/def ::github-reference-attachment
  (s/and map?
         #(= :github-reference (:type %))
         #(nat-int? (:number %))
         #(string? (:title %))
         #(contains? #{"issue" "pr" "discussion"} (:reference-type %))
         #(string? (:state %))
         #(string? (:url %))))

;; Blob attachment (base64-encoded inline data, received in user.message events)
;; Note: blob uses manual predicates for :data/:mime-type to avoid conflicting with
;; ::data used as event data (map?) in ::session-event.
(s/def ::mime-type string?)
(s/def ::blob-attachment
  (s/and map?
         #(= :blob (:type %))
         #(string? (:data %))
         #(string? (:mime-type %))
         #(or (nil? (:display-name %)) (string? (:display-name %)))))

(s/def ::attachment
  (s/or :file-or-directory ::file-or-directory-attachment
        :selection ::selection-attachment
        :github-reference ::github-reference-attachment
        :blob ::blob-attachment))

;; Inbound attachment (identical to ::attachment, kept as a semantic alias
;; for event data contexts where attachments are received rather than sent)
(s/def ::inbound-attachment
  (s/or :file-or-directory ::file-or-directory-attachment
        :selection ::selection-attachment
        :github-reference ::github-reference-attachment
        :blob ::blob-attachment))

(s/def ::attachments (s/coll-of ::attachment))
(s/def ::inbound-attachments (s/coll-of ::inbound-attachment))
(s/def ::mode #{:enqueue :immediate})

;; Per-request HTTP headers forwarded to the model provider (upstream PR #1094).
;; Map of header name → value, merged with any provider-level headers.
(s/def ::request-headers (s/map-of string? string?))

(s/def ::send-options
  (s/keys :req-un [::prompt]
          :opt-un [::attachments ::mode ::timeout-ms ::request-headers]))

;; :timeout-ms as used in option maps for send-async / <send! /
;; send-async-with-id allows nil to "disable" the timeout per the docstrings.
(s/def ::timeout-ms (s/nilable pos-int?))
;; send-and-wait!'s positional timeout-ms argument passes the value directly to
;; async/timeout, so the positional form requires a strict positive integer.
(s/def ::strict-timeout-ms pos-int?)

;; -----------------------------------------------------------------------------
;; Connection state
;; -----------------------------------------------------------------------------

(s/def ::connection-state #{:disconnected :connecting :connected :error})

;; -----------------------------------------------------------------------------
;; Session metadata
;; -----------------------------------------------------------------------------

(s/def ::start-time ::instant)
(s/def ::modified-time ::instant)
(s/def ::summary string?)
(s/def ::remote? boolean?)

;; Session context (cwd, git info from session creation)
(s/def ::git-root ::non-blank-string)
(s/def ::repository ::non-blank-string)
(s/def ::branch ::non-blank-string)

(s/def ::session-context
  (s/keys :req-un [::cwd]
          :opt-un [::git-root ::repository ::branch]))

;; Session list filter
(s/def ::session-list-filter
  (s/keys :opt-un [::cwd ::git-root ::repository ::branch]))

(s/def ::context (s/nilable ::session-context))

(s/def ::session-metadata
  (s/keys :req-un [::session-id ::start-time ::modified-time ::remote?]
          :opt-un [::summary ::context]))

;; -----------------------------------------------------------------------------
;; Session lifecycle events (client-level)
;; -----------------------------------------------------------------------------

(s/def ::lifecycle-event-type
  #{:session.created :session.deleted :session.updated
    :session.foreground :session.background})

(s/def ::lifecycle-event
  (s/keys :req-un [::lifecycle-event-type ::session-id]
          :opt-un [::metadata]))

(s/def ::lifecycle-handler fn?)

;; -----------------------------------------------------------------------------
;; Session Events (from generated schema)
;; -----------------------------------------------------------------------------

(s/def ::event-id ::non-blank-string)
(s/def ::event-timestamp ::timestamp)
(s/def ::parent-id (s/nilable ::non-blank-string))
(s/def ::ephemeral? boolean?)
;; agent-id: identifies which (sub-)agent emitted an event. Present on most events
;; once sub-agent streaming is enabled (upstream PR #1108).
(s/def ::agent-id ::non-blank-string)

;; canOfferSessionApproval: writeFile permission requests carry this hint indicating
;; whether the CLI can present a "trust this session" option (upstream CLI 1.0.28).
;; Note: the spec key name omits the `?` suffix because camel-snake-kebab (used
;; by util/wire->clj) converts `canOfferSessionApproval` to
;; `:can-offer-session-approval` (no `?`), and `:opt-un` matches on the exact
;; unqualified keyword — a `?`-suffixed spec would silently never fire.
(s/def ::can-offer-session-approval boolean?)

;; reasoningTokens: per-message / per-session tokens used for reasoning content
;; (upstream CLI 1.0.32). Reported on assistant.usage and session.usage_info events.
(s/def ::reasoning-tokens nat-int?)
(s/def ::reasoning-id ::non-blank-string)
(s/def ::encrypted-content string?)
(s/def ::output-tokens nat-int?)
(s/def ::phase string?)
(s/def ::reasoning-opaque string?)
(s/def ::reasoning-text string?)
(s/def ::request-id ::non-blank-string)

;; Session log specs (upstream PR #737)
(s/def ::level #{"info" "warning" "error"})
(s/def ::log-options (s/keys :opt-un [::level ::ephemeral?]))

(s/def ::base-event
  (s/keys :req-un [::event-id ::event-timestamp ::parent-id]
          :opt-un [::ephemeral? ::agent-id]))

;; Event type enum (namespaced under :copilot/)
(s/def ::event-type
  #{:copilot/session.start :copilot/session.resume :copilot/session.error :copilot/session.idle
    :copilot/session.info :copilot/session.model_change :copilot/session.handoff
    :copilot/session.truncation :copilot/session.snapshot_rewind :copilot/session.usage_info
    :copilot/session.compaction_start :copilot/session.compaction_complete
    :copilot/session.shutdown :copilot/session.task_complete
    :copilot/session.title_changed :copilot/session.warning :copilot/session.context_changed
    :copilot/session.mode_changed :copilot/session.plan_changed :copilot/session.workspace_file_changed
    :copilot/user.message :copilot/pending_messages.modified
    :copilot/assistant.turn_start :copilot/assistant.intent :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta :copilot/assistant.message :copilot/assistant.message_delta
    :copilot/assistant.streaming_delta :copilot/assistant.turn_end :copilot/assistant.usage
    :copilot/abort
    :copilot/tool.user_requested :copilot/tool.execution_start :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress :copilot/tool.execution_complete
    :copilot/subagent.started :copilot/subagent.completed :copilot/subagent.failed :copilot/subagent.selected
    :copilot/subagent.deselected
    :copilot/skill.invoked
    :copilot/hook.start :copilot/hook.end
    :copilot/system.message
    :copilot/system.notification
    ;; Interaction broadcast events (permission, user input, elicitation, tool flows)
    :copilot/permission.requested :copilot/permission.completed
    :copilot/user_input.requested :copilot/user_input.completed
    :copilot/elicitation.requested :copilot/elicitation.completed
    :copilot/external_tool.requested :copilot/external_tool.completed
    ;; MCP OAuth events
    :copilot/mcp.oauth_required :copilot/mcp.oauth_completed
    ;; Command events
    :copilot/command.queued :copilot/command.execute :copilot/command.completed
    :copilot/commands.changed
    ;; Plan mode events
    :copilot/exit_plan_mode.requested :copilot/exit_plan_mode.completed
    ;; Auto-mode switch events (upstream PR #1228)
    :copilot/auto_mode_switch.requested :copilot/auto_mode_switch.completed
    ;; Session status events
    :copilot/session.tools_updated :copilot/session.background_tasks_changed
    :copilot/session.skills_loaded :copilot/session.mcp_servers_loaded
    :copilot/session.mcp_server_status_changed :copilot/session.extensions_loaded
    :copilot/session.custom_agents_updated
    ;; Sampling events (upstream PR #908)
    :copilot/sampling.requested :copilot/sampling.completed
    ;; Session remote steerable (upstream PR #908)
    :copilot/session.remote_steerable_changed
    ;; Capabilities changed (upstream PR #908)
    :copilot/capabilities.changed
    ;; Schedule events (upstream schema 1.0.42)
    :copilot/session.schedule_created :copilot/session.schedule_cancelled})

;; Session events
(s/def ::already-in-use? boolean?)
(s/def ::remote-steerable? boolean?)
(s/def ::host-type string?)
(s/def ::head-commit string?)
(s/def ::base-commit string?)

(s/def ::detached-from-spawning-parent-session-id string?)
(s/def ::session.start-data
  ;; Note: ::version is intentionally omitted from this hand-written spec.
  ;; The upstream schema types it as `number` while the global `::version`
  ;; spec (used by ::model-info) is `string?`. The generated wire spec
  ;; (github.copilot-sdk.generated.event-specs/session.start-data) is the
  ;; canonical contract for this field.
  (s/keys :req-un [::session-id]
          :opt-un [::producer ::copilot-version ::start-time ::selected-model
                   ::reasoning-effort ::already-in-use? ::remote-steerable? ::host-type ::head-commit ::base-commit
                   ::detached-from-spawning-parent-session-id]))

(s/def ::event-count nat-int?)
(s/def ::session.resume-data
  (s/keys :req-un [::event-count]
          :opt-un [::selected-model ::reasoning-effort ::already-in-use? ::remote-steerable?
                   ::host-type ::head-commit ::base-commit]))

(s/def ::status-code integer?)
(s/def ::provider-call-id string?)
(s/def ::error-type string?)
(s/def ::stack string?)

(s/def ::session.error-data
  (s/keys :req-un [::error-type ::message]
          :opt-un [::stack ::status-code ::provider-call-id ::url]))

(s/def ::session.idle-data map?)

(s/def ::remote-session-id string?)

(s/def ::session.handoff-data
  (s/keys :opt-un [::remote-session-id ::host]))

;; Remote sessions (Mission Control) — RPC result for session.remote.enable
;; (upstream PR #1192). The wire shape `{ url?, remoteSteerable }` becomes
;; `{ :url, :remote-steerable }` after `util/wire->clj` (camel-snake-kebab
;; does not append `?` for booleans). Note this is distinct from the
;; session-event spec ::remote-steerable? above.
(s/def ::remote-steerable boolean?)
(s/def ::remote-enable-result
  (s/keys :req-un [::remote-steerable]
          :opt-un [::url]))

(s/def ::agent-mode #{:interactive :plan :autopilot :shell})
(s/def ::interaction-id string?)
(s/def ::source string?)

;; user.message event data — attachments can include blobs (inbound-only types)
(s/def ::user.message-data
  (s/and (s/keys :req-un [::content]
                 :opt-un [::transformed-content ::source ::agent-mode ::interaction-id])
         #(or (not (contains? % :attachments))
              (s/valid? ::inbound-attachments (:attachments %)))))

;; Queued command response (CLI 1.0.45, session.commands.respondToQueuedCommand)
(s/def ::handled? boolean?)
(s/def ::stop-processing-queue? boolean?)

(s/def ::anthropic-advisor-blocks (s/coll-of any?))
(s/def ::anthropic-advisor-model string?)
(s/def ::assistant.message-data
  (s/keys :req-un [::message-id ::content]
          :opt-un [::tool-requests ::parent-tool-call-id ::encrypted-content
                   ::interaction-id ::output-tokens ::phase ::reasoning-opaque
                   ::reasoning-text ::request-id
                   ::anthropic-advisor-blocks ::anthropic-advisor-model ::model]))

(s/def ::total-response-size-bytes nat-int?)
(s/def ::turn-id ::non-blank-string)

(s/def ::assistant.turn_start-data
  (s/keys :req-un [::turn-id]
          :opt-un [::interaction-id]))

(s/def ::assistant.reasoning-data
  (s/keys :req-un [::reasoning-id ::content]))

(s/def ::delta-content string?)

(s/def ::assistant.reasoning_delta-data
  (s/keys :req-un [::reasoning-id ::delta-content]))

(s/def ::assistant.message_delta-data
  (s/keys :req-un [::message-id ::delta-content]
          :opt-un [::parent-tool-call-id ::interaction-id]))

(s/def ::assistant.streaming_delta-data
  (s/keys :req-un [::total-response-size-bytes]))

(s/def ::api-call-id string?)
(s/def ::cache-read-tokens nat-int?)
(s/def ::cache-write-tokens nat-int?)
(s/def ::cost number?)
(s/def ::duration nat-int?)
(s/def ::initiator string?)
(s/def ::input-tokens nat-int?)
(s/def ::inter-token-latency-ms nat-int?)
(s/def ::quota-snapshots map?)
(s/def ::ttft-ms nat-int?)
(s/def ::copilot-usage map?)

(s/def ::assistant.usage-data
  (s/keys :req-un [::model]
          :opt-un [::api-call-id ::cache-read-tokens ::cache-write-tokens
                   ::copilot-usage ::cost ::duration ::initiator ::input-tokens
                   ::inter-token-latency-ms ::output-tokens ::parent-tool-call-id
                   ::provider-call-id ::quota-snapshots ::reasoning-effort
                   ::reasoning-tokens ::ttft-ms]))

(s/def ::mcp-server-name string?)
(s/def ::mcp-tool-name string?)

(s/def ::tool.execution_start-data
  (s/keys :req-un [::tool-call-id ::tool-name]
          :opt-un [::arguments ::parent-tool-call-id ::mcp-server-name ::mcp-tool-name]))

(s/def ::progress-message string?)
(s/def ::success boolean?)

(s/def ::tool.execution_progress-data
  (s/keys :req-un [::tool-call-id ::progress-message]))

(s/def ::tool.execution_complete-data
  (s/keys :req-un [::tool-call-id ::success]
          :opt-un [::is-user-requested? ::result ::error ::tool-telemetry ::parent-tool-call-id
                   ::model ::interaction-id]))

;; Permission event data — resolved-by-hook indicates the runtime already handled
;; this permission request via a permissionRequest hook (upstream PR #999).
(s/def ::resolved-by-hook boolean?)

;; Session shutdown event
(s/def ::shutdown-type #{"routine" "error"})
(s/def ::error-reason string?)
(s/def ::total-premium-requests nat-int?)
(s/def ::total-api-duration-ms nat-int?)
(s/def ::session-start-time number?)
(s/def ::code-changes map?)
(s/def ::model-metrics map?)
(s/def ::current-model string?)

(s/def ::session.shutdown-data
  (s/keys :req-un [::shutdown-type ::total-premium-requests ::total-api-duration-ms
                   ::session-start-time ::code-changes ::model-metrics]
          :opt-un [::error-reason ::current-model]))

;; Session title changed event
(s/def ::title string?)
(s/def ::session.title_changed-data
  (s/keys :req-un [::title]))

;; Session warning event
(s/def ::warning-type string?)
(s/def ::session.warning-data
  (s/keys :req-un [::warning-type ::message]))

;; Session context changed event
(s/def ::session.context_changed-data
  (s/keys :req-un [::cwd]
          :opt-un [::git-root ::repository ::branch]))

;; Session model change event (upstream PR #796)
(s/def ::previous-model (s/nilable string?))
(s/def ::new-model string?)
(s/def ::previous-reasoning-effort string?)
(s/def ::session.model_change-data
  (s/keys :req-un [::new-model]
          :opt-un [::previous-model ::previous-reasoning-effort ::reasoning-effort]))

;; Session mode changed event
(s/def ::previous-mode string?)
(s/def ::new-mode string?)
(s/def ::session.mode_changed-data
  (s/keys :req-un [::previous-mode ::new-mode]))

;; Session plan changed event
(s/def ::operation #{"create" "update" "delete"})
(s/def ::session.plan_changed-data
  (s/keys :req-un [::operation]))

;; Session workspace file changed event
;; ::path already defined above; ::operation reused but constrained to create/update
(s/def ::session.workspace_file_changed-data
  (s/and (s/keys :req-un [::path ::operation])
         #(contains? #{"create" "update"} (:operation %))))

;; Session task complete event
(s/def ::aborted? boolean?)
(s/def ::session.task_complete-data
  (s/keys :opt-un [::summary ::aborted?]))

;; Schedule events (upstream schema 1.0.42)
;; Note: schedule data uses a positive integer `:id` (numeric scheduled-prompt
;; id, `exclusiveMinimum: 0` in the schema), distinct from the string ::id
;; (UUID) used elsewhere — validated via predicate to avoid colliding with the
;; global ::id spec. ::interval-ms is also strictly positive per schema.
(s/def ::interval-ms pos-int?)
;; ::prompt is already defined above (::non-blank-string), reused here
(s/def ::session.schedule_created-data
  (s/and (s/keys :req-un [::interval-ms ::prompt])
         #(contains? % :id)
         #(pos-int? (:id %))))
(s/def ::session.schedule_cancelled-data
  (s/and map?
         #(contains? % :id)
         #(pos-int? (:id %))))

;; Skill invoked event
(s/def ::allowed-tools (s/coll-of string?))
(s/def ::plugin-name string?)
(s/def ::plugin-version string?)
;; ::description already defined above

(s/def ::skill.invoked-data
  (s/keys :req-un [::name ::path ::content]
          :opt-un [::allowed-tools ::plugin-name ::plugin-version ::description]))

;; Subagent event data (upstream PR #916)
(s/def ::agent-display-name string?)
(s/def ::agent-description string?)
(s/def ::total-tool-calls nat-int?)
(s/def ::total-tokens nat-int?)
(s/def ::duration-ms nat-int?)

(s/def ::subagent.started-data
  (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name]
          :opt-un [::agent-description ::model]))

(s/def ::subagent.completed-data
  (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name]
          :opt-un [::model ::total-tool-calls ::total-tokens ::duration-ms]))

(s/def ::subagent.failed-data
  (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name ::error]
          :opt-un [::model ::total-tool-calls ::total-tokens ::duration-ms]))

;; session.custom_agents_updated event data (upstream PR #916)
(s/def ::user-invocable? boolean?)
(s/def ::agent-tool-names (s/coll-of string?))

(s/def ::custom-agent-info
  (s/and (s/keys :req-un [::id ::name ::display-name ::description ::source ::user-invocable?]
                 :opt-un [::model])
         #(contains? #{"user" "project" "inherited" "remote" "plugin"} (:source %))
         ;; tools is required by the upstream schema but the value may be
         ;; null (`tools: string[] | null` in CustomAgentsUpdatedAgent;
         ;; upstream schema 1.0.41-1). Enforce key presence here, then
         ;; validate the value as either nil or a string-coll.
         #(contains? % :tools)
         #(or (nil? (:tools %)) (s/valid? ::agent-tool-names (:tools %)))))

(s/def ::agents (s/coll-of ::custom-agent-info))
(s/def ::warnings (s/coll-of string?))
(s/def ::errors (s/coll-of string?))

(s/def ::session.custom_agents_updated-data
  (s/keys :req-un [::agents ::warnings ::errors]))

;; Session status/listing events from generated session-events schema.
(s/def ::mcp-server-status
  #{"connected" "failed" "needs-auth" "pending" "disabled" "not_configured"})
(s/def ::status string?)
(s/def ::mcp-loaded-server
  (s/and (s/keys :req-un [::name ::status]
                 :opt-un [::error ::source])
         #(s/valid? ::mcp-server-status (:status %))))
(s/def ::servers (s/coll-of ::mcp-loaded-server))
(s/def ::session.mcp_servers_loaded-data
  (s/keys :req-un [::servers]))
(s/def ::server-name string?)
(s/def ::session.mcp_server_status_changed-data
  (s/and (s/keys :req-un [::server-name ::status])
         #(s/valid? ::mcp-server-status (:status %))))

(s/def ::user-invocable boolean?)
(s/def ::skill-info
  (s/keys :req-un [::name ::description ::enabled ::source ::user-invocable]
          :opt-un [::path]))
(s/def ::skills (s/coll-of ::skill-info))
(s/def ::session.skills_loaded-data
  (s/keys :req-un [::skills]))

(s/def ::extension-status #{"running" "disabled" "failed" "starting"})
(s/def ::extension-info
  (s/and (s/keys :req-un [::id ::name ::source ::status])
         #(s/valid? ::extension-status (:status %))))
(s/def ::extensions (s/coll-of ::extension-info))
(s/def ::session.extensions_loaded-data
  (s/keys :req-un [::extensions]))

;; Generic session event
(s/def ::session-event
  (s/merge ::base-event
           (s/keys :req-un [::event-type ::data])))

;; -----------------------------------------------------------------------------
;; Tool call/result types
;; -----------------------------------------------------------------------------

(s/def ::tool-call-id ::non-blank-string)
(s/def ::result-type
  (s/or :keyword #{:success :failure :rejected :denied :timeout}
        :string #{"success" "failure" "rejected" "denied" "timeout"}))
(s/def ::text-result-for-llm string?)
(s/def ::session-log string?)
(s/def ::tool-telemetry map?)

;; Binary result items for tool results (upstream ToolBinaryResult)
;; Each item has :data (base64 string), :mime-type, :type ("image"/"resource"),
;; and optional :description. Uses map? to avoid conflicts with existing specs
;; for ::type (attachment-specific) — binary result items have different semantics.
(s/def ::binary-results-for-llm (s/coll-of map?))

(s/def ::tool-result-object
  (s/keys :req-un [::text-result-for-llm ::result-type]
          :opt-un [::binary-results-for-llm ::error ::session-log ::tool-telemetry]))

(s/def ::tool-result
  (s/or :string string?
        :object ::tool-result-object))

;; -----------------------------------------------------------------------------
;; Permission types
;; -----------------------------------------------------------------------------

(s/def ::permission-kind #{:shell :write :mcp :read :url :custom-tool :memory :hook
                           :extension-management :extension-permission-access})

;; Memory permission event data fields (CLI 1.0.22, upstream PR #1055)
(s/def ::memory-action #{:store :vote})
(s/def ::memory-direction #{:upvote :downvote})
(s/def ::memory-reason string?)

(s/def ::permission-request
  (s/keys :req-un [::permission-kind]
          :opt-un [::tool-call-id ::memory-action ::memory-direction ::memory-reason
                   ::can-offer-session-approval]))

(s/def ::permission-result-kind
  #{:approve-once
    :approve-for-session
    :approve-for-location
    :reject
    :user-not-available
    :no-result
    ;; Legacy Clojure aliases accepted at API boundaries and normalized before
    ;; sending decisions to the CLI.
    :approved
    :denied-by-rules
    :denied-no-approval-rule-and-could-not-request-from-user
    :denied-interactively-by-user
    :denied-by-content-exclusion-policy
    :denied-by-permission-request-hook})
(s/def ::approval map?)
(s/def ::location-key ::non-blank-string)
(s/def ::rules (s/coll-of map?))
(s/def ::feedback string?)
(s/def ::kind ::permission-result-kind)

(s/def ::permission-result
  (s/keys :req-un [::kind]
          :opt-un [::rules ::approval ::location-key ::feedback]))

;; -----------------------------------------------------------------------------
;; Client record spec
;; -----------------------------------------------------------------------------

(s/def ::options map?)
(s/def ::external-server? boolean?)
(s/def ::actual-host string?)
(s/def ::state #(instance? clojure.lang.Atom %))

(s/def ::client
  (s/keys :req-un [::options ::state]
          :opt-un [::external-server? ::actual-host ::on-list-models]))

;; -----------------------------------------------------------------------------
;; Session record spec
;; -----------------------------------------------------------------------------

(s/def ::session
  (s/keys :req-un [::session-id ::client]
          :opt-un [::workspace-path]))

;; -----------------------------------------------------------------------------
;; API response specs
;; -----------------------------------------------------------------------------

(s/def ::version string?)
(s/def ::protocol-version int?)
(s/def ::authenticated? boolean?)
(s/def ::auth-type keyword?)
(s/def ::host string?)
(s/def ::login string?)
(s/def ::status-message string?)

;; Model capabilities
(s/def ::supports-vision boolean?)
(s/def ::supports-reasoning-effort boolean?)
(s/def ::model-supports
  (s/keys :opt-un [::supports-vision ::supports-reasoning-effort]))

(s/def ::max-prompt-tokens int?)
(s/def ::max-context-window-tokens int?)
(s/def ::supported-media-types (s/coll-of string?))
(s/def ::max-prompt-images int?)
(s/def ::max-prompt-image-size int?)
(s/def ::vision-capabilities
  (s/keys :opt-un [::supported-media-types ::max-prompt-images ::max-prompt-image-size]))
(s/def ::model-limits
  (s/keys :opt-un [::max-prompt-tokens ::max-context-window-tokens ::vision-capabilities]))

(s/def ::model-capabilities
  (s/keys :opt-un [::model-supports ::model-limits]))

;; Model policy
(s/def ::policy-state #{"enabled" "disabled" "unconfigured"})
(s/def ::terms string?)
(s/def ::model-policy
  (s/keys :opt-un [::policy-state ::terms]))

;; Model billing
(s/def ::multiplier number?)
(s/def ::model-billing
  (s/keys :opt-un [::multiplier]))

;; Supported reasoning efforts
(s/def ::supported-reasoning-efforts (s/coll-of string?))
(s/def ::default-reasoning-effort string?)

;; Model picker categorization (CLI 1.0.46). Upstream defines closed enums,
;; but the idiom spec keeps these as strings so future categories pass through
;; unchanged on the wire.
(s/def ::model-picker-category string?)
(s/def ::model-picker-price-category string?)

;; Model info
(s/def ::id string?)
(s/def ::name string?)
(s/def ::vendor string?)
(s/def ::family string?)
(s/def ::max-input-tokens int?)
(s/def ::max-output-tokens int?)
(s/def ::preview? boolean?)
(s/def ::model-info
  (s/keys :req-un [::id ::name]
          :opt-un [::vendor ::family ::version ::max-input-tokens ::max-output-tokens
                   ::preview? ::default-temperature ::model-picker-priority
                   ::model-capabilities ::model-policy ::model-billing
                   ::supported-reasoning-efforts ::default-reasoning-effort
                   ::vision-limits
                   ::model-picker-category ::model-picker-price-category]))

;; Misc specs for instrument.clj
(s/def ::message-id string?)
(s/def ::events-ch any?)  ; core.async channel
(s/def ::buffer pos-int?)
(s/def ::xf fn?)
(s/def ::max-events pos-int?)

;; -----------------------------------------------------------------------------
;; Tool listing (tools.list RPC)
;; -----------------------------------------------------------------------------

(s/def ::namespaced-name string?)
(s/def ::description string?)
(s/def ::parameters (s/nilable map?))
(s/def ::instructions (s/nilable string?))

(s/def ::tool-info-entry
  (s/keys :req-un [::name ::description]
          :opt-un [::namespaced-name ::parameters ::instructions]))

;; -----------------------------------------------------------------------------
;; Account quota (account.getQuota RPC)
;; -----------------------------------------------------------------------------

(s/def ::entitlement-requests number?)
(s/def ::used-requests number?)
(s/def ::remaining-percentage number?)
(s/def ::overage number?)
(s/def ::overage-allowed-with-exhausted-quota? boolean?)
(s/def ::reset-date string?)

(s/def ::quota-snapshot
  (s/keys :req-un [::entitlement-requests ::used-requests ::remaining-percentage
                   ::overage ::overage-allowed-with-exhausted-quota?]
          :opt-un [::reset-date]))

(s/def ::quota-snapshots
  (s/map-of string? ::quota-snapshot))

;; -----------------------------------------------------------------------------
;; Session model operations (session.model.getCurrent / switchTo)
;; -----------------------------------------------------------------------------

(s/def ::model string?)
(s/def ::model-id (s/nilable string?))
