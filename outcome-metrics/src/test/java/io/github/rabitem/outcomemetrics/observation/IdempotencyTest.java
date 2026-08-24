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

@DisplayName("Idempotency")
class IdempotencyTest {

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
    @DisplayName("tags applied and skipped duplicates as classified successes")
    void successDispositions() {
        outcomeObservations.recordIdempotent(
                "idem.op", KeyValues.empty(), () -> "created", result -> IdempotencyOutcome.APPLIED);
        outcomeObservations.recordIdempotent(
                "idem.op", KeyValues.empty(), () -> "noop", result -> IdempotencyOutcome.DUPLICATE_SKIPPED);

        assertThat(meters.get("idem.op")
                .tag("outcome", "success")
                .tag("idempotency", "applied")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("idem.op")
                .tag("outcome", "success")
                .tag("idempotency", "duplicate_skipped")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps the idempotency tag key on failures with value none")
    void failureKeepsTagKey() {
        assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                "idem.op", KeyValues.empty(), () -> {
                    throw new IdempotencyException(IdempotencyReason.CONFLICT);
                }, result -> IdempotencyOutcome.APPLIED))
                .isInstanceOf(IdempotencyException.class);

        assertThat(meters.get("idem.op")
                .tag("outcome", "failure")
                .tag("idempotency", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("routes idempotency failure reasons with their baked-in alertability")
    void failureReasons() {
        for (final IdempotencyReason reason : IdempotencyReason.values()) {
            assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                    "idem.reasons", KeyValues.empty(), () -> {
                        throw new IdempotencyException(reason, new IllegalStateException("dedup store"));
                    }, result -> IdempotencyOutcome.APPLIED))
                    .isInstanceOf(IdempotencyException.class);
        }

        assertThat(meters.get("idem.reasons")
                .tag("reason", "idempotency_conflict")
                .tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("idem.reasons")
                .tag("reason", "stale_replay")
                .tag("alertability", "ticket")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("idem.reasons")
                .tag("reason", "idempotency_key_missing")
                .tag("alertability", "ticket")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails the observation when the classifier returns null or throws, tag key intact")
    void classifierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                "idem.null", KeyValues.empty(), () -> "value", result -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not return null");
        assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                "idem.throws", KeyValues.empty(), () -> "value", result -> {
                    throw new IllegalStateException("classifier blew up");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("idem.null")
                .tag("outcome", "failure")
                .tag("idempotency", "none")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("idem.throws")
                .tag("outcome", "failure")
                .tag("idempotency", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates dependencies")
    void validation() {
        assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                "idem.op", KeyValues.empty(), () -> "value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordIdempotent(
                "idem.op", KeyValues.empty(), null, result -> IdempotencyOutcome.APPLIED))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
        assertThatThrownBy(() -> new IdempotencyException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason must not be null");
    }
}
