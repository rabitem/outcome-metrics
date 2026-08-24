package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DualControl")
class DualControlTest {

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("records witness gaps with closure tags and sanitized dimensions")
    void gapTimer() {
        DualControl.recordGap(meters, "payment.release.witness_gap",
                KeyValues.of("role", "Senior-Approver"), Duration.ofHours(2), DualControl.GapClosure.COMPLETED);
        DualControl.recordGap(meters, "payment.release.witness_gap",
                KeyValues.of("role", "auditor"), Duration.ofHours(72), DualControl.GapClosure.EXPIRED);
        DualControl.recordGap(meters, "payment.release.witness_gap",
                KeyValues.empty(), Duration.ofMinutes(5), DualControl.GapClosure.VETOED);

        assertThat(meters.get("payment.release.witness_gap")
                .tag("closure", "completed")
                .tag("role", "senior_approver")
                .timer().totalTime(TimeUnit.HOURS)).isEqualTo(2.0);
        assertThat(meters.get("payment.release.witness_gap")
                .tag("closure", "expired").timer().count()).isEqualTo(1);
        assertThat(meters.get("payment.release.witness_gap")
                .tag("closure", "vetoed").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("clamps negative gaps to zero and tolerates null inputs without throwing")
    void emissionSafety() {
        assertThatCode(() -> {
            DualControl.recordGap(meters, "gap.skew", KeyValues.empty(),
                    Duration.ofSeconds(-30), DualControl.GapClosure.COMPLETED);
            DualControl.recordGap(meters, "gap.none", KeyValues.empty(),
                    null, DualControl.GapClosure.COMPLETED);
            DualControl.recordGap(meters, "gap.none", KeyValues.empty(), Duration.ZERO, null);
            DualControl.recordGap(null, "gap.none", KeyValues.empty(), Duration.ZERO,
                    DualControl.GapClosure.COMPLETED);
            DualControl.recordGap(meters, " ", KeyValues.empty(), Duration.ZERO,
                    DualControl.GapClosure.COMPLETED);
        }).doesNotThrowAnyException();

        assertThat(meters.get("gap.skew").timer().count()).isEqualTo(1);
        assertThat(meters.get("gap.skew").timer().totalTime(TimeUnit.SECONDS)).isZero();
        assertThat(meters.find("gap.none").timer()).isNull();
    }

    @Test
    @DisplayName("tags witness actions on observations sharing one label set")
    void witnessActions() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        observations.record("payment.release",
                KeyValues.of(DualControl.WitnessAction.FIRST_APPROVAL.tag()).and("role", "approver"),
                () -> {
                });
        observations.record("payment.release",
                KeyValues.of(DualControl.WitnessAction.SECOND_APPROVAL.tag()).and("role", "senior_approver"),
                () -> {
                });

        assertThat(meters.get("payment.release")
                .tag("witness", "first_approval").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("payment.release")
                .tag("witness", "second_approval")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("routes dual-control reasons as tickets")
    void reasons() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        assertThatThrownBy(() -> observations.record("payment.release",
                KeyValues.of(DualControl.WitnessAction.VETO.tag()), () -> {
                    throw new DualControlException(DualControl.DualControlReason.VETO_AFTER_APPROVAL);
                })).isInstanceOf(DualControlException.class);

        assertThat(meters.get("payment.release")
                .tag("reason", "veto_after_approval")
                .tag("alertability", "ticket")
                .timer().count()).isEqualTo(1);
        assertThat(DualControl.DualControlReason.WITNESS_TIMEOUT.alertability())
                .isEqualTo(Alertability.TICKET);
    }

    private static final class DualControlException extends RuntimeException implements OutcomeReasonSource {

        private final transient OutcomeReason reason;

        private DualControlException(final OutcomeReason reason) {
            super(reason.code());
            this.reason = reason;
        }

        @Override
        public OutcomeReason outcomeReason() {
            return reason;
        }
    }
}
