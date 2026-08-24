package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricsTags;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Closed vocabularies and the witness-gap timer for maker-checker (four-eyes) flows.
 *
 * <p>A dual-control gap spans requests, actors, processes, and restarts — only the application's
 * workflow store knows a gap is open, so this library holds no lifecycle state. Each witness
 * <em>event</em> is recorded as a normal observation tagged with {@link WitnessAction}; "awaiting a
 * witness" is a state, not an outcome — publish the pending-gap count as a gauge
 * ({@code LatestValueGauges}); overdue gaps are found by a sweeper using the reconcile pattern
 * ({@code recordReconciliation}, #23).
 *
 * <p>When the app closes a gap, {@link #recordGap} records the store-computed duration on a plain
 * timer with a closed {@code closure} tag. Not named {@code disposition} — that key belongs to
 * reconciliation findings.
 *
 * <p>Never emit actor ids — and not their hashes either: a hashed actor id is still a pseudonymous
 * identifier, unbounded in cardinality and still personal data. Emit closed roles
 * ({@code approver}, {@code senior_approver}); the {@link TagPrivacyPolicy} long-hex detector
 * redacts identity hashes by design.
 *
 * @since 0.1.0
 */
public final class DualControl {

    /** Witness action tag name. */
    public static final String TAG_WITNESS = "witness";

    /** Gap closure tag name. */
    public static final String TAG_CLOSURE = "closure";

    private DualControl() {
    }

    /**
     * Records a closed witness gap on a timer.
     *
     * <p>Emission-time helper: never throws. A negative gap (clock skew between store timestamps)
     * clamps to zero and is still recorded; a {@code null} gap or closure records nothing.
     *
     * @param meterRegistry registry to record on
     * @param name          timer name, fully caller-owned (convention: {@code <flow>.witness_gap})
     * @param dimensions    low-cardinality dimensions; sanitized, {@code null} treated as empty
     * @param gap           duration between first witness and closure, from the workflow store
     * @param closure       how the gap closed
     */
    public static void recordGap(
            final MeterRegistry meterRegistry,
            final String name,
            final KeyValues dimensions,
            final Duration gap,
            final GapClosure closure) {
        if (meterRegistry == null || name == null || name.isBlank() || gap == null || closure == null) {
            return;
        }
        final Timer.Builder timer = Timer.builder(name.strip())
                .tag(TAG_CLOSURE, closure.tagValue());
        for (final KeyValue dimension : MetricsTags.sanitize(dimensions)) {
            timer.tag(dimension.getKey(), dimension.getValue());
        }
        timer.register(meterRegistry).record(gap.isNegative() ? Duration.ZERO : gap);
    }

    /**
     * Closed witness event vocabulary for the {@code witness} dimension.
     *
     * <p>If one observation name spans witness actions, every recording of that name must carry the
     * tag (label sets stay consistent per meter name).
     *
     * @since 0.1.0
     */
    public enum WitnessAction {

        /** The maker's request received its first approval. */
        FIRST_APPROVAL("first_approval"),

        /** The gap closed with a second approval. */
        SECOND_APPROVAL("second_approval"),

        /** A witness vetoed. */
        VETO("veto"),

        /** The gap expired without the required witnesses. */
        EXPIRY("expiry");

        private final String tagValue;

        WitnessAction(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the stable {@code witness} tag value.
         *
         * @return lower-case tag value, never {@code null} or blank
         */
        public String tagValue() {
            return tagValue;
        }

        /**
         * Returns this action as a dimension tag.
         *
         * @return {@code witness} key value, never {@code null}
         */
        public KeyValue tag() {
            return KeyValue.of(TAG_WITNESS, tagValue);
        }
    }

    /**
     * Closed vocabulary for how a witness gap closed.
     *
     * @since 0.1.0
     */
    public enum GapClosure {

        /** Second approval arrived; the action proceeded. */
        COMPLETED("completed"),

        /** A witness vetoed; the action did not proceed. */
        VETOED("vetoed"),

        /** The gap expired without the required witnesses. */
        EXPIRED("expired");

        private final String tagValue;

        GapClosure(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the stable {@code closure} tag value.
         *
         * @return lower-case tag value, never {@code null} or blank
         */
        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * Dual-control failure reasons with routing pinned.
     *
     * <p>Both are tickets on purpose: a veto after first approval is the control <em>working</em> —
     * someone caught something, review it, wake nobody — and a witness timeout is a process
     * failure, not an outage. Shops that disagree define their own reasons.
     *
     * @since 0.1.0
     */
    public enum DualControlReason implements OutcomeReason {

        /** A witness vetoed after a first approval already stood. */
        VETO_AFTER_APPROVAL("veto_after_approval", Alertability.TICKET),

        /** The witness gap expired with fewer witnesses than required. */
        WITNESS_TIMEOUT("witness_timeout", Alertability.TICKET);

        private final String code;
        private final Alertability alertability;

        DualControlReason(final String code, final Alertability alertability) {
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
