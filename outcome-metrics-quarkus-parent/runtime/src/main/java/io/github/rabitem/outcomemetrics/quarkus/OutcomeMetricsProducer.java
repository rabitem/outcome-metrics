package io.github.rabitem.outcomemetrics.quarkus;

import io.github.rabitem.outcomemetrics.MeterTagLimit;
import io.github.rabitem.outcomemetrics.MetricsMeterFilters;
import io.github.rabitem.outcomemetrics.OverflowAwareMeterFilter;
import io.github.rabitem.outcomemetrics.observation.CombinationGuard;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservationConvention;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.ReasonBudget;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
            final Instance<ReasonBudget> reasonBudgets,
            final MeterRegistry meterRegistry,
            final OutcomeMetricsConfig config) {
        final ObservationRegistry registry;
        if (observationRegistries.isResolvable()) {
            registry = observationRegistries.get();
        } else {
            registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        }
        return new OutcomeObservations(registry, convention(reasonBudgets, meterRegistry, config));
    }

    private static OutcomeObservationConvention convention(
            final Instance<ReasonBudget> reasonBudgets,
            final MeterRegistry meterRegistry,
            final OutcomeMetricsConfig config) {
        final ReasonBudget budget = reasonBudgets.isResolvable() ? reasonBudgets.get() : null;
        final ReasonRegistry reasons = config.reasonRegistry().codes()
                .filter(codes -> !codes.isEmpty())
                .map(codes -> ReasonRegistry.builder().codes(codes.toArray(String[]::new)).build())
                .orElse(null);
        final CombinationGuard guard = config.combinationGuard().keys()
                .filter(keys -> !keys.isEmpty())
                .map(keys -> {
                    final CombinationGuard.Builder guardBuilder = CombinationGuard.builder()
                            .keys(keys.toArray(String[]::new))
                            .minSupport(config.combinationGuard().minSupport())
                            .window(config.combinationGuard().window());
                    config.combinationGuard().namePrefixes()
                            .filter(prefixes -> !prefixes.isEmpty())
                            .ifPresent(prefixes -> guardBuilder.namePrefixes(prefixes.toArray(String[]::new)));
                    return guardBuilder.build();
                })
                .orElse(null);
        final TagPrivacyPolicy policy = privacyPolicy(config);
        if (budget == null && reasons == null && guard == null && policy == null) {
            return OutcomeObservationConvention.INSTANCE;
        }
        final OutcomeObservationConvention.Builder builder = OutcomeObservationConvention.builder();
        if (budget != null) {
            builder.reasonBudget(budget);
        }
        if (reasons != null) {
            builder.reasonRegistry(reasons);
            reasons.bindTo(meterRegistry);
        }
        if (guard != null) {
            builder.combinationGuard(guard);
            guard.bindTo(meterRegistry);
        }
        if (policy != null) {
            builder.tagPrivacyPolicy(policy);
            policy.bindTo(meterRegistry);
        }
        return builder.build();
    }

    private static TagPrivacyPolicy privacyPolicy(final OutcomeMetricsConfig config) {
        if (!config.privacy().enabled()) {
            return null;
        }
        final TagPrivacyPolicy.Builder builder = TagPrivacyPolicy.builder();
        if (config.privacy().saasDefaults()) {
            builder.denyKeys(TagPrivacyPolicy.saasDefaults().denyKeys().toArray(String[]::new));
        }
        config.privacy().denyKeys()
                .filter(keys -> !keys.isEmpty())
                .ifPresent(keys -> builder.denyKeys(keys.toArray(String[]::new)));
        return builder.build();
    }

    /**
     * Reason cardinality budget from configuration (#19); injectable so operators can wire their
     * own runtime expand/collapse toggle. Bound to the meter registry here.
     *
     * @param config        outcome metrics config
     * @param meterRegistry Micrometer registry
     * @return the reason budget
     */
    @Produces
    @Singleton
    @DefaultBean
    @LookupIfProperty(name = "outcome.metrics.reason-budget.enabled", stringValue = "true")
    public ReasonBudget outcomeReasonBudget(final OutcomeMetricsConfig config, final MeterRegistry meterRegistry) {
        final ReasonBudget budget = new ReasonBudget(
                config.reasonBudget().collapsedLimit(),
                config.reasonBudget().expandedLimit());
        budget.bindTo(meterRegistry);
        return budget;
    }

    /**
     * Drops the handler-added open-vocabulary {@code error} tag from outcome meters (issue #97).
     *
     * @return meter filter
     */
    @Produces
    @Singleton
    @LookupIfProperty.List({
            @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true),
            @LookupIfProperty(name = "outcome.metrics.drop-error-tag", stringValue = "true", lookupIfMissing = true)
    })
    public MeterFilter outcomeMetricsErrorTagFilter() {
        return MetricsMeterFilters.dropRedundantErrorTag();
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
     * <p>Overflow gauge registration is deferred to {@link #registerTagValueOverflowGauge} so the
     * filter can be applied while the {@link MeterRegistry} is still being constructed.
     *
     * @param config outcome metrics config
     * @return meter filter
     */
    @Produces
    @Singleton
    @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true)
    public OverflowAwareMeterFilter outcomeMetricsTagValueLimiter(final OutcomeMetricsConfig config) {
        final List<MeterTagLimit> limits = config.tagLimits().stream()
                .map(limit -> new MeterTagLimit(
                        limit.meterNamePrefix(),
                        limit.tagKey(),
                        limit.maximumValues()))
                .toList();
        return MetricsMeterFilters.boundedTagValues(limits);
    }

    /**
     * Registers overflow telemetry once the application {@link MeterRegistry} is available.
     *
     * @param event                   Quarkus startup event
     * @param tagValueLimiters        optional bounded tag-value filter
     * @param meterRegistry           Micrometer registry
     */
    void registerTagValueOverflowGauge(
            @Observes final StartupEvent event,
            final Instance<OverflowAwareMeterFilter> tagValueLimiters,
            final OutcomeMetricsConfig config,
            final MeterRegistry meterRegistry) {
        if (!tagValueLimiters.isResolvable() || config.tagLimits().isEmpty()) {
            return;
        }
        Gauge.builder(
                        "outcome.metrics.tag_value_overflows",
                        tagValueLimiters.get(),
                        OverflowAwareMeterFilter::overflowCount)
                .description("Count of tag values remapped to other by outcome-metrics cardinality limits")
                .register(meterRegistry);
    }
}
