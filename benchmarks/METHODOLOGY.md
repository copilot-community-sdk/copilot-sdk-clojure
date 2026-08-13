# Confirmatory Benchmark Methodology

Predeclare the matched Node/Clojure performance study before collecting
confirmatory evidence.

## Protocol revision

Methodology version 2 was declared on 2026-08-13 before its confirmatory run.
Run exactly one fresh rigorous profile after this declaration. Do not reuse any
pair, observation, or statistic from earlier attempts or diagnostic pilots.

The rigorous profile fixes:

- 30 matched cold-process pairs;
- 20 matched steady-process pairs;
- 20,000 warmup operations and 4,000 measured operations per steady workload;
- concurrency 1;
- alternating implementation order within successive pairs;
- ping latency, send-and-wait latency, ping throughput, and send-and-wait
  throughput as one four-endpoint family;
- the matched process pair as the inference unit;
- paired log effects, paired-process bootstrap intervals with seed 424242 and
  10,000 resamples, exact two-sided sign-flip tests, and Holm correction at
  familywise alpha 0.05.

Report every predeclared pair. Do not delete, replace, rerun, or restart a pair
because of latency, throughput, drift, effect direction, significance, or
diagnostic values.

After completing pre-run validation commands, wait a fixed 15-minute
quiescence period before starting the rigorous profile. Start after that period
regardless of the observed host load, and record the load rather than selecting
a start time from it.

## Stationarity diagnostics

Treat the existing 15% warmup drift and 10% measured drift values as
report-only reference bounds. They are not acceptance criteria.

For each implementation, workload, and process, report:

- the median drift between the final two groups of eight 250-operation warmup
  windows, relative to the earlier group;
- the median drift between the first and last 2,000 measured operations;
- whether each drift falls within its unchanged reference bound;
- checkpoint CPU, RSS, heap, GC/JIT or V8 code-state, host load, elapsed time,
  pair order, and workload order;
- every raw measured latency.

Collect runtime checkpoints between warmup windows and immediately before or
after the measured batch. Do not collect diagnostics inside a timed SDK call.
Apply the same checkpoint schedule to both implementations; use nullable fields
where a runtime does not expose an equivalent counter without in-band tracing.

The reference diagnostics cannot justify process selection. A rigorous study
contains 160 reference comparisons:

```text
20 pairs * 2 implementations * 2 workloads * 2 drift diagnostics = 160
```

If a stationary diagnostic independently crosses its reference with
probability `q`, requiring every comparison to pass has probability
`(1 - q)^160`. At `q = 0.01`, the all-pass probability is about 0.200. Repeating
the study until all comparisons pass would select on endpoint trajectories and
create optional stopping. Reporting the diagnostics as covariates preserves
the fixed sample and exposes noise without conditioning inference on it.

## Abort criteria

Abort the complete run only for an integrity failure:

- malformed, duplicate, missing, or unexpected observation or diagnostic
  records;
- fixture request rejection, request-count mismatch, trace mismatch, or
  connection-count mismatch;
- process failure, timeout, or unconfirmed cleanup;
- start/end provenance, corpus, benchmark-input, Node package, or toolchain
  mismatch;
- invalid confirmatory analysis input.

Do not abort for a stationarity reference exceedance, noisy result,
non-significant result, or unfavorable effect direction. If an integrity
failure aborts the run, retain the failed evidence for diagnosis but do not use
it for inference or reuse its completed pairs.

## Interpretation

Report geometric mean Clojure/Node ratios, paired bootstrap 95% intervals, raw
exact sign-flip p-values, Holm-adjusted p-values, pair-level effects, and all
stationarity/runtime diagnostics.

Use directional wording only when the Holm-adjusted p-value is at most 0.05.
Otherwise label the endpoint `no-supported-difference`. A non-significant
result does not establish equivalence, parity, non-inferiority, or "as good or
better."

Latency and throughput for one workload summarize the same measured batch and
are not independent corroborations. Holm correction remains valid under that
dependence. Do not present a count of significant endpoints as a count of
independent confirmations.

Batch throughput includes the runtime-specific loop, timer conversion, sample
storage, and asynchronous continuation overhead around each SDK call. Report
`1000 * iterations / sum(raw latency samples)` as a sample-timing-derived
descriptive sensitivity value alongside batch throughput. It excludes
sampling-loop work outside each per-call timer interval but retains timer
boundary overhead. Keep it outside the four-endpoint family and do not use it
for directional conclusions.
