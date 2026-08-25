package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MessagingTags;
import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.github.rabitem.outcomemetrics.MetricsTags;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records the outcome of a unit of work as a Micrometer {@link Observation}.
 *
 * <p>The observation is tagged with caller-provided dimensions and an outcome classification derived
 * from success, failure, or a returned result. Micrometer can turn the same observation into metrics
 * and, when a tracing bridge is configured, spans.
 *
 * @since 0.1.0
 */
public final class OutcomeObservations {

    private final ObservationRegistry observationRegistry;
    private final OutcomeObservationConvention convention;

    /**
     * Creates an outcome observation helper.
     *
     * @param observationRegistry registry to record against; must not be {@code null}
     */
    public OutcomeObservations(final ObservationRegistry observationRegistry) {
        this(observationRegistry, OutcomeObservationConvention.INSTANCE);
    }

    /**
     * Creates an outcome observation helper whose failure reason codes flow through a
     * {@link ReasonBudget}.
     *
     * @param observationRegistry registry to record against; must not be {@code null}
     * @param reasonBudget        budget admitting reason codes per observation name; must not be
     *                            {@code null}
     */
    public OutcomeObservations(final ObservationRegistry observationRegistry, final ReasonBudget reasonBudget) {
        this(observationRegistry, OutcomeObservationConvention.withReasonBudget(reasonBudget));
    }

    /**
     * Creates an outcome observation helper with a custom-composed convention.
     *
     * <p>Use {@link OutcomeObservationConvention#builder()} to compose reason enforcement, for
     * example a {@link ReasonRegistry} together with a {@link ReasonBudget}.
     *
     * @param observationRegistry registry to record against; must not be {@code null}
     * @param convention          convention to tag with; must not be {@code null}
     */
    public OutcomeObservations(
            final ObservationRegistry observationRegistry,
            final OutcomeObservationConvention convention) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
        this.convention = Objects.requireNonNull(convention, "convention must not be null");
    }

    /**
     * Runs work as an observation, recording success or failure.
     *
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     */
    public void record(final String name, final KeyValues dimensions, final Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        runUnchecked(name, context(dimensions), () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs work as an observation, recording success or failure.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T record(final String name, final KeyValues dimensions, final Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        return runUnchecked(name, context(dimensions), work);
    }

    /**
     * Runs checked work as an observation, recording success or failure.
     *
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     * @throws Throwable if {@code work} throws
     */
    public void recordChecked(final String name, final KeyValues dimensions, final CheckedRunnable work)
            throws Throwable {
        Objects.requireNonNull(work, "work must not be null");
        recordChecked(name, dimensions, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs checked work as an observation, recording success or failure.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     * @return result from {@code work}
     * @throws Throwable if {@code work} throws
     */
    public <T> T recordChecked(final String name, final KeyValues dimensions, final CheckedSupplier<T> work)
            throws Throwable {
        Objects.requireNonNull(work, "work must not be null");
        return run(name, context(dimensions), work);
    }

    /**
     * Runs work as a result-classified observation.
     *
     * @param <T>          result type
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param work         work to time and observe; must not be {@code null}
     * @param resultTagger maps a successful result to bounded low-cardinality tags; must not be
     *                     {@code null}
     * @return result from {@code work}
     * @deprecated result tags are emitted on success only, so an operation that can fail produces
     * inconsistent label sets under one meter name — legacy Prometheus clients reject that at
     * registration, and the current client silently exposes the mixed sets, splitting aggregations
     * (issue #60). Use {@link #record(String, KeyValues, Supplier, Function, String...)} and declare
     * the tag keys — failures then emit every declared key as {@code none}.
     */
    @Deprecated(since = "0.1.0")
    public <T> T record(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final Function<? super T, KeyValues> resultTagger) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(resultTagger, "resultTagger must not be null");
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        return runUnchecked(name, context, () -> {
            final T result = work.get();
            context.setResultTags(MetricsTags.sanitize(resultTagger.apply(result)));
            return result;
        });
    }

    /**
     * Runs work as a result-classified observation with a declared result-tag schema.
     *
     * <p>Every declared key presets to {@code none}, so failures emit the same label set as
     * successes (mixed label sets crash legacy Prometheus clients and silently split aggregations
     * on current ones — issue #60). A tagger
     * that omits a declared key leaves {@code none}; a tagger that emits an <em>undeclared</em> key
     * fails the observation loudly — silently dropping it would re-create the bug one key at a
     * time.
     *
     * @param <T>                    result type
     * @param name                   observation name; must not be blank
     * @param dimensions             low-cardinality dimension tags; must not be {@code null}
     * @param work                   work to time and observe; must not be {@code null}
     * @param resultTagger           maps a successful result to bounded tags over the declared keys
     * @param declaredResultTagKeys  the complete result-tag key schema; at least one, none blank
     * @return result from {@code work}
     */
    public <T> T record(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final Function<? super T, KeyValues> resultTagger,
            final String... declaredResultTagKeys) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(resultTagger, "resultTagger must not be null");
        final KeyValues presets = declaredPresets(declaredResultTagKeys);
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        context.setResultTags(presets);
        return runUnchecked(name, context, () -> {
            final T result = work.get();
            context.setResultTags(declaredOnly(
                    presets, MetricsTags.sanitize(resultTagger.apply(result)), declaredResultTagKeys));
            return result;
        });
    }

    /**
     * Runs work as an integrity-classified observation.
     *
     * <p>The operation still records {@code outcome=success} when it completes without throwing;
     * {@code classifier} additionally grades the delivered result as {@code ok}, {@code degraded} or
     * {@code empty} on the {@code integrity} tag. This keeps quiet failures (blank documents, partial
     * writes, swallowed downstream calls) alertable without breaking SLOs that track technical
     * completion.
     *
     * <p>If {@code classifier} throws or returns {@code null}, the observation records a failure: an
     * integrity check that silently passes would itself be a quiet failure.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     * @param classifier grades the successful result's integrity; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T recordClassified(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final IntegrityClassifier<? super T> classifier) {
        return recordClassified(name, dimensions, work, classifier, result -> KeyValues.empty());
    }

    /**
     * Runs work as an integrity-classified observation with additional result tags.
     *
     * <p>Combines {@link #recordClassified(String, KeyValues, Supplier, IntegrityClassifier)} with a
     * result tagger as in {@link #record(String, KeyValues, Supplier, Function)}.
     *
     * @param <T>          result type
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param work         work to time and observe; must not be {@code null}
     * @param classifier   grades the successful result's integrity; must not be {@code null}
     * @param resultTagger maps a successful result to bounded low-cardinality tags; must not be
     *                     {@code null}
     * @return result from {@code work}
     * @deprecated same label-set hazard as the tagger overload of {@code record} (issue #60); use
     * {@link #recordClassified(String, KeyValues, Supplier, IntegrityClassifier, Function, String...)}
     * with declared keys.
     */
    @Deprecated(since = "0.1.0")
    public <T> T recordClassified(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final IntegrityClassifier<? super T> classifier,
            final Function<? super T, KeyValues> resultTagger) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(resultTagger, "resultTagger must not be null");
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        return runUnchecked(name, context, () -> {
            final T result = work.get();
            context.setIntegrity(
                    Objects.requireNonNull(classifier.classify(result), "classifier must not return null"));
            context.setResultTags(MetricsTags.sanitize(resultTagger.apply(result)));
            return result;
        });
    }

    /**
     * Runs work as an integrity-classified observation with a declared result-tag schema.
     *
     * <p>Combines the integrity classifier with declared result tags: every declared key presets to
     * {@code none} so failures emit the same label set as successes (issue #60); an undeclared
     * emitted key fails the observation loudly.
     *
     * @param <T>                   result type
     * @param name                  observation name; must not be blank
     * @param dimensions            low-cardinality dimension tags; must not be {@code null}
     * @param work                  work to time and observe; must not be {@code null}
     * @param classifier            grades the successful result's integrity; must not be {@code null}
     * @param resultTagger          maps a successful result to bounded tags over the declared keys
     * @param declaredResultTagKeys the complete result-tag key schema; at least one, none blank
     * @return result from {@code work}
     */
    public <T> T recordClassified(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final IntegrityClassifier<? super T> classifier,
            final Function<? super T, KeyValues> resultTagger,
            final String... declaredResultTagKeys) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(resultTagger, "resultTagger must not be null");
        final KeyValues presets = declaredPresets(declaredResultTagKeys);
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        context.setResultTags(presets);
        return runUnchecked(name, context, () -> {
            final T result = work.get();
            context.setIntegrity(
                    Objects.requireNonNull(classifier.classify(result), "classifier must not return null"));
            context.setResultTags(declaredOnly(
                    presets, MetricsTags.sanitize(resultTagger.apply(result)), declaredResultTagKeys));
            return result;
        });
    }

    private static KeyValues declaredPresets(final String[] declaredKeys) {
        Objects.requireNonNull(declaredKeys, "declaredResultTagKeys must not be null");
        if (declaredKeys.length == 0) {
            throw new IllegalArgumentException("declare at least one result tag key");
        }
        final List<KeyValue> presets = new ArrayList<>(declaredKeys.length);
        for (final String key : declaredKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("declared result tag key must not be blank");
            }
            presets.add(KeyValue.of(key.strip(), MetricTagValues.NONE));
        }
        return KeyValues.of(presets);
    }

    private static KeyValues declaredOnly(
            final KeyValues presets,
            final KeyValues emitted,
            final String[] declaredKeys) {
        final Set<String> declared = new HashSet<>();
        for (final String key : declaredKeys) {
            declared.add(key.strip());
        }
        for (final KeyValue tag : emitted) {
            if (!declared.contains(tag.getKey())) {
                throw new IllegalStateException("result tagger emitted undeclared key \""
                        + tag.getKey() + "\"; declared keys: " + declared);
            }
        }
        return presets.and(emitted);
    }

    /**
     * Runs an idempotency-guarded operation as an observation.
     *
     * <p>Successful results carry an {@code idempotency} tag with the classifier's disposition
     * ({@code applied} or {@code duplicate_skipped}); failures carry {@code idempotency=none}, so the
     * tag key is present on every series of the observation name (label sets must stay consistent
     * per meter name). Idempotency key conflicts, stale replays, and missing keys are
     * failures — throw {@link IdempotencyException} instead of classifying them as success shapes.
     *
     * <p>If {@code classifier} throws or returns {@code null}, the observation records a failure.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       work to time and observe; must not be {@code null}
     * @param classifier maps the successful result to its idempotency disposition; must not be
     *                   {@code null}
     * @return result from {@code work}
     */
    public <T> T recordIdempotent(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final IdempotencyClassifier<? super T> classifier) {
        Objects.requireNonNull(classifier, "classifier must not be null");
        return recordDeclaredResultTag(name, dimensions, work, IdempotencyOutcome.TAG_IDEMPOTENCY,
                result -> Objects.requireNonNull(
                        classifier.classify(result), "classifier must not return null").tagValue());
    }

    /**
     * Runs a RAG operation as a grounding-classified observation.
     *
     * <p>Successful results carry a {@code grounding} tag with the classifier's verdict
     * ({@code aligned}, {@code ignored_evidence}, {@code hallucinated_gap} or
     * {@code no_corpus_needed}); failures carry {@code grounding=none}, keeping label sets
     * consistent per meter name. If {@code classifier} throws or returns {@code null}, the
     * observation records a failure. Asynchronous judges record their own evaluation observation
     * instead of using this method.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       RAG work to time and observe; must not be {@code null}
     * @param classifier maps the successful result to its grounding verdict; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T recordGrounded(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final GroundingClassifier<? super T> classifier) {
        Objects.requireNonNull(classifier, "classifier must not be null");
        return recordDeclaredResultTag(name, dimensions, work, GroundingFidelity.TAG_GROUNDING,
                result -> Objects.requireNonNull(
                        classifier.classify(result), "classifier must not return null").tagValue());
    }

    /**
     * Runs a server-side reconciliation pass as an observation.
     *
     * <p>The dimensions automatically carry {@code phase=reconcile} (overriding any caller-supplied
     * {@code phase}). Successful passes carry a {@code disposition} tag with the classifier's
     * finding ({@code confirmed}, {@code diverged}, {@code abandoned} or {@code deferred}); failures
     * carry {@code disposition=none}, keeping label sets consistent per meter name. Abandonment and
     * divergence are findings of a successful reconciliation, not new {@code outcome} values —
     * {@code outcome} stays binary.
     *
     * <p>If {@code classifier} throws or returns {@code null}, the observation records a failure.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param work       reconciliation work to time and observe; must not be {@code null}
     * @param classifier maps the successful result to its reconciliation finding; must not be
     *                   {@code null}
     * @return result from {@code work}
     */
    public <T> T recordReconciliation(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final ReconcileClassifier<? super T> classifier) {
        Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        return recordDeclaredResultTag(
                name,
                dimensions.and(OutcomePhase.RECONCILE.tag()),
                work,
                ReconcileDisposition.TAG_DISPOSITION,
                result -> Objects.requireNonNull(
                        classifier.classify(result), "classifier must not return null").tagValue());
    }

    /**
     * Runs a message delivery attempt as an observation with fate and attempt classification.
     *
     * <p>Dimensions automatically carry {@code attempt_bucket} (from the 1-based {@code attempt}).
     * Success emits {@code fate=processed}; on failure, {@code classifier} maps the error to
     * {@code retry}, {@code dead_letter} or {@code drop}. A {@code null}-returning or throwing
     * classifier yields {@code fate=unknown} and the original exception propagates untouched —
     * unlike the success-path classifiers, this one must never convert one incident into two.
     *
     * <p>Message ids, offsets, and partitions must never appear in {@code dimensions}.
     *
     * @param <T>        result type
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @param attempt    1-based delivery attempt number; below 1 emits {@code attempt_bucket=unknown}
     * @param work       delivery work to time and observe; must not be {@code null}
     * @param classifier maps a delivery failure to its fate; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T recordDelivery(
            final String name,
            final KeyValues dimensions,
            final int attempt,
            final Supplier<T> work,
            final DeliveryFateClassifier classifier) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        final OutcomeObservationContext context = context(
                dimensions.and(MessagingTags.TAG_ATTEMPT_BUCKET, MessagingTags.attemptBucket(attempt)));
        context.markClassified();
        context.setResultTags(KeyValues.of(DeliveryFate.TAG_FATE, MetricTagValues.UNKNOWN));
        try {
            return run(name, context, () -> {
                final T result = work.get();
                context.setResultTags(KeyValues.of(DeliveryFate.TAG_FATE, DeliveryFate.PROCESSED.tagValue()));
                return result;
            }, error -> {
                final DeliveryFate fate = classifier.classify(error);
                if (fate != null) {
                    context.setResultTags(KeyValues.of(DeliveryFate.TAG_FATE, fate.tagValue()));
                }
            });
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable t) {
            throw new IllegalStateException("supplier threw a checked throwable", t);
        }
    }

    /**
     * Runs a resilient (internally retrying) call as one observation with a retry-shadow summary.
     *
     * <p>The retry loop stays the caller's (Resilience4j, custom); {@code shadow} is invoked once
     * when the call completes — on success <em>and</em> failure, because retry depth and dominant
     * reason matter most when attempt N also failed — and its three closed tags ride this series
     * ({@code attempt_bucket}, {@code dominant_reason}, {@code shadow_cost}; presets {@code unknown}).
     *
     * <p>The shadow supplier summarizes telemetry, not the business result: unlike the success-path
     * classifiers it never fails the observation — a {@code null} return or a throw leaves the
     * {@code unknown} presets, and on the failure path the original exception propagates untouched.
     *
     * @param <T>    result type
     * @param name   observation name; must not be blank
     * @param dims   low-cardinality dimension tags; must not be {@code null}
     * @param work   resilient call to time and observe; must not be {@code null}
     * @param shadow supplies the retry summary at completion; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T recordResilient(
            final String name,
            final KeyValues dims,
            final Supplier<T> work,
            final Supplier<RetryShadow> shadow) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(shadow, "shadow must not be null");
        final OutcomeObservationContext context = context(dims);
        context.markClassified();
        context.setResultTags(RetryShadow.unknownTags());
        try {
            return run(name, context, () -> {
                final T result = work.get();
                applyShadow(context, shadow);
                return result;
            }, error -> applyShadow(context, shadow));
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable t) {
            throw new IllegalStateException("supplier threw a checked throwable", t);
        }
    }

    private static void applyShadow(final OutcomeObservationContext context, final Supplier<RetryShadow> shadow) {
        try {
            final RetryShadow summary = shadow.get();
            if (summary != null) {
                context.setResultTags(summary.tags());
            }
        } catch (final RuntimeException | Error ignored) {
            // Summarizing telemetry must not fail the observation or mask the original exception.
        }
    }

    private <T> T recordDeclaredResultTag(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final String tagKey,
            final Function<? super T, String> successValue) {
        Objects.requireNonNull(work, "work must not be null");
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        // Preset so a failure still emits the tag key with value none, keeping label sets consistent.
        context.setResultTags(KeyValues.of(tagKey, MetricTagValues.NONE));
        return runUnchecked(name, context, () -> {
            final T result = work.get();
            context.setResultTags(KeyValues.of(tagKey, successValue.apply(result)));
            return result;
        });
    }

    /**
     * Starts an observation for asynchronous work whose terminal signal fires later.
     *
     * <p>Use for publishers, futures, and coroutines where synchronous {@code record(...)} would
     * time the assembly and stamp success before anything ran. All composed enforcement (reason
     * budget/registry, combination guard, privacy policy) applies. The caller must settle the
     * returned handle exactly once; racing terminals are safe (first wins). See
     * {@link DeferredOutcome} for the contract, including cancellation semantics and the
     * no-scope caveat.
     *
     * @param name       observation name; must not be blank
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     * @return a started deferred outcome, never {@code null}
     */
    public DeferredOutcome startDeferred(final String name, final KeyValues dimensions) {
        final OutcomeObservationContext context = context(dimensions);
        final Observation observation = observation(name, context);
        observation.start();
        return new DeferredOutcome(observation, context);
    }

    /**
     * Starts, scopes, and stops the observation, marking the context settled before error and stop.
     *
     * <p>Micrometer consults the convention at {@code start()} as well as {@code stop()}, with
     * provisional state (no error yet, preset result tags). Side-effectful tagging — occurrence
     * marking, combination-guard support counting — must only act on the settled, final state; the
     * convention returns provisional values for unsettled consultations.
     */
    private <T> T run(final String name, final OutcomeObservationContext context, final CheckedSupplier<T> work)
            throws Throwable {
        return run(name, context, work, null);
    }

    private <T> T run(
            final String name,
            final OutcomeObservationContext context,
            final CheckedSupplier<T> work,
            final Consumer<Throwable> onError) throws Throwable {
        final Observation observation = observation(name, context);
        observation.start();
        Throwable thrown = null;
        try (Observation.Scope ignored = observation.openScope()) {
            return work.get();
        } catch (final Throwable t) {
            thrown = t;
            throw t;
        } finally {
            if (thrown != null && onError != null) {
                try {
                    onError.accept(thrown);
                } catch (final Throwable tagFailure) {
                    // Telemetry must not mask the original business exception; presets stand.
                }
            }
            context.markSettled();
            if (thrown != null) {
                observation.error(thrown);
            }
            observation.stop();
        }
    }

    private <T> T runUnchecked(final String name, final OutcomeObservationContext context, final Supplier<T> work) {
        try {
            return run(name, context, work::get);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable t) {
            throw new IllegalStateException("supplier threw a checked throwable", t);
        }
    }

    private Observation observation(final String name, final OutcomeObservationContext context) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("observation name must not be blank");
        }
        return Observation.createNotStarted(name.strip(), () -> context, observationRegistry)
                .observationConvention(convention);
    }

    private OutcomeObservationContext context(final KeyValues dimensions) {
        return new OutcomeObservationContext(Objects.requireNonNull(dimensions, "dimensions must not be null"));
    }
}
