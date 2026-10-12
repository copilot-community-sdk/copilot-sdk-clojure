# ADR: Preserve transport diagnostics and opaque policy

- Status: Accepted
- Related: [upstream snapshot](https://github.com/github/copilot-sdk/commit/6be623b01b194a61466385e3f708639ec98ce8ac)

## Context

The Node SDK adds request compression and WebSocket-to-HTTP fallback metadata
to usage and model-call failure events. No client/session method, configuration,
or create/resume/join builder contract changes. New monthly quota readings and
managed permission evaluation belong to experimental subsystems.

## Decision

Expose the seven stable event fields through curated idiom specs. Preserve
their upstream string enums and boolean values without new aliases or
configuration options. Optional fields reject explicit null, preserve zero and
false, and retain the exact schema bounds: HTTP status codes are 0-999 and
WebSocket close codes are 0-4999. Event data remains open to future fields.

Keep serialized and transmitted body sizes distinct. Diagnostics describe the
attempt that produced the outcome; inherited fallback descriptors do not imply
a new failure on every call. The SDK does not infer compression ratios, retry
counts, or a cause when the runtime omits a value.

Regenerate wire validation and key-preservation metadata from the checksummed
CLI release. Preserve nested managed-policy JSON keys through live events and
historical response conversion without rebuilding runtime-owned policy.
Passive monthly quota data retains amounts, absence, and timestamps unchanged;
it is neither inferred session spend nor a new stable quota API.

Do not expose managed-browser policy evaluation, sandbox credential
suggestions, internal MCP registry/tool-selection RPCs, or new provider-account
APIs. The Node in-process native-runtime login change has no Clojure counterpart.
Existing experimental exclusions and supported Workflow contracts remain intact.

## Consequences

Consumers can distinguish request compression and newly initiated versus
inherited fallback without payload contents. The public API snapshot adds
spec keys, not methods or fdefs; existing event readers remain compatible.

The complete target inventory extends, rather than retargets, historical
certification. The CLI compatibility pin advances to `1.0.96-2`; the library
version remains `1.0.16.0`. No release, tag, or deployment is authorized.
