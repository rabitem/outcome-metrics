package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof for #97: {@code DefaultMeterObservationHandler} stamps an open-vocabulary {@code error}
 * tag (exception simple class names) on every observation meter; the filter removes it from
 * outcome meters only.
 */
@DisplayName("Error tag filter")
class ErrorTagFilterTest {

    @Test
    @DisplayName("pins the leak: without the filter, exception class names become tag values")
    void leakExistsWithoutFilter() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final OutcomeObservations observations = observations(meters);

        assertThatThrownBy(() -> observations.record("leak.op", KeyValues.empty(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // the handler-added tag carries the exception's simple class name: open vocabulary
        assertThat(meters.get("leak.op")
                .tag("error", "IllegalStateException")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("drops error from outcome meters only, verified on a real Prometheus scrape")
    void dropsScopedToOutcomeMeters() {
        final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        meters.config().meterFilter(MetricsMeterFilters.dropRedundantErrorTag());
        final OutcomeObservations observations = observations(meters);

        observations.record("scoped.op", KeyValues.empty(), () -> {
        });
        assertThatThrownBy(() -> observations.record("scoped.op", KeyValues.empty(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        // a foreign meter that legitimately uses an error tag keeps it (no outcome tag)
        meters.counter("foreign.requests", "error", "SocketTimeoutException").increment();

        final String scrape = meters.scrape();
        assertThat(scrape).doesNotContain("error=\"IllegalStateException\"");
        assertThat(scrape).contains("reason=\"unknown\"");
        assertThat(scrape).contains("foreign_requests_total{error=\"SocketTimeoutException\"}");
        // both outcome series share one label set, neither with error
        assertThat(meters.get("scoped.op").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(meters.get("scoped.op").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    private static OutcomeObservations observations(final io.micrometer.core.instrument.MeterRegistry meters) {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new OutcomeObservations(registry);
    }
}
