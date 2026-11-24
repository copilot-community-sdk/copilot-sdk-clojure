---
name: update-docs
description: Use when public SDK behavior, examples, configuration, or documentation changes and the affected documentation must be updated and regenerated.
---

# Update SDK Documentation

Keep documentation aligned with canonical public source and executable examples.
Read `doc/style.md` and
`.github/instructions/documentation.instructions.md` before editing.

## 1. Identify the Contract Change

Compare the branch with current default and map changed public source, specs,
fdefs, examples, and hand-written docs to their consumers. Do not infer scope
only from generated output.

| Area | Canonical inputs | Documentation |
|------|------------------|---------------|
| Public overview/install | `build.clj`, `deps.edn`, public facade | `README.md`, `doc/getting-started.md` |
| Client, session and turns | `native_client.clj`, `turn.clj`, public facade | `doc/reference/API.md`, `doc/getting-started.md` |
| Native prerequisites | `native_host.clj`, `deps.edn`, `build.clj` | `README.md`, `doc/getting-started.md` |
| Callbacks, tools and filesystems | `native_callbacks.clj`, `callback_results.clj`, `reverse.clj`, `native_interaction.clj`, `native_fs.clj`, `tool_set.clj` | `doc/reference/API.md` |
| Specs/fdefs | `src/github/copilot_sdk/specs.clj`, `instrument.clj` | `doc/reference/API.md` |
| Session options, MCP and authentication | `session_options.clj`, `native_client.clj`, `credentials.clj`, `discovery.clj` | `doc/reference/API.md`, `doc/getting-started.md` |
| Session and lifecycle events | `turn.clj`, `native_client.clj`, generated wire specs and coercion | `doc/reference/API.md` |
| Examples | `examples/*.clj` | `README.md`, `examples/README.md`, related guides |

Read enough source and tests to establish actual defaults, omission semantics,
errors, lifecycle, and return values. Use a subagent only when the scope needs a
separate substantial context; do direct reads for small changes.

## 2. Update Canonical Documentation

- Lead with complete, parseable Clojure examples.
- Use the standard namespace aliases and include lifecycle cleanup.
- Use tables for option maps, including types, defaults, omission behavior, and
  experimental status.
- Document stable behavior and intentional experimental exclusions.
- Keep transient audit results, local paths, temporary failures, and session
  history out of evergreen docs.
- Update `doc/index.md` only when navigation or document structure changes.
- Update `examples/README.md` when the executable example inventory changes.
- For an unpublished checkout show `:local/root` and one exact runtime
  classifier. Do not point a 2.x quickstart at a published 1.x JAR or commit.
  Keep the community version marker in both installation guides; the version task updates it.
- Distinguish the release target, locally verified classifier, CI matrix,
  offline fixture, and authenticated release gate. Do not cite 1.x ADRs,
  certification or benchmark results as evidence for 2.x.
- Add a concise `[Unreleased]` changelog entry for user-visible changes.

## 3. Treat Examples as Executable Evidence

Examples must be:

- portable across supported environments;
- bounded rather than dependent on indefinite waits;
- fail-loud on invalid results;
- explicit about client, session, subscription, process, and channel cleanup;
- listed exactly once in the maintained example inventory.

Run `./run-all-examples.sh` (or supply one name) on a supported classifier.
The fixture supplies model traffic, a fresh home and an explicit tool policy.
Never use a real account to validate an offline example. Classify removed
recipes in `doc/guides/native-2x.md` and link to immutable historical source.

## 4. Regenerate Outputs

Generated API HTML and other generated documentation are outputs, not editing
surfaces. Fix the canonical source or Markdown, then run the owning generator.
Never hand-edit generated files.

Run `bb docs` after public docstrings or topics change. When removing a
namespace or topic, regenerate from a clean output directory so obsolete HTML
does not survive. Run the generator twice from identical inputs; compare the
full output (including new and deleted files), not just the working tree stat.
Keep the Codox namespace list in `deps.edn` explicit: the facade, specs,
instrumentation, and tool-set helpers. Do not publish raw protocol or generated
wire namespaces to fill documentation gaps.

## 5. Validate

Run:

```bash
bb validate-docs
```

For a distribution change also run `bb jar` followed by
`bb test:artifact native-darwin-arm64` or `bb test:artifact native-linux-x64`.
Use the full native gates from `AGENTS.md` before review. Review the final diff for stale links, duplicate
guidance, unparseable snippets, generated-file hand edits, and undocumented
stable or experimental behavior.
