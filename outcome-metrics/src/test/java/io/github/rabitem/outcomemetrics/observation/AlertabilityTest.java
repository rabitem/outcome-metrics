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

@DisplayName("Alertability")
class AlertabilityTest {

    private SimpleMeterRegistry meters;
    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    }

    @Test
    @DisplayName("pages by default for declared reasons and downgrades only on explicit override")
    void ladderLevels() {
        final OutcomeObservations observations = new OutcomeObservations(registry);
        fail(observations, "alert.op", new ReasonedException("db_down", null));
        fail(observations, "alert.op", new ReasonedException("cert_expiring", Alertability.TICKET));
        fail(observations, "alert.op", new ReasonedException("payment_declined", Alertability.NONE));

        assertThat(meters.get("alert.op")
                .tag("reason", "db_down").tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("alert.op")
                .tag("reason", "cert_expiring").tag("alertability", "ticket")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("alert.op")
                .tag("reason", "payment_declined").tag("alertability", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("pages on unclassified failures and stays none on success")
    void failLoudDefaults() {
        final OutcomeObservations observations = new OutcomeObservations(registry);
        assertThatThrownBy(() -> observations.record("alert.unknown", KeyValues.empty(), () -> {
            throw new IllegalStateException("unclassified");
        })).isInstanceOf(IllegalStateException.class);
        observations.record("alert.success", KeyValues.empty(), () -> {
        });

        assertThat(meters.get("alert.unknown")
                .tag("reason", "unknown").tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("alert.success")
                .tag("outcome", "success").tag("alertability", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("treats a broken null alertability implementation as page")
    void nullAlertabilityPages() {
        final OutcomeObservations observations = new OutcomeObservations(registry);
        fail(observations, "alert.broken", new ReasonedException("broken_reason", null) {
            @Override
            public OutcomeReason outcomeReason() {
                return new OutcomeReason() {
                    @Override
                    public String code() {
                        return "broken_reason";
                    }

                    @Override
                    public Alertability alertability() {
                        return null;
                    }
                };
            }
        });

        assertThat(meters.get("alert.broken")
                .tag("reason", "broken_reason").tag("alertability", "page")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps the declared alertability when the reason budget suppresses the code")
    void budgetSuppressionKeepsAlertability() {
        final ReasonBudget budget = new ReasonBudget(1, 1);
        final OutcomeObservations observations = new OutcomeObservations(registry, budget);
        fail(observations, "alert.budget", new ReasonedException("admitted", Alertability.NONE));
        fail(observations, "alert.budget", new ReasonedException("suppressed_outage", null));

        assertThat(meters.get("alert.budget")
                .tag("reason", "other").tag("alertability", "page")
                .timer().count()).isEqualTo(1);
    }

    private void fail(final OutcomeObservations observations, final String name, final RuntimeException error) {
        assertThatThrownBy(() -> observations.record(name, KeyValues.empty(), () -> {
            throw error;
        })).isInstanceOf(RuntimeException.class);
    }

    private static class ReasonedException extends RuntimeException implements OutcomeReasonSource {

        private final String code;
        private final transient Alertability alertability;

        ReasonedException(final String code, final Alertability alertability) {
            super(code);
            this.code = code;
            this.alertability = alertability;
        }

        @Override
        public OutcomeReason outcomeReason() {
            if (alertability == null) {
                return () -> code;
            }
            return new OutcomeReason() {
                @Override
                public String code() {
                    return code;
                }

                @Override
                public Alertability alertability() {
                    return alertability;
                }
            };
        }
    }
}
