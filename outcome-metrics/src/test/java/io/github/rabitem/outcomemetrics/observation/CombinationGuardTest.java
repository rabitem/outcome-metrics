package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CombinationGuard")
class CombinationGuardTest {

    private SimpleMeterRegistry meters;
    private ObservationRegistry registry;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        clock = new MutableClock(Instant.parse("2026-08-24T12:00:00Z"));
    }

    @Test
    @DisplayName("collapses rare combinations and reveals at the k-th event within a window")
    void revealAtThreshold() {
        final CombinationGuard guard = guard(3, Duration.ofMinutes(15));
        final OutcomeObservations observations = observations(guard);

        record(observations, "eu_west", "wheelchair_transport");
        record(observations, "eu_west", "wheelchair_transport");
        assertThat(meters.get("booking.place")
                .tag("region", "other").tag("product", "other")
                .timer().count()).isEqualTo(2);

        record(observations, "eu_west", "wheelchair_transport"); // k-th event reveals
        record(observations, "eu_west", "wheelchair_transport");
        assertThat(meters.get("booking.place")
                .tag("region", "eu_west").tag("product", "wheelchair_transport")
                .timer().count()).isEqualTo(2);
        assertThat(meters.get(CombinationGuard.COLLAPSED_COUNTER_NAME).functionCounter().count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("never reveals combinations that trickle slower than the window")
    void slowTrickleStaysCollapsed() {
        final CombinationGuard guard = guard(3, Duration.ofMinutes(15));
        final OutcomeObservations observations = observations(guard);

        for (int i = 0; i < 5; i++) {
            record(observations, "eu_west", "rare_product");
            clock.advance(Duration.ofMinutes(20)); // each event lands in a fresh window
        }

        assertThat(meters.get("booking.place")
                .tag("region", "other").tag("product", "other")
                .timer().count()).isEqualTo(5);
    }

    @Test
    @DisplayName("keeps a revealed combination revealed across window rollovers")
    void revealIsOneWay() {
        final CombinationGuard guard = guard(2, Duration.ofMinutes(15));
        final OutcomeObservations observations = observations(guard);

        record(observations, "eu_west", "common_product");
        record(observations, "eu_west", "common_product"); // reveals
        clock.advance(Duration.ofHours(6));
        record(observations, "eu_west", "common_product");

        assertThat(meters.get("booking.place")
                .tag("region", "eu_west").tag("product", "common_product")
                .timer().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("fails closed over the tracked-tuple cap")
    void capFailsClosed() {
        final CombinationGuard guard = CombinationGuard.builder()
                .keys("region", "product").minSupport(2).window(Duration.ofMinutes(15))
                .maxTrackedTuples(1).clock(clock).build();
        final OutcomeObservations observations = observations(guard);

        record(observations, "eu_west", "first_tuple");
        for (int i = 0; i < 3; i++) {
            record(observations, "us_east", "second_tuple"); // over cap: must stay collapsed
        }

        assertThat(meters.get("booking.place")
                .tag("region", "other").tag("product", "other")
                .timer().count()).isEqualTo(4);
    }

    @Test
    @DisplayName("scopes to name prefixes and leaves other names and unguarded keys untouched")
    void scoping() {
        final CombinationGuard guard = CombinationGuard.builder()
                .keys("region", "product").minSupport(5).window(Duration.ofMinutes(15))
                .namePrefixes("booking.").clock(clock).build();
        final OutcomeObservations observations = observations(guard);

        observations.record("payment.capture",
                KeyValues.of("region", "eu_west", "product", "rare_product"), () -> {
                });
        record(observations, "eu_west", "rare_product");

        assertThat(meters.get("payment.capture")
                .tag("region", "eu_west").tag("product", "rare_product")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("booking.place")
                .tag("region", "other").tag("product", "other")
                .tag("channel", "web")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("guards failure observations identically and dedups occurrence on collapsed values")
    void failuresAndOccurrence() {
        final CombinationGuard guard = guard(10, Duration.ofMinutes(15));
        final OutcomeObservations observations = observations(guard);

        try (OutcomeScope scope = OutcomeScope.open()) {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> observations.record("booking.place",
                        KeyValues.of("region", "eu_west", "product", "rare_product"), () -> {
                            throw new IllegalStateException("boom");
                        })).isInstanceOf(IllegalStateException.class);
            }
        }

        assertThat(meters.get("booking.place")
                .tag("outcome", "failure")
                .tag("region", "other").tag("product", "other")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("booking.place")
                .tag("outcome", "failure")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("counts support exactly once per observation despite repeated convention lookups")
    void noDoubleCountOnRepeatedConsultation() {
        final CombinationGuard guard = guard(2, Duration.ofMinutes(15));
        final OutcomeObservationConvention convention =
                OutcomeObservationConvention.builder().combinationGuard(guard).build();
        final OutcomeObservationContext context =
                new OutcomeObservationContext(KeyValues.of("region", "eu_west", "product", "p"));
        context.setName("booking.place");
        context.markSettled();

        final var first = convention.getLowCardinalityKeyValues(context);
        final var second = convention.getLowCardinalityKeyValues(context);

        // one observation consulted twice: still 1 support, so the tuple must not reveal
        assertThat(first).isEqualTo(second);
        assertThat(first.stream().filter(tag -> tag.getKey().equals("region")).findFirst().orElseThrow()
                .getValue()).isEqualTo("other");
    }

    @Test
    @DisplayName("rejects guarding outcome and alertability, blank keys, and invalid settings")
    void validation() {
        assertThatThrownBy(() -> CombinationGuard.builder().keys("outcome"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guarding \"outcome\" is not allowed");
        assertThatThrownBy(() -> CombinationGuard.builder().keys("alertability"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guarding \"alertability\" is not allowed");
        assertThatThrownBy(() -> CombinationGuard.builder().keys(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("guarded key must not be blank");
        assertThatThrownBy(() -> CombinationGuard.builder().keys("region").minSupport(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minSupport must be at least 2");
        assertThatThrownBy(() -> CombinationGuard.builder().keys("region").window(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("window must be positive");
        assertThatThrownBy(() -> CombinationGuard.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("at least one guarded key is required");
    }

    private CombinationGuard guard(final int minSupport, final Duration window) {
        final CombinationGuard guard = CombinationGuard.builder()
                .keys("region", "product")
                .minSupport(minSupport)
                .window(window)
                .clock(clock)
                .build();
        guard.bindTo(meters);
        return guard;
    }

    private OutcomeObservations observations(final CombinationGuard guard) {
        return new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder().combinationGuard(guard).build());
    }

    private void record(final OutcomeObservations observations, final String region, final String product) {
        observations.record("booking.place",
                KeyValues.of("region", region, "product", product, "channel", "web"), () -> {
                });
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(final Instant start) {
            this.now = start;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
