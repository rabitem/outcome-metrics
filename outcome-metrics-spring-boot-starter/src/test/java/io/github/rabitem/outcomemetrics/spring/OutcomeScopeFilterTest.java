package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutcomeScopeFilter")
class OutcomeScopeFilterTest {

    @Test
    @DisplayName("coalesces repeat observations within one filtered request")
    void coalescesWithinRequest() throws Exception {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        new OutcomeScopeFilter().doFilter(null, null, (request, response) -> {
            observations.record("request.op", KeyValues.empty(), () -> {
            });
            observations.record("request.op", KeyValues.empty(), () -> {
            });
        });

        assertThat(meters.get("request.op").tag("occurrence", "first").timer().count()).isEqualTo(1);
        assertThat(meters.get("request.op").tag("occurrence", "repeat").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("registers only when explicitly enabled in a servlet web application")
    void conditionalRegistration() {
        final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ObservationAutoConfiguration.class, OutcomeMetricsAutoConfiguration.class));

        runner.run(context ->
                assertThat(context).doesNotHaveBean(OutcomeScopeFilter.class));
        runner.withPropertyValues("outcome.metrics.scope.enabled=true").run(context ->
                assertThat(context).hasSingleBean(OutcomeScopeFilter.class));
    }
}
