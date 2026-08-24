package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Divergence-window timer for local state vs external rail state (payment rails, clearing,
 * adjudication).
 *
 * <p>Local ledger commit and rail confirmation diverge for hours; endpoint latency does not measure
 * the mismatch duration. Rail confirmations arrive via webhook, to any instance, across deploys —
 * so this library holds no window state (same rule as #23/#32): when the application's store closes
 * a window, {@link #recordWindow} records the store-computed duration with a closed
 * {@code resolution} tag.
 *
 * <p>{@code local_state}/{@code rail_state} pairs are caller-owned bounded dimensions — one library
 * enum cannot serve payment rails and insurer adjudication at once. Rail-specific failure reasons
 * are likewise the caller's {@link OutcomeReason} vocabulary. Active divergence counts are gauges
 * ({@code LatestValueGauges}). The webhook handler that closes a window pairs naturally with
 * {@code recordIdempotent} (#21): duplicate deliveries must not double-record the window.
 *
 * @since 0.1.0
 */
public final class RailDivergence {

    /** Resolution tag name. */
    public static final String TAG_RESOLUTION = "resolution";

    private RailDivergence() {
    }

    /**
     * Records a closed divergence window on a timer.
     *
     * <p>Emission-time helper: never throws. Negative durations (clock skew between stores) clamp
     * to zero and still record; {@code null} inputs record nothing.
     *
     * @param meterRegistry registry to record on
     * @param name          timer name, fully caller-owned (convention: {@code <flow>.rail_divergence})
     * @param dimensions    low-cardinality dimensions (bounded state pair etc.); sanitized
     * @param window        duration between local commit and rail resolution, from the store
     * @param resolution    how the window resolved
     */
    public static void recordWindow(
            final MeterRegistry meterRegistry,
            final String name,
            final KeyValues dimensions,
            final Duration window,
            final DivergenceResolution resolution) {
        SuppliedDurationTimers.record(meterRegistry, name, dimensions, window,
                TAG_RESOLUTION, resolution == null ? null : resolution.tagValue());
    }

    /**
     * Closed vocabulary for how a divergence window resolved.
     *
     * @since 0.1.0
     */
    public enum DivergenceResolution {

        /** The rail confirmed the local state; the ledgers agree. */
        CONVERGED("converged"),

        /** The rail returned or reversed the operation (e.g. ACH return, chargeback). */
        RETURNED("returned"),

        /** The rail resolved with different values than local (e.g. adjudication adjustment). */
        ADJUSTED("adjusted"),

        /** The divergence was closed administratively without rail agreement. */
        WRITTEN_OFF("written_off");

        private final String tagValue;

        DivergenceResolution(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the stable {@code resolution} tag value.
         *
         * @return lower-case tag value, never {@code null} or blank
         */
        public String tagValue() {
            return tagValue;
        }
    }
}
