# When to use

## Use it when

- You instrument **business** units of work (commands, jobs, handlers)
- You need a stable `outcome` + `reason` schema for SLOs
- You want the same annotation/config on Spring and Quarkus
- You need tag-value caps that remap to `other` instead of dropping meters

## Skip it when

- You only need HTTP/JVM metrics the platform already emits
- You need per-user / per-order series → logs or traces, not metrics
- You refuse Micrometer and only call OTel APIs directly
- You can live with raw `Observation` and don't want the dependency

Programmatic `OutcomeObservations` alone is fine if you dislike AOP/CDI interceptors.

## vs common alternatives

| Approach | Use when | Gap vs this library |
|---|---|---|
| Micrometer `Timer` / `Counter` | One-off custom meters | Easy to skip failure tags; no shared schema |
| `@Timed` / `@Counted` | Simple duration/count | No `outcome`/`reason` policy |
| Observation / `@Observed` | Custom spans + timers | No opinionated business outcome tags or tag limits |
| OpenTelemetry API | You own the OTel stack end-to-end | Different API; Boot apps usually face Micrometer Observation |
| This library | Business outcomes + cardinality kit | Extra dependency; Quarkus still preview |

Under the hood this is still Micrometer Observation. It does not replace Observation or OTel exporters.

## Migrate from hand counters

```java
// before
try {
  doWork();
  success.increment();
} catch (Exception e) {
  failure.increment();
  throw e;
}

// after
return observations.record("demo.work", MetricsTags.pairs("op=do"), this::doWork);
```

Overhead numbers (JMH): [`benchmarks/RESULTS.md`](../../benchmarks/RESULTS.md).
