# Node/Clojure Confirmatory Benchmark

Report the single methodology version 2 confirmatory run completed on
2026-08-13.

## Result

The runner completed 30 matched cold-process pairs and 20 matched
steady-process pairs with 320,340 raw observations. The Clojure/Node ratio has
the direction described in the table.

| Endpoint | Geometric mean ratio | Paired bootstrap 95% interval | Raw exact p | Holm-adjusted p | Conclusion |
|----------|---------------------:|------------------------------:|------------:|----------------:|------------|
| Ping latency | 0.929 | [0.901, 0.956] | 0.000139 | 0.000278 | Clojure lower latency |
| Send-and-wait latency | 1.398 | [1.355, 1.436] | 0.00000191* | 0.00000763 | Node lower latency |
| Ping throughput | 1.032 | [0.953, 1.115] | 0.454 | 0.454 | No supported difference |
| Send-and-wait throughput | 0.728 | [0.695, 0.764] | 0.00000191* | 0.00000763 | Node higher throughput |

`*` = `2 / 2^20`, the smallest attainable two-sided exact p-value with
20 pairs.

Latency ratios below 1 favor Clojure. Throughput ratios above 1 favor
Clojure. These conclusions describe public SDK overhead against the
deterministic loopback fixture, not live Copilot service performance.

The ping-throughput result does not establish equivalence, parity,
non-inferiority, or "as good or better."

Each process contributes one confirmatory value per endpoint: the nearest-rank
median of its 4,000 latency samples, or
`1000 * iterations / batch-duration` for throughput. Latency and throughput
within a workload summarize the same measured batch and are not independent
corroborations.

The descriptive sample-timing throughput sensitivity ratios were 1.082 for
ping and 0.704 for send-and-wait, compared with pooled batch-throughput ratios
of 1.080 and 0.704. Sampling-loop work outside each per-call timer interval
does not explain either descriptive direction. These sensitivity values are
not confirmatory endpoints.

## Evidence

The full raw evidence directory is 480 MiB and remains outside Git. The
committed files preserve its exact provenance, complete process-pair effects,
diagnostic summaries, and a manifest covering every raw file:

| File | Purpose | SHA-256 |
|------|---------|---------|
| `metadata.json` | Start provenance | `b38cc389239ca43162959458633939b28dbdbdb3e14a8ba2289286db25936d10` |
| `metadata-final.json` | End provenance | `be16e363ef7e69c0536c6eb8cc87e9349b6dfd4e90e20abce117346824566e1a` |
| `summary.json` | Effects, intervals, tests, pair effects, and diagnostics | `fa852d063a364c5a87b44ac12a5921e97f522c4ac09e1c48520ac8e2148882fc` |
| `evidence-manifest.json` | Hashes and byte lengths for all 206 files in the raw set | `2acb39540f142c5452a30c396d675e4a277f007f5d6c3a9ba7f4f90921805020` |

Primary raw-file hashes from the manifest:

| Raw file | Records | SHA-256 |
|----------|--------:|---------|
| `observations.ndjson` | 320,340 | `c34a5f14127ead972015096b1e2cb43f0b7fbb78d3396bc5ece221f730e749ec` |
| `stability.ndjson` | 6,480 | `6fe3af1b4e0cfdbc220ceb5409382bd727e69e09fb5c5b375914802a94d712d1` |
| `diagnostics.ndjson` | 6,720 | `0a440037536d840fc48521bb8b31216427c7f80a3112f8e0886b82eba428af9e` |

The manifest also covers 100 fixture-state files and 100 request-trace files.

## Provenance and integrity

- Run ID: `rigorous-1786610956890`
- Repository commit during measurement:
  [`c4d0e3a`](https://github.com/copilot-community-sdk/copilot-sdk-clojure/commit/c4d0e3ac8a65623f1746a28a23a553ad6fcc695a)
- Repository tree during measurement: modified relative to `c4d0e3a`; the
  measured code and methodology are pinned by dirty-state SHA-256
  `c265c1cb112401c4c87f631117b792391d21c1f2db41ac5eddd31b521bd3af90`
  and the benchmark-input hash below, not by the commit alone
- Node SDK commit:
  [`811adc0`](https://github.com/github/copilot-sdk/commit/811adc050a82d823cc6f6891576f30058554af8d)
- Benchmark-input hash:
  `c4219a80a12a98995fdf7435b414b13a843ce443f0dff3266bb24ce7043b974b`
- Methodology file hash before measurement:
  `4dbc9377a7cbce1f7d662d97d52b74f498a7672802c1ba5d87b2e78c9ded4e50`
- Runner exit: 0
- Start/end repository commit, dirty-state hash, benchmark-input hash, Node
  package hashes, corpus hash, host ID, and toolchains: identical
- Fixture states: 100; rejected fixtures: 0; connection count: 1 each
- Manifest verification: all 206 hashes and byte lengths matched
- Independent statistics recomputation: all four ratios, bootstrap bounds, raw
  exact p-values, and Holm-adjusted p-values matched `summary.json` exactly

No pair was deleted, replaced, or reused from an earlier attempt.

The runner artifact does not independently encode the fixed 15-minute
pre-run quiescence wait. Session execution logs record the wait, but this
protocol step cannot be verified from the raw evidence set alone.

## Stationarity and runtime diagnostics

The unchanged 15% warmup and 10% measured drift references remained
report-only. Seven reference exceedances occurred across six
process/workload groups and all were retained:

| Implementation | Workload | Replicate | Warmup drift | Measured drift |
|----------------|----------|----------:|-------------:|---------------:|
| Clojure | Ping | 0 | 19.9% (outside) | 9.7% |
| Clojure | Ping | 17 | 15.9% (outside) | 14.3% (outside) |
| Clojure | Send-and-wait | 13 | 16.3% (outside) | 4.5% |
| Clojure | Send-and-wait | 15 | 4.9% | 13.4% (outside) |
| Clojure | Send-and-wait | 18 | 7.1% | 15.6% (outside) |
| Node | Ping | 12 | 22.9% (outside) | 5.2% |

Recorded one-minute host load ranged from 7.78 to 30.38 on the 10-core host.
The result therefore describes this recorded environment; it is not a claim
about every machine or workload.

## Root-cause diagnosis

The earlier protocol required all 160 process-level drift checks to pass.
That condition was not a calibrated stationarity test and selected on the same
latency trajectories used for inference. Assuming independent checks, even a
1% false-rejection probability per check gives only about a 20% probability
that all 160 checks pass.

A fresh diagnostic-only reproduction under the old hard gate failed Clojure
ping warmup at 17.3% drift while its subsequent measured drift was 1.9%. A
normal 3 ms JVM garbage collection immediately preceded the warmup median
shift; the fixture accepted every request and host thermal status remained
normal. This was consistent with sensitivity to ordinary GC/window phase
rather than a deterministic fixture defect. See the
[diagnostic pilot report](../2026-08-13-diagnostic-pilot/REPORT.md). Those
artifacts were not used for confirmatory inference.

Methodology version 2 was declared before the confirmatory run. It retained the
15% and 10% values as reported covariates, removed drift-based aborts, and kept
integrity failures as the only abort conditions. See
[`METHODOLOGY.md`](../../METHODOLOGY.md).
