# ADR: Preserve session, hook, and compaction contracts

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/404304416ac5358651e3877a33bc9e8f4e482162)

## Context

The Node SDK adds session naming, explicit discovery roots, session capability
options, and callback context. Stable event changes describe compaction
outcomes, provider errors, and permission-recovery ownership. Separate
experimental exports and a larger internal RPC surface are not stable API
requirements.

## Decision

Expose creation-only `:name`, and shared `:agent-directories`,
`:hook-directories`, `:interactive-user?`, and `:enable-github-cache-store?`
on create, resume, and join, including channel variants. Preserve omitted,
false, and empty values. Reject explicit `nil` according to the public Node
types. Do not leak these options into unrelated mutable updates.

The runtime validates names and owns persistence. Named sessions can be
disconnected and cold-resumed without a model turn. Metadata publication is
not assumed to complete synchronously with the create response; do not add
SDK polling or fabricate catalog entries.

Explicit discovery roots do not grant filesystem access. Relative paths are
resolved against the request's working directory; retained roots do not move
with subsequent directory changes. Empty vectors clear only the SDK's explicit
contribution. File-hook roots are distinct from callback hooks and from
repository-origin hooks controlled by `:enable-file-hooks`.

Use `:agent-hooks` for custom-agent declarative actions. Preserve the established
agent-field prefix while sending `hooks` on the wire. Require exact string
event names and recursively string-keyed JSON action objects, reusing the
existing JSON specs. Do not introduce key aliases, keyword conversion, or
callback execution in this data surface.

Pass runtime-derived `:prompt-request` in the permission callback invocation
without rewriting the raw permission request or granting permissions. Preserve
hook actor metadata. Normalize only object errors of error-occurred and
session-end hooks: use a string message, otherwise the original object's JSON.
Preserve opaque keys at the protocol boundary before serialization.

Keep user-prompt blocking runtime-owned. A block refuses a prompt without a
model turn or durable user message; it is not an agent-stop continuation.

Expose stable compaction, provider-error, and permission-owner fields through
curated idiom specs and existing event readers. Preserve open outcome/reason
strings, non-negative counts, and omitted versus empty/zero values on live and
historical paths. Remove the retired `::factory-run-id` spec; use
`:workflow-run-id` without a compatibility alias. Event maps remain open for
historical and future pass-through fields.

Keep AHP unpublication, reasoning-summary presentation policy, tool-operation
billing, catalog/plugin installation, sandbox helpers, and internal settings
APIs excluded. Passive experimental metadata remains wire-only, including
opaque nested quota objects.

## Consequences

The public API snapshot records the new option/event specs, the removed legacy
spec, and the permission dispatcher's additional context arity and fdef.
No new permission, hosting, or billing authority is exposed.

The exact-pin inventory extends the historical oracle without retargeting it.
Source-only snapshots record the absent upstream test tree explicitly; retained
tests remain historical evidence, and new behavior is proved by local protocol
and real-runtime tests.

The CLI compatibility and schema pin advances to `1.0.99-2`. The library
version remains `1.0.16.0`; no release, tag, or deployment is authorized.
