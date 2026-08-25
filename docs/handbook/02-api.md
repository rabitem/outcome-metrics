# API

One entry point records a unit of work; the schema does the rest:

```java
@Inject // or constructor-inject
OutcomeObservations observations;

return observations.of("order.place")
    .dims(MetricsTags.pairs("channel=" + channel))
    .record(() -> placeOrder(cmd));
```

Every observation becomes a Micrometer timer (and a span when tracing is configured), tagged with
a closed, low-cardinality schema. This page covers, in order: the tag schema, the recording
builder, failure reasons and alert routing, async terminals, request scopes, the enforcement
pipeline, the domain vocabularies, annotations, and the hard rules that hold everything together.

## The tag schema

Always emitted, on every observation:

| Tag | Values | Semantics |
|---|---|---|
| `outcome` | `success` / `failure` | success = returned; failure = threw. Always binary — every other verdict rides a parallel tag |
| `reason` | `none` / `unknown` / your code | success → `none`; `OutcomeReasonSource` → your code; unclassified → `unknown` (never the exception class name) |
| `integrity` | `ok` / `degraded` / `empty` / `none` | success → classifier verdict (default `ok`); failure → `none` |
| `alertability` | `page` / `ticket` / `none` | failure → the reason's declared routing (default `page`); success → `none` |
| `occurrence` | `first` / `repeat` | first observation of a series per `OutcomeScope` → `first`; identical repeats → `repeat`; no scope → always `first` |

Emitted per operation when you opt in (declared, so failure series carry the key as `none`):

| Tag | Values | Opt-in via |
|---|---|---|
| `idempotency` | `applied` / `duplicate_skipped` / `none` | `.idempotency(...)` |
| `disposition` | `confirmed` / `diverged` / `abandoned` / `deferred` / `none` | `.reconciliation(...)` (adds `phase=reconcile`) |
| `grounding` | `aligned` / `ignored_evidence` / `hallucinated_gap` / `no_corpus_needed` / `none` | `.grounding(...)` |
| `fate` | `processed` / `retry` / `dead_letter` / `drop` / `unknown` | `.delivery(attempt, ...)` (adds `attempt_bucket`) |
| `attempt_bucket`, `dominant_reason`, `shadow_cost` | closed buckets | `.resilient(...)` |
| your keys | your sanitized values / `none` | `.resultTags(tagger, keys...)` |

**Never in tags:** user ids, emails, order ids, UUIDs, free text, idempotency keys, message
ids/offsets/partitions, intent/device/sync-batch ids, actor ids **or their hashes** (a hash is
still pseudonymous personal data and unbounded cardinality), fingerprints of any kind.

## The recording builder

`observations.of(name)` is the API. It accumulates dimensions, composes classifications, and
terminates in a recording call:

```java
Payment payment = observations.of("payment.capture")
    .dims(KeyValues.of("channel", "webhook"))
    .integrity((Payment r) -> r.complete() ? OutcomeIntegrity.OK : OutcomeIntegrity.DEGRADED)
    .idempotency(r -> r.wasNoOp() ? IdempotencyOutcome.DUPLICATE_SKIPPED : IdempotencyOutcome.APPLIED)
    .record(() -> handler.process(delivery));
```

Give the **first** classifier an explicitly typed lambda parameter (or a method reference) so the
result type infers; everything after follows. Terminals: `record(Runnable | Supplier)`,
`recordChecked(CheckedRunnable | CheckedSupplier)`, and `startDeferred()` (plain builder only —
classifications need a completed result).

### Result-shaped classifications

These grade what the operation *delivered*. They run on the success path and **fail loud**: a
classifier that throws or returns `null` fails the observation — a check that silently passes
would itself be a quiet failure.

- **`.integrity(...)`** — HTTP 200 can hide a blank PDF or partial write. Grade the result
  `ok`/`degraded`/`empty`; `outcome` stays `success`, so completion SLOs are untouched while the
  quiet failure becomes alertable.
- **`.idempotency(...)`** — at-least-once delivery makes duplicates expected: `applied` vs
  `duplicate_skipped`, so skipped duplicates neither destroy SLOs as failures nor hide the no-op
  rate as plain successes. Key conflicts, stale replays, and missing keys are **failures** — throw
  `IdempotencyException(IdempotencyReason.CONFLICT | STALE_REPLAY | KEY_MISSING)`
  (`idempotency_conflict` pages; the others ticket).
- **`.reconciliation(...)`** — server-side reconciliation of offline/edge claims. Adds
  `phase=reconcile` automatically (overriding a caller-supplied `phase`); the finding is
  `confirmed`/`diverged`/`abandoned`/`deferred`. Abandonment is a *finding* of a successful
  reconciliation, never a new `outcome` value. Client phases tag manually:
  `KeyValues.of(OutcomePhase.INTENT.tag())` / `OutcomePhase.COMMIT.tag()` — every event of a
  phase-spanning name must carry the tag.
- **`.grounding(...)`** — RAG epistemics: `integrity` grades technical trustworthiness,
  `grounding` grades evidence backing. `integrity=ok` + `grounding=hallucinated_gap` is exactly
  the fleet blind spot. Your judge supplies the verdict (the library cannot detect a
  hallucination); asynchronous LLM-as-judge verdicts record their *own* evaluation observation
  carrying the `grounding` tag instead of blocking the request path. `no_corpus_needed` is a cost
  signal, not a quality failure.
- **`.resultTags(tagger, "key", ...)`** — arbitrary bounded result tags over declared keys. A
  tagger that omits a declared key leaves `none`; one that emits an *undeclared* key fails loudly.
  Values are sanitized (`RETRY` → `retry`).

### Execution-shaped classifications

These summarize how the work *ran*. They also fire on the failure path, and there they **never
throw or mask**: the observation is recording an incident that already happened — a misbehaving
classifier leaves `unknown` presets and the original exception propagates untouched.

- **`.delivery(attempt, fateClassifier)`** — message handling. Adds `attempt_bucket`
  (`1`/`2_3`/`4_plus`; below 1 stays visible as `unknown`). Success → `fate=processed`; failure →
  your classifier's `retry`/`dead_letter`/`drop`. Outbox-drain and consumer-lag SLIs need no
  machinery: add `MessagingTags.lagBucket(rowAge)` (`lt_1s`…`gte_10m`, strict bounds, clock skew
  clamps to `lt_1s`) and a `priority_class` dimension.
- **`.resilient(shadowSupplier)`** — retries hidden inside one resilient call. Supply
  `RetryShadow.of(attempts, dominantReason, shadowTime, totalTime)` at completion (your wrapper
  holds the stats — no ledger); first-try successes use `RetryShadow.firstTry()`. Emits
  `attempt_bucket` + typed `dominant_reason` + `shadow_cost` (`none`/`minor`/`dominant`), on
  success **and** on final failure, where depth and dominant reason matter most.

### Composition rules

Multiple classifications combine — the preset union keeps label sets identical on every path.
Invalid combinations fail at build time, not silently: duplicate declared keys, `delivery` +
`resilient` (both own `attempt_bucket`, and a cross-process redelivery is not an in-process retry
count), and `startDeferred()` with any classification configured.

## Failure reasons and alert routing

Reasons are closed machine codes carried by exceptions:

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

The cause chain is walked for the first usable `OutcomeReasonSource`. Unclassified exceptions →
`reason=unknown` — never the exception class name.

Routing is part of the reason, and the default is **fail-loud**: every failure pages
(`alertability=page`) until its author explicitly downgrades to `TICKET` or `NONE`. Unclassified
failures and broken (`null`-returning) implementations page — nothing is silenced by omission. The
level derives from the reason *object*, so a budget-suppressed `reason=other` still carries its
declared alertability. Route alerts on the tag, never by regexing reason codes.

## Async: bind outcomes to the terminal signal

Wrapping a `Mono` in `record(...)` times the *assembly* and stamps success before anything ran.
`startDeferred` starts now and settles at the terminal — from any thread, first terminal wins:

```java
DeferredOutcome outcome = observations.of("order.async").dims(dims).startDeferred();
future.whenComplete((v, e) -> {
    if (e instanceof CancellationException) outcome.cancel();
    else if (e != null) outcome.fail(e);
    else outcome.succeed();
});
```

Adapters bind publishers in one call — one observation **per subscription**, so a retry is two
attempts — and `@MeasuredOutcome` on reactive-returning beans auto-binds when the adapter module
is on the classpath:

```java
// outcome-metrics-reactor (Mono/Flux, Spring aspect auto-binds)
return ReactorOutcomes.record(observations, "flow.fetch", dims, webClientCall());

// outcome-metrics-mutiny (Uni/Multi, Quarkus interceptor auto-binds)
return MutinyOutcomes.record(observations, "order.fetch", dims, client.fetch(id));
```

Cancellation records `outcome=failure, reason=cancelled, alertability=none` — an expected terminal
(client disconnect) that wakes nobody but is rate-alertable. `cancelled` is schema floor:
registries admit it, budgets never charge it. Deferred mode opens no `Observation.Scope`
(terminals fire on other threads): metrics and spans work, MDC propagation is
micrometer-context-propagation's job, and `OutcomeScope` fails open to `occurrence=first`. An
unterminated `Flux`/`Multi` is a never-stopped observation — bind at request granularity.
Kotlin coroutines: #82.

## Scopes: keep repeat storms out of SLIs

One downstream timeout in a request can emit many identical failure observations. Open an
`OutcomeScope` per unit of work; the first observation of each series tags `occurrence=first`,
identical repeats `occurrence=repeat`:

```java
try (OutcomeScope scope = OutcomeScope.open()) {
    handleRequest(); // any records inside, including @MeasuredOutcome methods
}
```

Nothing is dropped — timers, spans and totals keep every event; SLI queries filter
`occurrence="first"`. Deduplication keys on the full emitted series identity, applies symmetrically
to successes and failures, and fails open: no scope, another thread, or more than 1024 distinct
series per scope → `first`. Scopes are thread-confined and nest as a stack. The Spring starter can
open one per servlet request (`outcome.metrics.scope.enabled`, see [Spring Boot](03-spring-boot.md)).

**Virtual threads are the good case**: per JEP 444, thread-locals are confined to their virtual
thread — a scope cannot bleed across a carrier (proven by a 2,000-thread isolation test in the
suite), and no `ScopedValue` migration is needed (preview on the JDK 21 baseline). Carrier
*pinning* is execution cost, not a result defect — observe it with `micrometer-java21`'s
`VirtualThreadMetrics` and join pin rate against duration shifts on the dashboard; per-observation
pin attribution is deliberately not offered.

## The enforcement pipeline

Optional guards compose onto the convention and run in a fixed order — privacy scrub → reason
registry → reason budget → combination guard — before the tags reach the registry:

```java
OutcomeObservations observations = new OutcomeObservations(
    observationRegistry,
    OutcomeObservationConvention.builder()
        .tagPrivacyPolicy(TagPrivacyPolicy.saasDefaults())
        .reasonRegistry(ReasonRegistry.builder().vocabulary(PaymentReasons.class).codes("cache_stale").build())
        .reasonBudget(new ReasonBudget(8, 64))
        .combinationGuard(CombinationGuard.builder()
            .keys("region", "product").minSupport(20).window(Duration.ofMinutes(15)).build())
        .build());
```

Each is a `MeterBinder` (call `bindTo(meterRegistry)` for its gauges/counters), and both framework
adapters can compose all four from configuration ([Spring](03-spring-boot.md) ·
[Quarkus](04-quarkus.md)). Runtime enforcement **never throws** — telemetry must not turn one
incident into two; validation fails fast at registration/wiring time instead.

- **`TagPrivacyPolicy`** — redacts tag *values* (keys stay, so label sets hold) when the key is
  deny-listed or the value looks like an email, UUID, JWT, IPv4, long hex, or long digit run;
  counted on `tag_privacy.redacted`. The deny list is the control (matched on sanitized keys, so
  `userId` can't dodge `user_id`); detectors are best-effort — pre-sanitized values have lost `@`,
  dots, and casing. Known false positive: `1.2.3.4` version strings redact; use `v1_2_3_4`. In
  tests, assert `policy.violations(tags)` is empty. Runs first so raw values never reach guard
  memory or the registry.
- **`ReasonRegistry`** — makes reason membership enforced, not conventional. Unregistered reasons
  are distrusted *entirely*: `reason=unknown` **and** forced `alertability=page` (a rogue reason
  must not silence its own page), counted on `reason_registry.rejected`. The schema floor
  (`none`/`unknown`/`other`/`cancelled`) is implicitly registered; `codes()` feeds the CI
  attestation ([Testing](06-testing.md)).
- **`ReasonBudget`** — bounds distinct `reason` codes per observation name; the rest emit `other`
  with a suppression counter. `expand()` restores full detail at runtime — effective on the next
  event, *including previously suppressed codes*; `collapse()` re-bounds new codes but never
  evicts admitted ones. Expansion is deliberately manual (runbook/webhook, not an in-process
  burn-rate copy). Floor codes never consume budget; registered-over-budget keeps its declared
  alertability. If you also bound `reason` with a tag-value filter, keep that bound ≥ the expanded
  limit — filter remaps are pinned by Micrometer's pre-filter id cache.
- **`CombinationGuard`** — per-tag limits miss rare combinations (region × product × reason) that
  can point at tiny cohorts. Guarded-key combinations emit `other` until they show `minSupport`
  events within one tumbling window; reveal is one-way per process (the first `minSupport − 1`
  events stay collapsed — counters are monotonic), slow-trickling tuples never reveal, and over
  the tuple cap the guard fails **closed**. Guarding `outcome` or `alertability` is rejected at
  build time. **This is not k-anonymity** — support counts events, not individuals; it is
  re-identification *risk reduction* in front of backend controls.

## Domain vocabularies

Closed vocabularies and helpers for specific domains. All follow the same rules: no lifecycle
state in the library (only your store spans requests and restarts — helpers take store-computed
durations), caller-owned dimensions where the library has no authority, and consistent label sets.

- **`SharedResource`** — multi-tenant attribution: `owned(type, consumerTier)` /
  `borrowed(type, consumerTier, ownerTier)` / `pooled(type, consumerTier)` emit a fixed five-tag
  bundle (`resource`, `relationship`, `consumer_tier`, `owner_tier`, `pool`) with `owner_tier=self`
  for owned, `shared` for pooled, `pool=none` unless `withPool(...)`. Factories throw on
  UUID-shaped/long-hex values — a tenant id in a tag fails in your tests, not on the pager.
- **`SloCatalog`** — code declares *which* SLO it instruments; policy (target, window) stays in
  your SLO toolchain. `binding("checkout-success")` returns the `slo` tag and throws for
  undeclared ids — resolve into constants so typos fail at wiring. `bindTo` emits
  `outcome.metrics.slo.info{slo}=1` per id; alert on `absent(...)` when rules reference an id the
  binary no longer instruments. `@MeasuredOutcome(tags = {"slo=..."})` works but is not
  catalog-checked — the CI attestation covers those sites. Rule generation:
  [Operations](08-operations.md#slo-scaffolding-generate-the-rules-keep-the-policy).
- **`ExperimentRegistry`** — flag × outcome without combinatorial cardinality. Register
  experiments up front (`maxActive` cap, declared arms ≤ 6, default `control|treatment`);
  `slice(id, arm)` never throws: unregistered ids collapse to `experiment=unregistered,
  variant=unknown` (raw flag keys mechanically never become tags), undeclared arms collapse the
  variant only, both counted. Un-sliced traffic on a sliced name uses `ExperimentRegistry.none()`.
- **`DualControl`** — maker-checker without a widened outcome: witness *events* are observations
  tagged `WitnessAction.FIRST_APPROVAL|SECOND_APPROVAL|VETO|EXPIRY.tag()`; the gap records via
  `DualControl.recordGap(registry, name, dims, storeDuration, GapClosure.COMPLETED|VETOED|EXPIRED)`;
  pending gaps are a *state* — publish a gauge (`LatestValueGauges`). `veto_after_approval` and
  `witness_timeout` ship ticket-routed: a late veto is the control *working*, a 72h gap is process
  failure, not outage. Roles only — never actor ids or their hashes.
- **`BreakGlass`** — an emergency override is a **success that must alert**: alert on the
  `break_glass="activation"` stage rate itself (the signal 401/403 metrics can't carry). Stages
  `ACTIVATION|ACCESS|REVIEW|CLOSURE`; review lag via `recordReviewLag(...)` with
  `verdict=justified|unjustified|inconclusive`; overdue reviews come from your sweeper
  (reconciliation pattern) with the ticket-routed `review_overdue` reason. **Operational metrics
  are not a legal audit trail** — the WORM audit is a different system, and dashboards are never
  audit evidence.
- **`RailDivergence`** — local ledger vs payment/clinical rail state diverge for hours; endpoint
  latency doesn't measure that. When your store closes a window:
  `recordWindow(registry, name, dims, storeDuration, DivergenceResolution.CONVERGED|RETURNED|ADJUSTED|WRITTEN_OFF)`.
  `local_state`/`rail_state` pairs are *your* bounded vocabularies. Close windows from idempotent
  webhook handlers (`.idempotency(...)`) so duplicate deliveries can't double-record.
- **`RetentionClass` / `RetentionFilters`** — two TTLs, one instrumentation: tag audit-worthy
  operations `RetentionClass.AUDIT.tag()` (untagged = ops by default — nothing becomes audit-class
  by accident) and route with stock Micrometer (`CompositeMeterRegistry` + `auditOnly()` /
  `excludeAudit()` on children). A routing hint, never a legal guarantee.
- **`ReplayDelta`** — eval flakiness with the harness accusing itself first:
  `classify(contextDiffers, verdictFlipped, latencyShifted, costShifted)` encodes the precedence —
  an unreproduced read context **voids** the comparison (`context_drift`) before any
  `verdict_flip`/`latency_class_shift`/`cost_class_shift`. Resolve relative expressions once at
  capture and replay the literals; fingerprints never become tags.

## Annotations

```java
@MeasuredOutcome(name = "order.reserve", tags = {"step=reserve"})
public Reservation reserve(String sku) { ... }
```

- Type-level `@MeasuredOutcome` supplies defaults; method-level `name` wins; tags merge type-first.
- Spring: bean + proxy call (no self-invocation). Quarkus: CDI bean, non-private method.
- Reactive return types (`Mono`/`Flux` with `outcome-metrics-reactor`, `Uni`/`Multi` with
  `outcome-metrics-mutiny`) bind to the terminal signal automatically.
- Opt into compile-time validation with `outcome-metrics-processor` in `annotationProcessorPaths`:
  malformed tag pairs, blank keys/values, and unresolvable names fail the build instead of the
  first production request; non-canonical tokens warn. Dynamic dimensions belong in the builder,
  never in annotation constants.

## The hard rules

- **`outcome` stays binary.** Every richer verdict — integrity, grounding, dispositions, fates —
  rides a parallel tag, so `outcome=failure` ratio queries never break.
- **Closed vocabularies only.** Values come from enums and declared sets; anything open-ended
  degrades to a floor value (`unknown`, `other`, `unregistered`) instead of minting series.
- **Tags, never parallel meters.** A second meter for the same events drifts from the first and
  can't join against the schema.
- **Label sets are law.** Every series of a meter name carries the same tag keys — declared keys
  preset to `none` on the paths that don't produce values. Getting this wrong is ugly on *every*
  client: legacy Prometheus clients reject the second registration outright, and the current
  client (Micrometer 1.13+) silently exposes the mixed label sets, splitting `sum by (...)`
  aggregations with no error anywhere — verified against a real `PrometheusMeterRegistry` in the
  test suite. `hasConsistentLabelSets()` ([Testing](06-testing.md)) is the loud gate.
- **Telemetry never throws at emission time**, and failure-path helpers never mask the original
  exception. Fail fast at registration/wiring/build time instead.
- **Signal guards fail open; privacy guards fail closed.** Over a scope/budget cap, signal stays
  visible; over the combination-guard cap, tuples stay hidden. Opposite directions, both on
  purpose.

## Plugins, classloaders, and native images

If a plugin ships its **own copy** of this library, its exceptions implement a *different*
`OutcomeReasonSource` of the same name — `instanceof` fails and every plugin reason degrades to
`unknown`. The fix is deployment hygiene, not federation: the host exports the API package,
plugins depend on it in **provided scope**, delegation is parent-first for
`io.github.rabitem.outcomemetrics.*`, and the host owns the injected `MeterRegistry`. Diagnose in
tests or error handlers (not the hot path):

```java
if (MetricTagValues.isForeignReasonSource(error)) {
    // a foreign copy of OutcomeReasonSource is on the classpath — fix the plugin's dependencies
}
```

GraalVM native: reason enums registered by class literal
(`ReasonRegistry.vocabulary(MyReasons.class)`) are reachable by construction — the library performs
no by-name reason loading, so no reflect-config is needed. Vocabulary completeness is a CI concern:
attestation + `ReasonVocabularyContracts` ([Testing](06-testing.md)).

## Legacy entry points

The specialized methods (`recordClassified`, `recordIdempotent`, `recordReconciliation`,
`recordDelivery`, `recordResilient`, `recordGrounded`, and the tagger overloads of `record`) are
`@Deprecated` delegates to the builder, slated for removal at 1.0. Semantics are unchanged — the
builder is the same machinery with composition. Plain `record`/`recordChecked`
(name-dims-work) and `startDeferred(name, dims)` remain undeprecated conveniences.

## What this library does not do

- Replace HTTP/DB auto-instrumentation
- Replace the OpenTelemetry SDK
- Export metrics by itself (use Prometheus / OTLP via Micrometer)
