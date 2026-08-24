package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValue;

/**
 * Closed replay-delta vocabulary for flakiness detection (same fingerprint, different verdict).
 *
 * <p>Adopts the revised contract from the discussion on #38: a replay's read context is asserted
 * <em>before</em> the verdict comparison, so a context that could not be reproduced <em>voids</em>
 * the comparison ({@link #CONTEXT_DRIFT}) instead of accusing the system under test of a
 * {@link #VERDICT_FLIP}. The precedence lives in {@link #classify} so no harness reimplements it
 * subtly wrong.
 *
 * <p>A replay run is an operation: record it as a normal observation with {@link #tag()} as a
 * dimension — no parallel {@code outcome_replay_delta_total} counter. Input, policy, and
 * read-context <b>fingerprints never become tags</b>: hashes are pseudonymous, unbounded, and the
 * {@link TagPrivacyPolicy} long-hex detector redacts them by design; they belong in the harness's
 * capture store. {@code policy_version} may ride as a bounded caller dimension.
 *
 * @since 0.1.0
 */
public enum ReplayDelta {

    /** Same logical input, policy, and read context — same verdict, latency class, and cost class. */
    STABLE("stable"),

    /**
     * The resolved read context differed; the verdict comparison is void, not flipped. The harness
     * accuses itself before it accuses the system under test.
     */
    CONTEXT_DRIFT("context_drift"),

    /** Verdict changed under identical logical input, policy, and resolved read context. */
    VERDICT_FLIP("verdict_flip"),

    /** Verdict held; the latency class shifted. */
    LATENCY_CLASS_SHIFT("latency_class_shift"),

    /** Verdict and latency class held; the cost class shifted. */
    COST_CLASS_SHIFT("cost_class_shift");

    /** Replay delta tag name. */
    public static final String TAG_DELTA = "delta";

    private final String tagValue;

    ReplayDelta(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Encodes the comparison order: context drift voids everything, then the most severe changed
     * axis wins.
     *
     * @param contextDiffers whether the resolved read context differed from the capture
     * @param verdictFlipped whether the verdict changed
     * @param latencyShifted whether the latency class changed
     * @param costShifted    whether the cost class changed
     * @return the delta under the fixed precedence, never {@code null}
     */
    public static ReplayDelta classify(
            final boolean contextDiffers,
            final boolean verdictFlipped,
            final boolean latencyShifted,
            final boolean costShifted) {
        if (contextDiffers) {
            return CONTEXT_DRIFT;
        }
        if (verdictFlipped) {
            return VERDICT_FLIP;
        }
        if (latencyShifted) {
            return LATENCY_CLASS_SHIFT;
        }
        return costShifted ? COST_CLASS_SHIFT : STABLE;
    }

    /**
     * Returns the stable {@code delta} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }

    /**
     * Returns this delta as a dimension tag.
     *
     * @return {@code delta} key value, never {@code null}
     */
    public KeyValue tag() {
        return KeyValue.of(TAG_DELTA, tagValue);
    }
}
