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

@DisplayName("ReasonBudget")
class ReasonBudgetTest {

    private SimpleMeterRegistry meters;
    private ReasonBudget budget;
    private OutcomeObservations outcomeObservations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        budget = new ReasonBudget(1, 3);
        budget.bindTo(meters);
        outcomeObservations = new OutcomeObservations(registry, budget);
    }

    @Test
    @DisplayName("admits codes up to the collapsed limit and remaps the rest to other")
    void suppressesBeyondCollapsedLimit() {
        fail("budget.op", "alpha");
        fail("budget.op", "beta");

        assertThat(meters.get("budget.op").tag("reason", "alpha").timer().count()).isEqualTo(1);
        assertThat(meters.get("budget.op").tag("reason", "other").timer().count()).isEqualTo(1);
        assertThat(meters.get(ReasonBudget.SUPPRESSED_COUNTER_NAME).functionCounter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("expand admits a previously suppressed code on the very next event")
    void expandResurrectsSuppressedCode() {
        fail("budget.op", "alpha");
        fail("budget.op", "beta"); // suppressed at collapsed limit 1

        budget.expand();
        fail("budget.op", "beta");

        assertThat(meters.get("budget.op").tag("reason", "beta").timer().count()).isEqualTo(1);
        assertThat(meters.get(ReasonBudget.MODE_GAUGE_NAME).gauge().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("collapse keeps admitted codes reporting and suppresses only new ones")
    void collapseNeverEvicts() {
        budget.expand();
        fail("budget.op", "alpha");
        fail("budget.op", "beta");

        budget.collapse();
        fail("budget.op", "beta"); // admitted while expanded: stays fine-grained
        fail("budget.op", "gamma"); // new code above collapsed limit: suppressed

        assertThat(meters.get("budget.op").tag("reason", "beta").timer().count()).isEqualTo(2);
        assertThat(meters.get("budget.op").tag("reason", "other").timer().count()).isEqualTo(1);
        assertThat(meters.get(ReasonBudget.MODE_GAUGE_NAME).gauge().value()).isEqualTo(0);
    }

    @Test
    @DisplayName("schema-floor codes pass without consuming budget")
    void schemaFloorCodesAreFree() {
        assertThatThrownBy(() -> outcomeObservations.record("budget.op", KeyValues.empty(), () -> {
            throw new IllegalStateException("unclassified");
        })).isInstanceOf(IllegalStateException.class);
        fail("budget.op", "alpha"); // still admitted: unknown consumed nothing

        assertThat(meters.get("budget.op").tag("reason", "unknown").timer().count()).isEqualTo(1);
        assertThat(meters.get("budget.op").tag("reason", "alpha").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("budgets independently per observation name")
    void perNameIsolation() {
        fail("budget.first", "alpha");
        fail("budget.second", "beta");

        assertThat(meters.get("budget.first").tag("reason", "alpha").timer().count()).isEqualTo(1);
        assertThat(meters.get("budget.second").tag("reason", "beta").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("occurrence deduplication keys on the emitted reason value")
    void occurrenceUsesEmittedReason() {
        fail("budget.scope", "alpha"); // consumes the collapsed budget
        try (OutcomeScope scope = OutcomeScope.open()) {
            fail("budget.scope", "beta"); // suppressed -> other
            fail("budget.scope", "gamma"); // suppressed -> other, same emitted series
        }

        assertThat(meters.get("budget.scope")
                .tag("reason", "other")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("budget.scope")
                .tag("reason", "other")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates limits and null budget wiring")
    void validation() {
        assertThatThrownBy(() -> new ReasonBudget(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("collapsedLimit must be positive");
        assertThatThrownBy(() -> new ReasonBudget(2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expandedLimit must be at least collapsedLimit");
        assertThatThrownBy(() -> OutcomeObservationConvention.withReasonBudget(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reasonBudget must not be null");
    }

    private void fail(final String name, final String reasonCode) {
        assertThatThrownBy(() -> outcomeObservations.record(name, KeyValues.empty(), () -> {
            throw new ReasonedException(reasonCode);
        })).isInstanceOf(ReasonedException.class);
    }

    private static final class ReasonedException extends RuntimeException implements OutcomeReasonSource {

        private final String code;

        private ReasonedException(final String code) {
            super(code);
            this.code = code;
        }

        @Override
        public OutcomeReason outcomeReason() {
            return () -> code;
        }
    }
}
