# Operations

## PromQL starters

Metric names follow Micrometer naming (`_seconds_count` for timers):

```promql
# SLI: filter occurrence="first" so one incident inside a request counts once,
# even when it emitted many identical observations. Drop the filter for raw volume.
sum(rate(demo_order_place_seconds_count{outcome="success",occurrence="first"}[5m]))
/
sum(rate(demo_order_place_seconds_count{occurrence="first"}[5m]))

sum by (reason) (rate(demo_order_place_seconds_count{outcome="failure"}[5m]))

# Integrity rate: successes that are actually trustworthy.
# Alert when it diverges from the success rate — that gap is your quiet-failure rate.
sum(rate(demo_invoice_render_seconds_count{outcome="success",integrity="ok"}[5m]))
/
sum(rate(demo_invoice_render_seconds_count{outcome="success"}[5m]))

outcome_metrics_tag_value_overflows

# Reason budget: 0 collapsed / 1 expanded, plus codes folded into reason="other"
outcome_metrics_reason_budget_expanded
outcome_metrics_reason_budget_suppressed

# Page only on actionable failures; expected declines stay off the pager
sum by (reason) (rate(demo_order_place_seconds_count{outcome="failure",alertability="page"}[5m]))
```

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

| Signal | Meaning |
|---|---|
| Success ratio drops for a command | Business regression |
| `reason=unknown` rises | Missing `OutcomeReasonSource` |
| Integrity rate < success rate | Quiet failures: degraded/empty results behind HTTP 200 |
| `occurrence=repeat` rises | Repeat storms inside single requests (retry loops, fan-out amplification) |
| `reason_budget.suppressed` rises | Distinct failure codes exceed the budget — expand it or trim the vocabulary |
| `reason_registry.rejected` rises | Code emitting reasons outside the registered vocabulary (or a rogue reason source) |
| `reason=idempotency_conflict` | Same key, different payload: upstream producer bug (pages by default) |
| `idempotency=duplicate_skipped` ratio shifts | Redelivery storm or dedup-window change upstream |
| `disposition=diverged` rises | Client-claimed successes not matching server state |
| `disposition=abandoned` rises | Flows dying mid-way (app closed, sync never completed) |
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
