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

@DisplayName("Reconciliation")
class ReconciliationTest {

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
    @DisplayName("tags all four dispositions as classified successes under phase reconcile")
    void dispositions() {
        for (final ReconcileDisposition disposition : ReconcileDisposition.values()) {
            outcomeObservations.recordReconciliation(
                    "sync.reconcile", KeyValues.empty(), () -> disposition, result -> result);
        }

        for (final ReconcileDisposition disposition : ReconcileDisposition.values()) {
            assertThat(meters.get("sync.reconcile")
                    .tag("phase", "reconcile")
                    .tag("outcome", "success")
                    .tag("disposition", disposition.tagValue())
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("keeps phase and disposition keys on failure")
    void failureKeepsKeys() {
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.reconcile", KeyValues.empty(), () -> {
                    throw new IllegalStateException("server state unavailable");
                }, result -> ReconcileDisposition.CONFIRMED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("sync.reconcile")
                .tag("outcome", "failure")
                .tag("phase", "reconcile")
                .tag("disposition", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("overrides a caller-supplied phase with reconcile, emitting the tag exactly once")
    void phaseCollision() {
        outcomeObservations.recordReconciliation(
                "sync.collision",
                KeyValues.of(OutcomePhase.TAG_PHASE, OutcomePhase.INTENT.tagValue()),
                () -> "ok",
                result -> ReconcileDisposition.CONFIRMED);

        final var timer = meters.get("sync.collision").tag("phase", "reconcile").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags().stream()
                .filter(tag -> tag.getKey().equals(OutcomePhase.TAG_PHASE)))
                .hasSize(1);
    }

    @Test
    @DisplayName("records intent and commit phases via the manual phase tag")
    void manualPhases() {
        outcomeObservations.record(
                "sync.flow", KeyValues.of(OutcomePhase.INTENT.tag()), () -> {
                });
        outcomeObservations.record(
                "sync.flow", KeyValues.of(OutcomePhase.COMMIT.tag()), () -> {
                });

        assertThat(meters.get("sync.flow").tag("phase", "intent").timer().count()).isEqualTo(1);
        assertThat(meters.get("sync.flow").tag("phase", "commit").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails the observation when the classifier returns null or throws, keys intact")
    void classifierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.null", KeyValues.empty(), () -> "value", result -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not return null");
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.throws", KeyValues.empty(), () -> "value", result -> {
                    throw new IllegalStateException("classifier blew up");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("sync.null")
                .tag("outcome", "failure").tag("disposition", "none")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("sync.throws")
                .tag("outcome", "failure").tag("disposition", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates dependencies")
    void validation() {
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.reconcile", KeyValues.empty(), () -> "value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.reconcile", KeyValues.empty(), null, result -> ReconcileDisposition.CONFIRMED))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordReconciliation(
                "sync.reconcile", null, () -> "value", result -> ReconcileDisposition.CONFIRMED))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dimensions must not be null");
    }
}
