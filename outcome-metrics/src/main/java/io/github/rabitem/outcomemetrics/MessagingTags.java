package io.github.rabitem.outcomemetrics;

import java.time.Duration;

/**
 * Closed, bounded bucket vocabularies for messaging dimensions.
 *
 * <p>Raw attempt counts and lag durations are unbounded; these helpers map them to closed buckets.
 * They run at emission time and never throw — out-of-contract inputs map to
 * {@link MetricTagValues#UNKNOWN} so mistakes are visible on the dashboard instead of silently
 * clamped.
 *
 * <p>Message ids, offsets, and partitions must never become tags. Partition is an int, so no guard
 * can mechanically distinguish it from a legitimate small number — this rule is documentation and
 * review, deliberately not a regex pretending to protect you.
 *
 * @since 0.1.0
 */
public final class MessagingTags {

    /** Attempt bucket tag name. */
    public static final String TAG_ATTEMPT_BUCKET = "attempt_bucket";

    /** Lag bucket tag name (outbox row age at publish, or consumer lag at delivery). */
    public static final String TAG_LAG_BUCKET = "lag_bucket";

    private MessagingTags() {
    }

    /**
     * Buckets a 1-based delivery attempt: {@code 1}, {@code 2_3}, {@code 4_plus}.
     *
     * @param attempt 1-based attempt number; values below 1 map to {@code unknown} (a 0-indexed
     *                caller's off-by-one stays visible)
     * @return closed bucket value, never {@code null}
     */
    public static String attemptBucket(final int attempt) {
        if (attempt < 1) {
            return MetricTagValues.UNKNOWN;
        }
        if (attempt == 1) {
            return "1";
        }
        return attempt <= 3 ? "2_3" : "4_plus";
    }

    /**
     * Buckets a lag duration with strict upper bounds:
     * {@code lt_1s}, {@code lt_10s}, {@code lt_1m}, {@code lt_10m}, {@code gte_10m}.
     *
     * @param lag lag at delivery or row age at publish; negative values (clock skew) clamp to
     *            {@code lt_1s}, {@code null} maps to {@code unknown}
     * @return closed bucket value, never {@code null}
     */
    public static String lagBucket(final Duration lag) {
        if (lag == null) {
            return MetricTagValues.UNKNOWN;
        }
        if (lag.compareTo(Duration.ofSeconds(1)) < 0) {
            return "lt_1s";
        }
        if (lag.compareTo(Duration.ofSeconds(10)) < 0) {
            return "lt_10s";
        }
        if (lag.compareTo(Duration.ofMinutes(1)) < 0) {
            return "lt_1m";
        }
        return lag.compareTo(Duration.ofMinutes(10)) < 0 ? "lt_10m" : "gte_10m";
    }
}
