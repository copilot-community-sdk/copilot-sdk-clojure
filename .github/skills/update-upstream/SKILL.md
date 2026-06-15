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

1. **Sync local `main` first.** Recently-merged PRs may have already
   ported some upstream changes, and your feature branch should sit on
   top of the latest `main` to avoid duplicate work and rebase conflicts
   later:
   ```
   git checkout main && git pull --ff-only origin main
   ```
   If `main` cannot fast-forward, stop and let the maintainer resolve.
2. Run `./update.sh` from the repo root to pull the latest upstream and list releases.
3. Check the current Clojure SDK version in `build.clj` (format: `UPSTREAM.CLJ_PATCH` — see AGENTS.md § Version Management).
4. List upstream commits since our last synced version:
   ```
   cd ../copilot-sdk && git log --oneline <last-tag>..HEAD -- nodejs/
   ```
5. For each commit, classify:
   - **Port** — Code changes to `nodejs/src/` (types, client, session, generated)
   - **Skip** — CI/tooling, language-specific (Python/Go/.NET only)

### Phase 2: Gap Analysis

Launch three parallel explore agents to build a comprehensive inventory. Use the file mapping in `references/PROJECT.md` to locate the right files.

1. **Node.js SDK** — Read upstream files listed in references/PROJECT.md (types.ts, client.ts, session.ts, index.ts, generated/). Catalog all public types, methods, event types, and event data fields.
2. **Python SDK** — Read `python/copilot/client.py`, `session.py`, `__init__.py`, `generated/`. Note behavioral differences from Node.js.
3. **Clojure SDK** — Read all `src/github/copilot_sdk/*.clj`. Catalog public functions, specs, event sets, wire conversion.

Compare inventories to identify gaps:
- New or changed public functions, types, or method signatures
- New event types and event data fields
- New config options or enum values
- Behavioral changes — new guards, fields that gate logic, or shape changes to existing data

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
2. Run tests: `bb test` — confirm failure (it's OK to just run the tests you added as you iterate)
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

Launch parallel code-review agents using two distinct model families (e.g., Claude Opus 4.7 and GPT-5.5 — or later if available). Different model families catch different categories of issues; use whichever specific models are currently available.

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

Invoke the `update-docs` skill. See `AGENTS.md` § Documentation for the full list of doc files that may need updating.

At minimum:
1. Update `doc/reference/API.md` — new specs, event fields, behavior changes
2. Update `CHANGELOG.md` — entries under `[Unreleased]` (see `AGENTS.md` § Changelog for formatting)
3. Run `bb validate-docs`

### Phase 8: PR Creation

1. **Confirm `main` is current before branching.** Run
   `git fetch origin main && git checkout main && git pull --ff-only` if
   you haven't refreshed since Phase 1. A stale local `main` causes
   rebase conflicts later, especially when prior sync PRs squash-merge.
2. Create a feature branch: `git checkout -b upstream-sync/v<version>`
3. Commit changes in logical commits to make them easy to review commit by commit and with descriptive message and `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`
4. Push and create PR with `gh pr create`
5. PR body should include: summary, changes list, validation results, review findings table

If the maintainer asks you to rebase a stale branch onto fresh `main`,
expect that previous round-N sync commits on your branch may already be
present in `origin/main` under squash-merge SHAs. Use `git rebase --skip`
for any commit whose patch is already upstream — Git will print
"patch contents already upstream" for the others and drop them
automatically.

### Phase 9: Reflecting on code review feedback.

Once the PR is created, Copilot Code Review (and possibly humans) will provide code review feedback.

run: /pr auto Consider Copilot Code Review feedback. For each piece of feedback, determine if its valid and important, or invalid (false positive) and/or not important. For all valid feedback, address it. For each piece of feedback (valid or not), comment in the thread for that particular feedback comment and explain how you addressed the feedback or your rationale for not addressing, or a suggestion for addressing in the future (create issues for future/follow up). Once all feedback is addressed/commented, rerequest a review, and continue iterating this processes until there is no more code review feedback - you should keep iterating this process until you see a review from Copilot Code Review which generates no comments, but no more than 10 rounds (to avoid exploding costs). If more than 10 rounds is needed, prompt the users asking what to do next.

### Phase 10: Skill Self-Review

After each upstream sync, review this skill itself for accuracy and relevance:

1. **Project structure** — Compare the file listing in `AGENTS.md` § Project Structure against the actual contents of `src/github/copilot_sdk/`. Flag any new, renamed, or removed source files.
2. **Upstream file mapping** — Compare the mapping table in `references/PROJECT.md` against the current upstream `nodejs/src/` directory. Flag new or removed upstream files.
3. **Common Pitfalls** — Only propose a new pitfall if it generalizes to a *class* of recurring bug. Single-occurrence specifics belong in commit messages or PR descriptions, not here. Also flag listed pitfalls that no longer apply (e.g., refactored away).
4. **Process phases** — Note any workflow steps that were awkward, missing, or unnecessary during this sync.

If any drift is found, describe your findings to the user and propose specific edits to `SKILL.md`, `references/PROJECT.md`, or `AGENTS.md`. **Do not commit** the skill updates — wait for the maintainer to review and approve them.

## Key Principles

1. **API parity with upstream Node.js SDK.** Only port what the official SDK exposes. Don't add CLI-only features unless clearly marked experimental. See `AGENTS.md` § API Compatibility Rules.

2. **Idiomatic Clojure, not a transliteration.** Use immutable data, core.async for events/concurrency, specs for validation, kebab-case keywords. See `AGENTS.md` § Design Philosophy.

3. **Red/green TDD is mandatory.** Never implement without a failing test first. This catches wire conversion bugs (camelCase → kebab-case) and spec mismatches early.

4. **Multi-model review catches different things.** Different model families tend to find different categories of issues — API contracts, logic/concurrency bugs, spec inconsistencies. Use at least two distinct families.

5. **Wire conversion is the #1 source of bugs.** Always verify camelCase → kebab-case conversion for new fields. See `references/PROJECT.md` § Wire Conversion Cheat Sheet.

6. **Event data specs use open maps.** `s/keys` allows extra keys, so new upstream fields pass through automatically. Add explicit specs for documentation and validation, not to gate functionality.

7. **Pre-existing issues are out of scope.** If a reviewer finds a real issue that wasn't introduced by this sync, note it but don't fix it in the sync PR. Track separately.

## Common Pitfalls

Real recurring traps when porting upstream changes:

1. **Data shapes are declared in multiple sites — find them all.** A new config option, event type, or permission kind typically lives in 3–5 places: a value spec, a closed-keys set, one or more `:opt-un` lists, a public set, and a docstring. Missing any one site causes the option to be silently stripped, the spec to go unenforced, or the value to be invisible to consumers. Grep for an existing analogous key and mirror every site.

2. **Wire conversion happens once, at the boundary; don't convert again.** Inbound responses are normalized by `util/wire->clj` in `protocol/normalize-incoming`; outbound results are converted by `util/clj->wire`. RPC handlers, specs, tests, and event consumers work in idiomatic shapes (kebab-case). Escape hatches exist for opaque source-defined data (tool call arguments, custom-notification subject/payload) — these must be applied to **both** the notification path and the response path if the field is reachable via `session.getMessages` (historical events go through responses).

3. **Public functions need an fdef registered in `instrument.clj`.** Use the `register-fdef!` macro — a plain `s/fdef` or one outside that file leaves a silent instrumentation gap, and integration tests (which run instrumented) won't enforce the spec.

4. **Test fixtures: match the shape the layer actually produces.** Mock server responses use wire shape; client-side assertions use idiomatic shape. Production conversion (`util/wire->clj`) only transforms keyword keys — string-keyed fixtures silently bypass it and produce tests that pass for the wrong reason.

5. **Outbound optional fields: match upstream's omit-vs-null behavior per RPC.** Don't reflexively gate an optional wire field on `contains?` and emit JSON `null`. Some patch RPCs (e.g. `session.options.update`) genuinely accept `null` to clear a value; others (e.g. `session.model.switchTo`) have no null variant in the schema — upstream spreads `...options`, dropping `undefined`. Check the upstream call site and the request schema: if the field has no null union, compute the wire value first and gate on `(some? v)` so a `nil` option omits the key instead of sending `null`.

6. **`session.create` and `session.resume` build wire params in two separate functions — keep shared sub-shapes in a named helper.** `build-create-session-params` and `build-resume-session-params` both emit tool defs, system message, provider, MCP servers, custom agents, and commands. A new field on any shape sent by both must be added to both builders, or it ships on create and silently vanishes on resume. Funnel each shared sub-shape through one `*->wire` helper (e.g. `tool-def->wire`, `util/mcp-servers->wire`) rather than duplicating a `cond->` inline.

For the mechanics of camelCase ↔ kebab-case conversion (including the `?`-suffix rule), see the cheat sheet in `references/PROJECT.md`.
