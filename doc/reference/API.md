# API Reference

## Helpers API

The helpers namespace provides simple, stateless query functions with automatic client management.

```clojure
(require '[github.copilot-sdk.helpers :as h])
```

### `query`

```clojure
(h/query prompt & {:keys [client session timeout-ms]})
```

Execute a query and return the response text.

**Options:**
- `:client` - Client options map (cli-path, log-level, cwd, env) OR a CopilotClient instance
- `:session` - Session options map (model, system-prompt, tools, etc.) OR a CopilotSession instance
- `:timeout-ms` - Timeout in milliseconds (default: 180000)

When `:session` is a CopilotSession instance, the query uses that session directly (enabling multi-turn conversations). When `:client` is a CopilotClient instance, it uses that client directly.

```clojure
;; Simple query (shared client, fresh session)
(h/query "What is 2+2?")
;; => "4"

;; With session options
(h/query "Explain monads" :session {:on-permission-request copilot/approve-all :model "claude-sonnet-4.5"})

;; With system prompt
(h/query "Hello" :session {:on-permission-request copilot/approve-all :system-prompt "Be concise."})

;; With explicit client
(copilot/with-client [client {}]
  (h/query "What is Clojure?" :client client))

;; With explicit session (multi-turn conversation)
(copilot/with-client [client {}]
  (copilot/with-session [session client {:on-permission-request copilot/approve-all}]
    (h/query "My name is Alice." :session session)
    (h/query "What is my name?" :session session))) ;; context preserved!
```

### `query-seq!`

```clojure
(h/query-seq! prompt & {:keys [client session max-events]})
```

Execute a query and return a bounded lazy sequence of events with guaranteed cleanup (default: 256 events).

```clojure
(->> (h/query-seq! "Tell me a story" :session {:on-permission-request copilot/approve-all :streaming? true})
     (filter #(= :copilot/assistant.message_delta (:type %)))
     (map #(get-in % [:data :delta-content]))
     (run! print))
```

### `query-chan`

```clojure
(h/query-chan prompt & {:keys [client session buffer]})
```

Execute a query and return a core.async channel of events. Use this when you need an explicit lifecycle
or want to stop reading early without leaking session resources.

```clojure
(let [ch (h/query-chan "Tell me a story" :session {:on-permission-request copilot/approve-all :streaming? true})]
  (go-loop []
    (when-let [event (<! ch)]
      (when (= :copilot/assistant.message_delta (:type event))
        (print (get-in event [:data :delta-content])))
      (recur))))
```

### `shutdown!`

```clojure
(h/shutdown!)
```

Explicitly shutdown the shared client. Safe to call multiple times.

### `client-info`

```clojure
(h/client-info)
;; => {:client-opts {:log-level :info, ...} :connected? true}
```

Get information about the current shared client state. Returns `nil` if no shared client exists, otherwise a map with `:client-opts` and `:connected?` keys.

---

## CopilotClient

```clojure
(require '[github.copilot-sdk :as copilot])
```

### Constructor

```clojure
(copilot/client options)
```

**Options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:cli-path` | string | `"copilot"` | Path to CLI executable. Falls back to `COPILOT_CLI_PATH` env var when not set |
| `:cli-args` | vector | `[]` | Extra arguments prepended before SDK-managed flags |
| `:cli-url` | string | nil | URL of existing CLI server (e.g., `"localhost:8080"`). When provided, no CLI process is spawned |
| `:port` | number | `0` | Server port (0 = random) |
| `:use-stdio?` | boolean | `true` | Use stdio transport instead of TCP |
| `:log-level` | keyword | `:info` | One of `:none` `:error` `:warning` `:info` `:debug` `:all` |
| `:auto-start?` | boolean | `true` | Auto-start server on first operation |
| `:auto-restart?` | boolean | `true` | Auto-restart on crash |
| `:notification-queue-size` | number | `4096` | Max queued protocol notifications |
| `:router-queue-size` | number | `4096` | Max queued non-session notifications |
| `:tool-timeout-ms` | number | `120000` | Timeout for tool handlers returning channels |
| `:cwd` | string | nil | Working directory for CLI process |
| `:env` | map | nil | Environment variables |
| `:github-token` | string | nil | GitHub token for authentication. Sets `COPILOT_SDK_AUTH_TOKEN` env var and passes `--auth-token-env` flag |
| `:use-logged-in-user?` | boolean | `true` | Use logged-in user auth. Defaults to `false` when `:github-token` is provided. Cannot be used with `:cli-url` |
| `:copilot-home` | string | nil | Base directory for Copilot data files. Sets `COPILOT_HOME` env var on the spawned CLI. (upstream PR #1191) |
| `:tcp-connection-token` | string | nil | Connection token for the headless CLI server (TCP only). When the SDK spawns its own CLI in TCP mode and this is omitted, a UUID is generated automatically so the loopback listener is safe by default. The token is sent to the CLI via `COPILOT_CONNECTION_TOKEN` and forwarded over the wire on the new `connect` handshake. Rejected when combined with `:use-stdio? true`. (upstream PR #1176) |
| `:remote?` | boolean | `false` | When `true`, append `--remote` to the spawned CLI args so the CLI exposes the session over a GitHub-hosted remote endpoint. Ignored when `:cli-url` is set. (upstream PR #1192) |
| `:session-idle-timeout-seconds` | integer | `0` (disabled) | Server-wide session idle timeout in seconds. When `> 0`, append `--session-idle-timeout <n>` to the spawned CLI so idle sessions are cleaned up after the given duration. |
| `:on-list-models` | fn | nil | Zero-arg function returning model info maps. Bypasses `models.list` RPC; does not require `start!`. Results are cached the same way as RPC results |
| `:telemetry` | map | nil | OpenTelemetry export config, applied as environment variables to the **spawned** CLI (ignored when connecting to an existing server via `:cli-url` or a parent process via `:is-child-process?`, since no CLI is spawned). When present, enables OTel. Keys (all optional): `:otlp-endpoint` (OTLP HTTP endpoint), `:otlp-protocol` (`"http/json"` or `"http/protobuf"` — sets `OTEL_EXPORTER_OTLP_PROTOCOL`), `:file-path` (write spans to a file), `:exporter-type` (exporter selection), `:source-name` (service/source name), `:capture-content?` (boolean — capture prompt/response content; **off by default for privacy**). See [Observability](#observability). (upstream PR #785, [PR #1648](https://github.com/github/copilot-sdk/pull/1648)) |
| `:on-get-trace-context` | fn | nil | Zero-arg function returning `{:traceparent "..." :tracestate "..."}`, called per request (session create/resume and each message send) to propagate a distributed-trace context. Only `:traceparent` and `:tracestate` are forwarded. See [Observability](#observability) |
| `:is-child-process?` | boolean | `false` | When `true`, connect via own stdio to a parent Copilot CLI process (no process spawning). Requires `:use-stdio?` `true`; mutually exclusive with `:cli-url` |
| `:session-fs` | map | nil | Session filesystem provider config. Keys: `:initial-cwd` (string, required), `:session-state-path` (string, required), `:conventions` (`"windows"` or `"posix"`, required). When set, the client calls `sessionFs.setProvider` on connect and routes filesystem operations through per-session handlers. See [Session Filesystem](#session-filesystem) |
| `:mode` | keyword | `:copilot-cli` | Client multitenancy mode: `:copilot-cli` (default — preserve historical CLI behavior) or `:empty` (multi-tenant SaaS hosts that must isolate sessions from local machine state). In `:empty` mode the SDK requires at least one tenant-scoped storage root (`:copilot-home`, `:session-fs`, `:cli-url`, or `:is-child-process?`), sets `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI, spreads 10 safe defaults under caller session config, forces `installedPlugins []`, and normalizes `:system-message` to strip `environment_context`. See [Client Mode](#client-mode-empty). (upstream PR #1428) |

### Methods

#### `start!`

```clojure
(copilot/start! client)
```

Start the CLI server and establish connection. Blocks until connected.

#### `with-client`

```clojure
(copilot/with-client [client {:log-level :info}]
  ;; use client
  )
```

Create a client, start it, and ensure `stop!` runs on exit.

#### `stop!`

```clojure
(copilot/stop! client)
```

Stop the server and close all sessions gracefully.

For SDK-spawned processes (not `:external-server?`), `stop!` issues a
`runtime.shutdown` RPC before closing the connection, giving the CLI a chance to
flush state and exit cleanly. The call is bounded by a 10-second timeout; on
timeout or error the SDK falls back to terminating the process (SIGTERM, then
SIGKILL). Connecting to an external server (`:cli-url`) skips the shutdown RPC and
the process is left running. (upstream [PR #1667](https://github.com/github/copilot-sdk/pull/1667))

#### `force-stop!`

```clojure
(copilot/force-stop! client)
```

Force stop the CLI server without graceful cleanup. Use when `stop!` takes too long.

#### `client-options`

```clojure
(copilot/client-options client)
;; => {:log-level :info, :use-stdio? true, :auto-start? true, ...}
```

Get the options that were used to create this client.

#### `create-session`

```clojure
(copilot/create-session client config)
```

Create a new conversation session.

#### `with-session`

```clojure
(copilot/with-session [session client {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  ;; use session
  )
```

Create a session and ensure `disconnect!` runs on exit.

#### `with-client-session`

```clojure
;; Form 1: [session session-opts] - anonymous client with default options
(copilot/with-client-session [session {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  ;; use session
  )

;; Form 2: [client-opts session session-opts] - anonymous client with custom options
(copilot/with-client-session [{:log-level :debug} session {:model "gpt-5.4"
                                                           :on-permission-request copilot/approve-all}]
  ;; use session
  )

;; Form 3: [client session session-opts] - named client with default options
(copilot/with-client-session [client session {:model "gpt-5.4"
                                              :on-permission-request copilot/approve-all}]
  ;; use client and session
  )

;; Form 4: [client client-opts session session-opts] - named client with custom options
(copilot/with-client-session [client {:log-level :debug} session {:model "gpt-5.4"
                                                                  :on-permission-request copilot/approve-all}]
  ;; use client and session
  )
```

Create a client and session together, ensuring both are cleaned up on exit.

**Config:**

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Custom session ID (optional) |
| `:client-name` | string | Client name to identify the application (included in User-Agent header) |
| `:model` | string | Model to use (`"gpt-5.4"`, `"claude-sonnet-4.5"`, etc.) |
| `:tools` | vector | Custom tools exposed to the CLI |
| `:system-message` | map | System message customization (see below) |
| `:available-tools` | vector | List of allowed tool names |
| `:excluded-tools` | vector | List of excluded tool names |
| `:provider` | map | Provider config for BYOK (see [BYOK docs](../auth/byok.md)). Required key: `:base-url`. Optional: `:provider-type` (`:openai`/`:azure`/`:anthropic`), `:wire-api` (`:completions`/`:responses`), `:api-key`, `:bearer-token`, `:azure-options`, `:headers` (map of HTTP header name→value, sent with each provider request — upstream PR #1094), `:model-id` (string — the model identifier to send to the provider; overrides session `:model`), `:wire-model` (string — model name as sent on the provider wire when it differs from `:model-id`), `:max-input-tokens` (integer — input/prompt token cap; serialized as wire `maxPromptTokens`), `:max-output-tokens` (integer — output token cap). The four override fields were added in upstream PR #966 |
| `:mcp-servers` | map | MCP server configs keyed by server ID (see [MCP docs](../mcp/overview.md)). Local (stdio) servers: `:mcp-command`, `:mcp-args`, `:mcp-tools`. Remote (HTTP/SSE) servers: `:mcp-server-type` (`:http`/`:sse`), `:mcp-url`, `:mcp-tools`. Spec aliases: `::mcp-stdio-server` = `::mcp-local-server`, `::mcp-http-server` = `::mcp-remote-server` |
| `:commands` | vector | Command definitions (slash commands). See [Commands](#commands) |
| `:custom-agents` | vector | Custom agent configs. Each agent map: `:agent-name` (required), `:agent-prompt` (required), `:agent-display-name`, `:agent-description`, `:agent-tools`, `:agent-infer?`, `:agent-skills` (vector of strings), `:agent-model` (string, e.g. `"claude-haiku-4.5"`; when set the runtime tries this model for the agent, falling back to the parent session model — upstream PR #1309), `:mcp-servers` |
| `:default-agent` | map | Built-in/default agent config. Use `{:excluded-tools [...]}` to hide tools from the default agent while leaving them available to custom agents |
| `:on-permission-request` | fn | Permission handler function. **Optional** (upstream PR #1308). When omitted, permission requests are not auto-resolved; resolve them manually via `handle-pending-permission-request!`. Use `copilot/approve-all` to approve everything. |
| `:streaming?` | boolean | Enable streaming deltas |
| `:config-dir` | string | Override config directory for CLI |
| `:skill-directories` | vector | Additional skill directories to load |
| `:instruction-directories` | vector | Additional directories to search for custom instruction files. Forwarded as `instructionDirectories` on `session.create` and `session.resume`. (upstream PR #1190) |
| `:disabled-skills` | vector | Disable specific skills by name |
| `:large-output` | map | (Experimental) Tool output handling config. CLI protocol feature, not in official SDK. |
| `:working-directory` | string | Working directory for the session (tool operations relative to this) |
| `:infinite-sessions` | map | Infinite session config (see below) |
| `:reasoning-effort` | string | Reasoning effort level: `"low"`, `"medium"`, `"high"`, or `"xhigh"` |
| `:github-token` | string | GitHub token for this session. Sent as `gitHubToken` on `session.create`; use this for per-session authentication when one client manages sessions for different GitHub users |
| `:on-user-input-request` | fn | Handler for `ask_user` requests (see below) |
| `:hooks` | map | Lifecycle hooks (see below) |
| `:agent` | string | Name of a custom agent to activate at session start. Must match a name in `:custom-agents`. Equivalent to calling `agent.select` after creation. |
| `:on-event` | fn | Event handler (1-arg fn receiving event maps). Registered before the RPC call, guaranteeing early events like `session.start` are not missed. |
| `:on-elicitation-request` | fn | Handler for elicitation requests from the agent. When provided, advertises `requestElicitation=true` and handles `elicitation.requested` broadcast events. Single-arg handler receives an `ElicitationContext` map with `:session-id`, `:message`, `:requested-schema`, `:mode`, `:elicitation-source`, `:url`. Returns an `ElicitationResult` map `{:action "accept"/"decline"/"cancel" :content {...}}`. See [Elicitation Provider](#elicitation-provider) |
| `:on-exit-plan-mode` | fn | Handler for `exitPlanMode.request` RPCs — invoked when the agent asks to leave plan mode. When provided, advertises `requestExitPlanMode=true`. Receives the request map; returns the approval result. (upstream PR #1228) |
| `:on-auto-mode-switch` | fn | Handler for `autoModeSwitch.request` RPCs — invoked when the agent asks to switch autonomy mode. When provided, advertises `requestAutoModeSwitch=true`. Receives the request map; returns the approval result. (upstream PR #1228) |
| `:enable-session-telemetry?` | boolean | Enable/disable the CLI's **internal** session telemetry (distinct from the client `:telemetry` OpenTelemetry export). Defaults to enabled for GitHub-authenticated sessions; always disabled when a BYOK `:provider` is set; defaulted to `false` in `:mode :empty` (caller can override). Wire-encoded as `enableSessionTelemetry`. See [Observability](#observability). (upstream PR #1224) |
| `:create-session-fs-handler` | fn | Factory for session filesystem providers. Required when `:session-fs` is set on the client. Called as `(factory session)`, returns a provider-style map or a low-level handler map. See [Session Filesystem](#session-filesystem) |
| `:enable-config-discovery` | boolean | Auto-discover `.mcp.json`, `.vscode/mcp.json`, skills, etc. Instruction files always load regardless. (upstream PR #1044) |
| `:model-capabilities` | map | Model capabilities override. DeepPartial of model capabilities, e.g. `{:model-supports {:supports-vision true}}`. (upstream PR #1029) |
| `:include-sub-agent-streaming-events?` | boolean | Forward streaming events from sub-agents to the parent session's event stream. Defaults to `true` on the wire. (upstream PR #1108) |
| `:remote-session` | keyword | Per-session Mission Control mode: `:off`, `:export`, or `:on`. When omitted, the CLI applies its default. `:off` disables remote, `:export` exports session events to Mission Control without enabling remote steering, `:on` enables both. Forwarded as `remoteSession`. (upstream PR #1295, CLI 1.0.48) |
| `:cloud` | map | (create-session only) Creates a remote cloud session. Shape: `{:repository {:owner "octocat" :name "hello-world" :branch "main"}}` — `:owner` and `:name` are required non-blank strings; `:branch` is optional. Forwarded as `cloud.repository.*` on `session.create`. Not accepted on `resume-session` (matches upstream `ResumeSessionConfig`). When `:cloud` is set and `:session-id` is omitted, the SDK defers id assignment to the server and registers the session under the server-returned id (upstream PR #1479). (upstream PR #1306) |
| `:mcp-oauth-token-storage` | keyword | Controls where MCP OAuth tokens are persisted. `#{:persistent :in-memory}`. Default is server-side (persistent). Set to `:in-memory` in multi-tenant hosts that must not leak tokens to disk. Wire-encoded as `mcpOAuthTokenStorage`. (upstream PR #1326) |
| `:embedding-cache-storage` | keyword | `#{:persistent :in-memory}`. Controls where the embedding cache lives. Wire-encoded as `embeddingCacheStorage`. (upstream PR #1474) |
| `:skip-embedding-retrieval` | boolean | Skip embedding-based context retrieval. (upstream PR #1474) |
| `:organization-custom-instructions` | string | Organization-wide instructions injected by the host. (upstream PR #1474) |
| `:enable-on-demand-instruction-discovery` | boolean | Auto-discover instruction files on demand. (upstream PR #1474) |
| `:enable-file-hooks` | boolean | Enable file-watcher-style lifecycle hooks. (upstream PR #1474) |
| `:enable-host-git-operations` | boolean | Allow the CLI to run git operations through the host. (upstream PR #1474) |
| `:enable-session-store` | boolean | Enable the disk-backed session store. (upstream PR #1474) |
| `:enable-skills` | boolean | Enable skills discovery and loading. (upstream PR #1474) |
| `:plugin-directories` | vector | Extra plugin directories loaded even when `:enable-config-discovery` is `false`. Wire-encoded as `pluginDirectories`. (upstream PR #1482) |
| `:reasoning-summary` | string | `"none"` / `"concise"` / `"detailed"`. Controls inclusion/granularity of reasoning summaries on assistant turns. Wire-encoded as `reasoningSummary`. String-valued for consistency with `:reasoning-effort`. |
| `:context-tier` | keyword \| `nil` | `#{:default :long-context}` selects the long-context model variant; `nil` explicitly clears any prior tier (wire-encoded as JSON `null`). Omit the key entirely to leave the current setting untouched. Wire-encoded as `contextTier` with values `"default"` / `"long_context"`. |
| `:skip-custom-instructions` | boolean | Skip loading user-level custom instruction files. Forwarded via `session.options.update` (NOT `session.create`). Defaulted to `true` in `:empty` mode. (upstream PR #1428) |
| `:custom-agents-local-only` | boolean | Restrict custom-agent loading to caller-supplied configs only (no on-disk discovery). Forwarded via `session.options.update`. Defaulted to `true` in `:empty` mode. (upstream PR #1428) |
| `:coauthor-enabled` | boolean | Add a Copilot Co-authored-by trailer to commits made by the CLI. Forwarded via `session.options.update`. Defaulted to `false` in `:empty` mode. (upstream PR #1428) |
| `:manage-schedule-enabled` | boolean | Enable the built-in schedule-management tools. Forwarded via `session.options.update`. Defaulted to `false` in `:empty` mode. (upstream PR #1428) |
| `:open-canvases` | vector | (resume-session / join-session only) Seed the open-canvases snapshot when reconnecting. Each entry: `{:instance-id ... :extension-id ... :canvas-id ... :reopen bool :availability "ready"\|"stale" :extension-name? ... :title? ... :status? ... :url? ... :input? {...}}`. Caller-defined `:input` keys are preserved verbatim through wire conversion (no kebab→camel re-casing). See [`open-canvases`](#open-canvases). (upstream PR #1604) |
| `:memory` | map | Persistent-memory configuration. Shape: `{:enabled boolean}`. Sent on **both** `session.create` and `session.resume`; omitted entirely when the key is absent (never wire `null`). Wire-encoded as `memory`. In `:mode :empty` it is defaulted to `{:enabled false}` (caller can override). (upstream [PR #1617](https://github.com/github/copilot-sdk/pull/1617)) |

#### `resume-session`

```clojure
(copilot/resume-session client session-id config)
```

Resume an existing session by ID. The `config` map accepts the same options as `create-session` (except `:session-id`), including per-session `:github-token`, plus:

| Option | Type | Description |
|---|---|---|
| `:disable-resume?` | boolean | When true, skip emitting the session.resume event (default: false) |
| `:continue-pending-work?` | boolean | When true, the runtime re-emits any pending `permission.requested` and external tool calls so handlers can re-respond on resume; default false treats pending work as interrupted. Forwarded as `continuePendingWork` on `session.resume`. |
| `:large-output` | map | (Experimental) Tool output handling config. Now also forwarded on `session.resume` (matching upstream `client.ts:1308`). |

When `:on-permission-request` is set to `default-join-session-permission-handler`, the SDK sends `requestPermission: false` on the wire, telling the CLI that this client does not handle permission requests. Any other handler sends `requestPermission: true`.

```clojure
;; Resume with a different model and reasoning effort
(copilot/resume-session client "session-123"
  {:model "claude-sonnet-4"
   :reasoning-effort "high"
   :on-permission-request copilot/approve-all})

;; Resume without handling permissions (join-style)
(copilot/resume-session client "session-123"
  {:on-permission-request copilot/default-join-session-permission-handler})
```

#### `<create-session`

```clojure
(copilot/<create-session client config)
```

Async version of `create-session`. Returns a channel that delivers a `CopilotSession`.

Validation is synchronous (throws immediately on invalid config). The RPC call parks instead of blocking, making this safe inside `go` blocks. On RPC error, delivers an `ExceptionInfo` to the channel instead of a session — check with `(instance? Throwable result)`.

```clojure
(require '[clojure.core.async :refer [go <!]])

(go
  (let [result (<! (copilot/<create-session client {:model "gpt-5.4"
                                                    :on-permission-request copilot/approve-all}))]
    (if (instance? Throwable result)
      (println "Error:" (ex-message result))
      (let [answer (<! (copilot/<send! result {:prompt "Hello"}))]
        (println answer)))))
```

#### `<resume-session`

```clojure
(copilot/<resume-session client session-id config)
```

Async version of `resume-session`. Returns a channel that delivers a `CopilotSession`.

Same config options as `resume-session`. Safe for use inside `go` blocks. On RPC error, delivers an `ExceptionInfo` to the channel — check with `(instance? Throwable result)`.

```clojure
(go
  (let [session (<! (copilot/<resume-session client "session-123"
                                                     {:on-permission-request copilot/approve-all}))]
    ;; use resumed session
    ))
```

#### `join-session`

```clojure
(copilot/join-session config)
```

Join the current foreground session from an extension running as a child process of the Copilot CLI. Reads the `SESSION_ID` environment variable, creates a child-process client, and resumes the session with `:disable-resume?` defaulting to `true`.

Returns a map with `:client` and `:session` keys. The caller is responsible for stopping the client when done.

Throws if `SESSION_ID` is not set in the environment.

```clojure
(let [{:keys [client session]} (copilot/join-session
                                 {:on-permission-request copilot/approve-all
                                  :tools [my-tool]})]
  ;; use session...
  (copilot/stop! client))
```

#### `ping`

```clojure
(copilot/ping client)
(copilot/ping client message)
```

Ping the server to check connectivity. Returns `{:message "..." :timestamp ... :protocol-version ...}`.

#### `get-status`

```clojure
(copilot/get-status client)
```

Get CLI status including version and protocol information. Returns `{:version "0.0.389" :protocol-version 2}`.

#### `get-auth-status`

```clojure
(copilot/get-auth-status client)
```

Get current authentication status. Returns:
```clojure
{:authenticated? true
 :auth-type :user        ; :user | :env | :gh-cli | :hmac | :api-key | :token
 :host "github.com"
 :login "username"
 :status-message "Authenticated as username"}
```

#### `list-models`

```clojure
(copilot/list-models client)
```

List available models with their metadata. Results are cached per client connection.
When `:on-list-models` handler is provided in client options, calls the handler
instead of the RPC method (no connection required).
Requires authentication (unless `:on-list-models` is provided). Returns a vector of model info maps:
```clojure
[{:id "gpt-5.4"
  :name "GPT-5.4"
  :vendor "openai"
  :family "gpt-5.4"
  :version "gpt-5.4"
  :max-input-tokens 128000
  :max-output-tokens 16384
  :preview? false
  :model-capabilities {:model-supports {:supports-vision true
                                        :supports-reasoning-effort false}
                       :model-limits {:max-prompt-tokens 128000
                                      :max-context-window-tokens 128000
                                      :vision-capabilities
                                      {:supported-media-types ["image/png" "image/jpeg"]
                                       :max-prompt-images 10
                                       :max-prompt-image-size 20971520}}}
  :model-policy {:policy-state "enabled"
                 :terms "..."}
  :model-billing {:multiplier 1.0
                  ;; Per-token prices (upstream PR #1633), present when the
                  ;; model reports them; keys are optional:
                  :token-prices {:input-price 0.00000125
                                 :output-price 0.00001
                                 :cache-price 0.0000003125
                                 :long-context {:input-price 0.0000025
                                                :output-price 0.00002}}}
  ;; Model picker categorization (CLI 1.0.46+):
  :model-picker-category "powerful"            ;; "lightweight" | "versatile" | "powerful"
  :model-picker-price-category "very_high"     ;; "low" | "medium" | "high" | "very_high"
  ;; For models supporting reasoning:
  :supported-reasoning-efforts ["low" "medium" "high" "xhigh"]
  :default-reasoning-effort "medium"}
 ...]
```

List all models with their billing multiplier:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (doseq [m (copilot/list-models client)]
    (println (:id m) (str "x" (get-in m [:model-billing :multiplier])))))
;; prints:
;; gpt-5.4 x1.0
;; claude-sonnet-4.5 x1.0
;; o1 x2.0
;; ...
```

#### `list-tools`

```clojure
(copilot/list-tools client)
(copilot/list-tools client "gpt-5.4")
```

List available tools with their metadata. Pass an optional model string to get model-specific tool overrides.

```clojure
(copilot/list-tools client)
;; => [{:name "read_file"
;;      :namespaced-name "builtin.read_file"
;;      :description "Read a file from disk"
;;      :parameters {...}
;;      :instructions "..."}
;;     ...]

;; Print all tool names
(doseq [tool (copilot/list-tools client)]
  (println (:name tool) "-" (:description tool)))
```

Each tool info map contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Short tool name |
| `:namespaced-name` | string | no | Fully qualified tool name |
| `:description` | string | yes | Human-readable description |
| `:parameters` | map | no | JSON Schema of tool parameters |
| `:instructions` | string | no | Usage instructions for the tool |

#### `get-quota`

```clojure
(copilot/get-quota client)
```

Get account quota information. Returns a map of quota type (string) to quota snapshot maps.

```clojure
(copilot/get-quota client)
;; => {"chat" {:entitlement-requests 1000
;;             :used-requests 42
;;             :remaining-percentage 95.8
;;             :overage 0
;;             :overage-allowed-with-exhausted-quota? false
;;             :reset-date "2025-02-01T00:00:00Z"}}

(let [quotas (copilot/get-quota client)]
  (doseq [[type snapshot] quotas]
    (println type ":" (:remaining-percentage snapshot) "% remaining")))
```

Each quota snapshot map contains:

| Key | Type | Description |
|-----|------|-------------|
| `:entitlement-requests` | number | Total allowed requests |
| `:used-requests` | number | Requests used so far |
| `:remaining-percentage` | number | Percentage of quota remaining |
| `:overage` | number | Number of requests over quota |
| `:overage-allowed-with-exhausted-quota?` | boolean | Whether overage is allowed when quota is exhausted |
| `:reset-date` | string (optional) | ISO 8601 date when quota resets |

#### `mcp-config-list` / `mcp-config-add!` / `mcp-config-update!` / `mcp-config-remove!`

> **Experimental:** These wrap server-level MCP configuration RPCs and may change.

```clojure
;; List configured MCP servers
(copilot/mcp-config-list client)
;; => {:servers [...]}

;; Add a new MCP server config
(copilot/mcp-config-add! client {:name "my-server"
                                  :command "npx"
                                  :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                  :tools ["*"]})

;; Update an existing config
(copilot/mcp-config-update! client {:name "my-server" :tools ["read_file"]})

;; Remove a config
(copilot/mcp-config-remove! client {:name "my-server"})
```

#### `state`

```clojure
(copilot/state client)
```

Get current connection state: `:disconnected` | `:connecting` | `:connected` | `:error`

#### `notifications`

```clojure
(copilot/notifications client)
```

Get a channel that receives non-session notifications. The channel is buffered; notifications are dropped if it fills.

#### `on-lifecycle-event`

```clojure
;; Subscribe to all lifecycle events
(def unsub (copilot/on-lifecycle-event client
             (fn [event]
               (println (:lifecycle-event-type event) (:session-id event)))))

;; Subscribe to a specific event type
(def unsub (copilot/on-lifecycle-event client :session.created
             (fn [event]
               (println "New session:" (:session-id event)))))

;; Unsubscribe
(unsub)
```

Subscribe to session lifecycle events dispatched by the CLI server. The handler receives an event map with:

| Key | Type | Description |
|-----|------|-------------|
| `:lifecycle-event-type` | keyword | One of `:session.created`, `:session.deleted`, `:session.updated`, `:session.foreground`, `:session.background` |
| `:session-id` | string | The session ID |
| `:metadata` | map (optional) | Contains `:start-time`, `:modified-time`, and optionally `:summary` |

**Two arities:**
- `(on-lifecycle-event client handler)` — wildcard, receives all lifecycle events
- `(on-lifecycle-event client event-type handler)` — receives only events matching `event-type`

Returns an unsubscribe function. Call it with no arguments to remove the handler.

Handlers are called synchronously on the notification router's go-loop. Keep handlers fast; offload heavy work to another thread or channel.

#### `list-sessions`

```clojure
(copilot/list-sessions client)
(copilot/list-sessions client {:repository "owner/repo" :branch "main"})
```

List available sessions. Pass an optional filter map to narrow results by context fields.

**Filter options:**

| Key | Type | Description |
|-----|------|-------------|
| `:cwd` | string | Filter by working directory |
| `:git-root` | string | Filter by git repository root |
| `:repository` | string | Filter by repository (e.g., `"owner/repo"`) |
| `:branch` | string | Filter by branch name |

Returns a vector of session metadata maps with `:start-time` and `:modified-time` as `java.time.Instant`. Sessions may include a `:context` map with the session's working directory and repository info.

```clojure
(copilot/list-sessions client)
;; => [{:session-id "abc-123"
;;      :start-time #inst "2025-01-15T10:00:00Z"
;;      :modified-time #inst "2025-01-15T10:05:00Z"
;;      :summary "Refactoring auth module"
;;      :remote? false
;;      :context {:cwd "/home/user/project"
;;                :git-root "/home/user/project"
;;                :repository "owner/repo"
;;                :branch "main"}}
;;     ...]
```

#### `get-session-metadata`

```clojure
(copilot/get-session-metadata client session-id)
```

Get metadata for a specific session by ID. Returns the session metadata map if found, or `nil` if the session does not exist. Provides an efficient O(1) lookup instead of calling `list-sessions` and filtering client-side.

The returned map has the same shape as entries returned by `list-sessions`:
- `:session-id` — session ID string
- `:start-time` — `java.time.Instant` when the session was created
- `:modified-time` — `java.time.Instant` of last modification
- `:remote?` — boolean, true if the session is remote
- `:summary` — optional summary string
- `:context` — optional map with `:cwd` and optional `:git-root`, `:repository`, `:branch`

```clojure
(def metadata (copilot/get-session-metadata client "session-abc123"))
;; => {:session-id "session-abc123"
;;     :start-time #object[java.time.Instant 0x... "2025-01-15T10:00:00Z"]
;;     :modified-time #object[java.time.Instant 0x... "2025-01-15T10:05:00Z"]
;;     :remote? false
;;     :summary "Refactoring auth module"
;;     :context {:cwd "/home/user/project"}}

(copilot/get-session-metadata client "non-existent-id")
;; => nil
```

#### `delete-session!`

```clojure
(copilot/delete-session! client session-id)
```

Delete a session and its data from disk. Unlike `disconnect!` (which gracefully closes an active session), `delete-session!` removes persisted session data by ID.

#### `get-last-session-id`

```clojure
(copilot/get-last-session-id client)
```

Get the ID of the most recently updated session.

#### `get-foreground-session-id`

```clojure
(copilot/get-foreground-session-id client)
```

Get the foreground session ID. Returns the session ID or nil. Only applicable in TUI+server mode.

#### `set-foreground-session-id!`

```clojure
(copilot/set-foreground-session-id! client session-id)
```

Set the foreground session. Requests the TUI to switch to displaying the specified session. Only applicable in TUI+server mode.

---

## CopilotSession

Represents a single conversation session.

### Methods

#### `send!`

```clojure
(copilot/send! session options)
```

Send a message to the session. Returns immediately with the message ID.

**Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:prompt` | string | The message/prompt to send |
| `:attachments` | vector | File attachments (see below) |
| `:mode` | keyword | `:enqueue` or `:immediate` |
| `:agent-mode` | keyword | `#{:interactive :plan :autopilot :shell}`. Per-message agent mode. Wire-encoded as `agentMode`. (upstream PR #1438) |
| `:display-prompt` | string | Alternate prompt shown in the timeline UI instead of `:prompt`. Useful when the model-facing prompt contains machinery or context that should not be surfaced to the end user. Wire-encoded as `displayPrompt`. (upstream PR #1470) |
| `:request-headers` | map | Extra HTTP headers (string→string) forwarded to the model provider for this request. Merged with provider-level `:headers`. (upstream PR #1094) |

**Attachment types:**

| Type | Required Keys | Optional Keys | Description |
|------|--------------|---------------|-------------|
| `:file` | `:type`, `:path` | `:display-name`, `:line-range` | File attachment |
| `:directory` | `:type`, `:path` | `:display-name`, `:line-range` | Directory attachment |
| `:selection` | `:type`, `:file-path`, `:display-name` | `:selection-range`, `:text` | Code selection attachment |
| `:github-reference` | `:type`, `:number`, `:title`, `:reference-type`, `:state`, `:url` | — | GitHub issue, PR, or discussion reference |
| `:blob` | `:type`, `:data`, `:mime-type` | `:display-name` | Inline base64-encoded data (e.g. images) |

`:line-range` is a map with `:start` and `:end` line numbers (zero-based) to restrict the attachment to a range of lines:

```clojure
(copilot/send! session
  {:prompt "Explain this function"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :line-range {:start 10 :end 25}}]})
```

Selection range is a map with `:start` and `:end` positions, each containing `:line` and `:character`:

```clojure
(copilot/send! session
  {:prompt "Explain this code"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-fn"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-fn [...] ...)"}]})
```

#### `send-and-wait!`

```clojure
(copilot/send-and-wait! session options)
(copilot/send-and-wait! session options timeout-ms)
```
Send a message and block until the session becomes idle. Returns the final assistant message event.
Default timeout is `300000` ms (5 minutes).

#### `send-async`

```clojure
(copilot/send-async session options)
```

Send a message and return a core.async channel that receives all events for this message, closing when idle.
Safe for use inside `go` blocks — no blocking operations.
Supports `:timeout-ms` in options (default: `300000`) to force cleanup on long-running requests.

#### `send-async-with-id`

```clojure
(copilot/send-async-with-id session options)
```

Send a message and return `{:message-id :events-ch}` for correlating responses.
Supports `:timeout-ms` in options (default: `300000`).

#### `<send!`

```clojure
(copilot/<send! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final content string.
Supports `:timeout-ms` in options (default: `300000`).

Combined with `<create-session`, enables fully non-blocking pipelines:

```clojure
(go
  (let [session (<! (copilot/<create-session client {:model "gpt-5.4"
                                                     :on-permission-request copilot/approve-all}))
        answer  (<! (copilot/<send! session {:prompt "Explain monads"}))]
    (println answer)))
```

#### `<send-and-wait!`

```clojure
(copilot/<send-and-wait! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final assistant message **event** — the same shape as `send-and-wait!`'s successful return value (content lives under `[:data :content]`), or closes with nothing if no assistant message was received.
Supports `:timeout-ms` in options (default: `300000`, set to `nil` to disable).

Error semantics differ from `send-and-wait!`: where `send-and-wait!` throws on `:copilot/session.error` or timeout, this variant never surfaces those — the channel closes (delivering the last assistant message if one arrived, otherwise nothing), consistent with `<send!`.

```clojure
(go
  (let [session (<! (copilot/<create-session client {:on-permission-request copilot/approve-all}))
        event   (<! (copilot/<send-and-wait! session {:prompt "Explain monads"}))]
    (println (get-in event [:data :content]))))
```

Use `<send!` when you only need the content string; use `<send-and-wait!` when you need the full event (metadata, id, etc.).

#### `events`

```clojure
(copilot/events session)
```

Get the core.async `mult` for session events. Use `tap` to subscribe:

```clojure
(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (println event)
      (recur))))
```

#### `events->chan`

```clojure
(copilot/events->chan session {:buffer 256
                               :xf (filter #(= :copilot/assistant.message (:type %)))})
```

Subscribe to session events with optional buffer size and transducer.

#### `subscribe-events`

```clojure
(copilot/subscribe-events session)
```

Subscribe to session events. Returns a channel (sliding buffer, size 1024) that receives events.
This is a convenience wrapper around `(tap (copilot/events session) ch)`.

##### Event Drop Behavior

Session events are delivered via core.async `mult` to a per-subscriber **sliding-buffer**
channel. Because a sliding buffer never blocks on `put!`, `mult` is never stalled by a slow
subscriber. **If a subscriber falls behind and its buffer fills, the oldest buffered events
are dropped for that subscriber only** to make room for new ones.

Key points:
- **Per-subscriber**: Each subscriber is independent. A slow subscriber drops its own oldest
  events without affecting delivery to other subscribers.
- **Oldest-first**: When the buffer is full, the oldest buffered events are dropped, not the
  newest — subscribers always see the most recent events.
- **Silent**: No error, warning, or indication that a drop occurred.
- **Not recoverable**: Dropped events are gone for that subscriber.

With the default 1024 buffer, drops are unlikely unless a subscriber completely stops
reading. For most use cases, this is not a concern.

#### `unsubscribe-events!`

```clojure
(copilot/unsubscribe-events! session ch)
```

Unsubscribe a channel from session events.

#### `abort!`

```clojure
(copilot/abort! session)
```

Abort the currently processing message.

#### `get-messages`

```clojure
(copilot/get-messages session)
```

Get all events/messages from this session.

#### `handle-pending-tool-call!` / `<handle-pending-tool-call!`

```clojure
(copilot/handle-pending-tool-call! session
                                   {:request-id "tool-req-7"
                                    :result "STATUS_OK"})
;; or async:
(copilot/<handle-pending-tool-call! session {:request-id "tool-req-7"
                                             :error "lookup failed"})
```

Resolve a tool call that was not auto-handled (because `:handler` was omitted
from `define-tool`). The args map accepts `:request-id` plus either `:result`
(string or full result map) or `:error` (string). Sent on the wire as
`session.tools.handlePendingToolCall`. (upstream PR #1308)

#### `handle-pending-permission-request!` / `<handle-pending-permission-request!`

```clojure
(copilot/handle-pending-permission-request! session
                                            {:request-id "perm-req-3"
                                             :result {:kind :approve-once}})
```

Resolve a permission request that was not auto-handled (because
`:on-permission-request` was omitted from the session config). The result map
must contain a `:kind` other than `:no-result`. Sent on the wire as
`session.permissions.handlePendingPermissionRequest`. (upstream PR #1308)

#### `get-current-model`

```clojure
(copilot/get-current-model session)
;; => "gpt-5.4"
```

Get the current model for this session. Returns the model ID string, or nil if none set.

#### `switch-model!`

```clojure
(copilot/switch-model! session "claude-sonnet-4.5")
;; => "claude-sonnet-4.5"

;; With model capabilities override (upstream PR #1029):
(copilot/switch-model! session "gpt-5.4"
  {:model-capabilities {:model-supports {:supports-vision true}}})
```

Switch the model for this session mid-conversation. Returns the new model ID string, or nil.

Optional opts map:
- `:reasoning-effort` — Reasoning effort level ("low", "medium", "high", "xhigh")
- `:reasoning-summary` — Reasoning summary mode ("none", "concise", "detailed"). Wire-encoded as `reasoningSummary`.
- `:context-tier` — Context window tier for models that support it: `:default` or `:long-context` (upstream PR #1522). Wire-encoded as `contextTier` with values `"default"` / `"long_context"`.
- `:model-capabilities` — Model capabilities override map, e.g. `{:model-supports {:supports-vision true}}`

#### `set-model!`

```clojure
(copilot/set-model! session "claude-sonnet-4.5")
;; => "claude-sonnet-4.5"
```

Alias for `switch-model!`, matching the upstream SDK's `setModel()` API.

```clojure
(copilot/with-client-session [session {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  (println "Before:" (copilot/get-current-model session))
  (copilot/set-model! session "claude-sonnet-4.5")
  (println "After:" (copilot/get-current-model session)))
;; prints:
;; Before: gpt-5.4
;; After: claude-sonnet-4.5
```

#### `log!`

```clojure
(copilot/log! session "Processing started")
(copilot/log! session "Something went wrong" {:level "error"})
(copilot/log! session "Temporary note" {:ephemeral? true})
```

Log a message to the session timeline. Returns the event ID string.

**Options (optional map):**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:level` | string | `"info"` | Log severity: `"info"`, `"warning"`, or `"error"` |
| `:ephemeral?` | boolean | `false` | When `true`, the message is transient and not persisted to disk |

#### `disconnect!`

```clojure
(copilot/disconnect! session)
```

Disconnect the session and free resources. This is the preferred way to close a session.

#### `destroy!` *(deprecated)*

```clojure
(copilot/destroy! session)
```

**Deprecated.** Use `disconnect!` instead. `destroy!` delegates to `disconnect!` and will be removed in a future release.

#### `session-id`

```clojure
(copilot/session-id session)
```

Get the session's unique identifier.

#### `workspace-path`

```clojure
(copilot/workspace-path session)
```

Get the session workspace path when provided by the CLI (may be nil).

#### `session-config`

```clojure
(copilot/session-config session)
;; => {:model "gpt-5.4", :streaming? true, :reasoning-effort "high", ...}
```

Get the configuration that was used to create this session.

#### `client`

```clojure
(copilot/client session)
```

Get the client that owns this session.

---

### Experimental RPC Methods

> **Note:** These are experimental APIs wrapping emerging CLI RPC methods. They may change in future releases.

```clojure
(require '[github.copilot-sdk.session :as session])

;; List available skills
(session/skills-list my-session)
;; => {:skills [{:name "update-docs" :source-location "project" ...} ...]}

;; Enable/disable MCP servers
(session/mcp-enable! my-session "my-server")
(session/mcp-disable! my-session "my-server")

;; Get/set agent mode
(session/mode-get my-session)
;; => {:mode "interactive"}
(session/mode-set! my-session "plan")

;; Read/update session plan
(session/plan-read my-session)
;; => {:exists? true :content "# Plan\n..." :file-path "/path/to/plan.md"}
(session/plan-update! my-session "# Updated Plan\n...")
(session/plan-delete! my-session)

;; Workspace file operations
(session/workspace-list-files my-session)
;; => {:files ["notes.md" "data.json"]}
(session/workspace-read-file my-session "notes.md")
;; => {:content "..."}
(session/workspace-create-file! my-session "output.txt" "result data")

;; Custom agent management
(session/agent-list my-session)
;; => {:agents [{:name "researcher" ...} ...]}
(session/agent-select! my-session "researcher")
(session/agent-get-current my-session)
;; => {:name "researcher"}
(session/agent-deselect! my-session)
```

**Skills**

| Function | Description |
|----------|-------------|
| `session/skills-list` | List available skills. Returns map with `:skills`. |
| `session/skills-enable!` | Enable a skill by name. |
| `session/skills-disable!` | Disable a skill by name. |
| `session/skills-reload!` | Reload all skills. |

**Queued commands**

The CLI emits `:copilot/command.queued` events when a slash-command is
dispatched for client-side execution. Each event carries a `:request-id`
and `:command`. Clients respond via `respond-to-queued-command!`:

```clojure
;; Inside an event handler that observes :copilot/command.queued
(session/respond-to-queued-command! session
                                    {:request-id (:request-id event-data)
                                     :handled? true
                                     :stop-processing-queue? false})

;; Or, to let the CLI fall back to default handling:
(session/respond-to-queued-command! session
                                    {:request-id (:request-id event-data)
                                     :handled? false})
```

| Function | Description |
|----------|-------------|
| `session/respond-to-queued-command!` | Acknowledge a `command.queued` event (experimental). |

**MCP Servers**

| Function | Description |
|----------|-------------|
| `session/mcp-list` | List configured MCP servers. |
| `session/mcp-enable!` | Enable an MCP server by name. |
| `session/mcp-disable!` | Disable an MCP server by name. |
| `session/mcp-reload!` | Reload all MCP servers. |

**Extensions**

| Function | Description |
|----------|-------------|
| `session/extensions-list` | List extensions. |
| `session/extensions-enable!` | Enable an extension by ID. |
| `session/extensions-disable!` | Disable an extension by ID. |
| `session/extensions-reload!` | Reload all extensions. |

**Mode**

| Function | Description |
|----------|-------------|
| `session/mode-get` | Get current agent mode. Returns `{:mode "interactive"\|"plan"\|"autopilot"}`. |
| `session/mode-set!` | Set agent mode. Accepts `"interactive"`, `"plan"`, or `"autopilot"`. |

**Plan**

| Function | Description |
|----------|-------------|
| `session/plan-read` | Read the session plan file. Returns `{:exists? :content :file-path}`. |
| `session/plan-update!` | Update the plan file content. |
| `session/plan-delete!` | Delete the plan file. |

**Workspace**

| Function | Description |
|----------|-------------|
| `session/workspace-list-files` | List files in the session workspace. Returns `{:files [...]}`. |
| `session/workspace-read-file` | Read a workspace file by relative path. Returns `{:content "..."}`. |
| `session/workspace-create-file!` | Create a file in the workspace with given path and content. |

**Agents**

| Function | Description |
|----------|-------------|
| `session/agent-list` | List available custom agents. Returns `{:agents [...]}`. |
| `session/agent-get-current` | Get the currently selected agent. Returns `{:name "..."}` or `{:name nil}`. |
| `session/agent-select!` | Select a custom agent by name. |
| `session/agent-deselect!` | Deselect the current custom agent. |
| `session/agent-reload!` | Reload all custom agents. |

**Fleet**

| Function | Description |
|----------|-------------|
| `session/fleet-start!` | Start parallel sub-sessions. Accepts a params map. |

**Other**

| Function | Description |
|----------|-------------|
| `session/plugins-list` | List plugins. |
| `session/compaction-compact!` | Trigger manual context compaction (uses `session.history.compact` RPC). |
| `session/history-truncate!` | Trigger manual context truncation. |
| `session/sessions-fork!` | Fork the current session. |
| `session/shell-exec!` | Execute a shell command. |
| `session/shell-kill!` | Kill a running shell process. |

**Session Name**

| Function | Description |
|----------|-------------|
| `session/session-name-get` | Get the session name (or auto-generated summary). Returns `{:name "..."}`. |
| `session/session-name-set!` | Set the session name (1–100 characters). |

```clojure
(session/session-name-get my-session)
;; => {:name "My debugging session"}

(session/session-name-set! my-session "Refactoring auth module")
```

**Workspace (Extended)**

| Function | Description |
|----------|-------------|
| `session/workspace-get-workspace` | Get current workspace metadata. Returns `{:workspace {...}}`. |

```clojure
(session/workspace-get-workspace my-session)
;; => {:workspace {:path "/home/user/project" ...}}
```

**MCP Discovery**

| Function | Description |
|----------|-------------|
| `session/mcp-discover` | Discover MCP servers in a directory. Accepts optional opts map with `:working-directory`. |

```clojure
(session/mcp-discover my-session)

(session/mcp-discover my-session {:working-directory "/path/to/project"})
```

**Usage Metrics**

| Function | Description |
|----------|-------------|
| `session/usage-get-metrics` | Get usage metrics for the session. |

```clojure
(session/usage-get-metrics my-session)
```

**Remote Sessions** (experimental, upstream PR #1192)

| Function | Description |
|----------|-------------|
| `session/remote-enable` | Enable remote steerability for the session. Returns `{:url <string?> :remote-steerable <boolean>}`. Optional 2-arity `opts` map accepts `:mode` set to `:off`, `:export`, or `:on` (upstream CLI 1.0.48-1). |
| `session/remote-disable` | Disable remote steerability for the session. Returns `nil`. |

```clojure
(session/remote-enable my-session)
;; => {:url "https://copilot-remote.test/abc" :remote-steerable true}

;; Optional per-session mode (upstream CLI 1.0.48-1):
;; - :off    — disable remote
;; - :export — export session events to Mission Control without remote steering
;; - :on     — export + enable remote steering
(session/remote-enable my-session {:mode :export})
;; => {:remote-steerable false}

(session/remote-disable my-session)
;; => nil
```

---

## UI Elicitation

Request structured user input via interactive dialogs. Check host support before calling.

```clojure
(require '[github.copilot-sdk :as copilot])
```

### `capabilities`

```clojure
(copilot/capabilities session)
;; => {:ui {:elicitation true}}
```

Get the host capabilities map reported when the session was created or resumed.

### `open-canvases`

```clojure
(copilot/open-canvases session)
;; => [{:instance-id "i1" :canvas-id "diff" :extension-id "ext.x"
;;      :reopen false :availability "ready"}]
```

Get the current open-canvases snapshot for `session`. Returns a vector of
canvas-instance maps. The snapshot is initialized from `session.resume` and
updated by `:copilot/session.canvas.opened` / `:copilot/session.canvas.closed`
events. `session.create` does NOT populate it (matches upstream Node.js).

Each entry has required keys `:instance-id`, `:extension-id`, `:canvas-id`,
`:reopen`, `:availability` and optional `:extension-name`, `:title`,
`:status`, `:url`, `:input`. Closing an instance that's not in the snapshot is a
silent no-op (idempotent); malformed payloads (missing required field, wrong
type, or invalid `:availability`) log a warning and leave the snapshot
unchanged — matches upstream `isOpenCanvasInstance` strictness.

The `:input` map (caller-defined opaque data on each canvas) is preserved
verbatim through wire conversion. Keys you receive (e.g. via the canvas
opened event or after a resume) round-trip back to the CLI without
camelCasing — including `snake_case` and nested keys.

#### Seeding `open-canvases` on resume

To restore canvases after reconnecting, pass `:open-canvases` to
[`resume-session`](#resume-session) or [`join-session`](#join-session). The
shape mirrors what `(open-canvases session)` returned previously:

```clojure
(let [snap (copilot/open-canvases old-session)]
  (copilot/resume-session client session-id
                          {:on-permission-request copilot/approve-all
                           :open-canvases snap}))
```

The SDK preserves caller-defined `:input` keys verbatim on the wire (they are
sent as JSON object fields with the original key names, unchanged by Clojure's
kebab-case conversion).

### `elicitation-supported?`

```clojure
(copilot/elicitation-supported? session)
;; => true
```

Return `true` if the CLI host supports interactive elicitation dialogs.

### `confirm!`

```clojure
(copilot/confirm! session message)
```

Show a confirmation dialog. Returns `true` if the user confirms, `false` if they decline or cancel. Throws if elicitation is not supported.

```clojure
(when (copilot/elicitation-supported? session)
  (when (copilot/confirm! session "Deploy to production?")
    (println "Deploying...")))
```

### `select!`

```clojure
(copilot/select! session message options)
```

Show a selection dialog with the given options. Returns the selected value as a string, or `nil` if the user declines or cancels. Throws if elicitation is not supported.

```clojure
(when-let [env (copilot/select! session "Choose environment" ["staging" "production"])]
  (println "Selected:" env))
```

### `input!`

```clojure
(copilot/input! session message)
(copilot/input! session message opts)
```

Show a text input dialog. Returns the entered text as a string, or `nil` if the user declines or cancels. Throws if elicitation is not supported.

**Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:title` | string | Title for the input field |
| `:description` | string | Description text |
| `:min-length` | integer | Minimum input length |
| `:max-length` | integer | Maximum input length |
| `:format` | string | Input format (`"email"`, `"uri"`, `"date"`, `"date-time"`) |
| `:default` | string | Default value |

```clojure
(when-let [name (copilot/input! session "Enter your name"
                  {:min-length 1
                   :max-length 100})]
  (println "Hello," name))
```

### `ui-elicitation!`

```clojure
(copilot/ui-elicitation! session params)
```

Raw elicitation request for custom JSON schemas. `params` is a map with `:message` and `:requested-schema` keys. Returns a map with `:action` (`"accept"`, `"decline"`, or `"cancel"`) and `:content`. Throws if elicitation is not supported.

```clojure
(copilot/ui-elicitation! session
  {:message "Configure deployment"
   :requested-schema {:type "object"
                      :properties {"env" {:type "string" :enum ["staging" "production"]}
                                   "replicas" {:type "number" :default 3}}
                      :required ["env"]}})
;; => {:action "accept", :content {:env "staging", :replicas 3}}
```

---

## Event Types

Sessions emit various events during processing. All event types are namespaced keywords prefixed with `copilot/`.

### Exported Constants

```clojure
;; All event types
copilot/event-types
;; => #{:copilot/session.idle :copilot/assistant.message ...}

;; Session lifecycle events
copilot/session-events
;; => #{:copilot/session.start :copilot/session.idle ...}

;; Assistant response events  
copilot/assistant-events
;; => #{:copilot/assistant.message :copilot/assistant.message_delta ...}

;; Tool execution events
copilot/tool-events
;; => #{:copilot/tool.execution_start :copilot/tool.execution_complete ...}

;; Interaction flow events (permission, user input, elicitation)
copilot/interaction-events
;; => #{:copilot/permission.requested :copilot/permission.completed
;;      :copilot/user_input.requested :copilot/user_input.completed
;;      :copilot/elicitation.requested :copilot/elicitation.completed
;;      :copilot/external_tool.requested :copilot/external_tool.completed
;;      :copilot/mcp.oauth_required :copilot/mcp.oauth_completed
;;      :copilot/command.queued :copilot/command.execute
;;      :copilot/command.completed :copilot/commands.changed
;;      :copilot/exit_plan_mode.requested :copilot/exit_plan_mode.completed}
```

### `evt` — Event Keyword Helper

```clojure
(copilot/evt :session.info)      ;; => :copilot/session.info
(copilot/evt :assistant.message) ;; => :copilot/assistant.message
```

Convert an unqualified event keyword to a namespace-qualified `:copilot/` keyword. Throws `IllegalArgumentException` if the keyword is not a valid event type.

### Event Reference

| Event Type | Description |
|------------|-------------|
| `:copilot/session.start` | Session created |
| `:copilot/session.resume` | Session resumed |
| `:copilot/session.error` | Session error occurred; data: `{:error-type "..." :message "..." :stack "..." :status-code 429 :provider-call-id "..." :url "..."}` (`:stack`, `:status-code`, `:provider-call-id`, `:url` optional) |
| `:copilot/session.idle` | Session finished processing |
| `:copilot/session.info` | Informational session update |
| `:copilot/session.model_change` | Session model changed |
| `:copilot/session.handoff` | Session handed off to another agent; data: `{:remote-session-id "..." :host "https://github.com"}` (both optional) |
| `:copilot/session.usage_info` | Token usage information |
| `:copilot/session.context_changed` | Session context (cwd, repo, branch) changed |
| `:copilot/session.title_changed` | Session title updated |
| `:copilot/session.warning` | Session warning (e.g., quota limits) |
| `:copilot/session.shutdown` | Session is shutting down |
| `:copilot/session.truncation` | Context window truncated |
| `:copilot/session.snapshot_rewind` | Session state rolled back |
| `:copilot/session.compaction_start` | Context compaction started (infinite sessions) |
| `:copilot/session.compaction_complete` | Context compaction completed (infinite sessions) |
| `:copilot/session.mode_changed` | Session agent mode changed; data: `{:previous-mode "...", :new-mode "..."}` |
| `:copilot/session.plan_changed` | Session plan created/updated/deleted; data: `{:operation "create"/"update"/"delete"}` |
| `:copilot/session.workspace_file_changed` | Workspace file created or updated; data: `{:path "...", :operation "create"/"update"}` |
| `:copilot/session.task_complete` | Task completed by the session agent; data: `{:summary "..." :aborted? false}` (both optional) |
| `:copilot/session.todos_changed` | Signal-only: the agent's todos / todo-deps table was written. **No payload.** Events arrive in order; debounce on arrival if needed (upstream schema 1.0.63) |
| `:copilot/session.schedule_created` | Scheduled prompt registered via `/every`; data: `{:id <pos-int> :interval-ms <pos-int> :prompt "..."}` (upstream schema 1.0.42) |
| `:copilot/session.schedule_cancelled` | Scheduled prompt cancelled from the schedule manager dialog; data: `{:id <pos-int>}` (upstream schema 1.0.42) |
| `:copilot/session.autopilot_objective_changed` | Autopilot objective lifecycle events; data: `{:operation #{"create" "update" "delete"}}` (required) with optional `:id` (integer) and `:status` (upstream schema 1.0.56). The `:status` enum is widened to include `"active"`, `"paused"`, `"cap_reached"`, `"completed"`. |
| `:copilot/session.permissions_changed` | Per-session permission flags changed; data: `{:allow-all-permissions boolean :previous-allow-all-permissions boolean}` (upstream schema 1.0.56). |
| `:copilot/skill.invoked` | Skill invocation triggered; data includes :name, :path, :content, optional :description, :plugin-name, :plugin-version |
| `:copilot/user.message` | User message added |
| `:copilot/pending_messages.modified` | Pending message queue updated |
| `:copilot/assistant.turn_start` | Assistant turn started |
| `:copilot/assistant.intent` | Assistant intent update |
| `:copilot/assistant.reasoning` | Model reasoning (if supported) |
| `:copilot/assistant.reasoning_delta` | Streaming reasoning chunk |
| `:copilot/assistant.message` | Complete assistant response |
| `:copilot/assistant.message_delta` | Streaming response chunk |
| `:copilot/assistant.streaming_delta` | Response size update during streaming; data: `{:total-response-size-bytes N}` |
| `:copilot/assistant.turn_end` | Assistant turn completed |
| `:copilot/assistant.usage` | Token usage for this turn; data may include optional `:content-filter-triggered` (boolean) and `:finish-reason` (string) (upstream schema 1.0.63) |
| `:copilot/abort` | Current message aborted |
| `:copilot/tool.user_requested` | Tool execution requested by user |
| `:copilot/tool.execution_start` | Tool execution started; data includes `:tool-call-id`, `:tool-name`, optional `:arguments`, `:parent-tool-call-id`, `:mcp-server-name`, `:mcp-tool-name`, `:model` |
| `:copilot/tool.execution_progress` | Tool execution progress update |
| `:copilot/tool.execution_partial_result` | Tool execution partial result |
| `:copilot/tool.execution_complete` | Tool execution completed; data may include optional `:structured-content` (arbitrary structured tool result) (upstream schema 1.0.63) |
| `:copilot/subagent.started` | Subagent started; data includes :tool-call-id, :agent-name, :agent-display-name, :agent-description |
| `:copilot/subagent.completed` | Subagent completed; data includes :tool-call-id, :agent-name, :agent-display-name, optional :model, :total-tool-calls, :total-tokens, :duration-ms |
| `:copilot/subagent.failed` | Subagent failed; data includes :tool-call-id, :agent-name, :agent-display-name, :error, optional :model, :total-tool-calls, :total-tokens, :duration-ms |
| `:copilot/subagent.selected` | Subagent selected |
| `:copilot/subagent.deselected` | Subagent deselected |
| `:copilot/hook.start` | Hook invocation started |
| `:copilot/hook.progress` | Ephemeral progress update from a long-running hook; data: `{:message "..."}` (upstream schema 1.0.56). |
| `:copilot/hook.end` | Hook invocation finished |
| `:copilot/system.message` | System message emitted |
| `:copilot/system.notification` | System notification with structured `:kind` discriminator (e.g. `agent_completed`, `shell_completed`, `shell_detached_completed`) |
| `:copilot/permission.requested` | Permission request initiated; data includes `:resolved-by-hook` when already handled by a hook |
| `:copilot/permission.completed` | Permission request resolved |
| `:copilot/user_input.requested` | User input requested from agent |
| `:copilot/user_input.completed` | User input received |
| `:copilot/elicitation.requested` | Elicitation request initiated |
| `:copilot/elicitation.completed` | Elicitation request resolved |
| `:copilot/external_tool.requested` | External tool call requested (v3) |
| `:copilot/external_tool.completed` | External tool call completed (v3) |
| `:copilot/mcp.oauth_required` | MCP server requires OAuth authentication |
| `:copilot/mcp.oauth_completed` | MCP OAuth authentication completed |
| `:copilot/command.queued` | Command queued for execution |
| `:copilot/command.execute` | Command execution started |
| `:copilot/command.completed` | Command execution completed |
| `:copilot/commands.changed` | Available commands list changed |
| `:copilot/exit_plan_mode.requested` | Exit from plan mode requested |
| `:copilot/exit_plan_mode.completed` | Exit from plan mode completed |
| `:copilot/session.tools_updated` | Session tools list updated (e.g., after model change) |
| `:copilot/session.background_tasks_changed` | Background tasks status changed |
| `:copilot/session.skills_loaded` | Skills loaded for the session |
| `:copilot/session.mcp_servers_loaded` | MCP servers loaded for the session |
| `:copilot/session.mcp_server_status_changed` | MCP server status changed |
| `:copilot/session.extensions_loaded` | Extensions loaded for the session |
| `:copilot/session.custom_agents_updated` | Custom agents list updated |
| `:copilot/session.custom_notification` | Custom Skill notification (Notify block); ephemeral. Data: `{:source "<ext-id>" :name "<event>" :payload <any> :subject {<k> <v>} :version <pos-int>}` (`:subject` and `:version` are optional; `:subject` keys are preserved verbatim — see PR #1292, CLI 1.0.48) |
| `:copilot/sampling.requested` | MCP sampling request initiated; ephemeral |
| `:copilot/sampling.completed` | MCP sampling request completed; ephemeral |
| `:copilot/session.remote_steerable_changed` | Session remote steering capability changed; data: `{:remote-steerable true/false}` |
| `:copilot/capabilities.changed` | Session capabilities dynamically changed (e.g., elicitation support); ephemeral. Data: `{:ui {:elicitation true/false}}` |
| `:copilot/mcp_app.tool_call_complete` | An MCP App tool call completed (upstream schema 1.0.52-4, SEP-1865); ephemeral. Data: `{:server-name ... :tool-name ... :duration-ms ... :success bool :arguments {...} :result {...}}` — `:arguments` and `:result` are opaque source-defined maps whose keys are preserved verbatim (not kebab-cased). |
| `:copilot/session.canvas.opened` | A canvas (auxiliary UI surface) was opened in the session; ephemeral. Data: `{:instance-id ... :canvas-id ... :extension-id ... :reopen bool :availability "ready"|"stale" :extension-name? ... :title? ... :status? ... :url? ... :input? {...}}`. The SDK upserts the entry into the [`open-canvases`](#open-canvases) snapshot before publishing. |
| `:copilot/session.canvas.closed` | A canvas was closed; ephemeral. Data: `{:instance-id ... :canvas-id ... :extension-id ...}`. The SDK removes the matching entry from the [`open-canvases`](#open-canvases) snapshot before publishing. (upstream PR #1604) |
| `:copilot/session.canvas.registry_changed` | The set of canvases the host can offer changed; ephemeral. |

### Example: Handling Events

```clojure
(copilot/with-client-session [session {:streaming? true
                                       :on-permission-request copilot/approve-all}]
  (let [ch (chan 256)]
    (tap (copilot/events session) ch)
    (go-loop []
      (when-let [event (<! ch)]
        (case (:type event)
          :copilot/assistant.message_delta
          (print (get-in event [:data :delta-content]))
          
          :copilot/session.usage_info
          (println "Tokens:" (get-in event [:data :current-tokens]))
          
          :copilot/session.idle
          (println "\nDone!")
          
          nil)
        (recur)))
    (copilot/send! session {:prompt "Hello"})))
```

---

## Streaming

Enable streaming to receive assistant response chunks as they're generated:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :streaming? true
                :on-permission-request copilot/approve-all}))

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :copilot/assistant.message_delta
          ;; Streaming chunk - print incrementally
          (print (get-in event [:data :delta-content]))

        :copilot/assistant.reasoning_delta
          ;; Streaming reasoning (model-dependent). Send to stderr.
          (binding [*out* *err*]
            (print (get-in event [:data :delta-content])))

        :copilot/assistant.reasoning
          (binding [*out* *err*]
            (println "\n--- Final Reasoning ---")
            (println (get-in event [:data :content])))

        :copilot/assistant.message
          ;; Final complete message
          (println "\n--- Final ---")
          (println (get-in event [:data :content]))

        nil)
      (recur))))

(copilot/send! session {:prompt "Solve a logic puzzle and show your reasoning."})
```

When `:streaming? true`:
- `:copilot/assistant.message_delta` events contain incremental text in `:delta-content`
- `:copilot/assistant.reasoning_delta` events contain incremental reasoning in `:delta-content` (model-dependent)
- Accumulate delta values to build the full response progressively
- The final `:copilot/assistant.message` event always contains the complete content

---

## Observability

The SDK supports two independent telemetry mechanisms.

### OpenTelemetry export (client `:telemetry`)

Pass a `:telemetry` map in the **client** options to enable OpenTelemetry export on the
spawned CLI. Presence of the map enables OTel; all sub-keys are optional:

```clojure
(def client
  (copilot/client
    {:telemetry {:otlp-endpoint "http://localhost:4318"
                 :exporter-type "otlp"
                 :source-name   "my-app"
                 :capture-content? false}}))
```

| Key | Type | Description | CLI env var |
|-----|------|-------------|-------------|
| `:otlp-endpoint` | string | OTLP HTTP endpoint to export spans to | `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `:otlp-protocol` | string | OTLP wire protocol: `"http/json"` or `"http/protobuf"` | `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `:file-path` | string | Write spans to a local file instead of/alongside OTLP | `COPILOT_OTEL_FILE_EXPORTER_PATH` |
| `:exporter-type` | string | Exporter selection | `COPILOT_OTEL_EXPORTER_TYPE` |
| `:source-name` | string | Service / source name attached to spans | `COPILOT_OTEL_SOURCE_NAME` |
| `:capture-content?` | boolean | Capture prompt/response content in spans. **Defaults to off** — only enable in trusted environments, as it records message content | `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` |

When `:telemetry` is present the SDK sets `COPILOT_OTEL_ENABLED=true` on the CLI process.
(upstream PR #785, [PR #1648](https://github.com/github/copilot-sdk/pull/1648))

#### Distributed trace propagation (`:on-get-trace-context`)

To stitch CLI spans into a caller-managed distributed trace, provide a zero-arg
`:on-get-trace-context` function in the **client** options. The SDK calls it **per request**
to capture a fresh trace context — on session create, session resume, and every message
send — forwarding only `:traceparent` and `:tracestate`:

```clojure
(def client
  (copilot/client
    {:on-get-trace-context
     (fn [] {:traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
             :tracestate  "rojo=00f067aa0ba902b7"})}))
```

### Internal session telemetry (`:enable-session-telemetry?`)

`:enable-session-telemetry?` is a **session** config flag that controls the CLI's own
internal usage telemetry — independent of the OpenTelemetry export above. It defaults to
enabled for GitHub-authenticated sessions and is **always disabled** when a BYOK
`:provider` is configured. In `:mode :empty` it is defaulted to `false` as one of the
multi-tenant hardening defaults (the caller can still set it explicitly). Set it to
`false` to opt out:

```clojure
(def session
  (copilot/create-session client
    {:enable-session-telemetry? false
     :on-permission-request copilot/approve-all}))
```

(upstream PR #1224)

---

## Advanced Usage

### Manual Server Control

```clojure
(def client (copilot/client {:auto-start? false}))

;; Start manually
(copilot/start! client)

;; Use client...

;; Stop manually
(copilot/stop! client)
```

### Client Mode (Empty)

`:mode :empty` configures the client for multi-tenant SaaS hosts that must
isolate sessions from the local machine — no on-disk state from a
specific user account leaks into a session. The default `:copilot-cli`
mode preserves historical CLI behavior. (upstream PR #1428)

```clojure
(require '[github.copilot-sdk :as copilot]
         '[github.copilot-sdk.tool-set :as tool-set])

(def client
  (copilot/client
    {:mode :empty
     ;; At least ONE of :copilot-home / :session-fs / :cli-url /
     ;; :is-child-process? is required so the CLI has a tenant-scoped
     ;; storage root. Using both is fine and common:
     :copilot-home "/srv/tenants/acme/copilot-home"
     :session-fs   {:initial-cwd "/srv/tenants/acme/cwd"
                    :session-state-path "/srv/tenants/acme/state"
                    :conventions "posix"}}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     ;; Required in :empty mode (use [] to allow nothing — the key must
     ;; be present so silently-empty filters can't happen):
     :available-tools tool-set/isolated
     ;; Required when client has :session-fs:
     :create-session-fs-handler (fn [_session] my-fs-handler)}))
```

What `:empty` mode enforces (vs `:copilot-cli`):

- **Constructor validation**: at least one of `:copilot-home`,
  `:session-fs`, `:cli-url`, or `:is-child-process?` must be supplied
  (so the CLI never falls back to the user's home directory). The SDK
  also forces `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI.
- **Session validation**: every `create-session` / `resume-session` call
  must provide `:available-tools` (an empty vector is legitimate). When
  the client has `:session-fs`, `:create-session-fs-handler` is also
  required (this applies to both modes).
- **Safe session defaults** (spread UNDER caller config — caller always wins):
  `:enable-session-telemetry? false`, `:mcp-oauth-token-storage :in-memory`,
  `:skip-embedding-retrieval true`, `:embedding-cache-storage :in-memory`,
  `:enable-on-demand-instruction-discovery false`, `:enable-file-hooks false`,
  `:enable-host-git-operations false`, `:enable-session-store false`,
  `:enable-skills false`, `:memory {:enabled false}`.
- **System message normalization**: the SDK strips the `environment_context`
  section from the system message (or promotes `:append` to `:customize`) so
  no host-environment context leaks. If the caller already provides their own
  `environment_context` override in `:customize` mode, it is preserved verbatim.
- **Post-create options**: a follow-up `session.options.update` RPC sets
  `:skip-custom-instructions true`, `:custom-agents-local-only true`,
  `:coauthor-enabled false`, `:manage-schedule-enabled false`, and forces
  `:installed-plugins []`. On failure, the SDK cleans up the half-configured
  session before propagating the error.

Both modes always emit `:tool-filter-precedence "excluded"` on
`session.create` and `session.resume`, and reject bare `"*"` in
`:available-tools` / `:excluded-tools` at the SDK boundary.

### Tool Sets

Use [`github.copilot-sdk.tool-set`](#tool-sets) to construct `:available-tools` /
`:excluded-tools` lists with built-in helpers. Mirrors the upstream
`BuiltInTools` constants. (upstream PR #1428)

```clojure
(require '[github.copilot-sdk.tool-set :as tool-set])

;; Source-qualified single tool — patterns are "<source>:<name>",
;; source is one of "builtin", "mcp", or "custom":
(tool-set/builtin "ask_user")     ; => "builtin:ask_user"
(tool-set/builtin "*")            ; => "builtin:*"   (all built-ins)
(tool-set/mcp "*")                ; => "mcp:*"      (all MCP tools)
(tool-set/custom "my_tool")       ; => "custom:my_tool"

;; Vector of patterns:
(tool-set/builtins ["task" "skill"])
;; => ["builtin:task" "builtin:skill"]

;; The "Isolated" preset matches BuiltInTools.Isolated upstream —
;; every built-in that is safely session-bounded (no host I/O):
tool-set/isolated
;; => ["builtin:ask_user" "builtin:task_complete" "builtin:exit_plan_mode" ...]
```

The constructors enforce well-formed entries: a bare `"*"` is rejected (the SDK
also rejects it in `:available-tools` / `:excluded-tools` at the session
boundary — apps must explicitly opt into a source so an absent source can
never silently grant access to unexpected tools). The runtime always receives
`:tool-filter-precedence "excluded"` on `session.create` / `session.resume`
so the ordering between allow and deny lists is deterministic.

### Tools

Let the CLI call back into your process when the model needs capabilities you provide:

```clojure
(def lookup-tool
  (copilot/define-tool "lookup_issue"
    {:description "Fetch issue details from our tracker"
     :parameters {:type "object"
                  :properties {:id {:type "string"
                                    :description "Issue identifier"}}
                  :required ["id"]}
     :handler (fn [{:keys [id]} invocation]
                (let [issue (fetch-issue id)]
                  (copilot/result-success issue)))}))

(def session (copilot/create-session client
               {:model "gpt-5.4"
                :tools [lookup-tool]
                :on-permission-request copilot/approve-all}))
```

When Copilot invokes `lookup_issue`, the SDK automatically runs your handler and responds to the CLI.

**Declaration-only tools (manual resolution):**

The `:handler` key is **optional** (upstream PR #1308). When omitted, the SDK does not auto-respond to tool calls — the call surfaces as a `:copilot/external_tool.requested` event with a pending request id, and the application resolves it later via `handle-pending-tool-call!`. Useful for human-in-the-loop UIs or out-of-process tool execution.

```clojure
(def manual-tool
  (copilot/define-tool "manual_lookup"
    {:description "Look up status manually"
     :parameters {:type "object"
                  :properties {:id {:type "string"}}
                  :required ["id"]}}))
;; …later, after a human reviews the request:
(copilot/handle-pending-tool-call! session
                                   {:request-id "tool-req-7"
                                    :result "STATUS_OK"})
```

**Overriding built-in tools:**

Set `:overrides-built-in-tool true` to override a built-in tool (e.g., `grep`, `edit_file`). Without this flag, defining a tool whose name clashes with a built-in tool causes an error.

```clojure
(def custom-grep
  (copilot/define-tool "grep"
    {:description "Custom grep with project-specific filtering"
     :overrides-built-in-tool true
     :parameters {:type "object"
                  :properties {:pattern {:type "string"
                                         :description "Search pattern"}}
                  :required ["pattern"]}
     :handler (fn [{:keys [pattern]} _invocation]
                (copilot/result-success (my-custom-grep pattern)))}))
```

**Deferring tools:**

Set `:defer` to `:auto` or `:never` (upstream PR #1632) to control whether a tool may be *deferred* — loaded lazily via tool search rather than always pre-loaded into the model's context. `:auto` (the default) lets the runtime defer the tool; `:never` forces it to be pre-loaded. Deferring large tool sets keeps the active context smaller.

```clojure
(def always-loaded
  (copilot/define-tool "critical_action"
    {:description "A tool that must always be available"
     :defer :never
     :parameters {:type "object" :properties {}}
     :handler (fn [_ _] (copilot/result-success "done"))}))
```

The keyword is converted to the wire string (`:auto` -> `"auto"`, `:never` -> `"never"`); when `:defer` is omitted the field is not sent and the runtime applies its default.

**Handler return values:**

| Return Type | Description |
|-------------|-------------|
| String | Automatically wrapped as success result |
| Map with `:result-type` | Full control over result metadata |
| core.async channel | Async result (yields string or map) |

**Result helpers:**

```clojure
(copilot/result-success "It worked!")
(copilot/result-failure "It failed" "error details")
(copilot/result-denied "Permission denied")
(copilot/result-rejected "Invalid parameters")
```

**MCP result conversion:**

Convert an MCP `CallToolResult` into the SDK's `ToolResultObject` format with `convert-mcp-call-tool-result`:

```clojure
(require '[github.copilot-sdk.tools :as tools])

(tools/convert-mcp-call-tool-result
  {:content [{:type "text" :text "Hello from MCP"}]
   :is-error false})
;; => {:text-result-for-llm "Hello from MCP", :result-type "success"}

(tools/convert-mcp-call-tool-result
  {:content [{:type "text" :text "Something went wrong"}]
   :is-error true})
;; => {:text-result-for-llm "Something went wrong", :result-type "failure"}
```

The input map uses Clojure-idiomatic keys:

| Key | Type | Description |
|-----|------|-------------|
| `:content` | vector | Content blocks, each with `:type` and type-specific fields |
| `:is-error` | boolean | When true, the result-type is `"failure"` |

Supported content block types:

| Type | Fields | Description |
|------|--------|-------------|
| `"text"` | `:text` | Text content, joined with newlines |
| `"image"` | `:data`, `:mime-type` | Base64-encoded image, added to `:binary-results-for-llm` |
| `"resource"` | `:resource` with `:uri`, `:text`, `:blob`, `:mime-type` | Resource content (text and/or binary) |

### Commands

Register slash commands that users can invoke in the TUI. Define each command as a map with `:name`, `:description`, and `:command-handler`, then pass them via `:commands` in session config.

```clojure
(def my-commands
  [{:name "deploy"
    :description "Deploy the current project"
    :command-handler (fn [{:keys [session-id command-name args]}]
                       (println "Deploying with args:" args))}
   {:name "status"
    :description "Show project status"
    :command-handler (fn [{:keys [session-id command-name args]}]
                       (println "All systems operational"))}])

(def session (copilot/create-session client
               {:model "gpt-5.4"
                :commands my-commands
                :on-permission-request copilot/approve-all}))
```

**Command definition keys:**

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Command name (without leading slash) |
| `:description` | string | no | Description shown in TUI command list |
| `:command-handler` | fn | yes | Handler function |

The handler receives a context map:

| Key | Description |
|-----|-------------|
| `:session-id` | The session ID |
| `:command` | Full command string |
| `:command-name` | Matched command name |
| `:args` | Arguments after the command name |

The handler may return `nil` or a core.async channel (awaited automatically).

### System Message Customization

Control the system prompt:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :system-message
                  {:content "
<workflow_rules>
- Always check for security vulnerabilities
- Suggest performance improvements when applicable
</workflow_rules>
"}}))
```

The SDK auto-injects environment context, tool instructions, and security guardrails. Your `:content` is appended after SDK-managed sections.

For full control (removes all guardrails), use `:mode :replace`:

```clojure
(copilot/create-session client
  {:model "gpt-5.4"
   :on-permission-request copilot/approve-all
   :system-message {:mode :replace
                    :content "You are a helpful assistant."}})
```

#### Customize Mode

The `:customize` mode enables section-level overrides of the system prompt. Eleven sections are configurable:

| Section | Description |
|---------|-------------|
| `:identity` | Agent identity preamble and mode statement |
| `:tone` | Response style, conciseness rules, output formatting |
| `:tool-efficiency` | Tool usage patterns, parallel calling, batching |
| `:environment-context` | CWD, OS, git root, directory listing, available tools |
| `:code-change-rules` | Coding rules, linting/testing, ecosystem tools, style |
| `:guidelines` | Tips, behavioral best practices |
| `:safety` | Environment limitations, prohibited actions, security |
| `:tool-instructions` | Per-tool usage instructions |
| `:custom-instructions` | Repository and organization custom instructions |
| `:runtime-instructions` | Runtime-provided context (system notifications, memories, mode-specific instructions, content-exclusion policy) — added in upstream PR #1377 |
| `:last-instructions` | End-of-prompt instructions |

Each section supports static actions (`:replace`, `:remove`, `:append`, `:prepend`) and transform callbacks (1-arity functions).

```clojure
(require '[github.copilot-sdk :as copilot])

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :system-message
     {:mode :customize
      :sections {:identity {:action :replace
                            :content "You are Acme Assistant."}
                 :tone {:action :append
                        :content "\nAlways respond in bullet points."}
                 :code-change-rules {:action :remove}}
      :content "Additional instructions here."}}))
```

Transform callbacks receive the current section content and return the replacement:

```clojure
(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :system-message
     {:mode :customize
      :sections {:identity {:action (fn [current]
                                     (clojure.string/replace current
                                       "GitHub Copilot" "Acme Assistant"))}}}}))
```

Inspect available sections with the `system-prompt-sections` constant:

```clojure
copilot/system-prompt-sections
;; => {:identity {:description "Agent identity preamble and mode statement"}
;;     :tone {:description "Response style, conciseness rules, ..."}
;;     :runtime-instructions {:description "Runtime instructions injected ..."} ...}
```

Available section keys: `:identity`, `:tone`, `:tool-efficiency`,
`:environment-context`, `:code-change-rules`, `:guidelines`, `:safety`,
`:tool-instructions`, `:custom-instructions`, `:runtime-instructions`
(added in upstream PR #1377), `:last-instructions`.

> **Naming note** — Upstream renamed `SystemPromptSection` →
> `SystemMessageSection` in the TypeScript SDK. The Clojure SDK keeps
> `system-prompt-sections` as the canonical name (for back-compat) and
> exposes `system-message-sections` as an alias.

Unknown section keywords are allowed — they gracefully fall back to appending content to additional instructions.

### Default Agent Tool Exclusions

Hide tools from the built-in/default agent while keeping them available to custom agents with `:default-agent`.

```clojure
(require '[github.copilot-sdk :as copilot])

(def repo-index-tool
  (copilot/define-tool "repo_index_search"
    {:description "Search the private repository index"
     :parameters {:type "object"
                  :properties {:query {:type "string"}}
                  :required ["query"]}
     :handler (fn [{:keys [query]} _]
                (str "index results for " query))}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :tools [repo-index-tool]
     :custom-agents [{:agent-name "repo-auditor"
                      :agent-prompt "Audit repository changes."
                      :agent-tools ["repo_index_search"]}]
     :default-agent {:excluded-tools ["repo_index_search"]}}))
```

The default agent cannot call `repo_index_search`. The `repo-auditor` custom agent can still call it because custom-agent tool assignment is independent of `:default-agent`.

### Config Directory and Skills

`config-dir` overrides where the CLI reads its config and state (e.g., `~/.copilot`).
It does not define custom agents. Custom agents are provided via `:custom-agents`.

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :config-dir "/tmp/copilot-config"
                :skill-directories ["/path/to/skills" "/opt/team-skills"]
                :disabled-skills ["legacy-skill" "experimental-skill"]}))
```

### Large Tool Output Handling (Experimental)

> **Note:** This is a CLI protocol feature not exposed in the official `@github/copilot-sdk`.
> The `outputDir` and `maxSizeBytes` settings may be ignored by some CLI versions due to
> a known issue where session-level config is not applied during `session.send` execution.
> The CLI's default behavior (30KB threshold, system tmpdir) applies regardless.

Configure how large tool outputs are handled before being sent back to the model:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :large-output {:enabled true
                               :max-size-bytes 65536
                               :output-dir "/tmp/copilot-tool-output"}}))
```

When a tool output exceeds the configured size, the CLI writes the full output to a temp file,
and the tool result delivered to the model contains a short message with the file path and preview.
You can see this message in `:tool.execution_complete` events:

```clojure
(let [events (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! events)]
        (when (= :tool.execution_complete (:type event))
          (when-let [content (get-in event [:data :result :content])]
            (println "Tool output message:\n" content)))
      (recur))))
```

Note: large output handling is applied by the CLI for built-in tools (like the shell tool).
For external tools you define in the SDK, consider handling oversized outputs yourself
(e.g., write to a file and return a short preview).

### Infinite Sessions

Infinite sessions enable automatic context compaction, allowing conversations to continue
beyond the model's context window limit. When the context approaches capacity, the CLI
automatically compacts older messages while preserving important context.

```clojure
;; Enable with defaults (enabled by default)
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all}))

;; Explicit configuration
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :infinite-sessions {:enabled true
                                    :background-compaction-threshold 0.80
                                    :buffer-exhaustion-threshold 0.95}}))

;; Disable infinite sessions
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :infinite-sessions {:enabled false}}))
```

**Configuration options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:enabled` | boolean | `true` | Enable infinite sessions |
| `:background-compaction-threshold` | number | `0.80` | Context utilization (0.0-1.0) at which background compaction starts |
| `:buffer-exhaustion-threshold` | number | `0.95` | Context utilization (0.0-1.0) at which session blocks until compaction completes |

**How it works:**

1. When context reaches the background threshold (default 80%), compaction starts asynchronously
2. The session continues processing while compaction runs in the background
3. If context reaches the buffer exhaustion threshold (default 95%), the session blocks until compaction completes
4. Compaction preserves essential context while removing older, less relevant messages

**Compaction events:**

Sessions emit `:session.compaction_start` and `:session.compaction_complete` events during compaction:

```clojure
(let [ch (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :session.compaction_start
        (println "Compaction started...")

        :session.compaction_complete
        (println "Compaction complete")

        nil)
      (recur))))
```

### Permission Handling

The SDK uses a **deny-by-default** permission model. All permission requests
(file writes, shell commands, URL fetches, custom tool execution, etc.) are denied unless your
session config provides an `:on-permission-request` handler. The handler is
optional on `create-session`, `resume-session`, and `join-session` (upstream
PR #1308): when omitted, permission requests are not auto-resolved and
applications must resolve them via `handle-pending-permission-request!`.
`join-session` historically defaulted to `{:kind :no-result}` and continues to
behave that way.

Use `approve-all` to opt into approving everything:

```clojure
(def session (copilot/create-session client
               {:on-permission-request copilot/approve-all}))
```

The `:permission-kind` field in permission requests identifies the type of action requiring approval:

| Permission Kind | Description |
|----------------|-------------|
| `:shell` | Shell command execution |
| `:write` | File system write operation |
| `:mcp` | MCP tool invocation |
| `:read` | File system read operation |
| `:url` | URL fetch / HTTP request |
| `:custom-tool` | SDK-registered custom tool invocation |
| `:memory` | Memory storage operation (subject, fact, citations) |
| `:hook` | Hook-triggered permission check |

Memory permission events include additional data fields (specs `::memory-action`, `::memory-direction`, `::memory-reason`):

| Field | Type | Description |
|-------|------|-------------|
| `:memory-action` | `:store` or `:vote` | The memory operation type |
| `:memory-direction` | `:upvote` or `:downvote` | Vote direction (when action is `:vote`) |
| `:memory-reason` | string | Reason for the memory operation |

For fine-grained control, provide your own handler. When the CLI needs
approval, it sends a JSON-RPC `permission.request` to the SDK. Your
`:on-permission-request` callback must return a map compatible with the
permission result payload; the SDK wraps this into the JSON-RPC response
as `{:result <your-map>}`:

The `permission_bash.clj` example demonstrates both an allowed and a denied
shell command and prints the full permission request payload so you can inspect
fields like `:full-command-text`, `:commands`, and `:possible-paths`.

```clojure
;; Approve this request once
{:kind :approve-once}

;; Approve and remember for the session
{:kind :approve-for-session
 :approval {:kind :commands
            :command-identifiers ["echo"]}}

;; Approve and persist for the project location
{:kind :approve-for-location
 :approval {:kind :write}
 :location-key "/path/to/project"}

;; Reject with optional user-facing detail
{:kind :reject
 :feedback "Not allowed"}

;; No user confirmation is available
{:kind :user-not-available}

;; Extension declines to answer (another handler may respond)
{:kind :no-result}
```

Legacy Clojure permission result kinds such as `:approved` and
`:denied-by-rules` remain accepted and are normalized before the SDK sends the
decision to the CLI.

#### `resolvedByHook` — Hook-Resolved Permissions

When the runtime resolves a permission request via a `permissionRequest` hook, the
`permission.requested` event includes `:resolved-by-hook true`. The SDK automatically
skips the client's `:on-permission-request` handler and does not send the
`handlePendingPermissionRequest` RPC — the event is still published to event subscribers
for observability.

#### `approve-all`

```clojure
(copilot/approve-all request ctx)
```

A convenience permission handler that approves all permission requests by
returning `{:kind :approve-once}`. Equivalent to the upstream Node.js SDK
`approveAll` export.

Pass as the `:on-permission-request` value in session config:

```clojure
(copilot/create-session client {:on-permission-request copilot/approve-all})
```

#### `default-join-session-permission-handler`

```clojure
(copilot/default-join-session-permission-handler request ctx)
```

Returns `{:kind :no-result}` — the CLI handles permission decisions itself. When used with `resume-session`, the SDK sends `requestPermission: false` on the wire, telling the CLI that this client does not want to handle permission requests.

Use this when reconnecting to a session where the original client already established permission handling:

```clojure
(copilot/resume-session client "session-123"
  {:on-permission-request copilot/default-join-session-permission-handler})
```

Equivalent to the upstream Node.js SDK `defaultJoinSessionPermissionHandler` export.

### User Input Handling

When the agent needs input from the user (via `ask_user` tool), the `:on-user-input-request`
handler is called. Return a response map with the user's input:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :on-user-input-request
                (fn [request invocation]
                  ;; request contains {:question "..." :choices [...] :allow-freeform true/false}
                  (println "Agent asks:" (:question request))
                  (when-let [choices (:choices request)]
                    (println "Choices:" choices))
                  ;; Return user's response
                  ;; :answer is required, :was-freeform defaults to true
                  {:answer (read-line)
                   :was-freeform true})}))
```

The request map includes:
- `:question` - The question being asked
- `:choices` - Optional list of choices for multiple choice questions
- `:allow-freeform` - Whether freeform text input is allowed

The response map should include:
- `:answer` - The user's answer (string, required). `:response` is also accepted for convenience.
- `:was-freeform` - Whether the answer was freeform (boolean, defaults to true)

### Elicitation Provider

Provide a handler for elicitation requests from the agent. This enables the SDK client to act as a UI provider for form-based dialogs.

```clojure
(require '[github.copilot-sdk :as copilot])

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :on-elicitation-request
     (fn [{:keys [session-id message requested-schema mode]}]
       (println "Elicitation for session" session-id ":" message)
       {:action "accept"
        :content {:name "user-input"}})}))
```

The handler receives a single `ElicitationContext` map:

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Session that triggered the request |
| `:message` | string | What information is needed from the user |
| `:requested-schema` | map | JSON Schema describing form fields (optional) |
| `:mode` | string | `"form"` for structured input, `"url"` for browser redirect (optional) |
| `:elicitation-source` | string | Source that initiated the request, e.g. MCP server name (optional) |
| `:url` | string | URL to open in browser, url mode only (optional) |

Return an `ElicitationResult` map:

| Key | Type | Description |
|-----|------|-------------|
| `:action` | string | `"accept"`, `"decline"`, or `"cancel"` |
| `:content` | map | Field values when action is `"accept"` |

If the handler throws, the SDK sends `{:action "cancel"}` to prevent the request from hanging.

When `:on-elicitation-request` is set, the session advertises `requestElicitation=true` in the create/resume RPC. Capabilities are updated dynamically via `capabilities.changed` events.

### Session Filesystem

Virtualize per-session storage with custom filesystem handlers. The runtime routes all session-scoped file I/O (event logs, large outputs, checkpoints) through the provided callbacks.

Configure the client with `:session-fs`:

```clojure
(require '[github.copilot-sdk :as copilot])

(def client
  (copilot/client {:session-fs {:initial-cwd "/home/user/project"
                                :session-state-path "/sessions"
                                :conventions "posix"}}))
```

Provide a provider factory per session:

```clojure
(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :create-session-fs-handler
     (fn [_session]
       (let [store (atom {})]
         {:read-file (fn [path]
                       (or (get @store path)
                           (throw (ex-info "missing file" {:code "ENOENT"}))))
          :write-file (fn [path content _mode]
                        (swap! store assoc path content))
          :append-file (fn [path content _mode]
                         (swap! store update path str content))
          :exists (fn [path]
                    (contains? @store path))
          :stat (fn [path]
                  {:is-file true
                   :is-directory false
                   :size (count (get @store path ""))
                   :mtime "2026-01-01T00:00:00Z"
                   :birthtime "2026-01-01T00:00:00Z"})
          :mkdir (fn [_path _recursive _mode] nil)
          :readdir (fn [_path] [])
          :readdir-with-types (fn [_path] [])
          :rm (fn [path _recursive _force]
                (swap! store dissoc path))
          :rename (fn [src dest]
                    (swap! store
                      (fn [s]
                        (-> s
                            (assoc dest (get s src ""))
                            (dissoc src)))))}))}))
```

Provider functions use direct arguments and throw on failure. Errors with `{:code "ENOENT"}` become structured `SessionFsError` maps with code `"ENOENT"`; all other exceptions become `"UNKNOWN"`. `create-session` and `resume-session` automatically adapt provider-style factory returns to the low-level RPC handler contract.

Use `create-session-fs-adapter` when you need the low-level handler map explicitly:

```clojure
(require '[clojure.java.io :as io])

(def provider
  {:read-file slurp
   :write-file (fn [path content _mode] (spit path content))
   :append-file (fn [path content _mode] (spit path content :append true))
   :exists (fn [path] (.exists (io/file path)))
   :stat (fn [path] {:is-file (.isFile (io/file path))
                     :is-directory (.isDirectory (io/file path))
                     :size (.length (io/file path))})
   :mkdir (fn [path _recursive _mode] (.mkdirs (io/file path)))
   :readdir (fn [path] (vec (.list (io/file path))))
   :readdir-with-types (fn [_path] [])
   :rm (fn [path _recursive _force] (clojure.java.io/delete-file path true))
   :rename (fn [src dest] (.renameTo (io/file src) (io/file dest)))})

(def handler
  (copilot/create-session-fs-adapter provider))
```

The low-level handler map requires the 10 core FS operations below. The two
`:sqlite-*` keys are optional and only required when the client advertises
`:capabilities {:sqlite true}` on its `:session-fs` config (see
[SQLite support](#sqlite-support-optional)).

| Key | Params | Returns |
|-----|--------|---------|
| `:read-file` | `{:session-id :path}` | `{:content "..."}` |
| `:write-file` | `{:session-id :path :content :mode}` | nil |
| `:append-file` | `{:session-id :path :content :mode}` | nil |
| `:exists` | `{:session-id :path}` | `{:exists true/false}` |
| `:stat` | `{:session-id :path}` | `{:is-file :is-directory :size :mtime :birthtime}` |
| `:mkdir` | `{:session-id :path :recursive :mode}` | nil |
| `:readdir` | `{:session-id :path}` | `{:entries [...]}` |
| `:readdir-with-types` | `{:session-id :path}` | `{:entries [...]}` |
| `:rm` | `{:session-id :path :recursive :force}` | nil |
| `:rename` | `{:session-id :src :dest}` | nil |
| `:sqlite-query` _(optional)_ | `{:session-id :query-type :query :params}` | `{:rows [...] :columns [...] :rows-affected n}` |
| `:sqlite-exists` _(optional)_ | `{:session-id}` | `{:exists true/false}` |

Handler functions may return values directly or via core.async channels.

#### SQLite support (optional)

To handle `sessionFs.sqliteQuery` and `sessionFs.sqliteExists` (upstream PR #1299),
add a nested `:sqlite` map to the provider and advertise the capability on the
client config:

```clojure
(def client
  (copilot/client {:session-fs {:initial-cwd "/home/user/project"
                                :session-state-path "/sessions"
                                :conventions "posix"
                                :capabilities {:sqlite true}}}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :create-session-fs-handler
     (fn [_session]
       {;; ... all 10 fs operations above ...
        :sqlite {:query (fn [query-type sql params]
                          ;; query-type is one of :exec, :query, :run
                          ;; params is the raw bind-parameter map (keys preserved verbatim, e.g. :$userId)
                          {:rows [{:n 1}] :columns ["n"] :rows-affected 0})
                 :exists (fn [] true)}})}))
```

Notes:

- `:capabilities {:sqlite true}` is required when sqlite is advertised; declaring
  it without supplying `:sqlite` in the provider throws at session creation.
- SQL bind-parameter map keys (e.g. `$userId`) bypass kebab-case conversion and
  arrive at the handler verbatim.
- Result row column-name keys (e.g. `:user_id`, `:created_at`) round-trip
  verbatim on the outgoing wire path — they are not converted to camelCase,
  matching upstream Node.js semantics where provider rows are forwarded
  untouched.
- SQLite handler exceptions propagate as JSON-RPC errors (not wrapped as
  `SessionFsError`).

### Session Hooks

Lifecycle hooks allow custom logic at various points during the session:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :hooks
                {:on-pre-tool-use
                 (fn [input invocation]
                   ;; Called before each tool execution
                   ;; input contains {:tool-name "..." :arguments {...}}
                   (println "About to use tool:" (:tool-name input))
                   ;; Return nil to proceed, or a modified input map
                   nil)

                 :on-post-tool-use
                 (fn [input invocation]
                   ;; Called after each *successful* tool execution
                   ;; input contains {:tool-name "..." :tool-args {...} :tool-result {...}}
                   ;; For failed tool calls, register :on-post-tool-use-failure below.
                   (println "Tool completed:" (:tool-name input))
                   nil)

                 :on-post-tool-use-failure
                 (fn [input invocation]
                   ;; Called after a tool execution whose result was `"failure"`
                   ;; (upstream PR #1421). :on-post-tool-use only fires for
                   ;; successful results, so register this handler to observe
                   ;; failed tool outcomes. Note: `"rejected"`, `"denied"`, and
                   ;; `"timeout"` results do NOT currently trigger this hook —
                   ;; only `"failure"` does.
                   ;; input contains {:tool-name "..." :tool-args {...}
                   ;;                 :error "failure message string"
                   ;;                 :session-id "..." :timestamp 12345}
                   ;; Optional return: {:additional-context "..."} is appended as
                   ;; hidden guidance to the model alongside the failed result.
                   ;; Other fields (e.g. :modified-result, :suppress-output) are
                   ;; not honored for failure hooks.
                   (println "Tool failed:" (:tool-name input) (:error input))
                   {:additional-context "Tip: try `ls` first to see available files."})

                 :on-pre-mcp-tool-call
                 (fn [input invocation]
                   ;; Called before each MCP tool call is dispatched (upstream PR #1366).
                   ;; input contains
                   ;;   {:server-name "..." :tool-name "..." :arguments {...}
                   ;;    :tool-call-id "..." :_meta {...} :session-id "..."
                   ;;    :timestamp 12345}
                   ;; :arguments and :_meta are opaque MCP payloads and are
                   ;; passed through verbatim (NOT kebab-cased recursively).
                   ;; Return nil/{} to preserve the existing _meta on the
                   ;; outgoing MCP request, {:meta-to-use {...}} to replace
                   ;; it, or {:meta-to-use nil} to remove it.
                   (println "Pre-MCP call:" (:server-name input) (:tool-name input))
                   {:meta-to-use {:traceId "my-trace-id"}})

                 :on-user-prompt-submitted
                 (fn [input invocation]
                   ;; Called when user sends a prompt
                   (println "User prompt:" (:prompt input))
                   nil)

                 :on-session-start
                 (fn [input invocation]
                   (println "Session started")
                   nil)

                 :on-session-end
                 (fn [input invocation]
                   (println "Session ended")
                   nil)

                 :on-error-occurred
                 (fn [input invocation]
                   (println "Error:" (:error input))
                   nil)}}))
```

All hooks receive an `input` map (contents vary by hook type) and an `invocation` map
containing `{:session-id ...}`. Hooks may return `nil` to proceed normally, or in some
cases return a modified value.

### Reasoning Effort

For models that support reasoning (like o1), you can control the reasoning effort level:

```clojure
;; Check model capabilities
(let [models (copilot/list-models client)]
  (doseq [m models
          :when (:supports-reasoning-effort m)]
    (println (:name m) "supports reasoning:"
             (:supported-reasoning-efforts m)
             "default:" (:default-reasoning-effort m))))

;; Create session with reasoning effort
(def session (copilot/create-session client
               {:model "o1"
                :reasoning-effort "high"
                :on-permission-request copilot/approve-all})) ; "low", "medium", "high", or "xhigh"
```

### Multiple Sessions

```clojure
(def session1 (copilot/create-session client {:model "gpt-5.4"
                                              :on-permission-request copilot/approve-all}))
(def session2 (copilot/create-session client {:model "claude-sonnet-4.5"
                                              :on-permission-request copilot/approve-all}))

;; Both sessions are independent
(copilot/send-and-wait! session1 {:prompt "Hello from session 1"})
(copilot/send-and-wait! session2 {:prompt "Hello from session 2"})
```

### File Attachments

```clojure
;; File attachment
(copilot/send! session
  {:prompt "Analyze this file"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :display-name "My File"}]})

;; File attachment with line range (restrict to lines 10-25)
(copilot/send! session
  {:prompt "Explain this section"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :line-range {:start 10 :end 25}}]})

;; Selection attachment (code range)
(copilot/send! session
  {:prompt "What does this function do?"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-function"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-function [...] ...)"}]})
```

### Blob Attachments

Send inline base64-encoded data (e.g. images) without writing to disk:

```clojure
;; Blob attachment (inline base64 data)
(copilot/send! session
  {:prompt "Describe this image"
   :attachments [{:type :blob
                  :data "iVBORw0KGgoAAAANSUhEUg..."
                  :mime-type "image/png"
                  :display-name "screenshot.png"}]})
```

### Connecting to External Server

```clojure
;; Connect to an existing CLI server (no process spawned)
(def client (copilot/client {:cli-url "localhost:8080"}))
(copilot/start! client)
```

---

## Error Handling

```clojure
(try
  (let [session (copilot/create-session client
                                       {:on-permission-request copilot/approve-all})]
    (copilot/send! session {:prompt "Hello"}))
  (catch Exception e
    (println "Error:" (ex-message e))))
```
