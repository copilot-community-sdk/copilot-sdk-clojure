# Copilot SDK for Clojure

Run Copilot in your JVM and work with Clojure maps and core.async channels. The
SDK uses JNA to call the Rust C interface directly. It does not use the Java
SDK, start a Copilot CLI, or connect to an existing runtime.

## Try a model conversation

From this **2.x checkout**, on macOS arm64 or glibc Linux x64:

```bash
./run-all-examples.sh query     # One question, local model fixture, no account.
./run-all-examples.sh --live basic-chat 'What makes a rainbow?' 'Explain the colors in one sentence.'
# Run the full portable suite against your Copilot account:
./run-all-examples.sh --live
```

The two `basic-chat` questions share a session. `--live` uses a Copilot-enabled
account (for example, through `gh auth login`) and can consume account usage.
The portable suite starts with one question and ends with multi-client resume.
Omit `--live` for the same native runtime with local fixtures. The commands
need the Clojure CLI, a JDK and access to
fetch dependencies on first use. See the [demo catalog](examples/README.md)
for individual commands and examples that require extra setup.

The application code is short:

```clojure
(require '[copilot.sdk.helpers :as h])

(defn ask [client-options session-options prompt]
  (h/query-once!! {:client client-options :session session-options}
                  {:prompt prompt :timeout-ms 30000}))
```

Choose the mode, storage, and credentials explicitly. `:empty` requires an
isolated **session** storage location and an explicit session tool allowlist;
`[]` means no tools. The storage root does not confine all native process
writes; see [deployment limits](./doc/getting-started.md#add-the-sdk-to-another-application).
`query-once!!` owns one client and session, waits for this prompt's text,
and closes both. For a follow-up, use `h/query!!` on an open session.
Use `copilot.sdk/send-and-wait!!` for the full event or `copilot.sdk/start-turn!`
for a result channel. These are alternatives, not steps. Closing a session
detaches the handle; it does not erase history.

## Install and migrate

Community SDK version in this checkout: `2.0.3.0-SNAPSHOT`. A checkout version
does not establish that the artifact is available from Maven Central.
Use the checkout as a [local dependency](./doc/getting-started.md). Add the
matching `com.microsoft/copilot-sdk-runtime` platform classifier, version
`2.0.3`. The pinned runtime is `1.0.116`, SDK protocol `4`. `bb jar` builds
the community SDK locally without bundling the platform binary or publishing.

| I want to… | Start here |
|---|---|
| Install the runtime or configure my own model | [Getting started](./doc/getting-started.md) |
| Port a 1.x application or check a deployment constraint | [Migration from 1.x](./doc/guides/native-2x.md) |
| Look up operations, option maps, or event behavior | [API reference](./doc/reference/API.md) |
| Run a live model demo or a credential-free smoke test | [Executable examples](./examples/README.md) |
| Understand the in-process boundary | [Native runtime decision](./doc/adr/2026-11-24-native-only-runtime.md) |
| Verify the JAR from another JVM before release | [Candidate and release checks](./PUBLISHING.md) |

The compatibility target is the **stable in-process** surface of the
[upstream Node.js SDK at 2.0.3](https://github.com/microsoft/copilot-sdk/tree/5671812ecd4ce58ee040e9a41d98da2f849c8095/nodejs),
not every generated RPC or experimental extension. In-process hosting is not
a security sandbox: a Rust panic terminates the host JVM. If you need a
separate CLI process, TCP, or a parent extension host, keep using the
[1.x SDK and its manual](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934).

See the [documentation index](./doc/index.md), [changelog](./CHANGELOG.md),
and [license](./LICENSE).
