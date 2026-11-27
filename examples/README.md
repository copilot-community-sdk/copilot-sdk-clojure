# Copilot SDK demos

Run a small Clojure application, ask a real model a question, and watch the SDK calls and results in your terminal. Start from this checkout on macOS arm64 or glibc Linux x64 with the Clojure CLI and a JDK.

```bash
./run-all-examples.sh --live
# Or choose one demo and supply your own prompts:
./run-all-examples.sh --live basic-chat \
  'Pick one common garden herb. Reply with its name only.' \
  'Suggest a dish using the herb you just named.'
```

The first command runs the unattended live suite with default inputs and stops on the first error. It does not enable a shell, read stdin, or start an external tool server. In the second command, both questions use the **same session**. The demo prints `You:` and `Assistant:` for each turn.

## Choose a demo

For the portable `--live` suite, use an account with GitHub Copilot access that the native runtime can discover in `:empty` mode. A supported sign-in path is GitHub CLI (`gh auth login`). Signing in **only** to the Copilot CLI is not sufficient when its credentials are stored in the system keychain or its usual state directory: the demo disables that keychain and starts with a fresh directory. Do not copy tokens or point the demo at your normal Copilot state.

`--live NAME` selects one demo. `basic-chat` and `infinite-sessions` accept multiple prompts in one conversation; `query-multi` treats them as independent questions. `multi-agent` takes research topics and `tool-integration` takes language names. To select a model explicitly, set `COPILOT_DEMO_MODEL` to an ID available to your account. The script selects the platform; it does not require a running Copilot CLI or a token on the command line.

| Demo | What to watch |
|---|---|
| [ask-user-failure](user_input.clj) | Simulate a skipped `ask_user` question. The callback fails; the model receives an unavailable answer and can finish the turn. |
| [basic-chat](basic_chat.clj) | `with-session` keeps context across questions. `h/query!!` borrows that session and returns each turn's text. |
| [config-skill-output](config_skill_output.clj) | Load one deliberately disabled skill. A scoped tool returns 110,000 bytes; the runtime stores the full result in the configured directory. The demo checks the file contents. |
| [elicitation-provider](elicitation_provider.clj) | Submit a form with `elicit!`. An application callback accepts this specific form and returns the selected color. No model turn or stdin. |
| [file-attachments](file_attachments.clj) | Send a `:file` reference to a sample dependency manifest. The model calls a tool that can read **only that file**. A path alone does not guarantee its contents reach the model. |
| [file-selection](file_attachments.clj) | Send one selected line from a sample garden note. The model receives that text directly; no filesystem tool is enabled. |
| [infinite-sessions](infinite_sessions.clj) | Set automatic context-compaction thresholds, ask three questions in one session, then inspect its history. A short conversation need not trigger compaction. |
| [lifecycle-hooks](lifecycle_hooks.clj) | Add audience guidance on the prompt, delegate to a tool-free reader, and observe the session, tool and agent hooks through detach. The error hook only runs on a runtime error. |
| [manual-tool-resume](manual_tool_resume.clj) | Client 1 saves a pending permission ID and closes. Client 2 resumes, approves it and saves the resulting tool request ID. Client 3 resumes and provides the tool result. Only the private store and the IDs persist. |
| [metadata-api](metadata_api.clj) | Read the runtime version, filter stored sessions by working directory, and inspect and delete only the example's session. No model turn. |
| [model-switch](metadata_api.clj) | Send a turn, switch to a different model with `set-model!`, and ask it to recall the first turn. Requires two available models. |
| [multi-agent](multi_agent.clj) | Use `start-turn!` to start three research turns in separate sessions before waiting for their answers. The analyst receives all three findings; the writer receives the analysis. |
| [permission-policy](tool_integration.clj) | Reject the private catalog lookup *before* the tool executes. The model receives the refusal; this is distinct from a lookup that runs but finds no entry. |
| [query](basic_chat.clj) / [query-multi](basic_chat.clj) | `h/query-once!!` owns the one-shot client and session. `query-multi` reuses a client, not conversation history. |
| [query-streaming](session_events.clj) / [query-async](session_events.clj) | Stream reply text through an `:on-event` callback or consume a `core.async` subscription yourself. Both await the result of the request; no lazy sequence owns a hidden session. |
| [reasoning-effort](reasoning_effort.clj) | Select a model and a reasoning level it advertises. Set `COPILOT_DEMO_REASONING_EFFORT` to request a particular level. |
| [session-events](session_events.clj) | `h/with-subscription` scopes observation of a dedicated conversation. Use the `start-turn!` result, not the event feed, for this prompt's final reply. |
| [session-resume](session_resume.clj) | Teach a session the word `PINEAPPLE`, detach, and ask a **new turn** after resume to recall it without repeating the word. Check clean resume, then delete the demo session. |
| [structured-answer](structured_output.clj) | `h/query-json!!` returns data with string keys. Check the model's reported confidence is in `[0,1]`; it is not a measured probability. |
| [structured-output](structured_output.clj) | `h/query-json!!` asks for a meeting-note summary and actions. Select `structured-output-primitive` by name to send the same schema, read the final event and decode its text yourself. Both use the same application validation and one send. |
| [tool-integration](tool_integration.clj) | Look up Clojure, Python, Rust and an unlisted name. The same custom tool returns `:success` or `:failure`. Pass other language names to choose the queries. |
| [user-input](user_input.clj) / [user-input-simple](user_input.clj) | The first runs both an answered and a skipped question; the simple variant chooses a preset and greets the user in that style. Neither waits for stdin. |

```bash
./run-all-examples.sh --live structured-output 'The beta opens Friday. Kim will send invitations.'
./run-all-examples.sh --live lifecycle-hooks 'Why do bridges have expansion joints?'
./run-all-examples.sh --live tool-integration
```

The [live launcher](live_examples.clj) supplies a private state directory and an explicit tool policy. Most demos share a client, not a session. The pending-tool demo opens fresh clients to demonstrate persistence. The script removes its private state after the JVM exits. It does not resume or delete your existing Copilot sessions.

**Live runs send prompts to the selected model and can consume account usage.** Use non-sensitive input: prompts, model output and the demo's tool results are printed to your terminal. Do not put credentials in prompts. If the runtime cannot use a signed-in account, the demo fails rather than substituting a canned answer. For an application that supplies its own model endpoint or credentials, see [Getting started](../doc/getting-started.md#connect-your-own-model).

## Demos with extra prerequisites

For actual terminal input, run `./run-all-examples.sh --live user-input-stdin`. Enter the literal text you want to send, or EOF to skip. The example reads one line before starting the timed turn and matches a choice by text, not its position. No model turn waits for the terminal.

`./run-all-examples.sh --live permission-bash` is a separate opt-in to **real process execution**. In a fresh directory it permits one exact `/usr/bin/printf` greeting and denies a different command. It accepts no user-supplied command. On a supported machine with Bash and `/usr/bin/printf`, `python3 -B examples/fixtures/permission_smoke.py` runs it with a loopback model and no account.

The following four demos use your chosen endpoint, not your Copilot account. Set `COPILOT_DEMO_MODEL` to a model or deployment served there. The launcher does not start a model server.

| `COPILOT_DEMO_BASE_URL` | Provider | Credential variable |
|---|---|---|
| `http://127.0.0.1:PORT/v1` | Local OpenAI-compatible server, including Ollama | None; no key is sent |
| `https://api.openai.com/v1` | OpenAI | `COPILOT_DEMO_OPENAI_KEY` |
| `https://RESOURCE.openai.azure.com` | Your Azure OpenAI resource | `COPILOT_DEMO_AZURE_KEY` |
| `https://api.anthropic.com` | Anthropic | `COPILOT_DEMO_ANTHROPIC_KEY` |

```bash
./run-all-examples.sh --live byok-provider
./run-all-examples.sh --live empty-mode
COPILOT_DEMO_PYTHON="$(command -v python3)" ./run-all-examples.sh --live mcp-local-server
COPILOT_DEMO_PYTHON="$(command -v python3)" ./run-all-examples.sh --live mcp-local-server-with-custom-tools
```

[byok-provider](byok_provider.clj) registers a named provider and model. [empty-mode](empty_mode.clj) also gives one tenant an application-owned in-memory filesystem and an explicit session-bounded tool list. It checks that the session journal was written to that store.

[mcp-local-server](mcp_local_server.clj) uses a tool-capable model and a trusted Python 3 interpreter to list files in a demo-owned directory through the [bundled Model Context Protocol server](fixtures/directory_server.py). The `with-custom-tools` variant then passes those names to a Clojure summary tool. Neither server accepts a path from the model. With cached dependencies, `python3 examples/fixtures/optional_smoke.py` exercises all four workflows against a loopback model; it does not contact a cloud provider.

## Run an offline smoke test

```bash
./run-all-examples.sh                    # Run the offline suite without using an account.
./run-all-examples.sh structured-output   # Or check one.
```

The [offline runner](offline_examples.clj) invokes the portable live demos with their default inputs, a disposable home, and a local model fixture. It loads the real native runtime. You see the app's output followed by `PASS: <name>` after the runner checks the result and model request count. For example, it checks which model answered after a switch, what history survived a resume, and what the model received after permission was denied. The first dependency download needs network access; inference does not.

The offline suite also exercises cases that do not run unattended against an account:

| Command | Why it runs separately |
|---|---|
| `./run-all-examples.sh session-filesystem` | The [filesystem demo](session_filesystem.clj) writes a journal to an in-memory provider, reads it from a fresh native client, and removes the store. No model is needed. |
| `./run-all-examples.sh commands` | A [local framed host](../test/github/copilot_sdk/command_example_fixture.clj) dispatches the registered `/guide` and `/status` [commands](commands.clj) and checks their completion. Slash text in a model prompt does not dispatch a host command. |
| `./run-all-examples.sh elicitation-url` | The host sends a URL-based consent request. The [provider](elicitation_provider.clj) declines it without opening a browser. |
| `./run-all-examples.sh user-input-stdin` | Exercise a choice matched by text, a freeform answer and EOF without reading CI's terminal. |

The no-argument command never switches to live inference, even when you're signed in. Offline runs belong in CI; opt-in live runs demonstrate the API and smoke-test the account and model you actually use. Neither replaces the protected release check. To run the unmodified 1.x demos with their original SDK, or find the native equivalent of a particular workflow, see [Example migration](../doc/guides/native-2x.md#example-migration).
