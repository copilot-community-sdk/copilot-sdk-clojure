# Matched Node/Clojure Benchmarks

Run both SDKs through the same deterministic TCP JSON-RPC fixture. The harness
measures implementation overhead without model, network, or live Copilot
service latency.

**Evidence status:** the confirmatory profile is executable and predeclared, but
no complete 20-pair confirmatory result is included. All existing local result
sets are exploratory, failed, or incomplete. Do not make a directional
performance conclusion from this repository change.

## Prepare the Node SDK

Resolve the canonical upstream checkout and build its public package:

```bash
UPSTREAM="$(bash .github/skills/update-upstream/scripts/resolve-upstream.sh)"
npm --prefix "$UPSTREAM/nodejs" install --ignore-scripts
npm --prefix "$UPSTREAM/nodejs" run build
```

Set `COPILOT_BENCH_NODE_SDK_ROOT` to an alternative built Node SDK package root.
The runner otherwise uses the repository resolver. It fails if the package is
missing or does not publicly export `CopilotClient` and
`RuntimeConnection.forUri`; it never falls back to a sibling checkout.

## Run

```bash
bb benchmark:test
bb benchmark:smoke
bb benchmark:rigorous
```

The smoke profile uses 2 cold processes, 20 warmup operations, and 10 measured
operations. Its deliberately loose stability bounds prove protocol, validation,
cleanup, and analysis behavior but are not performance evidence.

The rigorous profile uses 30 independent cold processes and 20 independent
steady processes per implementation. Each steady process runs 20,000 excluded
warmup operations and 4,000 measured operations for each workload. Concurrency
is explicitly 1. The fixed warmup is divided into 250-operation windows. In the
final 16 windows, the median of the first eight window medians and the median
of the last eight must have relative drift at most 15%. The first and last
2,000-operation halves of the measured 4,000 operations must also have median
drift at most 10%. These predeclared checks apply identically to both SDKs; any
failed process aborts the run without truncating or deleting operations. Cold
and steady execution alternate which implementation runs first to reduce
host-drift bias. Override the output directory by invoking the runner directly:

```bash
clojure -M:bench -m github.copilot-sdk.bench.runner \
  --profile=rigorous \
  --output=/path/to/results
```

## Method

Each implementation uses only its public SDK:

- Clojure constructs a client with `:cli-url`, starts it, calls `ping`, creates
  a session, and calls `send-and-wait!`.
- Node constructs `CopilotClient` with `RuntimeConnection.forUri`, starts it,
  calls `ping`, creates a session, and calls `sendAndWait`.

Both clients connect to a standalone loopback fixture over TCP with
Content-Length-framed JSON-RPC. The corpus fixes the connection token, protocol
version, ping, prompt, response, model, event order, and timestamps. Fixture
traces retain raw request hashes and a normalized comparable sequence hash.
Normalization removes only JSON-RPC correlation IDs and replaces session IDs
with a sentinel; the complete request envelope and all other nested parameters
participate in the per-request comparison hash. The fixture rejects wrong
JSON-RPC versions, missing/invalid IDs, and unexpected top-level keys. Responses
and events are compared as complete envelopes. Every cold sample and steady
replicate gets a fresh external fixture. During measured traffic the fixture
only constructs responses and buffers trace inputs in memory; canonicalization,
hashing, serialization, and file writes happen during fixture shutdown.

Cold `process` latency is parent-observed wall time from process spawn through
successful cleanup. Cold `connect-ping` latency starts after the language driver
and SDK namespace/package have loaded. Steady latency excludes warmup.
Throughput is measured batch operations divided by batch wall time at
concurrency 1; raw-observation serialization occurs after the timed batch. RSS
delta is `ps` resident bytes immediately after the measured batch minus resident
bytes immediately after warmup; cold RSS uses immediately before client
construction and immediately after validated ping. The harness does not force
GC: RSS delta describes observed process growth under the workload, not retained
heap or a language-independent allocation metric.

Each steady workload performs an untimed validated preflight, then warmup, then
the measured batch, samples RSS, and finally performs an untimed validated
postflight. Timed operations await the public SDK call and its result
materialization but discard the deterministic result without harness assertions.
The fixture validates every request. A pre/postflight validation failure aborts
before observations or stability evidence are written. Fixture count/trace
expectations include both validation calls identically for Node and Clojure.

## Confirmatory inference

The rigorous profile predeclares one family of four steady endpoints:

1. ping latency
2. send-and-wait latency
3. ping throughput
4. send-and-wait throughput

The matched process pair is the inferential unit. For each pair and endpoint,
compute `log(Clojure / Node)` using that process's median operation latency or
batch throughput. Report the geometric mean ratio across 20 process pairs and a
paired-process bootstrap 95% interval by resampling matched pairs with
replacement (seed 424242, 10,000 resamples).

Test the mean paired log ratio with an exact two-sided sign-flip randomization
test over all `2^20` assignments. Apply Holm correction across all four
endpoints at familywise alpha 0.05. Directional wording is allowed only when the
Holm-adjusted p-value is at most 0.05; every other endpoint is labeled
`no-supported-difference`. No endpoint or replicate is removed.

Operation-level pooled p50/p95/p99 and pooled ratios are descriptive only and
appear separately from the confirmatory process-pair results. Twenty pairs give
minimum two-sided exact resolution `2 / 2^20`, unlike five pairs whose minimum
resolution is 0.0625.

The runner serializes cold samples, applies bounded timeouts, validates every
result, confirms child and fixture cleanup, verifies exact request counters,
and validates the exact unique observation tuples expected by the profile.
Duplicate, missing, malformed, non-finite, and unexpected observations fail the
run. Warmup-window and measured-drift records are also exact-schema and
exact-tuple validated; unstable rigorous records fail the run. It refuses
comparison unless each matched fixture pair has identical
counts, per-request comparable hashes, and normalized sequence hashes, and
unless start/end provenance and corpus hashes match.

The benchmark JVM maintains one shutdown hook and an atomic draining registry
of every fixture/driver process it spawns. Normal cleanup can unregister only a
confirmed-dead process. Shutdown atomically enters draining state, terminates
deepest descendants before ancestors/parents, and handles concurrent late
registrations immediately without touching unrelated processes.

The deterministic fixture does not invoke Copilot CLI. CLI version remains
useful environment provenance, but it is optional: metadata records either
`{"status":"available","value":"..."}` or a structured
`{"status":"unavailable",...}` value. All metadata commands that affect the
actual benchmark remain required and fail loudly.

## Output

Each run writes:

| File | Contents |
|------|----------|
| `metadata.json`, `metadata-final.json` | Start/end repository HEAD and dirty hash; benchmark-input hash; upstream commit/dirty hash; loaded Node entry, full dist, package, and lock hashes; toolchains; host; profile; corpus; and bootstrap settings |
| `observations.ndjson` | One raw observation per line using `schema/observation.schema.json`, including its independent process `replicate` |
| `stability.ndjson` | Per-process warmup-window medians, actual cumulative warmup counts, final stability outcomes, and first/last measured-window drift using `schema/stability.schema.json` |
| `*-NNN-fixture.json` | Fresh per-replicate exact request/connection counts, corpus hash, cleanup signal, and comparable sequence hash |
| `*-NNN-trace.ndjson` | Fresh per-replicate raw-request and full-envelope comparable hashes; full canonical inputs remain in the deterministic fixture/corpus source |
| `summary.json` | Descriptive operation summaries, raw stability diagnostics, and separate four-endpoint confirmatory process-pair effects/CIs/exact p-values/Holm conclusions |

Ratios are labeled in `summary.json`. Descriptive operation summaries never
drive directional conclusions. Confirmatory latency ratios below 1 favor
Clojure; throughput ratios above 1 favor Clojure. RSS remains descriptive, and
its multiplicative ratio is omitted unless every matched delta in both
implementations is strictly positive. Zero, negative, and mixed-sign pairs are
retained in raw/descriptive values but never conditionally dropped or absolutized.

Smoke is correctness-only. A future rigorous run may use directional wording
only from a complete `summary.json` endpoint whose Holm-adjusted exact p-value
is at most 0.05. Operation summaries and bootstrap intervals never establish a
directional conclusion by themselves.
