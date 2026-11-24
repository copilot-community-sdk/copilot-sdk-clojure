# Upstream Sync Reference

This reference supplements `AGENTS.md`, the canonical project reference.

## Authority Inventory

Inventory the complete official Node.js public surface at the selected exact
pin.

| Upstream source | Evidence |
|-----------------|----------|
| `nodejs/src/index.ts` | Package-root exports and supported public entry points |
| `nodejs/src/types.ts` | Public options, config maps, result types, enums, and nullability |
| `nodejs/src/client.ts` | `CopilotClient` construction, create/resume builders, and client methods |
| `nodejs/src/session.ts` | `CopilotSession` methods, lifecycle, event handling, and method builders |
| `nodejs/src/extension.ts` | Classify extension exports and exclude parent-process joins from native scope |
| `nodejs/src/toolSet.ts` | Public tool-filter helpers |
| `nodejs/src/workflow.ts` | Experimental orchestration contracts and the shared `JsonValue` export |
| `nodejs/src/host.ts`, `nodejs/src/installationConfirmation.ts` | Experimental runtime-host and installation-review exports; classify before exposing |
| `nodejs/test/`, when included in the snapshot | Stable unit and end-to-end behavior, especially omission and lifecycle semantics |
| `nodejs/src/generated/` | Wire signatures and event schemas; informative, not independently a stable parity requirement |

Treat Python as secondary corroboration. Treat CLI/runtime implementation as
wire evidence, not public API authority.

Source-only snapshots may omit upstream tests. Record that absence explicitly;
retain historical tests at their original pin rather than presenting them as
target-pin evidence. Prove new behavior with local real-protocol and runtime
tests, and inventory removed test paths alongside source deltas.

## Clojure Mapping

| Contract area | Clojure source |
|---------------|----------------|
| Package facade and public system-message section catalog | `src/github/copilot_sdk.clj`, `github.copilot-sdk.specs/system-message-sections` |
| Client/session ownership, admission and cleanup | `src/github/copilot_sdk/native_client.clj` |
| Pure create/resume/update/send wire builders | `src/github/copilot_sdk/session_options.clj` |
| Rust C ABI, native image, UI actions and callback quiescence | `src/github/copilot_sdk/native_host.clj` |
| Event callbacks and response encoding | `src/github/copilot_sdk/native_callbacks.clj`, `src/github/copilot_sdk/callback_results.clj` |
| Reverse calls, lifecycle hooks, credentials and virtual storage | `src/github/copilot_sdk/reverse.clj`, `native_interaction.clj`, `credentials.clj`, `native_fs.clj` in that directory |
| Model discovery and trace context | `src/github/copilot_sdk/discovery.clj` |
| Request-correlated turn completion and subscriptions | `src/github/copilot_sdk/turn.clj` |
| Source-qualified tool filters | `src/github/copilot_sdk/tool_set.clj` |
| Caller-facing shapes | `src/github/copilot_sdk/specs.clj` |
| Public function contracts | `src/github/copilot_sdk/instrument.clj` |
| Protocol and normalization | `src/github/copilot_sdk/protocol.clj`, `src/github/copilot_sdk/util.clj` |
| Generated wire validation and coercion | `src/github/copilot_sdk/generated/` |
| Versioned public compatibility snapshot | `resources/github/copilot_sdk/api_surface.edn` |

## Delta Classification

Assign exactly one classification before deciding whether to port a delta:

| Class | Action |
|-------|--------|
| Stable public | Port and prove the full Clojure contract |
| Experimental | Exclude unless explicitly approved; mark outside stable compatibility if ported |
| Internal | Do not expose |
| Generated-only | Use as wire evidence; do not infer public support |
| Language-specific | Skip unless the Clojure design has an independently justified counterpart |
| Transport exclusion | Do not add subprocess, TCP or parent-extension attachment. The host-to-model network or a configured MCP server is not an SDK transport. |

Classify by the affected surface, not the changed file type. Documentation and
tests for an experimental API are experimental; use internal only for non-public
implementation surfaces.

Follow referenced types through aliases and re-exports. A field on an otherwise
stable configuration can still reference an explicitly experimental type;
package-root re-export alone does not promote that subsystem to stable.
In 2.0.1 the startup permission-mode, sandbox, and diagnostics fields are
examples: their presence on typed create/resume options does not authorize
exposure of their experimental backing types. Keep them separate from passive
open/status data. The retained named BYOK catalog covers a fixed-credential
subset; it does not imply a rotating bearer provider.

When upstream retires an already-supported experimental wire surface, record a
migration or removal decision. Do not advance the runtime pin while leaving
unreachable wrappers, stale permissions, or incompatible execution callbacks.

Record intentional exclusions in durable evidence, docs, or an ADR.

## Stable Delta Proof

For every stable public delta, prove:

```text
Node export/type
  -> applicable native create/resume/method builder or callback owner
  -> exact wire shape and omission semantics
  -> Clojure name and idiomatic value
  -> closed spec + registered fdef + API snapshot
  -> targeted tests
  -> docs + examples + changelog
```

Test optional fields with a table containing the states that apply: absent,
`false`, `true`, empty, and `nil`. Omission and JSON `null` are different
contracts. Reject explicit `nil` when an optional upstream type is non-null.
Check that create/resume-only fields never appear in unrelated mutable
updates.

Public shapes are usually declared in several places: leaf specs, closed-key
sets, one or more `s/keys` lists, public sets, builders, and docstrings. Grep for
an analogous existing key and mirror every applicable declaration site. Missing
one can silently strip an option, leave it unenforced, or hide it from callers.

## Wire and Idiom Boundary

Convert camelCase and kebab-case once at the protocol boundary.

- Boolean conversion does not add a `?` suffix. Re-key caller-facing predicates
  deliberately.
- `wire->clj` transforms keyword keys; string-keyed fixture maps bypass that
  conversion.
- Mock-server requests are wire-shaped. Client assertions are idiomatic.
- Generated wire specs validate raw schema-faithful values and are never public
  API.
- Hand-curated idiom specs define Clojure-native values and should use closed
  maps where the upstream contract is exact.
- Event data specs remain open for forward-compatible pass-through unless there
  is a deliberate exact-map requirement. Add explicit field specs for public
  documentation and validation; do not close an event map merely because a new
  field was discovered.
- Generated coercion records deliberate wire/idiom differences.
- Opaque JSON needs recursive JSON specs and key-preservation tests, not a broad
  `map?`. Cover live notifications and historical response paths when both can
  carry the value.

Use the result encoders in `callback_results.clj` and `native_callbacks.clj` as the
reference for exact permission and tool reply shapes. Preserve source-defined
argument keys in both request and event paths.

## Evidence Maintenance

Prefer symbol-based evidence over source line ranges. A validated historical
oracle remains pinned to the source it actually inspected. When the target
moves, add a separate delta inventory from that oracle to the new exact pin.
Never replace only the old hash while retaining stale line or range claims.

Parity evidence should make all stable deltas and intentional exclusions
machine-readable and leave no unclassified rows.

For 2.0.1, `test/resources/native_2_0_1_contracts.edn` is the frozen source
inventory; `test/resources/native_2_0_1_rc_overlay.edn` maps the same contracts
to the candidate source, tests and outstanding gaps. Check the evidence revision
and gate for each claim. `test/resources/v1_provenance.edn` locates the older contract
tests and TCP benchmarks. Neither their counts nor performance results
transfer to the in-process client. For a new release, verify classification
and behavior using current source, source-classpath native tests, and a
standalone consumer of the built artifact on each platform claimed.

## Deterministic Outputs

Schemas, generated Clojure, API snapshots, and generated docs are outputs of
canonical pinned inputs. Follow the deterministic regeneration procedure in
the owning skill.
