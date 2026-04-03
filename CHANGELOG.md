# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added (v0.2.1 sync)
- **`resolvedByHook` guard on `permission.requested`** — when the runtime resolves a permission request via a `permissionRequest` hook, the broadcast event includes `resolvedByHook: true`. The SDK now skips the client's `:on-permission-request` handler and does not send the `handlePendingPermissionRequest` RPC, preventing duplicate responses. Event subscribers still observe the event (upstream PR #999, runtime 1.0.17).
- **New permission result kinds** — `:denied-by-content-exclusion-policy` and `:denied-by-permission-request-hook` added to `::permission-result-kind` spec (upstream PR #999).
- **MCP fields on `tool.execution_start` events** — optional `:mcp-server-name` and `:mcp-tool-name` fields added to `::tool.execution_start-data` spec indicating the MCP server and original tool name for MCP-originated tool calls (upstream runtime 1.0.17).
- **`::resolved-by-hook` spec** — boolean spec for the `resolvedByHook` field on `permission.requested` event data.
- **Commands example** — new `examples/commands.clj` demonstrating slash command registration and handling.
- Integration tests for `resolvedByHook` guard (both true and false cases), new permission result kind specs, and MCP tool event fields.

### Changed
- **Public preview branding** — README updated from "technical preview" to "public preview" with link to the [announcement](https://github.blog/changelog/2026-04-02-copilot-sdk-in-public-preview/).

### Changed (v0.2.1 sync)
- **BREAKING**: Elicitation handler signature changed from 2-arg `(fn [request ctx])` to single-arg `(fn [context])`. The `ElicitationContext` map now includes `:session-id` alongside request fields (`:message`, `:requested-schema`, `:mode`, `:elicitation-source`, `:url`). Matches upstream cross-SDK consistency change (upstream PR #960). `::elicitation-request` spec renamed to `::elicitation-context`.

### Added (v0.2.1 sync)
- **`remote-steerable?` field on `session.start` and `session.resume` events** — event data now includes optional `:remote-steerable?` boolean field indicating whether the session supports remote steering via Mission Control. Replaces previous `:steerable?` (upstream PRs #927, #908).
- **`get-session-metadata`** — new function on client for efficient O(1) session lookup by ID. Returns session metadata map if found, or `nil` if not found. Sends `session.getMetadata` JSON-RPC call. Shared `wire->session-metadata` helper extracted from `list-sessions` to eliminate duplication (upstream PR #899).
- **Elicitation provider support** — new `:on-elicitation-request` handler on `SessionConfig` and `ResumeSessionConfig`. When provided, sends `requestElicitation: true` in the session create/resume RPC. The runtime routes `elicitation.requested` broadcast events to the handler, and results are sent back via `session.ui.handlePendingElicitation` RPC. Handler errors automatically send a cancel response. New `::elicitation-request` and `::on-elicitation-request` specs (upstream PR #908).
- **`capabilities.changed` event handling** — session capabilities are dynamically updated when `capabilities.changed` broadcast events are received, e.g. when another client joins with elicitation support (upstream PR #908).
- **New event types** — `sampling.requested`, `sampling.completed`, `session.remote_steerable_changed`, `capabilities.changed` added to event type enum and event sets (upstream PRs #908, #916).
- **Subagent event data fields** — `subagent.started`, `subagent.completed`, `subagent.failed` events now include optional `:model`, `:total-tool-calls`, `:total-tokens`, `:duration-ms` fields. New `::subagent.started-data`, `::subagent.completed-data`, `::subagent.failed-data` specs (upstream PR #916).
- **`skill.invoked` event `:description` field** — optional `:description` from SKILL.md frontmatter (upstream PR #916).
- **`session.custom_agents_updated` payload spec** — full `::session.custom_agents_updated-data` spec with `:agents` (array of agent metadata), `:warnings`, `:errors`. New `::custom-agent-info` spec (upstream PR #916).
- **SessionFs virtual filesystem** — new `:session-fs` client option with `:initial-cwd`, `:session-state-path`, `:conventions`. Client calls `sessionFs.setProvider` RPC on connect. New `:create-session-fs-handler` on session config provides a per-session FS handler factory. The SDK dispatches incoming `sessionFs.*` RPC requests (10 operations: `readFile`, `writeFile`, `appendFile`, `exists`, `stat`, `mkdir`, `readdir`, `readdirWithTypes`, `rm`, `rename`) to the session's handler. Enables custom session storage backends (upstream PR #917).
- **`aborted?` on `session.task_complete`** — optional boolean indicating the preceding agentic loop was cancelled via abort signal. New `::aborted?` spec (upstream PR #917).
- **`timeout` tool result type** — `::result-type` now accepts `:timeout` / `"timeout"` for tool calls that timed out (upstream PR #970).
- Integration tests for elicitation provider routing, handler error→cancel fallback, capabilities.changed updates, and requestElicitation wire flag.

### Changed (v0.2.1 sync)
- **BREAKING**: `::steerable?` renamed to `::remote-steerable?` on `session.start` and `session.resume` event data, matching upstream wire field rename from `steerable` to `remoteSteerable` (upstream PR #908).
- **`session.idle` is now ephemeral** — the runtime no longer persists `session.idle` events in session history. `get-messages` will no longer return `session.idle` events. Live event listeners (used by `send-and-wait!` and `send!`) are unaffected and still receive it (upstream PR #927).

## [0.2.1.1-SNAPSHOT] - 2026-03-26
### Added (v0.2.1 sync)
- **Commands support** — register slash commands per-session via `:commands` option in session config. Each command definition has `:name`, optional `:description`, and a `:command-handler` function. Commands are sent on the wire (name + description) and executed via `command.execute` broadcast events with `session.commands.handlePendingCommand` RPC callback (upstream PR #906).
- **UI Elicitation convenience API** — new public functions `confirm!`, `select!`, `input!` wrap the existing `ui-elicitation!` with typed schemas. `capabilities` accessor returns host capabilities from session create/resume response. `elicitation-supported?` predicate checks if the host supports elicitation dialogs. All convenience methods throw with a clear error when elicitation is unsupported (upstream PR #906).
- **`COPILOT_CLI_PATH` env var fallback** — client constructor now checks `COPILOT_CLI_PATH` environment variable before defaulting to `"copilot"` when no explicit `:cli-path` or `:cli-url` is provided (upstream PR #906).
- **New event type** `session.custom_agents_updated` added to event type enum.
- **`:host` field on `session.handoff` events** — event data now includes optional `:host` field with the GitHub host URL. New `::session.handoff-data` spec documents the shape (upstream PR #900).
- New specs: `::command-definition`, `::commands`, `::session-capabilities`, `::elicitation-params`, `::elicitation-result`, `::input-options`.
- Function specs and instrumentation for `capabilities`, `elicitation-supported?`, `confirm!`, `select!`, `input!`.
- Integration tests for command wire format, command.execute routing, unknown command errors, handler errors, capabilities storage, and elicitation guards.

### Changed (v0.2.1 sync)
- `ui-elicitation!` no longer marked `^:experimental` — now asserts elicitation support before calling. Updated fdef to use `::elicitation-params` spec.
- Mock server `handle-request` now supports `session.commands.handlePendingCommand` RPC and allows request hooks to merge additional data into responses.

## [0.2.0.0] - 2026-03-23
### Added (v0.2.0 sync)
- **System message customize mode** — new `:customize` mode for `:system-message` enables section-level overrides of the Copilot system prompt. Ten configurable sections: `:identity`, `:tone`, `:tool-efficiency`, `:environment-context`, `:code-change-rules`, `:guidelines`, `:safety`, `:tool-instructions`, `:custom-instructions`, `:last-instructions`. Each section supports static actions (`:replace`, `:remove`, `:append`, `:prepend`) and transform callbacks (1-arity functions receiving current content, returning modified text). New `system-prompt-sections` constant exported from main namespace (upstream PR #816).
- **New experimental RPC methods** — thin wrapper functions in `session` namespace for emerging CLI APIs (upstream PR #900):
  - Skills: `skills-list`, `skills-enable!`, `skills-disable!`, `skills-reload!`
  - MCP servers: `mcp-list`, `mcp-enable!`, `mcp-disable!`, `mcp-reload!`
  - Extensions: `extensions-list`, `extensions-enable!`, `extensions-disable!`, `extensions-reload!`
  - Plugins: `plugins-list`
  - Compaction: `compaction-compact!`
  - Shell: `shell-exec!`, `shell-kill!`
  - UI: `ui-elicitation!`
- **15 new event types** added to the event type enum: `command.completed`, `command.execute`, `command.queued`, `commands.changed`, `exit_plan_mode.requested`, `exit_plan_mode.completed`, `external_tool.completed`, `mcp.oauth_required`, `mcp.oauth_completed`, `session.tools_updated`, `session.background_tasks_changed`, `session.skills_loaded`, `session.mcp_servers_loaded`, `session.mcp_server_status_changed`, `session.extensions_loaded`.
- Experimental API annotations (`^:experimental` metadata) on all new RPC method wrappers.
- Function specs (`s/fdef`) and instrumentation for all new RPC methods.

### Changed (v0.2.0 sync)
- **Version bump** to `0.2.0.0-SNAPSHOT` tracking upstream copilot-sdk v0.2.0.
- Updated `interaction-events` set to include new event types (commands, MCP OAuth, exit plan mode).

## [0.1.33.0-SNAPSHOT] - 2026-03-19
### Added
- `:no-result` permission outcome — extensions can attach to sessions without actively answering permission requests by returning `{:kind :no-result}` from their `:on-permission-request` handler. On v3 protocol, the `handlePendingPermissionRequest` RPC is skipped; on v2, an error is propagated to the CLI (upstream PR #802).
- `:blob` attachment type for outbound messages — send inline base64-encoded data (e.g. images) via `{:type :blob :data "..." :mime-type "image/png"}` in `:attachments`. Previously blob attachments were only supported in inbound events (upstream PR #731).

### Added (v0.1.33 sync)
- `:skip-permission?` option on tool definitions — when `true`, the tool executes without triggering a permission prompt. Sent as `skipPermission: true` in the wire protocol (upstream PR #808).
- OpenTelemetry support: new `:telemetry` client option (map with `:otlp-endpoint`, `:file-path`, `:exporter-type`, `:source-name`, `:capture-content?`) configures OTel environment variables on the spawned CLI process. New `:on-get-trace-context` client option (0-arity fn returning `{:traceparent ... :tracestate ...}`) enables W3C Trace Context propagation into `session.create`, `session.resume`, and `session.send` RPCs (upstream PR #785).
- Tool invocations now receive `:traceparent` and `:tracestate` fields in the invocation context map when the CLI provides them (upstream PR #785).
- Optional `:reasoning-effort` parameter in `switch-model!` and `set-model!` — pass `{:reasoning-effort "high"}` as a third argument to set reasoning effort when switching models (upstream PR #712).
- New event data fields from upstream codegen update (upstream PR #796):
  - `session.start` event: `:reasoning-effort`, `:already-in-use?`, `:host-type`, `:head-commit`, `:base-commit` optional fields
  - `session.resume` event: new `::session.resume-data` spec with `:event-count`, `:selected-model`, `:reasoning-effort`, `:already-in-use?`, `:host-type`, `:head-commit`, `:base-commit`
  - `session.model_change` event: new `::session.model_change-data` spec with `:new-model`, `:previous-model`, `:reasoning-effort`, `:previous-reasoning-effort`
  - `user.message` event: new `:blob` attachment type with `:data` (base64), `:mime-type`, optional `:display-name`

### Changed (v0.1.33 sync)
- `join-session` now makes `:on-permission-request` **optional**. When omitted, a default handler returns `{:kind :no-result}`, leaving any pending permission request unanswered. This matches the upstream `JoinSessionConfig` where `onPermissionRequest` is optional (upstream PR #802).
- `:auto-restart?` client option is **deprecated** and has no effect. The auto-restart/reconnect behavior has been removed across all official SDKs. The option is retained for backward compatibility but will be removed in a future release (upstream PR #803).

### Added (documentation)
- "Permission Handling" section in README.md — covers deny-by-default model, `approve-all`, custom handlers, and links to API reference (upstream PR #879).

## [0.1.32.0] - 2026-03-12
### Added (upstream sync)
- Session pre-registration: sessions are now created and registered in client state **before** the RPC call, preventing early events (e.g. `session.start`) from being dropped. Session IDs are generated client-side via `java.util.UUID/randomUUID` when not explicitly provided. On RPC failure, sessions are automatically cleaned up (upstream PR #664).
- `:on-event` optional handler in `create-session` and `resume-session` configs — a 1-arity function receiving event maps, registered before the RPC call so no events are missed. Equivalent to calling `subscribe-events` immediately after creation, but executes earlier in the lifecycle (upstream PR #664).
- `join-session` function — convenience for extensions running as child processes of the Copilot CLI. Reads `SESSION_ID` from environment, creates a child-process client, and resumes the session with `:disable-resume? true`. Returns `{:client ... :session ...}` (upstream PR #737).
- `:copilot/system.notification` event type — structured notification events with `:kind` discriminator (`agent_completed`, `shell_completed`, `shell_detached_completed`) (upstream PR #737).

### Changed
- `CopilotSession` record no longer includes `workspace-path` as a field. Use `(workspace-path session)` accessor which reads from mutable session state. This enables the pre-registration flow where workspace-path is set after the RPC response.

## [0.1.32.0] - 2026-03-10
### Added (v0.1.32 sync)
- `:agent` optional string parameter in `create-session` and `resume-session` configs — pre-selects a custom agent by name when the session starts. Must match a name in `:custom-agents`. Equivalent to calling `agent.select` after creation (upstream PR #722).
- `:on-list-models` optional handler in client options — zero-arg function returning model info maps. Bypasses the `models.list` RPC call and does not require `start!`. Results use the same promise-based cache (upstream PR #730).
- `log!` session method — logs a message to the session timeline via `"session.log"` RPC. Accepts optional `:level` (`"info"`, `"warning"`, `"error"`) and `:ephemeral?` (transient, not persisted) options. Returns the event ID string (upstream PR #737).
- `:is-child-process?` client option — when `true`, the SDK connects via its own stdio to a parent Copilot CLI process instead of spawning a new one. Mutually exclusive with `:cli-url`; requires `:use-stdio?` to be `true` (or unset) (upstream PR #737).

## [0.1.30.1] - 2026-03-07
### Added
- `disconnect!` function as the preferred API for closing sessions, matching upstream SDK's `disconnect()` (upstream PR #599). `destroy!` is deprecated but still works as an alias.
- 6 new broadcast event types from CLI protocol 0.0.421 (upstream PR #684): `:copilot/permission.requested`, `:copilot/permission.completed`, `:copilot/user_input.requested`, `:copilot/user_input.completed`, `:copilot/elicitation.requested`, `:copilot/elicitation.completed`
- New `interaction-events` category set for permission, user input, and elicitation flow events
- `:memory` permission kind added to `::permission-kind` spec (upstream PR #684)
- Protocol v3 support with backwards compatibility (supports v2 and v3). The SDK negotiates the protocol version with the CLI server at startup using a supported range `[2, 3]`. Version 3 replaces `tool.call` and `permission.request` RPC callbacks with broadcast events (`external_tool.requested`, `permission.requested`) and new RPC response methods (`session.tools.handlePendingToolCall`, `session.permissions.handlePendingPermissionRequest`).
- Custom agents & sub-agent orchestration guide (`doc/guides/custom-agents.md`)

### Changed
- `stop!` now uses `disconnect!` internally instead of `destroy!`
- `delete-session!` docstring clarified to contrast with `disconnect!`
- Version negotiation now validates the CLI-reported protocol version is within the supported range `[2, 3]` instead of requiring exact match on version 2

### Deprecated
- `destroy!` — use `disconnect!` instead. `destroy!` delegates to `disconnect!` and will be removed in a future release.

## [0.1.30.0] - 2026-03-04
### Added (v0.1.30 sync)

- Support overriding built-in tools via `:overrides-built-in-tool` option in `define-tool` (upstream PR #636). Tools with this flag set to `true` can override built-in tools like `grep` or `edit_file`. Without the flag, name clashes cause an error.
- `set-model!` function as alias for `switch-model!`, matching the upstream SDK's `setModel()` API (upstream PR #621).

### Changed (v0.1.30 sync)

- Updated `switch-model!` and `get-current-model` docstrings — removed stale "not yet implemented as of CLI 0.0.412" notes. These RPC methods are now supported.

## [0.1.29.0] - 2026-03-03
### Added (upstream PR #605 sync)
- `:copilot/subagent.deselected` event type added to `::event-type` spec, `event-types` var, and API reference table (upstream PR #605 / CLI 0.0.420).
- `:github-reference` attachment type: represents a GitHub issue, PR, or discussion attached to a user message. Added to `::attachment-type` spec and `::attachment` spec. Data fields: `number`, `title`, `reference-type` (`"issue"/"pr"/"discussion"`), `state`, `url` (upstream PR #605).
- `::assistant.turn_start-data` spec with `turn-id` (required) and `interaction-id` (optional).
- `:interaction-id` optional field added to `::user.message-data`, `::assistant.turn_start-data`, `::assistant.message_delta-data`, and `::tool.execution_complete-data` specs (upstream PR #605).
- `:model` optional field added to `::tool.execution_complete-data` spec — model used for the tool execution (upstream PR #605).
- `:plugin-name` and `:plugin-version` optional fields added to `::skill.invoked-data` spec (upstream PR #605).

### Added (documentation)
- Azure Managed Identity BYOK guide (`doc/auth/azure-managed-identity.md`): shows how to use `DefaultAzureCredential` with short-lived bearer tokens for Azure AI Foundry, with Clojure examples for basic usage and token refresh (upstream PR #498).
- Updated BYOK limitations to link to the Managed Identity workaround instead of listing it as fully unsupported.
- Added Azure Managed Identity guide to `doc/auth/index.md` and `doc/index.md`.

### Added (upstream PR #512 sync)
- `examples/file_attachments.clj` — Demonstrates sending file attachments with prompts using `:attachments` in message options.
- `examples/session_resume.clj` — Demonstrates session resume: create session, send secret word, resume by ID, verify context preserved.
- `examples/infinite_sessions.clj` — Demonstrates infinite sessions with context compaction thresholds for long conversations.
- `examples/lifecycle_hooks.clj` — Demonstrates all 6 lifecycle hooks: session start/end, pre/post tool use, user prompt submitted, error occurred.
- `examples/reasoning_effort.clj` — Demonstrates the `:reasoning-effort` session config option.

## [0.1.28.0] - 2026-02-27
### Changed (upstream PR #554 sync)
- **BREAKING**: `:on-permission-request` is now **required** when calling `create-session`, `resume-session`, `<create-session`, and `<resume-session`. Calls without a handler throw `ExceptionInfo` with a descriptive message. This matches upstream Node.js SDK where `onPermissionRequest` is required in `SessionConfig` and `ResumeSessionConfig` (upstream PR #554).
- `create-session` and `<create-session` no longer accept a 0-arity (no config) form — a config map with `:on-permission-request` must always be provided.
- `resume-session` and `<resume-session` no longer accept a 2-arity (no config) form — a config map with `:on-permission-request` must always be provided.
- All examples, tests, and documentation updated to always pass `:on-permission-request`.

### Added (upstream PR #555 sync)
- `:custom-tool` permission kind — `::permission-kind` spec now includes `:custom-tool`, matching the upstream `PermissionRequest.kind` union type. Permission handlers will receive `{:permission-kind :custom-tool ...}` for SDK-registered custom tool invocations (upstream PR #555).

### Added (upstream PR #544 sync)
- `:copilot/session.task_complete` event type added to `::event-type` spec and `event-types` var. Previously it was in the spec but missing from the public `event-types` set (upstream PR #544).
- `:copilot/assistant.streaming_delta` new event type: emitted when the total response size changes during streaming. Data: `{:total-response-size-bytes N}`. Added to `::event-type` spec, `event-types` var, and `assistant-events` var (upstream PR #544).
- `:copilot/session.mode_changed` event type: emitted when the session agent mode changes. Data: `{:previous-mode "...", :new-mode "..."}`. Added to `::event-type` spec, `event-types` var, and `session-events` var.
- `:copilot/session.plan_changed` event type: emitted when the session plan changes. Data: `{:operation "create"|"update"|"delete"}`. Added to `::event-type` spec, `event-types` var, and `session-events` var.
- `:copilot/session.workspace_file_changed` event type: emitted when a workspace file is created or updated. Data: `{:path "...", :operation "create"|"update"}`. Added to `::event-type` spec, `event-types` var, and `session-events` var.
- Data specs for new events: `::session.mode_changed-data`, `::session.plan_changed-data`, `::session.workspace_file_changed-data`, `::session.task_complete-data`, `::assistant.streaming_delta-data`.

### Changed (upstream PR #544 sync)
- `::assistant.message_delta-data` spec: removed `::total-response-size-bytes` from optional keys. The response size is now delivered via the separate `assistant.streaming_delta` event (upstream PR #544).

### Added (documentation)
- Microsoft Foundry Local BYOK provider guide in `doc/auth/byok.md`: quick start example, installation instructions, and connection troubleshooting (upstream PR #461).
- `doc/reference/API.md`: added `:copilot/session.task_complete` to the Event Reference table (from upstream PR #544 sync).
- `doc/reference/API.md`: added permission kind reference table (`:shell`, `:write`, `:mcp`, `:read`, `:url`, `:custom-tool`) in the Permission Handling section.

### Added (upstream PR #329 sync)
- Windows console window hiding: CLI process is spawned with explicit PIPE redirects ensuring the JVM sets `CREATE_NO_WINDOW` on Windows — no console window appears in GUI applications. Equivalent to upstream `windowsHide: true` (upstream PR #329).

### Changed
- Recommended default model for non-streaming examples is `claude-haiku-4.5` instead of `gpt-5.2` for faster response times

## [0.1.26.0-SNAPSHOT] - 2026-02-20
### Added (upstream PR #510 sync)
- `:client-name` option for `create-session` and `resume-session` — identifies the application using the SDK, included in the User-Agent header for API requests. Forwarded as `clientName` on the wire (upstream PR #510).

### Changed (upstream PR #509 sync)
- **BREAKING**: Deny all permissions by default — `requestPermission` is now always `true` on the wire, and permission requests are denied when no `:on-permission-request` handler is configured. Previously, omitting the handler meant the CLI never asked for permission. To restore the old behavior, pass `:on-permission-request copilot/approve-all` in your session config.

### Added (upstream PR #509 sync)
- `approve-all` — convenience permission handler that approves all requests (`copilot/approve-all`). Equivalent to the upstream Node.js SDK `approveAll` export. Use as `:on-permission-request copilot/approve-all` in session config.
- Integration tests for deny-by-default permission model: wire format assertions, `approve-all` behavior, handler dispatch with/without handler, custom selective handler

### Changed
- MCP local server example now passes `:on-permission-request copilot/approve-all` (required for MCP tool execution under deny-by-default)

### Fixed
- Permission denial result `:kind` now consistently uses keywords (not strings) in default handler responses, matching specs and `approve-all` behavior

## [0.1.25.1] - 2026-02-18
### Fixed
- Release pipeline: GPG signing now fails fast with a clear error when no key is available, instead of silently producing unsigned artifacts that Maven Central rejects
- Release pipeline: `stamp-changelog` no longer throws when `[Unreleased]` is empty — prints a warning and exits cleanly

### Changed
- Metadata API example: suppressed SDK INFO log noise, improved session display (short IDs, summaries, timestamps), clearer messaging for unsupported CLI methods

## [0.1.25.0] - 2026-02-18
### Added
- **Core.async native async architecture** — eliminates all blocking operations from the async API path:
  - `<create-session` / `<resume-session` — async session lifecycle functions that return channels delivering `CopilotSession`, safe for use inside `go` blocks
  - Protocol layer now uses core.async channels instead of Java promises for RPC responses
  - Session send-lock replaced `java.util.concurrent.Semaphore` with a channel-based lock
  - `<send-async*` — fully non-blocking send pipeline using parking channel operations
  - The idiomatic pattern is now `(go (let [s (<! (<create-session client opts))] (<! (<send! s {:prompt "..."}))))` — no thread pool starvation

### Fixed
- Wire parity: always send `requestPermission`, `requestUserInput`, and `hooks` fields (as `false` when not configured) to match upstream Node.js SDK — fixes 400 errors when creating sessions without specifying a model
- MCP server environment variables now passed correctly as literal values to subprocesses — sends `envValueMode: "direct"` on session create/resume wire payloads (upstream PR #484)
- Fix potential semaphore deadlock in `send-and-wait!` and `send-async*` — `tap` on a closed mult could leave the send-lock permanently held; moved `tap` inside the `try/finally` block that releases the lock
- Multi-agent example parallelism: sessions and sends now run concurrently in `go` blocks instead of sequentially blocking

### Added (v0.1.24 sync)
- CLI stderr is now captured and forwarded to debug logging; included in error messages on startup failure for better diagnostics (inspired by upstream PR #492)
- `verify-protocol-version!` now races the initial ping against process exit to detect early CLI failures instead of blocking for 60 seconds on timeout
- `list-sessions` now accepts optional filter map `{:cwd :git-root :repository :branch}` to narrow results by session context (upstream PR #427)
- Session metadata from `list-sessions` now includes `:context` map with working directory info (`{:cwd :git-root :repository :branch}`) when available (upstream PR #427)
- `list-tools` — list available tools with metadata; accepts optional model param for model-specific overrides (upstream PR #464)
- `get-quota` — get account quota information (entitlements, usage, overage) (upstream PR #464)
- `get-current-model` — get the current model for a session (session-scoped) (upstream PR #464)
- `switch-model!` — switch the model for a session (session-scoped) (upstream PR #464)
- New event types: `session.context_changed`, `session.title_changed`, `session.warning` (upstream PRs #396, #427)
- `line-range` optional field on file/directory attachment specs (upstream session-events schema update)
- `agent-mode` optional field on `user.message` event data — one of `:interactive`, `:plan`, `:autopilot`, `:shell` (upstream session-events schema update)

### Changed
- **BREAKING**: Namespace prefix renamed from `krukow.copilot-sdk` to `github.copilot-sdk`.
  All requires must be updated (e.g., `github.copilot-sdk.client`, `github.copilot-sdk.helpers`).
- **Repository moved** to [`copilot-community-sdk/copilot-sdk-clojure`](https://github.com/copilot-community-sdk/copilot-sdk-clojure)
  on GitHub. Maven artifact unchanged: `io.github.copilot-community-sdk/copilot-sdk-clojure`.
- Git dependency URL in README fixed to point to new org

### Added (v0.1.23 sync)
- Selection attachment type support (`:selection` with `:file-path`, `:display-name`, `:selection-range`, `:text`)
- Session lifecycle event subscription via `on-lifecycle-event` (`:session.created`, `:session.deleted`, `:session.updated`, `:session.foreground`, `:session.background`)
- Enhanced model info with full capabilities (`:model-capabilities`), billing (`:model-billing`), and policy (`:model-policy`) structures
- `session.shutdown` and `skill.invoked` event types
- `"xhigh"` reasoning effort level

### Added (CI/CD)
- GitHub Actions CI workflow: runs `bb ci` (unit/integration tests, doc validation, jar build) on PRs and `main` pushes
- Daily documentation updater agentic workflow: automatically scans for merged PRs and updates docs
- `.github/instructions/documentation.instructions.md`: guidelines for AI agents updating documentation
- GitHub Actions Release workflow: manual dispatch with version management inputs (`sync-upstream`, `bump-clj-patch`, `set-version`), GPG signing, Maven Central deploy, and [SLSA build provenance attestation](https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations)
- `bb ci` task: runs tests, doc validation, and jar build (no copilot CLI required)
- `bb ci:full` task: full pipeline including E2E tests and examples (requires copilot CLI)
- Cross-platform `build.clj`: `md5-hash` and `sha1-hash` helpers with macOS/Linux fallback
- Idempotent `update-readme-sha`: succeeds when README already has current SHA
- `stamp-changelog` build task: automatically moves `[Unreleased]` entries to a versioned section with today's date and updates comparison links; integrated into the release workflow

### Changed (CI/CD)
- Release workflow now creates a PR with auto-merge instead of pushing directly to `main`,
  compatible with branch protection rules requiring PRs and status checks
- Release workflow creates a `vX.Y.Z.N` tag after successful deploy

### Added (documentation)
- `doc/index.md` — Documentation hub / table of contents
- `doc/style.md` — Documentation authoring style guide
- `doc/reference/API.md` — API reference (moved from `doc/API.md`)
- `PUBLISHING.md` — Maven Central publishing guide
- `script/validate_docs.clj` — Documentation validation script (`bb validate-docs`)
- `.github/skills/update-docs/SKILL.md` — Update-docs skill for regenerating docs after source changes

### Changed
- **BREAKING**: Version scheme changed to 4-segment format `UPSTREAM.CLJ_PATCH` (e.g., `0.1.22.0`)
  to track upstream copilot-sdk releases. See PUBLISHING.md for details.
- New build tasks: `sync-version` (align to upstream), `bump-version` (increment clj-patch)
- Replaced `cheshire/cheshire` (Clojars) with `org.clojure/data.json` (Maven Central)
  for JSON processing — eliminates Clojars and Jackson transitive dependencies
- **Deprecated** Clojars publishing (`net.clojars.krukow/copilot-sdk`). Use Maven Central
  (`io.github.copilot-community-sdk/copilot-sdk-clojure`) going forward.

### Removed
- Java API (`java_api.clj`), Java examples, and AOT compilation.
  For Java/JVM usage, see [copilot-sdk-java](https://github.com/copilot-community-sdk/copilot-sdk-java).
- `doc/intro.md` and `doc/java-async-api.md` (replaced by reorganized documentation)

### Added
- Resume session config parity with create-session (upstream PR #376):
  - `resume-session` now accepts `:model`, `:system-message`, `:available-tools`,
    `:excluded-tools`, `:config-dir`, and `:infinite-sessions` options
- API parity with official Node.js SDK (`@github/copilot-sdk`):
  - `:working-directory` option for `create-session` and `resume-session`
  - `:disable-resume?` option for `resume-session`
  - `get-foreground-session-id` and `set-foreground-session-id!` client methods (TUI+server mode)
  - `:large-output` marked as experimental (CLI protocol feature, not in official SDK)
- New metadata APIs (upstream PR #77):
  - `get-status` - Get CLI version and protocol information
  - `get-auth-status` - Get current authentication status
  - `list-models` - List available models with metadata
- New event type `:copilot/tool.execution_progress` for progress updates during long-running tool executions
- Infinite sessions support (upstream PR #76):
  - `:infinite-sessions` config option for `create-session`
  - Automatic context compaction when approaching context window limits
  - New event types: `:copilot/session.compaction_start`, `:copilot/session.compaction_complete`
- Session workspace path accessors for Clojure and Java APIs
- New event type `:copilot/session.snapshot_rewind` for session state rollback (upstream PR #208)
- Exported event type constants:
  - `event-types` - All valid event types
  - `session-events` - Session lifecycle and state events
  - `assistant-events` - Assistant response events
  - `tool-events` - Tool execution events
- New example: `session_events.clj` - demonstrates monitoring session state events
- Authentication options for client (upstream PR #237):
  - `:github-token` - GitHub token for authentication (sets `COPILOT_SDK_AUTH_TOKEN` env var)
  - `:use-logged-in-user?` - Whether to use logged-in user auth (default: true, false when token provided)
- Hooks and user input handlers (upstream PR #269):
  - `:on-user-input-request` - Handler for `ask_user` tool invocations
  - `:hooks` - Lifecycle hooks map with callbacks:
    - `:on-pre-tool-use` - Called before tool execution
    - `:on-post-tool-use` - Called after tool execution
    - `:on-user-prompt-submitted` - Called when user sends a prompt
    - `:on-session-start` - Called when session starts
    - `:on-session-end` - Called when session ends
    - `:on-error-occurred` - Called on errors
- Reasoning effort support (upstream PR #302):
  - `:reasoning-effort` session config option ("low", "medium", "high", "xhigh")
  - Model info now includes `:supports-reasoning-effort`, `:supported-reasoning-efforts`, `:default-reasoning-effort`
- Documentation:
  - `doc/getting-started.md` — Comprehensive tutorial
  - `doc/auth/index.md` — Authentication guide (all methods, priority order)
  - `doc/auth/byok.md` — BYOK (Bring Your Own Key) guide with examples for OpenAI, Azure, Anthropic, Ollama
  - `doc/mcp/overview.md` — MCP server configuration guide
  - `doc/mcp/debugging.md` — MCP debugging and troubleshooting guide
- New examples:
  - `examples/byok_provider.clj` — BYOK provider configuration
  - `examples/mcp_local_server.clj` — MCP local server integration
- BYOK validation: `create-session` and `resume-session` now throw when `:provider` is specified without `:model`

### Changed
- **BREAKING**: Event types are now namespaced keywords (e.g., `:copilot/session.idle` instead of `:session.idle`)
  - Migration: Add `copilot/` prefix to all event type keywords in your code
- **FIX**: MCP server config wire format now correctly strips `:mcp-` prefix before sending to CLI.
  Previously `:mcp-command`, `:mcp-args`, `:mcp-tools`, etc. were sent as `mcpCommand`, `mcpArgs`,
  `mcpTools` on the wire; they are now correctly sent as `command`, `args`, `tools` to match the
  upstream Node.js SDK. The Clojure API keys (`:mcp-command`, `:mcp-args`, etc.) are unchanged.
- Protocol version bumped from 1 to 2 (requires CLI 0.0.389+)
- Removed `helpers/query-seq` in favor of `helpers/query-seq!` and `helpers/query-chan`
- `list-models` now caches results per client connection to prevent 429 rate limiting under concurrency (upstream PR #300)
  - Cache is cleared on `stop!` and `force-stop!`

## [0.1.0] - 2026-01-18
### Added
- Initial release of copilot-sdk-clojure
- Full port of JavaScript Copilot SDK to idiomatic Clojure
- JSON-RPC protocol layer with Content-Length framing
- CopilotClient for managing CLI server lifecycle
  - stdio and TCP transport support
  - Auto-start and auto-restart capabilities
- CopilotSession for conversation management
  - `send!`, `send-and-wait!`, `send-async` message methods
  - Event handling via core.async mult channels
  - Tool registration and invocation
  - Permission request handling
- Tool definition helpers with result builders
- System message configuration (append/replace modes)
- MCP server configuration support
- Custom agent configuration support
- Provider configuration (BYOK - Bring Your Own Key)
- Streaming support for assistant messages
- Comprehensive clojure.spec definitions
- Example applications:
  - Basic Q&A conversation
  - Custom tool integration
  - Multi-agent orchestration with core.async

### Dependencies
- org.clojure/clojure 1.12.4
- org.clojure/core.async 1.6.681
- org.clojure/spec.alpha 0.5.238
- cheshire/cheshire 5.13.0

[Unreleased]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.1.1-SNAPSHOT...HEAD
[0.2.1.1-SNAPSHOT]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.0.0...v0.2.1.1-SNAPSHOT
[0.2.0.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.33.0-SNAPSHOT...v0.2.0.0
[0.1.33.0-SNAPSHOT]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.32.0...v0.1.33.0-SNAPSHOT
[0.1.32.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.32.0...v0.1.32.0
[0.1.32.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.30.1...v0.1.32.0
[0.1.30.1]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.30.0...v0.1.30.1
[0.1.30.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.29.0...v0.1.30.0
[0.1.29.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.28.0...v0.1.29.0
[0.1.28.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.26.0-SNAPSHOT...v0.1.28.0
[0.1.26.0-SNAPSHOT]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.25.1...v0.1.26.0-SNAPSHOT
[0.1.25.1]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.25.0...v0.1.25.1
[0.1.25.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/0.1.0...v0.1.25.0
[0.1.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/releases/tag/0.1.0
