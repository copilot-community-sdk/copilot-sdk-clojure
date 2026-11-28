# Documentation Style Guide

Conventions for writing and maintaining documentation in the copilot-sdk-clojure project.

## Principles

1. **Code-first** — Lead with working Clojure examples, then explain
2. **Progressive disclosure** — Simplest usage first, layer complexity
3. **Direct tone** — Imperative mood, no filler ("simply", "just", "let's")
4. **Clojure-idiomatic** — Use Clojure terminology and conventions

## Document Structure

Every doc file follows this structure:

```markdown
# Title

One-line description of what this page covers.

## Section

Short paragraphs (1–3 sentences). Use code blocks liberally.
```

**Headings:**
- `#` for the page title (one per file)
- `##` for major sections
- `###` for subsections — avoid going deeper than `###`

## Code Examples

### Requirements

- All Clojure blocks must be valid, readable Clojure
- Show `require` forms so readers know which namespace to import
- Use `;; =>` comments for return values
- Use `;; prints:` comments for output

```clojure
(require '[copilot.sdk :as copilot])

(defn runtime-version [client]
  (:version (copilot/await!! (copilot/status client))))
```

### Conventions

- Use `copilot` as the alias for `copilot.sdk`
- Use `h` as the alias for `copilot.sdk.helpers`
- Prefer `def` over `let` in top-level examples for clarity
- Use `with-client` and `with-session` for resources the example creates;
  close subscriptions with `clojure.core.async/close!`

### What to Avoid

- Pseudocode or incomplete snippets without context
- Java-style naming (use `kebab-case`, not `camelCase`)
- Bare `println` without explaining what it prints

## Terminology

Use Clojure terms consistently:

| Use | Don't use |
|-----|-----------|
| namespace | package, module |
| var | variable, field |
| map | object, dictionary, hash |
| keyword | enum, symbol (unless literally a symbol) |
| function | method |
| atom | mutable variable |
| channel | queue, stream (unless referring to core.async specifically) |
| require | import |

## Cross-References

- Use **relative paths** between doc files: `[API Reference](reference/API.md)`
- From repo root README: `[API Reference](./doc/reference/API.md)`
- `AGENTS.md` is a symlink to `.github/copilot-instructions.md`; links in that
  shared file must use full canonical GitHub URLs so both rendered paths work
- From examples/: `[Getting started](../doc/getting-started.md)`
- Never use absolute filesystem paths
- Link to specific sections with anchors: `[Events](reference/API.md#events-and-history)`

## Options and Configuration

Use tables for options, flags, and configuration keys:

```markdown
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:model` | string | Omitted | Model to use for the session |
| `:working-directory` | string | Omitted | Working directory for the session |
```

- Sort keys alphabetically or by importance
- Include types and defaults
- Use Clojure keyword notation (`:model`, not `model`)

## When to Update Docs

Update documentation when:

- A public API function is added, changed, or removed
- A new example is added to `examples/`
- A configuration option is added or changed
- Authentication or MCP behavior changes
- The doc structure itself changes (update `doc/index.md`)

After changes, run `bb validate-docs` to verify links and code blocks.

## File Naming

- Use `kebab-case` for file names: `getting-started.md`, not `GettingStarted.md`
- Use `index.md` for directory entry points (e.g., `auth/index.md`)
- Keep names short and descriptive

## Checklist

Before submitting doc changes:

- [ ] Code examples parse without errors
- [ ] All internal links resolve to existing files
- [ ] `require` forms shown for code that uses SDK namespaces
- [ ] Tables have consistent formatting
- [ ] No filler language ("simply", "just", "let's", "easy")
- [ ] `bb validate-docs` passes
