package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomeObservations")
class OutcomeObservationsTest {

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
    @DisplayName("tags successful work with outcome success and reason none")
    void success() {
        final String result = outcomeObservations.record(
                "test.op",
                KeyValues.of("frame", "subscribe"),
                () -> "value");

        assertThat(result).isEqualTo("value");
        assertThat(meters.get("test.op")
                .tag("frame", "subscribe")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("tags reason-carrying failures with their reason code")
    void reasonedFailure() {
        assertThatThrownBy(() -> outcomeObservations.record(
                "test.op", KeyValues.of("frame", "subscribe"), () -> {
                    throw new TestReasonedException(TestReason.CROSS_TENANT);
                }))
                .isInstanceOf(TestReasonedException.class);

        assertThat(meters.get("test.op")
                .tag("outcome", "failure")
                .tag("reason", "cross_tenant")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("maps unclassified failures to reason unknown")
    void exceptionFailure() {
        assertThatThrownBy(() -> outcomeObservations.record(
                "test.op", KeyValues.empty(), () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("test.op")
                .tag("outcome", "failure")
                .tag("reason", "unknown")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records checked exceptions without wrapping them")
    void checkedException() {
        assertThatThrownBy(() -> outcomeObservations.recordChecked(
                "test.op", KeyValues.empty(), () -> {
                    throw new IOException("network");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("network");

        assertThat(meters.get("test.op")
                .tag("outcome", "failure")
                .tag("reason", "unknown")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records a checked supplier that returns a value as success")
    void recordCheckedSupplierSuccess() throws Throwable {
        final String result = outcomeObservations.recordChecked(
                "checked.supplier",
                KeyValues.of("frame", "subscribe"),
                (CheckedSupplier<String>) () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(meters.get("checked.supplier")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records a checked runnable that completes normally as success")
    void recordCheckedRunnableSuccess() throws Throwable {
        outcomeObservations.recordChecked(
                "checked.runnable",
                KeyValues.empty(),
                (CheckedRunnable) () -> {
                    // no-op: exercises the delegating supplier's return-null path
                });

        assertThat(meters.get("checked.runnable")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("sanitizes result tags and keeps outcome schema for classified success")
    void classifiedResult() {
        final String result = outcomeObservations.record(
                "test.op",
                KeyValues.empty(),
                () -> "RETRY",
                value -> KeyValues.of("result", value));

        assertThat(result).isEqualTo("RETRY");
        assertThat(meters.get("test.op")
                .tag("result", "retry")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records void work and treats null result tags as empty")
    void voidAndNullResultTags() {
        outcomeObservations.record("void.op", KeyValues.empty(), () -> {
        });
        final String result = outcomeObservations.record(
                "classified.op",
                KeyValues.empty(),
                () -> "OK",
                ignored -> null);

        assertThat(result).isEqualTo("OK");
        assertThat(meters.get("void.op").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("classified.op")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("tags unclassified success with integrity ok and failure with integrity none")
    void defaultIntegrity() {
        outcomeObservations.record("integrity.default", KeyValues.empty(), () -> {
        });
        assertThatThrownBy(() -> outcomeObservations.record(
                "integrity.failed", KeyValues.empty(), () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("integrity.default")
                .tag("outcome", "success")
                .tag("integrity", "ok")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("integrity.failed")
                .tag("outcome", "failure")
                .tag("integrity", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("tags classified success with the classifier's integrity")
    void classifiedIntegrity() {
        final String degraded = outcomeObservations.recordClassified(
                "integrity.classified",
                KeyValues.of("frame", "render"),
                () -> "half a pdf",
                result -> OutcomeIntegrity.DEGRADED);
        final String empty = outcomeObservations.recordClassified(
                "integrity.classified",
                KeyValues.of("frame", "render"),
                () -> "",
                result -> result.isEmpty() ? OutcomeIntegrity.EMPTY : OutcomeIntegrity.OK);

        assertThat(degraded).isEqualTo("half a pdf");
        assertThat(empty).isEmpty();
        assertThat(meters.get("integrity.classified")
                .tag("outcome", "success")
                .tag("reason", "none")
                .tag("integrity", "degraded")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("integrity.classified")
                .tag("outcome", "success")
                .tag("integrity", "empty")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("combines integrity classification with sanitized result tags")
    void classifiedIntegrityWithResultTags() {
        final String result = outcomeObservations.recordClassified(
                "integrity.tagged",
                KeyValues.empty(),
                () -> "PARTIAL",
                value -> OutcomeIntegrity.DEGRADED,
                value -> KeyValues.of("result", value));

        assertThat(result).isEqualTo("PARTIAL");
        assertThat(meters.get("integrity.tagged")
                .tag("outcome", "success")
                .tag("integrity", "degraded")
                .tag("result", "partial")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails the observation when the classifier returns null or throws")
    void integrityClassifierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordClassified(
                "integrity.null", KeyValues.empty(), () -> "value", result -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not return null");
        assertThatThrownBy(() -> outcomeObservations.recordClassified(
                "integrity.throws", KeyValues.empty(), () -> "value", result -> {
                    throw new IllegalStateException("classifier blew up");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("classifier blew up");

        assertThat(meters.get("integrity.null")
                .tag("outcome", "failure")
                .tag("integrity", "none")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("integrity.throws")
                .tag("outcome", "failure")
                .tag("integrity", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates integrity classification dependencies")
    void integrityValidation() {
        assertThatThrownBy(() -> outcomeObservations.recordClassified(
                "test.op", KeyValues.empty(), () -> "value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordClassified(
                "test.op", KeyValues.empty(), null, result -> OutcomeIntegrity.OK))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordClassified(
                "test.op", KeyValues.empty(), () -> "value", result -> OutcomeIntegrity.OK, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultTagger must not be null");
    }

    @Test
    @DisplayName("validates observation name and dependencies")
    void validation() {
        assertThatThrownBy(() -> outcomeObservations.record(" ", KeyValues.empty(), () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("observation name must not be blank");
        assertThatThrownBy(() -> outcomeObservations.record((String) null, KeyValues.empty(), () -> "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("observation name must not be blank");
        assertThatThrownBy(() -> outcomeObservations.record("test.op", null, () -> "value"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dimensions must not be null");
        assertThatThrownBy(() -> outcomeObservations.record("test.op", KeyValues.empty(), (Runnable) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
        assertThatThrownBy(() -> outcomeObservations.record("test.op", KeyValues.empty(), () -> "value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultTagger must not be null");
        assertThatThrownBy(() -> new OutcomeObservations(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("observationRegistry must not be null");
    }

    private enum TestReason implements OutcomeReason {
        CROSS_TENANT;

        @Override
        public String code() {
            return "cross_tenant";
        }
    }

    private static final class TestReasonedException extends RuntimeException implements OutcomeReasonSource {

        private final transient OutcomeReason reason;

        private TestReasonedException(final OutcomeReason reason) {
            super("test");
            this.reason = reason;
        }

        @Override
        public OutcomeReason outcomeReason() {
            return reason;
        }
    }
}
