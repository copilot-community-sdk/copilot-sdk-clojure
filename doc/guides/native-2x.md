# Migrate from 1.x

2.x runs Copilot inside the application JVM. A **client** owns that runtime
connection; a **session** is a conversation; a **turn** is one prompt and its
outcome. If your application must connect to an external CLI or keep its
crashes outside the JVM, stay on 1.x.

## Port one question

Keep the community Maven coordinate `io.github.copilot-community-sdk/copilot-sdk-clojure`.
Use the [2.x dependency snippet](../getting-started.md#add-the-sdk-to-another-application)
with one matching Microsoft runtime classifier, a fresh JVM, and a separate
state directory. Change the public import from `github.copilot-sdk` to
`copilot.sdk` and `github.copilot-sdk.helpers` to `copilot.sdk.helpers`.
Public specs move to `copilot.sdk.specs`; event types such as
`:copilot/assistant.message` do not change.

For a standalone script with an explicit model and Copilot token:

```clojure
(require '[copilot.sdk.helpers :as h])

(defn ask [state-dir model token prompt]
  (h/query-once!! {:client {:mode :empty :base-directory state-dir :github {:use-logged-in-user? false}}
                   :session {:model model :github {:token token} :tools {:available-tools []}}}
                  prompt))
```

This waits for text, detaches the new session, and closes the new client.
Unlike the old standalone `query`, it does not look up or replace a global
client. For follow-ups, keep one session open. Here are **alternative**
calls on an already-open session; do not run all three for one question:

| Task | Call |
|---|---|
| [1.x helper](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/src/github/copilot_sdk/helpers.clj#L213-L266) | `(h/query prompt :session s)` |
| 2.x helper (`h` is now `copilot.sdk.helpers`) | `(h/query!! s prompt)` |
| 2.x primitive (`copilot` is `copilot.sdk`) | `(get-in (copilot/send-and-wait!! s prompt) [:data :content])` |

All three calls above borrow `s`. In 2.x a missing ordinary reply returns
`nil`; errors throw. For JSON, `h/query-json!!` requests a schema and decodes
one value with string keys; validate it separately. For parallel turns or
live progress, choose the primitives below.

## Decide whether to migrate

| 1.x requirement | 2.x decision |
|---|---|
| Start a CLI automatically; choose its path, arguments, environment, stdio, or restart policy | Remove the process configuration. Add a pinned platform classifier to the application classpath. Missing native assets are an error. |
| Connect over TCP, join a parent extension, or isolate native failures in a separate runtime process | No transport equivalent. Keep the 1.x application or redesign the process boundary. |
| Reuse local credentials or coding tools by default | Select `:mode` explicitly. Use `:empty` with isolated storage and a tool allowlist for an application; use `:coding-agent` only when the application intends the runtime to act as a coding agent. |
| Reuse a saved 1.x session | Do not assume on-disk compatibility. Keep a backup and a separate 2.x storage directory. Resume/delete operate on the native runtime's store. |
| Keep many sessions with an event callback | In 2.x, `:on-event` reserves one of 16 workers per client, even for an idle session. When all are occupied, another observed open or resume fails with `:observer-capacity`. For more streams on the same client, omit the callback and [subscribe before sending](../reference/API.md#events-and-history). |
| Run Clojure code against the Copilot Java SDK | The Clojure library binds the Rust C interface through JNA. Only the platform **runtime** artifact is required in addition to the Clojure SDK and its dependencies. |

In-process is not a sandbox. Tool handlers, approved actions, model requests,
and native code run with the application's permissions. A Rust panic in
the bundled 2.0.3 runtime terminates the JVM. Enforce your own
credential, filesystem, network, and tenant boundaries. For the install
recipe, see [Getting started](../getting-started.md).

## Port a conversation

1. Require `copilot.sdk`. Replace separate client/start/stop calls with
   `with-client`. Create each session inside `with-session`.
2. Move flat options into groups: for example, `:events {:streaming? true}`,
   `:tools {:available-tools []}`, and `:permissions {:on-request f}`. Do
   not assume an old option name still works.
3. Replace a blocking 1.x `send-and-wait!` with `send-and-wait!!`. It returns
   this prompt's final assistant event (or `nil`) instead of inferring an
   answer from session idle. Use `start-turn!` instead for a result channel:
   use `await!!` on a blocking thread, or take the already-started result
   with `<!` inside `go` and inspect `:ok`/`:error`. If migrating from the
   channel-returning 2.x preview, rename that call to `start-turn!`.
   `send!` delivers a **submission ID**, not an answer. Each form sends a
   prompt; choose one rather than calling two for the same question.
   Put an optional positive `:timeout-ms` in the message map (default 60000);
   2.x does not take a positional timeout or use `nil` for an indefinite wait.
4. To stream events, subscribe before sending, drain while work is in flight,
   and close the subscription in `finally`. It observes the whole conversation,
   including overlapping turns. Use the result of `start-turn!` for the
   final answer, not the last event on the subscription. Use separate sessions
   if independent conversations need separate live displays.
5. Keep the session and client open until their operations finish. Detach to
   preserve history. To resume, supply the full session ID and current options;
   reattach callbacks, model catalogs, credentials, and storage providers as
   needed. `delete-session!!` targets an exactly listed ID in the client's
   default runtime store. An unused, unnamed session can leave files without
   being listed; for no-turn cleanup, name the session at creation and detach
   before deleting. For a different storage directory, detach and
   point a client at that directory. For application-owned storage, remove
   the saved data through the filesystem provider after detach. See
   [Resource lifetimes](../reference/API.md#resource-lifetimes).

A saved model ID is not a provider registry. A fresh runtime cannot resolve a
named model such as `"local/chat"` without its provider and model definitions.
Supply them on cold resume, even if you do not restate the selected model.
Supply live credential callbacks on **every** resume. A successful warm resume
is not evidence that credentials, callbacks or provider configuration persist.
The named BYOK catalog supports fixed credentials, not a rotating bearer
provider. Do not confuse a session's GitHub token callback with provider
authentication.

## Example migration

The [1.x catalog had 23 example files](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/examples).
Its shell script ran 23 entry points from 19 files; four other examples
needed credentials, a local server, or a parent CLI session.

**To run the originals unchanged**, use a separate checkout of the original
SDK (`1.0.16.0`), not the 2.x classpath. From a clone with this history:

```bash
git worktree add --detach ../copilot-sdk-clojure-1x 01246672fc5fb8e8f2b50437eea6a55145e54934
cd ../copilot-sdk-clojure-1x
./run-all-examples.sh
```

That command runs live prompts. It requires a compatible, signed-in Copilot
CLI and can consume account usage. For the other four examples, follow the
[original setup instructions](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/examples/README.md#running-examples).
An old model ID or external service may no longer be available to your account.

**To port an application**, do not load 1.x and 2.x together: they share a
Maven coordinate, not a runtime or a supported mixed classpath. The originals refer to removed
names such as `with-client-session`, `helpers/query`, `events`, and
`create-session`. Some calls also changed return type: for example, 2.x
`send!` delivers its submission ID on a channel. See
[Port a conversation](#port-a-conversation) before translating individual calls.

On 2.x, `./run-all-examples.sh --live` runs the portable live demos; the bare
command runs the offline smoke suite. Use the names below to run just one.

| 1.x examples | 2.x outcome |
|---|---|
| `basic_chat`, `helpers_query` | `basic-chat` borrows a session for `query!!`. `query` uses `query-once!!`; `query-multi` reuses a client across independent sessions. `query-streaming` and `query-async` show callback and channel observation. There is no hidden shared client or lazy event sequence. |
| `session_events` | `session-events` subscribes before the turn, streams text and reports the event types and counts. Not every turn produces compaction, truncation or usage events. The application closes the subscription. |
| `session_resume` | `session-resume` teaches a code word, detaches, resumes without transcript recovery and asks the model to recall the word on a second turn. |
| `metadata_api` | `metadata-api` filters stored sessions by working directory. `model-switch` switches to a second advertised model between turns in the same conversation. The stable 2.x facade has no equivalent to `get-quota`, `list-tools` or `get-current-model`; the demo does not invent those values. |
| `byok_provider`, `empty_mode` | Named-only `byok-provider` supports OpenAI, Azure OpenAI, Anthropic and a local OpenAI-compatible endpoint such as Ollama. `empty-mode` runs a provider turn with tenant-owned session storage in memory and an explicit tool policy. |
| `tool_integration`, `permission_bash` | `tool-integration` returns success and failure from a custom language lookup. `permission-policy` denies a lookup before it runs. Named-only `permission-bash` approves one fixed command and denies another in a private directory; it does not accept shell text from the caller. |
| `file_attachments`, `reasoning_effort`, `structured_output` | `file-attachments` passes a file path to a scoped tool; `file-selection` sends text inline. `reasoning-effort` requests an advertised level. Compare `structured-output` and `structured-output-primitive` for the same meeting-note schema and validation. `structured-answer` checks an answer and confidence. |
| `config_skill_output`, `infinite_sessions` | `config-skill-output` loads a disabled skill and verifies the actual oversized tool output on disk. `infinite-sessions` keeps multiple prompts in the same session and inspects compaction events; it does not force compaction. |
| `multi_agent` | `multi-agent` starts three independent research turns before awaiting their answers, passes all findings to an analyst, and passes the analysis to a writer. |
| `commands`, `user_input`, `ask_user_failure`, `elicitation_provider` | Use `commands` for host dispatch of `/guide` and `/status`. `user-input-stdin` handles literal choices, freeform input and EOF. `user-input-simple` uses a preset; `ask-user-failure` simulates a skipped question. `elicitation-provider` serves a form; `elicitation-url` declines external URL consent rather than opening a browser or approving on the user's behalf. |
| `lifecycle_hooks` | `lifecycle-hooks` observes session start/end, prompt, tool pre/post, agent stop and subagent start/stop while delegating to a tool-free reader. It registers an error hook; a successful turn does not fire it. |
| `manual_tool_resume` | `manual-tool-resume` defers permission on client 1, approves the original permission and saves the tool request on client 2, and supplies the original tool's result on client 3. All three share the isolated store, not a client handle. No CLI kill or sleep is required. |
| `mcp_local_server` | Named-only `mcp-local-server` lists a private directory with a read-only stdio server. `mcp-local-server-with-custom-tools` passes that list to a Clojure summary tool. Both require a model endpoint and Python 3, not an automatic package download. |
| `dynamic_workflows` | The parent-extension transport is not part of 2.x. Keep this experimental recipe on 1.x. |

For application-owned session storage, run `./run-all-examples.sh session-filesystem`.
It checks persistence across a fresh client and removes the example's store.
See the [provider function signatures](../reference/API.md#session-filesystem)
before moving session state into your application's storage.

The [pinned 1.x reference](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/reference/API.md)
and [upstream 2.0.3 breaking changes](https://github.com/microsoft/copilot-sdk/blob/5671812ecd4ce58ee040e9a41d98da2f849c8095/docs/v2-breaking-changes.md)
are useful for comparison. Use the [Clojure 2.x API reference](../reference/API.md)
for actual function names, return shapes, and configuration.

## Historical topic guides

The [1.x authentication guides](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/auth)
covered CLI account discovery, flat BYOK configuration, and Azure Managed
Identity. Do not reuse those settings in 2.x. Use the current
[credential contracts](../reference/API.md#credentials-and-interactive-callbacks)
or [named provider recipe](../getting-started.md#connect-your-own-model).

The [1.x MCP troubleshooting and setup](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/mcp)
and [custom-agent guide](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/guides/custom-agents.md)
describe the old configuration and discovery paths. Use `[:mcp :servers]` and
`[:agents :definitions]` in a native session. The
[1.x documentation coverage matrix](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/upstream-doc-gap-matrix.md)
is historical evidence, not a support matrix for in-process hosting.
