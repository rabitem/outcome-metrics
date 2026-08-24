package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomeScope")
class OutcomeScopeTest {

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
    @DisplayName("tags every observation occurrence first when no scope is open")
    void unscopedIsAlwaysFirst() {
        outcomeObservations.record("scope.none", KeyValues.empty(), () -> {
        });
        outcomeObservations.record("scope.none", KeyValues.empty(), () -> {
        });

        assertThat(meters.get("scope.none")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("tags repeats within a scope but still records them")
    void repeatsAreTaggedNotDropped() {
        try (OutcomeScope scope = OutcomeScope.open()) {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> outcomeObservations.record(
                        "scope.storm", KeyValues.of("target", "billing"), () -> {
                            throw new IllegalStateException("downstream timeout");
                        }))
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        assertThat(meters.get("scope.storm")
                .tag("outcome", "failure")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("scope.storm")
                .tag("outcome", "failure")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("coalesces successes symmetrically with failures")
    void successesCoalesceToo() {
        try (OutcomeScope scope = OutcomeScope.open()) {
            outcomeObservations.record("scope.success", KeyValues.empty(), () -> {
            });
            outcomeObservations.record("scope.success", KeyValues.empty(), () -> {
            });
        }

        assertThat(meters.get("scope.success")
                .tag("outcome", "success")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("scope.success")
                .tag("outcome", "success")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keys deduplication by full series identity, not just name and reason")
    void distinctSeriesStayFirst() {
        try (OutcomeScope scope = OutcomeScope.open()) {
            outcomeObservations.record("scope.slices", KeyValues.of("target", "admin"), () -> {
            });
            outcomeObservations.record("scope.slices", KeyValues.of("target", "user"), () -> {
            });
        }

        assertThat(meters.get("scope.slices")
                .tag("target", "admin")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("scope.slices")
                .tag("target", "user")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("nested scopes deduplicate independently and restore the outer scope")
    void nestedScopes() {
        try (OutcomeScope outer = OutcomeScope.open()) {
            outcomeObservations.record("scope.nested", KeyValues.empty(), () -> {
            });
            try (OutcomeScope inner = OutcomeScope.open()) {
                outcomeObservations.record("scope.nested", KeyValues.empty(), () -> {
                });
            }
            outcomeObservations.record("scope.nested", KeyValues.empty(), () -> {
            });
        }

        // outer first, inner first (fresh set), outer repeat after inner closed
        assertThat(meters.get("scope.nested")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(2);
        assertThat(meters.get("scope.nested")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("close is idempotent and tolerates out-of-order closes")
    void closeIsDefensive() {
        final OutcomeScope outer = OutcomeScope.open();
        final OutcomeScope inner = OutcomeScope.open();
        assertThatCode(() -> {
            outer.close(); // out of order: inner is still current, must be a no-op
            inner.close();
            inner.close(); // idempotent
            outer.close();
        }).doesNotThrowAnyException();
        assertThat(OutcomeScope.current()).isNull();
    }

    @Test
    @DisplayName("fails open to first once the per-scope series cap is exceeded")
    void capFailsOpen() {
        try (OutcomeScope scope = OutcomeScope.open()) {
            for (int i = 0; i < OutcomeScope.MAX_TRACKED_SERIES; i++) {
                assertThat(scope.markFirst("series-" + i)).isTrue();
            }
            assertThat(scope.markFirst("series-0")).as("tracked repeat still detected").isFalse();
            assertThat(scope.markFirst("series-overflow")).as("beyond cap fails open").isTrue();
            assertThat(scope.markFirst("series-overflow")).as("untracked, stays first").isTrue();
        }
    }

    @Test
    @DisplayName("caches the occurrence so repeated convention lookups cannot flip it")
    void conventionLookupIsMemoized() {
        try (OutcomeScope scope = OutcomeScope.open()) {
            final OutcomeObservationContext context = new OutcomeObservationContext(KeyValues.empty());
            context.setName("scope.memo");
            final var first = OutcomeObservationConvention.INSTANCE.getLowCardinalityKeyValues(context);
            final var second = OutcomeObservationConvention.INSTANCE.getLowCardinalityKeyValues(context);

            assertThat(first).isEqualTo(second);
            assertThat(first.stream()
                    .filter(tag -> tag.getKey().equals("occurrence"))
                    .map(tag -> tag.getValue()))
                    .containsExactly("first");
        }
    }
}
