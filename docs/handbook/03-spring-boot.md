# Spring Boot

Requires **Spring Boot 4.1+**, Java 21+, and an `ObservationRegistry` bean.

## Dependencies

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-spring-boot-starter</artifactId>
  <version>0.1.0-beta.3</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-micrometer-metrics</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-micrometer-observation</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Optional: import `outcome-metrics-bom`.

Working example: [`samples/spring-boot-demo`](../../samples/spring-boot-demo/).

## Config

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus

outcome:
  metrics:
    enabled: true
    annotation:
      enabled: true
    max-meters: 50000
    cache:
      normalize-tags: true
    tag-limits:
      - meter-name-prefix: order.
        tag-key: channel
        maximum-values: 32
```

| Property | Effect |
|---|---|
| `outcome.metrics.enabled=false` | No auto-config beans |
| `outcome.metrics.annotation.enabled=false` | Keep `OutcomeObservations`; disable `@MeasuredOutcome` |

## Typical usage

```java
@Service
public class OrderService {
  private final OutcomeObservations observations;

  public OrderService(OutcomeObservations observations) {
    this.observations = observations;
  }

  public Order place(String sku, String channel) {
    return observations.of("order.place")
        .dims(MetricsTags.pairs("channel=" + channel))
        .record(() -> doPlace(sku, channel));
  }

  @MeasuredOutcome(name = "order.reserve", tags = {"step=reserve"})
  public Reservation reserve(String sku) {
    return doReserve(sku);
  }
}
```

## Pitfalls

| Symptom | Cause |
|---|---|
| No `OutcomeObservations` bean | Missing observation/metrics starters |
| `@MeasuredOutcome` never fires | Self-invocation / not a Spring proxy |
| Cardinality spike | Unbounded tag values (bind with `tag-limits`) |
| Duplicate HTTP timers | Instrumenting controllers Actuator already covers |

Overflow gauge (when tag limits are set): `outcome.metrics.tag_value_overflows`.

## Per-request outcome scope

Opt in to request-scoped coalescing (#18): a servlet filter opens an `OutcomeScope` per request so
repeat observations within one request tag `occurrence=repeat`:

```yaml
outcome:
  metrics:
    scope:
      enabled: true   # off by default: enabling changes the occurrence split on existing series
```

Servlet (blocking) dispatch only. Async servlet completions after the chain returns, WebFlux, and
reactive stacks hop threads — a ThreadLocal scope there would corrupt rather than help, so those
paths fail open to `occurrence=first` by design.

## Enforcement from configuration

Compose the convention pipeline (#19/#24/#26/#29) without code — any bean you define yourself
takes precedence:

```yaml
outcome:
  metrics:
    reason-budget:
      enabled: true
      collapsed-limit: 8
      expanded-limit: 64      # the ReasonBudget bean stays injectable for your expand/collapse toggle
    reason-registry:
      codes: [db_down, payment_declined]   # literal codes; enum vocabularies -> define a ReasonRegistry bean
    combination-guard:
      keys: [region, product]
      min-support: 20
      window: 15m
    privacy:
      enabled: true            # saas defaults + your extras
      deny-keys: [custom_secret]
```

All four are `MeterBinder`s, so their gauges/counters bind automatically.
