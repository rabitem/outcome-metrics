package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.Alertability;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.rabitem.outcomemetrics.test.OutcomeMetricsAssertions.assertThatOutcomes;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomeMetricsAssertions")
class OutcomeMetricsAssertionsTest {

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
    @DisplayName("passes a clean registry through all assertions")
    void cleanRegistryPasses() {
        observations.record("order.place", KeyValues.of("channel", "web"), () -> {
        });
        assertThatThrownBy(() -> observations.record("order.place", KeyValues.of("channel", "pos"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> assertThatOutcomes(meters)
                .hasConsistentLabelSets()
                .hasOutcomeSchema("order.place")
                .hasSeriesCardinalityAtMost("order.place", 4)
                .hasNoPrivacyViolations(TagPrivacyPolicy.saasDefaults()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("detects inconsistent label sets under one meter name, naming both key sets")
    void inconsistentLabelSets() {
        Counter.builder("mixed.meter").tag("outcome", "success").register(meters).increment();
        Counter.builder("mixed.meter").tag("outcome", "success").tag("extra", "tag")
                .register(meters).increment();

        assertThatThrownBy(() -> assertThatOutcomes(meters).hasConsistentLabelSets())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("mixed.meter")
                .hasMessageContaining("inconsistent label sets")
                .hasMessageContaining("extra");
    }

    @Test
    @DisplayName("fails when the outcome schema is missing or the meter was never recorded")
    void schemaFailures() {
        Counter.builder("bare.counter").tag("outcome", "success").register(meters).increment();

        assertThatThrownBy(() -> assertThatOutcomes(meters).hasOutcomeSchema("bare.counter"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing schema tag");
        assertThatThrownBy(() -> assertThatOutcomes(meters).hasOutcomeSchema("never.recorded"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No meter named");
    }

    @Test
    @DisplayName("enforces series cardinality budgets")
    void cardinalityBudget() {
        for (int i = 0; i < 5; i++) {
            observations.record("budget.op", KeyValues.of("step", "s" + i), () -> {
            });
        }

        assertThatThrownBy(() -> assertThatOutcomes(meters).hasSeriesCardinalityAtMost("budget.op", 3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exceeding the budget of <3>");
    }

    @Test
    @DisplayName("catches PII that bypassed the runtime policy")
    void privacyViolations() {
        // registered directly, bypassing any runtime scrubbing
        Counter.builder("leaky.meter").tag("contact", "john@example.com").register(meters).increment();

        assertThatThrownBy(() -> assertThatOutcomes(meters)
                .hasNoPrivacyViolations(TagPrivacyPolicy.saasDefaults()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("leaky.meter")
                .hasMessageContaining("email-shaped");
    }

    @Test
    @DisplayName("verifies well-formed vocabularies and rejects malformed ones")
    void vocabularyContracts() {
        assertThatCode(() -> ReasonVocabularyContracts.assertWellFormed(GoodReasons.class))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReasonVocabularyContracts.assertWellFormed(UnstableCode.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("not sanitization-stable");
        assertThatThrownBy(() -> ReasonVocabularyContracts.assertWellFormed(DuplicateCodes.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicates reason code");
        assertThatThrownBy(() -> ReasonVocabularyContracts.assertWellFormed(NullAlertability.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("null alertability");
        assertThatThrownBy(() -> ReasonVocabularyContracts.assertWellFormed(BlankCode.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("blank reason code");
    }

    private enum GoodReasons implements OutcomeReason {
        DB_DOWN;

        @Override
        public String code() {
            return "db_down";
        }
    }

    private enum UnstableCode implements OutcomeReason {
        MIXED;

        @Override
        public String code() {
            return "Payment-Declined";
        }
    }

    private enum DuplicateCodes implements OutcomeReason {
        FIRST, SECOND;

        @Override
        public String code() {
            return "same_code";
        }
    }

    private enum NullAlertability implements OutcomeReason {
        BROKEN;

        @Override
        public String code() {
            return "broken";
        }

        @Override
        public Alertability alertability() {
            return null;
        }
    }

    private enum BlankCode implements OutcomeReason {
        EMPTY;

        @Override
        public String code() {
            return " ";
        }
    }
}
