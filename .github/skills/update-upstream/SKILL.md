---
name: update-upstream
description: Sync the Clojure Copilot SDK with upstream copilot-sdk changes. Runs update.sh, performs gap analysis against Node.js and Python SDKs, ports changes with red/green TDD, runs full CI (E2E tests + examples), gets parallel multi-model code reviews, updates docs, and creates a PR. Use when syncing with new upstream releases or checking for unported changes.
compatibility: Requires copilot CLI authenticated, gh CLI, clojure CLI, bb (babashka). Upstream repo at ../copilot-sdk.
---

# Update Upstream Skill

Sync the copilot-sdk-clojure project with upstream [github/copilot-sdk](https://github.com/github/copilot-sdk) changes. This skill codifies the full upstream sync workflow — from discovery through implementation, review, docs, and PR creation.

**Prerequisites**: Read `AGENTS.md` at the repo root for project structure, design philosophy, API compatibility rules, testing commands, and version management. Read `references/PROJECT.md` (relative to this skill) for upstream↔Clojure file mapping and wire conversion notes.

## Process

### Phase 1: Discovery

1. Run `./update.sh` from the repo root to pull the latest upstream and list releases.
2. Check the current Clojure SDK version in `build.clj` (format: `UPSTREAM.CLJ_PATCH` — see AGENTS.md § Version Management).
3. List upstream commits since our last synced version:
   ```
   cd ../copilot-sdk && git log --oneline <last-tag>..HEAD -- nodejs/
   ```
4. For each commit, classify:
   - **Port** — Code changes to `nodejs/src/` (types, client, session, generated)
   - **Skip** — CI/tooling, docs-only, language-specific (Python/Go/.NET only)

### Phase 2: Gap Analysis

Launch three parallel explore agents to build a comprehensive inventory. Use the file mapping in `references/PROJECT.md` to locate the right files.

1. **Node.js SDK** — Read upstream files listed in references/PROJECT.md (types.ts, client.ts, session.ts, index.ts, generated/). Catalog all public types, methods, event types, and event data fields.
2. **Python SDK** — Read `python/copilot/client.py`, `session.py`, `__init__.py`, `generated/`. Note behavioral differences from Node.js.
3. **Clojure SDK** — Read all `src/github/copilot_sdk/*.clj`. Catalog public functions, specs, event sets, wire conversion.

Compare inventories to identify gaps:
- Missing behavioral guards (e.g., `resolvedByHook`)
- Missing spec fields (new event data, new config options)
- Missing permission result kinds, event types
- API signature changes (e.g., handler arity)

### Phase 3: Planning

Create a structured plan in the session plan.md with:
- **Code gaps** — Behavioral changes needed (HIGH priority)
- **Spec gaps** — Missing fields/values (MEDIUM priority)
- **Doc gaps** — Documentation needing updates
- **Example gaps** — Missing examples for new features
- **Idiomatic review** — Areas to check for Clojure idiom adherence

Show the plan to the user. Wait for approval before implementing.

### Phase 4: Implementation (Red/Green TDD)

For each code/spec change:

1. **RED** — Write a failing test first in `test/github/copilot_sdk/integration_test.clj`
2. Run tests: `bb test` — confirm failure
3. **GREEN** — Implement the minimal change in `src/`
4. Run tests again — confirm all pass (0 failures)

See `references/PROJECT.md` for the upstream↔Clojure file mapping to know which source files to modify. See `AGENTS.md` § Instrumented Testing for the spec/fdef workflow when adding new public functions.

Wire format notes — see `references/PROJECT.md` § Wire Conversion Cheat Sheet. Key rule: camel-snake-kebab does **not** add `?` suffixes for booleans.

### Phase 5: Full Validation

Run the full CI pipeline (see `AGENTS.md` § Before Committing):

```bash
bb ci:full
```

This runs E2E tests, examples, doc validation, and JAR build. If copilot CLI is unavailable, run `bb ci` instead.

Carefully review example output for regressions.

### Phase 6: Multi-Model Code Review

Launch parallel code-review agents using at least three distinct model families (e.g., Claude, GPT, Gemini). Different model families catch different categories of issues — use whichever specific models are currently available.

Each reviewer gets the same context: what changed, why, and what to focus on (correctness, spec completeness, test coverage, Clojure idioms, wire conversion accuracy).

Compile a combined assessment table:

| # | Finding | Source | Severity | Validity | Decision |
|---|---------|--------|----------|----------|----------|

For each finding:
- **Valid + actionable** → Fix it, re-run tests
- **Valid + pre-existing** → Note as out of scope, track separately
- **Invalid / false positive** → Document rationale for dismissal

Iterate: fix → re-test → re-review until no actionable findings remain.

### Phase 7: Documentation

Invoke the `update-docs` skill (or do manually). See `AGENTS.md` § Documentation for the full list of doc files that may need updating.

At minimum:
1. Update `doc/reference/API.md` — new specs, event fields, behavior changes
2. Update `CHANGELOG.md` — entries under `[Unreleased]` (see `AGENTS.md` § Changelog for formatting)
3. Run `bb validate-docs`

### Phase 8: PR Creation

1. Create a feature branch: `git checkout -b upstream-sync/v<version>`
2. Commit with descriptive message and `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`
3. Push and create PR with `gh pr create`
4. PR body should include: summary, changes list, validation results, review findings table

### Phase 9: Skill Self-Review

After each upstream sync, review this skill itself for accuracy and relevance:

1. **Project structure** — Compare the file listing in `AGENTS.md` § Project Structure against the actual contents of `src/github/copilot_sdk/`. Flag any new, renamed, or removed source files.
2. **Upstream file mapping** — Compare the mapping table in `references/PROJECT.md` against the current upstream `nodejs/src/` directory. Flag new or removed upstream files.
3. **Common Pitfalls** — Check whether any pitfalls were encountered during this sync that aren't listed, or whether any listed pitfalls are no longer relevant (e.g., patterns that have been refactored away).
4. **Process phases** — Note any workflow steps that were awkward, missing, or unnecessary during this sync.

If any drift is found, describe your findings to the user and propose specific edits to `SKILL.md`, `references/PROJECT.md`, or `AGENTS.md`. **Do not commit** the skill updates — wait for the maintainer to review and approve them.

## Key Principles

1. **API parity with upstream Node.js SDK.** Only port what the official SDK exposes. Don't add CLI-only features unless clearly marked experimental. See `AGENTS.md` § API Compatibility Rules.

2. **Idiomatic Clojure, not a transliteration.** Use immutable data, core.async for events/concurrency, specs for validation, kebab-case keywords. See `AGENTS.md` § Design Philosophy.

3. **Red/green TDD is mandatory.** Never implement without a failing test first. This catches wire conversion bugs (camelCase → kebab-case) and spec mismatches early.

4. **Multi-model review catches different things.** Different model families tend to find different categories of issues — API contracts, logic/concurrency bugs, spec inconsistencies. Use at least three distinct families.

5. **Wire conversion is the #1 source of bugs.** Always verify camelCase → kebab-case conversion for new fields. See `references/PROJECT.md` § Wire Conversion Cheat Sheet.

6. **Event data specs use open maps.** `s/keys` allows extra keys, so new upstream fields pass through automatically. Add explicit specs for documentation and validation, not to gate functionality.

7. **Pre-existing issues are out of scope.** If a reviewer finds a real issue that wasn't introduced by this sync, note it but don't fix it in the sync PR. Track separately.

## Common Pitfalls

These are verified sources of real bugs in this codebase:

- **Boolean wire fields don't get `?` suffix.** camel-snake-kebab converts `resolvedByHook` → `:resolved-by-hook`, not `:resolved-by-hook?`. Code that manually maps wire booleans to `?`-suffixed keywords (like `:preview?`) must do so explicitly.
- **Forgetting `instrument.clj` allowlists.** Every new public function needs an `s/fdef` and entries in both `instrument-all!` and `unstrument-all!` lists. Integration tests run instrumented, so missing entries cause silent spec gaps.
- **Closed config key sets.** When adding session config options, update both `session-config-keys` and `resume-session-config-keys` in `specs.clj`. Missing a set causes the option to be silently stripped.
- **Mock data vs wire format.** Test fixtures should use wire-shaped data (camelCase) for mock server responses and Clojure-shaped data (kebab-case with `?` suffixes where applicable) for client-side assertions.
