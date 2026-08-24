package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReplayDelta")
class ReplayDeltaTest {

    @Test
    @DisplayName("voids the verdict comparison on context drift before any other axis")
    void contextDriftVoids() {
        assertThat(ReplayDelta.classify(true, true, true, true))
                .isEqualTo(ReplayDelta.CONTEXT_DRIFT);
        assertThat(ReplayDelta.classify(true, false, false, false))
                .isEqualTo(ReplayDelta.CONTEXT_DRIFT);
    }

    @Test
    @DisplayName("ranks changed axes by severity under a reproduced context")
    void precedence() {
        assertThat(ReplayDelta.classify(false, true, true, true))
                .isEqualTo(ReplayDelta.VERDICT_FLIP);
        assertThat(ReplayDelta.classify(false, false, true, true))
                .isEqualTo(ReplayDelta.LATENCY_CLASS_SHIFT);
        assertThat(ReplayDelta.classify(false, false, false, true))
                .isEqualTo(ReplayDelta.COST_CLASS_SHIFT);
        assertThat(ReplayDelta.classify(false, false, false, false))
                .isEqualTo(ReplayDelta.STABLE);
    }

    @Test
    @DisplayName("records replay runs as observations with the delta dimension")
    void replayAsObservation() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        observations.record("eval.replay",
                KeyValues.of(ReplayDelta.classify(false, true, false, false).tag())
                        .and("policy_version", "v12"),
                () -> {
                });

        assertThat(meters.get("eval.replay")
                .tag("delta", "verdict_flip")
                .tag("policy_version", "v12")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }
}
