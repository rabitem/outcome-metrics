# JMH results

Published run: **2026-08-05T20:23:51Z** (pre-schema baseline). For the current convention-pipeline
overhead numbers (0.1.0-beta.4 schema with full enforcement), see the
[Pipeline overhead](README.md#pipeline-overhead-indicative) section in the benchmarks README — that run supersedes the
observation figures below.

## Environment

| | |
|---|---|
| Host | Darwin 25.5.0 `arm64` (Apple M2 Max) |
| JVM | Corretto 25.0.3+9-LTS |
| JMH | 1.37 |
| Mode | `AverageTime`, `ns/op` |
| Warmup | 5 × 1s |
| Measurement | 10 × 1s |
| Forks | 2 |
| Heap | `-Xms512m -Xmx512m` |

Re-run: `./benchmarks/scripts/run-jmh.sh`

## OutcomeObservationsBenchmark

Trivial payload (`Blackhole.consume(1)`). Prefer deltas vs `baseline` / `micrometerObservation`.

| Benchmark | Threads | Score ± Error | Unit |
|---|---:|---:|---|
| `baseline` | 1 | 0.36 ± 0.01 | ns/op |
| `micrometerTimer` | 1 | 55.68 ± 1.26 | ns/op |
| `micrometerObservation` | 1 | 414.13 ± 5.38 | ns/op |
| `outcomeObservationsSuccess` | 1 | 814.51 ± 29.82 | ns/op |
| `outcomeObservationsFailure` | 1 | 998.51 ± 23.51 | ns/op |
| `outcomeObservationsClassified` | 1 | 1,107.83 ± 23.04 | ns/op |

Rough deltas on this machine:

| Comparison | Δ |
|---|---|
| `outcomeObservationsSuccess` − `micrometerObservation` | ~400 ns/op |
| `outcomeObservationsSuccess` − `baseline` | ~814 ns/op |

## CardinalityFilterBenchmark

| Benchmark | Threads | Score ± Error | Unit |
|---|---:|---:|---|
| `mapUnaffectedPrefix` | 1 | 3.25 ± 0.09 | ns/op |
| `mapWithinLimit` | 1 | 23.09 ± 0.52 | ns/op |
| `mapOverflowSteady` | 1 | 121.11 ± 2.16 | ns/op |
| `mapWithinLimitContended` | 4 | 310.82 ± 4.14 | ns/op |
| `mapOverflowContended` | 4 | 836.69 ± 9.00 | ns/op |

## Notes

- Scores are for near-empty work; real I/O dominates in services.
- Failure path uses a stackless `OutcomeReasonSource` exception (isolates instrumentation cost).
- Raw JMH JSON/txt under `benchmarks/results/` are gitignored.
