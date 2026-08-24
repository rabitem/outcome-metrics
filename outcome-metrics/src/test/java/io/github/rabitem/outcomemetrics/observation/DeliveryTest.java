package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MessagingTags;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Delivery")
class DeliveryTest {

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
    @DisplayName("tags successful deliveries fate processed with the attempt bucket")
    void successfulDelivery() {
        outcomeObservations.recordDelivery(
                "order.consume", KeyValues.of("priority_class", "bulk"), 1,
                () -> "ok", error -> DeliveryFate.RETRY);

        assertThat(meters.get("order.consume")
                .tag("outcome", "success")
                .tag("fate", "processed")
                .tag("attempt_bucket", "1")
                .tag("priority_class", "bulk")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("classifies failure fates alongside reason and alertability on one series")
    void classifiedFailureFates() {
        failDelivery("order.consume", 2, error -> DeliveryFate.RETRY);
        failDelivery("order.consume", 5, error -> DeliveryFate.DEAD_LETTER);
        failDelivery("order.consume", 5, error -> DeliveryFate.DROP);

        assertThat(meters.get("order.consume")
                .tag("outcome", "failure")
                .tag("fate", "retry")
                .tag("attempt_bucket", "2_3")
                .tag("reason", "unknown")
                .tag("alertability", "page")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("order.consume")
                .tag("fate", "dead_letter").tag("attempt_bucket", "4_plus")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("order.consume")
                .tag("fate", "drop")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("yields fate unknown when the classifier misbehaves and never masks the original error")
    void classifierMisbehaves() {
        assertThatThrownBy(() -> outcomeObservations.recordDelivery(
                "order.null", KeyValues.empty(), 1, () -> {
                    throw new IllegalStateException("original failure");
                }, error -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("original failure");
        assertThatThrownBy(() -> outcomeObservations.recordDelivery(
                "order.throws", KeyValues.empty(), 1, () -> {
                    throw new IllegalStateException("original failure");
                }, error -> {
                    throw new IllegalArgumentException("classifier blew up");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("original failure");

        assertThat(meters.get("order.null")
                .tag("fate", "unknown").timer().count()).isEqualTo(1);
        assertThat(meters.get("order.throws")
                .tag("fate", "unknown").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("buckets attempts including out-of-contract values")
    void attemptBuckets() {
        assertThat(MessagingTags.attemptBucket(1)).isEqualTo("1");
        assertThat(MessagingTags.attemptBucket(2)).isEqualTo("2_3");
        assertThat(MessagingTags.attemptBucket(3)).isEqualTo("2_3");
        assertThat(MessagingTags.attemptBucket(4)).isEqualTo("4_plus");
        assertThat(MessagingTags.attemptBucket(0)).isEqualTo("unknown");
        assertThat(MessagingTags.attemptBucket(-1)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("buckets lag with strict upper bounds and clamps clock skew")
    void lagBuckets() {
        assertThat(MessagingTags.lagBucket(Duration.ofMillis(999))).isEqualTo("lt_1s");
        assertThat(MessagingTags.lagBucket(Duration.ofSeconds(1))).isEqualTo("lt_10s");
        assertThat(MessagingTags.lagBucket(Duration.ofSeconds(10))).isEqualTo("lt_1m");
        assertThat(MessagingTags.lagBucket(Duration.ofMinutes(1))).isEqualTo("lt_10m");
        assertThat(MessagingTags.lagBucket(Duration.ofMinutes(10))).isEqualTo("gte_10m");
        assertThat(MessagingTags.lagBucket(Duration.ofSeconds(-5))).isEqualTo("lt_1s");
        assertThat(MessagingTags.lagBucket(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("supports the outbox drain and consumer lag patterns as plain dimensions")
    void lagAsDimension() {
        outcomeObservations.record(
                "outbox.publish",
                KeyValues.of(MessagingTags.TAG_LAG_BUCKET, MessagingTags.lagBucket(Duration.ofSeconds(4)),
                        "priority_class", "realtime"),
                () -> {
                });

        assertThat(meters.get("outbox.publish")
                .tag("lag_bucket", "lt_10s")
                .tag("priority_class", "realtime")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates dependencies")
    void validation() {
        assertThatThrownBy(() -> outcomeObservations.recordDelivery(
                "order.consume", KeyValues.empty(), 1, () -> "ok", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("classifier must not be null");
        assertThatThrownBy(() -> outcomeObservations.recordDelivery(
                "order.consume", KeyValues.empty(), 1, null, error -> DeliveryFate.RETRY))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("work must not be null");
    }

    private void failDelivery(final String name, final int attempt, final DeliveryFateClassifier classifier) {
        assertThatThrownBy(() -> outcomeObservations.recordDelivery(
                name, KeyValues.empty(), attempt, () -> {
                    throw new IllegalStateException("delivery failed");
                }, classifier))
                .isInstanceOf(IllegalStateException.class);
    }
}
