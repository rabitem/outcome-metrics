package io.github.rabitem.outcomemetrics.spring;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutcomeMetricsProperties")
class OutcomeMetricsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutcomeMetricsAutoConfiguration.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create);

    @Test
    @DisplayName("binds cache and interception settings from the environment")
    void bindsSettings() {
        contextRunner
                .withPropertyValues(
                        "outcome.metrics.cache.normalize-tags=false",
                        "outcome.metrics.cache.removed-tags=foo,bar",
                        "outcome.metrics.annotation.enabled=false",
                        "outcome.metrics.max-meters=99")
                .run(context -> {
                    final OutcomeMetricsProperties properties = context.getBean(OutcomeMetricsProperties.class);
                    assertThat(properties.getMaxMeters()).isEqualTo(99);
                    assertThat(properties.getCache().isNormalizeTags()).isFalse();
                    assertThat(properties.getCache().getRemovedTags()).containsExactlyInAnyOrder("foo", "bar");
                    assertThat(properties.getAnnotation().isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("fails fast when tag-limit fields are blank or non-positive")
    void validatesTagLimits() {
        contextRunner
                .withPropertyValues(
                        "outcome.metrics.tag-limits[0].meter-name-prefix= ",
                        "outcome.metrics.tag-limits[0].tag-key=destination",
                        "outcome.metrics.tag-limits[0].maximum-values=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails fast when max-meters is not positive")
    void validatesMaxMeters() {
        contextRunner
                .withPropertyValues("outcome.metrics.max-meters=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
