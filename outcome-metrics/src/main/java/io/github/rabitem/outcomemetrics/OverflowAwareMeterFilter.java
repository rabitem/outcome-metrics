package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.config.MeterFilter;

/**
 * A {@link MeterFilter} that tracks how often tag values were remapped due to cardinality limits.
 *
 * @since 0.1.0
 */
public interface OverflowAwareMeterFilter extends MeterFilter {

    /**
     * Returns how many tag values were remapped to the overflow bucket.
     *
     * @return non-negative overflow count
     */
    long overflowCount();
}
