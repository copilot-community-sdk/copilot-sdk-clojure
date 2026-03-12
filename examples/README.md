# Copilot SDK Clojure Examples

This directory contains example applications demonstrating various features of the Copilot SDK for Clojure.

## Prerequisites

1. **Copilot CLI**: Ensure the GitHub Copilot CLI is installed and accessible in your PATH.
   ```bash
   which copilot
   # Or set COPILOT_CLI_PATH to your CLI location
   ```

2. **Dependencies**: The examples use the `:examples` alias from `deps.edn`.

## Running Examples

All examples use Clojure's `-X` invocation, which allows passing parameters directly.

From the project root:

```bash
# Basic Q&A conversation
clojure -A:examples -X basic-chat/run

# With custom questions
clojure -A:examples -X basic-chat/run :q1 '"What is Clojure?"' :q2 '"Who created it?"'

# Simple stateless query (helpers API)
clojure -A:examples -X helpers-query/run
clojure -A:examples -X helpers-query/run :prompt '"Explain recursion briefly."'

# Multiple independent queries
clojure -A:examples -X helpers-query/run-multi
clojure -A:examples -X helpers-query/run-multi :questions '["What is Rust?" "What is Go?"]'

# Streaming output
clojure -A:examples -X helpers-query/run-streaming

# Custom tool integration
clojure -A:examples -X tool-integration/run
clojure -A:examples -X tool-integration/run :languages '["clojure" "haskell"]'

# Multi-agent orchestration
clojure -A:examples -X multi-agent/run
clojure -A:examples -X multi-agent/run :topics '["AI safety" "machine learning"]'

# Config directory, skills, and large output
clojure -A:examples -X config-skill-output/run

# Metadata API (list-tools, get-quota, model switching)
clojure -A:examples -X metadata-api/run

# Permission handling
clojure -A:examples -X permission-bash/run

# Session state events monitoring
clojure -A:examples -X session-events/run

# User input handling (ask_user)
clojure -A:examples -X user-input/run
clojure -A:examples -X user-input/run-simple

# Ask user cancellation (simulates Esc)
clojure -A:examples -X ask-user-failure/run

# BYOK provider (requires API key, see example docs)
OPENAI_API_KEY=sk-... clojure -A:examples -X byok-provider/run
clojure -A:examples -X byok-provider/run :provider-name '"ollama"'

# MCP local server (requires npx/Node.js)
clojure -A:examples -X mcp-local-server/run
clojure -A:examples -X mcp-local-server/run-with-custom-tools

# File attachments
clojure -A:examples -X file-attachments/run

# Session resume
clojure -A:examples -X session-resume/run

# Infinite sessions (context compaction)
clojure -A:examples -X infinite-sessions/run

# Lifecycle hooks
clojure -A:examples -X lifecycle-hooks/run

# Reasoning effort
clojure -A:examples -X reasoning-effort/run
```

Or run all examples:
```bash
./run-all-examples.sh
```

> **Note:** `run-all-examples.sh` runs 14 examples that need only the Copilot CLI (examples 1–9 and 12–16).
> Examples 10 (BYOK) and 11 (MCP) require external dependencies (API keys, Node.js) and must be run manually.

With a custom CLI path:
```bash
COPILOT_CLI_PATH=/path/to/copilot clojure -A:examples -X basic-chat/run
```

---

## Example 1: Basic Chat (`basic_chat.clj`)

**Difficulty:** Beginner  
**Concepts:** Client lifecycle, sessions, message sending

The simplest use case—create a client, start a conversation, and get responses.

### What It Demonstrates

- Creating and starting a `CopilotClient`
- Creating a session with a specific model
- Sending messages with `send-and-wait!`
- Multi-turn conversation (context is preserved)
- Proper cleanup with `with-client-session`

### Usage

```bash
clojure -A:examples -X basic-chat/run
clojure -A:examples -X basic-chat/run :q1 '"What is Clojure?"' :q2 '"Who created it?"'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

;; 1. Create a client and session
(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"}]
  ;; 2. Send a message using query with the session
  (println (h/query "What is the capital of France?" :session session))
  ;; => "The capital of France is Paris."

  ;; 3. Follow-up question (conversation context preserved)
  (println (h/query "What is its population?" :session session)))
  ;; The model knows "its" refers to Paris
```

---

## Example 2: Helpers Query (`helpers_query.clj`)

**Difficulty:** Beginner  
**Concepts:** Stateless queries, simple API

Shows the simplified helpers API for one-shot queries without managing client/session lifecycle.

### What It Demonstrates

- `query` - Simple synchronous query, returns just the answer string
- `query-seq!` - Returns a bounded lazy sequence (default 256 events) and guarantees session cleanup  
- `query-chan` - Returns core.async channel of events for explicit lifecycle control
- Automatic client management (created on first use, reused across queries)
- Automatic cleanup via JVM shutdown hook (no manual cleanup needed)

### Usage

```bash
# Simple query
clojure -A:examples -X helpers-query/run

# With custom prompt
clojure -A:examples -X helpers-query/run :prompt '"What is functional programming?"'

# Streaming output (lazy seq)
clojure -A:examples -X helpers-query/run-streaming

# Streaming output (core.async)
clojure -A:examples -X helpers-query/run-async

# Multiple independent queries
clojure -A:examples -X helpers-query/run-multi
clojure -A:examples -X helpers-query/run-multi :questions '["What is Rust?" "What is Go?"]'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk.helpers :as h])

;; Simplest possible query - just get the answer
(h/query "What is 2+2?" :session {:on-permission-request copilot/approve-all
                                   :model "claude-haiku-4.5"})
;; => "4"

;; With options
(h/query "What is Clojure?" :session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"})

;; Streaming with multimethod event handling
(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event :copilot/assistant.message_delta [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event :copilot/assistant.message [_] (println))

(run! handle-event (h/query-seq! "Tell me a joke" :session {:on-permission-request copilot/approve-all
                                                              :model "gpt-5.2" :streaming? true}))
```

---

## Example 3: Tool Integration (`tool_integration.clj`)

**Difficulty:** Intermediate  
**Concepts:** Custom tools, tool handlers, result types

Shows how to let the LLM call back into your application when it needs capabilities you provide.

### What It Demonstrates

- Defining tools with `define-tool`
- JSON Schema parameters for type-safe tool inputs
- Handler functions that execute when tools are invoked
- Different result types: `result-success`, `result-failure`
- Overriding built-in tools with `:overrides-built-in-tool`

### Usage

```bash
clojure -A:examples -X tool-integration/run
clojure -A:examples -X tool-integration/run :languages '["clojure" "haskell"]'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

;; Define a tool with handler
(def lookup-tool
  (copilot/define-tool "lookup_language"
    {:description "Look up information about a programming language"
     :parameters {:type "object"
                  :properties {:language {:type "string"
                                          :description "Language name"}}
                  :required ["language"]}
     :handler (fn [args invocation]
                ;; args = {:language "clojure"}
                ;; invocation = full invocation context
                (let [lang (-> args :language str/lower-case)
                      info (get knowledge-base lang)]
                  (if info
                    (copilot/result-success info)
                    (copilot/result-failure 
                      (str "No info for: " lang)
                      "not found"))))}))

;; Create session with tools and use query
(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"
                                       :tools [lookup-tool]}]
  (println (h/query "Tell me about Clojure using the lookup tool" :session session)))
```

### Tool Result Types

```clojure
;; Success - return data to the LLM
(copilot/result-success "The answer is 42")

;; Failure - tell LLM the operation failed
(copilot/result-failure "Could not connect to database" "connection timeout")

;; Denied - permission was denied
(copilot/result-denied "User declined permission")

;; Rejected - tool invocation was invalid
(copilot/result-rejected "Invalid parameters")
```

### Overriding Built-In Tools

You can override built-in tools (like `grep` or `edit_file`) with custom implementations
by setting `:overrides-built-in-tool true`:

```clojure
(def custom-grep
  (copilot/define-tool "grep"
    {:description "Project-aware grep that only searches source files"
     :overrides-built-in-tool true
     :parameters {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search pattern"}}
                  :required ["query"]}
     :handler (fn [{:keys [query]} _]
                (copilot/result-success (str "Custom grep for: " query)))}))

(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :tools [custom-grep]}]
  (println (h/query "Search for 'defn' in the project" :session session)))
```

Without `:overrides-built-in-tool true`, defining a tool whose name clashes
with a built-in tool causes an error.

---

## Example 4: Multi-Agent Orchestration (`multi_agent.clj`)

**Difficulty:** Advanced  
**Concepts:** Multiple sessions, core.async, concurrent operations, agent coordination

Demonstrates a sophisticated pattern where multiple specialized agents collaborate using core.async channels for coordination.

### What It Demonstrates

- Creating multiple sessions with different system prompts (personas)
- Using `core.async` channels for concurrent operations
- Parallel research queries with `go` blocks
- Sequential pipeline: Research → Analysis → Synthesis
- Coordinating results from multiple async operations

### Usage

```bash
clojure -A:examples -X multi-agent/run
clojure -A:examples -X multi-agent/run :topics '["AI safety" "machine learning" "neural networks"]'
```

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Multi-Agent Workflow                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   Phase 1: Parallel Research                                │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│   │  Topic 1     │  │  Topic 2     │  │  Topic 3     │     │
│   │  (go block)  │  │  (go block)  │  │  (go block)  │     │
│   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│          │                 │                 │              │
│          └────────────────┬┴─────────────────┘              │
│                           │ result-ch                       │
│                           ▼                                 │
│   Phase 2: Analysis  ┌──────────────┐                      │
│                      │   Analyst    │                      │
│                      │   Session    │                      │
│                      └──────┬───────┘                      │
│                             │                               │
│                             ▼                               │
│   Phase 3: Synthesis ┌──────────────┐                      │
│                      │   Writer     │                      │
│                      │   Session    │                      │
│                      └──────┬───────┘                      │
│                             │                               │
│                             ▼                               │
│                      Final Summary                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Example 5: Config, Skills, and Large Output (`config_skill_output.clj`)

**Difficulty:** Intermediate  
**Concepts:** config-dir overrides, skill directories, disabling skills, large tool output settings

Shows how to:
- set a custom config directory
- provide additional skill directories
- disable specific skills by name
- configure large tool output handling with a custom tool

### Usage

```bash
clojure -A:examples -X config-skill-output/run
```

---

## Example 6: Metadata API (`metadata_api.clj`)

**Difficulty:** Beginner  
**Concepts:** list-sessions, list-tools, get-quota, get-current-model, switch-model

Demonstrates the metadata API functions introduced in v0.1.24 for inspecting available tools, quota information, and dynamically switching models within a session.

### What It Demonstrates

- `list-sessions` with context filtering (by repository, branch, cwd)
- `list-tools` to enumerate available tools, with optional model-specific overrides
- `get-quota` to check account usage and entitlements
- `get-current-model` to inspect the session's current model
- `switch-model!` to change the model mid-conversation while maintaining context

### Usage

```bash
# Run the metadata API demo
clojure -A:examples -X metadata-api/run
```

### Key Points

- **list-sessions**: Filter sessions by context (`:repository`, `:branch`, `:cwd`, `:git-root`)
- **list-tools**: Get tool metadata; pass a model ID for model-specific tool lists
- **get-quota**: Returns a map of quota type to snapshot (entitlement, used, remaining %)
- **switch-model!**: Change models dynamically without losing conversation context

> **Note:** Some methods (`tools.list`, `account.getQuota`, `session.model.*`) may not be
> supported by all CLI versions. The example gracefully skips unsupported operations.

---

## Example 7: Permission Handling (`permission_bash.clj`)

**Difficulty:** Intermediate  
**Concepts:** permission requests, bash tool, approval callback, deny-by-default

The SDK **requires** an `:on-permission-request` handler in every session config.
Use `copilot/approve-all` for blanket approval, or provide a custom handler for
fine-grained control.

Shows how to:
- handle `permission.request` via `:on-permission-request`
- invoke the built-in shell tool with allow/deny decisions
- log the full permission request payload for inspection

### Usage

```bash
clojure -A:examples -X permission-bash/run
```

---

## Example 8: Session Events Monitoring (`session_events.clj`)

**Difficulty:** Intermediate  
**Concepts:** Event handling, session lifecycle, state management

Demonstrates how to monitor and handle session state events for debugging, logging, or building custom UIs.

### What It Demonstrates

- Monitoring session lifecycle events (start, resume, idle, error)
- Tracking context management events (truncation, compaction)
- Observing usage metrics (token counts, limits)
- Handling `session.snapshot_rewind` events (state rollback)
- Formatting events for human-readable display

### Session State Events

| Event | Description |
|-------|-------------|
| `session.start` | Session created (note: fires before you can subscribe) |
| `session.resume` | Existing session resumed |
| `session.idle` | Session ready for input |
| `session.error` | Error occurred |
| `session.usage_info` | Token usage metrics |
| `session.truncation` | Context window truncated |
| `session.compaction_start/complete` | Infinite sessions compaction |
| `session.snapshot_rewind` | Session state rolled back |
| `session.model_change` | Model switched |
| `session.handoff` | Session handed off |

### Usage

```bash
clojure -A:examples -X session-events/run
clojure -A:examples -X session-events/run :prompt '"Explain recursion."'
```

### Code Walkthrough

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])
(require '[github.copilot-sdk :as copilot])

(def session-state-events
  #{:copilot/session.idle :copilot/session.usage_info :copilot/session.error
    :copilot/session.truncation :copilot/session.snapshot_rewind
    :copilot/session.compaction_start :copilot/session.compaction_complete})

(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :streaming? true}]
  (let [events-ch (chan 256)
        done (promise)]
    (tap (copilot/events session) events-ch)
    (go-loop []
      (when-let [event (<! events-ch)]
        ;; Log session state events
        (when (session-state-events (:type event))
          (println "Session event:" (:type event) (:data event)))
        ;; Handle completion
        (when (= :copilot/session.idle (:type event))
          (deliver done true))
        (recur)))
    (copilot/send! session {:prompt "Hello"})
    @done))
```

---

## Example 9: User Input Handling (`user_input.clj`)

**Difficulty:** Intermediate  
**Concepts:** User input requests, ask_user tool, interactive sessions

Demonstrates how to handle `ask_user` requests when the agent needs clarification or input from the user.

### What It Demonstrates

- Registering an `:on-user-input-request` handler
- Responding to questions with choices or freeform input
- Interactive decision-making workflows

### Usage

```bash
# Full interactive example
clojure -A:examples -X user-input/run

# Simpler yes/no example
clojure -A:examples -X user-input/run-simple
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"
                                       :on-user-input-request
                                       (fn [request invocation]
                                         ;; request contains:
                                         ;; - :question - the question being asked
                                         ;; - :choices - optional list of choices
                                         ;; - :allow-freeform - whether freeform input is allowed
                                         (println "Agent asks:" (:question request))
                                         (when-let [choices (:choices request)]
                                           (doseq [c choices]
                                             (println " -" c)))
                                         ;; Return the user's response
                                         ;; :answer is required, :was-freeform defaults to true
                                         {:answer (read-line)})}]
  (copilot/send-and-wait! session
    {:prompt "Ask me what format I prefer for the output, then respond accordingly."}))
```

---

## Example 10: BYOK Provider (`byok_provider.clj`)

**Difficulty:** Intermediate  
**Concepts:** BYOK (Bring Your Own Key), custom providers, API key authentication

Shows how to use the SDK with your own API keys from OpenAI, Azure, Anthropic, or Ollama instead of GitHub Copilot authentication.

### What It Demonstrates

- Configuring a `:provider` map for BYOK
- Connecting to OpenAI, Azure OpenAI, Anthropic, or local Ollama
- Using environment variables for API keys

### Prerequisites

Set an environment variable for your provider:
- OpenAI: `OPENAI_API_KEY`
- Azure: `AZURE_OPENAI_KEY`
- Anthropic: `ANTHROPIC_API_KEY`
- Ollama: No key needed (ensure `ollama serve` is running)

### Usage

```bash
# OpenAI (default)
OPENAI_API_KEY=sk-... clojure -A:examples -X byok-provider/run

# Anthropic
ANTHROPIC_API_KEY=sk-... clojure -A:examples -X byok-provider/run :provider-name '"anthropic"'

# Ollama (local, no key)
clojure -A:examples -X byok-provider/run :provider-name '"ollama"'

# Azure
AZURE_OPENAI_KEY=... clojure -A:examples -X byok-provider/run :provider-name '"azure"'
```

See [doc/auth/byok.md](../doc/auth/byok.md) for full BYOK documentation.

---

## Example 11: MCP Local Server (`mcp_local_server.clj`)

**Difficulty:** Intermediate  
**Concepts:** MCP servers, external tools, filesystem access

Shows how to integrate MCP (Model Context Protocol) servers to extend the assistant's capabilities with external tools.

### What It Demonstrates

- Configuring `:mcp-servers` with a local stdio server
- Using the `@modelcontextprotocol/server-filesystem` MCP server
- Combining MCP server tools with custom tools
- Using `copilot/approve-all` to permit MCP tool execution (deny-by-default)

### Prerequisites

- Node.js and `npx` installed (for the filesystem MCP server)

### Usage

```bash
# Basic filesystem access
clojure -A:examples -X mcp-local-server/run

# With custom directory
clojure -A:examples -X mcp-local-server/run :allowed-dir '"/home/user/docs"'

# MCP + custom tools combined
clojure -A:examples -X mcp-local-server/run-with-custom-tools
```

See [doc/mcp/overview.md](../doc/mcp/overview.md) for full MCP documentation.

---

## Example 12: File Attachments (`file_attachments.clj`)

**Difficulty:** Beginner  
**Concepts:** File attachments, message options

Attach files to a prompt so the model can analyze their contents.

### What It Demonstrates

- Sending `:attachments` in message options with `send-and-wait!`
- File attachment type: `{:type :file :path "/absolute/path"}`
- Resolving relative paths to absolute with `java.io.File`

### Usage

```bash
# Attach and analyze deps.edn (default)
clojure -A:examples -X file-attachments/run

# Attach a different file
clojure -A:examples -X file-attachments/run :file-path '"README.md"'
```

---

## Example 13: Session Resume (`session_resume.clj`)

**Difficulty:** Intermediate  
**Concepts:** Session persistence, session resume, multi-session lifecycle

Resume a previous session by ID to continue a conversation with preserved context.

### What It Demonstrates

- Creating a session and sending a message to store context
- Retrieving the session ID from the session map
- Resuming a session with `copilot/resume-session`
- Verifying context is preserved across resume
- Manual session lifecycle with `with-client` (ensures `stop!`/session cleanup), `create-session`, `resume-session`

### Usage

```bash
# Default: remembers "PINEAPPLE"
clojure -A:examples -X session-resume/run

# Custom secret word
clojure -A:examples -X session-resume/run :secret-word '"MANGO"'
```

---

## Example 14: Infinite Sessions (`infinite_sessions.clj`)

**Difficulty:** Intermediate  
**Concepts:** Infinite sessions, context compaction, long conversations

Enable infinite sessions so the SDK automatically compacts older messages when the context window fills up.

### What It Demonstrates

- Configuring `:infinite-sessions` with compaction thresholds
- `:background-compaction-threshold` — when background compaction starts (80%)
- `:buffer-exhaustion-threshold` — when urgent compaction triggers (95%)
- Sending multiple prompts in a long-running session

### Usage

```bash
clojure -A:examples -X infinite-sessions/run

# Custom prompts
clojure -A:examples -X infinite-sessions/run :prompts '["What is Clojure?" "Who created it?" "When?"]'
```

---

## Example 15: Lifecycle Hooks (`lifecycle_hooks.clj`)

**Difficulty:** Intermediate  
**Concepts:** Hooks, callbacks, tool use monitoring

Register callbacks for session lifecycle events: start/end, tool use, prompts, and errors.

### What It Demonstrates

- Configuring `:hooks` in session config with all 6 hook types
- `:on-session-start` — fires when session begins
- `:on-session-end` — fires when session ends
- `:on-pre-tool-use` — fires before a tool runs (return `{:approved true}` to allow)
- `:on-post-tool-use` — fires after a tool completes
- `:on-user-prompt-submitted` — fires when user sends a prompt
- `:on-error-occurred` — fires on errors
- Collecting and summarizing hook events

### Usage

```bash
clojure -A:examples -X lifecycle-hooks/run

# Custom prompt
clojure -A:examples -X lifecycle-hooks/run :prompt '"List all .md files using glob"'
```

---

## Example 16: Reasoning Effort (`reasoning_effort.clj`)

**Difficulty:** Beginner  
**Concepts:** Reasoning effort, model configuration

Control how much reasoning the model applies with the `:reasoning-effort` option.

### What It Demonstrates

- Setting `:reasoning-effort` in session config
- Valid values: `"low"`, `"medium"`, `"high"`, `"xhigh"`
- Lower effort produces faster, more concise responses

### Usage

```bash
# Default: low reasoning effort
clojure -A:examples -X reasoning-effort/run

# Higher reasoning effort
clojure -A:examples -X reasoning-effort/run :effort '"high"'
```

---

## Example 17: Ask User Failure (`ask_user_failure.clj`)

**Difficulty:** Intermediate  
**Concepts:** User input cancellation, ask_user tool, error handling, event tracing

Demonstrates what happens when a user cancels an `ask_user` request (simulating pressing Esc). This is a 1:1 port of the upstream `basic-example.ts`.

### What It Demonstrates

- Handling user cancellation by throwing from `:on-user-input-request`
- Event tracing: subscribing to all events via `tap` on the session events mult
- Graceful degradation when the user skips a question
- Full event stream logging for debugging

### Usage

```bash
clojure -A:examples -X ask-user-failure/run
```

### Code Walkthrough

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])
(require '[github.copilot-sdk :as copilot])

;; Track cancelled requests
(let [cancelled-requests (atom [])]
  (copilot/with-client [client]
    (copilot/with-session [session client
                           {:on-permission-request copilot/approve-all
                            :model "claude-haiku-4.5"
                            :on-user-input-request
                            (fn [request _invocation]
                              (swap! cancelled-requests conj request)
                              ;; Throwing simulates Esc — the SDK sends a failure
                              ;; result back to the ask_user tool automatically.
                              (throw (RuntimeException. "User skipped question")))}]
      ;; Subscribe to all events for tracing
      (let [events-ch (chan 256)]
        (tap (copilot/events session) events-ch)
        (go-loop []
          (when-let [event (<! events-ch)]
            (println event)
            (recur)))

        (let [result (copilot/send-and-wait! session
                       {:prompt "Use the ask_user tool to ask me to pick between 'Red' and 'Blue'."})]
          (println "Response:" (get-in result [:data :content])))))))
```

---

## Clojure vs JavaScript Comparison

Here's how common patterns compare between the Clojure and JavaScript SDKs:

### Client Creation

**JavaScript:**
```typescript
import { CopilotClient } from "@github/copilot-sdk";
const client = new CopilotClient({ logLevel: "info" });
await client.start();
```

**Clojure:**
```clojure
(require '[github.copilot-sdk :as copilot])
(copilot/with-client [client]
  ;; use client
  )
```

### Simple Query (Helpers)

**JavaScript:**
```typescript
// No direct equivalent - must create client/session
```

**Clojure:**
```clojure
(require '[github.copilot-sdk.helpers :as h])
(h/query "What is 2+2?" :session {:on-permission-request copilot/approve-all
                                   :model "claude-haiku-4.5"})
;; => "4"
```

### Event Handling

**JavaScript:**
```typescript
session.on((event) => {
  if (event.type === "assistant.message") {
    console.log(event.data.content);
  }
});
```

**Clojure:**
```clojure
;; Using helpers with multimethod dispatch
(defmulti handle-event :type)
(defmethod handle-event :copilot/assistant.message [{{:keys [content]} :data}]
  (println content))

(run! handle-event (h/query-seq! "Hello" :session {:on-permission-request copilot/approve-all
                                                    :model "gpt-5.2" :streaming? true}))
```

### Tool Definition

**JavaScript:**
```typescript
import { z } from "zod";
import { defineTool } from "@github/copilot-sdk";

defineTool("lookup", {
  description: "Look up data",
  parameters: z.object({ id: z.string() }),
  handler: async ({ id }) => fetchData(id)
});
```

**Clojure:**
```clojure
(copilot/define-tool "lookup"
  {:description "Look up data"
   :parameters {:type "object"
                :properties {:id {:type "string"}}
                :required ["id"]}
   :handler (fn [{:keys [id]} _] 
              (fetch-data id))})
```

### Async Patterns

**JavaScript (Promises):**
```typescript
const response = await session.sendAndWait({ prompt: "Hello" });
```

**Clojure (Blocking):**
```clojure
(def response (copilot/send-and-wait! session {:prompt "Hello"}))
```

**Clojure (core.async):**
```clojure
(go
  (let [ch (copilot/send-async session {:prompt "Hello"})]
    (loop []
      (when-let [event (<! ch)]
        (println event)
        (recur)))))
```

---

## Troubleshooting

### "Connection refused" errors

Ensure the Copilot CLI is installed and accessible:
```bash
copilot --version
# Or check your custom path
$COPILOT_CLI_PATH --version
```

### Timeout errors

Increase the timeout for complex queries:
```clojure
(copilot/send-and-wait! session {:prompt "Complex question"} 300000) ; 5 minutes
```

### Tool not being called

Ensure your prompt explicitly mentions the tool or its capability:
```clojure
;; Less likely to trigger tool:
{:prompt "Tell me about Clojure"}

;; More likely to trigger tool:
{:prompt "Use the lookup_language tool to tell me about Clojure"}
```

### Memory issues with many sessions

Clean up sessions when done:
```clojure
(copilot/disconnect! session)
```

And periodically list/delete orphaned sessions:
```clojure
(doseq [s (copilot/list-sessions client)]
  (copilot/delete-session! client (:session-id s)))
```
