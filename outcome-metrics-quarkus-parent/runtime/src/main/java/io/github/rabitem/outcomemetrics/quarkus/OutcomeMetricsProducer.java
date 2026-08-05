package io.github.rabitem.outcomemetrics.quarkus;

import io.github.rabitem.outcomemetrics.MeterTagLimit;
import io.github.rabitem.outcomemetrics.MetricsMeterFilters;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Set;

/**
 * CDI producers for outcome metrics beans and Micrometer filters.
 *
 * <p>MeterFilter beans are produced without qualifiers so Quarkus Micrometer can collect them.
 *
 * @since 0.1.0
 */
@ApplicationScoped
public class OutcomeMetricsProducer {

    /**
     * Creates the outcome observation helper.
     *
     * <p>Uses a CDI {@link ObservationRegistry} when Quarkus provides one; otherwise builds a
     * registry backed by the application {@link MeterRegistry}. Marked {@link DefaultBean} so apps
     * can supply their own {@link OutcomeObservations}.
     *
     * @param observationRegistries optional Quarkus observation registry
     * @param meterRegistry         Micrometer meter registry
     * @return outcome observations helper
     */
    @Produces
    @Singleton
    @DefaultBean
    @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true)
    public OutcomeObservations outcomeObservations(
            final Instance<ObservationRegistry> observationRegistries,
            final MeterRegistry meterRegistry) {
        if (observationRegistries.isResolvable()) {
            return new OutcomeObservations(observationRegistries.get());
        }
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        return new OutcomeObservations(registry);
    }

    /**
     * Meter cardinality ceiling.
     *
     * @param config outcome metrics config
     * @return meter filter
     */
    @Produces
    @Singleton
    @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true)
    public MeterFilter outcomeMetricsMeterCeiling(final OutcomeMetricsConfig config) {
        return MetricsMeterFilters.maximumAllowableMetrics(config.maxMeters());
    }

    /**
     * Cache tag normalizer when enabled.
     *
     * @param config outcome metrics config
     * @return meter filter
     */
    @Produces
    @Singleton
    @LookupIfProperty.List({
            @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true),
            @LookupIfProperty(name = "outcome.metrics.cache.normalize-tags", stringValue = "true", lookupIfMissing = true)
    })
    public MeterFilter outcomeMetricsCacheTagNormalizer(final OutcomeMetricsConfig config) {
        final Set<String> removed = config.cache().removedTags().orElse(Set.of("cache.manager", "name"));
        return MetricsMeterFilters.cacheTagNormalizer(removed);
    }

    /**
     * Bounded tag-value filter from configured limits.
     *
     * @param config outcome metrics config
     * @return meter filter
     */
    @Produces
    @Singleton
    @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true)
    public MeterFilter outcomeMetricsTagValueLimiter(final OutcomeMetricsConfig config) {
        final List<MeterTagLimit> limits = config.tagLimits().stream()
                .map(limit -> new MeterTagLimit(
                        limit.meterNamePrefix(),
                        limit.tagKey(),
                        limit.maximumValues()))
                .toList();
        return MetricsMeterFilters.boundedTagValues(limits);
    }
}
