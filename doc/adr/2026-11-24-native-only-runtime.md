# ADR: Bind the Rust runtime directly from Clojure

- Status: Accepted for 2.x
- Target: [Microsoft Copilot SDK 2.0.1](https://github.com/microsoft/copilot-sdk/releases/tag/v2.0.1), runtime `1.0.114`, SDK protocol `4`

## Context

The v2 runtime can run inside the JVM. It exchanges the same framed JSON-RPC
messages over a native connection rather than a socket or the streams of a CLI
process. An application still needs a language SDK to submit requests, receive
events, call application-supplied functions, and release resources.

The public Copilot Java client does not support all stable in-process
capabilities that the Clojure SDK needs, including session filesystem
providers. Its byte-stream host is [explicitly internal](https://github.com/microsoft/copilot-sdk/blob/3ce61458503b6b27ebd1f674a980f4d9596d9b22/java/sdk/src/main/java/com/microsoft/copilot/ffi/NativeBinding.java).
Neither is a dependency of the Clojure 2.x design.

## Decision

Write the SDK and the host adapter in Clojure. Use Java Native Access (JNA)
only to call the [pinned Rust C interface](https://github.com/microsoft/copilot-sdk/blob/3ce61458503b6b27ebd1f674a980f4d9596d9b22/go/internal/ffihost/ffihost.go).
Load the verified platform library from the upstream runtime distribution.
The platform-specific `com.microsoft:copilot-sdk-runtime` artifact supplies
native assets; it is not the `com.microsoft:copilot-sdk` Java client.

The adapter owns native host and connection handles, frame transfer, and
shutdown. It must copy bytes before a native callback returns and retain
callback references until the runtime confirms that no more callbacks can run.
Clojure owns request correlation, bounded application callback dispatch,
cancellation, and the immutable data and core.async channels exposed to callers.
Rust owns model routing, agent execution, tool scheduling, and persisted state.
Do not implement those services a second time in Clojure.

For a completed turn, use `session.sendAndWait`. The pinned engine correlates
the response to the submitted message and can identify the final event. Do not
infer the answer from the last assistant event seen on the session, or resubmit
a prompt through a fallback after a timeout. A local wait timeout is not a
request to abort the agent.

The only SDK-to-runtime transport is in-memory JSON-RPC inside the application
process. Fail if the required native assets cannot load or protocol negotiation
fails. Do not discover a replacement runtime on `PATH`, spawn a CLI, connect to
TCP, attach to a parent process, or fall back to protocol 3. This does not
prevent the runtime from calling a configured model provider over the network.

## Consequences

Remove the transport selector and its options in 2.x. Applications that must
attach to a separate runtime or that need runtime crash isolation cannot use
2.x as a drop-in upgrade. Native code shares the JVM's process: a native crash
can terminate the application. Keep stable in-process capabilities, including
host callbacks and session filesystems; review experimental capabilities
separately.

Verify the actual native release and protocol in a fresh consumer JVM on each
advertised platform. Exercise a model turn with a local fixture, reverse
requests, cancellation and overlapping clients. A successful ping is not proof
of safe callback shutdown or of feature parity. Keep 1.x certification pinned
to its own source; this decision is not a release or a v2 parity certificate.
