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

### Clojure Counterparts

| Upstream Area | Clojure File | Notes |
|---------------|-------------|-------|
| Types / config | `specs.clj` | Session config, event data, permissions, tools |
| Client methods | `client.clj` | Broadcast handlers, create-client, create-session |
| Session methods | `session.clj` | send/receive, UI convenience methods |
| Event types | `client.clj` | `event-types` set, `subscribe-events!` |
| RPC methods | `protocol.clj` | JSON-RPC protocol layer |
| Public exports | `client.clj`, `helpers.clj`, `tools.clj` | Public API surface |
| Function specs | `instrument.clj` | fdefs for all public functions |

## Wire Conversion Cheat Sheet

camelCase → kebab-case conversion is handled by `util/wire->clj` and `util/clj->wire`.

Test a conversion: `(csk/->kebab-case-keyword :yourCamelCaseField)`

**Key rule**: camel-snake-kebab does **not** add a `?` suffix for booleans.
`resolvedByHook` → `:resolved-by-hook` (not `:resolved-by-hook?`).
