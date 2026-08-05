# JMH benchmarks

Measures in-process overhead of `OutcomeObservations` and the bounded tag-value `MeterFilter`.

Not an HTTP load test. Payload is intentionally tiny so scores show instrumentation cost, not I/O.

## Run

```bash
./benchmarks/scripts/run-jmh.sh
```

Smoke (faster, not for publishing):

```bash
./benchmarks/scripts/run-jmh.sh -f 1 -wi 1 -i 3 -r 1s
```

One class:

```bash
./benchmarks/scripts/run-jmh.sh OutcomeObservationsBenchmark
```

Output under `benchmarks/results/` (gitignored). Summarize:

```bash
python3 benchmarks/scripts/summarize-jmh.py benchmarks/results/jmh-<UTC>.json
```

Published table: [`RESULTS.md`](RESULTS.md).

## Suites

| Class | Compares |
|---|---|
| `OutcomeObservationsBenchmark` | baseline, Micrometer `Timer`, Micrometer `Observation`, `OutcomeObservations` (success / failure / classified) |
| `CardinalityFilterBenchmark` | filter `map`: within limit, overflow→`other`, unaffected prefix, 4-thread |

## Defaults (publishable)

| Setting | Value |
|---|---|
| JMH | 1.37 |
| Mode | `AverageTime` (`ns/op`) |
| Warmup | 5 × 1s |
| Measurement | 10 × 1s |
| Forks | 2 |
| Heap | `-Xms512m -Xmx512m` |

Compare deltas vs `baseline` and vs `micrometerObservation`. Re-run on your machine before quoting scores.
