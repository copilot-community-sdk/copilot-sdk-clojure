# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added (examples)
- **New `manual_tool_resume` example** (`examples/manual_tool_resume.clj`),
  the SDK-driven analogue of the upstream `manual_tool_resume` sample. It
  demonstrates a declaration-only tool (defined without a `:handler`, upstream
  PR #1308) whose pending permission request and pending tool call are resolved
  by hand across three separate client lifecycles via `resume-session` with
  `:continue-pending-work? true` — resolving the original request ids with
  `handle-pending-permission-request!` and `handle-pending-tool-call!`,
  subscribing to events before each trigger with a bounded wait. Each lifecycle
  suspends gracefully with `disconnect!` (which persists the in-flight pending
  requests) rather than force-killing the client.
- **`run-all-examples.sh` now runs `ask_user_failure` and `manual_tool_resume`**
  (19 CLI-only examples total) and documents why `byok_provider`, `empty_mode`,
  and `mcp_local_server` are excluded (they require a provider API key or
  `npx`/network setup). `examples/README.md` updated to match.

### Changed (v1.0.0 GA sync)
- **Synced version to upstream GA `v1.0.0`** (`1.0.0-beta.12.0` -> `1.0.0.0`).
  Upstream's `v1.0.0` release is functionally identical to `v1.0.0-beta.12` at
  the SDK level: no changes to `nodejs/src/`, the pinned `@github/copilot` CLI
  dependency stays `^1.0.57` (matching our schema `1.0.57`), and there are no
  `schemas/` or generated-code diffs. The remaining upstream commits in the
  range are Go/Java/CI/release plumbing and E2E test de-flaking — nothing to
  port. This SDK is therefore at full API/wire/schema parity with upstream GA.

### Documentation
- **Documented observability/telemetry.** Added an Observability section to the
  API reference covering the client `:telemetry` map (OpenTelemetry export:
  `:otlp-endpoint`, `:file-path`, `:exporter-type`, `:source-name`,
  `:capture-content?`), the client `:on-get-trace-context` distributed-trace hook,
  and the session `:enable-session-telemetry?` flag. These options were already
  implemented but undocumented in the API reference.
- **Documented `:on-exit-plan-mode` and `:on-auto-mode-switch`** session config
  handlers (upstream PR #1228) in the API reference, plus added option-table rows
  for `:telemetry` and `:on-get-trace-context` to the client constructor.
- **Corrected stale docstrings.** The top-level `create-session`,
  `resume-session`, and `join-session` docstrings claimed `:on-permission-request`
  was **required**; it has been **optional** since upstream PR #1308. Docstrings
  now match the implementation.
- **Added a Features navigation map** to the documentation hub and a durable
  `doc/upstream-doc-gap-matrix.md` recording per-topic coverage versus the
  upstream SDK docs.
- **Doc validation now covers `doc/`-root pages.** `bb validate-docs` previously
  skipped markdown files directly under `doc/` (e.g. `index.md`,
  `getting-started.md`); it now validates them, and link extraction ignores
  illustrative links inside inline-code spans.

### Security
- **Validation exceptions no longer leak secrets.** Configuration validation
  failures (`client`, `create-session`, `resume-session`, and MCP-server checks)
  previously embedded the raw caller-supplied options map in the thrown
  exception's `ex-data` (and via `clojure.spec`'s `::s/value`), so a default
  uncaught-exception report could print `:github-token`,
  `:tcp-connection-token`, BYOK `:provider` credentials (`:api-key`,
  `:bearer-token`, custom `:headers`), and MCP `:mcp-headers` / `:env` values in
  cleartext. These values are now masked (`"***"`) in both the exception message
  and `ex-data` before the exception is thrown.

### Fixed (correctness)
- **`start!` is now safe under concurrent calls.** The status guard previously
  did a non-atomic check-then-act (read `:status`, then a separate `swap!` to
  `:connecting`), so two threads calling `start!` on the same client could both
  pass the guard and spawn two CLI processes. The transition is now an atomic
  `swap-vals!` compare-and-set: only the caller that observes a non-`:connecting`
  /`:connected` status proceeds to spawn; the others no-op. The same atomic guard
  is applied to the test-only `connect-with-streams!`.
- **`disconnect!` is now idempotent under concurrent calls.** It used a
  non-atomic check-then-act on the session's `:destroyed?` flag, so two threads
  disconnecting the same session could both send a `session.destroy` RPC. The
  teardown is now claimed with an atomic `swap-vals!` on `:destroyed?`; only the
  winning caller notifies the server and closes the event channel.
- **`remove-session!` no longer closes the event channel before removing the
  session.** It closed the channel first and dissoc'd the session afterward,
  leaving a window where the notification router could still resolve the session
  and `offer!` an event to the just-closed channel (a spurious "buffer full"
  warning). The session is now removed from the registry first, then its channel
  is closed.
- **`query-chan` no longer blocks a go dispatch thread or leaks on send
  failure.** It called the blocking `disconnect!` directly inside its event
  go-loop (parking a shared core.async dispatch thread for the duration of
  connection teardown); teardown now runs on `async/thread` and the loop parks
  on its result. If the initial `send!` throws before the loop starts, the
  freshly created session is now disconnected (instead of leaking) and the
  output channel is closed before the error propagates.
- **`subscribe-events` / `events->chan` now actually isolate slow subscribers.**
  Both used a fixed (blocking) channel buffer, but their docstrings promised
  that a full subscriber buffer drops events "for this subscriber only." With a
  fixed buffer, `mult` blocks when any tap's buffer fills, stalling delivery to
  *all* subscribers until the slow one drains. Both wrappers now use a
  `sliding-buffer`, so a slow subscriber drops its own oldest events without
  ever blocking the mult or other subscribers — matching the documented
  behavior. Docstrings and the API reference were corrected accordingly.
- **A failed `start!` no longer leaks resources.** If startup failed after the
  CLI process was spawned (e.g. the process died before announcing its port, or
  protocol verification failed), the error path only set the client status to
  `:error` and left the spawned process, its stderr/exit-watcher threads, the
  socket, and the JSON-RPC connection running. `start!` now tears these down
  before re-throwing.
- **In-flight requests no longer hang on disconnect.** A graceful
  `disconnect` (e.g. `stop!`) previously left any in-flight JSON-RPC request's
  response channel unresolved, so a caller blocked on it would wait forever.
  `disconnect` now drains all pending requests and delivers a
  `{:error {:code -32000 :message "Connection closed"}}` to each. The read
  loop's EOF/IO-error draining and `disconnect` now share a single atomic
  `drain-pending!`, so a pending request is resolved exactly once even when
  both run concurrently.
- **Requests sent during/after disconnect fail fast.** `send-request` now
  registers its pending entry only while the connection is running, in one
  atomic step, and resolves the response channel with a connection-closed error
  if the connection is gone or the outgoing channel is already closed —
  previously such a request was silently dropped and the caller hung.

### Removed
- **Dropped the unused `:force-stopping?` client-state flag.** `force-stop!`
  set it (and `initial-state` initialized it), but nothing ever read it, so it
  was dead state. `:stopping?` (which the process-exit watcher does read) is
  unchanged.

### Changed
- **BREAKING**: renamed `unsubscribe-events` to `unsubscribe-events!`. The
  function mutates — it untaps the channel from the session's event mult and
  closes it — so it now carries the `!` side-effect suffix per the SDK's naming
  convention. Update callers to the new name (no behavior change).

### Added
- **`<send-and-wait!`** — channel-based equivalent of `send-and-wait!` for use
  inside `go` blocks. Returns a channel that delivers the final assistant message
  event (same shape as `send-and-wait!`'s successful return; content under
  `[:data :content]`), or closes empty if none was received. Like `<send!`, it
  does not surface `:copilot/session.error`/timeout as exceptions. Complements
  `<send!` (which yields just the content string).

### Fixed (GA parity)
- **BYOK `ProviderConfig` wire keys** — `:provider {:provider-type ...
  :azure-options {:azure-api-version ...}}` now serializes to the upstream wire
  shape (`type` / `azure` / `apiVersion`) instead of the camelCased SDK names
  (`providerType` / `azureOptions` / `azureApiVersion`). The runtime reads the
  provider config verbatim, so the previous encoding meant non-OpenAI BYOK
  (`:azure`, `:anthropic`) and the Azure `apiVersion` were silently dropped —
  only `:openai` worked, because it is the runtime default. Matches the
  `ProviderConfig` shape in `nodejs/src/types.ts`.

### Added (GA parity)
- **`:session-idle-timeout-seconds` client option** — server-wide session idle
  timeout. When `> 0`, the SDK appends `--session-idle-timeout <n>` to the
  spawned CLI, matching the official SDK's `sessionIdleTimeoutSeconds`. Default
  disabled (`0`).

### Changed (GA parity)
- **`list-tools`, `get-quota`, and `get-current-model` are now marked
  `^:experimental`.** None of these correspond to a method on the official
  Copilot SDK's `CopilotClient` / `CopilotSession`; they expose convenience
  wire RPCs (`tools.list`, `account.getQuota`, `session.model.getCurrent`).
  Marking them experimental keeps the stable GA surface aligned with the
  upstream SDK while leaving the helpers available. Non-breaking.
- **Public `event-types` set now matches the pinned schema exactly.** The
  curated set previously omitted `assistant.message_start`, `model.call_failure`,
  `session.extensions.attachments_pushed`, and the two canvas events
  (`session.canvas.opened`, `session.canvas.registry_changed`). These are all
  delivered by the runtime and parsed by the wire layer, so consumers must be
  able to discover them; they are now included (and added to the idiom
  `::event-type` spec), with `assistant.message_start` also categorized under
  `assistant-events`. The canvas authoring API remains out of scope for 1.0.0 —
  only the events are observable. A new codegen test guards against future drift
  between the public `event-types` set and the generated schema set.

### Added (v1.0.0-beta.12 sync)
- **`:context-tier` and `:reasoning-summary` on `switch-model!` / `set-model!`**
  (upstream PR #1522). `:context-tier` accepts `:default` or `:long-context`
  (wire-encoded as `contextTier` → `"default"` / `"long_context"`);
  `:reasoning-summary` accepts `"none"` / `"concise"` / `"detailed"`
  (wire-encoded as `reasoningSummary`). Mirrors the existing create/resume
  session-config options.
- **`:model` field on `:copilot/tool.execution_start` event data**
  (upstream npm `@github/copilot` 1.0.57). Added to `::tool.execution_start-data`
  as an optional key, mirroring `::tool.execution_complete-data`.
- **`session.extensions.attachments_pushed` event + `extension_context`
  attachment branch** — regenerated wire specs from the bumped schema
  (upstream PR #1517).

### Fixed (v1.0.0-beta.12 sync)
- **Preserve opaque `extension_context` attachment payloads** — `extension_context`
  attachments (reachable on `user.message` events via `session.getMessages` and on
  `session.extensions.attachments_pushed` events) carry an opaque `:payload` whose
  keys must not be kebab-cased by `wire->clj`. The protocol layer now restores the
  raw payload for these attachments on both the live notification and historical
  response paths.

### Changed (v1.0.0-beta.12 sync)
- Pinned schema bumped `1.0.56-1` → `1.0.57`; version synced to upstream release
  `v1.0.0-beta.12` (`1.0.0-beta.12.0`).

### Added (Client Mode Empty — upstream PR #1428)
- **`:mode` client option** — `#{:copilot-cli :empty}`, default
  `:copilot-cli`. Selects between historical CLI behavior and a hardened
  multitenancy posture for SaaS hosts that must isolate sessions from
  the local machine. Validated on `copilot/client`.
- **`:empty` mode constructor enforcement** — In `:empty` mode the
  client requires at least one tenant-scoped storage root
  (`:copilot-home`, `:session-fs`, `:cli-url`, or `:is-child-process?`)
  so the CLI never falls back to the user's home directory, and forces
  `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI so the headless server
  never touches the host keychain.
- **Required `:available-tools` in `:empty` mode** —
  `create-session` / `resume-session` (sync and async) now reject
  empty-mode sessions that don't supply a tool allow-list. An empty
  vector `[]` is legitimate (it means "no tools") — the key just has
  to be present so silently-empty filters can't happen.
- **9 mode-default session config fields** — In `:empty` mode the SDK
  spreads safe defaults UNDER the caller's session config (caller
  always wins): `:enable-session-telemetry? false`,
  `:mcp-oauth-token-storage :in-memory`, `:skip-embedding-retrieval true`,
  `:embedding-cache-storage :in-memory`,
  `:enable-on-demand-instruction-discovery false`,
  `:enable-file-hooks false`, `:enable-host-git-operations false`,
  `:enable-session-store false`, `:enable-skills false`.
- **`session.options.update` plumbing** — After a successful
  `session.create` / `session.resume`, the SDK now issues a follow-up
  `session.options.update` RPC carrying the four overridable feature
  flags (and, in `:empty` mode, `installedPlugins: []`):
  - `:skip-custom-instructions` (default `true` in `:empty`)
  - `:custom-agents-local-only` (default `true` in `:empty`)
  - `:coauthor-enabled` (default `false` in `:empty`)
  - `:manage-schedule-enabled` (default `false` in `:empty`)
  In `:copilot-cli` mode only flags the caller explicitly set are
  forwarded; if the patch ends up empty the RPC is skipped. On failure
  the SDK disconnects and removes the half-configured session before
  rethrowing. Wired into all four entry points (`create-session`,
  `resume-session`, `<create-session`, `<resume-session`).
- **System message normalization in `:empty` mode** — Mirrors upstream
  `getSystemMessageConfigForMode`: if the caller did not provide a
  `:system-message`, the SDK emits `{:mode "customize" :sections
  {:environment_context {:action "remove"}}}`. If the caller provided
  `:append`, the SDK promotes it to `:customize` (preserving the
  content) and adds the env-context removal. If the caller used
  `:customize` and supplied their own `:environment-context` section,
  the SDK leaves it untouched. `:replace` mode is passed through
  unchanged. `:copilot-cli` mode keeps the legacy behavior — no
  normalization.
- **Always-emit `:tool-filter-precedence "excluded"`** — Both modes now
  always send `toolFilterPrecedence: "excluded"` on `session.create`
  and `session.resume`. Makes the ordering between
  `:available-tools` and `:excluded-tools` deterministic regardless of
  CLI version.
- **`github.copilot-sdk.tool-set` namespace** — Source-qualified tool
  filter constructors (`builtin`, `mcp`, `custom`, `builtins`) plus
  `isolated-builtins` / `isolated` — the parity equivalents of
  upstream `BuiltInTools.Isolated`. Bare `"*"` (no source) is rejected
  at the SDK boundary and at construction time.

### Added (post-v1.0.0-beta.4 sync, round 6)
- **`:agent-mode` and `:display-prompt` send options** — `session/send!`
  (and async/streaming variants) now accept:
  - `:agent-mode` — keyword in `#{:interactive :plan :autopilot :shell}`,
    wire-encoded as `agentMode`. Lets the model run with different agent
    behaviours per message. (upstream PR #1438)
  - `:display-prompt` — string shown in the timeline UI instead of the
    model-facing `:prompt`. Useful when the model prompt contains
    machinery or context that should not be surfaced to the end user.
    Wire-encoded as `displayPrompt`. (upstream PR #1470)
- **`:mcp-oauth-token-storage` config option** — Controls where MCP OAuth
  tokens are persisted. Enum `#{:persistent :in-memory}`, defaulting to
  the server's default (persistent disk-backed). Set to `:in-memory` in
  multi-tenant hosts that must not leak tokens to disk. Wire-encoded as
  `mcpOAuthTokenStorage` (the wire key is set directly to bypass the
  default kebab-camel converter which would mangle `OAuth`). Accepted on
  both create and resume. (upstream PR #1326)
- **Multitenancy per-session granular flags** — All optional, accepted on
  both `create-session` and `resume-session`:
  - `:embedding-cache-storage` (`#{:persistent :in-memory}`, wire
    `embeddingCacheStorage`)
  - `:skip-embedding-retrieval` (boolean)
  - `:organization-custom-instructions` (string)
  - `:enable-on-demand-instruction-discovery` (boolean)
  - `:enable-file-hooks` (boolean)
  - `:enable-host-git-operations` (boolean)
  - `:enable-session-store` (boolean)
  - `:enable-skills` (boolean)

  Lets multi-tenant hosts opt individual sessions out of disk-backed
  caches, host git, hooks, sessions store, and skills discovery without
  switching to a separate client. (upstream PR #1474)
- **`:plugin-directories` config option** — `[string]` of extra plugin
  directories. Wire-encoded as `pluginDirectories`. Loaded even when
  `:enable-config-discovery` is `false`, so multi-tenant hosts can
  inject a curated plugin set without enabling general discovery.
  Accepted on both create and resume. (upstream PR #1482)
- **Cloud sessions can defer `sessionId` to the server** — When
  `:cloud` is set and `:session-id` is omitted from `create-session` /
  `<create-session`, the SDK now omits `sessionId` from the
  `session.create` request and captures the server-assigned id from the
  response. A new inline-response callback (registered with
  `protocol/send-request`'s `{:on-response-inline}` option) runs
  synchronously in the JSON-RPC reader thread before the next inbound
  message is processed, so any session-scoped notification arriving
  immediately after the response is correctly routed to the
  newly-registered session. Callers may still supply `:session-id`
  explicitly; if both caller and server provide an id, they must agree.
  (upstream PR #1479)
- **Config parity additions** — Existed in upstream `SessionConfigBase`
  prior to this window; added to close pre-existing parity gaps. All
  optional, accepted on both create and resume:
  - `:reasoning-summary` (`#{"none" "concise" "detailed"}`, wire
    `reasoningSummary`) — controls inclusion/granularity of reasoning
    summaries in assistant turns. String-valued for consistency with
    the existing `:reasoning-effort` option.
  - `:context-tier` (`#{:default :long-context}`, wire `contextTier` as
    `"default"` / `"long_context"`) — selects long-context model variants.
  - `:large-output` on `resume-session` — already accepted on create;
    now also forwarded on resume (wire `largeOutput`).
- **`:config-directory` and `:output-directory` option aliases** —
  Non-breaking aliases for `:config-dir` and `:output-dir`
  (`:output-directory` is inside the `:large-output` map). Wire keys
  stay `configDir` / `outputDir`. When both old and new keys are
  supplied, the new key wins. (upstream PR #1482 source-side rename)
- **New event types** — Added to the public `event-types` set and
  picked up automatically by the generated wire spec:
  - `:copilot/hook.progress` — ephemeral progress updates from
    long-running hooks. Curated `::hook.progress-data` spec exposes
    `:message` (non-blank string); `:session-id` / `:timestamp` live on
    the envelope.
  - `:copilot/session.autopilot_objective_changed` — autopilot
    objective lifecycle events. Generated wire spec carries
    `:operation` (required, one of `"create"` / `"update"` /
    `"delete"`), with optional `:id` (integer) and `:status`. The
    `:status` enum is widened to include `"active"`, `"paused"`,
    `"cap_reached"`, `"completed"`.
  - `:copilot/session.permissions_changed` — emitted when per-session
    permission flags change. Curated `::session.permissions_changed-data`
    spec requires `:allow-all-permissions` and
    `:previous-allow-all-permissions` (both booleans).
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.55-1`
  to `1.0.56-1`, covering upstream tags `v1.0.0-beta.9` and
  `v1.0.0-beta.10`. Schema regen picks up several new optional event
  fields (`working-directory` on `external_tool.requested-data`,
  `context-tier` on `session.resume-data`, autopilot status values) and
  the three new event types above.

### Deferred (round 6)
- **Removal of the legacy `:config-dir` / `:output-dir` option keys
  (upstream PR #1482 follow-up)** — The new `:config-directory` /
  `:output-directory` aliases ship in this release (see Added). The
  breaking removal of the older spellings is tracked alongside the
  other rename PRs (#1357 etc.) for a coordinated rename release.
- **Canvas runtime, MCP Apps `enableMcpApps`** — Continue to defer as
  experimental coupled surfaces.

### Added (post-v1.0.0-beta.4 sync, round 5)
- **`:on-post-tool-use-failure` hook** — New lifecycle hook in the
  `:hooks` map. Fires after a tool execution whose result was `"failure"`;
  `:on-post-tool-use` only fires for successful results, so register this
  handler to observe or react to failed tool outcomes. Note: `"rejected"`,
  `"denied"`, and `"timeout"` results do not currently trigger this hook —
  only `"failure"` does. Handler input has `:tool-name`, `:tool-args`,
  `:error` (string), plus the base hook fields (`:session-id`,
  `:timestamp`, `:cwd`). Optional return value:
  `{:additional-context "..."}` is appended as hidden guidance to the
  model alongside the failed tool result. (upstream PR #1421)
- **`:runtime-instructions` system message section** — New section recognized
  by the SDK's `:system-message` `:customize` mode. Wire-encoded as
  `"runtime_instructions"` and accepted by `::specs/system-prompt-section`.
  Upstream PR #1377 also renamed `SystemPromptSection` → `SystemMessageSection`
  in TypeScript; for source compatibility the Clojure side keeps
  `specs/system-prompt-sections` as the canonical name and exposes
  `specs/system-message-sections` (and `::specs/system-message-section`) as
  aliases pointing at the same data. (upstream PR #1377)
- **`:copilot/mcp_app.tool_call_complete` event** — New session event emitted
  when a tool call from an MCP App completes (upstream schema 1.0.52-4,
  SEP-1865). Added to the public `event-types` set. The `:arguments` and
  `:result` fields are preserved opaquely by `protocol/preserve-event-opaque-fields`
  (they survive `normalize-incoming` without kebab-case rewriting so
  source-defined keys round-trip verbatim).
- **Additional event-data fields (passive, via schema regen)** — All optional;
  generated `:opt-un` specs pick them up automatically:
  - `:service-request-id` on `:error`, `:assistant.message`, `:assistant.usage`,
    `:model.call_failure`, `:session.compaction_complete` event data
    (Copilot CAPI service-request-id for correlation with CAPI logs).
  - `:context-tier` (`"long_context" | "default" | nil`) on
    `:session.model_change` data.
  - `:transport`, `:plugin-name`, `:plugin-version` on the loaded MCP server
    spec inside `:session.mcp_servers_loaded` data.
  - `:error` on `:session.mcp_server_status_changed` data.
  - `:source` and `:trigger` (`"user-invoked" | "agent-invoked" | "context-load"`)
    on `:skill.invoked` data.
  - `:tool-description` and `:ui-resource` on `:tool.execution_complete` data.
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.52-1` to
  `1.0.55-1`. Picked up the 1.0.52-4 pre-release (upstream PR #1393), the
  1.0.52 stable release (upstream PR #1405), the 1.0.53-2 pre-release
  (upstream PR #1408), and the 1.0.53 / 1.0.54 / 1.0.55-0 / 1.0.55-1
  schema bumps (upstream PRs #1410, #1411, #1412, #1432). Schema regen
  surfaces new wire-only canvas event types (`session.canvas.opened`,
  `session.canvas.registry_changed`) and their field set in the
  generated `event-specs` namespace. The canvas runtime (extension
  manifests, `requestCanvasRenderer`, `openCanvases`, etc. — upstream
  PRs #1401, #1413) is **not yet exposed** on the public Clojure API,
  including the curated `event-types` set. Canvas runtime support
  (including opaque-field preservation for `data.input` and nested
  `inputSchema` payloads on canvas events) will land in a dedicated
  future sync round.

### Changed (post-v1.0.0-beta.4 sync, round 5)
- **BREAKING: Minimum supported protocol version raised from 2 to 3.** The
  SDK will now reject CLI servers that report protocol version 2. The
  back-compat shims that adapted v2 `tool.call` / `permission.request`
  JSON-RPC requests into v3 broadcast-event flows have been removed from
  `set-request-handler!` and from `protocol/normalize-incoming`. Clients
  must use a Copilot CLI that supports protocol v3 (CLI 1.0.46 or later).
  (upstream PR #1378)

### Removed (post-v1.0.0-beta.4 sync, round 5)
- **v2 protocol RPC dispatcher cases** — `tool.call` and `permission.request`
  request handlers (and their associated tests
  `test-tool-call-response-shape`, `test-tool-handler-runs-on-blocking-thread`,
  `test-permission-denied-with-deny-handler`,
  `test-permission-approved-with-handler`,
  `test-permission-unknown-session-response-shape`,
  `test-permission-custom-handler`, `test-permission-no-result-v2`). v3
  broadcast handlers `handle-v3-tool-requested!` / `handle-v3-permission-requested!`
  cover the same behaviour. (upstream PR #1378)

### Added (post-v1.0.0-beta.4 sync, round 4)
- **`:on-pre-mcp-tool-call` hook** — New lifecycle hook in the `:hooks` map
  that fires before an MCP tool call is dispatched to its server (upstream
  PR #1366, wire `hookType: "preMcpToolCall"`). The handler receives an
  input map with kebab-cased base fields (`:server-name`, `:tool-name`,
  `:tool-call-id`, `:session-id`, `:timestamp`) plus two opaque,
  source-defined fields that are preserved verbatim through wire
  normalization: `:arguments` (the MCP tool arguments) and `:_meta` (the
  MCP request metadata; the leading underscore is preserved — not
  collapsed by kebab-case conversion). The handler return value supports
  a tri-state `:meta-to-use` field controlling the outgoing MCP request
  `_meta`:
  - absent (`nil` / `{}`): preserve the existing `_meta`
  - `{:meta-to-use {...}}`: replace `_meta` with the given map (inner
    keys are preserved opaquely — not camelCased)
  - `{:meta-to-use nil}`: serialize as JSON `null`, removing `_meta`.

  Note: PR #1366 also renamed hook-input `cwd` to `workingDirectory` in
  the Node.js public API. Existing Clojure hook handlers (`:on-pre-tool-use`,
  `:on-post-tool-use`, etc.) currently receive the field as `:cwd`. For
  internal consistency, `:on-pre-mcp-tool-call` also exposes `:cwd`; the
  coordinated `:cwd` → `:working-directory` rename across all hooks is
  tracked with the deferred PR #1357 work below.

  (upstream PR #1366)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.51` to
  `1.0.52-1`. Additive changes only:
  - `session.compaction_complete-data` gains optional `:custom-instructions`.
  - `tool.execution_complete-data` gains optional `:sandboxed`.
  - `session.shutdown-data` relaxes `:total-premium-requests` from
    required to optional. The hand-curated idiom spec
    `::specs/session.shutdown-data` matches.

  Most of the remaining schema diff is `x-opaque-json` / description
  annotations that do not affect the generated specs.

### Tracked-but-not-ported (post-v1.0.0-beta.4 sync, round 4)
- **PR #1357 (TypeScript SDK API review fixes)** — Pure naming/API-shape
  changes in the JS public API: `onExitPlanMode → onExitPlanModeRequest`,
  `onAutoModeSwitch → onAutoModeSwitchRequest`,
  `ResumeSessionConfig.disableResume → suppressResumeEvent`,
  `cwd → workingDirectory` across config types, `getMessages → getEvents`,
  `InputOptions → UiInputOptions`, `maxInputTokens → maxPromptTokens`
  (drops wire shim), and removal of `autoStart` / `autoRestart` from
  `CopilotClientOptions`. The Clojure SDK already uses idiomatic kebab-case
  names that are independent of upstream's JS naming, and the
  `:max-input-tokens` → `maxPromptTokens` wire shim was explicit in
  Clojure from the start (mirrors upstream's pre-#1357 behavior), so
  dropping the JS shim has no effect on Clojure. The remaining renames
  (`:cwd` → `:working-directory` on hook inputs, `:disable-resume?` →
  `:suppress-resume-event?`, etc.) are breaking and are deferred to a
  separate PR that can introduce deprecation aliases.
- **PRs #1370 / #1371 (1.0.52-x schema bumps)** — Picked up by the
  schema regen in this PR.



### Changed (post-v1.0.0-beta.4 sync, round 3)
- **`ping` `:timestamp` field type changed in CLI 1.0.51** — Upstream PR #1340
  changed the `ping` RPC result `timestamp` field from epoch-millis number to
  an ISO 8601 date-time string (e.g. `"2026-05-21T08:00:00.000Z"`). The SDK
  forwards the server value verbatim, so callers of `sdk/ping` will see a
  string `:timestamp` against CLI ≥ 1.0.51 and a numeric epoch-millis value
  against older CLIs. The `::specs/timestamp` spec accepts both shapes
  (`(s/or :iso-string string? :epoch-ms nat-int?)`) so spec instrumentation
  passes against either CLI version. The mock test server was updated to
  emit the ISO string form, and the ping docstring documents both shapes.
  (upstream PR #1340)

### Added (post-v1.0.0-beta.4 sync, round 3)
- **`:mcp-args` is now optional on MCP stdio server configs** — Following
  upstream PR #1347 (`MCPStdioServerConfig.args` made optional across all
  SDKs), the `::mcp-local-server` / `::mcp-stdio-server` spec moves
  `::mcp-args` from `:req-un` to `:opt-un`. Stdio MCP servers declared with
  just `{:mcp-command "..." :mcp-tools [...]}` (no `:mcp-args`) now validate
  and forward correctly. (upstream PR #1347)
- **`:time-to-first-token-ms` on `assistant.usage` event data** — The CLI
  1.0.51 wire schema renamed the assistant-usage TTFT property from
  `ttftMs` to `timeToFirstTokenMs`, which surfaces as the kebab-case key
  `:time-to-first-token-ms` after wire normalization. The
  `::assistant.usage-data` spec lists both keys in `:opt-un` so events from
  older and newer CLIs both validate; the new key is the canonical name
  going forward. The generated wire spec
  (`generated/event_specs.clj`) only declares the new field, matching the
  current schema. (upstream CLI 1.0.51 schema)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.49` to
  `1.0.51`. Generated wire specs and coercions regenerated. Additive
  changes only beyond the rename above: bounded-integer fields previously
  typed as `number?` now generate as `integer?` (upstream PR #1329,
  "Use 32-bit types for bounded schema integers").

### Tracked-but-not-ported (post-v1.0.0-beta.4 sync, round 3)
- **PR #1316** (re-export generated session-event types from `index.ts`) —
  Node.js packaging concern only; the Clojure SDK already exposes generated
  event specs via the `github.copilot-sdk.generated.event-specs` namespace.
- **PR #1327 `ToolBinaryResult.type` tightened to `"image" | "resource"`** —
  Our `::binary-results-for-llm` spec is intentionally permissive
  (`(coll-of map?)`). Tightening would require adding a dedicated
  `::tool-binary-result` spec with string-valued `:type`; deferred since
  the runtime helpers already emit valid values.
- Upstream test stabilization (#1346, #1317, #1314), other-language /
  documentation / codegen-only PRs (#1336, #1291, #1331, #1338, #1339,
  #1304, #1289, #1313) — no Clojure SDK action required.

### Added (post-v1.0.0-beta.4 sync, round 2)
- **SessionFs SQLite support** — `sessionFs.sqliteQuery` and `sessionFs.sqliteExists`
  RPCs are now dispatched to a user-supplied provider. The provider-style handler
  accepts an optional nested `:sqlite {:query (fn [query-type sql params]) :exists (fn [])}`
  map, alongside the existing filesystem keys. The low-level handler shape uses
  flat `:sqlite-query` / `:sqlite-exists` keys (the adapter translates between
  them). Clients advertise support via `:capabilities {:sqlite true}` under
  `:session-fs`; the value is forwarded on `sessionFs.setProvider` and validated
  at session creation (declaring `capabilities.sqlite` without providing a
  `:sqlite` handler now throws). `query-type` is automatically coerced from the
  wire string to a keyword (`#{:exec :query :run}`). SQL bind-parameter keys
  (e.g. `$userId`) are preserved verbatim through wire normalization, and
  result row column-name keys (e.g. `:user_id`, `:created_at`) round-trip
  verbatim on the outgoing wire path — they are no longer mangled by
  recursive kebab→camelCase conversion. SQLite errors propagate as JSON-RPC
  errors (not wrapped as SessionFsError). (upstream PR #1299)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.49-1` to `1.0.49`.
  Additive changes only: new named enum types (`AutoModeSwitchResponse`,
  `ExitPlanModeAction`, `McpServerSource`, `McpServerStatus`, `SessionMode`,
  `SkillSource`, renamed `PermissionRequestMemoryAction/Direction`),
  `format: "duration"`/`"uri"` annotations, `"max"` value in reasoning-effort
  description, plus the new `sessionFs.sqliteQuery` / `sessionFs.sqliteExists`
  RPC methods. (upstream PRs #1305, #1307, #1327, #1333)

### Fixed (post-v1.0.0-beta.4 sync, round 2)
- **`examples/permission_bash.clj`** — Updated permission decision kind from the
  deprecated `:approved` to the current `:approve-once`. (carried from upstream PR #1315)

### Added (post-v1.0.0-beta.4 sync)
- **`:session-id` on hook input maps** — `:on-hook-invoke` handlers now receive
  a `:session-id` key on the input map. When the upstream wire payload includes
  a `sessionId` (sub-agent hooks), the wire-provided value is preserved;
  otherwise the SDK fills in the parent session id as a convenience.
  (upstream PR #1290)
- **`:cloud` session config option (create only)** — `create-session` accepts
  an optional `:cloud` map for creating a remote cloud session. Shape:
  `{:repository {:owner "octocat" :name "hello-world" :branch "main"}}` — `:owner`
  and `:name` are required non-blank strings; `:branch` is optional. Forwarded on
  the wire as `cloud.repository.*`. Matches upstream's `CloudSessionOptions` /
  `CloudSessionRepository`. Not accepted on `resume-session` / `join-session`,
  matching upstream `ResumeSessionConfig` (Pick excludes `cloud`).
  (upstream PR #1306)
- **Optional permission and tool callbacks (manual pending RPCs)** — Following
  upstream PR #1308, `:on-permission-request` is now **optional** on
  `create-session` and `resume-session`, and `:handler` is optional on tools
  built via `tools/define-tool`. When omitted, the runtime no longer
  auto-responds to permission requests or tool calls. Applications can resolve
  these requests asynchronously via the new public functions:
  - `sdk/handle-pending-tool-call!` / `sdk/<handle-pending-tool-call!`
  - `sdk/handle-pending-permission-request!` / `sdk/<handle-pending-permission-request!`

  Useful for human-in-the-loop UIs that surface pending tool/permission
  requests through `sdk/get-messages` and resolve them later.
  **Note:** This is a behavioural change — previously the SDK threw if
  `:on-permission-request` was missing; now it's accepted and the request is
  treated as pending until the application resolves it. (upstream PR #1308)
- **`:agent-model` on custom-agent configs** — Custom agent maps in
  `:custom-agents` now accept an optional `:agent-model` string (e.g.
  `"claude-haiku-4.5"`). When set, the runtime attempts to use that model
  for the agent, falling back to the parent session model if unavailable.
  Forwarded on the wire as `agentModel` on each entry in `customAgents`
  for both `session.create` and `session.resume`. (upstream PR #1309)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.48` to
  `1.0.49-1`. Generated wire specs and coercions regenerated; new pass-through
  event fields include `:display-prompt`, `:reasoning-summary`,
  `:previous-reasoning-summary`. (upstream PRs #1305, #1307)

### Fixed (post-v1.0.0-beta.4 sync, review iteration)
- `::cloud-repository` spec now enforces non-blank `:name` (was just `string?`
  via the shared `::name` spec, allowing blanks despite docs).
- `handle-pending-tool-call!` and `<handle-pending-tool-call!` now throw when
  neither `:result` nor `:error` is supplied (previously fell through to a
  default "tool returned no result" payload).
- `handle-pending-tool-call!` / `<handle-pending-tool-call!` validate that
  `:error`, when supplied, is a string.
- All four pending-RPC resolvers (`handle-pending-tool-call!`,
  `<handle-pending-tool-call!`, `handle-pending-permission-request!`,
  `<handle-pending-permission-request!`) now require `:request-id` to be a
  non-blank string.
- `handle-pending-permission-request!` and async variant validate that
  `:result :kind` is a keyword in the documented decision set — matches
  the upstream `PermissionDecision` schema:
  `:approve-once`, `:approve-for-session`, `:approve-for-location`,
  `:approve-permanently`, `:reject`, `:user-not-available`. Previously
  unsupported values (e.g. `{:kind 42}`) would be sent on the wire and
  surface as opaque server-side errors.
- `tools/define-tool-from-spec` mirrors `tools/define-tool`: when `:handler`
  is omitted, no `:tool-handler` wrapper is installed (declaration-only tool).

### Notes (v1.0.0-beta.4 sync)
Upstream `v1.0.0-beta.4` shipped no new Node.js SDK API surface relative to
`v1.0.0-beta.3` — every SDK-visible change in the upstream diff
(`ModelBilling.multiplier` optional, extension permission kinds,
`detachedFromSpawningParentSessionId`, advisor block fields on
`assistant.message`, `model` on `assistant.message`, model-picker categories,
`session.commands.respondToQueuedCommand`) was already brought in by the
earlier CLI 1.0.48 schema sync (PR #103), whose entries appear below.
`session.tasks.sendMessage` and the C#/Go-only changes from beta.4 are
deliberately out of scope per the API-parity rule (see Tracked-but-not-ported).
This release bumps the upstream marker from `1.0.0-beta.3` to `1.0.0-beta.4`.

### Added (v1.0.0-beta.4 sync)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.46` to
  `1.0.48` (the latest GA on npm).
  Generated wire specs and coercions regenerated.
- **`:remote-session` session config option** — `create-session` and
  `resume-session` accept an optional `:remote-session` key set to `:off`,
  `:export`, or `:on`, enabling per-session Mission Control remote mode at
  session-creation time without a separate `remote-enable` call. Forwarded to
  the wire as `remoteSession`. Reuses the `::remote-session-mode` spec.
  (upstream PR #1295, CLI 1.0.48)
- **`:copilot/session.custom_notification` event** — Skills (via the `Notify`
  block) can emit arbitrary application-level events to the SDK. The event
  exposes `:source`, `:name`, `:payload` (any JSON value), optional
  `:subject` (map of keyword→string), and optional `:version` (positive
  integer). Added to `sdk/event-types` and `sdk/session-events`; new idiom
  spec `::session.custom_notification-data`. The `:subject` and `:payload`
  fields contain source-defined identifiers and opaque JSON, so their keys
  are preserved verbatim by the protocol normalizer (no kebab-casing) —
  matching the existing escape hatch for `external_tool.requested`
  arguments. (upstream PR #1292, CLI 1.0.48)
- **Extension permission kinds** — `::permission-kind` accepts the new
  upstream values `:extension-management` and `:extension-permission-access`,
  emitted by the CLI for extension lifecycle and capability-access prompts.
  (upstream PR #1239, CLI 1.0.44-3)
- **`:detached-from-spawning-parent-session-id` on `session.start` events** —
  when a session continues another session's context (e.g., a detached
  headless rem-agent run launched on the parent's interactive shutdown),
  `session.start` now exposes the spawning parent's session id. Telemetry
  from such sessions is reported under the parent's `session_id`.
  Accepted by both the regenerated wire spec and the hand-curated
  `::specs/session.start-data`. (upstream PR #1239, CLI 1.0.44-3)
- **Anthropic advisor block fields on `assistant.message`** —
  `::assistant.message-data` now accepts the optional `:anthropic-advisor-blocks`
  (raw Anthropic content array with advisor blocks, for verbatim replay),
  `:anthropic-advisor-model`, and `:model` (model that produced the response,
  when known). The regenerated wire spec already exposed these; the
  hand-curated idiom spec now mirrors them. (upstream PR #1263, CLI 1.0.45)
- **`:model-picker-category` / `:model-picker-price-category` on `list-models`** —
  the regenerated `Model` shape carries upstream's new model-picker
  categorization fields (`"lightweight" | "versatile" | "powerful"` and
  `"low" | "medium" | "high" | "very_high"`). `parse-model-info` now
  surfaces both as idiomatic strings (open enum) on each entry returned by
  `copilot/list-models`. (upstream PR #1270, CLI 1.0.46)
- **`session/respond-to-queued-command!` (experimental)** — wraps the new
  `session.commands.respondToQueuedCommand` RPC for acknowledging
  `:copilot/command.queued` events. Accepts
  `{:request-id ... :handled? true/false :stop-processing-queue? bool?}` and
  forwards the wire shape
  `{:requestId ..., :result {:handled bool, :stopProcessingQueue bool?}}`.
  Marked experimental, mirroring upstream's exposure of this only via the
  generated low-level RPC. (upstream PR #1263, CLI 1.0.45)
- **`:is-autopilot-continuation` on `user.message` events** —
  `::user.message-data` now accepts the optional boolean flag emitted by
  autopilot's continuation loop. `true` indicates the message was
  auto-injected rather than typed by the user; used to distinguish
  autopilot-driven turns in telemetry. Wire key: `isAutopilotContinuation`
  → kebab-case key `:is-autopilot-continuation` (no `?` suffix —
  camel-snake-kebab does not append `?` for booleans). (upstream PR #1286,
  CLI 1.0.47)
- **`:api-endpoint` on `assistant.usage` events** —
  `::assistant.usage-data` now accepts the optional API endpoint string
  identifying which CAPI endpoint produced the model call. Known values:
  `"/chat/completions"`, `"/v1/messages"`, `"/responses"`, `"ws:/responses"`.
  Modeled as an open string spec for forward-compatibility; the regenerated
  wire spec enforces the closed enum. (upstream PR #1286, CLI 1.0.47)
- **`:mode` on `session/remote-enable` (experimental)** — the
  `session.remote.enable` RPC now accepts an optional `RemoteSessionMode`
  parameter. `remote-enable` gained a 2-arity overload `(remote-enable session
  opts)` where `opts` may contain `:mode` set to `:off`, `:export`, or `:on`.
  `:off` disables remote, `:export` exports session events to Mission Control
  without enabling remote steering, `:on` enables both. Zero-arg call is
  unchanged. New idiom specs: `::remote-session-mode` and
  `::remote-enable-opts`. (upstream PR #1288, CLI 1.0.48-1)
- **`:recurring` on `session.schedule_created` events** —
  `::session.schedule_created-data` now accepts the optional boolean flag
  indicating whether the schedule re-arms after each tick (`/every`) or fires
  once (`/after`). Wire key: `recurring` → kebab-case key `:recurring` (no
  `?` suffix — csk does not append `?` for booleans). (upstream PR #1288,
  CLI 1.0.48-1)

### Tracked-but-not-ported (v1.0.0-beta.4 sync)
- **`session.tasks.sendMessage` (experimental)** — the upstream Tasks API
  (`session.tasks.*`) is intentionally not surfaced in the Clojure SDK
  yet; the new `sendMessage` RPC is tracked here for a future port.
  (upstream PR #1239, CLI 1.0.44-3)
- **`UserToolSessionApproval` extension kinds** — upstream adds
  `extension-management` and `extension-permission-access` to the
  `UserToolSessionApproval` discriminated union. The Clojure idiom spec
  for `::approval` is intentionally broad (`map?`), so these payloads pass
  through unchanged; only the wire-side discriminator changed.
  (upstream PR #1263, CLI 1.0.45)
- **`WorkspacesGetWorkspaceResult.session_sync_level` removal** — upstream
  dropped this field. The Clojure SDK never surfaced it; no change needed.
  (upstream PR #1239, CLI 1.0.44-3)
- **`session.commands.list` / `session.commands.invoke` RPCs** — upstream
  added these slash-command discovery and invocation methods to the generated
  RPC layer in CLI 1.0.47. The Node.js SDK's public `CopilotSession` does
  NOT expose them as high-level methods (only via the low-level generated
  RPC), so per the API-parity rule the Clojure SDK does not surface them
  either. (upstream PR #1286, CLI 1.0.47)
- **`ModelBilling.tokenPrices`** — upstream added per-token pricing fields
  (`inputPrice`, `outputPrice`, `cachePrice`, `batchSize`) to `Model.billing`.
  The Clojure idiom spec for `::billing` is intentionally broad (open map),
  so these payloads pass through `list-models` unchanged. No dedicated idiom
  spec added yet. (upstream PR #1270, CLI 1.0.46)

## [1.0.0-beta.3.0] - 2026-05-12
### Changed (release tooling)
- **Version scheme — Maven qualifier support.** The release workflow,
  `script/release.sh`, and `build.clj` (`sync-version`, `bump-version`)
  now accept upstream versions carrying Maven pre-release qualifiers
  (e.g., `1.0.0-beta.3` in addition to `0.1.23`) and the corresponding
  4th-segment forms (e.g., `1.0.0-beta.3.0`, `1.0.0-beta.3.0-SNAPSHOT`).
  The full grammar is `X.Y.Z[-(alpha|beta|rc).M].N[-SNAPSHOT]`. Maven and
  tools.deps already sort these correctly; only our own validation regex
  was too strict.

### Added (v1.0.0-beta.3 sync)
- **`:enable-session-telemetry?` session config** — boolean. When omitted
  (default) or `true`, the CLI's internal session telemetry is enabled for
  GitHub-authenticated sessions. Set to `false` to disable. With a custom
  `:provider` (BYOK), session telemetry is always disabled regardless of this
  setting. Independent of the OpenTelemetry config in `:telemetry`. Accepted in
  both `create-session` and `resume-session`. Wire key: `enableSessionTelemetry`.
  (upstream PR #1224)
- **`:on-exit-plan-mode` session handler** — restores the Exit Plan Mode
  request RPC. When the SDK is configured with this handler, the CLI sends an
  `exitPlanMode.request` RPC asking the client to approve leaving plan mode.
  The handler receives `(request, {:session-id ...})` where `request` has
  `:summary`, optional `:plan-content`, `:actions` (vec of string), and
  `:recommended-action`. Returns an idiomatic map with `:approved?` (required
  boolean), optional `:selected-action`, `:feedback`. When omitted, the SDK
  auto-replies with the wire-shaped equivalent of `{:approved? true}` (i.e.,
  `{"approved": true}` on the wire) and sets the
  `requestExitPlanMode` capability flag to `false`. Accepted in both
  `create-session` and `resume-session`. (upstream PR #1228)
- **`:on-auto-mode-switch` session handler** — restores the Auto Mode Switch
  request RPC. When the SDK is configured with this handler, the CLI sends an
  `autoModeSwitch.request` RPC asking the client whether to switch the agent
  to auto mode after a rate-limit event. The handler receives
  `(request, {:session-id ...})` where `request` may include `:error-code`
  and `:retry-after-seconds`. Returns `:yes`, `:yes-always`, or `:no`
  (keyword or matching string), or a map `{:response ...}` with the same.
  When omitted, the SDK auto-replies with the wire-shaped equivalent of
  `:no` (i.e., `{"response": "no"}` on the wire) and sets the
  `requestAutoModeSwitch` capability flag to `false`. Accepted in both
  `create-session` and `resume-session`. (upstream PR #1228)
- **`AbortReason` wire enum** — `abort` events now carry a `:reason`
  field that is a closed enum of `"user_initiated"`, `"remote_command"`,
  `"user_abort"`. Validated by the regenerated wire spec. (upstream
  schema 1.0.44-2)
- **`subagent.started.model` field** — `subagent.started` events now expose
  an optional `:model` field identifying the model the sub-agent will run
  against. Both the regenerated wire spec and the hand-curated idiom spec
  (`::specs/subagent.started-data`) accept it. (upstream schema 1.0.44-2)
- **`session.remote.enable` / `session.remote.disable` (schema-only)** — the
  regenerated `schemas/api.schema.json` introduces two new experimental
  RPC methods for enabling/disabling remote session access, along with a
  `RemoteEnableResult` definition. **Not yet surfaced in the Clojure
  public API** — the schemas are tracked here so future ports can lift
  them without another schema bump. (upstream schema 1.0.44-2)

### Changed (v1.0.0-beta.3 sync)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.42` to
  `1.0.44-2`; generated wire specs and coercions regenerated.
- **MCP binary tool result mime-type fallback** — when an MCP tool returns a
  blob resource whose `:mime-type` is missing, the empty string, or any
  non-string value, the SDK now falls back to `"application/octet-stream"`
  (previously only `nil` triggered the fallback). Matches upstream Node.js
  behavior. (upstream PR #1222)

### Added (v1.0.0-beta.2 sync)
- **`:remote?` client option** — when `true`, the SDK appends `--remote` to
  the spawned CLI args so the headless CLI exposes its session over a
  GitHub-hosted remote endpoint. Ignored when `:cli-url` is set (i.e., when
  connecting to an externally-managed CLI). (upstream PR #1192)
- **`session/remote-enable`, `session/remote-disable` (experimental)** —
  enable/disable remote steerability for an active session via the new
  `session.remote.enable` / `session.remote.disable` JSON-RPC methods.
  `remote-enable` returns `{:url ... :remote-steerable boolean}`. (upstream
  PR #1192)
- **ProviderConfig overrides** — `:provider` config now accepts
  `:model-id`, `:wire-model`, `:max-input-tokens`, and `:max-output-tokens`
  alongside the existing `:base-url` / `:api-key` fields. `:max-input-tokens`
  is renamed to wire `maxPromptTokens` to match upstream's
  `toWireProviderConfig`. (upstream PR #966)
- **`session.schedule_created` / `session.schedule_cancelled` events** — the
  CLI now emits these events when the `/every` slash-command registers a
  scheduled prompt or it is cancelled from the schedule manager dialog; both
  are added to `event-types` and `session-events`. (upstream schema 1.0.42)
- **`mcpToolName` field on tool requests** — already-existing `:mcp-tool-name`
  on `AssistantMessageToolRequest` is now spec-validated by the regenerated
  wire layer. (upstream schema 1.0.42)

### Changed (v1.0.0-beta.2 sync)
- **Schema bump** — `.copilot-schema-version` advanced from `1.0.41-0` to
  `1.0.42`; generated wire specs and coercions regenerated.
- **`CustomAgentsUpdatedAgent.tools`** is now nilable (`tools: string[] | null`)
  on the wire; the regenerated wire spec accepts `nil`. (upstream schema
  1.0.41-1)

### Added (v1.0.0-beta.1 sync)
- **`:copilot-home` client option** — base directory for Copilot data files;
  forwarded to the spawned CLI as the `COPILOT_HOME` environment variable.
  (upstream PR #1191)
- **`:instruction-directories` session config** — additional directories to
  search for custom instruction files. Accepted in `create-session`,
  `resume-session`, and `join-session` configs and forwarded as
  `instructionDirectories` on the wire. (upstream PR #1190)
- **`:tcp-connection-token` client option** — connection token for the headless
  CLI server when running in TCP mode. When the SDK spawns its own CLI in TCP
  mode and the caller did not supply a token, a UUID is auto-generated so the
  loopback listener is safe by default. The token is sent to the CLI via the
  `COPILOT_CONNECTION_TOKEN` environment variable. Rejected when combined with
  `:use-stdio? true` (stdio is pre-authenticated by transport).
  (upstream PR #1176)
- **`connect` handshake** — the SDK now performs the protocol-version handshake
  via the new `connect` JSON-RPC method (carrying the optional connection
  token). Falls back to `ping` against legacy servers that respond with
  JSON-RPC `MethodNotFound` (-32601), or with a non-MethodNotFound code but
  the message `"Unhandled method connect"` (matching upstream Node parity,
  `client.ts:1132-1135`). (upstream PR #1176)
- **`:continue-pending-work?` resume/join session config** — when truthy, the
  CLI re-emits any in-flight `permission.requested` and external tool requests
  on resume so the consumer can respond, instead of treating them as
  interrupted. Forwarded as `continuePendingWork` on `session.resume`.

### Changed (v1.0.0-beta.1 sync)
- **Schema bump** — `.copilot-schema-version` advanced from `0.0.403` to
  `1.0.41-0`; generated wire specs (`src/github/copilot_sdk/generated/`) and
  field-level coercions regenerated. The upstream session-events schema now
  references each event variant via `$ref`; the codegen emitter (`script/`)
  was updated to dereference these refs when collecting leaf properties and
  emitting per-event data specs. (upstream PR #1184)

### Added (v0.3.0 sync)
- **Per-session GitHub authentication** — `:github-token` is now accepted in
  `create-session`, `<create-session`, `resume-session`, `<resume-session`, and
  `join-session` configs and is forwarded as `gitHubToken` on session
  create/resume RPCs. This enables one client to manage sessions authenticated
  as different GitHub users. (upstream PR #1124)
- **Stable v0.3.0 permission decision kinds** — `::permission-kind` now includes
  `:hook`, and `::permission-result-kind` accepts upstream decision kinds
  `:approve-once`, `:approve-for-session`, `:approve-for-location`, `:reject`,
  and `:user-not-available` in addition to `:no-result`. Legacy Clojure denial
  aliases remain accepted and are normalized before the SDK sends decisions to
  the CLI. (upstream PR #1124)

### Changed (v0.3.0 sync)
- **`approve-all` parity** — `copilot/approve-all` now returns
  `{:kind :approve-once}`, matching the upstream Node.js SDK `approveAll`
  helper. Existing legacy `:approved` permission results are still accepted
  from custom handlers and normalized to `:approve-once`. (upstream PR #1124)

### Added (codegen)
- **Schema-driven codegen pipeline** — new `bb codegen` task generates
  `src/github/copilot_sdk/generated/event_specs.clj` from the upstream
  `@github/copilot/schemas/session-events.schema.json`. Produces ~190 spec forms
  (one leaf spec per unique property, one `*-data` spec per event variant, one
  envelope spec per variant, and an aggregate `::event` spec).
- **Schema fetch task** — new `bb schemas:fetch` downloads the upstream npm
  package at the version pinned in `.copilot-schema-version` and extracts schema
  JSON files into `schemas/` (committed for reproducibility).
- **CI codegen-check workflow** — `.github/workflows/codegen-check.yml`
  regenerates on every PR touching schemas, generator, generated files, or the
  pinned version, and fails on drift between committed and regenerated output.
- **Developer documentation** — new `doc/codegen.md` explains the pipeline,
  workflows for local development, and the JSON Schema → `clojure.spec`
  translation rules.
- **Phase 3.5: three-tier wire/coerce/idiom architecture** — new
  `script/codegen/coercions.edn` (hand-curated event-scoped coercion table) and
  generated `src/github/copilot_sdk/generated/coerce.clj` (`event-wire->idiom`
  and `event-idiom->wire`). The runtime event dispatcher in `client.clj` now
  applies idiomatic coercion (e.g. ISO-8601 strings → `java.time.Instant`)
  before delivering events to user handlers. Coercion is fail-open: parse
  failures log a warning and deliver the uncoerced-but-normalized event so a
  malformed payload cannot kill the notification go-loop. The
  `hand-written-specs-agree-with-generated` drift audit now runs against
  coerced data with an empty `known-drifts` set, and three new invariants are
  enforced by tests (every coercion is exercised, converters are idempotent,
  round-trip is semantically lossless).
- **Historical event coercion** — `session/get-messages` now applies the same
  wire→idiom coercion pipeline as the live notification path, so `:start-time`
  on `session.start` events fetched from history is also delivered as a
  `java.time.Instant`. New integration test
  `test-get-messages-applies-coercion` enforces this.

### Changed (instrumentation)
- **Phase 6: instrument deduplication** — `src/github/copilot_sdk/instrument.clj`
  no longer maintains three parallel symbol lists (one `s/fdef` per public API
  fn, one symbol list passed to `instrument-all!`, one to `unstrument-all!`).
  A new private `register-fdef!` macro both delegates to `s/fdef` and records
  the fully-qualified symbol in a single `registered-fdefs` registry; both
  `instrument-all!` and `unstrument-all!` now derive their target list from
  that registry. The macro fail-fast rejects unqualified symbols at
  macroexpansion time, so a stale or alias-qualified entry is caught
  immediately rather than silently leaving an instrumentation gap. Net effect:
  ~162 lines removed from `instrument.clj`, no behavior change, and adding a
  new public API fn now requires a single edit instead of three.

### Fixed (codegen)
- **`session.start` event delivery** — `:selected-model` was being read with
  the wrong (camelCase) key `:selectedModel` in the runtime dispatcher; fixed
  to `:selected-model` to match the kebab-case keys produced by
  `util/wire->clj`.

### Changed (testing)
- **Mock server validates injected event types** — `mock/send-session-event!`
  now rejects unknown event types instead of silently emitting an
  unrecognised notification. Validation uses the SDK's canonical public
  `event-types` registry as the source of truth, so any event the SDK
  recognises can still be injected. A dedicated
  `mock/send-v3-broadcast-event!` helper restricts injection to the five
  protocol v3 broadcast events (kept in sync with
  `client/handle-v3-broadcast-event!`); the four v3 integration tests
  have migrated to it. Surfaces typos like `"session.startt"`
  immediately rather than as a confusing missing-event test failure.

## [0.3.0.0-SNAPSHOT] - 2026-04-23
### Added (v0.3.0-preview.0 sync)
- **`defaultAgent.excludedTools` session option** — new `:default-agent {:excluded-tools [...]}` config for create, resume, and join session paths. This hides selected tools from the built-in/default agent while preserving tool availability for custom agents. (upstream commit `b1b0df5c`)
- **Session FS provider adapter** — new `create-session-fs-adapter` helper adapts provider-style filesystem functions into structured `sessionFs.*` RPC results. Session factories now also auto-adapt provider-style maps while preserving the existing low-level one-arg handler contract. (upstream commit `a3e273c9`)
- **Generated event data specs** — added explicit specs for assistant reasoning/message/usage fields, MCP server status/load events, skills loaded events, and extension loaded events from the upstream generated schema.
- Integration tests for `defaultAgent` wire params, session FS adapter behavior, session FS factory auto-adaptation, extension status values, and event data specs.

### Fixed (v0.3.0-preview.0 sync)
- **Extension status enum parity** — `session.extensions_loaded` now accepts upstream statuses `"running"`, `"starting"`, `"disabled"`, and `"failed"` and rejects obsolete `"enabled"`.
- **Message content specs** — assistant/user message and reasoning event content remains string-only while elicitation result content accepts maps, matching upstream generated schemas.

## [0.2.2.0] - 2026-04-20
### Added (post-v0.2.2 sync, batch 2)
- **`includeSubAgentStreamingEvents` session option** — new boolean `:include-sub-agent-streaming-events?` on `::session-config`, `::resume-session-config`, and `::join-session-config`. When `true` (default), sub-agent streaming events are forwarded to the parent session's event stream. (upstream PR #1108)
- **Per-request HTTP headers on `send!`** — new `:request-headers` option (map of string→string) on `::send-options`. Forwarded as wire `requestHeaders` and merged with provider-level headers by the CLI. (upstream PR #1094)
- **Provider-level HTTP headers** — new `:headers` field (map of string→string) on `::provider` config. Sent with each model request to BYOK endpoints. (upstream PR #1094)
- **`::can-offer-session-approval`** spec — boolean field present on `permission.requested` events of kind `writeFile`, indicating the CLI can offer a "trust this session" choice. (CLI 1.0.28, upstream PR #1089)
- **`::reasoning-tokens`** spec — non-negative integer field on `assistant.usage` and `session.usage_info` events tracking tokens used for reasoning content. (CLI 1.0.32, upstream PR #1105)
- **`::agent-id`** spec — optional string field on `::base-event`, identifying which (sub-)agent emitted the event. (upstream PR #1108)
- Integration tests for all new wire fields and specs (6 new `deftest`s covering sync/async wire forwarding and the 3 new spec additions).

### Added (post-v0.2.2 sync)
- **`convert-mcp-call-tool-result`** — new public function in `tools` namespace that converts MCP `CallToolResult` format into the SDK's `ToolResultObject`. Handles text, image, and resource content types. (upstream PR #1049)
- **`default-join-session-permission-handler`** — new permission handler for `resume-session` that returns `{:kind :no-result}`, signaling the CLI to handle permissions itself. Sends `requestPermission: false` on the wire. (upstream PR #1056)
- **MCP config spec aliases** — `::mcp-stdio-server` and `::mcp-http-server` as aliases for `::mcp-local-server` and `::mcp-remote-server` respectively, matching upstream rename from Local→Stdio, Remote→HTTP. Old names kept for backward compatibility. (upstream PR #1051)
- **Per-agent skills field** — `::agent-skills` (vector of strings) on `::custom-agent` spec, allowing skill injection per custom agent. (upstream PR #995)
- **Memory permission event specs** — `::memory-action`, `::memory-direction`, `::memory-reason` specs for enriched memory permission request events. (CLI 1.0.22, upstream PR #1055)
- **New RPC wrappers** in `session` namespace (all experimental):
  - `session-name-get`, `session-name-set!` — get/set session display name (CLI 1.0.26, upstream PR #1076)
  - `workspace-get-workspace` — get current workspace metadata (CLI 1.0.26, upstream PR #1076)
  - `mcp-discover` — discover MCP servers in a working directory (CLI 1.0.22, upstream PR #1055)
  - `usage-get-metrics` — get session usage metrics (CLI 1.0.22, upstream PR #1055)
- Integration tests for all new features (18 tests covering convert-mcp-call-tool-result, spec renames, agent skills, requestPermission behavior, new RPCs, and memory specs)

### Changed (post-v0.2.2 sync)
- **`requestPermission` on resume** — `resume-session` now sends `requestPermission: false` when using `default-join-session-permission-handler`, and `true` when using any other handler (e.g., `approve-all`). Previously always sent `true`. (upstream PR #1056)

### Added (v0.2.2 sync)
- **`enableConfigDiscovery` session option** — new boolean `:enable-config-discovery` on session and resume configs. Auto-discovers `.mcp.json`, `.vscode/mcp.json`, skills, etc. Instruction files are always loaded regardless. (upstream PR #1044)
- **`modelCapabilities` override** — new `:model-capabilities` option on session config, resume config, and `switch-model!`/`set-model!`. Pass a partial capabilities map (e.g. `{:model-supports {:supports-vision true}}`) to override model capabilities for the session. (upstream PR #1029)
- **`history-truncate!`** — new experimental function to trigger manual truncation of session context (upstream PR #1039)
- **`sessions-fork!`** — new experimental function to fork the current session (upstream PR #1039)
- Integration tests for all new features (wire param verification, RPC routing)

### Changed (v0.2.2 sync)
- **`compaction-compact!` RPC renamed** — underlying JSON-RPC method changed from `session.compaction.compact` to `session.history.compact` (upstream PR #1039). The Clojure function name is unchanged for backward compatibility.

## [0.2.1.1] - 2026-04-04
### Added
- **Session RPC wrappers** — new experimental functions for session-level RPCs previously only accessible via `proto/send-request!`:
  - `mode-get`, `mode-set!` — get/set agent mode (interactive/plan/autopilot)
  - `plan-read`, `plan-update!`, `plan-delete!` — read/update/delete session plan file
  - `workspace-list-files`, `workspace-read-file`, `workspace-create-file!` — session workspace file operations
  - `agent-list`, `agent-get-current`, `agent-select!`, `agent-deselect!`, `agent-reload!` — custom agent management
  - `fleet-start!` — start parallel sub-sessions
- **MCP config wrappers** — new experimental server-level functions in `client`:
  - `mcp-config-list`, `mcp-config-add!`, `mcp-config-update!`, `mcp-config-remove!` — MCP server configuration management
- **Hooks integration tests** — 6 tests covering all hook types (preToolUse, postToolUse, sessionStart, unknownType, handler exceptions, no-hooks)
- **User input handler tests** — 2 tests for `userInput.request` server→client RPC
- **System message transform tests** — 3 tests for `systemMessage.transform` callback invocation, error fallback, and passthrough
- **Tool result normalization tests** — 3 tests for string, nil, and structured ToolResultObject results via v3 broadcast
- **Session RPC wrapper tests** — 18 integration tests for all new RPC wrapper functions
- **Mock server enhancements** — `send-rpc-request!` for testing server→client RPCs, response routing in server loop, 30+ new method stubs
- Full `s/fdef` instrumentation for all 19 new public functions

### Changed (v0.2.1 sync)
- **`session.error` event data spec enriched** — optional `:status-code` (int), `:provider-call-id` (string), and `:url` (string) fields added to `::session.error-data` spec. These fields carry HTTP status codes, GitHub request tracing IDs, and actionable URLs from upstream error events (upstream PR #999, runtime 1.0.17).

## [0.2.1.0] - 2026-04-04
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

[Unreleased]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v1.0.0-beta.3.0...HEAD
[1.0.0-beta.3.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.3.0.0-SNAPSHOT...v1.0.0-beta.3.0
[0.3.0.0-SNAPSHOT]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.2.0...v0.3.0.0-SNAPSHOT
[0.2.2.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.1.1...v0.2.2.0
[0.2.1.1]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.1.0...v0.2.1.1
[0.2.1.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.2.1.1-SNAPSHOT...v0.2.1.0
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
