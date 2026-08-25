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

## Pipeline overhead (indicative)

`PipelineOverheadBenchmark` measures the fully composed convention (reason registry + reason
budget + combination guard + privacy policy) against the unenforced paths.

Reference run — **developer laptop, reduced iterations: indicative, not authoritative.**
Apple M2 Max, OpenJDK 25.0.4.1, JMH 1.37, `-f 1 -wi 3 -w 1s -i 5 -r 1s`, avgt ns/op:

| Benchmark | ns/op |
|---|---|
| raw Micrometer `Timer.record` | ~50 |
| `record` success (unenforced) | ~1 270 |
| `record` reasoned failure (unenforced) | ~1 530 |
| `record` success, **full enforcement** | ~1 900 |
| `record` success, full enforcement + open `OutcomeScope` | ~1 930 |
| `record` reasoned failure, full enforcement | ~2 070 |
| `startDeferred` + `succeed`, full enforcement | ~1 870 |

Reading: the Micrometer Observation machinery itself costs ~1.2 µs over a raw timer; the entire
enforcement pipeline adds roughly 0.6 µs on top, and an open scope ~30 ns. Reproduce with:

```bash
./benchmarks/scripts/run-jmh.sh PipelineOverheadBenchmark
```
