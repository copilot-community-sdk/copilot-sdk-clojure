# Upstream Sync Reference

This reference supplements `AGENTS.md` (the canonical project reference) with sync-specific context.

For project structure, testing commands, version format, changelog conventions, and code quality expectations, see `AGENTS.md`.

## Upstream ↔ Clojure File Mapping

When syncing, map upstream changes to the corresponding Clojure files:

### Upstream (../copilot-sdk)

| Upstream File | Contains |
|---------------|----------|
| `nodejs/src/types.ts` | Canonical type definitions (`SessionConfig`, `MessageOptions`, etc.) |
| `nodejs/src/client.ts` | `CopilotClient` methods, what params go on the wire |
| `nodejs/src/session.ts` | `CopilotSession` methods, event handling |
| `nodejs/src/index.ts` | Public exports (defines the public API surface) |
| `nodejs/src/generated/session-events.ts` | All event types and data shapes |
| `nodejs/src/generated/rpc.ts` | RPC method signatures |
| `nodejs/src/toolSet.ts` | `ToolSet` / `BuiltInTools` tool-filter helpers |

### Clojure Counterparts

| Upstream Area | Clojure File | Notes |
|---------------|-------------|-------|
| Types / config | `specs.clj` | Session config, event data, permissions, tools |
| Client methods | `client.clj` | Broadcast handlers, create-client, create-session |
| Session methods | `session.clj` | send/receive, UI convenience methods |
| Curated public event sets | `copilot_sdk.clj` | Top-level `github.copilot-sdk` ns: hand-curated public `event-types` / `session-events` sets |
| Generated event specs | `generated/event_specs.clj` | AUTO-GENERATED full `event-types` set + wire specs (`bb codegen`) |
| RPC methods | `protocol.clj` | JSON-RPC protocol layer |
| Tool-filter helpers | `tool_set.clj` | Source-qualified `:available-tools` / `:excluded-tools` patterns |
| Public exports | `copilot_sdk.clj`, `client.clj`, `helpers.clj`, `tools.clj` | Public API surface |
| Function specs | `instrument.clj` | fdefs for all public functions |

## Wire Conversion Cheat Sheet

camelCase ↔ kebab-case is handled by `util/wire->clj` and `util/clj->wire` (camel-snake-kebab). Conversion happens **once**, at the protocol boundary.

Test a conversion: `(csk/->kebab-case-keyword :yourCamelCaseField)`

Mechanics worth remembering:

- **Booleans don't get a `?` suffix.** `resolvedByHook` → `:resolved-by-hook`, not `:resolved-by-hook?`. Code that wants `?`-suffixed keywords (e.g., `:approved?`) must re-key manually. csk preserves an existing `?` if you pass it in.
- **`wire->clj` only transforms keyword keys.** String-keyed maps pass through untouched. The mock server parses JSON with `:key-fn keyword` and does **not** apply `wire->clj`, so request-hook callbacks see camelCase keyword keys (e.g., `:remoteSession`).
- **Opaque user data must be preserved.** Source-defined identifiers (`tool.call` arguments, `session.custom_notification` `:subject`/`:payload`) bypass conversion via explicit escape hatches in `protocol/normalize-incoming`. When adding a new event with opaque fields, apply the escape hatch on both the notification path and the response path (for `session.getMessages`).
- **`handle-permission-request!`** in `session.clj` is the reference example for an RPC handler: returns idiomatic shapes and only manually re-keys what csk can't (`:approved?` → `:approved`).
