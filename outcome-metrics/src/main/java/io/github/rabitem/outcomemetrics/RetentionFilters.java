package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Prebuilt meter filters routing by {@link RetentionClass} on a {@code CompositeMeterRegistry}'s
 * children.
 *
 * <p>This is deliberately the filter layer: routing is a static accept/deny predicate on a tag that
 * never changes for a given series — no value ever needs to promote, so the pre-filter id cache
 * concerns that rule out filters elsewhere in this library do not apply.
 *
 * <p>Recommended wiring: the ops child takes no routing filter (live alerting needs audit-class
 * series too, for its shorter TTL); the audit child takes {@link #auditOnly()}. Combine with
 * {@code MeterFilter.ignoreTags(...)} to strip per-pipeline detail and
 * {@link MetricsMeterFilters#boundedTagValues} for per-pipeline cardinality policies.
 *
 * <pre>{@code
 * CompositeMeterRegistry composite = new CompositeMeterRegistry();
 * composite.add(opsRegistry);                                  // 90d TTL; everything
 * auditRegistry.config().meterFilter(RetentionFilters.auditOnly());
 * composite.add(auditRegistry);                                // years TTL; explicit audit only
 * }</pre>
 *
 * @since 0.1.0
 */
public final class RetentionFilters {

    private RetentionFilters() {
    }

    /**
     * Accepts only meters explicitly tagged {@code retention=audit}.
     *
     * @return filter for the long-retention child registry
     */
    public static MeterFilter auditOnly() {
        return MeterFilter.denyUnless(RetentionFilters::isAudit);
    }

    /**
     * Denies meters tagged {@code retention=audit}, for shops that want a strict split instead of
     * the recommended everything-in-ops default.
     *
     * @return filter excluding audit-class meters
     */
    public static MeterFilter excludeAudit() {
        return MeterFilter.deny(RetentionFilters::isAudit);
    }

    private static boolean isAudit(final Meter.Id id) {
        return RetentionClass.AUDIT.tagValue().equals(id.getTag(RetentionClass.TAG_RETENTION));
    }
}
