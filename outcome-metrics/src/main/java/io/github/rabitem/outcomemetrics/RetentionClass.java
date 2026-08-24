package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValue;

/**
 * Closed retention-class vocabulary for routing telemetry to pipelines with different TTLs.
 *
 * <p>Micrometer's {@code CompositeMeterRegistry} with per-child {@link RetentionFilters} does the
 * actual routing; this enum only closes the vocabulary so the routing predicate is not a per-team
 * string convention. Untagged observations are {@link #OPS}-class by default — nothing becomes
 * audit-class by accident.
 *
 * <p>{@code retention=audit} means <em>long-retention investigation telemetry</em>: it supports the
 * humans who follow up. It is a routing hint, never a legal guarantee — the WORM audit trail is a
 * different system (see the break-glass docs), and dashboards must never be offered to an auditor
 * as evidence.
 *
 * @since 0.1.0
 */
public enum RetentionClass {

    /** Operational telemetry: standard short TTL. The default for untagged observations. */
    OPS("ops"),

    /** Long-retention investigation telemetry. Must be declared explicitly. */
    AUDIT("audit");

    /** Retention tag name. */
    public static final String TAG_RETENTION = "retention";

    private final String tagValue;

    RetentionClass(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code retention} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }

    /**
     * Returns this class as a dimension tag.
     *
     * @return {@code retention} key value, never {@code null}
     */
    public KeyValue tag() {
        return KeyValue.of(TAG_RETENTION, tagValue);
    }
}
