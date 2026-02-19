# Using MCP Servers with the Copilot SDK for Clojure

The Copilot SDK can integrate with **MCP servers** (Model Context Protocol) to extend the assistant's capabilities with external tools. MCP servers run as separate processes and expose tools (functions) that Copilot can invoke during conversations.

> **Important:** MCP tools often require permissions (file access, shell commands, etc.). The SDK uses a **deny-by-default** permission model. To allow MCP tool execution, either use `:on-permission-request copilot/approve-all` for blanket approval, or provide a custom permission handler. See the [Permission Handling](#permission-handling) section below.

## What is MCP?

[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) is an open standard for connecting AI assistants to external tools and data sources. MCP servers can:

- Execute code or scripts
- Query databases
- Access file systems
- Call external APIs
- And much more

## Server Types

The SDK supports two types of MCP servers:

| Type | Description | Use Case |
|------|-------------|----------|
| **Local/Stdio** (`:local`, `:stdio`) | Runs as a subprocess, communicates via stdin/stdout | Local tools, file access, custom scripts |
| **HTTP/SSE** (`:http`, `:sse`) | Remote server accessed via HTTP | Shared services, cloud-hosted tools |

## Configuration

### Local MCP Server

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :mcp-servers
                               {"my-local-server"
                                {:mcp-command "node"
                                 :mcp-args ["./mcp-server.js"]
                                 :mcp-tools ["*"]
                                 :env {"DEBUG" "true"}
                                 :cwd "./servers"}}}]
  (println (h/query "Use my tools to help me" :session session)))
```

### Remote MCP Server (HTTP)

```clojure
(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :mcp-servers
                               {"github"
                                {:mcp-server-type :http
                                 :mcp-url "https://api.githubcopilot.com/mcp/"
                                 :mcp-headers {"Authorization" (str "Bearer " token)}
                                 :mcp-tools ["*"]}}}]
  (println (h/query "List my recent GitHub notifications" :session session)))
```

### Multiple MCP Servers

You can combine multiple MCP servers in a single session:

```clojure
(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}
                                "github"
                                {:mcp-server-type :http
                                 :mcp-url "https://api.githubcopilot.com/mcp/"
                                 :mcp-headers {"Authorization" (str "Bearer " token)}
                                 :mcp-tools ["*"]}}}]
  ;; Both servers' tools are available
  (println (h/query "List files in /tmp and my GitHub notifications" :session session)))
```

## Quick Start: Filesystem MCP Server

Here's a complete working example using the official [`@modelcontextprotocol/server-filesystem`](https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem) MCP server:

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  (println (h/query "List the files in the allowed directory" :session session)))
```

> **Tip:** You can use any MCP server from the [MCP Servers Directory](https://github.com/modelcontextprotocol/servers). Popular options include `@modelcontextprotocol/server-github`, `@modelcontextprotocol/server-sqlite`, and `@modelcontextprotocol/server-puppeteer`.

## Configuration Options

### Local/Stdio Server

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:mcp-command` | string | Yes | Command to execute |
| `:mcp-args` | vector | Yes | Command arguments |
| `:mcp-tools` | vector | Yes | Tools to enable (`["*"]` for all, `[]` for none, or specific tool names) |
| `:mcp-server-type` | keyword | No | `:local` or `:stdio` (defaults to local) |
| `:mcp-timeout` | number | No | Timeout in milliseconds |
| `:env` | map | No | Environment variables for the subprocess |
| `:cwd` | string | No | Working directory for the subprocess |

### Remote Server (HTTP/SSE)

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:mcp-server-type` | keyword | Yes | `:http` or `:sse` |
| `:mcp-url` | string | Yes | Server URL |
| `:mcp-tools` | vector | Yes | Tools to enable (`["*"]` for all) |
| `:mcp-timeout` | number | No | Timeout in milliseconds |
| `:mcp-headers` | map | No | HTTP headers (e.g., for authentication) |

### Key Naming Convention

MCP server config keys use an `:mcp-` prefix in Clojure (e.g., `:mcp-command`, `:mcp-args`, `:mcp-tools`) to distinguish them from other configuration options. On the wire, the SDK automatically strips this prefix to match the upstream protocol (e.g., `command`, `args`, `tools`).

The non-prefixed keys `:env` and `:cwd` are shared with other config types and do not have an `:mcp-` prefix.

## Tool Filtering

Control which tools from an MCP server are available to the model:

```clojure
;; All tools
{:mcp-tools ["*"]}

;; No tools (server connected but tools disabled)
{:mcp-tools []}

;; Specific tools only
{:mcp-tools ["read_file" "write_file" "list_directory"]}
```

## Combining MCP Servers with Custom Tools

MCP server tools work alongside custom tools defined with `define-tool`:

```clojure
(def my-tool
  (copilot/define-tool "my_custom_tool"
    {:description "A custom tool"
     :parameters {:type "object"
                  :properties {:input {:type "string"}}
                  :required ["input"]}
     :handler (fn [args _] (copilot/result-success (str "Processed: " (:input args))))}))

(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :tools [my-tool]
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  ;; Both MCP tools and custom tools are available
  )
```

## Permission Handling

MCP tools often require permissions to access files, execute shell commands, or perform network requests. The SDK uses a **deny-by-default** permission model — all permission requests are denied unless you provide an `:on-permission-request` handler.

### Approve All Permissions

For development or trusted MCP servers, use `copilot/approve-all` to approve all permission requests:

```clojure
(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request copilot/approve-all
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  (println (h/query "List the files in /tmp" :session session)))
```

### Custom Permission Handler

For fine-grained control, provide a custom handler that approves or denies specific operations:

```clojure
(defn my-permission-handler [request _ctx]
  (if (= (:capability request) "bash")
    {:kind :denied-by-rules
     :rules [{:kind "bash"}]}
    {:kind :approved}))

(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :on-permission-request my-permission-handler
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  ;; File operations approved, shell commands denied
  )
```

See the [API Reference](../reference/API.md#permission-handling) for complete permission handler documentation.

## Troubleshooting

See the [MCP Debugging Guide](./debugging.md) for detailed troubleshooting.

### Common Issues

| Issue | Solution |
|-------|----------|
| Tools not showing up | Verify `:mcp-tools` is `["*"]` or lists specific tools |
| Server not starting | Check command path, use absolute paths when in doubt |
| Connection refused (HTTP) | Check URL and ensure the server is running |
| Timeout errors | Increase `:mcp-timeout` or check server performance |
| Tools work but aren't called | Make your prompt clearly require the tool's functionality |

## Related Resources

- [Model Context Protocol Specification](https://modelcontextprotocol.io/)
- [MCP Servers Directory](https://github.com/modelcontextprotocol/servers) — Community MCP servers
- [GitHub MCP Server](https://github.com/github/github-mcp-server) — Official GitHub MCP server
- [MCP Debugging Guide](./debugging.md) — Detailed MCP troubleshooting
- [Getting Started Guide](../getting-started.md) — SDK basics and custom tools
