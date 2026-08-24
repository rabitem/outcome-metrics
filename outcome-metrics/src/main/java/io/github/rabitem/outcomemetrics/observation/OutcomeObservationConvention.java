package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Maps an {@link OutcomeObservationContext} to low-cardinality metric and span tags.
 *
 * <p>Every observation gets {@code outcome}, {@code reason}, {@code integrity}, {@code alertability}
 * and {@code occurrence}. Failures carry the reason's declared {@link Alertability} (default
 * {@code page}; unclassified failures page); successes carry {@code alertability=none}. Classified successes may carry additional result tags but never omit the
 * outcome schema. Failures carry {@code integrity=none} because no business result was delivered;
 * successes carry the classified integrity, defaulting to {@code ok}. Within an open
 * {@link OutcomeScope} the first observation of a series is {@code occurrence=first} and identical
 * repeats {@code occurrence=repeat}; without a scope every observation is {@code first}.
 *
 * @since 0.1.0
 */
public final class OutcomeObservationConvention implements ObservationConvention<OutcomeObservationContext> {

    /** Shared unbudgeted convention instance. */
    public static final OutcomeObservationConvention INSTANCE = new OutcomeObservationConvention(null);

    /** Outcome tag name. */
    public static final String TAG_OUTCOME = "outcome";

    /** Reason tag name. */
    public static final String TAG_REASON = "reason";

    /** Integrity tag name. */
    public static final String TAG_INTEGRITY = "integrity";

    /** Alertability tag name. */
    public static final String TAG_ALERTABILITY = "alertability";

    /** Occurrence tag name. */
    public static final String TAG_OCCURRENCE = "occurrence";

    /** First-occurrence tag value: counts toward SLIs. */
    public static final String OCCURRENCE_FIRST = "first";

    /** Repeat-occurrence tag value: same series already observed within the open scope. */
    public static final String OCCURRENCE_REPEAT = "repeat";

    /** Success outcome tag value. */
    public static final String OUTCOME_SUCCESS = "success";

    /** Failure outcome tag value. */
    public static final String OUTCOME_FAILURE = "failure";

    private final ReasonBudget reasonBudget;

    private OutcomeObservationConvention(final ReasonBudget reasonBudget) {
        this.reasonBudget = reasonBudget;
    }

    /**
     * Creates a convention whose failure reason codes are admitted through a {@link ReasonBudget}.
     *
     * @param reasonBudget budget admitting reason codes per observation name; must not be {@code null}
     * @return a budgeted convention
     */
    public static OutcomeObservationConvention withReasonBudget(final ReasonBudget reasonBudget) {
        return new OutcomeObservationConvention(
                Objects.requireNonNull(reasonBudget, "reasonBudget must not be null"));
    }

    @Override
    public @NonNull KeyValues getLowCardinalityKeyValues(final @NonNull OutcomeObservationContext context) {
        final KeyValues base = context.dimensions().and(context.resultTags());
        final Throwable error = context.getError();
        final KeyValues tags;
        if (error != null) {
            tags = base.and(TAG_OUTCOME, OUTCOME_FAILURE)
                    .and(TAG_REASON, failureReason(context, error))
                    .and(TAG_INTEGRITY, MetricTagValues.NONE)
                    .and(TAG_ALERTABILITY, alertability(error));
        } else {
            tags = base.and(TAG_OUTCOME, OUTCOME_SUCCESS)
                    .and(TAG_REASON, MetricTagValues.NONE)
                    .and(TAG_INTEGRITY, context.integrity().tagValue())
                    .and(TAG_ALERTABILITY, MetricTagValues.NONE);
        }
        return tags.and(TAG_OCCURRENCE, occurrence(context, tags));
    }

    private static String alertability(final Throwable error) {
        // Derived from the reason object, not the emitted code: a ReasonBudget-suppressed
        // reason=other keeps its declared alertability, so suppressed actionable failures still page.
        final OutcomeReason reason = MetricTagValues.outcomeReason(error);
        if (reason == null) {
            return Alertability.PAGE.tagValue();
        }
        final Alertability alertability = reason.alertability();
        return alertability == null ? Alertability.PAGE.tagValue() : alertability.tagValue();
    }

    private String failureReason(final OutcomeObservationContext context, final Throwable error) {
        final String cached = context.cachedReason();
        if (cached != null) {
            return cached;
        }
        final String code = MetricTagValues.reasonCode(error);
        final String reason = reasonBudget == null ? code : reasonBudget.admit(context.getName(), code);
        context.cacheReason(reason);
        return reason;
    }

    private static String occurrence(final OutcomeObservationContext context, final KeyValues tags) {
        final String cached = context.cachedOccurrence();
        if (cached != null) {
            return cached;
        }
        final OutcomeScope scope = OutcomeScope.current();
        final String occurrence = scope == null || scope.markFirst(seriesKey(context.getName(), tags))
                ? OCCURRENCE_FIRST
                : OCCURRENCE_REPEAT;
        context.cacheOccurrence(occurrence);
        return occurrence;
    }

    private static String seriesKey(final String name, final KeyValues tags) {
        final StringBuilder key = new StringBuilder(name == null ? "" : name);
        for (final KeyValue tag : tags) {
            key.append('|').append(tag.getKey()).append('=').append(tag.getValue());
        }
        return key.toString();
    }

    @Override
    public boolean supportsContext(final Observation.@NonNull Context context) {
        return context instanceof OutcomeObservationContext;
    }
}
