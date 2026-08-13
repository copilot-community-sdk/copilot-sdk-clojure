# ADR: Defer a host-owned inference boundary

- Status: Accepted
- Deciders: @krukow
- Related:
  [latest upstream audit baseline](https://github.com/github/copilot-sdk/commit/811adc050a82d823cc6f6891576f30058554af8d),
  [upstream audit baseline](https://github.com/github/copilot-sdk/commit/8d0a9cc63391cb5d820bd092726c811f1225c4b9),
  [prior upstream audit baseline](https://github.com/github/copilot-sdk/commit/25c0beab6095def6881bb12ddd8d36f21dcbd3d6),
  [current Clojure baseline](https://github.com/copilot-community-sdk/copilot-sdk-clojure/commit/4fd01ffc2437d8698f65bea356e8dfbad35eb4c1)

## Context

The upstream Node.js SDK now exposes one experimental
`CopilotClientOptions.requestHandler` for application-owned model-layer
traffic. It intercepts HTTP, streaming SSE responses, and WebSocket traffic for
both CAPI and BYOK providers, and the same handler serves every session on the
client
([`types.ts` lines 381-403](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/types.ts#L381-L403),
[`copilotRequestHandler.ts` lines 232-327](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L232-L327)).
Node exports the handler and its WebSocket types from the package root
([`index.ts` lines 27-38](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/index.ts#L27-L38)).

This is one subsystem, not five independent RPC conveniences. Its exact
experimental wire lifecycle is:

1. SDK to runtime: `llmInference.setProvider`.
2. Runtime to SDK: `llmInference.httpRequestStart`.
3. Runtime to SDK: `llmInference.httpRequestChunk`.
4. SDK to runtime: `llmInference.httpResponseStart`.
5. SDK to runtime: `llmInference.httpResponseChunk`.

The generated client-global dispatcher defines the two inbound requests and no
`llmInference` notifications or events
([`rpc.ts` lines 21978-22048](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L21978-L22048)).
The generated server API defines registration and the two outbound response
requests
([`rpc.ts` lines 19268-19293](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L19268-L19293)).
A start frame allocates correlated request state, chunks feed its request body
or cancel it, and the response head must precede all response chunks. Exposing
one generated method without the others would expose a sequencing hazard, not a
usable host API
([`rpc.ts` lines 7215-7410](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L7215-L7410),
[`rpc.ts` lines 19276-19284](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L19276-L19284)).

The Node adapter accepts a chunk before its start frame by allocating the
exchange early. It acknowledges start and chunk delivery immediately, runs the
application handler separately, maps cancellation to a best-effort 499 response,
and maps other handler failures to 502
([`copilotRequestHandler.ts` lines 329-440](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L329-L440)).
Upstream E2E tests cover handler failures and `session.abort` cancellation
([`copilot_request_cancel_error.e2e.test.ts` lines 135-190](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/test/e2e/copilot_request_cancel_error.e2e.test.ts#L135-L190)).

The upstream implementation also demonstrates constraints that are not suitable
defaults for Clojure:

- Request chunks accumulate in an unbounded array, and ordinary HTTP request
  bodies are fully buffered before the callback runs
  ([`copilotRequestHandler.ts` lines 471-571](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L471-L571),
  [`copilotRequestHandler.ts` lines 646-708](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L646-L708)).
- Response RPCs are awaited, which paces response delivery, but WebSocket
  response ordering uses a promise chain that can grow with queued writes
  ([`copilotRequestHandler.ts` lines 573-644](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L573-L644),
  [`copilotRequestHandler.ts` lines 780-829](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L780-L829)).
- Node's default WebSocket forwarder opens `new WebSocket(url)` without request
  headers, while Python forwards every non-hop-by-hop header. That disagreement
  is an implementation choice, not a canonical transparent-forwarding contract
  ([`copilotRequestHandler.ts` lines 144-201](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L144-L201),
  [`copilot_request_handler.py` lines 176-207](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/python/copilot/copilot_request_handler.py#L176-L207)).
- Request chunks can carry bytes, but both upstream adapters decode runtime
  WebSocket request frames as text before forwarding. Node does preserve binary
  WebSocket response messages
  ([`copilotRequestHandler.ts` lines 278-326](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L278-L326),
  [`copilotRequestHandler.ts` lines 756-778](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L756-L778),
  [`copilot_request_handler.py` lines 744-751](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/python/copilot/copilot_request_handler.py#L744-L751)).

### Ownership differs by runtime topology

The handler belongs to the client connection, but provider ownership belongs to
the runtime process. The client registers during startup
([`client.ts` lines 832-912](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/client.ts#L832-L912)).
The runtime enforces one provider process-wide. An upstream E2E test comment
documents that a second registration fails with "Another client is already the
LLM inference provider" and skips that scenario for shared in-process transport;
upstream does not assert the rejection in that test
([`copilot_request_cancel_error.e2e.test.ts` lines 159-170](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/test/e2e/copilot_request_cancel_error.e2e.test.ts#L159-L170)).
There is no `unsetProvider` RPC.

With stdio, each SDK client normally owns a separate runtime process, so client
and provider lifetimes coincide. Shared in-process or external runtimes make
several clients contend for the same process-wide slot; an external runtime may
also outlive the registering client
([`types.ts` lines 103-190](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/types.ts#L103-L190)).
The Clojure SDK currently supports owned CLI processes, existing servers via
`:cli-url`, and child-process attachment, but not Node's in-process FFI
transport
([`client.clj` lines 215-267](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/client.clj#L215-L267)).
The inference contract must not assume that disconnecting a client terminates
the runtime.

### This is a privileged secret boundary

The application callback receives an absolute URL, full multi-valued headers,
request metadata, cancellation, and the request body. The handler context does
not redact credentials
([`copilotRequestHandler.ts` lines 28-43](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L28-L43),
[`copilotRequestHandler.ts` lines 241-275](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L241-L275),
[`copilotRequestHandler.ts` lines 658-686](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L658-L686),
[`rpc.ts` lines 7276-7294](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L7276-L7294)).
That is necessary for transparent forwarding: CAPI and BYOK authorization
headers, custom provider headers, URL query data, and prompt/body content may all
be required by the upstream endpoint. It also means that enabling the handler
deliberately grants application code access to model credentials and content.

The existing Clojure SDK already treats provider keys, bearer tokens, custom
headers, and environment values as secrets in validation paths
([`client.clj` lines 60-148](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/client.clj#L60-L148)).
An inference boundary must preserve that standard and adopt a stricter rule for
callback failures. The current generic reverse-RPC path logs and returns
`ex-message`; inference callback messages can contain credentials or response
content and therefore must not follow that precedent
([`protocol.clj` lines 379-384](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/protocol.clj#L379-L384)).

### The existing reverse-RPC pool is necessary but insufficient

Clojure dispatches server-to-client requests onto a connection-owned bounded
worker pool, never the reader thread or a core.async `go` dispatch thread.
Concurrency and queue capacity are bounded; saturation returns an explicit
JSON-RPC error; disconnect interrupts blocked workers
([`protocol.clj` lines 33-52](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/protocol.clj#L33-L52),
[`protocol.clj` lines 160-175](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/protocol.clj#L160-L175),
[`protocol.clj` lines 343-439](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/protocol.clj#L343-L439)).

That pool cannot own a complete inference exchange. A reverse worker waits for
the handler's result channel before replying. If long-lived HTTP, SSE, or
WebSocket work held those workers, enough exchanges could occupy all 16 default
workers while later `httpRequestChunk` frames first queued and then failed with
`request_handler_saturated` on the same pool. Because occupied workers have no
handler timeout, the exchanges would remain stuck waiting for chunks, and
unrelated hooks, session filesystem calls, permissions, and factories would
also queue or fail. The runtime would be waiting for chunks to be handled while
the SDK workers were waiting for exchanges that need those chunks: a deadlock.
Start and chunk dispatch must therefore be short-lived stages that hand off
stream-lifetime work elsewhere.

### Adjacent parity tracks do not form one host API

Three adjacent areas share this ADR's scope boundary, not one technical blocker:

- Stable extension session config exists independently. Upstream separates
  extension opt-in, extension SDK path, and stable extension identity from
  canvas provider identity
  ([`types.ts` lines 2221-2248](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/types.ts#L2221-L2248)).
  Clojure supports `:request-extensions?`, `:extension-sdk-path`,
  `:extension-info`, and `:canvas-provider`; this decision does not reopen those
  stable fields
  ([`create-session` config](../reference/API.md#create-session)).
- Canvas authoring/provider APIs are a coherent but experimental upstream
  surface
  ([`canvas.ts` lines 20-31](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/canvas.ts#L20-L31),
  [`canvas.ts` lines 136-146](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/canvas.ts#L136-L146),
  [`types.ts` lines 2204-2219](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/types.ts#L2204-L2219),
  [`types.ts` lines 2250-2256](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/types.ts#L2250-L2256)).
  Clojure already observes canvas events and exposes `open-canvases`; only
  authoring/provider callbacks are excluded
  ([`copilot_sdk.clj` lines 137-151](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk.clj#L137-L151),
  [`copilot_sdk.clj` lines 1072-1088](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk.clj#L1072-L1088)).
- Extension launch-provider has generated experimental register/resolve RPCs,
  including a 15-second resolve deadline, but the Node client has no supported
  callback wiring, package-root API, tests, or narrative lifecycle docs
  ([`rpc.ts` lines 19018-19024](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L19018-L19024),
  [`rpc.ts` lines 21965-21975](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L21965-L21975),
  [`client.ts` lines 832-856](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/client.ts#L832-L856)).

Upstream also exposes a typed raw `client.rpc` surface
([`client.ts` lines 528-540](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/client.ts#L528-L540)).
Clojure instead validates a closed client option set and exposes curated
wrappers
([`specs.clj` lines 216-240](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/e4087504e29ab874ee37bc8d3aea981e77c1b72c/src/github/copilot_sdk/specs.clj#L216-L240)).
Generated method availability alone is not evidence of a complete supported
lifecycle.

The initial audit compared the prior upstream baseline
[`25c0beab6095def6881bb12ddd8d36f21dcbd3d6`](https://github.com/github/copilot-sdk/commit/25c0beab6095def6881bb12ddd8d36f21dcbd3d6)
with
[`8d0a9cc63391cb5d820bd092726c811f1225c4b9`](https://github.com/github/copilot-sdk/commit/8d0a9cc63391cb5d820bd092726c811f1225c4b9).
No material public host/inference/canvas/launch-provider shape changed in that
range; the relevant movement was generated schema/runtime package revision.

The decision was reconfirmed on 2026-08-13 against upstream
[`811adc050a82d823cc6f6891576f30058554af8d`](https://github.com/github/copilot-sdk/commit/811adc050a82d823cc6f6891576f30058554af8d).
At that commit, `CopilotClientOptions.requestHandler` remains explicitly
experimental and process-global
([`types.ts` lines 382-404](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/src/types.ts#L382-L404)).
Client startup still installs one global adapter and registers the provider
([`client.ts` lines 832-841](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/src/client.ts#L832-L841),
[`client.ts` lines 907-912](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/src/client.ts#L907-L912)).
The generated lifecycle still consists of `setProvider`, two inbound request
methods, and two outbound response methods
([`rpc.ts` lines 19194-19217](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/src/generated/rpc.ts#L19194-L19217),
[`rpc.ts` lines 21955-21964](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/src/generated/rpc.ts#L21955-L21964)).
There is still no `unsetProvider` RPC, and upstream still documents the
single-provider conflict in its E2E test
([`copilot_request_cancel_error.e2e.test.ts` lines 165-171](https://github.com/github/copilot-sdk/blob/811adc050a82d823cc6f6891576f30058554af8d/nodejs/test/e2e/copilot_request_cancel_error.e2e.test.ts#L165-L171)).
A path-scoped comparison from
[`d9a6fabb03a98900afbba7fa39e31c9ee6179cd1`](https://github.com/github/copilot-sdk/commit/d9a6fabb03a98900afbba7fa39e31c9ee6179cd1)
through the latest baseline found no changes to `types.ts`, `client.ts`,
`copilotRequestHandler.ts`, or generated `rpc.ts` affecting this lifecycle.

## Decision

Keep application-owned inference interception outside the supported Clojure SDK
for now. Do not add a `:request-handler` client option, public
`CopilotRequestHandler` equivalents, `llmInference.*` wrappers, inference
transport dependencies, or provider lifecycle machinery.

This intentionally selects Alternative C. The project is early enough that a
small, maintainable, curated API is more valuable than speculative compatibility
with a large experimental transport and secret boundary for which no concrete
Clojure consumer need is recorded. Deferring is the most reversible choice:
upstream can stabilize or redesign the contract, and a real consumer can supply
requirements before the project commits to process-global ownership, streaming
resource policy, and credential-bearing callbacks.

Stable extension session config is already supported and remains supported.
Experimental canvas authoring/provider callbacks and the experimental extension
launch-provider lifecycle remain separate intentional exclusions. Their
stability and value must be evaluated independently; none is a prerequisite for
the others.

## Non-goals

This decision does not authorize:

- inference interception implementation;
- public access to any `llmInference.*` method;
- a generic raw RPC object or generated wrapper surface;
- canvas authoring, renderer, or provider APIs;
- extension launch-provider registration or resolution;
- removal or reconsideration of stable extension session config;
- changes to existing canvas observation, `open-canvases`, canvas-provider
  identity, or extension management wrappers;
- stable API status;
- a permanent claim that Clojure will never support host-owned inference.

This ADR records an intentional parity exclusion, not a hidden implementation
plan. The checklist below constrains a future decision if the exclusion is
revisited; it does not authorize implementation.

## Consequences

**Smaller supported surface:** the SDK avoids new HTTP, SSE, WebSocket, binary,
cancellation, buffering, and execution-pool policy until those costs serve a
demonstrated Clojure use case.

**Reduced secret and resource exposure:** the supported API does not introduce
a callback that intentionally receives unredacted model credentials, headers,
URLs, prompts, bodies, and responses. It also avoids process-global provider
contention and a second long-lived streaming execution subsystem.

**Documented parity gap:** Clojure hosts cannot implement application gateways,
policy enforcement, traffic observation or mutation, or custom inference
hosting through the SDK. Users needing those capabilities must use another
integration boundary or reopen this decision with concrete requirements.

**Future work remains substantial:** deferment does not make a later port cheap.
If revisited, the project must still resolve lifecycle ownership, bounded
streaming, transport fidelity, cancellation, overload semantics, secret-safe
diagnostics, and the full test matrix below.

**Reversible now, costlier later:** waiting allows upstream evidence to improve
the design, but downstream users could build around the omission. A later API
addition remains compatible; an emergency port after adoption pressure appears
would have less design time.

## Alternatives

### A. Port the full host surface

Port inference, canvas authoring/provider, extension launch-provider, and raw
typed RPC access together.

This maximizes nominal upstream surface parity, but combines unrelated
lifecycles and stability levels. Canvas is coherent but experimental;
launch-provider has generated register/resolve shapes without supported Node
wiring or lifecycle evidence; raw RPC bypasses Clojure's curated API policy.
The result would expose more secrets, generated churn, and difficult-to-change
surface than the supported behavior justifies.

### B. Add only the complete inference-host boundary

Add one experimental client option covering the complete
HTTP/SSE/WebSocket/cancellation lifecycle while keeping the five wire methods
private. This is coherent and feasible, but it was not selected because it
would commit the project to a large secret, transport, singleton, and resource
boundary before either concrete Clojure demand or upstream stabilization.

### C. Exclude application-owned inference

Keep all host-owned inference outside the Clojure SDK until a concrete consumer
appears or upstream stabilizes the API.

This is the selected alternative. It minimizes security exposure, dependencies,
singleton complexity, and maintenance while preserving the ability to add a
well-motivated API later. It knowingly loses upstream parity for gateways,
policy enforcement, observation/mutation, and custom inference hosting.

## Future implementation checklist if revisited

This checklist preserves the implementation invariants developed during the
proposal. Meeting it would be necessary but not sufficient: revisiting the
decision first requires a new Proposed ADR with current upstream and consumer
evidence.

### Ownership and registration

- One handler belongs to one client connection and serves every session routed
  through the runtime provider.
- Registration occurs during client startup before session creation.
- The runtime owns one provider slot per process. A competing registration
  fails explicitly.
- Disconnect releases the connection's handler state and all in-flight
  exchanges. The SDK must not promise that disconnect terminates an external or
  shared runtime, and must not invent an unsupported `unsetProvider`.

### Two-stage execution

- `httpRequestStart` and `httpRequestChunk` enter through the existing bounded
  reverse-RPC dispatcher.
- Each stage allocates or routes bounded exchange state and acknowledges
  promptly. It must not perform application network I/O or hold a reverse worker
  for the exchange lifetime.
- Long-lived HTTP, SSE, and WebSocket application work and response emission run
  outside the reader thread, core.async `go` dispatch, and shared reverse-RPC
  workers on a separately bounded inference execution facility.
- The facility must bound concurrency and queued work, isolate lifecycle
  ownership per connection, and tear down deterministically.

### Buffering, pacing, and ordering

- Chunks arriving before start allocate provisional state and are retained in
  order. Start adopts that state.
- Every request-side queue and provisional exchange table is bounded.
- The wire has no pause, credit, or pull mechanism, and its chunk
  acknowledgement is explicitly permitted to be treated as fire-and-forget
  ([`rpc.ts` lines 7231-7268](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L7231-L7268),
  [`rpc.ts` lines 21990-21996](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/generated/rpc.ts#L21990-L21996)).
  Clojure must not use delayed acknowledgement as an undocumented pacing
  mechanism or claim true upstream backpressure. If the runtime outruns a
  bounded request buffer, terminate that exchange with an explicit
  transport/overload failure. Never block the reader or silently drop a chunk.
- Response start, chunks, and terminal completion are ordered per request.
  Awaiting response RPCs provides downstream pacing. Response ordering state
  must also be bounded.
- Cancellation stops application I/O, prevents later response writes, and
  reaches exactly one terminal cleanup path. Connection loss and teardown do
  the same for every in-flight exchange.
- Handler failures become explicit transport failures and cannot leave a turn
  hanging.

### Transport contract

- HTTP and streaming SSE use one HTTP contract. Preserve status, reason, body
  bytes, and all response header values.
- After the application WebSocket connection opens, emit the `101` response
  head before waiting for or forwarding any upstream message. The runtime gates
  request-message delivery on that acknowledgement; lazy emission would
  deadlock
  ([`copilotRequestHandler.ts` lines 283-290](https://github.com/github/copilot-sdk/blob/8d0a9cc63391cb5d820bd092726c811f1225c4b9/nodejs/src/copilotRequestHandler.ts#L283-L290)).
- The handler receives the absolute request URL and every request header value.
- Default HTTP and WebSocket forwarding sends every end-to-end request header
  value while removing only hop-by-hop or transport-controlled headers such as
  `host`, `connection`, `content-length`, `transfer-encoding`, `upgrade`, and
  related connection headers. Do not adopt Node's default WebSocket header loss.
- Binary HTTP request and response chunks remain bytes.
- Binary WebSocket response messages are supported. Runtime-to-application
  binary WebSocket request messages are rejected with an explicit unsupported
  transport error until upstream provides a coherent public end-to-end
  contract; never decode them with replacement characters or silently convert
  them to empty data.
- The contract applies to both CAPI and BYOK traffic.

### Security and observability

Enabling a future option would be an explicit trust decision. Application
handlers would receive unredacted URLs, headers, credentials, and prompt/body
content so they could forward or replace the request.

SDK-owned logs, telemetry, exceptions, diagnostics, and transport errors must
not include URLs, query strings, header values, bodies, response content, or
callback exception messages by default. Safe diagnostics are limited to
non-secret request/session/agent identifiers when available, transport, phase,
byte or queue counts, and stable machine-readable error classes. Any content
capture requires a separate explicit caller opt-in and remains outside this
ADR. This callback-failure rule must be stricter than the generic reverse-RPC
error path.

### Experimental and compatibility policy

Any future option, protocol/handler vars, and documentation must carry explicit
experimental metadata and remain outside stable API compatibility guarantees
until a later ADR promotes them.

Experimental status permits later source/API revisions or removal in newly
published versions, but cannot recall already published artifacts. Promotion
would require upstream stabilization, concrete Clojure consumer evidence, and
operational results from the bounded implementation.

### Design and API

- Present the exact experimental namespace, client option, handler map/protocol,
  data shapes, dependencies, queue limits, and executor ownership for review.
- Keep all five wire methods private.
- Add closed specs, registered fdefs, experimental metadata, API docs, and
  examples without implying stable support.
- Document the trust boundary and runtime singleton before showing usage.

### Deterministic unit and integration coverage

Use real local HTTP and WebSocket servers plus the real framed protocol test
harness. Cover:

- provider registration, duplicate rejection, startup failure cleanup, and
  owned versus external/shared runtime teardown; duplicate-provider assertion is
  new Clojure coverage rather than an upstream test to copy;
- prompt start/chunk acknowledgements through the reverse-RPC pool while
  application I/O runs elsewhere;
- enough concurrent long-lived exchanges to prove that later chunks,
  cancellation, unrelated reverse RPC, responses, and notifications continue
  when every inference execution slot is occupied;
- HTTP, SSE, WebSocket, CAPI-shaped and BYOK-shaped traffic, transparent
  multi-valued headers, empty bodies, binary HTTP chunks, binary WebSocket
  responses, and explicit rejection of binary WebSocket requests;
- eager WebSocket `101` response-head emission before any upstream message,
  including when the upstream remains silent;
- chunk-before-start, duplicate start, unknown request id, invalid ordering,
  response pacing, buffer overflow, cancellation, handler failure, connection
  loss, and exactly-once terminal cleanup;
- bounded exchange count, inference concurrency, request queues, and response
  ordering state;
- request-buffer overflow as an explicit transport failure, never silent drop
  or a claim of upstream backpressure;
- sentinel secrets in URL queries, CAPI/BYOK headers, bodies, and callback
  exceptions never appearing in logs, telemetry, exception data, diagnostics,
  or transport error messages.

Measure throughput and resource isolation under concurrent streaming. The
separate inference facility must not starve reverse RPC, session progress,
sockets, memory, or host CPU.

### Bounded live E2E

Against a real runtime, cover one HTTP/SSE flow, one WebSocket flow,
`session.abort` cancellation, and duplicate-provider rejection. Isolate runtime
processes and execution order so the singleton cannot make the suite
order-dependent. Set explicit time, request, and resource bounds.

## Revisit or supersede this decision when

- a concrete Clojure consumer needs model-layer interception and can supply
  transport, policy, deployment-topology, and lifecycle requirements that
  cannot be met through a smaller existing boundary;
- upstream stabilizes or materially redesigns `CopilotRequestHandler` or the
  five-method wire lifecycle;
- the runtime replaces the process-global provider slot with scoped ownership
  and explicit release, or otherwise makes shared/external runtime teardown
  coherent;
- upstream publishes coherent binary WebSocket request, overload,
  cancellation, and resource-lifecycle semantics with operational evidence;
- a reviewed security model materially reduces or clearly governs the
  unredacted credential and content boundary;
- project maturity and maintainer capacity justify owning the transport,
  execution, observability, and test matrix in the future checklist.

Material changes require a new ADR that marks this one superseded. Minor
clarifications may amend this Accepted ADR with an explicit dated note.
