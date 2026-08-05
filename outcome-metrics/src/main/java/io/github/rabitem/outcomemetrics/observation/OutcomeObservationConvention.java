package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import org.jspecify.annotations.NonNull;

/**
 * Maps an {@link OutcomeObservationContext} to low-cardinality metric and span tags.
 *
 * <p>Every observation gets {@code outcome} and {@code reason}. Classified successes may carry
 * additional result tags but never omit the outcome schema.
 *
 * @since 0.1.0
 */
public final class OutcomeObservationConvention implements ObservationConvention<OutcomeObservationContext> {

    /** Shared stateless convention instance. */
    public static final OutcomeObservationConvention INSTANCE = new OutcomeObservationConvention();

    /** Outcome tag name. */
    public static final String TAG_OUTCOME = "outcome";

    /** Reason tag name. */
    public static final String TAG_REASON = "reason";

    /** Success outcome tag value. */
    public static final String OUTCOME_SUCCESS = "success";

    /** Failure outcome tag value. */
    public static final String OUTCOME_FAILURE = "failure";

    private OutcomeObservationConvention() {
    }

    @Override
    public @NonNull KeyValues getLowCardinalityKeyValues(final @NonNull OutcomeObservationContext context) {
        final KeyValues base = context.dimensions().and(context.resultTags());
        final Throwable error = context.getError();
        if (error != null) {
            return base.and(TAG_OUTCOME, OUTCOME_FAILURE).and(TAG_REASON, MetricTagValues.reasonCode(error));
        }
        return base.and(TAG_OUTCOME, OUTCOME_SUCCESS).and(TAG_REASON, MetricTagValues.NONE);
    }

    @Override
    public boolean supportsContext(final Observation.@NonNull Context context) {
        return context instanceof OutcomeObservationContext;
    }
}
