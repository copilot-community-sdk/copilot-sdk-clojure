# Getting started

Try a conversation with your signed-in model or an offline fixture, then configure your own application.

## Run the examples

Use a 2.x source checkout, the Clojure CLI, and a 64-bit JDK. The native
test configurations are glibc Linux x64 with JDK 17 and macOS arm64 with
JDK 21. The upstream distribution also has Windows, macOS x64, Linux arm64,
and musl classifiers. They are not covered by this repository's acceptance
matrix. A published classifier is not a tested platform claim.

```bash
./run-all-examples.sh --live basic-chat 'Name a garden herb.' 'Suggest a dish that uses it.'
```

The two questions share a session. The model's actual answers appear in the
terminal. `--live` opts in to a Copilot-enabled account and may consume
usage. Sign in with GitHub CLI (`gh auth login`) if the isolated runtime
cannot find an account. It does not use the Copilot CLI's normal state
directory or system keychain. Supply non-sensitive prompts. See
`examples/README.md` in the checkout for the meeting-note, hook and custom-tool demos.

For a credential-free smoke test, omit `--live`: `./run-all-examples.sh basic-chat`.
The fixture supplies a model that returns a fixed answer. The application
runs in a separate test JVM with a fresh home and no inherited account
credentials. Inside that JVM the SDK loads the Rust runtime and exchanges
JSON-RPC messages in memory. Only the model request uses loopback HTTP.
The first dependency download requires network access.

## Add the SDK to another application

Community SDK version in this checkout: `2.0.3.0-SNAPSHOT`. Do not substitute
a published 1.x JAR or assume a development build is on Maven Central. For example,
place the 2.x checkout next to your application. On macOS arm64, use this
application `deps.edn`:

```edn
{:deps
 {io.github.copilot-community-sdk/copilot-sdk-clojure
  {:local/root "../copilot-sdk-clojure"}
  com.microsoft/copilot-sdk-runtime$darwin-arm64 {:mvn/version "2.0.3"}}}
```

For glibc Linux x64, use `com.microsoft/copilot-sdk-runtime$linux-x64`
instead. Include **one** matching classifier. This Maven artifact contains
the platform binary, not the Java SDK. The loader verifies its checksum and
needs a writable JVM temporary directory that permits native code loading.
If the classifier is absent or incompatible, opening the client fails; there
is no process or TCP fallback.

**Keep the original classifier JAR as a separate file on the runtime
classpath.** An application uberjar (one JAR containing its dependencies)
can include the Clojure SDK and JNA, but must exclude the classifier from
merging, shading, or nesting. Put the unmodified classifier beside the
application JAR and include both on the classpath. Verification covers the
whole published classifier archive, not just `runtime.node`.

The SDK extracts the verified runtime once per JVM. A process crash can
leave its temporary files behind. On Windows a loaded native library can
remain locked until the JVM exits. Use a deployment-owned temporary directory
that you can clean up **after** the application stops.

The session storage root is not a filesystem sandbox. On macOS the pinned
runtime can create `~/Library/Application Support/Microsoft/DeveloperTools/deviceid`
under the process user's home, even if an `:empty` client only opens and
pings with a separate `:base-directory`. An application session filesystem
does not redirect host-wide files. If all writes must be isolated, run the
JVM under a dedicated OS account or container with its own home and temp
directory; enforce write restrictions at the OS boundary.

Start the application REPL with `clojure -M`. From the SDK checkout instead,
use `clojure -M:native-darwin-arm64` or `clojure -M:native-linux-x64`; the
checkout defines those aliases for you.

## Connect your own model

This function sends a prompt to an **explicitly supplied** OpenAI-compatible
chat-completions endpoint. Pass an absolute directory reserved for this
application, the endpoint base URL (including `/v1` where required), and a
model identifier served there. It configures no model key and grants no tools.
The endpoint receives your prompt. Use a trusted endpoint; do not send
credentials over plain HTTP outside loopback.

```clojure
(require '[github.copilot-sdk :as copilot])

(defn ask-local [state-dir base-url model-id prompt]
  (copilot/with-client [client {:mode :empty
                                :base-directory state-dir
                                :github {:use-logged-in-user? false}}]
    (copilot/with-session
      [session client {:model "local/chat"
                       :inference {:providers [{:name "local" :provider-type :openai
                                                :base-url base-url}]
                                   :models [{:provider "local" :id "chat"
                                             :model-id model-id :wire-model model-id}]}
                       :events {:streaming? true}
                       :github {:capi {:enabled? false}}
                       :tools {:available-tools []}}]
      (let [reply (copilot/await!!
                   (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000}))]
        (get-in reply [:data :content])))))
```

`"local/chat"` selects the local catalog entry; `:wire-model` selects the
deployment the endpoint receives. Named provider catalogs are experimental
upstream.

`await!!` blocks a caller thread and throws if the operation failed. The
native runtime can complete without an assistant message; in that case the
function returns `nil`. A wait timeout does not cancel the turn or authorize
another submission. For streaming and resumption, see the
[migration guide](guides/native-2x.md).

## Use a GitHub-backed model

For an application with a GitHub credential, use the GitHub service instead
of a local catalog. The following function **does make a service request**
when called. Supply the token through your application's credential store
and a model ID available to that account. There is no interactive sign-in:

```clojure
(require '[github.copilot-sdk :as copilot])

(defn ask-github [state-dir token model-id prompt]
  (when-not (seq token)
    (throw (ex-info "A GitHub token is required" {})))
  (copilot/with-client [client {:mode :empty :base-directory state-dir
                                :github {:use-logged-in-user? false}}]
    (copilot/with-session [session client {:model model-id :github {:token token}
                                           :tools {:available-tools []}}]
      (get-in (copilot/await!! (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000}))
              [:data :content]))))
```

This is not part of the offline runner. For renewable credentials use a
[session token provider](reference/API.md#credentials-and-interactive-callbacks)
instead of `:token`.

## Verify a checkout

```bash
bb test
bb test:native native-darwin-arm64  # or native-linux-x64
./run-all-examples.sh
bb jar
bb test:artifact native-darwin-arm64  # or native-linux-x64
```

`bb jar` builds locally. Run `bb test:artifact` **after** building: it starts
a fresh consumer that resolves the JAR's dependencies from its POM. None of
these commands signs in or publishes a release. The protected release
dispatch has a separate authenticated smoke gate. A missing
`COPILOT_NATIVE_SMOKE_TOKEN` blocks that gate; the offline commands do not
use it.
