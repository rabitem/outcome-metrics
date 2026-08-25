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

Classified success (extra result tag, still keeps `outcome`/`reason`). **Declare the result-tag
keys** — every declared key presets to `none` on failure, so label sets stay consistent per meter
name (#60); an undeclared emitted key fails loudly:

```java
return observations.record(
    "order.payment",
    KeyValues.of("step", "classify"),
    () -> status,
    value -> MetricsTags.pairs("result=" + value),
    "result");
```

The tagger overloads without declared keys are deprecated: they emit tag keys on success only,
which corrupts the meter family the first time a classified operation fails: legacy Prometheus
clients reject the second registration outright, and the current client (Micrometer 1.13+) silently
exposes the mixed label sets — splitting `sum by (...)` aggregations without any error. Verified
against a real `PrometheusMeterRegistry` in the test suite.

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

## Rail divergence: measure the mismatch window, not the endpoint call

Local ledger commit and rail confirmation diverge for hours; endpoint latency doesn't measure that.
When your store closes a divergence window (webhook, adjudication feed), record its duration:

```java
// in the (idempotent! see recordIdempotent) webhook handler that closes the window
RailDivergence.recordWindow(meterRegistry, "payment.rail_divergence",
    KeyValues.of("local_state", "captured", "rail_state", "returned"),
    store.divergenceWindow(paymentId),
    RailDivergence.DivergenceResolution.RETURNED);
```

`resolution` is closed (`converged|returned|adjusted|written_off`); `local_state`/`rail_state`
pairs are **your** bounded vocabularies (one library enum can't serve payment rails and insurer
adjudication at once — pair with the `CombinationGuard` if the product needs it). Active divergence
counts are gauges (`LatestValueGauges`); rail-specific failure reasons are your `OutcomeReason`
vocabulary. Duplicate webhook deliveries must not double-record — that's exactly the
`recordIdempotent` pairing.

## Break-glass: an alertable success, not an auth failure

Emergency overrides are neither auth success nor RBAC denial. Tag lifecycle events with the closed
stage vocabulary; **alert on the activation rate itself** — that's the signal 401/403 metrics
can't carry:

```java
observations.record("clinical.override",
    KeyValues.of(BreakGlass.Stage.ACTIVATION.tag()).and("resource_class", "patient_record"),
    () -> openOverride(request));

// review closes days later; record the store-computed lag with the verdict
BreakGlass.recordReviewLag(meterRegistry, "clinical.override.review_lag",
    KeyValues.of("cause", "clinical_emergency"),
    store.reviewLag(overrideId), BreakGlass.ReviewVerdict.JUSTIFIED);
```

Overdue reviews come from your sweeper (reconcile pattern, #23) with `review_overdue` (ticket).
Active override counts are gauges. `cause` is your bounded vocabulary. Never tag patient or user
identifiers — resource classes and verdicts only (the PII sentinel redacts identity-shaped values).
**Operational metrics are not a legal audit trail**: the WORM audit is a different system, and
dashboards must never be offered to an auditor as evidence.

## Retry shadow: the cost hidden behind one green outcome

A resilient call succeeds on attempt N; attempts 1..N−1 burned latency and quota. Keep your retry
loop (Resilience4j, custom) and supply the summary at completion — on failure too, where depth and
dominant reason matter most:

```java
AtomicInteger attempts = new AtomicInteger();
List<OutcomeReason> shadowReasons = new ArrayList<>();

return observations.recordResilient("agent.tool_call", KeyValues.of("tool", "search"),
    () -> retry.executeSupplier(() -> { attempts.incrementAndGet(); return call(); }),
    () -> RetryShadow.of(attempts.get(), dominant(shadowReasons),
        stopwatch.shadowTime(), stopwatch.totalTime()));
```

Three closed tags ride the same series (never parallel meters): `attempt_bucket` (shared with
message deliveries), `dominant_reason` (typed `OutcomeReason` — closed by construction), and
`shadow_cost=none|minor|dominant`. First-try successes use `RetryShadow.firstTry()` so label sets
stay consistent. A misbehaving summary supplier leaves `unknown` presets and never masks the
original exception. `occurrence` (#18) dedups repeated observations; retry shadow summarizes
attempts inside one — complementary.

## Grounding: is the RAG answer backed by its evidence?

`integrity` grades technical trustworthiness; `grounding` grades epistemic backing — an answer can
be `integrity=ok` and `grounding=hallucinated_gap`, which is exactly the fleet blind spot. Your
judge supplies the verdict; the library can't detect a hallucination:

```java
return observations.recordGrounded(
    "rag.answer",
    KeyValues.of("retrieval_tier", "hybrid", "citation_mode", "inline"), // your vocabularies
    () -> pipeline.answer(query),
    answer -> answer.citations().isEmpty()
        ? (answer.usedRetrieval() ? GroundingFidelity.IGNORED_EVIDENCE
                                  : GroundingFidelity.NO_CORPUS_NEEDED)
        : GroundingFidelity.ALIGNED);
```

Failures keep `grounding=none`. Asynchronous LLM-as-judge verdicts arrive after the response —
record them as their *own* evaluation observation carrying the `grounding` tag instead of blocking
the request path. Alert on `hallucinated_gap`/`ignored_evidence` rates as quality regressions and
`no_corpus_needed` as wasted retrieval spend.

## Replay deltas: let the harness accuse itself first

Same fingerprint, different verdict — but only after the harness proves it reproduced the read
context. `ReplayDelta.classify` encodes the comparison order (context drift **voids** the verdict
comparison; then the most severe changed axis wins):

```java
ReplayDelta delta = ReplayDelta.classify(
    !capture.readContextFingerprint().equals(replay.readContextFingerprint()),
    !capture.verdict().equals(replay.verdict()),
    capture.latencyClass() != replay.latencyClass(),
    capture.costClass() != replay.costClass());

observations.record("eval.replay",
    KeyValues.of(delta.tag()).and("policy_version", policyVersion), // bounded
    () -> persistReplayResult(replay));
```

Resolve every relative expression (windows, `latest` aliases, index versions) **once at capture**
and replay the literals — a re-resolved expression at replay time makes `verdict_flip` a lie.
Fingerprints (input, policy, read context — and read-set hashes) never become tags: hashes are
pseudonymous and unbounded, and the PII sentinel redacts long hex by design; they live in the
harness's capture store.

## Async: bind outcomes to the terminal signal, not the assembly

Wrapping a `Mono` in `record(...)` times the assembly and stamps success before anything ran.
`startDeferred` starts now and settles at the terminal — from any thread, first terminal wins:

```java
DeferredOutcome outcome = observations.startDeferred("order.async", dims);
future.whenComplete((v, e) -> {
    if (e instanceof CancellationException) outcome.cancel();
    else if (e != null) outcome.fail(e);
    else outcome.succeed();
});
```

With `outcome-metrics-reactor`, publishers bind in one call (one observation **per subscription** —
a retry is two attempts), and `@MeasuredOutcome` on Mono/Flux-returning Spring beans auto-binds
when the module is on the classpath:

```java
return ReactorOutcomes.record(observations, "flow.fetch", dims, webClientCall());
```

Cancellation records `outcome=failure, reason=cancelled, alertability=none` — an expected terminal
(client disconnect) that wakes nobody and is rate-alertable; `cancelled` is schema floor, so
registries admit it and budgets never charge it. Deferred mode opens no `Observation.Scope`
(terminals fire on other threads): metrics and spans work, MDC propagation is
micrometer-context-propagation's job, and `OutcomeScope` fails open to `occurrence=first`. An
infinite Flux is a never-stopped observation — bind at request granularity. Mutiny: #81,
coroutines: #82.

## Virtual threads (Loom): already the good case

Per JEP 444, thread-locals are confined to their virtual thread — `OutcomeScope` opened on a
virtual thread **cannot** bleed to others sharing a carrier (proven by a 2,000-thread isolation
test in the suite). Scope-per-request on virtual-thread-per-request is the intended model; no
`ScopedValue` migration is needed (and `ScopedValue` is preview on this library's JDK 21 baseline).

Carrier **pinning** is an execution cost, not a result defect — a pinned success succeeded slowly,
and slowness is what the timer records. For pin observability, bind Micrometer's own
`micrometer-java21` `VirtualThreadMetrics` (JFR `jdk.VirtualThreadPinned`) and join on the
dashboard: pin-event rate against duration shifts of your outcome timers. Per-observation pin
attribution (thread × time-window correlation) costs more than it tells and is deliberately not
offered.

## Plugins, classloaders, and native images

If a plugin ships its **own copy** of this library, its exceptions implement a *different*
`OutcomeReasonSource` of the same name — `instanceof` fails and every plugin reason degrades to
`unknown`. The fix is deployment hygiene, not federation machinery: the host exports the API
package, plugins depend on it in **provided scope**, delegation is parent-first for
`io.github.rabitem.outcomemetrics.*`, and the host owns the `MeterRegistry` and injects it.
Diagnose the misconfiguration (in tests or error handlers, not the hot path):

```java
if (MetricTagValues.isForeignReasonSource(error)) {
    // a foreign copy of OutcomeReasonSource is on the classpath — fix the plugin's dependencies
}
```

GraalVM native: reason enums registered by class literal (`ReasonRegistry.vocabulary(MyReasons.class)`)
are reachable by construction — this library performs no by-name reason loading, so no
reflect-config is needed. The CI gate for vocabulary completeness is the attestation export (#64)
plus `ReasonVocabularyContracts` (#31).

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
