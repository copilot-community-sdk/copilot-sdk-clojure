# Upstream Documentation Gap Matrix

This matrix maps every page in the upstream [`github/copilot-sdk`](https://github.com/github/copilot-sdk)
`docs/**` tree to its coverage in this Clojure SDK, with a per-topic decision. It exists so
GA reviewers (and future maintainers) can see, at a glance, which upstream topics are
ported, adapted, intentionally folded into the API reference, deferred, or not applicable.

**Decision legend**

- **Adapted** — covered by a dedicated Clojure doc page (Clojure-idiomatic rewrite, not a copy).
- **Folded** — the capability is documented, but inside [`reference/API.md`](reference/API.md)
  (config-table rows or a topical section) rather than a standalone page. The stable API is
  documented; a separate narrative page is post-GA polish.
- **Deferred (post-GA)** — host-architecture / conceptual guidance that does not document a
  stable SDK API surface. No GA blocker; tracked for a later docs pass.
- **N-A** — not applicable to the Clojure SDK (index pages, or other-language integrations).

GA gate: every **stable** public API is documented (Adapted or Folded). Deferred items are
narrative/architecture guidance only — none gates a stable API.

## auth/

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `auth/index.md` | Adapted | [`auth/index.md`](auth/index.md) |
| `auth/authenticate.md` | Adapted | [`auth/index.md`](auth/index.md) (auth methods + priority order) |
| `auth/byok.md` | Adapted | [`auth/byok.md`](auth/byok.md) |

## features/

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `features/index.md` | N-A | Index — see [`index.md`](index.md) Features section |
| `features/agent-loop.md` | Folded | Conceptual; reflected in API.md [Event Types](reference/API.md#event-types) + [Streaming](reference/API.md#streaming) |
| `features/streaming-events.md` | Folded | API.md [Streaming](reference/API.md#streaming) |
| `features/hooks.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) |
| `features/mcp.md` | Adapted | [`mcp/overview.md`](mcp/overview.md) |
| `features/custom-agents.md` | Adapted | [`guides/custom-agents.md`](guides/custom-agents.md) |
| `features/skills.md` | Folded | API.md [Config Directory and Skills](reference/API.md#config-directory-and-skills) + `:skill-directories`/`:enable-skills`/`:disabled-skills` |
| `features/image-input.md` | Folded | API.md [File Attachments](reference/API.md#file-attachments) / [Blob Attachments](reference/API.md#blob-attachments) |
| `features/session-persistence.md` | Folded | API.md `resume-session`, `list-sessions`, `get-session-metadata` |
| `features/steering-and-queueing.md` | Folded | API.md `send!` `:mode` (`:enqueue`/`:immediate`) |
| `features/plugin-directories.md` | Folded | API.md `:plugin-directories` config row |
| `features/remote-sessions.md` | Folded (experimental) | API.md `:remote?` / `:remote-session` config rows |
| `features/cloud-sessions.md` | Folded (experimental) | API.md `:cloud` config row |
| `features/fleet-mode.md` | Folded (experimental) | API.md [Experimental RPC Methods](reference/API.md#experimental-rpc-methods) (`session/fleet-start!`) |

## hooks/

Upstream splits hooks across six pages; the Clojure SDK consolidates them into a single
API.md section. The stable hook API (registration, pre/post-tool-use, lifecycle,
user-prompt-submitted, short-circuit semantics) is documented; per-hook narrative pages are
post-GA polish.

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `hooks/index.md` | N-A | Index |
| `hooks/hooks-overview.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) |
| `hooks/pre-tool-use.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) |
| `hooks/post-tool-use.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) |
| `hooks/user-prompt-submitted.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) |
| `hooks/session-lifecycle.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) + `on-lifecycle-event` |
| `hooks/error-handling.md` | Folded | API.md [Session Hooks](reference/API.md#session-hooks) + [Error Handling](reference/API.md#error-handling) |

## observability/

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `observability/index.md` | Folded | API.md [Observability](reference/API.md#observability) |
| `observability/opentelemetry.md` | Folded | API.md [Observability](reference/API.md#observability) (client `:telemetry`, `:on-get-trace-context`, session `:enable-session-telemetry?`) |

## setup/

Upstream setup pages are largely Node/host-deployment architecture guidance. The Clojure
SDK documents the stable setup surface (client options, auth) in the API reference and
getting-started; deep host-architecture guides are deferred.

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `setup/index.md` | N-A | Index |
| `setup/local-cli.md` | Folded | [`getting-started.md`](getting-started.md) + API.md `:cli-path` |
| `setup/bundled-cli.md` | N-A | Node-specific CLI bundling; the Clojure SDK uses an external CLI via `:cli-path` |
| `setup/github-oauth.md` | Adapted | [`auth/index.md`](auth/index.md) |
| `setup/azure-managed-identity.md` | Adapted | [`auth/azure-managed-identity.md`](auth/azure-managed-identity.md) |
| `setup/multi-tenancy.md` | Folded | API.md [Client Mode (Empty)](reference/API.md#client-mode-empty) |
| `setup/choosing-a-setup-path.md` | Deferred (post-GA) | Conceptual setup-selection guide |
| `setup/backend-services.md` | Deferred (post-GA) | Host-architecture guidance, not an SDK API |
| `setup/scaling.md` | Deferred (post-GA) | Host-architecture guidance, not an SDK API |

## troubleshooting/

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `troubleshooting/index.md` | N-A | Index |
| `troubleshooting/mcp-debugging.md` | Adapted | [`mcp/debugging.md`](mcp/debugging.md) |
| `troubleshooting/debugging.md` | Folded | [`mcp/debugging.md`](mcp/debugging.md) + `:log-level` client option |
| `troubleshooting/compatibility.md` | Deferred (post-GA) | CLI/JVM/Clojure compatibility matrix (Phase 0.75 follow-up) |

## integrations/

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `integrations/index.md` | N-A | Index |
| `integrations/microsoft-agent-framework.md` | N-A | .NET / Python Microsoft Agent Framework integration; not applicable to the Clojure SDK |

## Top-level

| Upstream page | Decision | Clojure coverage |
|---------------|----------|------------------|
| `index.md` | Adapted | [`index.md`](index.md) |
| `getting-started.md` | Adapted | [`getting-started.md`](getting-started.md) |
