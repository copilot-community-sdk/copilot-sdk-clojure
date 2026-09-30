# ADR: Separate stable model policy from experimental host controls

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/a2b2c18eb5a20417fc613eaaa93199f55ad22ea4),
  [session configuration](../reference/API.md#create-session)

## Context

The Node SDK adds a stable `SessionConfigBase.allowedModels` field alongside
experimental AHP hosting, installation confirmation, sandbox provenance, and
read-only permission decisions. Package-root export visibility alone does not
make those other contracts stable.

The same snapshots change transport lifecycle behavior: owned stdio runtimes
finalize host telemetry after stdin EOF, parsed notifications must survive
remote EOF, and externally configured endpoints must remain usable on restart.

## Decision

Expose one `:allowed-models` spelling on create, resume, and join. Accept a
vector of strings, preserve omission separately from explicit empty vectors,
and reject `nil`. Forward IDs unchanged rather than applying model policy in
the SDK; the runtime owns allowlist validity and repository-policy intersection.
Do not add the experimental mutable model-policy setter.

Keep MCP failure provenance and classification as optional, non-null,
extensible strings. Preserve passive read-only permission result events without
adding the experimental permission decision that grants that authority.

Keep AHP hosting, installation confirmation, sandbox controls, and additional
Fusion capabilities excluded. Existing experimental Workflow operations retain
their established scope.

For graceful owned-process shutdown, signal stdin EOF only after a successful
shutdown RPC. Close stdin on one process-scoped worker while the independent
exit deadline bounds both the close and natural exit, then confirm termination
and join the worker.
External runtimes remain application-owned. The notification queue consumer
owns channel closure after remote EOF; explicit disconnect cancels a blocked
drain. Reader termination is fenced to its exact connection identity so stale
input cannot stop a replacement connection. Unexpected connection closure releases session resources but preserves
already-captured async completion for the result consumer; closing that result
channel is its abandonment path. Explicit local teardown cancels delivery.
Restore external endpoint state from validated client options on restart.

## Alternatives

Silently omitting empty allowlists would change their wire meaning. Rejecting
IDs based on a cached model catalogue would duplicate runtime policy. Exposing
all new package exports would introduce experimental authority and resource
ownership without a separate design decision.

Killing stdio immediately can truncate host telemetry. Leaving channel closure
with the reader discards notifications that its asynchronous consumer has not
yet delivered.

## Consequences

The public API gains three curated spec keys but no new functions or aliases.
Contract tests cover each session builder, omission and invalid values,
live/history metadata, real subprocess cleanup, TCP reconnect, and EOF
backpressure. Generated wire types remain private and schema-faithful.

Historical parity evidence stays pinned. The new exact-pin inventory records
the stable contracts and experimental exclusions separately. This decision
does not change the library version or authorize a release or deployment.
