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
| Helpers | `src/github/copilot_sdk/helpers.clj`, specs, fdefs | `doc/reference/API.md`, `doc/getting-started.md` |
| Client | `src/github/copilot_sdk/client.clj`, specs, fdefs | `doc/reference/API.md` |
| Session | `src/github/copilot_sdk/session.clj`, specs, fdefs | `doc/reference/API.md` |
| Tools | `src/github/copilot_sdk/tools.clj`, `tool_set.clj`, specs, fdefs | `doc/reference/API.md` |
| Specs/fdefs | `src/github/copilot_sdk/specs.clj`, `instrument.clj` | `doc/reference/API.md` |
| MCP | `src/github/copilot_sdk/util.clj`, `client.clj`, specs | `doc/mcp/overview.md`, `doc/mcp/debugging.md` |
| Authentication | `src/github/copilot_sdk/client.clj`, specs | `doc/auth/` |
| Events | public event sets, idiom specs, generated wire specs | `doc/reference/API.md` |
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
- Run `bb install-docs:sha` when the documented installation SHA moves, and
  review the resulting `README.md` and getting-started changes.
- Add a concise `[Unreleased]` changelog entry for user-visible changes.

## 3. Treat Examples as Executable Evidence

Examples must be:

- portable across supported environments;
- bounded rather than dependent on indefinite waits;
- fail-loud on invalid results;
- explicit about client, session, subscription, process, and channel cleanup;
- listed exactly once in the maintained example inventory.

Run affected examples. Do not convert a failing contract example into
pseudocode.

## 4. Regenerate Outputs

Generated API HTML and other generated documentation are outputs, not editing
surfaces. Fix the canonical source or Markdown, then run the owning generator.
Never hand-edit generated files.

Run `bb docs` after public docstrings change. When generator drift matters, run
the generator twice from identical inputs and require the second run to produce
no diff.

## 5. Validate

Run:

```bash
bb validate-docs
```

Also run the smallest affected executable examples and repository gates
required by `AGENTS.md`. Review the final diff for stale links, duplicate
guidance, unparseable snippets, generated-file hand edits, and undocumented
stable or experimental behavior.
