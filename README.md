# outcome-metrics

[![CI](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml/badge.svg)](https://github.com/rabitem/outcome-metrics/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/rabitem/outcome-metrics/badge)](https://scorecard.dev/viewer/?uri=github.com/rabitem/outcome-metrics)

Micrometer **outcome observations** and **cardinality guardrails**, with thin adapters for **Spring Boot** and **Quarkus**.

Record a unit of work once → get a timer today and a trace span when tracing is configured. Overflowing tag values remap to `other` instead of vanishing.

> Status: **0.1.0** (pre-1.0). APIs may still move.

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
  <version>0.1.0</version>
</dependency>
```

## Quarkus

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-quarkus</artifactId>
  <version>0.1.0</version>
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

## Rules of the road

- Instrument **units of work**, not every method.
- Tags must be **low-cardinality** (never raw user ids, emails, or free-text).
- Prefer `OutcomeObservations` + `OutcomeReasonSource` over hand-rolled counters.

## Build

```bash
./mvnw -B verify
./mvnw -f outcome-metrics-bom/pom.xml -B validate
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please use [private vulnerability reporting](https://github.com/rabitem/outcome-metrics/security/advisories/new) for security issues ([SECURITY.md](SECURITY.md)).

## License

MIT — see [LICENSE](LICENSE).

Derived from observability utilities originally developed for [Rettungshelden](https://rettungshelden.de); this project is independently maintained under MIT.
