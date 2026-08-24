# API

## Tags you always get

| Tag | Values | When |
|---|---|---|
| `outcome` | `success` / `failure` | success = returned; failure = threw |
| `reason` | `none` / `unknown` / your code | success → `none`; `OutcomeReasonSource` → your code; else `unknown` |
| `integrity` | `ok` / `degraded` / `empty` / `none` | success → classifier verdict (default `ok`); failure → `none` |

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
        return () -> "payment_declined";
    }
}
```

Cause chain is walked. Unclassified exceptions → `reason=unknown` (not the exception class name).

## What this library does not do

- Replace HTTP/DB auto-instrumentation
- Replace OpenTelemetry SDK
- Export metrics by itself (use Prometheus / OTLP via Micrometer)
