# Operations

In order: query the schema, route alerts, the alert catalog, trace exemplars, SLO rule
generation, retention routing, kill switches, and debugging.

## PromQL starters

Metric names follow Micrometer naming (`_seconds_count` for timers):

```promql
# SLI: filter occurrence="first" so one incident inside a request counts once,
# even when it emitted many identical observations. Drop the filter for raw volume.
sum(rate(demo_order_place_seconds_count{outcome="success",occurrence="first"}[5m]))
/
sum(rate(demo_order_place_seconds_count{occurrence="first"}[5m]))

# Failure breakdown by reason
sum by (reason) (rate(demo_order_place_seconds_count{outcome="failure"}[5m]))

# Integrity rate: successes that are actually trustworthy.
# Alert when it diverges from the success rate — that gap is your quiet-failure rate.
sum(rate(demo_invoice_render_seconds_count{outcome="success",integrity="ok"}[5m]))
/
sum(rate(demo_invoice_render_seconds_count{outcome="success"}[5m]))

# Page only on actionable failures; expected declines stay off the pager
sum by (reason) (rate(demo_order_place_seconds_count{outcome="failure",alertability="page"}[5m]))

# SLI per SLO id, deduplicated: group the bound series by slo
sum by (slo) (rate(demo_order_place_seconds_count{outcome="success",occurrence="first",slo!=""}[5m]))

# Drift detector: alert when an SLO id your rules reference has no runtime binding
absent(outcome_metrics_slo_info{slo="checkout_success"})

# Enforcement telemetry
outcome_metrics_tag_value_overflows
outcome_metrics_reason_budget_expanded     # 0 collapsed / 1 expanded
outcome_metrics_reason_budget_suppressed   # codes folded into reason="other"
```

## Alert routing

Route by `alertability` instead of regexing reason codes — e.g. in Alertmanager:

```yaml
routes:
  - matchers: [alertability="page"]
    receiver: pagerduty
  - matchers: [alertability="ticket"]
    receiver: ticket-queue
# alertability="none" (expected business declines): dashboards only, no route
```

## Alerts worth having

Core health:

| Signal | Meaning |
|---|---|
| Success ratio drops for a command | Business regression |
| `reason=unknown` rises | Missing `OutcomeReasonSource` |
| Integrity rate < success rate | Quiet failures: degraded/empty results behind HTTP 200 |
| `occurrence=repeat` rises | Repeat storms inside single requests (retry loops, fan-out amplification) |
| `shadow_cost=dominant` successes rise | Green dashboards hiding retry burn (quota, latency, capacity) |
| `reason=cancelled` rate spikes | Client disconnect / timeout storm on reactive endpoints (expected per event, alarming in bulk) |
| VT pin rate up + outcome durations up | Carrier pinning inflating latency (join `micrometer-java21` pin metrics with outcome timers) |

Enforcement telemetry (the guardrails reporting on your code):

| Signal | Meaning |
|---|---|
| `tag_value_overflows` rises | New unbounded tag values or limit too tight |
| `reason_budget.suppressed` rises | Distinct failure codes exceed the budget — expand it or trim the vocabulary |
| `reason_registry.rejected` rises | Code emitting reasons outside the registered vocabulary (or a rogue reason source) |
| `combination_guard.collapsed` high & steady | Guard scope too broad or `minSupport` too high for real traffic |
| `tag_privacy.redacted` rises | Code is putting identity data into tags — fix the call site |
| `experiment_registry.unregistered` rises | Flag SDK emitting ids/arms the registry doesn't declare |
| `absent(outcome_metrics_slo_info{slo="X"})` | An alert/SLO references an id this binary no longer instruments |

Domain signals:

| Signal | Meaning |
|---|---|
| `reason=idempotency_conflict` | Same key, different payload: upstream producer bug (pages by default) |
| `idempotency=duplicate_skipped` ratio shifts | Redelivery storm or dedup-window change upstream |
| `disposition=diverged` rises | Client-claimed successes not matching server state |
| `disposition=abandoned` rises | Flows dying mid-way (app closed, sync never completed) |
| `fate=dead_letter` rises | Poison message influx (bad producer deploy, schema drift) |
| `fate=unknown` rises | Delivery fate classifier gaps — extend it |
| `lag_bucket=gte_10m` with `outcome=success` | Work succeeding but far too late (silent SLA burn) |
| `closure=expired` gap timers rise | Dual-control approvals dying unwitnessed — process, not outage |
| `reason=veto_after_approval` | The four-eyes control caught something after first approval — review it |
| `resolution=returned`/`adjusted` windows rise | Rail disagreeing with local ledger (returns, adjudication drift) |
| `break_glass=activation` rate > 0 | Every emergency override is an alertable event — notify on the tag rate |
| `verdict=unjustified` reviews | Break-glass misuse found by review — follow up outside metrics |
| `grounding=hallucinated_gap`/`ignored_evidence` rise | RAG quality regression behind successful responses |
| `grounding=no_corpus_needed` rises | Retrieval running for answers that don't need it — wasted spend |
| `delta=verdict_flip` rises | Real non-determinism under pinned context — system-under-test regression |
| `delta=context_drift` rises | The replay harness can't reproduce its own reads — fix capture, not the model |

## Exemplars: from alert to trace

Outcome series carry OpenMetrics exemplars, so a panel on
`{outcome="failure",reason="payment_declined"}` links straight to the trace that produced it.
This is wiring, not a library feature — verified on Micrometer 1.17 / Prometheus client 1.3:

- **Spring Boot**: nothing to add. With the Prometheus actuator endpoint and any Micrometer
  Tracing bridge on the classpath, Boot's `PrometheusExemplarsAutoConfiguration` registers the
  Prometheus-client `SpanContext` and the export auto-configuration passes it into the registry.
- **Plain Micrometer**: pass a `SpanContext` yourself —
  `new PrometheusMeterRegistry(config, new PrometheusRegistry(), clock, spanContext)`
  (e.g. from `prometheus-metrics-tracer-otel`, or your own bridging the active span).

What actually carries exemplars (empirically tested in `PrometheusExemplarTest`): timer `_count`
samples get them out of the box; histogram `_bucket` samples get them once
`publishPercentileHistogram` is enabled for the meter (that's what latency heatmaps click
through) — e.g. `management.metrics.distribution.percentiles-histogram.demo.order.place=true`.
Exemplars render only under OpenMetrics content negotiation (`application/openmetrics-text`), the
Prometheus server needs `--enable-feature=exemplar-storage`, and Grafana needs the exemplar →
trace datasource link configured. One async caveat: the `SpanContext` is consulted on the
*recording* thread, so deferred/reactive outcomes attach the span active at the terminal signal —
correct under context propagation, absent without it.

## SLO scaffolding: generate the rules, keep the policy

`SloScaffold` (in `outcome-metrics-test`) emits Sloth `prometheus/v1` skeletons from the
`SloCatalog`, with queries derived from the outcome schema (`occurrence="first"` totals, errors
adding `outcome="failure"`). Every declared id must be bound, so the scaffold can never disagree
with the runtime info gauge:

```java
SloScaffold.builder()
    .service("checkout")
    .catalog(sloCatalog)
    .bind("checkout-success", "demo_order_place_seconds_count")
    .build()
    .writeTo(Path.of("slo/checkout-slos.yaml"));
```

The loop: **generate → set each `objective` (the emitted `SET_OBJECTIVE_PERCENT` placeholder is
non-numeric, so Sloth refuses an unedited skeleton) → commit → run Sloth → the
`absent(outcome_metrics_slo_info{...})` drift alert above closes the circle.** Policy stays in the
toolchain — the generator writes queries and structure, never targets or windows.

## Retention classes: two TTLs, one instrumentation

Route long-retention investigation telemetry to its own pipeline with stock Micrometer wiring —
the library only closes the vocabulary and ships the filters:

```java
CompositeMeterRegistry composite = new CompositeMeterRegistry();
composite.add(opsRegistry);                                   // 90d TTL; everything (alerting lives here)
auditRegistry.config().meterFilter(RetentionFilters.auditOnly());
composite.add(auditRegistry);                                 // years TTL; explicit audit only

observations.of("payment.release")
    .dims(KeyValues.of(RetentionClass.AUDIT.tag()).and(dims)) // untagged = ops by default
    .record(work);
```

Per-pipeline tag stripping is `MeterFilter.ignoreTags(...)`; per-pipeline cardinality is
`boundedTagValues(...)`. `retention=audit` is a **routing hint, never a legal guarantee** — it
means long-retention investigation telemetry, not the WORM legal record (see the break-glass rule:
dashboards are never audit evidence).

## Kill switches

| Property | Effect |
|---|---|
| `outcome.metrics.enabled=false` | Everything off |
| `outcome.metrics.annotation.enabled=false` | Annotations off; programmatic API stays |
| `outcome.metrics.drop-error-tag=false` | Keep Micrometer's raw `error` tag (exception class names) on outcome meters |

Config key renames need a major version.

## Debug checklist

1. Metrics missing → Micrometer/Observation enabled? Kill switch on?
2. Series explosion → check overflow gauge + recent tag changes
3. All failures `unknown` → wire `OutcomeReasonSource`; in plugin hosts, run
   `MetricTagValues.isForeignReasonSource(error)` — a duplicated library copy degrades silently
4. Annotation silent → proxy/CDI rules (self-call, private method)
5. Reactive series stamped success at once → missing `outcome-metrics-reactor`/`-mutiny` adapter
   (terminal binding), or a hand-wrapped publisher timing the assembly

## Sample scrapes

| App | URL |
|---|---|
| Spring demo | `http://localhost:18080/actuator/prometheus` |
| Quarkus demo | `http://localhost:18081/q/metrics` |

Vulnerabilities: [SECURITY.md](../../SECURITY.md).
