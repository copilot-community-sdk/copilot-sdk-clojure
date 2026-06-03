# Documentation

Clojure SDK for programmatic control of the GitHub Copilot CLI via JSON-RPC.

## Getting Started

- [Getting Started](getting-started.md) — Step-by-step tutorial building a weather assistant
- [Examples](../examples/README.md) — 20 working examples with walkthroughs

## Guides

- [Authentication](auth/index.md) — GitHub auth, OAuth, environment variables, priority order
- [BYOK Providers](auth/byok.md) — Bring Your Own Key for OpenAI, Azure, Anthropic, Ollama
- [Azure Managed Identity](auth/azure-managed-identity.md) — Azure BYOK with Managed Identity (no API keys)
- [MCP Servers](mcp/overview.md) — Model Context Protocol server integration
- [MCP Debugging](mcp/debugging.md) — Troubleshooting MCP connections
- [Custom Agents](guides/custom-agents.md) — Define specialized agents with scoped tools for sub-agent orchestration

## Features

Quick links to the major SDK capabilities (see the [API Reference](reference/API.md) for full detail):

- [Streaming events](reference/API.md#streaming) — incremental assistant/reasoning deltas
- [Tools](reference/API.md#tools) and [Tool Sets](reference/API.md#tool-sets) — expose and filter callable tools
- [Session Hooks](reference/API.md#session-hooks) — pre/post tool-use and lifecycle interception
- [UI Elicitation](reference/API.md#ui-elicitation) — `ask_user` / structured elicitation handlers
- [File & Blob Attachments](reference/API.md#file-attachments) — image and binary input
- [Custom Agents](guides/custom-agents.md) — specialized sub-agents with scoped tools
- [BYOK Providers](auth/byok.md) — OpenAI, Azure, Anthropic, Ollama
- [MCP Servers](mcp/overview.md) — Model Context Protocol integration
- [Observability](reference/API.md#observability) — OpenTelemetry export and session telemetry. **Privacy note:** `:capture-content?` records prompt/response content and is **off by default** — enable only in trusted environments.
- [Client Mode `:empty`](reference/API.md#client-mode-empty) — multi-tenant SaaS isolation. **Security note:** hardens sessions against local machine state; intended for hosts serving multiple users.
- [Session Filesystem](reference/API.md#session-filesystem) — route filesystem operations through host-provided handlers. **Security note:** the host fully controls session file access.
- Remote / cloud sessions (`:remote-session`, `:cloud`) and [fleet mode](reference/API.md#experimental-rpc-methods) — **experimental**; not covered by GA semver guarantees.



- [API Reference](reference/API.md) — Complete API: helpers, client, session, events, tools
- [Generated API Docs](api/index.html) — Codox-generated namespace documentation

## Contributing

- [Style Guide](style.md) — Documentation authoring conventions
- [Code Generation](codegen.md) — Schema-driven generation of clojure.spec definitions
- [Upstream Doc Gap Matrix](upstream-doc-gap-matrix.md) — Per-topic coverage vs the upstream SDK docs
- [AGENTS.md](../AGENTS.md) — Guidelines for AI agents working on this codebase
- [PUBLISHING.md](../PUBLISHING.md) — Versioning, CI/CD workflows, release process, build attestation
- [CHANGELOG](../CHANGELOG.md) — Version history
