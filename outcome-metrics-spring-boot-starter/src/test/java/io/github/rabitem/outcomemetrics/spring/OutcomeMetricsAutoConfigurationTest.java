package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutcomeMetricsAutoConfiguration")
class OutcomeMetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutcomeMetricsAutoConfiguration.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create);

    @Test
    @DisplayName("registers outcome helper, meter filters, and annotation aspect by default")
    void defaultBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OutcomeObservations.class);
            assertThat(context).hasSingleBean(MeasuredOutcomeAspect.class);
            assertThat(context).hasBean("cacheMeterTagNormalizer");
            assertThat(context).hasBean("meterCardinalityCeiling");
            assertThat(context).doesNotHaveBean("meterTagValueLimiter");
            assertThat(context.getBeansOfType(MeterFilter.class)).hasSize(3);
        });
    }

    @Test
    @DisplayName("backs off for custom outcome helper and disabled optional beans")
    void backoffAndProperties() {
        contextRunner
                .withBean(OutcomeObservations.class, () -> new OutcomeObservations(ObservationRegistry.NOOP))
                .withPropertyValues(
                        "outcome.metrics.cache.normalize-tags=false",
                        "outcome.metrics.annotation.enabled=false",
                        "outcome.metrics.max-meters=42")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutcomeObservations.class);
                    assertThat(context).doesNotHaveBean(MeasuredOutcomeAspect.class);
                    assertThat(context).doesNotHaveBean("cacheMeterTagNormalizer");
                    assertThat(context).hasBean("meterCardinalityCeiling");
                    assertThat(context).doesNotHaveBean("meterTagValueLimiter");
                    assertThat(context.getBean(OutcomeMetricsProperties.class).getMaxMeters()).isEqualTo(42);
                });
    }

    @Test
    @DisplayName("disables the entire auto-configuration when outcome.metrics.enabled=false")
    void masterKillSwitch() {
        contextRunner
                .withPropertyValues("outcome.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutcomeObservations.class);
                    assertThat(context).doesNotHaveBean(MeasuredOutcomeAspect.class);
                    assertThat(context).doesNotHaveBean(MeterFilter.class);
                });
    }

    @Test
    @DisplayName("binds tag value limits and registers the limiter only when configured")
    void tagLimitProperties() {
        contextRunner
                .withPropertyValues(
                        "outcome.metrics.tag-limits[0].meter-name-prefix=websocket.",
                        "outcome.metrics.tag-limits[0].tag-key=destination",
                        "outcome.metrics.tag-limits[0].maximum-values=12")
                .run(context -> {
                    assertThat(context).hasBean("meterTagValueLimiter");
                    assertThat(context).hasBean("tagValueOverflowMeterBinder");
                    assertThat(context.getBean(OutcomeMetricsProperties.class).meterTagLimits())
                            .singleElement()
                            .satisfies(limit -> {
                                assertThat(limit.meterNamePrefix()).isEqualTo("websocket.");
                                assertThat(limit.tagKey()).isEqualTo("destination");
                                assertThat(limit.maximumValues()).isEqualTo(12);
                            });
                });
    }
}
