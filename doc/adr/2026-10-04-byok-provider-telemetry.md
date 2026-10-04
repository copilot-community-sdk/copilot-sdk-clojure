# ADR: Separate BYOK configuration identity from runtime telemetry

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/ef04633cc84e4ba8e79888a39259ca276f5de732)

## Context

The stable Node `ProviderConfig.modelProvider` option identifies the product
serving a model for telemetry. Its input is a closed string union. The related
`AssistantUsageData` and `ModelCallFailureData` fields are optional strings
and can contain runtime-defined values outside that input union.

The already-supported experimental named-provider registry uses the same
selector. New tool replacement, structured progress metadata, Auto routing
explanations, and MCP prompt/OAuth operations remain experimental.

## Decision

Expose `:model-provider` as a closed keyword input on provider maps:
`:openai`, `:anthropic`, `:azure-openai`, `:ollama`, `:lm-studio`,
`:foundry-local`, or `:llama-cpp`. Share one provider serializer across singular
and named providers so create, resume, channel variants, and extension join
preserve the same wire contract. Convert hyphens to underscores only for this
value. Preserve omission and reject explicit `nil`; do not forward provider
configuration through mutable session options.

Maintain this shared selector on the existing named-provider registry without
changing its experimental status or adding new experimental operations.
Authentication callbacks and singular-only model overrides retain their
existing ownership and validation.

Keep event `:model-provider` and `:byok-kind` values as extensible strings,
including empty and future values. Register separate input and telemetry
specs rather than narrowing output to the configuration enum or accepting
strings and keywords interchangeably. Live and history paths keep identical
conversion. These fields describe telemetry, not model-selection authority.

Regenerate private wire specs and opaque-JSON paths from the exact runtime
schema. Preserve source-defined keys in experimental progress data without
adding it to curated stable specs. Keep experimental `setTools`, Auto routing
explanations, MCP prompts, and host-managed OAuth callback controls outside
the stable public surface. Internal tool-definition diagnostics remain
wire-only.

## Alternatives

Using wire-spelled keywords such as `:lm_studio` conflicts with the canonical
Clojure spelling. A shared closed enum for input and events would reject
legitimate telemetry such as `"other"` and future runtime values. Separate
provider serializers would duplicate the same translation and allow builder
drift.

## Consequences

The public snapshot gains three curated spec keys, without new functions or
fdefs. Existing builder fdefs pick up the nested validation. Tests cover every
keyword, omission, invalid values before transport, all builders and client
modes, event string domains, live/history conversion, and opaque progress
keys. Historical certification stays pinned to its original implementation.
This decision does not change the library version or authorize a release.
