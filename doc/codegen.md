# Schema-Driven Code Generation

The Clojure SDK generates portions of its `clojure.spec` registry from the
upstream `@github/copilot` JSON Schemas instead of maintaining them by hand.
This guarantees the SDK's view of the wire protocol cannot drift from what the
Copilot CLI actually emits.

## What is generated

Source files under `src/github/copilot_sdk/generated/` are produced by the
generator in `script/codegen/`. Currently:

- `event_specs.clj` — `clojure.spec` definitions for every `SessionEvent`
  variant in `session-events.schema.json`. One spec per event variant's `data`
  payload (e.g. `::session.start-data`), one per envelope (e.g.
  `::session.start`), an aggregate `::event` spec, and an `event-types` set.

These files start with an `AUTO-GENERATED` banner. **Do not edit them by
hand** — your edits will be overwritten by the next `bb codegen` run.

## How it works

```
                                     bb codegen
schemas/session-events.schema.json ──► script/codegen/main.clj ──► src/github/copilot_sdk/generated/event_specs.clj
                       ▲
                       │ bb schemas:fetch
                       │
        npm registry: @github/copilot @ <pinned version>
                       ▲
                       │
              .copilot-schema-version
```

1. `.copilot-schema-version` records the pinned upstream version (e.g. `0.0.403`).
2. `bb schemas:fetch` downloads that exact version from the npm registry and
   extracts schema JSON files into `schemas/` (committed to the repo
   so builds are reproducible offline).
3. `bb codegen` reads `schemas/session-events.schema.json` and writes
   `src/github/copilot_sdk/generated/event_specs.clj`.
4. The CI workflow `.github/workflows/codegen-check.yml` regenerates on every
   PR and fails if the committed output differs.

## Workflows

### Local development

After editing the generator or bumping the pinned schema version:

```bash
bb codegen
git diff src/github/copilot_sdk/generated/   # review changes
bb test                                       # ensure no regressions
```

### Bumping the upstream schema version

```bash
echo "0.0.404" > .copilot-schema-version
bb schemas:fetch        # downloads the new version
bb codegen              # regenerate Clojure
git diff                # review the schema diff and the generated diff
bb test                 # full test suite
```

Commit `.copilot-schema-version`, `schemas/`, and the regenerated
files together.

### Detecting drift in CI

The `Codegen Check` workflow runs on every PR that touches `schemas/`,
`script/codegen/`, `src/github/copilot_sdk/generated/`, or
`.copilot-schema-version`. It runs `bb codegen` and fails if `git diff
src/github/copilot_sdk/generated/` is non-empty. It also asserts the generated
namespace loads cleanly.

## Translation rules

Currently supported JSON Schema → spec mappings:

| Schema construct      | Spec form                                  |
|-----------------------|--------------------------------------------|
| `string`              | `string?`                                  |
| `string` + `enum`     | `#{"a" "b" ...}` (set literal)             |
| `string` + `const`    | `#{"x"}`                                   |
| `integer`             | `integer?`                                 |
| `number`              | `number?`                                  |
| `boolean`             | `boolean?`                                 |
| `null`                | `nil?`                                     |
| `array`               | `(s/coll-of <items>)`                      |
| `object` w/ properties| `(s/keys :req-un [...] :opt-un [...])`     |
| `anyOf` (incl. `null`)| `(s/or ...)` or `(s/nilable ...)`          |
| `$ref`                | resolved (single-pass)                     |
| anything else         | `any?` (with a `WARN:` on stderr)          |

Wire keys (`sessionId`, `parentId`, ...) are converted to kebab-case
(`session-id`, `parent-id`) before being emitted as spec keywords. This matches
the convention enforced at runtime by `util/wire->clj`.

## Why generated specs are strict

Generated leaf specs are inferred from the upstream JSON types — for example,
`version` is `number?` because `session-events.schema.json` declares it as
`{"type": "number"}`, even though earlier hand-written specs incorrectly
treated it as `string?`. If you encounter a "looks correct but spec rejects"
case in tests, treat the generated spec as canonical and reconcile the test or
the calling code.

## Wire vs idiom: the three-tier architecture (IMPORTANT)

The Clojure SDK exposes a deliberately Clojure-idiomatic API: timestamps are
`java.time.Instant`, enums are keywords, sets are sets. The wire is just JSON
strings and numbers. The codegen pipeline does **not** flatten these — it
reinforces the separation:

```
WIRE SHAPE          ─►  COERCION         ─►  IDIOM SHAPE
(generated specs)       (generated +          (hand-curated specs)
ISO strings,            curated table)         Instants, keywords, sets,
raw enum strings,                              kebab-case maps
```

| Layer | Source of truth | Drift-proof? | Caller-facing? |
|---|---|---|---|
| Wire (`github.copilot-sdk.generated.event-specs`) | upstream JSON Schema (auto) | ✅ yes | ❌ never |
| Coercion (planned: `util.coerce`)                  | schema + `coercions.edn`     | ✅ yes | ❌ internal |
| Idiom (`github.copilot-sdk.specs`)                 | hand-curated, deliberately Clojure-native | curator-reviewed | ✅ yes |

### Policy for contributors

1. **Generated wire specs are NEVER re-exported as idiom specs.** They validate
   raw post-`wire->clj` shape — they exist to detect drift against upstream,
   not to replace the public API surface.

2. **The Clojure-idiomatic API is preserved by the hand-written specs in
   `specs.clj`.** Use `Instant` for timestamps, keywords for enums, sets where
   sets make sense. These are the contract callers see.

3. **Adding a richer idiom spec (Instant, keyword, set) requires a coercion
   entry.** CI will eventually enforce this — see Phase 3.5 in
   [`plan.md`](../.copilot/session-state/.../plan.md) (planned).

4. **Bumping the pinned schema version requires reviewing the diff in BOTH the
   generated specs and the coercion table.** New schema properties default to
   passthrough (wire == idiom). If you want a richer Clojure type for a new
   property, add a deliberate coercion entry.

5. **The cross-validation test (`test/github/copilot_sdk/codegen_test.clj`)
   guarantees consistency.** It validates wire fixtures against generated wire
   specs, then asserts post-coercion values validate against idiom specs.

This is what makes "schema-driven" compatible with "Clojure-native" — the
schema governs the wire, the coercion governs the bridge, and the curator
governs the API.

## What is NOT generated (yet)

- RPC method names and `*Params`/`*Result` specs. Upstream has not yet
  published `api.schema.json` to the `@github/copilot` npm artifact. When it
  becomes available, Phase 5 of the codegen plan will generate the RPC
  registry and `s/fdef`s for all wrappers.
- The wire-key registry in `util.clj`. The current camel↔kebab conversion via
  `camel-snake-kebab` is deterministic for every key in the events schema, so
  generating a static registry is unnecessary until non-roundtripping keys
  appear (likely with the API schema's snake_case fields).

## See also

- [`AGENTS.md`](../AGENTS.md) — guidelines for AI agents (regeneration workflow)
- [`JAVA_SDK_COMPARISON.md`](../JAVA_SDK_COMPARISON.md) — analysis of the Java SDK's
  codegen pipeline that motivated this approach
- [`script/codegen/`](../script/codegen/) — generator source
- [`.copilot-schema-version`](../.copilot-schema-version) — pinned upstream version
