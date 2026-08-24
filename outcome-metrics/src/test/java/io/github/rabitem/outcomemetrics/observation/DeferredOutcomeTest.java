package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeferredOutcome")
class DeferredOutcomeTest {

    private SimpleMeterRegistry meters;
    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    }

    @Test
    @DisplayName("settles success and failure with the full schema, from a later call")
    void terminalSettlement() {
        final OutcomeObservations observations = new OutcomeObservations(registry);
        observations.startDeferred("async.op", KeyValues.of("channel", "web")).succeed();
        observations.startDeferred("async.op", KeyValues.of("channel", "web"))
                .fail(new IllegalStateException("boom"));

        assertThat(meters.get("async.op")
                .tag("outcome", "success").tag("reason", "none")
                .tag("integrity", "ok").tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("async.op")
                .tag("outcome", "failure").tag("reason", "unknown").tag("alertability", "page")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records cancellation as reason cancelled with alertability none")
    void cancellation() {
        new OutcomeObservations(registry)
                .startDeferred("async.cancel", KeyValues.empty()).cancel();

        assertThat(meters.get("async.cancel")
                .tag("outcome", "failure")
                .tag("reason", "cancelled")
                .tag("alertability", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("first terminal wins; racing settlements are no-ops")
    void firstTerminalWins() {
        final OutcomeObservations observations = new OutcomeObservations(registry);
        final DeferredOutcome outcome = observations.startDeferred("async.race", KeyValues.empty());
        outcome.fail(new IllegalStateException("first"));
        outcome.succeed();
        outcome.cancel();
        outcome.fail(new IllegalStateException("late"));

        assertThat(meters.get("async.race").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(meters.find("async.race").tags("outcome", "success").timer()).isNull();
    }

    @Test
    @DisplayName("cancelled is schema floor: registries admit it and budgets never charge it")
    void cancelledIsSchemaFloor() {
        final ReasonRegistry reasonRegistry = ReasonRegistry.builder().codes("db_down").build();
        final ReasonBudget budget = new ReasonBudget(1, 1);
        final OutcomeObservations observations = new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder()
                        .reasonRegistry(reasonRegistry)
                        .reasonBudget(budget)
                        .build());

        observations.startDeferred("async.floor", KeyValues.empty()).cancel();

        // not remapped to unknown/page by the registry, not charged by the budget
        assertThat(meters.get("async.floor")
                .tag("reason", "cancelled")
                .tag("alertability", "none")
                .timer().count()).isEqualTo(1);
        assertThat(reasonRegistry.isRegistered("cancelled")).isTrue();
        assertThat(budget.admit("async.floor", "cancelled")).isEqualTo("cancelled");
    }
}
