---
applyTo: "**/*.md"
---

# Documentation Instructions

Follow [`doc/style.md`](../../doc/style.md) for prose, Clojure examples,
terminology, headings, and links. Follow
[`update-docs`](../skills/update-docs/SKILL.md) for the maintenance workflow.
Do not duplicate either source here.

## Contract Evidence

- Treat examples as executable API evidence; the update-docs skill owns the
  portability, boundedness, inventory, and cleanup checklist.
- Document stable public behavior and intentional experimental exclusions.
  Keep transient audit findings, local evidence paths, and session history out
  of evergreen documentation.

## Canonical Inputs and Generated Outputs

- Treat public source, specs, fdefs, examples, and hand-written Markdown as
  canonical inputs.
- Treat generated API HTML and other generated documentation as outputs.
  Regenerate them with the owning task; never hand-edit generated files.
- When source and generated documentation disagree, fix the canonical input and
  regenerate.

## Cross-References and Changelog

- Use relative links within the repository and section anchors for specific
  references.
- `AGENTS.md` is a symlink to `.github/copilot-instructions.md`. Links authored
  in that shared file must be full canonical GitHub URLs so they work from both
  paths.
- Update `doc/index.md` when documentation structure changes.
- Add each user-visible change to `[Unreleased]` in `CHANGELOG.md`, grouped
  under the appropriate Keep a Changelog category. Mark breaking changes
  explicitly and use upstream-sync annotations only for upstream ports.

## Validation

Run `bb validate-docs` after documentation changes. When examples or public
source changed, also run the owning executable example and repository gates
specified in `AGENTS.md`.
