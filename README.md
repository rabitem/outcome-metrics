# outcome-metrics

[![CI](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml/badge.svg)](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/rabitem/outcome-metrics/badge)](https://scorecard.dev/viewer/?uri=github.com/rabitem/outcome-metrics)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/13963/badge)](https://www.bestpractices.dev/projects/13963)

**Business-outcome observations for Micrometer, with the guardrails built in.**

Record a unit of work once and get a timer (plus a span when tracing is on) carrying a closed,
low-cardinality schema: `outcome`, `reason`, `integrity`, `alertability`, `occurrence` — and
opt-in classifications for idempotency, reconciliation, delivery fate, retry shadow, and RAG
grounding. Cardinality, PII, and vocabulary drift are enforced in the pipeline, not in code
review.

> Status: **0.1.0-beta.4** (pre-1.0). APIs may still move.

**Docs:** [Handbook](docs/handbook/README.md) · [Samples](samples/README.md) ·
[Benchmarks](benchmarks/README.md) · [OpenSSF Best Practices](docs/openssf-best-practices.md) ·
[llms.txt](llms.txt)

## Quickstart

```xml
<!-- Spring Boot 4 -->
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-spring-boot-starter</artifactId>
  <version>0.1.0-beta.4</version>
</dependency>

<!-- Quarkus 3 -->
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-quarkus</artifactId>
  <version>0.1.0-beta.4</version>
</dependency>
```

```java
return observations.of("voucher.command")
    .dims(KeyValues.of("command", "apply"))
    .record(() -> doApply(command));
```

Or annotate — the interceptor handles sync and reactive (`Mono`/`Flux`, `Uni`/`Multi`) return
types alike:

```java
@MeasuredOutcome(name = "voucher.command", tags = {"command=apply"})
public Result apply(Command command) { ... }
```

Compose classifications on one operation:

```java
return observations.of("payment.capture")
    .dims(KeyValues.of("channel", "webhook"))
    .integrity((Payment r) -> r.complete() ? OutcomeIntegrity.OK : OutcomeIntegrity.DEGRADED)
    .idempotency(r -> r.wasNoOp() ? IdempotencyOutcome.DUPLICATE_SKIPPED : IdempotencyOutcome.APPLIED)
    .record(() -> handler.process(delivery));
```

Full schema, builder reference, and every vocabulary: [API handbook](docs/handbook/02-api.md).

## Modules

| Artifact | Purpose |
|---|---|
| `outcome-metrics` | Core: recording builder, tag schema, enforcement pipeline, domain vocabularies, `@MeasuredOutcome` |
| `outcome-metrics-spring-boot-starter` | Spring Boot 4 auto-config, AOP interception, per-request scope filter, enforcement from properties |
| `outcome-metrics-quarkus` | Quarkus 3 extension (preview): CDI interceptor, enforcement from config |
| `outcome-metrics-reactor` | Terminal-signal binding for `Mono`/`Flux` (+ Spring aspect auto-detection) |
| `outcome-metrics-mutiny` | Terminal-signal binding for `Uni`/`Multi` (+ Quarkus interceptor auto-detection) |
| `outcome-metrics-processor` | Opt-in annotation processor: malformed `@MeasuredOutcome` constants fail the build |
| `outcome-metrics-test` | Test contracts: label-set consistency, schema/cardinality/PII assertions, vocabulary attestation, propagation checks, SLO scaffolding |
| `outcome-metrics-bom` | Version alignment for all of the above |

Requires **Java 21+**.

## Design principles

Eight rules generate every decision in this library:

1. **`outcome` stays binary** — richer verdicts (integrity, grounding, fates, dispositions) ride
   parallel tags, so failure-ratio queries never break.
2. **Closed vocabularies only** — open-ended values degrade to floor values (`unknown`, `other`,
   `unregistered`) instead of minting series; unclassified exceptions map to `reason=unknown`,
   never the class name.
3. **Tags, never parallel meters** — one series of truth that everything joins against.
4. **Label sets are law** — every series of a meter name carries the same tag keys; declared keys
   preset to `none` on the paths that don't produce values.
5. **Telemetry never throws at emission time** — validation fails fast at wiring/build time; the
   failure path never masks the original exception.
6. **Signal guards fail open, privacy guards fail closed** — over a cap, signal stays visible and
   private data stays hidden.
7. **No lifecycle state in the library** — only your store spans requests and restarts; helpers
   take store-computed durations.
8. **Policy stays out of process** — SLO targets, burn rates, and alert routing live in the
   toolchain; code declares bindings and vocabularies.

## Configuration

```yaml
outcome:
  metrics:
    enabled: true
    annotation:
      enabled: true
    scope:
      enabled: false        # opt-in per-request OutcomeScope filter (servlet only)
    max-meters: 50000
    tag-limits:
      - meter-name-prefix: websocket.
        tag-key: destination
        maximum-values: 32  # further values remap to `other`, counted on tag_value_overflows
    # enforcement pipeline (reason-budget, reason-registry, combination-guard, privacy):
    # see the Spring Boot / Quarkus handbook pages
```

`outcome.metrics.*` keys are public API: renames require a major version bump.

## Overhead

Indicative numbers (Apple M2 Max, JDK 25, reduced JMH iterations — configuration and caveats in
[benchmarks](benchmarks/README.md)): a raw Micrometer timer is ~50 ns/op; an outcome observation
is ~1.3 µs/op, dominated by Micrometer's Observation machinery; composing the **entire**
enforcement pipeline adds ~0.6 µs, and an open `OutcomeScope` ~30 ns. Instrument units of work,
not tight loops.

## Samples

Runnable demos (built outside the library reactor):

| Demo | Port | Metrics |
|---|---|---|
| [`samples/spring-boot-demo`](samples/spring-boot-demo/) | 18080 | `/actuator/prometheus` |
| [`samples/quarkus-demo`](samples/quarkus-demo/) | 18081 | `/q/metrics` |

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -f outcome-metrics-bom/pom.xml -B -ntp install
./mvnw -f samples/pom.xml -B verify
```

## Build

```bash
./mvnw -B verify
./mvnw -f outcome-metrics-bom/pom.xml -B validate
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — commits need a DCO sign-off (`git commit -s`). Please use
[private vulnerability reporting](https://github.com/rabitem/outcome-metrics/security/advisories/new)
for security issues ([SECURITY.md](SECURITY.md)).

## License

MIT — see [LICENSE](LICENSE).
