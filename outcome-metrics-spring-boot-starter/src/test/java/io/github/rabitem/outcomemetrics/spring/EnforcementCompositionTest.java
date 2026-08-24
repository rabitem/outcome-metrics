package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.observation.CombinationGuard;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.ReasonBudget;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Enforcement composition")
class EnforcementCompositionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ObservationTestConfig.class)
            .withConfiguration(AutoConfigurations.of(OutcomeMetricsAutoConfiguration.class));

    @Test
    @DisplayName("composes nothing by default")
    void defaultsToUnenforced() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ReasonBudget.class);
            assertThat(context).doesNotHaveBean(ReasonRegistry.class);
            assertThat(context).doesNotHaveBean(CombinationGuard.class);
            assertThat(context).doesNotHaveBean(TagPrivacyPolicy.class);
        });
    }

    @Test
    @DisplayName("composes all four enforcement beans from properties and applies them end to end")
    void composesFromProperties() {
        runner.withPropertyValues(
                "outcome.metrics.reason-budget.enabled=true",
                "outcome.metrics.reason-budget.collapsed-limit=2",
                "outcome.metrics.reason-registry.codes[0]=db_down",
                "outcome.metrics.combination-guard.keys[0]=region",
                "outcome.metrics.combination-guard.min-support=5",
                "outcome.metrics.privacy.enabled=true",
                "outcome.metrics.privacy.deny-keys[0]=custom_secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReasonBudget.class);
                    assertThat(context).hasSingleBean(ReasonRegistry.class);
                    assertThat(context).hasSingleBean(CombinationGuard.class);
                    assertThat(context).hasSingleBean(TagPrivacyPolicy.class);
                    assertThat(context.getBean(ReasonRegistry.class).isRegistered("db_down")).isTrue();
                    assertThat(context.getBean(TagPrivacyPolicy.class).denyKeys())
                            .contains("custom_secret", "user_id");

                    // end to end: privacy redacts a denied key, guard collapses a rare tuple
                    final OutcomeObservations observations = context.getBean(OutcomeObservations.class);
                    final SimpleMeterRegistry meters = context.getBean(SimpleMeterRegistry.class);
                    observations.record("composed.op",
                            KeyValues.of("custom_secret", "42", "region", "eu_west"), () -> {
                            });
                    assertThat(meters.get("composed.op")
                            .tag("custom_secret", "redacted")
                            .tag("region", "other")
                            .tag("outcome", "success")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservationTestConfig {

        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry(final SimpleMeterRegistry meters) {
            final ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            return registry;
        }
    }
}
