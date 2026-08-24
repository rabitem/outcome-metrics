# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
