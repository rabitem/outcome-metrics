# Developer handbook

How to add `outcome-metrics` to a Spring Boot or Quarkus service.

**0.1.0-beta.1 preview** · Java 21+ · config prefix `outcome.metrics.*` (stable until 1.0)

| I need to… | Go here |
|---|---|
| Run the sample apps | [Quickstart](01-quickstart.md) |
| Know what tags get emitted | [API](02-api.md) |
| Wire Spring Boot | [Spring Boot](03-spring-boot.md) |
| Wire Quarkus | [Quarkus](04-quarkus.md) |
| Cap tag cardinality | [Cardinality](05-cardinality.md) |
| Assert metrics in tests | [Testing](06-testing.md) |
| Decide if this library fits | [When to use](07-when-to-use.md) |
| Alert / disable in prod | [Operations](08-operations.md) |
| Measure overhead (JMH) | [`benchmarks/`](../../benchmarks/) |

**Samples:** [`spring-boot-demo`](../../samples/spring-boot-demo/) (`:18080`) · [`quarkus-demo`](../../samples/quarkus-demo/) (`:18081`)

```text
your method
  → OutcomeObservations / @MeasuredOutcome
  → Micrometer Observation → timer (+ span if tracing is on)
  → tags: outcome=success|failure, reason=none|unknown|<your_code>
```

Instrument service/command boundaries. Skip controllers that already have HTTP metrics.
