package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("RailDivergence")
class RailDivergenceTest {

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("records divergence windows with all resolutions and caller-owned state dims")
    void resolutions() {
        for (final RailDivergence.DivergenceResolution resolution
                : RailDivergence.DivergenceResolution.values()) {
            RailDivergence.recordWindow(meters, "payment.rail_divergence",
                    KeyValues.of("local_state", "captured", "rail_state", "settled"),
                    Duration.ofHours(6), resolution);
        }

        for (final RailDivergence.DivergenceResolution resolution
                : RailDivergence.DivergenceResolution.values()) {
            assertThat(meters.get("payment.rail_divergence")
                    .tag("resolution", resolution.tagValue())
                    .tag("local_state", "captured")
                    .tag("rail_state", "settled")
                    .timer().totalTime(TimeUnit.HOURS)).isEqualTo(6.0);
        }
    }

    @Test
    @DisplayName("clamps clock skew and tolerates null inputs without throwing")
    void emissionSafety() {
        assertThatCode(() -> {
            RailDivergence.recordWindow(meters, "rail.skew", KeyValues.empty(),
                    Duration.ofMinutes(-10), RailDivergence.DivergenceResolution.CONVERGED);
            RailDivergence.recordWindow(meters, "rail.none", KeyValues.empty(), null,
                    RailDivergence.DivergenceResolution.CONVERGED);
            RailDivergence.recordWindow(meters, "rail.none", KeyValues.empty(), Duration.ZERO, null);
            RailDivergence.recordWindow(null, "rail.none", KeyValues.empty(), Duration.ZERO,
                    RailDivergence.DivergenceResolution.CONVERGED);
        }).doesNotThrowAnyException();

        assertThat(meters.get("rail.skew").timer().totalTime(TimeUnit.SECONDS)).isZero();
        assertThat(meters.find("rail.none").timer()).isNull();
    }
}
