package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Break-glass lifecycle vocabulary and review-lag timer — operational signal, not a WORM audit.
 *
 * <p>Emergency overrides are neither normal auth success nor RBAC denial. Each lifecycle event is a
 * normal observation tagged with a closed {@link Stage}; the mandatory review's lag is recorded via
 * {@link #recordReviewLag} with a closed verdict when the application's store closes the review —
 * reviews happen days later, by other people, through other systems, so this library holds no
 * lifecycle state (same rule as #23/#32/#33).
 *
 * <p>An activation is a <em>success that must alert</em>: alert on the
 * {@code break_glass="activation"} rate itself — that is exactly the signal 401/403 auth metrics
 * cannot carry. Active break-glass counts are gauges ({@code LatestValueGauges}); overdue reviews
 * are found by a sweeper (reconcile pattern, #23) and route via {@link BreakGlassReason}.
 *
 * <p>Never tag patient or user identifiers — resource classes, causes (caller-owned bounded
 * vocabulary), and verdicts only; the {@link TagPrivacyPolicy} redacts identity-shaped values by
 * design. <b>Operational metrics are not a legal audit trail</b>: the WORM audit is a different
 * system with different guarantees, and dashboards must never be offered to an auditor as
 * evidence.
 *
 * @since 0.1.0
 */
public final class BreakGlass {

    /** Break-glass stage tag name. */
    public static final String TAG_BREAK_GLASS = "break_glass";

    /** Review verdict tag name. */
    public static final String TAG_VERDICT = "verdict";

    private BreakGlass() {
    }

    /**
     * Records a closed mandatory review on a timer.
     *
     * <p>Emission-time helper: never throws. Negative lags (clock skew between store timestamps)
     * clamp to zero and still record; {@code null} inputs record nothing.
     *
     * @param meterRegistry registry to record on
     * @param name          timer name, fully caller-owned (convention: {@code <flow>.review_lag})
     * @param dimensions    low-cardinality dimensions (resource class, cause); sanitized
     * @param lag           duration between activation and review, from the store
     * @param verdict       the review's verdict
     */
    public static void recordReviewLag(
            final MeterRegistry meterRegistry,
            final String name,
            final KeyValues dimensions,
            final Duration lag,
            final ReviewVerdict verdict) {
        SuppliedDurationTimers.record(meterRegistry, name, dimensions, lag,
                TAG_VERDICT, verdict == null ? null : verdict.tagValue());
    }

    /**
     * Closed break-glass lifecycle stage vocabulary for the {@code break_glass} dimension.
     *
     * <p>If one observation name spans stages, every recording of that name must carry the tag
     * (label sets stay consistent per meter name).
     *
     * @since 0.1.0
     */
    public enum Stage {

        /** The emergency override was activated. Alert on this stage's rate. */
        ACTIVATION("activation"),

        /** An access happened under the open override. */
        ACCESS("access"),

        /** The mandatory review was performed. */
        REVIEW("review"),

        /** The override was closed. */
        CLOSURE("closure");

        private final String tagValue;

        Stage(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the stable {@code break_glass} tag value.
         *
         * @return lower-case tag value, never {@code null} or blank
         */
        public String tagValue() {
            return tagValue;
        }

        /**
         * Returns this stage as a dimension tag.
         *
         * @return {@code break_glass} key value, never {@code null}
         */
        public KeyValue tag() {
            return KeyValue.of(TAG_BREAK_GLASS, tagValue);
        }
    }

    /**
     * Closed vocabulary for the mandatory review's verdict.
     *
     * @since 0.1.0
     */
    public enum ReviewVerdict {

        /** The override was justified. */
        JUSTIFIED("justified"),

        /** The override was not justified; follow-up outside metrics. */
        UNJUSTIFIED("unjustified"),

        /** The review could not reach a verdict. */
        INCONCLUSIVE("inconclusive");

        private final String tagValue;

        ReviewVerdict(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the stable {@code verdict} tag value.
         *
         * @return lower-case tag value, never {@code null} or blank
         */
        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * Break-glass failure reasons with routing pinned.
     *
     * @since 0.1.0
     */
    public enum BreakGlassReason implements OutcomeReason {

        /**
         * The mandatory review window expired unreviewed — a process failure, not an outage.
         * Tickets; stricter shops define their own reasons.
         */
        REVIEW_OVERDUE("review_overdue", Alertability.TICKET);

        private final String code;
        private final Alertability alertability;

        BreakGlassReason(final String code, final Alertability alertability) {
            this.code = code;
            this.alertability = alertability;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public Alertability alertability() {
            return alertability;
        }
    }
}
