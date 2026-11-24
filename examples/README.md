# Copilot SDK demos

Run a small Clojure application, ask a real model a question, and watch the SDK calls and results in your terminal. Start from this checkout on macOS arm64 or glibc Linux x64 with the Clojure CLI and a JDK.

```bash
./run-all-examples.sh --live basic-chat \
  'Pick one common garden herb. Reply with its name only.' \
  'Suggest a dish using the herb you just named.'
```

Both questions use the **same session**. The demo prints `You:` and `Assistant:` for each turn. Pass a different question or another quoted follow-up; the model's answer is not fixed.

## Choose a demo

`--live NAME` uses the runtime's signed-in GitHub Copilot account and its default model. Each demo has a default prompt; put your own quoted prompt after the name. To select a model explicitly, set `COPILOT_DEMO_MODEL` to a model ID available to your account. The script selects the platform; it does not require a running Copilot CLI or a token on the command line.

| Demo | What to watch |
|---|---|
| [basic-chat](basic_chat.clj) | `with-session` keeps context across questions. `send-and-wait!` returns each turn's final reply. |
| [session-events](session_events.clj) | `subscribe` delivers text as it arrives. The application prints the deltas, awaits its own final event and unsubscribes. |
| [structured-output](structured_output.clj) | Turn a meeting note into a summary and action items. The application sends a JSON schema, parses the model's reply and checks its shape before printing the result. |
| [lifecycle-hooks](lifecycle_hooks.clj) | A prompt hook adds a reading-level instruction. The application prints the hook name, how often it ran, the added context and the model's explanation. |
| [tool-integration](tool_integration.clj) | Ask about ticket `NW-17`. Watch the permission decision, the in-memory `ticket_status` call and its result, then the model's answer. No shell or filesystem tool is enabled. |
| [session-resume](session_resume.clj) | Send a prompt, detach, resume the same session, read the saved reply, and delete only the demo's session. |
| [session-filesystem](session_filesystem.clj) | No model is needed: `./run-all-examples.sh session-filesystem` writes a journal to an [in-memory provider](../test/github/copilot_sdk/memory_fs_fixture.clj), reads it from a fresh client, and removes the store. |

```bash
./run-all-examples.sh --live structured-output 'The beta opens Friday. Kim will send invitations.'
./run-all-examples.sh --live lifecycle-hooks 'Why do bridges have expansion joints?'
./run-all-examples.sh --live tool-integration
```

The [live launcher](live_examples.clj) shows the complete setup: `with-client`, an explicit opt-in to account discovery, and the call into the selected demo. It starts one `:empty` client with no tools by default. Each run uses a new private state directory that the script removes after the JVM exits. It does not resume or delete your existing Copilot sessions.

**Live runs send prompts to the selected model and can consume account usage.** Use non-sensitive input: prompts, model output and the demo's tool results are printed to your terminal. Do not put credentials in prompts. If the runtime cannot use a signed-in account, the demo fails rather than substituting a canned answer. For an application that supplies its own model endpoint or credentials, see [Getting started](../doc/getting-started.md#connect-your-own-model).

## Run an offline smoke test

```bash
./run-all-examples.sh                    # Run all seven without using an account.
./run-all-examples.sh structured-output   # Or check one.
```

The [offline runner](offline_examples.clj) invokes the **same demo functions** with a disposable home and a local HTTP server that supplies fixed model responses. It still loads the real native runtime. You see the app's output followed by `PASS: <name>` only after the runner checks the result and request count. For example, it checks that the schema reached the model, that the hook supplied context, and that the model received the approved ticket result. The first dependency download needs network access; inference does not.

The no-argument command never switches to live inference, even when you're signed in. Offline runs belong in CI; opt-in live runs demonstrate the API and smoke-test the account and model you actually use. Neither replaces the protected release check. See the [1.x example migration table](../doc/guides/native-2x.md#example-migration) for old example names.
