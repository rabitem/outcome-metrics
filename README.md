# outcome-metrics

[![CI](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml/badge.svg)](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/rabitem/outcome-metrics/badge)](https://scorecard.dev/viewer/?uri=github.com/rabitem/outcome-metrics)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/13963/badge)](https://www.bestpractices.dev/projects/13963)

Micrometer **outcome observations** and **cardinality guardrails**, with thin adapters for **Spring Boot** and **Quarkus**.

Record a unit of work once → get a timer today and a trace span when tracing is configured. Overflowing tag values remap to `other` instead of vanishing.

> Status: **0.1.0-beta.2** (pre-1.0). APIs may still move.

**Docs:** [Handbook](docs/handbook/README.md) · [Samples](samples/README.md) · [JMH](benchmarks/README.md) · [OpenSSF Best Practices](docs/openssf-best-practices.md) · [llms.txt](llms.txt)

## Modules

| Artifact | Purpose | Dependencies |
|---|---|---|
| `outcome-metrics` | Core API (`OutcomeObservations`, filters, `@MeasuredOutcome`) | Micrometer + JSpecify only |
| `outcome-metrics-spring-boot-starter` | Spring Boot auto-config + AOP | Spring Boot + AspectJ |
| `outcome-metrics-quarkus` | Quarkus extension + CDI interceptor | Quarkus Micrometer |
| `outcome-metrics-bom` | Thin BOM for version alignment | — |

Requires **Java 21+**.

## Spring Boot

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-spring-boot-starter</artifactId>
  <version>0.1.0-beta.2</version>
</dependency>
```

## Quarkus

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-quarkus</artifactId>
  <version>0.1.0-beta.2</version>
</dependency>
```

## Usage

```java
private final OutcomeObservations observations;

return observations.record(
    "voucher.command",
    KeyValues.of("command", "apply"),
    () -> doApply(command));
```

Prefer closed reason codes via `OutcomeReasonSource`. Unclassified exceptions map to `reason=unknown` (not the exception class name).

Or annotate:

```java
@MeasuredOutcome(name = "voucher.command", tags = {"command=apply"})
public Result apply(Command command) { ... }
```

### Configuration (`outcome.metrics`)

```yaml
outcome:
  metrics:
    enabled: true
    max-meters: 50000
    cache:
      normalize-tags: true
    annotation:
      enabled: true
    tag-limits:
      - meter-name-prefix: websocket.
        tag-key: destination
        maximum-values: 32
```

`outcome.metrics.*` keys are public API: renames require a major version bump.

Overflowing tag values remap to `other`. The gauge `outcome.metrics.tag_value_overflows` counts remaps.

## Rules of the road

- Instrument **units of work**, not every method.
- Tags must be **low-cardinality** (never raw user ids, emails, or free-text).
- Prefer `OutcomeObservations` + `OutcomeReasonSource` over hand-rolled counters.

## Design decisions

| Decision | Choice |
|---|---|
| Unclassified failure `reason` | `unknown` (not exception class names) |
| Tag-value overflow | Remap to `other` (meters stay visible) |
| Classified successes | Still emit `outcome`/`reason`, plus sanitized result tags |
| Quiet failures | Parallel `integrity` tag (`ok`/`degraded`/`empty`); `outcome` stays binary for SLOs |
| Repeat storms | `occurrence` tag (`first`/`repeat`) per `OutcomeScope`; repeats stay recorded, SLIs filter `first` |
| Reason cardinality | Optional `ReasonBudget`: bounded codes per name, operator-expandable at runtime, never evicts |
| Reason membership | Optional `ReasonRegistry`: unregistered reasons emit `unknown` + forced `page`; never throws at runtime |
| Annotation firewall | Opt-in `outcome-metrics-processor`: malformed `@MeasuredOutcome` constants fail the build |
| Combination privacy | Optional `CombinationGuard`: rare guarded-tag tuples emit `other` until sustained volume; fails closed |
| SLO binding | `SloCatalog` issues `slo=<id>` tags (undeclared ids fail at wiring); info gauge proves runtime bindings |
| Messaging fate | `recordDelivery`: `fate=processed`/`retry`/`dead_letter`/`drop` + `attempt_bucket`; lag via closed `lag_bucket` |
| PII sentinel | Optional `TagPrivacyPolicy`: deny-listed keys + identity-shaped values redact to `redacted`; never throws |
| Experiments | `ExperimentRegistry`: pre-registered ids + declared arms; raw flag keys collapse to `unregistered` |
| Test contracts | `outcome-metrics-test`: label-set consistency, schema/cardinality/PII assertions, vocabulary contracts, optional ArchUnit rules |
| Dual control | `witness` action vocabulary + `closure`-tagged gap timer fed from the workflow store; roles, never actor ids or hashes |
| Rail divergence | `RailDivergence.recordWindow` timer with `resolution=converged/returned/adjusted/written_off`; state pairs are caller vocabularies |
| Break-glass | `break_glass` stage vocabulary + `verdict`-tagged review-lag timer; alert on activation rate; metrics ≠ audit trail |
| Retention routing | `retention=ops`/`audit` vocabulary + `RetentionFilters` on composite-registry children; untagged = ops |
| Retry shadow | `recordResilient`: `attempt_bucket` + typed `dominant_reason` + `shadow_cost=none/minor/dominant` on success **and** failure |
| RAG grounding | `recordGrounded`: `grounding=aligned/ignored_evidence/hallucinated_gap/no_corpus_needed`; async judges record their own observation |
| Replay deltas | `ReplayDelta.classify`: `context_drift` voids the verdict comparison before `verdict_flip`/shifts; fingerprints never become tags |
| Alert routing | `alertability` tag (`page`/`ticket`/`none`) declared per reason; unclassified failures page |
| Idempotency | `idempotency=applied`/`duplicate_skipped` on success, `none` on failure; conflicts/stale/missing-key are failure reasons |
| Shared resources | `SharedResource` five-tag bundle (`owned`/`borrowed`/`pooled`); factories reject UUID-shaped values |
| Offline sync | `phase=intent`/`commit`/`reconcile` + `disposition` finding (`confirmed`/`diverged`/`abandoned`/`deferred`); `outcome` stays binary |
| Config prefix | `outcome.metrics.*` frozen until 1.0 |
| Quarkus extension status | `preview` until broader production dogfooding |

## Samples

Runnable Spring Boot and Quarkus demos (not part of the library reactor):

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

See [CONTRIBUTING.md](CONTRIBUTING.md). Please use [private vulnerability reporting](https://github.com/rabitem/outcome-metrics/security/advisories/new) for security issues ([SECURITY.md](SECURITY.md)).

## License

MIT — see [LICENSE](LICENSE).
