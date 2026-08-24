package io.github.rabitem.outcomemetrics.test;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Entry point for outcome-metrics test assertions.
 *
 * <p>Static-import {@link #assertThatOutcomes(MeterRegistry)} alongside AssertJ's own
 * {@code assertThat}:
 *
 * <pre>{@code
 * assertThatOutcomes(meterRegistry)
 *     .hasConsistentLabelSets()
 *     .hasOutcomeSchema("order.place")
 *     .hasSeriesCardinalityAtMost("order.place", 24)
 *     .hasNoPrivacyViolations(policy);
 * }</pre>
 *
 * @since 0.1.0
 */
public final class OutcomeMetricsAssertions {

    private OutcomeMetricsAssertions() {
    }

    /**
     * Starts assertions on the meters recorded in a registry.
     *
     * @param meterRegistry registry to assert on; must not be {@code null}
     * @return a registry assert
     */
    public static MeterRegistryOutcomeAssert assertThatOutcomes(final MeterRegistry meterRegistry) {
        return new MeterRegistryOutcomeAssert(meterRegistry);
    }
}
