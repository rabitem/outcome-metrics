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

@DisplayName("ReasonRegistry")
class ReasonRegistryTest {

    private SimpleMeterRegistry meters;
    private ObservationRegistry registry;
    private ReasonRegistry reasonRegistry;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        reasonRegistry = ReasonRegistry.of(KnownReasons.class);
        reasonRegistry.bindTo(meters);
    }

    @Test
    @DisplayName("passes registered codes through and includes the schema floor")
    void registeredCodesPass() {
        assertThat(reasonRegistry.codes()).containsExactly(
                "cancelled", "db_down", "none", "other", "payment_declined", "unknown");

        final OutcomeObservations observations = observations();
        failWith(observations, "registry.op", KnownReasons.DB_DOWN);

        assertThat(meters.get("registry.op")
                .tag("reason", "db_down")
                .tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get(ReasonRegistry.REJECTED_COUNTER_NAME).functionCounter().count()).isZero();
    }

    @Test
    @DisplayName("distrusts unregistered reasons entirely: unknown code AND forced page on one series")
    void unregisteredReasonForfeitsAlertability() {
        final OutcomeObservations observations = observations();
        // rogue reason: unregistered free-text code that also tries to silence its own page
        failWith(observations, "registry.rogue", new OutcomeReason() {
            @Override
            public String code() {
                return "user_typed_this";
            }

            @Override
            public Alertability alertability() {
                return Alertability.NONE;
            }
        });

        assertThat(meters.get("registry.rogue")
                .tag("reason", "unknown")
                .tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get(ReasonRegistry.REJECTED_COUNTER_NAME).functionCounter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("sanitizes registered codes so registration form matches emitted form")
    void sanitizedRegistration() {
        final ReasonRegistry mixed = ReasonRegistry.builder().codes("Payment-Declined").build();
        assertThat(mixed.isRegistered("payment_declined")).isTrue();
    }

    @Test
    @DisplayName("composes with the budget: registered code over budget emits other, alertability kept")
    void composesWithBudget() {
        final ReasonBudget budget = new ReasonBudget(1, 1);
        final OutcomeObservations observations = new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder()
                        .reasonRegistry(reasonRegistry)
                        .reasonBudget(budget)
                        .build());
        failWith(observations, "registry.budget", KnownReasons.DB_DOWN); // consumes the budget
        failWith(observations, "registry.budget", KnownReasons.PAYMENT_DECLINED); // over budget

        assertThat(meters.get("registry.budget")
                .tag("reason", "other")
                .tag("alertability", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails fast on blank codes at registration time")
    void registrationValidation() {
        assertThatThrownBy(() -> ReasonRegistry.builder().codes(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> ReasonRegistry.builder().codes((String[]) null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reasonRegistry.codes().add("sneaky"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private OutcomeObservations observations() {
        return new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder().reasonRegistry(reasonRegistry).build());
    }

    private void failWith(final OutcomeObservations observations, final String name, final OutcomeReason reason) {
        assertThatThrownBy(() -> observations.record(name, KeyValues.empty(), () -> {
            throw new ReasonedException(reason);
        })).isInstanceOf(ReasonedException.class);
    }

    private enum KnownReasons implements OutcomeReason {
        DB_DOWN("db_down", Alertability.PAGE),
        PAYMENT_DECLINED("payment_declined", Alertability.NONE);

        private final String code;
        private final Alertability alertability;

        KnownReasons(final String code, final Alertability alertability) {
            this.code = code;
            this.alertability = alertability;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public Alertability alertability() {
            return alertability;
        }
    }

    private static final class ReasonedException extends RuntimeException implements OutcomeReasonSource {

        private final transient OutcomeReason reason;

        private ReasonedException(final OutcomeReason reason) {
            super(reason.code());
            this.reason = reason;
        }

        @Override
        public OutcomeReason outcomeReason() {
            return reason;
        }
    }
}
