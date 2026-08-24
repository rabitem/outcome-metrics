package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetryShadow")
class RetryShadowTest {

    private SimpleMeterRegistry meters;
    private OutcomeObservations outcomeObservations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        outcomeObservations = new OutcomeObservations(registry);
    }

    @Test
    @DisplayName("summarizes hidden retries on the successful final outcome")
    void shadowOnSuccess() {
        final AtomicInteger attempts = new AtomicInteger();
        final String result = outcomeObservations.recordResilient(
                "agent.call", KeyValues.of("tool", "search"),
                () -> {
                    // caller-owned retry loop
                    while (attempts.incrementAndGet() < 3) {
                        // attempts 1..2 failed internally
                    }
                    return "ok";
                },
                () -> RetryShadow.of(attempts.get(), () -> "rate_limited",
                        Duration.ofSeconds(8), Duration.ofSeconds(10)));

        assertThat(result).isEqualTo("ok");
        assertThat(meters.get("agent.call")
                .tag("outcome", "success")
                .tag("attempt_bucket", "2_3")
                .tag("dominant_reason", "rate_limited")
                .tag("shadow_cost", "dominant")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps the shadow on final failure - the case that needs it most")
    void shadowOnFailure() {
        assertThatThrownBy(() -> outcomeObservations.recordResilient(
                "agent.call", KeyValues.empty(),
                () -> {
                    throw new IllegalStateException("all attempts exhausted");
                },
                () -> RetryShadow.of(5, () -> "rate_limited",
                        Duration.ofSeconds(9), Duration.ofSeconds(10))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("agent.call")
                .tag("outcome", "failure")
                .tag("attempt_bucket", "4_plus")
                .tag("dominant_reason", "rate_limited")
                .tag("shadow_cost", "dominant")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("first-try successes share the label set with none values")
    void firstTry() {
        outcomeObservations.recordResilient(
                "agent.call", KeyValues.empty(), () -> "ok", RetryShadow::firstTry);

        assertThat(meters.get("agent.call")
                .tag("attempt_bucket", "1")
                .tag("dominant_reason", "none")
                .tag("shadow_cost", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("leaves unknown presets when the shadow supplier misbehaves, never masking the error")
    void supplierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordResilient(
                "agent.broken", KeyValues.empty(),
                () -> {
                    throw new IllegalStateException("original failure");
                },
                () -> {
                    throw new IllegalArgumentException("summary blew up");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("original failure");
        outcomeObservations.recordResilient("agent.null", KeyValues.empty(), () -> "ok", () -> null);

        assertThat(meters.get("agent.broken")
                .tag("attempt_bucket", "unknown")
                .tag("dominant_reason", "unknown")
                .tag("shadow_cost", "unknown")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("agent.null")
                .tag("outcome", "success")
                .tag("shadow_cost", "unknown")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("buckets shadow cost at none, minor, dominant, and unknown boundaries")
    void shadowCostBuckets() {
        assertThat(cost(Duration.ZERO, Duration.ofSeconds(10))).isEqualTo("none");
        assertThat(cost(Duration.ofSeconds(4), Duration.ofSeconds(10))).isEqualTo("minor");
        assertThat(cost(Duration.ofSeconds(5), Duration.ofSeconds(10))).isEqualTo("dominant");
        assertThat(cost(Duration.ofSeconds(-1), Duration.ofSeconds(10))).isEqualTo("none");
        assertThat(cost(null, Duration.ofSeconds(10))).isEqualTo("unknown");
        assertThat(cost(Duration.ofSeconds(1), null)).isEqualTo("unknown");
        assertThat(cost(Duration.ofSeconds(1), Duration.ZERO)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("validates dependencies")
    void validation() {
        assertThatThrownBy(() -> outcomeObservations.recordResilient(
                "agent.call", KeyValues.empty(), () -> "ok", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("shadow must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordResilient(
                "agent.call", KeyValues.empty(), null, RetryShadow::firstTry))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
    }

    private static String cost(final Duration shadow, final Duration total) {
        return RetryShadow.of(2, () -> "r", shadow, total).tags().stream()
                .filter(tag -> tag.getKey().equals("shadow_cost"))
                .findFirst().orElseThrow().getValue();
    }
}
