# ADR: Separate starting a turn from blocking for its answer

Status: Accepted for 2.x.

A session is a conversation. A turn is one prompt and its outcome. A
session subscription observes the conversation, including overlapping
turns. Its next idle event or text fragment cannot identify which prompt
finished. The [native runtime decision](2026-11-24-native-only-runtime.md)
requires the runtime's request-specific reply instead.

Expose `start-turn!` for a one-shot result channel and `send-and-wait!!` for
the same result on the calling thread. Both send once through the same
implementation. Keep `send!` for submission acknowledgment only. Do not
reuse `send-and-wait!`: it meant a blocking call in 1.x and a channel in the
2.x preview. Neither the transport nor the event stream needs to change.

The caller owns a `start-turn!` result; the blocking form owns its hidden
wait and releases it on interruption. Neither implicitly aborts a session
or retries a submitted prompt. For live progress, expose session observation
as such, not as a per-prompt stream. See the [turn contracts](../reference/API.md#operations).

Publish the primitives under `copilot.sdk` and conveniences under
`copilot.sdk.helpers`. Move the public specs, instrumentation and tool-set
namespaces under the same root. Private implementation namespaces do not
move. Keep the community Maven coordinate, wire event names, and single
native owner. A query borrows its session; a separately named
one-shot query takes explicit config and owns both client and session.
Scopes own only what they open. Do not hide shared clients, start duplicate
turns for decoding or observation, or hand live handles out of a future
without an ownership and abandonment contract.
