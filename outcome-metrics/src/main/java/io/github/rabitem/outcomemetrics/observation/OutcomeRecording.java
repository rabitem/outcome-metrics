package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MessagingTags;
import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.github.rabitem.outcomemetrics.MetricsTags;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Composable observation recording, built from {@link OutcomeObservations#of(String)}.
 *
 * <p>Replaces the specialized {@code record*} methods (issue #92): one fluent entry composes any
 * combination of classifications on one operation — something the method-per-feature surface could
 * never express:
 *
 * <pre>{@code
 * Payment payment = observations.of("payment.capture")
 *     .dims(KeyValues.of("channel", "webhook"))
 *     .integrity((Payment r) -> r.complete() ? OutcomeIntegrity.OK : OutcomeIntegrity.DEGRADED)
 *     .idempotency(r -> r.wasNoOp() ? IdempotencyOutcome.DUPLICATE_SKIPPED : IdempotencyOutcome.APPLIED)
 *     .record(() -> handler.process(delivery));
 * }</pre>
 *
 * <p>The first result-typed method ({@code integrity}, {@code idempotency}, {@code reconciliation},
 * {@code grounding}, {@code resultTags}) fixes the result type and returns a {@link Typed} builder
 * — give that first classifier an explicitly typed lambda parameter or a method reference so the
 * type infers. Result-type-independent features ({@code delivery}, {@code resilient}) and plain
 * terminals stay available untyped.
 *
 * <p>The established contracts hold for every combination: each feature's declared tag keys preset
 * before work runs, so label sets stay consistent per meter name on every path (issue #60);
 * success-path classifiers fail loud ({@code null} or throw fails the observation); the
 * failure-path and summary suppliers ({@code delivery} fate, {@code resilient} shadow) never throw
 * and never mask the original exception; {@code reconciliation} adds {@code phase=reconcile}
 * automatically.
 *
 * <p>Invalid combinations fail at build time, loudly: duplicate declared keys across features;
 * {@code delivery} together with {@code resilient} (both own {@code attempt_bucket}, and a
 * cross-process redelivery attempt is not an in-process retry count); {@link #startDeferred()} with
 * any classification configured (a deferred outcome has no result to classify — {@link Typed} does
 * not even expose it).
 *
 * @since 0.1.0
 */
public final class OutcomeRecording {

    private final Composition composition;

    OutcomeRecording(final OutcomeObservations owner, final String name) {
        this.composition = new Composition(owner, name);
    }

    /**
     * Adds low-cardinality dimension tags (accumulating).
     *
     * @param dimensions dimensions to add; must not be {@code null}
     * @return this recording
     */
    public OutcomeRecording dims(final KeyValues dimensions) {
        composition.addDims(dimensions);
        return this;
    }

    /**
     * Classifies the delivery fate of a message-handling operation (#28): adds the
     * {@code attempt_bucket} dimension, presets {@code fate=unknown}, emits {@code fate=processed}
     * on success and the classifier's verdict on failure (never masking the original exception).
     *
     * @param attempt    1-based delivery attempt; below 1 emits {@code attempt_bucket=unknown}
     * @param classifier maps a delivery failure to its fate; must not be {@code null}
     * @return this recording
     */
    public OutcomeRecording delivery(final int attempt, final DeliveryFateClassifier classifier) {
        composition.delivery(attempt, classifier);
        return this;
    }

    /**
     * Attaches a retry-shadow summary (#36), applied on success and failure; a misbehaving
     * supplier leaves the {@code unknown} presets and never masks the original exception.
     *
     * @param shadow supplies the retry summary at completion; must not be {@code null}
     * @return this recording
     */
    public OutcomeRecording resilient(final Supplier<RetryShadow> shadow) {
        composition.resilient(shadow);
        return this;
    }

    /**
     * Grades the successful result's integrity (#17), fixing the result type.
     *
     * @param <T>        result type
     * @param classifier integrity classifier; must not be {@code null}, fail-loud contract
     * @return the typed recording
     */
    public <T> Typed<T> integrity(final IntegrityClassifier<? super T> classifier) {
        return new Typed<T>(composition).integrity(classifier);
    }

    /**
     * Classifies the idempotency disposition of the successful result (#21), fixing the result
     * type.
     *
     * @param <T>        result type
     * @param classifier idempotency classifier; must not be {@code null}, fail-loud contract
     * @return the typed recording
     */
    public <T> Typed<T> idempotency(final IdempotencyClassifier<? super T> classifier) {
        return new Typed<T>(composition).idempotency(classifier);
    }

    /**
     * Classifies a reconciliation finding (#23), adding {@code phase=reconcile} and fixing the
     * result type.
     *
     * @param <T>        result type
     * @param classifier reconciliation classifier; must not be {@code null}, fail-loud contract
     * @return the typed recording
     */
    public <T> Typed<T> reconciliation(final ReconcileClassifier<? super T> classifier) {
        return new Typed<T>(composition).reconciliation(classifier);
    }

    /**
     * Classifies the grounding fidelity of a RAG result (#37), fixing the result type.
     *
     * @param <T>        result type
     * @param classifier grounding classifier; must not be {@code null}, fail-loud contract
     * @return the typed recording
     */
    public <T> Typed<T> grounding(final GroundingClassifier<? super T> classifier) {
        return new Typed<T>(composition).grounding(classifier);
    }

    /**
     * Adds custom result tags over a declared key schema (#60), fixing the result type.
     *
     * @param <T>          result type
     * @param resultTagger maps a successful result to bounded tags over the declared keys
     * @param declaredKeys the tagger's complete key schema; at least one, none blank
     * @return the typed recording
     */
    public <T> Typed<T> resultTags(
            final Function<? super T, KeyValues> resultTagger, final String... declaredKeys) {
        return new Typed<T>(composition).resultTags(resultTagger, declaredKeys);
    }

    /**
     * Records the work as an observation.
     *
     * @param work work to time and observe; must not be {@code null}
     */
    public void record(final Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        composition.execute(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Records the work as an observation.
     *
     * @param <T>  result type
     * @param work work to time and observe; must not be {@code null}
     * @return result from {@code work}
     */
    public <T> T record(final Supplier<T> work) {
        return composition.execute(work);
    }

    /**
     * Records checked work as an observation.
     *
     * @param <T>  result type
     * @param work work to time and observe; must not be {@code null}
     * @return result from {@code work}
     * @throws Throwable if {@code work} throws
     */
    public <T> T recordChecked(final CheckedSupplier<T> work) throws Throwable {
        return composition.executeChecked(work);
    }

    /**
     * Records checked work as an observation.
     *
     * @param work work to time and observe; must not be {@code null}
     * @throws Throwable if {@code work} throws
     */
    public void recordChecked(final CheckedRunnable work) throws Throwable {
        Objects.requireNonNull(work, "work must not be null");
        composition.executeChecked(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Starts a deferred outcome for asynchronous work (#39).
     *
     * @return a started deferred outcome
     * @throws IllegalStateException if any classification is configured — a deferred outcome has no
     * result to classify
     */
    public DeferredOutcome startDeferred() {
        return composition.startDeferred();
    }

    /**
     * The recording after its result type is fixed by the first result-typed classification.
     *
     * @param <T> result type
     * @since 0.1.0
     */
    public static final class Typed<T> {

        private final Composition composition;

        private Typed(final Composition composition) {
            this.composition = composition;
        }

        /**
         * Adds low-cardinality dimension tags (accumulating).
         *
         * @param dimensions dimensions to add; must not be {@code null}
         * @return this recording
         */
        public Typed<T> dims(final KeyValues dimensions) {
            composition.addDims(dimensions);
            return this;
        }

        /**
         * Classifies the delivery fate (#28); see {@link OutcomeRecording#delivery}.
         *
         * @param attempt    1-based delivery attempt
         * @param classifier delivery fate classifier; must not be {@code null}
         * @return this recording
         */
        public Typed<T> delivery(final int attempt, final DeliveryFateClassifier classifier) {
            composition.delivery(attempt, classifier);
            return this;
        }

        /**
         * Attaches a retry-shadow summary (#36); see {@link OutcomeRecording#resilient}.
         *
         * @param shadow supplies the retry summary at completion; must not be {@code null}
         * @return this recording
         */
        public Typed<T> resilient(final Supplier<RetryShadow> shadow) {
            composition.resilient(shadow);
            return this;
        }

        /**
         * Grades the successful result's integrity (#17).
         *
         * @param classifier integrity classifier; must not be {@code null}, fail-loud contract
         * @return this recording
         */
        public Typed<T> integrity(final IntegrityClassifier<? super T> classifier) {
            Objects.requireNonNull(classifier, "classifier must not be null");
            composition.integrity(result -> classifier.classify(cast(result)));
            return this;
        }

        /**
         * Classifies the idempotency disposition of the successful result (#21).
         *
         * @param classifier idempotency classifier; must not be {@code null}, fail-loud contract
         * @return this recording
         */
        public Typed<T> idempotency(final IdempotencyClassifier<? super T> classifier) {
            Objects.requireNonNull(classifier, "classifier must not be null");
            composition.declaredClassification(IdempotencyOutcome.TAG_IDEMPOTENCY,
                    result -> Objects.requireNonNull(
                            classifier.classify(cast(result)), "classifier must not return null").tagValue());
            return this;
        }

        /**
         * Classifies a reconciliation finding (#23), adding {@code phase=reconcile}.
         *
         * @param classifier reconciliation classifier; must not be {@code null}, fail-loud contract
         * @return this recording
         */
        public Typed<T> reconciliation(final ReconcileClassifier<? super T> classifier) {
            Objects.requireNonNull(classifier, "classifier must not be null");
            composition.reconcilePhase();
            composition.declaredClassification(ReconcileDisposition.TAG_DISPOSITION,
                    result -> Objects.requireNonNull(
                            classifier.classify(cast(result)), "classifier must not return null").tagValue());
            return this;
        }

        /**
         * Classifies the grounding fidelity of a RAG result (#37).
         *
         * @param classifier grounding classifier; must not be {@code null}, fail-loud contract
         * @return this recording
         */
        public Typed<T> grounding(final GroundingClassifier<? super T> classifier) {
            Objects.requireNonNull(classifier, "classifier must not be null");
            composition.declaredClassification(GroundingFidelity.TAG_GROUNDING,
                    result -> Objects.requireNonNull(
                            classifier.classify(cast(result)), "classifier must not return null").tagValue());
            return this;
        }

        /**
         * Adds custom result tags over a declared key schema (#60).
         *
         * @param resultTagger maps a successful result to bounded tags over the declared keys
         * @param declaredKeys the tagger's complete key schema; at least one, none blank
         * @return this recording
         */
        public Typed<T> resultTags(
                final Function<? super T, KeyValues> resultTagger, final String... declaredKeys) {
            Objects.requireNonNull(resultTagger, "resultTagger must not be null");
            composition.resultTags(result -> resultTagger.apply(cast(result)), declaredKeys);
            return this;
        }

        /**
         * Records the work as an observation.
         *
         * @param work work to time and observe; must not be {@code null}
         * @return result from {@code work}
         */
        public T record(final Supplier<? extends T> work) {
            return composition.execute(work);
        }

        /**
         * Records checked work as an observation.
         *
         * @param work work to time and observe; must not be {@code null}
         * @return result from {@code work}
         * @throws Throwable if {@code work} throws
         */
        public T recordChecked(final CheckedSupplier<? extends T> work) throws Throwable {
            return composition.executeChecked(work);
        }

        @SuppressWarnings("unchecked")
        private T cast(final Object result) {
            return (T) result;
        }
    }

    /**
     * Shared composition state; safe to hand between the untyped and typed views because the typed
     * view is only reachable after a classifier fixed the result type.
     */
    private static final class Composition {

        private final OutcomeObservations owner;
        private final String name;
        private final Map<String, String> presets = new LinkedHashMap<>();
        private final List<BiConsumer<Object, Map<String, String>>> successAppliers = new ArrayList<>();
        private KeyValues dims = KeyValues.empty();
        private Function<Object, OutcomeIntegrity> integrity;
        private DeliveryFateClassifier fate;
        private int attempt;
        private Supplier<RetryShadow> shadow;
        private boolean reconcile;

        private Composition(final OutcomeObservations owner, final String name) {
            this.owner = owner;
            this.name = name;
        }

        void addDims(final KeyValues dimensions) {
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            dims = dims.and(dimensions);
        }

        void integrity(final Function<Object, OutcomeIntegrity> classifier) {
            if (integrity != null) {
                throw new IllegalStateException("integrity is already classified on this recording");
            }
            integrity = classifier;
        }

        void reconcilePhase() {
            reconcile = true;
        }

        void delivery(final int deliveryAttempt, final DeliveryFateClassifier classifier) {
            Objects.requireNonNull(classifier, "classifier must not be null");
            if (shadow != null) {
                throw rejectDeliveryResilient();
            }
            declare(DeliveryFate.TAG_FATE, MetricTagValues.UNKNOWN);
            this.fate = classifier;
            this.attempt = deliveryAttempt;
        }

        void resilient(final Supplier<RetryShadow> shadowSupplier) {
            Objects.requireNonNull(shadowSupplier, "shadow must not be null");
            if (fate != null) {
                throw rejectDeliveryResilient();
            }
            for (final KeyValue preset : RetryShadow.unknownTags()) {
                declare(preset.getKey(), preset.getValue());
            }
            this.shadow = shadowSupplier;
        }

        private static IllegalStateException rejectDeliveryResilient() {
            return new IllegalStateException("delivery and resilient cannot compose: both own"
                    + " attempt_bucket, and a cross-process redelivery attempt is not an in-process"
                    + " retry count - pick the one that matches this operation");
        }

        void declaredClassification(final String tagKey, final Function<Object, String> successValue) {
            declare(tagKey, MetricTagValues.NONE);
            successAppliers.add((result, tags) -> tags.put(tagKey, successValue.apply(result)));
        }

        void resultTags(final Function<Object, KeyValues> tagger, final String[] declaredKeys) {
            Objects.requireNonNull(declaredKeys, "declaredResultTagKeys must not be null");
            if (declaredKeys.length == 0) {
                throw new IllegalArgumentException("declare at least one result tag key");
            }
            final List<String> stripped = new ArrayList<>(declaredKeys.length);
            for (final String key : declaredKeys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("declared result tag key must not be blank");
                }
                final String strippedKey = key.strip();
                declare(strippedKey, MetricTagValues.NONE);
                stripped.add(strippedKey);
            }
            successAppliers.add((result, tags) -> {
                for (final KeyValue emitted : MetricsTags.sanitize(tagger.apply(result))) {
                    if (!stripped.contains(emitted.getKey())) {
                        throw new IllegalStateException("result tagger emitted undeclared key \""
                                + emitted.getKey() + "\"; declared keys: " + stripped);
                    }
                    tags.put(emitted.getKey(), emitted.getValue());
                }
            });
        }

        private void declare(final String key, final String presetValue) {
            if (presets.containsKey(key)) {
                throw new IllegalStateException("result tag key \"" + key + "\" is already declared"
                        + " by another classification on this recording");
            }
            presets.put(key, presetValue);
        }

        <T> T execute(final Supplier<? extends T> work) {
            Objects.requireNonNull(work, "work must not be null");
            try {
                return executeChecked(work::get);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable t) {
                throw new IllegalStateException("supplier threw a checked throwable", t);
            }
        }

        <T> T executeChecked(final CheckedSupplier<? extends T> work) throws Throwable {
            Objects.requireNonNull(work, "work must not be null");
            final OutcomeObservationContext context = owner.context(effectiveDims());
            if (!presets.isEmpty() || integrity != null) {
                context.markClassified();
            }
            if (!presets.isEmpty()) {
                context.setResultTags(toKeyValues(presets));
            }
            return owner.run(name, context, () -> {
                final T result = work.get();
                if (integrity != null) {
                    context.setIntegrity(Objects.requireNonNull(
                            integrity.apply(result), "classifier must not return null"));
                }
                if (!presets.isEmpty()) {
                    final Map<String, String> tags = new LinkedHashMap<>(presets);
                    if (fate != null) {
                        tags.put(DeliveryFate.TAG_FATE, DeliveryFate.PROCESSED.tagValue());
                    }
                    applyShadow(tags);
                    for (final BiConsumer<Object, Map<String, String>> applier : successAppliers) {
                        applier.accept(result, tags);
                    }
                    context.setResultTags(toKeyValues(tags));
                }
                return result;
            }, fate == null && shadow == null ? null : error -> {
                final Map<String, String> tags = new LinkedHashMap<>(presets);
                applyShadow(tags);
                if (fate != null) {
                    final DeliveryFate verdict = fate.classify(error);
                    if (verdict != null) {
                        tags.put(DeliveryFate.TAG_FATE, verdict.tagValue());
                    }
                }
                context.setResultTags(toKeyValues(tags));
            });
        }

        DeferredOutcome startDeferred() {
            if (!presets.isEmpty() || integrity != null || fate != null || shadow != null) {
                throw new IllegalStateException("deferred outcomes cannot run classifiers: there is"
                        + " no result to classify at settle time");
            }
            return owner.startDeferred(name, dims);
        }

        private KeyValues effectiveDims() {
            KeyValues effective = dims;
            if (fate != null) {
                effective = effective.and(
                        MessagingTags.TAG_ATTEMPT_BUCKET, MessagingTags.attemptBucket(attempt));
            }
            if (reconcile) {
                effective = effective.and(OutcomePhase.RECONCILE.tag());
            }
            return effective;
        }

        private void applyShadow(final Map<String, String> tags) {
            if (shadow == null) {
                return;
            }
            try {
                final RetryShadow summary = shadow.get();
                if (summary != null) {
                    for (final KeyValue tag : summary.tags()) {
                        tags.put(tag.getKey(), tag.getValue());
                    }
                }
            } catch (final RuntimeException | Error ignored) {
                // Summarizing telemetry must not fail the observation or mask the original exception.
            }
        }

        private static KeyValues toKeyValues(final Map<String, String> tags) {
            final List<KeyValue> values = new ArrayList<>(tags.size());
            for (final Map.Entry<String, String> tag : tags.entrySet()) {
                values.add(KeyValue.of(tag.getKey(), tag.getValue()));
            }
            return KeyValues.of(values);
        }
    }
}
