# Technical Review: copilot-sdk-clojure vs copilot-sdk-java

A careful, side-by-side technical assessment of this project (`copilot-sdk-clojure`) and the
official Java SDK ([github/copilot-sdk-java](https://github.com/github/copilot-sdk-java)),
followed by a strategic recommendation on whether to reimplement the Clojure SDK on top of
the Java SDK.

---

## 0. Headline numbers

| Metric | Clojure SDK | Java SDK |
|---|---|---|
| Source LOC (hand-written) | ~7 960 | ~14 700 |
| Generated LOC | 0 | ~12 670 |
| Test LOC | ~4 340 | ~17 280 |
| Test files | 4 | 40 |
| Runtime deps | core.async, data.json, tools.logging, camel-snake-kebab, slf4j-simple | jackson-databind + jsr310, spotbugs-annotations |
| Tracks upstream | github/copilot-sdk **Node.js** | github/copilot-sdk **.NET** |
| Wire schema source | hand-maintained | `@github/copilot` npm `schemas/*.json` (JSON Schema → codegen) |
| Versioning | `UPSTREAM.CLJ_PATCH` (4-segment) | `<upstream>-java.<n>` |
| Min runtime | JDK 8+ | JDK 17 (JDK 25 recommended for virtual threads) |

---

## 1. Features

Both cover the same upstream feature surface (tools, MCP, permissions, hooks, streaming,
attachments, BYOK, multi-session, child-process mode, model selection). I cross-checked
this project's `examples/` (20 examples) against the Java SDK's `cookbook/` (5 recipes)
and the public API of each.

| Feature | Clojure | Java |
|---|---|---|
| Sync `send-and-wait!` / `sendAndWait` | ✅ | ✅ |
| Async non-blocking send | ✅ `<send!` (returns `core.async` channel) | ✅ `send()` (returns `CompletableFuture<String>`) |
| Streaming deltas | ✅ `:assistant.message_delta` events | ✅ `AssistantMessageDeltaEvent` |
| Custom tools | ✅ `define-tool`, but **no JSON-schema generation from spec** (`tools.clj:77` "for now") | ✅ `ToolDefinition(name, description, JSON-schema map, handler)` — schema is required and passed verbatim |
| MCP servers (stdio + http) | ✅ session + client level (`mcp-config-add!`) | ✅ session level only via `McpStdioServerConfig` / `McpHttpServerConfig` |
| Permission handler | ✅ `:on-permission-request`, `approve-all` | ✅ `PermissionHandler.APPROVE_ALL` |
| User-input handler (`ask_user`) | ✅ `:on-user-input-request` | ✅ `setOnUserInputRequest` |
| Elicitation provider | ✅ example `elicitation_provider.clj` | ✅ `ElicitationTest` + `SessionUiApi` |
| Lifecycle hooks (pre/post tool, prompt, error) | ✅ | ✅ `HooksTest` |
| Session resume | ✅ `resume-session` | ✅ `resumeSession(id, ResumeSessionConfig)` |
| Join existing session (child process) | ✅ `:is-child-process? true` + stream proxies | ❌ **not exposed** (no equivalent of `joinSession`) |
| Multi-agent / custom agents | ✅ example | ✅ `McpAndAgentsTest` |
| Infinite sessions / compaction | ✅ example | ✅ `CompactionTest` |
| Commands | ✅ example | ✅ `CommandsTest` |
| BYOK provider | ✅ example | ✅ `ProviderConfigTest` |
| File attachments | ✅ example with custom converters | ✅ `MessageAttachmentTest` |
| Per-session GitHub token | ✅ | ✅ `PerSessionAuthTest` |
| Session metadata API | ✅ `get-session-metadata`, `list-sessions` | ✅ `MetadataApiTest` |
| `query` one-liner / lazy seq / channel helpers | ✅ `helpers/query`, `query-seq!`, `query-chan` | ❌ no equivalent (must wire callbacks) |
| Forward compatibility for new event types | ⚠ unknown events drop through (no spec failure, since specs are input-only on send paths) | ✅ explicit `UnknownSessionEvent` as Jackson `defaultImpl` |
| `with-client` / `with-client-session` macros | ✅ | n/a (uses try-with-resources `AutoCloseable`) |
| Clojure spec runtime instrumentation | ✅ ~80 `s/fdef` definitions | n/a |

**Net feature delta.** The Clojure SDK has a slightly broader user-facing surface
(`query` helpers, `join-session`, client-level MCP CRUD, lazy-seq event ergonomics,
REPL-friendly `with-*` macros). The Java SDK has stronger forward-compat semantics
(sealed events + `UnknownSessionEvent`), per-event-class subscription
(`session.on(AssistantMessageEvent.class, h)`), and stronger build-time guarantees
from generated types.

---

## 2. Concurrency / parallelism

These are fundamentally different models, and this is where the design philosophies
diverge most.

### Clojure: atom + core.async + dedicated IO threads

```
        ┌───── jsonrpc-nio-reader (OS thread, blocking NIO) ─────┐
stdio/socket ─────────────────────────────────────────────────────► incoming-ch (LBQ → notification dispatcher)
                                                                            │
                                                                  notification-router go-loop
                                                                            │
                                            ┌───────────┬──────────────────┴────┐
                                            ▼           ▼                       ▼
                                     session.event   handle-tool-call!    permission/hook
                                     mult → tap     (async/thread)        (async/thread)
                                          │
                                      user channels / on-event callback (sliding 1024)

outgoing: go-loop → LinkedBlockingQueue → jsonrpc-nio-writer (OS thread)
```

Key design choices:

- **One immutable atom per client.** All state transitions are `swap!` over a map.
  Trivial to inspect (`@(:state client)`) at the REPL.
- **`core.async/mult` + `tap`** for event fan-out per session (sliding 4096) —
  backpressure-friendly, multi-subscriber.
- **Channel-as-mutex for `send-and-wait!`** — `(chan 1)` pre-filled with a token;
  idiomatic and lock-free in user space (`session.clj:57`).
- **Tool/permission/hook handlers always run on `async/thread`,** never on the go-pool —
  so a blocking handler can't starve the protocol router. This is a deliberate, correct
  choice.
- **Sliding buffer on `:on-event` callback channel** — slow consumers drop oldest events
  instead of stalling the mult.

### Java: CompletableFuture + ExecutorService + ConcurrentHashMap

```
        ┌──── jsonrpc-reader (single-thread Executor, daemon) ────┐
stdio/socket ───────────────────────────────────────────────────────► JsonRpcClient.handle(JsonNode)
                                                                            │
                                                                  RpcHandlerDispatcher
                                                                            │
                                          CompletableFuture.runAsync(task, executor)
                                          (default: ForkJoinPool.commonPool, or user-supplied Executor,
                                           or — JDK 21+ — Executors.newVirtualThreadPerTaskExecutor())
                                                                            │
                                            CopilotSession.dispatchEvent
                                                                            │
                                            ConcurrentHashMap.newKeySet of Consumer<SessionEvent>
```

Key design choices:

- **`Executor` injection point** (`CopilotClientOptions.setExecutor`) threads everywhere —
  the recommended way to opt into virtual threads on JDK 21/25.
- **`sendAndWait` choreography** is the single most sophisticated piece of hand-written
  code (`CopilotSession.java:498-586`). An inner future collects events; an outer one is
  what the user holds; `whenCompleteAsync(_, timeoutScheduler)` deliberately re-routes
  completion off the event-dispatch thread to avoid a race where user `on()` handlers
  see events after `sendAndWait` returned. It's well-commented and genuinely good.
- **Outgoing `sendMessage` is `synchronized`** on `JsonRpcClient` — simple correctness
  over throughput. Pending requests in `ConcurrentHashMap<Long, CompletableFuture>`.

### Concurrency: who wins?

| Aspect | Clojure | Java | Notes |
|---|---|---|---|
| Default model | Channels + go-blocks | CompletableFuture | Both correct; both idiomatic for their language |
| Backpressure on event fan-out | ✅ explicit via sliding/dropping buffers | ⚠ none — handlers run on shared executor; a blocking handler stalls subsequent events for the session | Clojure's mult-per-session is genuinely better here |
| Virtual threads | n/a (not relevant for go-blocks) | ✅ opt-in via Executor | Java's only way to scale to many concurrent sessions |
| Race-free `sendAndWait` | ✅ channel mutex pattern | ✅ deliberate `whenCompleteAsync` | Both correct; Java's is more subtle |
| State observability | ✅ trivial (`@state`) | ⚠ scattered across many fields, mostly private | REPL advantage to Clojure |
| Risk of blocking the protocol thread | Low — handlers go through `async/thread` | Medium — handlers run on `runAsync` to a shared pool, but a slow handler still serializes that session's event delivery | Architectural advantage to Clojure |
| Code complexity to deliver these guarantees | Lower (~500 lines protocol + 200 process + state in one atom) | Higher (multiple managers, `RpcHandlerDispatcher`, `LifecycleEventManager`, double-checked locking, scheduler shutdown races have their own test) | Clojure wins for the IO/concurrency core |

**Verdict.** The Clojure SDK has a genuinely more elegant concurrency model for this
problem. Java's is correct but heavier and more error-prone, judging by the existence
of dedicated tests like `SchedulerShutdownRaceTest` and `TimeoutEdgeCaseTest`.

---

## 3. Tests

| Dimension | Clojure | Java |
|---|---|---|
| Framework | `clojure.test` + custom mock server | JUnit 5 + Mockito |
| Test LOC | 4 339 | 17 281 |
| Test files | 4 | 40 |
| Assertions | ~807 `is` calls | (not counted; ~hundreds of `assertThat`) |
| Mock infrastructure | **In-process** mock JSON-RPC server (`mock_server.clj`, 552 lines) using `PipedInputStream`/`PipedOutputStream` | **Replay proxy** (`CapiProxy`) — clones `github/copilot-sdk` at the `.lastmerge` SHA, runs a Node.js process replaying recorded CAPI responses against the **real** `copilot` CLI |
| E2E mode | Real CLI, gated by `COPILOT_E2E_TESTS=true` env var | Always-on via the proxy (deterministic, offline-capable) |
| Spec/contract checking | Runtime spec instrumentation when `instrument` ns is required | Generated coverage tests (`GeneratedEventTypesCoverageTest`, etc.) check every generated record/event compiles and round-trips |
| Protocol-version coverage | v2 fully via mock; v3 via `with-redefs` (no wire-level mock) | Both via real CLI |
| Dedicated concurrency tests | None (covered implicitly) | `SchedulerShutdownRaceTest`, `ZeroTimeoutContractTest`, `TimeoutEdgeCaseTest`, `ExecutorWiringTest`, `ClosedSessionGuardTest` |
| Forward-compat tests | Implicit | `ForwardCompatibilityTest` (UnknownSessionEvent) |
| Documentation tests | `bb validate-docs` parses every code block in `*.md` | `DocumentationSamplesTest` runs cookbook samples |
| Tooling | Babashka CI | Maven (Surefire + JaCoCo + Spotless + Checkstyle + SpotBugs) |

**Test design quality.** Java's approach (replay against the real CLI) has higher
*fidelity* — it's testing the actual protocol the actual CLI speaks. Clojure's mock
server has higher *velocity* — fast, no external deps, but only covers what the mock
author thought to mock. Concretely, the Clojure mock is **v2-only** and v3 broadcast
paths fall back to `with-redefs`-style stubs, which test internal logic but not wire
format. Java's E2E test harness sidesteps this entirely.

Java's test count and dedicated concurrency-edge-case tests reflect an engineering
practice of converting every bug into a regression test. The Clojure suite is solid
(and 4 339 LOC across one giant integration file is extensive), but is more uniform
in scenario shape.

---

## 4. Codegen & upstream sync (the crux)

This is the single biggest architectural difference and the core of the strategic
question.

### Clojure: hand-written, dual-tracked

- All event types, RPC method names, wire field names → hand-written in `specs.clj`
  (1 153 lines) and string literals throughout `client.clj`/`session.clj`.
- `util.clj` has explicit hand-maintained translation tables for section keys, MCP
  keys, attachment shapes — because `camelCase ↔ kebab-case` doesn't roundtrip cleanly
  for all wire fields.
- Sync triggered by an agentic workflow (`.github/workflows/upstream-sync.md`) running
  weekdays. Diffs upstream Node.js, drafts a PR, full CI, human review.
- **Tracks upstream Node.js** — a moving JS target without machine-readable schema.
- Drift only detected at test time. The mock server is itself hand-maintained.
- Three-place duplication every time you add a public function: `s/fdef`,
  `instrument-all!` list, `unstrument-all!` list (`instrument.clj:689`).

### Java: schema-driven codegen

- `@github/copilot` npm package ships `schemas/session-events.schema.json` and
  `schemas/api.schema.json` — **machine-readable wire spec.**
- `scripts/codegen/java.ts` (~1 000 lines TypeScript, run via `tsx`) consumes the
  schemas and emits ~12 670 lines of typed Java (sealed `SessionEvent` hierarchy with
  74 permits, all `*Params`/`*Result` records, namespace `*Api` classes with
  auto-injected `sessionId`).
- `mvn generate-sources -Pcodegen` regenerates;
  `-Pupdate-schemas-from-npm-artifact -Dcopilot.schema.version=…` bumps the schema.
- **Two-loop agentic sync:**
  1. `weekly-reference-impl-sync.yml` — diffs `.lastmerge` SHA against upstream
     `github/copilot-sdk` (the .NET reference), opens an issue assigned to
     `copilot-swe-agent` with the merge prompt.
  2. `codegen-check.yml` + `codegen-agentic-fix.lock.yml` — on every PR, regenerates
     code, commits diffs back to the branch, runs `mvn verify`; if it fails, dispatches
     another Copilot agent to fix the build with up to 30 min budget.
- Drift between schema and Java code is **mathematically impossible** (the code is
  regenerated from the schema). Drift between Java code and what *user* code expects is
  caught by the typed compile.
- Forward compat: `UnknownSessionEvent` as Jackson `defaultImpl` means new wire types
  never throw.

This is the single largest technical advantage of the Java SDK and arguably the single
largest piece of upside available if the Clojure SDK adopted it.

---

## 5. Notably elegant components

### In the Clojure SDK

1. **Single-atom client state.** The whole world is one immutable map; `swap!` is the
   only state transition; `@state` at the REPL shows everything.
2. **Channel-as-mutex for `send-and-wait!`** — a one-liner that replaces a Java
   synchronized + scheduler dance.
3. **`with-client` / `with-client-session` macros** — clean, composable resource
   scoping with destructuring.
4. **`mult` + `tap` event fan-out** — naturally handles multiple subscribers + slow
   consumers via sliding buffers without a separate manager class.
5. **Tool/permission/hook on `async/thread`** — architectural rule that prevents
   protocol starvation, encoded once and applied everywhere.
6. **`helpers/query` family** — `query`, `query-seq!`, `query-chan` give three different
   ergonomic shapes with one code path. The Java SDK has no equivalent.
7. **4-segment versioning (`UPSTREAM.CLJ_PATCH`)** is more self-documenting than
   Java's `<upstream>-java.<n>`.

### In the Java SDK

1. **Schema-driven codegen.** ~12 670 lines of Java that *cannot* drift from the wire
   spec. This is the biggest single piece of engineering leverage in the project.
2. **Sealed `SessionEvent` hierarchy** — JDK 17 sealed classes give exhaustive `switch`
   (caller-side) and Jackson polymorphism (deserialization-side) for free.
3. **`session.on(EventClass.class, handler)`** — type-safe per-event-class subscription;
   the API is materially nicer than callback maps with magic keywords.
4. **`sendAndWait` race-avoidance choreography** (`CopilotSession.java:498-586`) — a
   careful, well-commented use of `whenCompleteAsync(_, dedicated-scheduler)` to avoid
   completing the user's future from inside event dispatch.
5. **`UnknownSessionEvent` as Jackson `defaultImpl`** — bulletproof forward-compat for
   new event types from a newer CLI.
6. **CapiProxy E2E tests** — running the real CLI against a recorded CAPI replay gives
   the highest possible test fidelity per CI minute.
7. **Two-loop agentic sync** (weekly reference-impl sync + codegen-fix) — a genuinely
   modern engineering pipeline.

---

## 6. Disadvantages of each

### Clojure SDK weaknesses

- Manual schema/event maintenance — every upstream change is hand-translated. Drift is
  detected only at test time, and the mock is v2-only.
- 80-element symbol list duplicated three places in `instrument.clj`.
- `define-tool-from-spec` has no spec→JSON-schema converter, so spec-defined tools are
  stubs to the LLM.
- `auto-restart?` is a deprecated no-op still accepted in config — a foot-gun.
- TCP banner parsing is a busy-poll regex (`process.clj:156`).
- Return specs are mostly `any?` — input-only validation.

### Java SDK weaknesses

- `CopilotSession.java` is 1 844 lines doing too much (events + tools + permissions +
  hooks + UI + agents).
- `dispatchEvent` uses `instanceof` chain instead of exhaustive sealed `switch` —
  loses the static guarantee.
- No `joinSession` / child-process-mode equivalent.
- No virtual threads by default — must opt in via Executor; `ForkJoinPool.commonPool`
  is the silent default.
- No restart/reconnect (process death = client unusable).
- Multi-branch `anyOf` schemas degrade to `Object` and require unchecked casts in user
  code.
- Test harness needs `git clone` of upstream + `npm install` + a Node.js process —
  heavy for a one-line `mvn test`.
- Generated code is verbose Jackson-bean style for events (mutable getters/setters),
  inconsistent with the records-everywhere style of the RPC types.

---

## 7. Should we reimplement Clojure on top of Java?

This is a real strategic question, not a rhetorical one. Here is an honest assessment.

### Four realistic options

| # | Option | Effort | What you gain | What you lose |
|---|---|---|---|---|
| A | **Status quo: pure Clojure** | 0 | REPL-native idioms, single-atom state, mult-based events, `with-*` macros, `helpers/query` | Ongoing manual sync, drift risk, v2-only mock, no schema source-of-truth |
| B | **Thin Clojure veneer over Java SDK** | High (~3–6 weeks of focused work) | Schema correctness for free; ride the Java team's codegen + agentic sync pipeline; fewer types to maintain | Force Clojure consumers onto JVM-only, Jackson-typed POJOs; lose `core.async` event channels (have to bridge from Java consumers); lose `with-*` macros idiom; CompletableFuture interop is awkward in Clojure; lose REPL-friendly state inspection |
| C | **Adopt the Java codegen *output* (or schema directly), keep Clojure runtime** | Medium (~2–3 weeks) | Auto-generated Clojure specs + wire-key registry + RPC method registry from `@github/copilot` npm schemas. **Eliminates manual drift on the schema side, keeps Clojure idioms.** | Need to build a Clojure codegen (or transpile via `scripts/codegen/java.ts` style); some integration work |
| D | **Drop Clojure SDK, point users at Java SDK + interop** | 0 | Zero maintenance | Forfeit the main reason a Clojure SDK exists |

### Recommendation: do not reimplement on Java (option B). Do option C.

**Reasons against B specifically:**

1. **The runtime models are not compatible.** Java uses Jackson-mutable POJOs +
   `CompletableFuture` + `Executor`. Clojure uses immutable maps + `core.async`
   channels + atoms. Wrapping one in the other doesn't compose — every API call needs
   a marshalling layer (POJO→map, CompletableFuture→chan, sealed event class→tagged
   map). You end up with the Java type surface as your maintenance burden *plus* the
   wrapper, plus user-facing semantics that feel un-Clojure.
2. **You lock users into a heavier runtime.** The Java SDK requires JDK 17. The Clojure
   SDK targets JDK 8+, which keeps it embeddable in a wider range of host environments.
3. **You lose the things the Clojure SDK is genuinely better at:** the `helpers/query`
   family, `with-client-session` macros, single-atom REPL state, mult-based event
   fan-out, `async/thread` handler isolation. These are why someone reaches for a
   Clojure-native SDK in the first place.
4. **The Java SDK's `CopilotSession` is its weakest module** (1 844 lines, monolithic,
   no exhaustive sealed dispatch). Wrapping it doesn't fix that — it propagates it.
5. **The wire/protocol layer is *not* where the Clojure SDK is weak.** `protocol.clj`
   (497 lines) is solid. `process.clj` (199 lines) is solid. The weakness is in the
   **schema/type registry** — and that part doesn't require taking on the Java runtime.

**Why C is the right move:**

- The single largest piece of engineering leverage in the Java SDK is the
  **JSON Schema → typed code** generator. The schema is a public, language-neutral
  artifact: `@github/copilot/schemas/session-events.schema.json` and `api.schema.json`.
  The Java SDK *also* consumes these — you can write a Clojure-targeted generator
  (or fork `scripts/codegen/java.ts` into a `clojure.ts`) that emits:
  - `clojure.spec` registry entries for every event and every `*Params`/`*Result`
  - the camel↔kebab key registry (replacing the hand-maintained tables in `util.clj`)
  - the RPC method-name constants
  - the event-keyword set used in `client.clj`
- Pin the same `@github/copilot` schema version the Java SDK uses; reuse the agentic
  codegen-check loop pattern.
- Keep `protocol.clj`, `process.clj`, `client.clj`, `session.clj`, `helpers.clj`,
  `tools.clj`, `with-*` macros — none of those need to change.
- The 80-symbol triplicate in `instrument.clj` becomes generated. The `auto-restart?`
  deprecation becomes a generator-level concern.

This captures ~80% of the upside of the Java SDK's engineering pipeline while
preserving 100% of what makes the Clojure SDK worth having.

### If you go with C, the rough plan would be

1. Add `scripts/codegen/` consuming the same `@github/copilot` npm artifact pinned in
   the Java SDK's `.lastmerge`.
2. Generate `src/github/copilot_sdk/generated/specs.clj` (event specs),
   `wire_registry.clj` (key tables), `rpc_methods.clj` (constants).
3. Replace the hand-written portions of `specs.clj` and `util.clj` with
   `(require [...generated...])`.
4. Add a CI job mirroring `codegen-check.yml`: regenerate, diff, fail PR if generator
   output drifts from committed code.
5. Extend the mock server to use the generated event registry — automatic v3 coverage.

### One-line answer

**Should we reimplement on top of Java?** No. The Java SDK's runtime model (mutable
POJOs + `CompletableFuture` + `Executor`) is the wrong substrate for an idiomatic
Clojure library. But the Java SDK's *codegen pipeline and schema-as-source-of-truth
approach* is the right idea, and it's portable — adopting the JSON Schemas (not the
Java runtime) gives you the same drift-elimination benefit while keeping every Clojure
idiom that makes this SDK worth maintaining.
