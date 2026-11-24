# AGENTS.md

Guidelines for AI agents working on this codebase.

## Project Overview

This is the native Clojure port of the [Microsoft Copilot SDK](https://github.com/microsoft/copilot-sdk).
The 2.x source, tests and JAR use `github.copilot-sdk`. There is no separate
source profile or 1.x client on the runtime classpath.

### Design Philosophy

We do **NOT** blindly copy the upstream implementation. Instead, we create an idiomatic Clojure library that:

- Leverages Clojure language features and idioms (immutability, functional programming, state management and
   concurrency features)
- Would be natural to integrate in Clojure projects
- Would make sense to expert Clojure programmers
- Uses Clojure libraries appropriately, particularly:
  - **core.async** for asynchronous operations
  - **clojure.spec** for data validation and documentation
  - other high-quality libraries as needed.

### Upstream References

When porting features or investigating behavior:

1. **Primary reference**: [nodejs implementation](https://github.com/microsoft/copilot-sdk/tree/main/nodejs) (JavaScript/TypeScript)
2. **Secondary reference**: [python implementation](https://github.com/microsoft/copilot-sdk/tree/main/python) for additional clarity
3. **Local upstream checkout**: Resolve the upstream repo with
   `bash .github/skills/update-upstream/scripts/resolve-upstream.sh`. This
   works from normal checkouts and linked worktrees; set
   `COPILOT_SDK_UPSTREAM` when the checkout is not beside the primary
   `copilot-sdk-clojure` checkout.
4. **CLI runtime**: The CLI itself is useful for understanding
   protocol behavior, but the **SDK source of truth** is always the Node.js SDK, not the CLI protocol types.

### API Compatibility Rules

This SDK follows the stable public contracts of the official Node.js SDK
(`@github/copilot-sdk` in 1.x, `@microsoft/copilot-sdk` in 2.x). For the
native-only 2.x release, parity covers stable in-process capabilities, not
subprocess, TCP, or parent-extension transports. The Clojure 2.x client binds
the Rust C interface directly with generic JNA; do not use the Copilot Java
SDK, its internal stream host, or a parallel Java implementation.

1. **Only expose what the official SDK exposes.** The CLI protocol often defines more options than the SDK surfaces. Do NOT add config options or methods just because the CLI accepts them — only add them when the Node.js SDK's `SessionConfig`, `CopilotClient`, etc. include them.

2. **When adding features**, always verify against:
   - `copilot-sdk/nodejs/src/types.ts` — canonical type definitions (`SessionConfig`, `ResumeSessionConfig`, `MessageOptions`, `CopilotClientOptions`)
   - `copilot-sdk/nodejs/src/client.ts` — `CopilotClient` methods and what params are sent on the wire
   - `copilot-sdk/nodejs/src/session.ts` — `CopilotSession` methods

3. **Experimental features** require explicit approval and clear documentation.
   For example, the SDK supports named BYOK catalogs with fixed credentials,
   not the experimental rotating bearer provider. Startup permission mode,
   sandbox, and diagnostics have experimental backing types and remain deferred.
   Do not promote a generated RPC type to a public API by inference.

4. **Clojure-only additions** (convenience macros, core.async wrappers, internal tuning knobs) are fine
   as long as they don't conflict with the official API surface.

5. **Prefer one canonical Clojure spelling.** Add compatibility aliases only
   for demonstrated consumer need or an explicit compatibility policy. Prefer a
   documented breaking correction over a speculative shim with no removal path.

### Stable Upstream Recertification

A stable recertification is a complete public-surface inventory at an exact
upstream pin, not a scan of changed generated files.

1. Inventory package-root exports and public types, `CopilotClient` builders and
   methods, `CopilotSession` methods, extension/join exclusions, and relevant stable
   tests. Generated RPC and CLI sources are wire evidence, not independent
   parity requirements.
2. Classify every delta as stable public, experimental, internal,
   generated-only, language-specific, or an excluded transport.
   Experimental subsystems require an explicit maintainer decision, accepted
   ADR, or direct request.
3. Trace each stable in-process delta end to end: public export/type, applicable
   create/resume or method builder, exact wire and omission semantics,
   idiom spec/fdef/API snapshot, tests, docs, examples, and changelog.
4. Apply the optional-field proof matrix in
   `.github/skills/update-upstream/references/PROJECT.md`. Omission and JSON
   `null` are different contracts.
5. Keep validated historical evidence pinned to the source it describes. Add a
   symbol-based post-baseline inventory rather than changing only an old hash
   while retaining stale line or range claims.

Use `.github/skills/update-upstream/SKILL.md` for the complete workflow and
durable source mapping.

## Syncing with Upstream

Use `.github/skills/update-upstream/SKILL.md` to check or sync upstream
changes. At minimum:

1. Review merged PRs: https://github.com/microsoft/copilot-sdk/pulls?q=is%3Apr+is%3Aclosed
2. Compare with recent commits in this repository
3. For each upstream change, assess:
   - **Should it be ported?** (code changes yes, docs/CI/language-specific changes usually no)
   - **If yes**: Create a plan adapting it to Clojure idioms
   - **If no**: Document why (e.g., "Python-specific", "CI tooling only")

## Building and Testing

### Running Tests

```bash
# Hermetic unit, contract and framed protocol tests
bb test

# Use native-linux-x64 instead on glibc Linux x64.
bb test:native native-darwin-arm64
bb jar
bb test:artifact native-darwin-arm64
```

Native tests exercise the released classifier and local fixtures. The
artifact test starts a fresh consumer and resolves the built JAR's dependencies
from its POM; the application supplies the matching classifier. Neither gate
requires a Copilot account or a CLI executable. The CI matrix covers macOS
arm64 and glibc Linux x64, not every upstream platform.

Only the protected release dispatch runs authenticated acceptance. Absence of
`COPILOT_NATIVE_SMOKE_TOKEN` fails that gate; do not substitute a personal
account, skip the gate, or log credentials. A source-level test, a successful
ping, and an offline fixture do not establish live authentication.

### Instrumented Testing

All public API functions have `clojure.spec` fdefs defined in `instrument.clj`.
When `github.copilot-sdk.instrument` is required (which happens automatically during
integration tests), every public function call is validated against its spec at runtime.

Instrumentation checks call arguments. Test actual return values, channel
closure and failure behavior separately. When adding new public functions:

1. Add a `register-fdef!` form in `src/github/copilot_sdk/instrument.clj` (same syntax as
   `s/fdef`, but also records the symbol in a single registry so `instrument-all!` /
   `unstrument-all!` derive their target list automatically — no parallel symbol lists)
2. Ensure the corresponding specs exist in `src/github/copilot_sdk/specs.clj`
3. Run `bb test` — if specs are wrong, instrumented tests will catch it

### Public API Surface Guard

`resources/github/copilot_sdk/api_surface.edn` records the published namespaces'
qualified names, arglists, function/macro/value kind, fdefs, and curated spec
keys. The guard checks that Codox documents exactly those namespaces. Run `bb api-surface:update`
after an intentional change and review the diff. The surface snapshot does
not establish stable capability parity, safe resource ownership, or correct
runtime behavior. Do not expose the generated wire namespace, opaque handles'
constructors or raw protocol just to satisfy a surface count.

### Running Examples

Examples are part of the test suite but run separately:

```bash
# Full testing includes all examples working (REQUIRED before committing)
./run-all-examples.sh
```

This runs all Clojure examples.

In addition, all snippets and API descriptions in documentation *.md files
should be checked for validity.

### Building

```bash
# Build JAR
clj -T:build jar

# Install to local Maven repo
clj -T:build install
```

### Version Management

This project uses 4-segment versioning: `UPSTREAM.CLJ_PATCH`. The first three
segments track the upstream SDK; the fourth is Clojure-specific. For example,
`2.0.1.0-SNAPSHOT` is a development version targeting 2.0.1. The platform
runtime artifact uses the upstream SDK version, not the community version or
the runtime engine version.

```bash
# Keep the community version unpublished when syncing a development checkout.
clj -T:build sync-version :upstream '"2.0.1"' :snapshot true

# Bump the Clojure-specific development patch.
clj -T:build bump-version :snapshot true
```

## Commit and Deploy Guidelines

### Before Committing

1. Run `bb ci:full native-darwin-arm64` or `bb ci:full native-linux-x64` for
   the current host. If no tested classifier is available, run `bb ci`
   (hermetic tests, documentation validation, JAR) and report the missing gates.
2. For public surface or docs changes, regenerate and check the relevant
   outputs before running the gate. No SDK transport may depend on a CLI.
3. **Always ask for my review before committing** - do not commit autonomously.

### Deployment

- **Do NOT deploy without explicit permission** - the maintainer will deploy
- You may bump version numbers when needed (`clj -T:build bump-version`)

## Code Quality Expectations

### Emphasis on Rigor

This project emphasizes **rigor and correctness**, particularly for:

- **Async code** (core.async channels, go blocks)
- **Protocol/wire code** (framed JSON-RPC to the native runtime)
- **Native resource management** (upcall lifetime, close barriers and isolation between clients)

### Async and Lifecycle Invariants

- Every resource-owning API must define ownership, cancellation, backpressure,
  bounded blocking, and deterministic exactly-once cleanup.
- Never run arbitrary blocking or user-supplied work on `go` dispatch or a
  protocol reader thread. Use an explicit bounded execution facility.
- Resource-owning lazy or streaming helpers require an explicit scope and
  abandonment path; channel closure alone is not cancellation unless the API
  defines it that way.
- Preserve the primary failure across cleanup. Cleanup failures must remain
  observable without replacing the exception that caused teardown.
- Synchronize tests on observable state, requests, events, latches, futures, or
  channel closure. Fixed sleeps are not completion, ordering, or absence
  oracles.

### Spec Usage

- Use `clojure.spec` for data validation at API boundaries
- Runtime validation with helpful error messages
- Function specs (`s/fdef`) for public APIs in `instrument.clj`

### Testing

- Unit tests for pure functions
- Integration tests using the real protocol and deterministic mock server
- End-to-end tests against the pinned native runtime
- All examples serve as additional integration tests
- Prefer table-driven and property-based tests for contract state spaces
- Prefer real lightweight implementations over mocks of internal behavior
- Test important public behavior and interaction boundaries, not facts already
  guaranteed by the compiler or spec declaration alone
- Use the smallest targeted gate while iterating, then the established full
  repository gates before review

### Performance Evidence

Do not make cross-language parity or superiority claims from unmatched
microbenchmarks or live service calls. Matched public SDK paths, fresh process
pairs, predeclared analysis/abort rules, no trajectory-based selection, complete
raw provenance, and careful non-significance language are non-negotiable.
`benchmarks/README.md` and `benchmarks/METHODOLOGY.md` own the historical
protocol. The 1.x TCP benchmark results do **not** measure the native SDK.

## Project Structure

```
src/github/copilot_sdk.clj           # Public native Clojure facade
src/github/copilot_sdk/native_host.clj  # Verified library, core C functions, UI actions and callback barrier
src/github/copilot_sdk/native_client.clj # Owns clients, session handles and teardown
src/github/copilot_sdk/protocol.clj  # Framed, bidirectional in-memory JSON-RPC
src/github/copilot_sdk/turn.clj      # Native turn completion and event subscriptions
src/github/copilot_sdk/session_options.clj # Pure wire builders
src/github/copilot_sdk/native_callbacks.clj # Bounded broadcast callbacks and manual responses
src/github/copilot_sdk/reverse.clj   # One reverse dispatcher per native client
src/github/copilot_sdk/native_interaction.clj # Interactive requests and hooks
src/github/copilot_sdk/discovery.clj # Model and request trace providers
src/github/copilot_sdk/credentials.clj # Session token callbacks
src/github/copilot_sdk/native_fs.clj # Host-provided session filesystem operations
src/github/copilot_sdk/generated/    # Generated by bb codegen; do not edit

script/codegen/          # Babashka generator (build-time only; not in JAR)
schemas/       # Pinned upstream JSON Schemas (committed)
.copilot-schema-version  # Copilot CLI release pin used for schema provenance
```

See [`doc/codegen.md`](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/doc/codegen.md) for the schema-driven code generation
workflow.

### Wire vs idiom — the three-tier rule

The SDK keeps two distinct spec namespaces, on purpose:

- **Wire specs** (`github.copilot-sdk.generated.event-specs`, AUTO-GENERATED) —
  validate raw post-`wire->clj` payloads. ISO strings, raw enum strings. Drift-proof.
  **NEVER re-export these as the public API.**
- **Idiom specs** (`github.copilot-sdk.specs`, HAND-CURATED) — define the
  Clojure-native API: `java.time.Instant`, keywords, sets. This is what callers see.
- **Coercion** (`github.copilot-sdk.generated.coerce`, AUTO-GENERATED) bridges
  the two; the table is generated from a curated `script/codegen/coercions.edn`.

When the upstream schema changes, the wire layer is regenerated automatically
and CI fails the PR if generated output is out of date. The idiom layer only
moves under deliberate curator review. This is what makes the API both
schema-faithful at the wire AND idiomatic in Clojure.

Opaque JSON fields need recursive JSON validation and explicit tests for which
key paths are preserved versus normalized; a broad `map?` contract is not
enough. Treat schemas, generated Clojure, API snapshots, and generated docs as
outputs of canonical pinned inputs. Follow the owning skill's deterministic
regeneration gate when drift risk matters.

## Documentation

All these must be updated as appropriate when making changes:

- `README.md` - User-facing documentation and quick start
- `doc/index.md` - Documentation hub / table of contents
- `doc/reference/API.md` - Detailed API reference - read this to understand the current API design
- `doc/getting-started.md` - Step-by-step tutorial for new users
- `doc/style.md` - Documentation authoring style guide
- `doc/guides/native-2x.md` - Migration from the 1.x transports and example inventory
- `examples/README.md` - Runnable offline examples and their assertions
- `benchmarks/README.md` - Benchmark usage, evidence, and interpretation
- `benchmarks/METHODOLOGY.md` - Confirmatory study protocol
- `CHANGELOG.md` - Version history and changes (see below)
- `AGENTS.md` - update this file when significant changes happen (e.g. Project Structure)

Run `bb validate-docs` to check for broken links, unparseable code blocks, and structural issues.
Invoke the repo-local `update-docs` skill
(`.github/skills/update-docs/SKILL.md`) to regenerate docs after source changes.

Examples are executable contract evidence. Keep them portable, bounded,
fail-loud, and explicit about client, session, subscription, process, and
channel cleanup. Generated API HTML is never an editing surface: fix canonical
source/docstrings or Markdown, then regenerate it.

Keep historical ADRs and sealed 1.x evidence at their original revision.
Update current guides and link to the original for removed 1.x behavior;
do not maintain a second 1.x manual.

### Changelog

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/) conventions. When making changes:

1. **Always update the `[Unreleased]` section** — add entries under `Added`, `Changed`, `Fixed`, or `Removed` as appropriate.
2. **Group related entries** — use sub-headings like `### Added (CI/CD)` when a batch of changes belongs to a theme.
3. **Upstream sync annotations** — when porting changes from an upstream copilot-sdk release, annotate
   the sub-heading with the upstream version: `### Added (v0.1.24 sync)`. This groups ported changes by
   their upstream origin and makes it clear which upstream release they correspond to. Individual entries
   should also cite specific upstream PRs (e.g., "upstream PR #376"). Changes that are Clojure-specific
   (not ported from upstream) use other theme annotations like `(CI/CD)`, `(documentation)`, or no annotation.
4. **Mark breaking changes** with `**BREAKING**:` prefix.
5. On release, the maintainer moves `[Unreleased]` entries to a versioned section and updates comparison links at the bottom.
   The Clojure SDK version (`UPSTREAM.CLJ_PATCH`, e.g., `0.1.24.0`) encodes which upstream release is included,
   but the version identifies the target only. Certification requires contract and runtime evidence.
