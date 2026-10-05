# ADR: Preserve callback, filesystem, and event authority boundaries

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/6b4f3a3bde7eb9a8604617b91effe0f4a2e3921b)

## Context

The Node SDK adds stable subagent lifecycle callbacks, exact-byte filesystem
operations, partial-write error classification, and shell-output and
response-provenance events. It also normalizes string-schema `apply_patch`
overrides and cleans up cloud sessions whose local initialization fails.

Some generated additions remain experimental. A passive record of response
provenance is not permission to expose trusted-human response submission or to
classify an automated SDK callback as direct human authorization.

## Decision

Expose `:on-subagent-start` and `:on-subagent-stop` through the existing hook
dispatch facility on create, resume, and join. Preserve the Clojure hook
representation: Unix-millisecond `:timestamp`, `:cwd`, and parent session
metadata for lifecycle callbacks. Child tool hooks retain child session IDs.
Forward block/rewrite outputs without duplicating the runtime's precedence
rules. Callback execution remains outside protocol readers and `go` dispatch.

Use Java byte arrays for provider `:read-file-bytes` and `:write-file-bytes`.
Validate declared binary capabilities before adaptation can add unsupported
operation handlers. Preserve absent/false/true capability states and reject
explicit null flags. Encode standard canonical base64 at the adapter boundary
with the upstream 50,330,880-byte limit. Do not fall back to host files.

Represent `SessionFsWriteFailure` with `session-fs-write-failure` and a matching
predicate. Attach `:write-changed true` only to text-write errors. Awaited
provider failures retain their original classification, including failures
delivered through channels and futures.

Delete a newly created, server-assigned cloud session only if its local
initialization fails. Bound deletion to ten seconds, retain the setup fence
until cleanup finishes, and attach cleanup failures without replacing the
initialization error. Later setup failures and resumed sessions remain
detachable and resumable rather than being deleted.

Normalize only string-schema built-in `apply_patch` overrides at handler
registration. Both invocation paths share that handler; invocation metadata
retains the raw arguments. Other tools, object schemas, and declaration-only
tools retain their existing behavior.

Expose `tool.shell_output` and `human_response.recorded` as curated event types.
Shell output is append-only and live-only; do not synthesize historical chunks.
Observe response provenance without minting it. Generic SDK input callbacks do
not create trusted-human receipts. Retain exact JSON keys in response content
and form schemas, and preserve optional authored reasoning-effort ownership
metadata on start, resume, and model-change events.

Keep ordered reasoning blocks and file-edit metadata experimental and
wire-only. Regenerate opaque paths and object-negation constraints from the
pinned schema. Provider discovery, trusted-human submission, proxy-CA controls,
and other generated experimental operations remain excluded.

## Alternatives

Binary strings would conflate text encoding with file bytes. Checking
capabilities after adding unsupported-operation handlers would incorrectly
advertise a usable provider. Deleting every failed session would destroy
resumable state; detaching an uninitialized cloud allocation would leak it.
Synthesizing response receipts in SDK callbacks would cross an authority
boundary that belongs to the runtime.

## Consequences

The API snapshot gains the two filesystem error helpers and their fdefs in the
facade and session namespaces, plus curated hook/event specs. Tests cover
builders, exact binary boundaries, invalid input, async errors, cleanup
ownership and timeout, and live/history fidelity. Real-runtime coverage checks
ephemeral shell output and the absence of human receipts for automated input.
The library version remains unchanged; this decision does not authorize a
release.
