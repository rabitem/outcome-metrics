package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;

import java.util.Objects;

/**
 * Observation context for an outcome-instrumented operation.
 *
 * <p>Carries the caller-supplied low-cardinality dimensions, such as {@code frame=subscribe} or
 * {@code target=admin}. The {@code outcome} and {@code reason} tags are derived by
 * {@link OutcomeObservationConvention} from the recorded error when the observation stops.
 *
 * <p>For work classified by its return value rather than by a thrown exception, the context is marked
 * {@link #isClassified() classified}; the caller's {@link #resultTags()} are then emitted on success.
 *
 * @since 0.1.0
 */
public class OutcomeObservationContext extends Observation.Context {

    private final KeyValues dimensions;
    private KeyValues resultTags = KeyValues.empty();
    private boolean classified;

    /**
     * Creates a context.
     *
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     */
    public OutcomeObservationContext(final KeyValues dimensions) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
    }

    /**
     * Returns the caller-supplied dimensions.
     *
     * @return dimensions, never {@code null}
     */
    public KeyValues dimensions() {
        return dimensions;
    }

    /**
     * Marks the observation as classified by its return value.
     */
    public void markClassified() {
        this.classified = true;
    }

    /**
     * Returns whether this context is classified by result tags.
     *
     * @return {@code true} when success is classified by {@link #resultTags()}
     */
    public boolean isClassified() {
        return classified;
    }

    /**
     * Sets the successful-result tags.
     *
     * @param resultTags result tags; {@code null} is treated as {@link KeyValues#empty()}
     */
    public void setResultTags(final KeyValues resultTags) {
        this.resultTags = resultTags == null ? KeyValues.empty() : resultTags;
    }

    /**
     * Returns the successful-result tags.
     *
     * @return result tags, never {@code null}
     */
    public KeyValues resultTags() {
        return resultTags;
    }
}
