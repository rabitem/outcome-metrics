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

## Idempotency: duplicates are not failures (and not lies)

At-least-once delivery makes duplicates expected. Classify the disposition of the key check;
successes carry `idempotency=applied|duplicate_skipped`, failures keep the tag key with
`idempotency=none` (Prometheus needs consistent label sets per meter name):

```java
return observations.recordIdempotent(
    "payment.capture",
    KeyValues.of("channel", "webhook"),
    () -> handler.process(delivery),
    result -> result.wasNoOp() ? IdempotencyOutcome.DUPLICATE_SKIPPED : IdempotencyOutcome.APPLIED);
```

Key conflicts, stale replays, and missing keys are **failures**, not success shapes — throw
`IdempotencyException` and the `reason`/`alertability` tags follow (`idempotency_conflict` pages;
`stale_replay` and `idempotency_key_missing` ticket):

```java
if (!stored.payloadHash().equals(delivery.payloadHash())) {
    throw new IdempotencyException(IdempotencyReason.CONFLICT);
}
```

Idempotency keys, message ids, and dedup-store values must never become tags — only the closed
disposition above is tag-safe.

## Shared resources: attribute failures to the right owner

Failures on borrowed or pooled resources are misattributed when only the consumer is tagged.
`SharedResource` emits a fixed five-tag bundle (`resource`, `relationship`, `consumer_tier`,
`owner_tier`, `pool`) so every relationship has the same label set:

```java
observations.record(
    "lesson.assign",
    SharedResource.borrowed("instructor", "starter", "enterprise").tags()
        .and(MetricsTags.pairs("channel=web")),
    () -> assign(lesson));
```

`owned(...)` sets `owner_tier=self`, `pooled(...)` sets `owner_tier=shared`; `pool=none` unless
`withPool("pool_eu_1")`. Values are tiers and types — the factories throw on UUID-shaped or long-hex
values, so a tenant id in a tag fails in your tests, not on the pager. Pool-id boundedness pairs
with `MetricsMeterFilters.boundedTagValues(...)`.

## Intent–commit–reconcile: abandoned flows become findings, not lies

Offline clients record a local intent, commit it later, and a server job reconciles what actually
happened. Tag the client phases manually; the reconcile recorder owns its phase and classifies its
finding:

```java
// client-side phases
observations.record("sync.flow", KeyValues.of(OutcomePhase.INTENT.tag()), () -> saveLocal(cmd));
observations.record("sync.flow", KeyValues.of(OutcomePhase.COMMIT.tag()), () -> pushToServer(cmd));

// server-side reconciliation job (phase=reconcile added automatically)
observations.recordReconciliation(
    "sync.reconcile", KeyValues.empty(),
    () -> reconcile(intentWindow),
    result -> switch (result.state()) {
        case MATCHED -> ReconcileDisposition.CONFIRMED;
        case MISMATCHED -> ReconcileDisposition.DIVERGED;
        case WINDOW_EXPIRED -> ReconcileDisposition.ABANDONED;
        case STILL_OPEN -> ReconcileDisposition.DEFERRED;
    });
```

Abandonment is a finding of a successful reconciliation, not a new `outcome` value — `outcome`
stays binary. Failures keep both keys (`phase=reconcile`, `disposition=none`). Alert on
`disposition=diverged|abandoned` rates. Intent ids, device ids, and sync batch ids never become
tags.

## Combination guard: rare tuples stay anonymous until they aren't rare

Per-tag limits miss rare combinations (region × product × reason) that can point at tiny cohorts.
Guarded key combinations emit `other` until they show sustained volume — `minSupport` events within
one tumbling window; reveal is one-way per process:

```java
CombinationGuard guard = CombinationGuard.builder()
    .keys("region", "product")
    .minSupport(20).window(Duration.ofMinutes(15))
    .namePrefixes("booking.")
    .build();
guard.bindTo(meterRegistry); // collapsed-event counter

OutcomeObservations observations = new OutcomeObservations(
    observationRegistry,
    OutcomeObservationConvention.builder().combinationGuard(guard).build());
```

**This is not k-anonymity** — support counts events, not individuals; one chatty user crosses any
threshold. It reduces re-identification risk as defense-in-depth in front of backend controls.
Unlike the signal guards (`OutcomeScope`, `ReasonBudget`), which fail open, this privacy guard
fails **closed**: over the tuple cap, combinations stay collapsed. Guarding `outcome` or
`alertability` is rejected at build time. Slow-trickling tuples that never reach `minSupport`
within a window never reveal; the first `minSupport − 1` events stay in the collapsed series
(counters are monotonic).

## SLO bindings: code declares which SLO it instruments

SLO policy (target, window) stays in your SLO toolchain; code asserts only the binding. Sites
obtain their `slo` tag through a closed catalog — resolve bindings into constants so a typo fails
at wiring time, not on the first request:

```java
SloCatalog slos = SloCatalog.of("checkout-success", "refund-latency");
slos.bindTo(meterRegistry); // outcome.metrics.slo.info{slo="..."} = 1 per declared id

private static final KeyValue CHECKOUT_SLO = slos.binding("checkout-success");

observations.record("order.place", KeyValues.of(CHECKOUT_SLO).and(dims), work);
```

The info gauge proves at runtime which SLO ids this binary instruments; alert on rules referencing
an id with no info series. `@MeasuredOutcome(tags = {"slo=..."})` also works but is not
catalog-checked — the CI export (#64) is the check for those sites.

## Messaging: delivery fate, attempts, and lag as business signals

Broker binders expose lag and rates, not business fate. `recordDelivery` classifies what happens
to a failing message and buckets the attempt:

```java
return observations.recordDelivery(
    "order.consume",
    KeyValues.of("priority_class", "realtime"),
    delivery.attempt(),                       // 1-based → attempt_bucket=1|2_3|4_plus
    () -> handler.process(delivery),
    error -> error instanceof ValidationException
        ? DeliveryFate.DEAD_LETTER
        : DeliveryFate.RETRY);
```

Success → `fate=processed`; failure → the classifier's `retry`/`dead_letter`/`drop`; a
misbehaving classifier yields `fate=unknown` and **never masks the original exception** (this
classifier runs on an already-failed path — deliberately unlike the success-path classifiers).

Outbox drain and consumer-lag SLIs are plain dimensions — no special machinery:

```java
// outbox publisher: row age at publish
observations.record("outbox.publish",
    KeyValues.of(MessagingTags.TAG_LAG_BUCKET, MessagingTags.lagBucket(rowAge),
        "priority_class", "bulk"),
    () -> publish(row));

// consumer: lag at delivery × priority × outcome comes from the same two dimensions
```

`lag_bucket` is closed: `lt_1s|lt_10s|lt_1m|lt_10m|gte_10m` (strict upper bounds; negative lag
clamps to `lt_1s`). Message ids, offsets, and partitions must never become tags — partition is an
int no guard can recognize, so this rule is documentation and review.

## PII sentinel: deny list first, detectors as the net

`sanitizeTagValue` normalizes shape; it does not detect identity data. The opt-in policy redacts
tag values (keys stay — label sets hold) when the key is deny-listed or the value looks like an
email, UUID, JWT, IPv4, long hex, or long digit run:

```java
TagPrivacyPolicy policy = TagPrivacyPolicy.saasDefaults(); // user_id, email, ip, token, ...
policy.bindTo(meterRegistry); // outcome.metrics.tag_privacy.redacted

OutcomeObservations observations = new OutcomeObservations(
    observationRegistry,
    OutcomeObservationConvention.builder().tagPrivacyPolicy(policy).build());
```

The **deny list is the control** (matched on sanitized keys, so `userId` can't dodge `user_id`);
detectors are best-effort — pre-sanitized values have lost `@`, dots, and casing, so email/JWT/IPv4
detection only sees raw values. Known false positive: `1.2.3.4` version strings redact; use
`v1_2_3_4`. Runtime never throws; in tests, assert `policy.violations(tags)` is empty (#31). PII
in reason codes is `ReasonRegistry`'s job (#24). Scrubbing runs before all other enforcement so
raw values never reach the combination guard's memory or the registry.

## Experiments: flag × outcome without combinatorial cardinality

Register experiments up front (build-time cap, declared arms); runtime ids from your flag SDK
never become tag values unless registered:

```java
ExperimentRegistry experiments = ExperimentRegistry.builder()
    .experiment("checkout-v2")                                  // arms default control|treatment
    .experiment("onboarding", "control", "gentle", "aggressive") // declared arms, max 6
    .build();                                                    // > maxActive (10) fails here
experiments.bindTo(meterRegistry);

observations.record("checkout.place",
    experiments.slice(sdk.experimentId(), sdk.variant()).and("surface", "web"),
    () -> place(cmd));
```

Unregistered ids collapse to `experiment=unregistered, variant=unknown`; undeclared arms keep the
id and collapse the variant — both counted on `experiment_registry.unregistered`. **Every event of
a sliced name needs the bundle**: un-sliced traffic uses `ExperimentRegistry.none()` (label sets
must match per meter name). For experiment × surface × reason combinatorics, pair with the
`CombinationGuard`.

## Dual control: witness events, not a widened outcome

Maker-checker gaps span requests and restarts — only your workflow store knows a gap is open, so
the library holds no lifecycle state. Record witness *events* as observations, close gaps with the
store-computed duration, and publish pending counts as a gauge:

```java
// each witness event is a normal observation (tag every event of the name)
observations.record("payment.release",
    KeyValues.of(DualControl.WitnessAction.FIRST_APPROVAL.tag()).and("role", "approver"),
    () -> approve(request));

// on second approval / veto / expiry: record the gap your store measured
DualControl.recordGap(meterRegistry, "payment.release.witness_gap",
    KeyValues.of("role", "senior_approver"),
    store.gapDuration(request), DualControl.GapClosure.COMPLETED);

// pending gaps are a state, not an outcome
gauges.set("payment.release.pending_gaps", "open witness gaps", Tags.empty(), store.openGapCount());
```

`veto_after_approval` and `witness_timeout` ship as reasons routed **ticket** — a late veto is the
control working, and a 72h gap is a process failure, not an outage. Overdue-gap sweeping is the
reconcile pattern (`recordReconciliation`, #23). Never tag actor ids — and not their hashes either:
a hashed id is still pseudonymous personal data and unbounded cardinality; emit closed roles (the
PII sentinel's long-hex detector redacts identity hashes by design).

## Record with an annotation

```java
@MeasuredOutcome(name = "order.reserve", tags = {"step=reserve"})
public Reservation reserve(String sku) { ... }
```

- Type-level `@MeasuredOutcome` supplies defaults; method-level `name` wins.
- Tags merge: type first, then method.
- Spring: bean + proxy call (no self-invocation).
- Quarkus: CDI bean; non-private method.

Opt into compile-time validation of the annotation constants with `outcome-metrics-processor`
(add it to `annotationProcessorPaths`): malformed tag pairs, blank keys/values, and unresolvable
names fail the build instead of the first production request; non-canonical tokens warn. Dynamic
dimensions belong in `OutcomeObservations.record(...)`, never in annotation constants.

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

## Enforced reason vocabulary

`OutcomeReasonSource` is convention-only; a `ReasonRegistry` makes membership enforced. Unregistered
reasons are distrusted entirely — their observations emit `reason=unknown` **and**
`alertability=page` (a rogue reason must not silence its own page), counted on
`outcome.metrics.reason_registry.rejected`:

```java
ReasonRegistry vocabulary = ReasonRegistry.builder()
    .vocabulary(PaymentReasons.class)   // enum implementing OutcomeReason
    .codes("cache_stale")               // literal additions
    .build();
vocabulary.bindTo(meterRegistry);

OutcomeObservations observations = new OutcomeObservations(
    observationRegistry,
    OutcomeObservationConvention.builder()
        .reasonRegistry(vocabulary)
        .reasonBudget(new ReasonBudget(8, 64)) // optional; registry runs first
        .build());
```

Runtime enforcement never throws — telemetry must not turn one incident into two; blank codes fail
fast at registration. `codes()` exposes the sanitized vocabulary for tests and CI attestation
(export tooling: #64). Order with a budget: unregistered → `unknown`/page (no budget consumed);
registered but over budget → `other` with declared alertability preserved.

## What this library does not do

- Replace HTTP/DB auto-instrumentation
- Replace OpenTelemetry SDK
- Export metrics by itself (use Prometheus / OTLP via Micrometer)
