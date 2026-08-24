# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Classified result tags now support a declared key schema (#60): new
  `record(..., resultTagger, String... declaredResultTagKeys)` and the matching `recordClassified`
  overload preset every declared key to `none`, so failures emit the same label set as successes
  (Prometheus rejects inconsistent label sets per meter name). A tagger emitting an undeclared key
  fails loudly. The undeclared tagger overloads are deprecated; samples migrated.

- Observation conventions are consulted by Micrometer at start as well as stop; side-effectful
  tagging now acts only on settled state. Fixes `occurrence` deduplication keying on provisional
  start-time tags (a failure could be miscounted as a repeat of a prior success of the same
  operation within an `OutcomeScope`).

### Added

- Vocabulary attestation (#64): `VocabularyAttestation` in `outcome-metrics-test` renders reason
  codes, SLO ids, and experiment ids as canonical JSON and diffs them against a committed file —
  vocabulary drift fails CI, and `-Doutcome.metrics.attestation.update=true` regenerates the file
  as a reviewable diff.

- Foreign reason-source diagnostic (#41): `MetricTagValues.isForeignReasonSource(...)` detects the
  duplicated-library-copy misconfiguration that silently degrades plugin reasons to `unknown`
  (proven with a real child-first-classloader test). Classloader hygiene and GraalVM notes in the
  handbook; the proposed federation machinery and reflect-config APT are deliberately rejected
  (class-literal registration is reachable by construction; #64 is the CI gate).

- Virtual-thread guidance and isolation proof (#40): a 2,000-virtual-thread test proves
  `OutcomeScope` is confined per virtual thread (JEP 444 — no carrier bleed), so no `ScopedValue`
  migration is needed (preview on the JDK 21 baseline); pinning is documented as execution cost
  observed via `micrometer-java21` `VirtualThreadMetrics` joined on dashboards, deliberately not
  per-observation attribution. Docs-and-proof change; no new API.

- Terminal-signal outcome binding (#39): core `DeferredOutcome` primitive
  (`startDeferred` → `succeed`/`fail`/`cancel`, first terminal wins) plus the new
  `outcome-metrics-reactor` module (`ReactorOutcomes.record` for Mono/Flux, one observation per
  subscription) and Spring aspect auto-binding for reactive return types — `@MeasuredOutcome` on a
  `Mono` no longer stamps success at assembly. Cancellation records
  `reason=cancelled`/`alertability=none`, and `cancelled` joins the schema floor (registries admit
  it, budgets never charge it). Mutiny: #81; Kotlin coroutines: #82.

- Replay delta vocabulary (#38): `ReplayDelta` adopts the revised contract from the issue
  discussion — `context_drift` asserted before the verdict comparison voids it instead of blaming
  the system under test — with the precedence encoded in `classify(...)`. Replay runs record as
  observations with the `delta` dimension (no parallel counter); fingerprints never become tags.

- RAG grounding fidelity (#37): `recordGrounded(...)` classifies successes as
  `grounding=aligned|ignored_evidence|hallucinated_gap|no_corpus_needed` (failures keep
  `grounding=none`) — the verdict is the caller's judge; asynchronous judges record their own
  evaluation observation. No parallel counter; distinct from `integrity` on purpose
  (`integrity=ok` + `grounding=hallucinated_gap` is the blind spot).

- Retry shadow summaries (#36): `recordResilient(...)` attaches `attempt_bucket` (shared with
  #28), typed `dominant_reason`, and `shadow_cost=none|minor|dominant` to the one existing series —
  on success and on final failure (the case that needs it most). No ledger: the caller's resilience
  wrapper supplies the summary at completion; a misbehaving supplier leaves `unknown` presets and
  never masks the original exception.

- Retention-class routing (#35): closed `retention=ops|audit` vocabulary (`RetentionClass`) and
  prebuilt `RetentionFilters.auditOnly()`/`excludeAudit()` for `CompositeMeterRegistry` children —
  stock Micrometer does the routing; untagged observations are ops-class by default; documented as
  a routing hint, never a legal guarantee.

- Break-glass lifecycle observations (#34): `BreakGlass` — closed
  `break_glass=activation|access|review|closure` stage vocabulary (alert on the activation rate:
  an override is a success that must alert), `recordReviewLag(...)` with
  `verdict=justified|unjustified|inconclusive`, and the ticket-routed `review_overdue` reason.
  No lifecycle state; no patient/user identifiers; operational metrics are explicitly not a legal
  audit trail.

- Rail divergence windows (#33): `RailDivergence.recordWindow(...)` records store-computed
  divergence durations with `resolution=converged|returned|adjusted|written_off` (never-throw,
  clock-skew clamp; shared supplied-duration mechanics with #32). No handle state in the library;
  local/rail state pairs stay caller vocabularies; pairs with `recordIdempotent` webhook handling.

- Dual-control observations (#32): `DualControl` — `witness` action vocabulary
  (`first_approval|second_approval|veto|expiry`), `recordGap(...)` timer with
  `closure=completed|vetoed|expired` fed from the workflow store (never-throw, negative gaps
  clamp), and ticket-routed reasons `veto_after_approval`/`witness_timeout`. No lifecycle state in
  the library; pending gaps are gauges; `outcome` stays binary; roles only, never actor ids or
  their hashes.

- `outcome-metrics-test` module (#31): AssertJ-style contracts — `hasConsistentLabelSets()` (the
  test-time detector for #60), `hasOutcomeSchema(...)`, `hasSeriesCardinalityAtMost(...)`,
  `hasNoPrivacyViolations(policy)` — plus `ReasonVocabularyContracts.assertWellFormed(...)` and
  optional ArchUnit rules (`outcomeReasonsAreEnums`, `observationsOnlyFrom`). No JUnit dependency;
  no custom PIT mutators (standard PIT config documented instead); propagation verifier split to
  #72.

- Experiment outcome slices (#30): `ExperimentRegistry` with build-time caps (`maxActive`,
  declared arms ≤ 6) emits `experiment`/`variant` tags; unregistered runtime ids collapse to
  `unregistered`/`unknown` (raw flag keys never become tag values), undeclared arms collapse the
  variant only, both counted. `none()` bundle keeps label sets consistent for un-sliced traffic;
  info gauge per registered experiment.

- Tag PII sentinel (#29): opt-in `TagPrivacyPolicy` redacts caller-supplied tag values (keys kept)
  on deny-listed keys — matched on sanitized form — or identity-shaped values (email, UUID, JWT,
  IPv4, long hex, long digit runs), counted on `outcome.metrics.tag_privacy.redacted`. Runtime
  never throws; `violations(...)` is the test hook (#31). Runs before all other enforcement so raw
  values never reach guard memory. `saasDefaults()` ships a starting deny list.

- Messaging delivery fate (#28): `recordDelivery(...)` emits `fate=processed|retry|dead_letter|drop`
  plus a closed `attempt_bucket`; a misbehaving fate classifier yields `fate=unknown` and never
  masks the original exception. `MessagingTags.lagBucket(...)` serves outbox drain and
  consumer-lag × priority SLIs as plain dimensions. Ships in core — a separate messaging artifact
  waits for real broker dependencies.

- SLO bindings (#27): `SloCatalog` issues `slo=<id>` tags through a closed catalog (undeclared ids
  fail at wiring time) and registers `outcome.metrics.slo.info{slo}=1` per declared id so alerting
  can detect rules referencing ids the binary no longer instruments. SLO policy (target/window)
  deliberately stays in the SLO toolchain; declared ids feed the CI attestation export (#64).

- Combination cardinality guard (#26): optional `CombinationGuard` collapses rare guarded-tag
  combinations to `other` until they show `minSupport` events within one tumbling window; reveal
  is one-way per process, over-cap tuples fail closed, guarding `outcome`/`alertability` is
  rejected, collapsed events counted on `outcome.metrics.combination_guard.collapsed`.
  Documented as re-identification risk reduction, not k-anonymity.

- Opt-in `outcome-metrics-processor` module (#25): compile-time validation of `@MeasuredOutcome`
  constants — malformed tag pairs, blank keys/values, and unresolvable observation names are build
  errors (they would throw from the interceptor at runtime); legal-but-non-canonical tokens warn.
  Registered via `annotationProcessorPaths`; added to the BOM.

- Enforced reason vocabulary registry (#24): `ReasonRegistry` (explicit enum/literal registration,
  schema floor implicit) wired via `OutcomeObservationConvention.builder()`. Unregistered reasons
  are distrusted entirely — `reason=unknown` and forced `alertability=page` resolved in one step —
  with rejections counted on `outcome.metrics.reason_registry.rejected`. Runtime enforcement never
  throws; registry runs before the `ReasonBudget`. Attestation export: #64.

- Intent–commit–reconcile vocabulary (#23): `OutcomePhase` (`phase=intent|commit|reconcile`) and
  `recordReconciliation(...)` classifying findings as `disposition=confirmed|diverged|abandoned|
  deferred` (failures keep `disposition=none`; `phase=reconcile` added automatically). Abandonment
  is a reconcile finding, not a new outcome value — `outcome` stays binary.

- Shared-resource attribution tags (#22): `SharedResource.owned|borrowed|pooled(...)` emits a fixed
  five-tag bundle (`resource`, `relationship`, `consumer_tier`, `owner_tier`, `pool`) with
  consistent label sets per relationship (`owner_tier=self`/`shared`, `pool=none` defaults);
  factories reject UUID-shaped and long-hex values at construction.

- Idempotency outcome classifier (#21): `recordIdempotent(...)` tags successes
  `idempotency=applied|duplicate_skipped` and failures `idempotency=none` (consistent Prometheus
  label sets); `IdempotencyReason` (`idempotency_conflict` pages, `stale_replay` /
  `idempotency_key_missing` ticket) with `IdempotencyException` for the failure side. Keys and
  message ids never become tags.

- Alertability ladder on `OutcomeReason` (#20): reasons declare routing via
  `alertability()` (`PAGE` default, `TICKET`, `NONE`); every observation emits an `alertability`
  tag (`page`/`ticket`/`none`, success → `none`). Fail-loud: unclassified failures and broken
  implementations page. Derived from the reason object, so budget-suppressed `reason=other` keeps
  its level. Alertmanager routing examples in the handbook.

- Operator-expandable reason cardinality budget (#19): optional `ReasonBudget` admits the first N
  distinct failure reason codes per observation name (rest emit as `other` with a suppression
  counter); `expand()` restores full detail at runtime — including previously suppressed codes —
  and `collapse()` never evicts admitted codes. Ships a 0/1 mode gauge; no in-process burn-rate
  automation by design.

- Request-scoped outcome coalescing for SLI accuracy (#18): new try-with-resources `OutcomeScope`
  and an always-emitted `occurrence` tag (`first`/`repeat`). Repeats within a scope stay fully
  recorded (timers, spans); SLI queries filter `occurrence="first"`. Fails open without a scope.

- Integrity classification for quiet failures (#17): every observation now carries an `integrity` tag
  (`ok`/`degraded`/`empty` on success, `none` on failure). New `OutcomeIntegrity` vocabulary,
  `IntegrityClassifier`, and `OutcomeObservations.recordClassified(...)`; handbook covers the
  integrity-rate-vs-success-rate alert.

## [0.1.0-beta.2] - 2026-08-05

### Fixed

- Release workflow creates a draft GitHub Release, uploads signed artifacts, then publishes (immutable releases).

### Added

- Initial public preview extracted from Rettungshelden backend support libraries.
- `outcome-metrics` core (Micrometer-only): `OutcomeObservations`, bounded tag filters, `@MeasuredOutcome`.
- `outcome-metrics-spring-boot-starter` with `outcome.metrics.*` configuration and AOP interception.
- `outcome-metrics-quarkus` extension (CDI producers, meter filters, interceptor).
- Thin `outcome-metrics-bom` for version alignment.
- Developer handbook (`docs/handbook/`), `llms.txt`, samples (`:18080` / `:18081`), JMH suite (`benchmarks/`).

### Changed

- Spring/Quarkus: defer tag-value overflow gauge registration so MeterFilter beans cannot circular-depend on `MeterRegistry` creation (Boot 4 + Prometheus).
- CI/CD: pin GitHub Actions to commit SHAs; Dependabot grouped weekly updates with cooldown.
- Build plugins: CycloneDX 2.9.3, Central publishing 0.11.0; JSpecify 1.0.1.
- Unclassified failure reasons map to `unknown` (exception class names are opt-in via `MetricTagValues.exceptionCode`).
- Tag-value cardinality overflow remaps to `other` instead of denying meter registration.
- Classified successes keep the `outcome`/`reason` schema; result tags are sanitized.
- `MetricTagValues.reasonCode` walks the cause chain for `OutcomeReasonSource`.
- Spring: `outcome.metrics.enabled` kill switch; skip empty tag-limit filter beans; safe target resolution for static join points.
- Quarkus: `@DefaultBean` OutcomeObservations, extension status `preview`, interface annotation lookup, removable CDI beans.
- Overflow telemetry via `outcome.metrics.tag_value_overflows`; `LatestValueGauges` series cap; Spring `@Validated` properties; AspectJ starter.
