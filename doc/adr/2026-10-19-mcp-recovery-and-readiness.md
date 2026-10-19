# ADR: Preserve MCP recovery and readiness contracts

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/b96cd2b54f7eb87458fd50f485a751c712e88f62)

## Context

The Node SDK lets an MCP OAuth handler delegate advertised, noninteractive
credential renewal to the runtime. New events distinguish authorization
recovery completion from accepting the host response, and MCP tool readiness
from connection status. Usage and failure events gain compression-fallback
diagnostics. Other additions remain experimental or internal.

## Decision

Use the canonical handler result `{:kind :runtime-refresh}` and send
`"runtimeRefresh"` only when the request has `:runtime-refresh-supported true`.
Preserve omission and explicit false in callback data. Explicit cancellation
takes precedence over a token; a nonempty string token takes precedence over
handoff. Preserve bare-token maps and existing optional-token-field omission.
Malformed results and handler exceptions are logged and cancel the host request.
The same callback path serves create, resume, join, and channel variants.

Keep renewal, grant storage, reconnect, and cancellation runtime-owned.
Handoff acceptance is not completion. Expose the persisted
`:copilot/mcp.oauth_recovery_completed` event with its request/server identity
and exact `"succeeded"`, `"cancelled"`, or `"failed"` outcome.
Expose ephemeral `:copilot/session.mcp_server_tools_loaded` with string server
name and the schema's integer tool count. Do not infer readiness from
`"connected"`, impose event ordering, or fabricate history for an ephemeral event.

Expose the three optional compression diagnostic fields on both usage and
failure events. Preserve string enums and the schema's integer status range
of 0-999. Omission is not zero; explicit `nil` is invalid. Keep payload maps
open for future fields and preserve live/history behavior without new coercion.

Retain the existing `:additional-directories` and `:enable-file-hooks` APIs.
Document startup directories as trust in repository commands and prompts,
including the containing Git root. Prove enabled/disabled and cold-resume
behavior with isolated repositories and a local model provider. Do not add
runtime discovery or authorization logic to the SDK.

Keep skill-provider supporting files, Azure ML model acquisition, catalog
category filtering, hosting discovery hints, and generated MCP configuration
observations outside the supported API. Internal provider-type telemetry and
the durable compression-disable marker remain wire-only.

## Consequences

The public snapshot adds event/spec membership and the existing OAuth
dispatcher's fdef, without new client or session operations. Complete
symbol-based evidence extends the historical certification at its original pin.
The target has no Node test tree; retained upstream tests remain historical,
while new behavior is checked through local protocol and real-runtime tests.

CLI compatibility and schema provenance advance to `1.0.100-1`. The library
version stays `1.0.16.0`; this decision does not cut a release, tag, or deployment.
