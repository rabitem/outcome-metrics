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

    /** Shared unenforced convention instance. */
    public static final OutcomeObservationConvention INSTANCE = new OutcomeObservationConvention(null, null);

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
    private final ReasonRegistry reasonRegistry;

    private OutcomeObservationConvention(final ReasonBudget reasonBudget, final ReasonRegistry reasonRegistry) {
        this.reasonBudget = reasonBudget;
        this.reasonRegistry = reasonRegistry;
    }

    /**
     * Creates a convention whose failure reason codes are admitted through a {@link ReasonBudget}.
     *
     * @param reasonBudget budget admitting reason codes per observation name; must not be {@code null}
     * @return a budgeted convention
     */
    public static OutcomeObservationConvention withReasonBudget(final ReasonBudget reasonBudget) {
        return builder().reasonBudget(reasonBudget).build();
    }

    /**
     * Creates a convention builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder composing reason enforcement.
     *
     * <p>Resolution order for failure reasons: registry membership first (unregistered reasons emit
     * {@code reason=unknown} and {@code alertability=page}, consuming no budget), then budget
     * admission (registered codes over budget emit {@code reason=other} with their declared
     * alertability preserved).
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private ReasonBudget reasonBudget;
        private ReasonRegistry reasonRegistry;

        private Builder() {
        }

        /**
         * Sets the reason cardinality budget.
         *
         * @param budget budget; must not be {@code null}
         * @return this builder
         */
        public Builder reasonBudget(final ReasonBudget budget) {
            this.reasonBudget = Objects.requireNonNull(budget, "reasonBudget must not be null");
            return this;
        }

        /**
         * Sets the reason vocabulary registry.
         *
         * @param registry registry; must not be {@code null}
         * @return this builder
         */
        public Builder reasonRegistry(final ReasonRegistry registry) {
            this.reasonRegistry = Objects.requireNonNull(registry, "reasonRegistry must not be null");
            return this;
        }

        /**
         * Builds the convention.
         *
         * @return a convention with the configured enforcement
         */
        public OutcomeObservationConvention build() {
            return new OutcomeObservationConvention(reasonBudget, reasonRegistry);
        }
    }

    @Override
    public @NonNull KeyValues getLowCardinalityKeyValues(final @NonNull OutcomeObservationContext context) {
        final KeyValues base = context.dimensions().and(context.resultTags());
        final Throwable error = context.getError();
        final KeyValues tags;
        if (error != null) {
            resolveFailure(context, error);
            tags = base.and(TAG_OUTCOME, OUTCOME_FAILURE)
                    .and(TAG_REASON, context.cachedReason())
                    .and(TAG_INTEGRITY, MetricTagValues.NONE)
                    .and(TAG_ALERTABILITY, context.cachedAlertability());
        } else {
            tags = base.and(TAG_OUTCOME, OUTCOME_SUCCESS)
                    .and(TAG_REASON, MetricTagValues.NONE)
                    .and(TAG_INTEGRITY, context.integrity().tagValue())
                    .and(TAG_ALERTABILITY, MetricTagValues.NONE);
        }
        return tags.and(TAG_OCCURRENCE, occurrence(context, tags));
    }

    /**
     * Resolves reason and alertability in a single step so the two tags can never disagree.
     *
     * <p>An unregistered reason forfeits both its code and its routing downgrade: trusting the
     * alertability of an object whose code is distrusted would let a rogue reason silence its own
     * page. Budget suppression, by contrast, distrusts only cardinality: {@code reason=other} keeps
     * the declared alertability.
     */
    private void resolveFailure(final OutcomeObservationContext context, final Throwable error) {
        if (context.cachedReason() != null) {
            return;
        }
        final OutcomeReason reason = MetricTagValues.outcomeReason(error);
        String code = reason == null
                ? MetricTagValues.UNKNOWN
                : MetricTagValues.sanitizeTagValue(reason.code());
        Alertability alertability = reason == null ? Alertability.PAGE : reason.alertability();
        if (alertability == null) {
            alertability = Alertability.PAGE;
        }
        if (reasonRegistry != null && !reasonRegistry.isRegistered(code)) {
            reasonRegistry.recordRejection();
            code = MetricTagValues.UNKNOWN;
            alertability = Alertability.PAGE;
        }
        if (reasonBudget != null) {
            code = reasonBudget.admit(context.getName(), code);
        }
        context.cacheReason(code);
        context.cacheAlertability(alertability.tagValue());
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
