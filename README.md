# Copilot SDK for Clojure

Run Copilot in your JVM and work with Clojure maps and core.async channels. The
SDK uses JNA to call the Rust C interface directly. It does not use the Java
SDK, start a Copilot CLI, or connect to an existing runtime.

## Try a turn without an account

From this **2.x checkout**, on macOS arm64 or glibc Linux x64, run:

```bash
./run-all-examples.sh basic-chat
# basic-chat => "Native turn complete."
```

This runs a real native session against a local HTTP model fixture. It needs
the Clojure CLI, a JDK, and access to fetch dependencies on first use. It does
not need a model key, Copilot login, or a running service.

The application code is short:

```clojure
(require '[github.copilot-sdk :as copilot])

(defn ask [client-options session-options prompt]
  (copilot/with-client [client client-options]
    (copilot/with-session [session client session-options]
      (let [reply (copilot/await!!
                   (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000}))]
        (get-in reply [:data :content])))))
```

Choose the mode, storage, and credentials explicitly. `:empty` requires an
isolated storage location and an explicit session tool allowlist; `[]` means
no tools. The result belongs to this request. Closing the session detaches
the handle; it does not erase history.

## Install and migrate

Community SDK version in this checkout: `2.0.1.0-SNAPSHOT`. A checkout version
does not establish that the artifact is available from Maven Central.
Use the checkout as a [local dependency](./doc/getting-started.md). Add the
matching `com.microsoft/copilot-sdk-runtime` platform classifier, version
`2.0.1`. The pinned runtime is `1.0.114`, SDK protocol `4`. `bb jar` builds
the community SDK locally without bundling the platform binary or publishing.

| I want to… | Start here |
|---|---|
| Install the runtime or configure my own model | [Getting started](./doc/getting-started.md) |
| Port a 1.x application or check a deployment constraint | [Migration from 1.x](./doc/guides/native-2x.md) |
| Look up operations, option maps, or event behavior | [API reference](./doc/reference/API.md) |
| Run offline examples for chat, streaming, resume, session storage, and tools | [Executable examples](./examples/README.md) |
| Understand the in-process boundary | [Native runtime decision](./doc/adr/2026-11-24-native-only-runtime.md) |
| Verify the JAR from another JVM before release | [Candidate and release checks](./PUBLISHING.md) |

The compatibility target is the **stable in-process** surface of the
[upstream Node.js SDK at 2.0.1](https://github.com/microsoft/copilot-sdk/tree/3ce61458503b6b27ebd1f674a980f4d9596d9b22/nodejs),
not every generated RPC or experimental extension. In-process hosting is not
a security sandbox: a native crash can terminate the JVM. If you need a
separate CLI process, TCP, or a parent extension host, keep using the
[1.x SDK and its manual](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934).

See the [documentation index](./doc/index.md), [changelog](./CHANGELOG.md),
and [license](./LICENSE).
