# Upstream Sync Reference

This reference supplements `AGENTS.md`, the canonical project reference.

## Authority Inventory

Inventory the complete official Node.js public surface at the selected exact
pin.

| Upstream source | Evidence |
|-----------------|----------|
| `nodejs/src/index.ts` | Package-root exports and supported public entry points |
| `nodejs/src/types.ts` | Public options, config maps, result types, enums, and nullability |
| `nodejs/src/client.ts` | `CopilotClient` construction, create/resume/join builders, and client methods |
| `nodejs/src/session.ts` | `CopilotSession` methods, lifecycle, event handling, and method builders |
| `nodejs/src/extension.ts` | Extension-facing public construction and session paths |
| `nodejs/src/toolSet.ts` | Public tool-filter helpers |
| `nodejs/test/` | Stable unit and end-to-end behavior, especially omission and lifecycle semantics |
| `nodejs/src/generated/` | Wire signatures and event schemas; informative, not independently a stable parity requirement |

Treat Python as secondary corroboration. Treat CLI/runtime implementation as
wire evidence, not public API authority.

## Clojure Mapping

| Contract area | Clojure source |
|---------------|----------------|
| Package facade and curated public values | `src/github/copilot_sdk.clj` |
| Client construction and session builders | `src/github/copilot_sdk/client.clj` |
| Session functions and lifecycle | `src/github/copilot_sdk/session.clj` |
| Public convenience helpers | `src/github/copilot_sdk/helpers.clj` |
| Tool helpers and filters | `src/github/copilot_sdk/tools.clj`, `src/github/copilot_sdk/tool_set.clj` |
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

Classify by the affected surface, not the changed file type. Documentation and
tests for an experimental API are experimental; use internal only for non-public
implementation surfaces.

Record intentional exclusions in durable evidence, docs, or an ADR.

## Stable Delta Proof

For every stable public delta, prove:

```text
Node export/type
  -> applicable create/resume/join/method builder
  -> exact wire shape and omission semantics
  -> Clojure name and idiomatic value
  -> closed spec + registered fdef + API snapshot
  -> targeted tests
  -> docs + examples + changelog
```

Test optional fields with a table containing the states that apply: absent,
`false`, `true`, empty, and `nil`. Omission and JSON `null` are different
contracts. Reject explicit `nil` when an optional upstream type is non-null.
Check that create/resume/join-only fields never appear in unrelated mutable
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

Use `handle-permission-request!` in `session.clj` as the reference for an RPC
handler that returns idiomatic shapes and re-keys only the predicate leaf that
requires it.

## Evidence Maintenance

Prefer symbol-based evidence over source line ranges. A validated historical
oracle remains pinned to the source it actually inspected. When the target
moves, add a separate delta inventory from that oracle to the new exact pin.
Never replace only the old hash while retaining stale line or range claims.

Parity evidence should make all stable deltas and intentional exclusions
machine-readable and leave no unclassified rows.

## Deterministic Outputs

Schemas, generated Clojure, API snapshots, and generated docs are outputs of
canonical pinned inputs. Follow the deterministic regeneration procedure in
the owning skill.
