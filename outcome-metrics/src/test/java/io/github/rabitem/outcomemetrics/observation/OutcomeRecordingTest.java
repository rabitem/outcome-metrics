package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomeRecording")
class OutcomeRecordingTest {

    private SimpleMeterRegistry meters;
    private OutcomeObservations observations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        observations = new OutcomeObservations(registry);
    }

    @Test
    @DisplayName("composes integrity and idempotency on one operation - impossible before #92")
    void integrityPlusIdempotency() {
        final String result = observations.of("payment.capture")
                .dims(KeyValues.of("channel", "webhook"))
                .integrity((String r) -> r.isEmpty() ? OutcomeIntegrity.EMPTY : OutcomeIntegrity.OK)
                .idempotency(r -> "noop".equals(r)
                        ? IdempotencyOutcome.DUPLICATE_SKIPPED : IdempotencyOutcome.APPLIED)
                .record(() -> "captured");
        assertThatThrownBy(() -> observations.of("payment.capture")
                .dims(KeyValues.of("channel", "webhook"))
                .integrity((String r) -> OutcomeIntegrity.OK)
                .idempotency(r -> IdempotencyOutcome.APPLIED)
                .record(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(result).isEqualTo("captured");
        // success: both classifications on one series
        assertThat(meters.get("payment.capture")
                .tag("outcome", "success")
                .tag("integrity", "ok")
                .tag("idempotency", "applied")
                .timer().count()).isEqualTo(1);
        // failure: same label set - integrity=none from the schema, idempotency=none from the preset
        assertThat(meters.get("payment.capture")
                .tag("outcome", "failure")
                .tag("integrity", "none")
                .tag("idempotency", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("composes reconciliation, grounding, and result tags with consistent label sets")
    void tripleComposition() {
        observations.of("sync.evaluate")
                .reconciliation((String r) -> ReconcileDisposition.CONFIRMED)
                .grounding(r -> GroundingFidelity.ALIGNED)
                .resultTags(r -> KeyValues.of("result", r), "result", "region")
                .record(() -> "MATCHED");
        assertThatThrownBy(() -> observations.of("sync.evaluate")
                .reconciliation((String r) -> ReconcileDisposition.CONFIRMED)
                .grounding(r -> GroundingFidelity.ALIGNED)
                .resultTags(r -> KeyValues.of("result", r), "result", "region")
                .record(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("sync.evaluate")
                .tag("phase", "reconcile")
                .tag("disposition", "confirmed")
                .tag("grounding", "aligned")
                .tag("result", "matched")
                .tag("region", "none")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("sync.evaluate")
                .tag("outcome", "failure")
                .tag("phase", "reconcile")
                .tag("disposition", "none")
                .tag("grounding", "none")
                .tag("result", "none")
                .tag("region", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects delivery plus resilient at build time - both own attempt_bucket")
    void deliveryResilientRejected() {
        assertThatThrownBy(() -> observations.of("order.consume")
                .delivery(2, error -> DeliveryFate.RETRY)
                .resilient(RetryShadow::firstTry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery and resilient cannot compose");
        assertThatThrownBy(() -> observations.of("order.consume")
                .resilient(RetryShadow::firstTry)
                .delivery(2, error -> DeliveryFate.RETRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attempt_bucket");
    }

    @Test
    @DisplayName("rejects duplicate declared keys and repeated integrity at build time")
    void duplicateDeclarations() {
        assertThatThrownBy(() -> observations.of("dup.op")
                .idempotency((Object r) -> IdempotencyOutcome.APPLIED)
                .resultTags(r -> KeyValues.empty(), "idempotency"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"idempotency\" is already declared");
        assertThatThrownBy(() -> observations.of("dup.op")
                .integrity((Object r) -> OutcomeIntegrity.OK)
                .integrity(r -> OutcomeIntegrity.OK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity is already classified");
    }

    @Test
    @DisplayName("rejects startDeferred once any classification is configured")
    void deferredRejectsClassifications() {
        assertThatThrownBy(() -> observations.of("async.op")
                .delivery(1, error -> DeliveryFate.RETRY)
                .startDeferred())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deferred outcomes cannot run classifiers");

        // the plain path still works
        observations.of("async.op").dims(KeyValues.of("channel", "web")).startDeferred().succeed();
        assertThat(meters.get("async.op")
                .tag("outcome", "success").tag("channel", "web")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("composes delivery with integrity and accumulates dimensions across calls")
    void deliveryPlusIntegrity() {
        observations.of("order.consume")
                .dims(KeyValues.of("priority_class", "bulk"))
                .delivery(4, error -> DeliveryFate.DEAD_LETTER)
                .<String>integrity(r -> OutcomeIntegrity.DEGRADED)
                .dims(KeyValues.of("channel", "queue"))
                .record(() -> "partial");

        assertThat(meters.get("order.consume")
                .tag("outcome", "success")
                .tag("attempt_bucket", "4_plus")
                .tag("fate", "processed")
                .tag("integrity", "degraded")
                .tag("priority_class", "bulk")
                .tag("channel", "queue")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("supports checked work through typed and untyped terminals")
    void checkedTerminals() {
        assertThatCode(() -> {
            final String value = observations.of("checked.typed")
                    .<String>integrity(r -> OutcomeIntegrity.OK)
                    .recordChecked(() -> "ok");
            assertThat(value).isEqualTo("ok");
            observations.of("checked.plain").recordChecked(() -> {
            });
        }).doesNotThrowAnyException();

        assertThat(meters.get("checked.typed").tag("integrity", "ok").timer().count()).isEqualTo(1);
        assertThat(meters.get("checked.plain").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps the resilient shadow contract when composed with other classifications")
    void resilientPlusGrounding() {
        assertThatThrownBy(() -> observations.of("agent.answer")
                .resilient(() -> RetryShadow.of(5, () -> "rate_limited",
                        Duration.ofSeconds(9), Duration.ofSeconds(10)))
                .<String>grounding(r -> GroundingFidelity.ALIGNED)
                .record(() -> {
                    throw new IllegalStateException("all attempts exhausted");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("all attempts exhausted");

        // failure: shadow applied, grounding stays at its none preset
        assertThat(meters.get("agent.answer")
                .tag("outcome", "failure")
                .tag("attempt_bucket", "4_plus")
                .tag("dominant_reason", "rate_limited")
                .tag("shadow_cost", "dominant")
                .tag("grounding", "none")
                .timer().count()).isEqualTo(1);
    }
}
