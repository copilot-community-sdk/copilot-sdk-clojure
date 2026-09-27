# ADR: Migrate the existing orchestration API to Dynamic Workflows

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/d106d29dc6c5112da2abdae59008571b6692f12b),
  [migration guide](../guides/dynamic-workflows.md#migrating-from-agent-factories)

## Context

The upstream SDK removes Agent Factory exports, registration, reverse handlers,
and RPCs in favor of Dynamic Workflows. The Clojure SDK already supports the
experimental orchestration subsystem. Advancing the runtime pin while leaving
Factory calls unchanged would retain an API that the target runtime cannot run.

Stable parity does not require adopting every experimental Workflow feature.
The existing implementation can preserve its ownership, cancellation, journal,
JSON, and progress behavior while moving to the current protocol.

## Decision

Use one canonical `github.copilot-sdk.workflow` namespace, Workflow facade
names, and the join-only `:workflows` option. Rename request dispatch,
execution state, permissions, notifications, event subscriptions, specs,
fdefs, examples, and the public API snapshot together. Do not retain Factory
aliases or retry rejected calls through a legacy protocol.

Treat `:paused` as a settled execution attempt so observation does not wait
indefinitely after an external pause. Preserve `:factory-run-id` only on
inbound subagent events, where the upstream historical-event contract still
includes it alongside `:workflow-run-id`.

Keep declared limits non-null while preserving explicit `nil` invocation
overrides as JSON `null` (unlimited). Classify the current Workflow resume
error codes and preserve unknown errors unchanged.

Keep the migrated API experimental and limited to the existing operations.
Do not add `argsSchema` authoring, pause/checkpoint mutation APIs, new agent
options, or unrelated experimental authentication, Connector, diagnostics,
installation-management, and Fusion subsystems.

## Alternatives

Removing orchestration entirely would discard working Clojure functionality.
Compatibility aliases would retain two spellings without a demonstrated
consumer need or removal policy. Keeping the old runtime would block the
current stable sync. Canonical migration preserves the implementation while
making the break explicit.

## Consequences

Callers must update imports, facade names, join options, permission kinds, and
event names using the migration guide. The supported namespace inventory and
generated documentation must not retain the retired Factory API.

Historical certification artifacts keep their original pins and contents.
New evidence records the current stable surface, the deliberate experimental
migration, and remaining exclusions. This change does not alter the library
version, create a release or tag, or deploy an artifact.
