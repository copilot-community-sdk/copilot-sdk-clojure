# ADR: Expose transcript recovery and bound owned-runtime disconnection

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/19e9a4b9c620d1032110cb6739961c4c1a651278),
  [model policy and lifecycle ADR](2026-09-30-model-policy-and-lifecycle-sync.md)

## Context

The stable Node SDK exposes recovery policy for transcript loads and a report
on resumed sessions. The same snapshots distinguish runtime process exit from
transport EOF: inherited pipes or independently surviving TCP peers can keep
a connection open after its owned runtime has died.

New AHP transport-selection, Connector-account, and managed-settings APIs in
these snapshots remain experimental or generated-only.

## Decision

Expose `:allow-transcript-recovery?` only on resume and extension join, including
channel-based resume. Preserve omission and explicit booleans, reject `nil`,
and leave the runtime's permissive default unchanged. Do not send the option
through create or mutable option updates.

Expose `transcript-recovery` in the session namespace and facade. Return `nil`
when the resume response has no report; otherwise preserve its planned backup
path and ordered physical line numbers, and normalize the predicate to
`:session-start-moved?`. The runtime owns transcript repair, backup creation,
resident-session reuse, and structured rejection errors. The SDK does not
inspect or rewrite storage.

Observe each owned process against its exact connection identity. When it
exits, allow stdio EOF up to ten seconds to deliver buffered messages. Then
reject unanswered RPCs and let the existing notification consumer drain
accepted messages before closing the session. TCP does not need an EOF grace
period. Input closure and notification acceptance share a short critical
section; response callbacks and user work remain outside that section.
Keep bounded waits off core.async dispatch threads.
Process exit also rejects unanswered RPCs during `stop!`; the stopping caller
retains teardown ownership, but cannot remain blocked on a dead runtime's
detach request.

Reject startup if the process has already died, even when a final startup
response arrived. Late exit or cleanup from an old generation cannot alter a
replacement connection. Do not kill external runtimes or unowned descendants.

Retain the existing experimental exclusions. Internal
`model.call_final_result` telemetry remains generated wire evidence, not a
curated public event.

## Alternatives

Defaulting the recovery option in the SDK would erase omission semantics.
Performing repair in the SDK would duplicate runtime persistence rules and
misrepresent when a backup exists. Waiting only for stream closure leaves
pending RPCs and session work alive after process death; closing stdio
immediately can discard buffered completion.

## Consequences

The public surface gains one accessor in two namespaces, two fdefs, and five
curated spec keys. Tests cover both client modes, each builder, omission and
invalid values, real damaged transcripts, surviving pipes and TCP peers,
startup failure, cache invalidation, and connection replacement.

Historical certification remains pinned. The new inventory classifies the
complete target surface and keeps experimental controls excluded. This
decision does not change the library version or authorize a release.
