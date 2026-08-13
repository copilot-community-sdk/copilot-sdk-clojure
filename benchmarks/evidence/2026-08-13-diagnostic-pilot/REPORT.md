# Hard-Gate Diagnostic Pilot

Document the nonconfirmatory reproduction used to diagnose the original
all-process stationarity gate.

## Outcome

The pilot ran the unchanged rigorous per-process workload protocol with the
original hard gate. Clojure ping replicate 0 recorded:

| Diagnostic | Value | Original bound |
|------------|------:|---------------:|
| Final warmup relative drift | 17.3% | 15% |
| Measured relative drift | 1.9% | 10% |

The driver completed all 20,000 warmup and 4,000 measured ping operations,
wrote the raw observations and diagnostics, then exited because the warmup
reference was outside the old hard bound. The measured region itself was
within its bound.

At warmup window 71, the JVM GC count increased by one, cumulative GC time
increased by 3 ms, and recorded heap use fell from approximately 343 MiB to
32 MiB. The final warmup median shift followed that normal collection. This is
consistent with gate sensitivity to ordinary GC/window phase.

The fixture reported `failed: false`, one connection, and exactly 24,002 ping
calls: one preflight, 20,000 warmup, 4,000 measured, and one postflight. No
fixture rejection or request-count loss caused the drift result.

## Scope

This pilot is diagnostic only:

- it contains one Clojure workload and no matched Node pair;
- it has no `metadata-final.json` or `summary.json`;
- its immutable `stability-legacy-v1.ndjson` uses the superseded hard-gate
  shape documented by `legacy-stability.schema.json`, not the current
  report-only stability schema;
- it contributes no pair, effect, interval, p-value, or conclusion to the
  confirmatory study;
- it was never reused after methodology version 2 removed drift-based
  selection.

## Files

| File | SHA-256 |
|------|---------|
| `metadata.json` | `6244280eca9629ef4fb5e2813e6e6f4cb28a5352176df697d02255e68daa8fc4` |
| `observations.ndjson` | `df049455f4c4ff8648ff92ddf4f9057948106d00b2cf772e4b7d918bff740500` |
| `stability-legacy-v1.ndjson` | `13966e606ba8c4d03ac7c3488c84a217ce60c5309744e492f59474b0eee4bc69` |
| `diagnostics.ndjson` | `1d5a8bb6deae50219a338f7d05dea64e403b613bbf95d6769ee026bdcf15affc` |
| `clojure-steady-000-fixture.json` | `19e49a7f42ccaa609e51f4cb9e19840405b0e0832e30f95b084a6953c1a9d50c` |

The full request trace remains outside Git. Its SHA-256 is
`3578b49e1272a0cbfc10d6e0d962df5232da12923efdc7e46dddab8e61fca8d5`.
