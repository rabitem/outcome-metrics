# API

## Tags you always get

| Tag | Values | When |
|---|---|---|
| `outcome` | `success` / `failure` | success = returned; failure = threw |
| `reason` | `none` / `unknown` / your code | success → `none`; `OutcomeReasonSource` → your code; else `unknown` |
| `integrity` | `ok` / `degraded` / `empty` / `none` | success → classifier verdict (default `ok`); failure → `none` |
| `alertability` | `page` / `ticket` / `none` | failure → the reason's declared level (default `page`; unclassified → `page`); success → `none` |
| `occurrence` | `first` / `repeat` | first observation of a series per `OutcomeScope` → `first`; identical repeats → `repeat`; no scope → always `first` |

Do not put user ids, emails, UUIDs, or free text into tags or reason codes.

## Record programmatically

```java
@Inject // or constructor-inject
OutcomeObservations observations;

return observations.record(
    "order.place",
    MetricsTags.pairs("channel=" + channel),
    () -> placeOrder(cmd));
```

Classified success (extra result tag, still keeps `outcome`/`reason`):

```java
return observations.record(
    "order.payment",
    KeyValues.of("step", "classify"),
    () -> status,
    value -> MetricsTags.pairs("result=" + value));
```

`MetricsTags` / result tags are sanitized (`RETRY` → `retry`).

## Integrity: catch quiet failures

HTTP 200 and `outcome=success` can hide a degraded business result (blank PDF, partial write). Grade
the delivered result with an `IntegrityClassifier` — `outcome` stays `success`, so completion SLOs
are untouched, but the quiet failure becomes alertable:

```java
return observations.recordClassified(
    "invoice.render",
    KeyValues.of("format", "pdf"),
    () -> renderInvoice(order),
    pdf -> pdf.pageCount() == 0 ? OutcomeIntegrity.EMPTY
        : pdf.hasAllLineItems() ? OutcomeIntegrity.OK
        : OutcomeIntegrity.DEGRADED);
```

The vocabulary is closed: `ok`, `degraded`, `empty`. A classifier that throws or returns `null`
fails the observation — an integrity check that silently passes would itself be a quiet failure.
An overload accepts a result tagger as well.

## Scopes: keep repeat storms out of SLIs

One downstream timeout in a request can emit many identical failure observations and inflate SLI
counters. Open an `OutcomeScope` per unit of work; the first observation of each series is tagged
`occurrence=first`, identical repeats `occurrence=repeat`:

```java
try (OutcomeScope scope = OutcomeScope.open()) {
    handleRequest(); // any records inside, including @MeasuredOutcome methods
}
```

Nothing is dropped — timers, spans and totals keep every event; SLI queries filter
`occurrence="first"`. Deduplication keys on the full series identity (name + all tags), applies
symmetrically to successes and failures, and fails open: no scope, another thread, or more than
1024 distinct series per scope → `first`. Scopes are thread-confined (Reactor/coroutines: #39) and
nest as a stack. Framework modules auto-opening scopes per HTTP request: #54.

## Reason budget: bounded codes, expandable during incidents

Bound how many distinct failure `reason` codes each observation name may emit; the rest show as
`other` and count on a suppression counter. During an incident, expand at runtime — effective on the
next event, **including codes that were suppressed before**:

```java
ReasonBudget budget = new ReasonBudget(8, 64); // collapsed, expanded
budget.bindTo(meterRegistry); // 0/1 mode gauge + suppression counter
OutcomeObservations observations = new OutcomeObservations(observationRegistry, budget);

budget.expand();   // incident: full detail from the next event on
budget.collapse(); // afterwards: new codes bounded again; admitted codes keep reporting
```

Expansion/collapse is deliberately manual — wire it to your runbook or an alert webhook, not to an
in-process burn-rate copy. `none`/`unknown`/`other` never consume budget. If you also configure a
bounded tag-value filter on `reason`, keep its bound at or above the expanded limit; filter remaps
are pinned by Micrometer's pre-filter id cache and cannot be undone by expanding.

## Record with an annotation

```java
@MeasuredOutcome(name = "order.reserve", tags = {"step=reserve"})
public Reservation reserve(String sku) { ... }
```

- Type-level `@MeasuredOutcome` supplies defaults; method-level `name` wins.
- Tags merge: type first, then method.
- Spring: bean + proxy call (no self-invocation).
- Quarkus: CDI bean; non-private method.

## Failure reasons

```java
public final class PaymentDeclinedException
        extends RuntimeException implements OutcomeReasonSource {

    @Override
    public OutcomeReason outcomeReason() {
        return new OutcomeReason() {
            @Override
            public String code() {
                return "payment_declined";
            }

            @Override
            public Alertability alertability() {
                return Alertability.NONE; // expected business outcome: don't page
            }
        };
    }
}
```

Cause chain is walked. Unclassified exceptions → `reason=unknown` (not the exception class name).

Every failure **pages by default** (`alertability=page`); downgrade expected failures explicitly to
`TICKET` or `NONE` by overriding `alertability()`. Unclassified failures and broken
implementations page — nothing is silenced by omission. The level derives from the reason object,
so a `ReasonBudget`-suppressed `reason=other` still carries its declared alertability.

## What this library does not do

- Replace HTTP/DB auto-instrumentation
- Replace OpenTelemetry SDK
- Export metrics by itself (use Prometheus / OTLP via Micrometer)
