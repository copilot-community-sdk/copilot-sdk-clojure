# Native SDK examples

Run the native SDK without a Copilot account, remote model, or CLI executable.

```bash
./run-all-examples.sh
```

Run from a 2.x checkout on macOS arm64 or glibc Linux x64 with the Clojure
CLI and JDK. The first run fetches dependencies, including the matching
runtime classifier. To run one example, add its name to the command.

| Name | Runnable source | What it verifies |
|---|---|---|
| `basic-chat` | [basic_chat.clj](basic_chat.clj) | Submit one turn, await its own final assistant event, and read the text. |
| `session-events` | [session_events.clj](session_events.clj) | Subscribe before sending, collect text deltas, check them against the final event, and unsubscribe. |
| `session-filesystem` | [session_filesystem.clj](session_filesystem.clj) | Store a timeline entry in an application-owned filesystem, read it from a new client, and remove the store. |
| `session-resume` | [session_resume.clj](session_resume.clj) | Detach, resume, read history and metadata, then delete only the session this run created. |
| `tool-integration` | [tool_integration.clj](tool_integration.clj) | Approve one read-only custom tool call and verify that the model receives its result. |

[The runner](offline_examples.clj) starts the local model fixture and invokes
each application in a fresh JVM with a disposable home, no ambient tools,
and no logged-in user. The filesystem recipe opens its own clients and uses
a [text-only in-memory provider](../test/github/copilot_sdk/memory_fs_fixture.clj);
it makes no model request. The turn recipes receive an open client, session
options, and a prompt. The tool example grants access to one in-memory
ticket lookup. The other turn recipes receive two text chunks. An
unexpected result, extra model call, or timeout fails the run. No example
uses your Copilot session history. These recipes test the SDK, not model
quality or live authentication.

Use [Getting started](../doc/getting-started.md) to configure an application
outside this harness. For the fate of the 1.x examples, see the
[migration table](../doc/guides/native-2x.md#example-migration).
