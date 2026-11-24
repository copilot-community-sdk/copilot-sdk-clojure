# Migrate from 1.x

Change the deployment first. In 2.x, the application JVM **is** the runtime
host. The target is the stable in-process surface of upstream 2.0.1, not
wire compatibility with a separately installed CLI.

## Decide whether to migrate

| 1.x requirement | 2.x decision |
|---|---|
| Start a CLI automatically; choose its path, arguments, environment, stdio, or restart policy | Remove the process configuration. Add a pinned platform classifier to the application classpath. Missing native assets are an error. |
| Connect over TCP, join a parent extension, or isolate native failures in a separate runtime process | No transport equivalent. Keep the 1.x application or redesign the process boundary. |
| Reuse local credentials or coding tools by default | Select `:mode` explicitly. Use `:empty` with isolated storage and a tool allowlist for an application; use `:coding-agent` only when the application intends the runtime to act as a coding agent. |
| Reuse a saved 1.x session | Do not assume on-disk compatibility. Keep a backup and a separate 2.x storage directory. Resume/delete operate on the native runtime's store. |
| Run Clojure code against the Copilot Java SDK | The Clojure library binds the Rust C interface through JNA. Only the platform **runtime** artifact is required in addition to the Clojure SDK and its dependencies. |

In-process is not a sandbox. Tool handlers, approved actions, model requests,
and native code run with the application's permissions. Enforce your own
credential, filesystem, network, and tenant boundaries. For the install
recipe, see [Getting started](../getting-started.md).

## Port a conversation

1. Require `github.copilot-sdk`. Replace separate client/start/stop calls with
   `with-client`. Create each session inside `with-session`.
2. Move flat options into groups: for example, `:events {:streaming? true}`,
   `:tools {:available-tools []}`, and `:permissions {:on-request f}`. Do
   not assume an old option name still works.
3. For a one-shot answer use `send-and-wait!`, then `await!!` on a blocking
   thread. `send!` returns a **submission ID**, not a response. Inside `go`
   use `<!` and inspect `:ok`/`:error` instead of blocking.
4. To stream events, subscribe before sending, drain while work is in flight,
   and close the subscription in `finally`. Use the correlated result of
   `send-and-wait!` for the final answer, not the last event on the channel.
5. Keep the session and client open until their operations finish. Detach to
   preserve history. To resume, supply the full session ID and current options;
   reattach callbacks, model catalogs, credentials, and storage providers as
   needed. `delete-session!!` is for runtime-stored sessions with exact,
   unambiguous IDs. For application-owned storage, detach first and remove
   the saved data through the filesystem provider.

A saved model ID is not a provider registry. A fresh runtime cannot resolve a
named model such as `"local/chat"` without its provider and model definitions.
Supply them on cold resume, even if you do not restate the selected model.
Supply live credential callbacks on **every** resume. A successful warm resume
is not evidence that credentials, callbacks or provider configuration persist.
The named BYOK catalog supports fixed credentials, not a rotating bearer
provider. Do not confuse a session's GitHub token callback with provider
authentication.

## Example migration

The [original 23 example files](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/examples)
remain available together with their original SDK. The recipes below explain
what to run or change; an archived recipe is not a passing 2.x example.

| 1.x examples | 2.x outcome |
|---|---|
| `basic_chat`, `helpers_query` | Run `./run-all-examples.sh basic-chat`. Use owned scopes and operation channels in place of the convenience helper namespace. |
| `session_events` | Run `./run-all-examples.sh session-events`. Session subscriptions are closable read channels, not lazy sequences. |
| `session_resume`, `metadata_api` | Run `./run-all-examples.sh session-resume` for history, session metadata, and deletion. Use `session-info` for the open result and `list-models` for model discovery. These are not quota APIs. |
| `byok_provider`, `empty_mode` | The native examples select an isolated `:empty` client and a named model catalog. See [the local provider recipe](../getting-started.md). This does not port live provider keys or prove remote endpoint behavior. |
| `tool_integration`, `permission_bash` | Run `./run-all-examples.sh tool-integration` for a read-only custom tool and a scoped `:approve-once`. Do not run the old shell example under an unrestricted policy. |
| `file_attachments`, `reasoning_effort`, `structured_output` | Use message `:attachments`, session `[:inference :reasoning-effort]`, and message `:response-schema` respectively. Read the assistant content and parse the structured response in the application. |
| `config_skill_output`, `infinite_sessions`, `multi_agent` | Revisit the storage, discovery, model, and agent policies. See [custom agent definitions](../reference/API.md#mcp-servers-and-custom-agents); the old flat configuration and helper orchestration are not runnable native recipes. |
| `commands`, `user_input`, `ask_user_failure`, `elicitation_provider`, `lifecycle_hooks` | Re-register [interactive callbacks](../reference/API.md#credentials-and-interactive-callbacks) and [session hooks](../reference/API.md#session-hooks) on each open or resume. An absent handler does not grant approval. The old interactive scripts are not run. |
| `manual_tool_resume`, `mcp_local_server` | Use `respond!!` with the original request ID to resolve a pending tool or permission request. [MCP servers](../reference/API.md#mcp-servers-and-custom-agents) belong in `[:mcp :servers]`; launching one grants process or network access. Neither recipe runs by default. |
| `dynamic_workflows` | The parent-extension transport is not part of 2.x. Keep this experimental recipe on 1.x. |

For application-owned session storage, run `./run-all-examples.sh session-filesystem`.
It checks persistence across a fresh client and removes the example's store.
See the [provider function signatures](../reference/API.md#session-filesystem)
before moving session state into your application's storage.

The [pinned 1.x reference](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/reference/API.md)
and [upstream 2.0.1 breaking changes](https://github.com/microsoft/copilot-sdk/blob/3ce61458503b6b27ebd1f674a980f4d9596d9b22/docs/v2-breaking-changes.md)
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
