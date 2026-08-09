# ADR: Add scope-bound query sequences before deprecating query-seq!

- Status: Proposed
- Deciders: @krukow
- Related: [`doc/reference/API.md#query-seq`](../reference/API.md#query-seq),
  [`doc/reference/API.md#query-chan`](../reference/API.md#query-chan),
  finding `COR-002` (GA Phase 2 correctness/lifecycle review, remediation `R8`),
  [issue #127](https://github.com/copilot-community-sdk/copilot-sdk-clojure/issues/127)

## Context

`github.copilot-sdk.helpers/query-seq!` returns a bounded lazy sequence backed
by a live session and an event tap (`src/github/copilot_sdk/helpers.clj:233-289`).
Cleanup (`copilot/disconnect!`) is wired into the lazy-seq generator itself and
only fires on one of three "natural ends": a `:copilot/session.idle` /
`:copilot/session.error` event, the events channel closing (`nil` on the next
read), or the `:max-events 0` special case. Any ordinary, otherwise-idiomatic
seq operation that doesn't force full realization abandons the session:

```clojure
(def opts {:on-permission-request copilot/approve-all})

(first (h/query-seq! "hi" :session opts))     ; leaks unless the 1st event is terminal
(take 1 (h/query-seq! "hi" :session opts))    ; same
(some pred (h/query-seq! "hi" :session opts)) ; leaks if pred matches before the end
```

The public docstring documents this precisely (and did not always --
[issue #127](https://github.com/copilot-community-sdk/copilot-sdk-clojure/issues/127)
corrected a prior, false "guaranteed cleanup ... even if the consumer stops
early" claim). But documentation cannot make a leaky return type safe: the
caller has no handle to close, and nothing signals abandonment. In a
long-lived host, repeated early abandonment accumulates orphaned
sessions/taps with no way for the caller to detect or recover them.

The GA Phase 2 correctness/lifecycle review confirmed this as `COR-002`
("Strong" confidence, "Needs ADR" disposition). Remediation `R8` ("P1
decision") required a separate, implementation-free ADR choosing between
deprecation/removal, a closeable reducible/iterator wrapper, an explicitly
closeable channel/handle API, or another idiomatic design -- ruling out
finalizers, retries, and hidden-timeout fallbacks as "success-shaped"
non-fixes.

**`query-chan`'s safety is narrower than its docstring currently states.**
`query-chan` (`helpers.clj:291-351`) pipes events through a bounded
(default 256) output channel via an internal `go-loop` that `>!` puts each
event and disconnects once it reaches its own terminal event or `events-ch`
closes. Its docstring claims this is "safe to stop early provided you close
the returned channel." Verified directly (minimal core.async repros, not
taken on faith): that claim only holds if the consumer closes the channel
*before* the internal buffer fills and a put parks, or keeps draining. If the
consumer stops reading and the session emits more events than the buffer
holds before its terminal event, the producer's `>!` parks -- and **closing
the channel after a put has already parked does not release it**
(`close!` only makes *subsequent* puts fail fast; an in-flight parked put is
unaffected). In that case `query-chan` leaks exactly like `query-seq!` does
today, just at a higher event-count threshold instead of on first
realization, and a "helpful" close called too late gives no rescue. This is a
pre-existing, previously-undocumented gap in `query-chan`'s own contract that
this review surfaced; it is not fixed here (out of `COR-002`'s scope, which is
about `query-seq!`), but the tests below make the real behavior explicit
rather than assumed, and this ADR does not claim `query-chan` as a fully safe
answer to "may stop early."

The codebase's established idiom for "create a resource, guarantee release on
scope exit" is a `with-*` macro over `try`/`finally` -- see `with-session` and
`with-client` (`src/github/copilot_sdk.clj:391,599-611`). There is no
`Closeable`/`AutoCloseable` type anywhere in the SDK's public surface.
`clojure.core/line-seq` is the same shape of problem in the standard library
(a lazy seq over a live `BufferedReader`); its accepted idiom is `with-open`
around the consuming form, not a `Closeable` `line-seq` return value.

**Two related gaps found while reading the implementation, not previously
filed:** `query-seq!` does not guard `(copilot/send! sess ...)` with
`try`/`catch` the way `query-chan` does, so a `send!` failure before the seq
is returned leaks the already-created session; and `::max-events` is specced
`pos-int?` (`specs.clj:2201`), rejecting the documented, load-bearing `0`
value -- untested only because no test today exercises
`query-seq!`/`query-chan`/`query` at all.

## Decision

Add `github.copilot-sdk.helpers/with-query-seq`, a macro that binds a lazy
sequence of events for the dynamic extent of its body and guarantees
session/tap cleanup in a `finally` when the body exits -- by return,
exception, or early termination -- regardless of how much of the sequence was
realized:

```clojure
(with-query-seq [events "Tell me a story" :session opts]
  (->> events
       (filter #(= :copilot/assistant.message_delta (:type %)))
       (map #(get-in % [:data :delta-content]))
       (run! print)))
```

This mirrors `with-session`/`with-client`: the resource is created eagerly and
released in a `finally` independent of consumption pattern. Internally, the
macro extracts a **private** helper, `query-seq-source`, from today's
`query-seq!` body, returning `[seq finish!]` instead of just `seq` (`finish!`
reuses the existing idempotent `done?` atom guard, so a second call from the
macro's `finally` after natural completion is a no-op). No new
resource-management protocol, `Closeable` type, or reducible/iterator
machinery is introduced.

**`query-seq-source` stays private, invoked via Var-quote, not made public.**
A macro's syntax-quote expansion normally resolves symbols to fully-qualified
form (e.g. `github.copilot-sdk.helpers/query-seq-source`), and the Clojure
compiler rejects a bare qualified reference to a private var from any
namespace but the one that defined it (`var: ... is not public`) -- which
would break at every real call site, since callers always live in a different
namespace. The fix is not to make the helper public; it is for the macro's
expansion to invoke it through a Var-quote, `#'query-seq-source` (equivalently
`(var query-seq-source)`), which resolves and derefs the Var directly and is
unaffected by the private-visibility check applied to bare symbol references.
This is confirmed with a minimal two-namespace repro (private `defn-` helper,
macro expansion using `(var helper)`, invoked from a second namespace: returns
correctly). `query-seq-source` therefore never becomes part of the public or
documented surface -- no `^:no-doc` marker or facade export is needed, and no
new lifecycle-shaped API surface is added beyond the macro itself.

### Deprecation is a later, separate step -- not part of this change

This ADR does **not** deprecate `query-seq!` yet. `query-seq!` keeps its
current signature, behavior, and docstring unchanged when `with-query-seq`
ships; it is refactored internally to delegate to `query-seq-source` (a
non-observable change) but is not labeled deprecated until the replacement has
actually shipped and proven itself. Rationale and cost, quantified rather
than assumed:

- **In-repo exposure is small and known:** exactly one production call site
  (`examples/helpers_query.clj:41`, the `run-streaming` example) and doc
  mentions in four files (`doc/getting-started.md:98`, `doc/reference/API.md`,
  `examples/README.md` -- two locations). Zero test references exist today.
  The implementation PR owns migrating all of these to `with-query-seq`.
- **External exposure is unknowable.** `query-seq!` has been published to
  Maven Central since `1.0.0` on a stable name; any number of external
  consumers may depend on it, and this repo has no visibility into that
  usage. Deprecating (let alone removing) it without a released,
  battle-tested replacement first would impose migration cost on unknown
  parties for no proven benefit.
- **Sequencing:** (1) ship `with-query-seq` and its tests; (2) once it has
  shipped in at least one published release and its critical tests
  (cleanup-on-early-exit, cleanup-on-exception, cross-namespace expansion)
  have held, mark `query-seq!` deprecated in place -- docstring + doc heading,
  following the existing `destroy!` -> `disconnect!` precedent
  (`src/github/copilot_sdk.clj:971-976`), in a *later* PR, not this one; (3)
  removal is a **separate future breaking-change decision**, not before the
  Clojure SDK's next major API release, and only after `with-query-seq` has
  been available for at least one published release with migration docs in
  place. This ADR authorizes step (1) only.

| Symbol | Change |
|---|---|
| `with-query-seq` | **New** macro; recommended default for seq-style consumption going forward. |
| `query-seq-source` | **New**, private fn; returns `[seq finish!]`. Invoked from the macro via Var-quote. |
| `query-seq!` | **Unchanged** signature/behavior/docstring now; internally delegates to `query-seq-source`. Deprecation is a later PR (see above). |
| `query-chan`, `query` | Unchanged. |
| `::max-events` spec | Bug fix: `pos-int?` -> accepts `0` (e.g. `nat-int?`). |

## Consequences

**Positive:** the `COR-002` defect is closed *for new code that adopts
`with-query-seq`*, without a new resource-lifetime abstraction -- the fix
reuses the proven `event-seq`/`finish!` internals and the codebase's existing
macro idiom. No forced migration in this change. The two adjacent gaps
(setup-failure leak parity, `::max-events` spec) are fixed alongside it.

**Honest residual risk -- this does not close `COR-002`, only mitigates it for
the default path:** `query-seq!` remains fully available, unlabeled, and
exhibits the exact same early-abandonment leak as before, for as long as it
exists. Nothing in this decision prevents any caller -- in this repo or
externally -- from continuing to write `(first (query-seq! ...))`. The defect
is only actually *closed* once `query-seq!` is removed, which this ADR
explicitly defers to a future breaking-change decision. Until then, `COR-002`
should be tracked as "mitigated for the recommended path," not "resolved."

**Other costs:** `with-query-seq`'s escape-discipline requirement (don't let
the bound seq outlive the body, e.g. by closing over it in a future/thread)
mirrors `with-open` -- an accepted risk class, but not zero risk; misuse tears
the session down mid-consumption rather than merely leaking it (louder, but
still a footgun of its own). Two supported seq-style consumption paths will
exist once `query-seq!` is eventually deprecated, more surface to explain in
docs during that window.

## Alternatives

**A. Deprecate and remove `query-seq!` outright now**, keeping only `query`
and `query-chan`. The literal "smallest coherent remediation" text in
`COR-002`, and matches this repo's own precedent of removing the original
`helpers/query-seq` for `query-seq!`/`query-chan`. Rejected: it deletes the
one ergonomic `->>`/`filter`/`map`/`run!`-composable way to consume a bounded
stream that the getting-started tutorial leads with, forcing every such
caller onto `go-loop`/`<!` immediately, with no deprecation window and no
proof the replacement works in practice first, for no extra safety over
`with-query-seq` once it exists and has been used.

**B. Closeable reducible/iterator wrapper** (`IReduceInit` + `Closeable`,
usable via `with-open`, optionally also `Seqable` so `filter`/`map`/`first`
keep working). Rejected, but not because it *cannot* be `Seqable` -- it can:
a type can implement `IReduceInit`, `Closeable`, and `Seqable` together. The
real problem is that `Seqable`-or-not, a caller who simply doesn't wrap it in
`with-open` gets exactly today's leak back -- `(first (my-wrapper ...))`
outside a `with-open` abandons the resource identically to `query-seq!`
today. The safety this alternative offers is entirely contingent on the same
scope-discipline `with-query-seq` already requires, but reached via a new
Java-interop type implementing three protocols instead of one macro -- the
SDK's first `Closeable`, inconsistent with the macro-only lifecycle idiom
used everywhere else, for a lifecycle/migration/type-surface cost with no
safety `with-query-seq` doesn't already provide.

**C. Push everyone onto `query-chan` (docs-only)**, formalizing it as the
sole prescribed path for non-full-drain consumption. Rejected as the sole
remedy for two reasons: `query-chan`'s existing docstring already steers
callers this way and `COR-002` was filed *after* that text existed (evidence
docs alone don't work); and, per the corrected analysis in Context,
`query-chan` itself is not unconditionally safe on early abandonment -- it
depends on buffer size and close timing. `query-chan` remains unchanged as
the async-native option, but is not treated here as a complete answer.

**D. Finalizers / hidden timeouts / retry-based cleanup.** Rejected per `R8`'s
own framing: finalizers run at GC's discretion with no deadline; a hidden
inactivity timeout silently changes the contract for callers who intended to
keep a session open; a retry-based fallback is success-shaped -- it quiets
failures without making the ownership model correct.

## Migration / compatibility plan

**This change (authorized now):** add `with-query-seq` and `query-seq-source`;
refactor `query-seq!` to delegate to `query-seq-source` with no observable
change; fix `::max-events`; migrate all in-repo docs/examples listed above to
recommend `with-query-seq` by default (`query-seq!` stays documented as an
existing, still-working option, not yet marked deprecated). `query-chan`'s
docstring should be corrected separately to reflect the buffer/close-timing
nuance above -- flagged here, not fixed in this ADR's scope.

**Tests** (currently zero direct tests exist for
`query`/`query-seq!`/`query-chan`):

- `with-query-seq`, exercised from a **separate test namespace** (as all test
  namespaces are): confirms the Var-quote expansion actually compiles and
  runs -- the regression guard for the private-var/macro-expansion constraint.
- `with-query-seq`: realize one event, body returns early (`first`-shaped);
  assert the session disconnects and the tap closes immediately.
- `with-query-seq`: a positive `:max-events` bound is reached and the body
  exits normally afterward; assert cleanup still ran (regression guard for
  the exact "positive bound is not a cleanup guarantee" gap `COR-002`
  describes for `query-seq!`).
- `with-query-seq`: body throws; assert cleanup still runs.
- `query-seq-source`/`query-seq!` setup failure: `send!` throws before the
  seq/`finish!` pair is returned; assert the already-created session is
  disconnected (closes the gap noted in Context).
- `query-seq!`/`query-seq-source`: full natural-end consumption still
  disconnects exactly once (idempotency regression guard).
- `:max-events 0` under instrumentation, for both `query-seq!` and
  `with-query-seq`.
- `query-chan`, consumer reads some events then closes the channel *before*
  the buffer fills: producer completes and disconnects (the documented-safe
  path).
- `query-chan`, consumer stops reading without closing and the session emits
  enough events to fill the buffer: producer parks and does not disconnect
  within a bounded wait -- documents the residual risk explicitly instead of
  leaving it assumed-safe.

**Deferred (not authorized by this ADR):** marking `query-seq!` deprecated
(future PR, after `with-query-seq` has shipped in >=1 release and the above
tests have held); removing `query-seq!` (future breaking-change decision, no
earlier than the Clojure SDK's next major API release, after `with-query-seq`
has been available for >=1 published release with migration docs).

No companion design doc: this is a macro plus an internal refactor, fully
explained here and by the code itself.

## Upstream-evolution implications

`query`, `query-seq!`, `query-chan`, and `with-query-seq` have no equivalent
in the upstream Node.js/Python SDKs -- `helpers.clj` is a Clojure-only
convenience layer, not re-exported from the `github.copilot-sdk` facade, and
outside the strict upstream-parity rules in `AGENTS.md`. No wire format or
`SessionConfig` shape is affected, and no future upstream release can
conflict with this decision.
