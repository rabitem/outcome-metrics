# Operations

## PromQL starters

Metric names follow Micrometer naming (`_seconds_count` for timers):

```promql
sum(rate(demo_order_place_seconds_count{outcome="success"}[5m]))
/
sum(rate(demo_order_place_seconds_count[5m]))

sum by (reason) (rate(demo_order_place_seconds_count{outcome="failure"}[5m]))

# Integrity rate: successes that are actually trustworthy.
# Alert when it diverges from the success rate — that gap is your quiet-failure rate.
sum(rate(demo_invoice_render_seconds_count{outcome="success",integrity="ok"}[5m]))
/
sum(rate(demo_invoice_render_seconds_count{outcome="success"}[5m]))

outcome_metrics_tag_value_overflows
```

## Alerts worth having

| Signal | Meaning |
|---|---|
| Success ratio drops for a command | Business regression |
| `reason=unknown` rises | Missing `OutcomeReasonSource` |
| Integrity rate < success rate | Quiet failures: degraded/empty results behind HTTP 200 |
| `tag_value_overflows` rises | New unbounded tag values or limit too tight |

## Kill switches

| Property | Effect |
|---|---|
| `outcome.metrics.enabled=false` | Everything off |
| `outcome.metrics.annotation.enabled=false` | Annotations off; programmatic API stays |

## Debug checklist

1. Metrics missing → Micrometer/Observation enabled? Kill switch on?
2. Series explosion → check overflow gauge + recent tag changes
3. All failures `unknown` → wire `OutcomeReasonSource`
4. Annotation silent → proxy/CDI rules (self-call, private method)

## Sample scrapes

| App | URL |
|---|---|
| Spring demo | `http://localhost:18080/actuator/prometheus` |
| Quarkus demo | `http://localhost:18081/q/metrics` |

Vulns: [SECURITY.md](../../SECURITY.md). Config key renames need a major version.
