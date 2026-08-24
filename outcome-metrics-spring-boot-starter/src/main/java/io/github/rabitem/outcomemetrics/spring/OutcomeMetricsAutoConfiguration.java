package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MetricsMeterFilters;
import io.github.rabitem.outcomemetrics.OverflowAwareMeterFilter;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Auto-configuration for outcome metrics support.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@ConditionalOnClass({ObservationRegistry.class, MeterFilter.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "outcome.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutcomeMetricsProperties.class)
public class OutcomeMetricsAutoConfiguration {

    /**
     * Creates the outcome observation helper.
     *
     * @param observationRegistry the Micrometer observation registry to record into; must not be {@code null}
     * @return the outcome observation helper, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    OutcomeObservations outcomeObservations(final ObservationRegistry observationRegistry) {
        return new OutcomeObservations(observationRegistry);
    }

    /**
     * Creates a cache meter tag normalizer.
     *
     * @param properties the bound metrics properties; must not be {@code null}
     * @return the cache tag normalizer, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(name = "cacheMeterTagNormalizer")
    @ConditionalOnProperty(prefix = "outcome.metrics.cache", name = "normalize-tags", havingValue = "true", matchIfMissing = true)
    MeterFilter cacheMeterTagNormalizer(final OutcomeMetricsProperties properties) {
        return MetricsMeterFilters.cacheTagNormalizer(properties.getCache().getRemovedTags());
    }

    /**
     * Creates a total-meter cardinality ceiling.
     *
     * @param properties the bound metrics properties; must not be {@code null}
     * @return the meter cardinality ceiling, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(name = "meterCardinalityCeiling")
    MeterFilter meterCardinalityCeiling(final OutcomeMetricsProperties properties) {
        return MetricsMeterFilters.maximumAllowableMetrics(properties.getMaxMeters());
    }

    /**
     * Creates a tag-value cardinality limiter when tag limits are configured.
     *
     * <p>Does not touch {@code MeterRegistry} here: registries apply {@link MeterFilter} beans while
     * they are still being created, so eager registry lookup caused a Boot circular dependency.
     *
     * @param properties the bound metrics properties; must not be {@code null}
     * @return the tag-value cardinality limiter, never {@code null}
     */
    @Bean(name = "meterTagValueLimiter")
    @ConditionalOnMissingBean(name = "meterTagValueLimiter")
    @Conditional(NonEmptyTagLimitsCondition.class)
    OverflowAwareMeterFilter meterTagValueLimiter(final OutcomeMetricsProperties properties) {
        return MetricsMeterFilters.boundedTagValues(properties.meterTagLimits());
    }

    /**
     * Binds overflow telemetry after meter registries exist.
     *
     * @param meterTagValueLimiter the bounded tag-value filter; must not be {@code null}
     * @return meter binder for {@code outcome.metrics.tag_value_overflows}, never {@code null}
     */
    @Bean
    @ConditionalOnBean(name = "meterTagValueLimiter")
    @ConditionalOnMissingBean(name = "tagValueOverflowMeterBinder")
    MeterBinder tagValueOverflowMeterBinder(
            @Qualifier("meterTagValueLimiter") final OverflowAwareMeterFilter meterTagValueLimiter) {
        return registry -> Gauge.builder(
                        "outcome.metrics.tag_value_overflows",
                        meterTagValueLimiter,
                        OverflowAwareMeterFilter::overflowCount)
                .description("Count of tag values remapped to other by outcome-metrics cardinality limits")
                .register(registry);
    }

    /**
     * Creates the {@link io.github.rabitem.outcomemetrics.MeasuredOutcome} interceptor.
     *
     * @param outcomeObservations the outcome observation helper; must not be {@code null}
     * @return the measured outcome aspect, never {@code null}
     */
    @Bean
    @ConditionalOnBean(OutcomeObservations.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "outcome.metrics.annotation", name = "enabled", havingValue = "true", matchIfMissing = true)
    MeasuredOutcomeAspect measuredOutcomeAspect(final OutcomeObservations outcomeObservations) {
        return new MeasuredOutcomeAspect(outcomeObservations);
    }

    /**
     * Registers the per-request {@code OutcomeScope} servlet filter (issues #18/#54).
     *
     * <p>Opt-in: enabling changes the {@code occurrence} split on existing series. Servlet
     * dispatch only — reactive stacks hop threads and fail open by design.
     *
     * @return the scope filter
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnProperty(prefix = "outcome.metrics.scope", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public OutcomeScopeFilter outcomeScopeFilter() {
        return new OutcomeScopeFilter();
    }
}
