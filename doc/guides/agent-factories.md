# Agent Factories

> **Experimental.** Every function in this guide is marked `^:experimental` and is not covered by GA semver guarantees. The wire protocol and API surface may change without a major version bump.

Register a long-running, resumable workflow with a session, then let the Copilot CLI invoke it from an extension or child process via reverse execution.

## A working example

An Agent Factory is defined once with `define-factory` and registered when an extension joins its parent session with `join-session`. The factory's `:run` function receives a context map with `agent`, `step`, `parallel`, `pipeline`, `phase`, and `log` functions for driving the workflow:

```clojure
(require '[github.copilot-sdk :as copilot])

(def review-factory
  (copilot/define-factory
   {:meta {:name "code-review"
           :description "Reviews changed files and summarizes findings"
           :phases [{:title "Analyze" :detail "Inspect changed files"}
                    {:title "Summarize"}]}
    :run (fn [{:keys [args agent step phase log]}]
           (phase "Analyze")
           (log (str "Reviewing " (:path args)))
           (let [findings (agent (str "Review " (:path args) " for bugs")
                                  {:label "reviewer" :schema {"type" "object"}})]
             (phase "Summarize")
             (step "summary" (fn [] {:findings findings :path (:path args)}))))}))

(let [{:keys [client session]} (copilot/join-session
                                 {:factories [review-factory]
                                  :on-permission-request copilot/approve-all})]
  ;; The Copilot CLI invokes "code-review" via reverse execution when
  ;; requested; you can also drive it directly from this same client:
  (copilot/run-factory! session "code-review" {:args {:path "src/app.clj"}})
  ;; => {:status :completed, :result {...}, :snapshot {...}}

  (copilot/stop! client))
```

`join-session` reads the `SESSION_ID` environment variable set by the CLI when it launches an extension as a child process, connects back to the parent session, and returns both the `:client` and the `:session` - see [`join-session`](../reference/API.md#join-session). `:factories` is accepted only by `join-session`; it is not part of `create-session` or `resume-session`.

## Overview

| Concept | Description |
|---------|-------------|
| **Factory** | A named, versionless workflow definition: `:meta` (name, description, phases, and optional limits) plus a `:run` function |
| **Run** | One durable execution of a factory, identified by a `run-id`, tracked server-side through `:pending` -> `:running` -> a terminal status |
| **Phase** | A named milestone declared in `:meta` and reported during execution via the context's `phase` function |
| **Step** | A unit of work inside `:run` whose result is journaled so a resumed run can skip re-computing it |
| **Context** | The map passed to `:run`, exposing `agent`, `step`, `parallel`, `pipeline`, `phase`, `log`, cancellation, and the hosting `:session` |

Factories differ from [custom agents](custom-agents.md) in direction and durability: a custom agent is inference-selected by the runtime *within* a conversation turn, while a factory is an extension-registered workflow the CLI calls back into (reverse execution) and that can be paused, resumed, or cancelled independently of any single turn.

## Defining a factory

`define-factory` validates `:meta` eagerly and returns an immutable handle; `:run` is stored as-is:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/define-factory
 {:meta {:name "backfill"
         :description "Backfills historical data"
         :phases [{:title "Fetch"} {:title "Write" :detail "Persist rows"}]}
  :run (fn [ctx] {:status "ok"})})
```

`:meta` requirements:

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `:name` | string | yes | non-blank; unique among factories registered together |
| `:description` | string | yes | non-blank |
| `:phases` | vector of `{:title string, :detail string}` | yes | each `:title` non-blank and unique within the vector; `:detail` optional |
| `:limits` | map | no | see [Limits](#limits) |

Duplicate phase titles throw `"Factory phase title is declared more than once"`. Registering two factories with the same `:name` in one `join-session`/`resume-session` call throws `"Duplicate factory name ..."`.

## Registering factories

Pass one or more handles under `:factories` to `join-session`:

```clojure
(copilot/join-session {:factories [review-factory backfill-factory]})
```

Only `:meta` (name, description, phases, limits) is sent to the CLI when joining; those fields go through the SDK's normal camelCase wire conversion (`:max-concurrent-subagents` becomes `maxConcurrentSubagents`, and so on). This is different from everything a run exchanges at execution time - see [JSON semantics](#json-semantics-and-the-nil-json-null-distinction) below.

## The execution context

The CLI invokes a registered factory's `:run` function with a single context map:

| Key | Type | Description |
|-----|------|-------------|
| `:run-id` | string | the current run's identifier |
| `:args` | JSON value | arguments supplied by the original `run-factory!` call, defaulting to `{}` and persisted across resume |
| `:session` | session | the hosting `CopilotSession`, for session-level operations such as sending messages |
| `:cancel-chan` | channel | closes when the run is cancelled from outside; use with `alts!!`/`<!!` to react cooperatively |
| `:cancelled?` | 0-arity fn | returns true once cancellation has been signaled |
| `:phase` | fn | `(phase "Title")` — reports progress against a declared phase |
| `:log` | fn | `(log "message")` — reports a free-form progress line |
| `:agent` | fn | `(agent prompt)` or `(agent prompt {:keys [label schema model]})` — runs a sub-agent turn and returns its result |
| `:step` | fn | `(step key producer-fn)` or `(step key producer-fn {:volatile? true})` — see [Steps](#steps-and-durability) |
| `:parallel` | fn | `(parallel [thunk ...])` — see [Parallel and pipeline](#parallel-and-pipeline) |
| `:pipeline` | fn | `(pipeline items stage-fn ...)` — see [Parallel and pipeline](#parallel-and-pipeline) |
| `:factory` | fn | always throws; nested factory invocation is not supported |

`phase` and `log` calls are buffered and flushed to the CLI before each `agent`/`step` call and once more after `:run` completes or throws, so progress is visible without an explicit flush call.

## JSON semantics and the nil / json-null distinction

A run's final result — whatever `:run` returns — must be a JSON-compatible value: maps, vectors, strings, booleans, numbers, or `nil`, recursively. Returning a non-JSON value (a keyword, a function, `##NaN`, and so on) throws `"Factory result must be a JSON value"` when the run completes.

Clojure `nil` and an *explicit JSON null* are different outcomes:

- Returning `nil` from `:run` means "no result" — the response omits the result field entirely.
- Returning `github.copilot-sdk.factory/json-null` means "the result is JSON `null`" — the response includes an explicit null result.

```clojure
(require '[github.copilot-sdk.factory :as factory])

(copilot/define-factory
 {:meta {:name "maybe-null" :description "Distinguishes nil from null" :phases []}
  :run (fn [_] factory/json-null)})
```

**Factory payloads bypass the SDK's usual wire conversion.** Everywhere else, the SDK converts camelCase wire keys to kebab-case Clojure keywords and back. Factory `:args`, `agent`/`step` results, and run/resume/get/cancel `:result` and `:snapshot` envelopes are the exception: they carry arbitrary caller-defined JSON, so their keys are turned into keywords *verbatim*, without kebab-casing. A JSON key `"snake_key"` becomes `:snake_key`, not `:snake-key`. Only the run envelope's own `:status` field is normalized (wire string → keyword). Design factory payload schemas with this in mind — don't expect the automatic kebab-casing you get from the rest of the SDK's session config and events.

## Steps and durability

`step` gives a unit of work a durable identity within a run:

```clojure
(step "fetch-users" (fn [] (fetch-all-users)))
```

By default, `step` checks a per-run journal before calling the producer function. On a cache hit, it returns the journaled value without invoking the producer again; on a miss, it calls the producer, validates the result is JSON, persists it, and returns it. This is what makes [`resume-factory!`](#run-resume-observe-and-cancel) cheap and safe to call repeatedly: a resumed run replays already-computed steps from the journal instead of re-running expensive or side-effecting work.

Pass `{:volatile? true}` to opt a step out of journaling — the producer runs every time, and only JSON-value validation is applied:

```clojure
(step "current-time" (fn [] (str (java.time.Instant/now))) {:volatile? true})
```

## `agent` semantics

`agent` runs one sub-agent turn and returns its result:

```clojure
(agent "Summarize the diff")
(agent "Classify severity" {:label "classifier" :schema {"type" "object"} :model "gpt-5.4"})
```

`:label` names the sub-agent for observability, `:schema` constrains the expected JSON shape of the result, and `:model` overrides the model for that turn. All three are optional.

The runtime memoizes identical `agent` calls by prompt and options. Use a
distinct `:label` (or prompt) when parallel calls must launch independent
sub-agents.

## Parallel and pipeline

`parallel` runs a vector of zero-argument thunks concurrently and returns their results in order:

```clojure
(parallel [(fn [] (agent "Check file A"))
           (fn [] (agent "Check file B"))])
```

`pipeline` runs each item through a sequence of stages, threading each stage's output into the next. For the first stage, the "previous" value is the item itself:

```clojure
(pipeline [1 2]
          (fn [previous item index] (* item 2))
          (fn [previous item index] (inc previous)))
;; => [3 5]
```

Both accept at most 4096 items/thunks; exceeding that throws.

A thunk or stage that throws is either **fatal** or **ordinary**:

- Fatal errors — a factory RPC call failing, or a cancellation-related error — abort the whole `parallel`/`pipeline` call: the exception propagates and is re-thrown to the caller.
- Ordinary errors (any other exception) do not abort sibling work. In `parallel`, that slot's result becomes `nil`:

  ```clojure
  (parallel [(fn [] "first")
             (fn [] (throw (Exception. "ordinary failure")))])
  ;; => ["first" nil]
  ```

  In `pipeline`, later stages for that item are skipped and its final value becomes `nil`, without affecting other items.

`pipeline` starts every admitted item immediately — there is no batching or chunk barrier between items.

## Cancellation

There are three distinct cancellation mechanisms, and they don't overlap:

1. **Cooperative, inside `:run`** — react to `:cancel-chan` closing or poll `:cancelled?` to stop early. Cancellation does not interrupt `:run` automatically; the factory must check for it.
2. **From outside, server-side** - `cancel-factory-run!` asks the CLI to cancel a run. The runtime then sends a reverse `factory.abort` request, which closes the run's `:cancel-chan` and eventually delivers a `:cancelled` terminal status.
3. **Aborting a local wait, not the run** — `wait-for-factory-run!` accepts an optional `:cancel-chan`; closing it makes the *wait* throw `ex-info` with `{:type :factory-wait-cancelled}` without cancelling the run itself. Use this to stop blocking on a run you still want to keep executing.

## Limits

Resource limits are optional. An omitted limit leaves that dimension unbounded,
except that an omitted `:max-concurrent-subagents` falls back to
`:max-total-subagents` when the latter is set.

Set a ceiling only when the factory's cost profile is known or the user explicitly
requested one. Do not guess limits on a user's behalf: an invented ceiling does not
make a run safer and can stop healthy work with `factory_limit_reached` after the run
has already spent credits. Bound broad fan-out with the factory's own workload
counters instead.

Model-initiated `run_factory` requests still require permission and show the
effective limits. Direct SDK calls to `run-factory!` and `resume-factory!` do not
request permission, so callers are responsible for choosing any ceilings
deliberately.

Per-factory or per-run limits are validated eagerly (unknown keys throw):

| Key | Type | Constraint |
|-----|------|------------|
| `:max-concurrent-subagents` | integer | positive |
| `:max-total-subagents` | integer | positive |
| `:max-ai-credits` | number | positive, finite, and must round to a positive nano-AIU value — very small fractional credits (for example `0.0000000001`) can round to zero and fail validation |
| `:timeout-seconds` | number | positive, finite, at most `2147483.647` |

Limits set in `:meta` apply to every run; limits passed to `run-factory!`/`resume-factory!` apply to that run only.

## Run, resume, observe, and cancel

All functions below live on the public `github.copilot-sdk` facade (aliased `copilot` below) and are `^:experimental`. Each has an async `<`-prefixed twin that runs on a thread and delivers the result — or a caught `Throwable` — on a channel; check `(instance? Throwable result)` before using the value.

| Function | Description |
|----------|-------------|
| `run-factory!` / `<run-factory!` | `[session name-or-handle]` or `[session name-or-handle {:keys [args limits resume-from-run-id]}]` — start a run and block until it reaches a terminal status. `:resume-from-run-id` delegates to `resume-factory!` instead of starting a new run |
| `resume-factory!` / `<resume-factory!` | `[session run-id]` or `[session run-id {:keys [limits]}]` — resume a durable run and block until terminal |
| `get-factory-run` / `<get-factory-run` | `[session run-id]` — read the latest durable envelope (status, result, snapshot) without waiting |
| `wait-for-factory-run!` / `<wait-for-factory-run!` | `[session run-id]` or with `{:keys [cancel-chan poll-interval-ms]}` — block until a run reaches a terminal status |
| `list-factory-runs` / `<list-factory-runs` | `[session]` — list this session's runs in creation order |
| `get-factory-run-detail` / `<get-factory-run-detail` | `[session run-id]` — durable phases, agents, and recent progress |
| `get-factory-run-progress` / `<get-factory-run-progress` | `[session run-id]` or with paging options — page durable progress lines |
| `cancel-factory-run!` / `<cancel-factory-run!` | `[session run-id]` — request cancellation and return the terminal envelope |

## Statuses and errors

A run's `:status` is one of `:pending`, `:running`, `:completed`, `:halted`, `:cancelled`, `:error`. `factory-terminal-status?` returns true for the last four:

```clojure
(copilot/factory-terminal-status? :completed) ;; => true
(copilot/factory-terminal-status? :running)   ;; => false
```

`resume-factory!` classifies a subset of resume failures into a stable `ex-info`:

```clojure
(try
  (copilot/resume-factory! session "unknown-run")
  (catch clojure.lang.ExceptionInfo e
    (:code (ex-data e)))) ;; => :not-found (when the run doesn't exist)
```

`(:type (ex-data e))` is `:factory-resume-error` and `(:code (ex-data e))` is one of `:not-found`, `:non-resumable`, `:already-active`, `:reapproval-declined`, `:no-approval-provider`. Any other resume failure re-throws the original `ex-info` unchanged, with the raw wire error under `(ex-data e)`.

## See Also

- [Custom Agents](custom-agents.md) — inference-selected sub-agents within a single conversation turn
- [API Reference — `join-session`](../reference/API.md#join-session) — extension/child-process session attachment
