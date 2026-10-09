# ADR: Preserve extensible identifiers and policy boundaries

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/341a526b26ccc170f6a1c6c5651b51f4ba9d3691)

## Context

The Node SDK makes Auto-tier identifiers extensible and adds managed-policy
controls, complete write-permission previews, provider provenance, accounting
identities, and transport diagnostics. New skill-provider, image-generation,
provider-quota, and host-catalog APIs remain experimental.

The broader event schema also makes a large generated object validator exceed
the existing source-size guard. Removing that guard would hide future JVM
method-size failures.

## Decision

Keep Auto-tier inputs and start/resume event values as keywords, rather than
adding a second string spelling. Preserve the complete identifier when encoding
it, including any keyword namespace, case, punctuation, and underscores. Reject
empty identifiers and whitespace/control characters. The runtime owns catalog
discovery, eligibility, persisted preferences, and routing; this SDK does not
choose a fallback tier or expose experimental live setters.

Forward managed-model enforcement and managed permission restrictions through
create, resume, and join. Keep omission distinct from false and empty rule
vectors. Reject explicit nulls and keep these fields out of mutable options
updates. The runtime validates and composes policy.

Expose complete write previews without reading the filesystem in the SDK.
An edit needs at least one non-null before/after snapshot, as required by the
documented public contract. Missing sides describe creation/deletion, whereas
an omitted preview means the runtime could not provide complete text.

Curate stable event metadata and preserve opaque dictionary keys on both live
and historical paths. Model-event provider IDs are non-null strings, distinct
from the nullable external-tool provider ID. Credit availability is explicit:
missing billing must never be interpreted as a free call. Internal accounting
snapshots and experimental provider references remain wire-only.

Reject event subscriptions after retirement. Capture one coherent session-I/O
snapshot and require the handle, session, and I/O registration tokens to match.
Register the tap and check source closure so teardown cannot leave
a late subscriber stranded. Failed admission closes the provisional channel;
unsubscription remains safe after teardown. Existing bounded response-wait
cancellation and failed-resume rollback remain unchanged.

Split oversized generated object validators into named, bounded parts.
Retain per-property predicates so explanations identify the failing field and
its expected form. Preserve required-key, closed-map, dictionary, negation,
non-conforming, and deterministic behavior without raising size limits.

## Consequences

Existing named Auto tiers remain compatible. New policies and event shapes gain
curated specs and contract tests without new public methods or experimental
authority. Provider-backed remembered grants require application-owned storage
isolation; they do not fall back to host consent.

The complete public-surface inventory is pinned separately from its historical
baseline. The library version remains unchanged; this decision does not
authorize a release, tag, or deployment.
