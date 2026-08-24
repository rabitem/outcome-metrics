package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Grounding")
class GroundingTest {

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
    @DisplayName("tags all grounding verdicts as classified successes with caller dimensions")
    void verdicts() {
        for (final GroundingFidelity fidelity : GroundingFidelity.values()) {
            outcomeObservations.recordGrounded(
                    "rag.answer",
                    KeyValues.of("retrieval_tier", "hybrid", "citation_mode", "inline"),
                    () -> fidelity,
                    verdict -> verdict);
        }

        for (final GroundingFidelity fidelity : GroundingFidelity.values()) {
            assertThat(meters.get("rag.answer")
                    .tag("outcome", "success")
                    .tag("grounding", fidelity.tagValue())
                    .tag("retrieval_tier", "hybrid")
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("keeps the grounding key on failures with value none")
    void failureKeepsKey() {
        assertThatThrownBy(() -> outcomeObservations.recordGrounded(
                "rag.answer", KeyValues.empty(), () -> {
                    throw new IllegalStateException("model timeout");
                }, result -> GroundingFidelity.ALIGNED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("rag.answer")
                .tag("outcome", "failure")
                .tag("grounding", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails loud on a misbehaving success-path classifier")
    void classifierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordGrounded(
                "rag.null", KeyValues.empty(), () -> "answer", result -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not return null");
        assertThatThrownBy(() -> outcomeObservations.recordGrounded(
                "rag.op", KeyValues.empty(), () -> "answer", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not be null");

        assertThat(meters.get("rag.null")
                .tag("outcome", "failure").tag("grounding", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("supports the async judge pattern as its own evaluation observation")
    void asyncJudgePattern() {
        outcomeObservations.record(
                "rag.judge",
                KeyValues.of(GroundingFidelity.TAG_GROUNDING,
                        GroundingFidelity.HALLUCINATED_GAP.tagValue()),
                () -> {
                });

        assertThat(meters.get("rag.judge")
                .tag("grounding", "hallucinated_gap")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }
}
