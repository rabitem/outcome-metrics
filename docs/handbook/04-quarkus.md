# Quarkus

Extension: `outcome-metrics-quarkus` · status: **preview** · Quarkus 3.38+ / Java 21+.

## Dependencies

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-quarkus</artifactId>
  <version>0.1.0-beta.4</version>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

Working example: [`samples/quarkus-demo`](../../samples/quarkus-demo/).

## Config

```properties
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true

outcome.metrics.enabled=true
outcome.metrics.annotation.enabled=true
outcome.metrics.max-meters=50000
outcome.metrics.tag-limits[0].meter-name-prefix=shipment.
outcome.metrics.tag-limits[0].tag-key=carrier
outcome.metrics.tag-limits[0].maximum-values=32
```

Same kill switches as Spring: `outcome.metrics.enabled`, `outcome.metrics.annotation.enabled`.

## Typical usage

```java
@ApplicationScoped
public class ShipmentService {
  private final OutcomeObservations observations;

  @Inject
  public ShipmentService(OutcomeObservations observations) {
    this.observations = observations;
  }

  public Map<String, Object> dispatch(String orderId, String carrier) {
    return observations.of("shipment.dispatch")
        .dims(MetricsTags.pairs("carrier=" + carrier))
        .record(() -> doDispatch(orderId, carrier));
  }

  @MeasuredOutcome(name = "shipment.label", tags = {"step=label"})
  public Map<String, Object> printLabel(String orderId) {
    return doPrint(orderId);
  }
}
```

Scrape: `/q/metrics`.

## Pitfalls

| Symptom | Cause |
|---|---|
| Annotation ignored | Not a CDI bean, or private method |
| No `/q/metrics` | Missing Prometheus registry extension |
| Want custom helper | Replace `@DefaultBean` `OutcomeObservations` |

Preview: APIs work, but expect sharper edges than the Spring starter. Prefer `OutcomeObservations` if you want zero interceptor magic.

## Enforcement from configuration

Mirrors the Spring starter (#57): `outcome.metrics.reason-budget.*` (`enabled`,
`collapsed-limit`, `expanded-limit` — the `ReasonBudget` bean stays injectable for runtime
toggles), `outcome.metrics.reason-registry.codes`, `outcome.metrics.combination-guard.*`
(`keys`, `min-support`, `window`, `name-prefixes`), and `outcome.metrics.privacy.*`
(`enabled`, `saas-defaults`, `deny-keys`). Binders register their gauges automatically.

## Reactive (Mutiny) terminal binding

Add `outcome-metrics-mutiny` and `@MeasuredOutcome` on `Uni`/`Multi`-returning beans binds to the
terminal signal instead of assembly (the annotation no longer lies, #39/#81). Programmatic:

```java
return MutinyOutcomes.record(observations, "order.fetch", dims, client.fetch(id));
```

One observation per subscription (a retry is two attempts); cancellation records
`reason=cancelled, alertability=none` (schema floor). An unterminated `Multi` is a never-stopped
observation — bind at request granularity.
