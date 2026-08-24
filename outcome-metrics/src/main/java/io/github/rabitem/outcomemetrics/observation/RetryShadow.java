package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MessagingTags;
import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValues;

import java.time.Duration;

/**
 * Summary of the retries hidden inside one resilient call.
 *
 * <p>Resilient callers succeed on attempt N while attempts 1..N−1 burned latency, quota, and
 * capacity — dashboards show one green outcome. No ledger is needed: the retry loop is in-process
 * and the resilience wrapper already holds the stats; it supplies this summary when the call
 * completes ({@code OutcomeObservations.recordResilient}).
 *
 * <p>Three closed tags ride the existing observation series (never parallel meters):
 * {@code attempt_bucket} (shared vocabulary with message deliveries, #28), {@code dominant_reason}
 * (typed as {@link OutcomeReason} — closed by construction, since a registry cannot reach a
 * dimension), and {@code shadow_cost=none|minor|dominant} (no shadow time; less than half the
 * total burned on failed attempts; half or more).
 *
 * <p>Complementary to {@link OutcomeScope}: {@code occurrence} deduplicates repeated observations,
 * retry shadow summarizes attempts inside one observation.
 *
 * @since 0.1.0
 */
public final class RetryShadow {

    /** Dominant shadow failure reason tag name. */
    public static final String TAG_DOMINANT_REASON = "dominant_reason";

    /** Shadow cost bucket tag name. */
    public static final String TAG_SHADOW_COST = "shadow_cost";

    private static final RetryShadow FIRST_TRY = new RetryShadow(KeyValues.of(
            MessagingTags.TAG_ATTEMPT_BUCKET, MessagingTags.attemptBucket(1),
            TAG_DOMINANT_REASON, MetricTagValues.NONE,
            TAG_SHADOW_COST, MetricTagValues.NONE));

    private static final KeyValues UNKNOWN_TAGS = KeyValues.of(
            MessagingTags.TAG_ATTEMPT_BUCKET, MetricTagValues.UNKNOWN,
            TAG_DOMINANT_REASON, MetricTagValues.UNKNOWN,
            TAG_SHADOW_COST, MetricTagValues.UNKNOWN);

    private final KeyValues tags;

    private RetryShadow(final KeyValues tags) {
        this.tags = tags;
    }

    /**
     * Returns the summary for a call that succeeded on the first attempt.
     *
     * @return shared first-try summary ({@code attempt_bucket=1}, no dominant reason, no shadow cost)
     */
    public static RetryShadow firstTry() {
        return FIRST_TRY;
    }

    /**
     * Creates a summary of a retried call.
     *
     * @param attempts       1-based total attempts; below 1 emits {@code attempt_bucket=unknown}
     * @param dominantReason most frequent failure reason among the shadow attempts; {@code null}
     *                       emits {@code unknown} (use {@link #firstTry()} when there were none)
     * @param shadowTime     time burned on failed attempts; {@code null} emits {@code unknown}
     * @param totalTime      total call time; {@code null} or non-positive emits {@code unknown}
     * @return an immutable summary
     */
    public static RetryShadow of(
            final int attempts,
            final OutcomeReason dominantReason,
            final Duration shadowTime,
            final Duration totalTime) {
        final String reason = dominantReason == null || dominantReason.code() == null
                ? MetricTagValues.UNKNOWN
                : MetricTagValues.sanitizeTagValue(dominantReason.code());
        return new RetryShadow(KeyValues.of(
                MessagingTags.TAG_ATTEMPT_BUCKET, MessagingTags.attemptBucket(attempts),
                TAG_DOMINANT_REASON, reason,
                TAG_SHADOW_COST, shadowCost(shadowTime, totalTime)));
    }

    /**
     * Returns the three shadow tags.
     *
     * @return tags, never {@code null}
     */
    public KeyValues tags() {
        return tags;
    }

    /**
     * Returns the preset used before a summary is available.
     */
    static KeyValues unknownTags() {
        return UNKNOWN_TAGS;
    }

    private static String shadowCost(final Duration shadowTime, final Duration totalTime) {
        if (shadowTime == null || totalTime == null || totalTime.isZero() || totalTime.isNegative()) {
            return MetricTagValues.UNKNOWN;
        }
        if (shadowTime.isZero() || shadowTime.isNegative()) {
            return MetricTagValues.NONE;
        }
        return shadowTime.multipliedBy(2).compareTo(totalTime) < 0 ? "minor" : "dominant";
    }
}
