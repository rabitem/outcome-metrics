package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricsTags;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;
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

    /**
     * Creates an outcome observation helper.
     *
     * @param observationRegistry registry to record against; must not be {@code null}
     */
    public OutcomeObservations(final ObservationRegistry observationRegistry) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
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
        newObservation(name, dimensions).observe(work);
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
        return newObservation(name, dimensions).observe(work);
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
        return newObservation(name, dimensions).observeChecked(work::get);
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
     */
    public <T> T record(
            final String name,
            final KeyValues dimensions,
            final Supplier<T> work,
            final Function<? super T, KeyValues> resultTagger) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(resultTagger, "resultTagger must not be null");
        final OutcomeObservationContext context = context(dimensions);
        context.markClassified();
        return observation(name, context).observe(() -> {
            final T result = work.get();
            context.setResultTags(MetricsTags.sanitize(resultTagger.apply(result)));
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
     */
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
        return observation(name, context).observe(() -> {
            final T result = work.get();
            context.setIntegrity(
                    Objects.requireNonNull(classifier.classify(result), "classifier must not return null"));
            context.setResultTags(MetricsTags.sanitize(resultTagger.apply(result)));
            return result;
        });
    }

    private Observation newObservation(final String name, final KeyValues dimensions) {
        return observation(name, context(dimensions));
    }

    private Observation observation(final String name, final OutcomeObservationContext context) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("observation name must not be blank");
        }
        return Observation.createNotStarted(name.strip(), () -> context, observationRegistry)
                .observationConvention(OutcomeObservationConvention.INSTANCE);
    }

    private OutcomeObservationContext context(final KeyValues dimensions) {
        return new OutcomeObservationContext(Objects.requireNonNull(dimensions, "dimensions must not be null"));
    }
}
