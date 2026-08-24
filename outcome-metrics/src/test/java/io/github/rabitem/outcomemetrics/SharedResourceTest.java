package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SharedResource")
class SharedResourceTest {

    @Test
    @DisplayName("emits the full five-tag bundle for every relationship")
    void relationshipBundles() {
        assertThat(toMap(SharedResource.owned("instructor", "Enterprise").tags()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "resource", "instructor",
                        "relationship", "owned",
                        "consumer_tier", "enterprise",
                        "owner_tier", "self",
                        "pool", "none"));
        assertThat(toMap(SharedResource.borrowed("instructor", "starter", "enterprise").tags()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "resource", "instructor",
                        "relationship", "borrowed",
                        "consumer_tier", "starter",
                        "owner_tier", "enterprise",
                        "pool", "none"));
        assertThat(toMap(SharedResource.pooled("instructor", "starter").withPool("pool_eu_1").tags()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "resource", "instructor",
                        "relationship", "pooled",
                        "consumer_tier", "starter",
                        "owner_tier", "shared",
                        "pool", "pool_eu_1"));
    }

    @Test
    @DisplayName("withPool returns a copy and leaves the original untouched")
    void withPoolIsImmutable() {
        final SharedResource base = SharedResource.pooled("instructor", "starter");
        final SharedResource pooled = base.withPool("pool_eu_1");

        assertThat(toMap(base.tags())).containsEntry("pool", "none");
        assertThat(toMap(pooled.tags())).containsEntry("pool", "pool_eu_1");
    }

    @Test
    @DisplayName("rejects UUID-shaped and long-hex values in every position")
    void rejectsIdentifierShapedValues() {
        final String uuid = "6b309a58-267e-4e79-8e1d-75c0abeb43bf";
        assertThatThrownBy(() -> SharedResource.owned(uuid, "enterprise"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType looks like an identifier");
        assertThatThrownBy(() -> SharedResource.owned("instructor", uuid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerTier looks like an identifier");
        assertThatThrownBy(() -> SharedResource.borrowed("instructor", "starter", uuid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerTier looks like an identifier");
        assertThatThrownBy(() -> SharedResource.pooled("instructor", "starter")
                .withPool("deadbeefdeadbeef01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolId looks like an identifier");
        assertThatThrownBy(() -> SharedResource.owned(" ", "enterprise"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resourceType must not be blank");
    }

    @Test
    @DisplayName("accepts short vocabulary values that merely contain digits")
    void acceptsBenignValues() {
        assertThat(toMap(SharedResource.pooled("gpu_a100", "tier_3").withPool("pool_2024").tags()))
                .containsEntry("resource", "gpu_a100")
                .containsEntry("consumer_tier", "tier_3")
                .containsEntry("pool", "pool_2024");
    }

    @Test
    @DisplayName("lands alongside the outcome schema tags in an observation")
    void integratesWithObservations() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        observations.record(
                "lesson.assign",
                SharedResource.borrowed("instructor", "starter", "enterprise").tags()
                        .and(KeyValues.of("channel", "web")),
                () -> {
                });

        assertThat(meters.get("lesson.assign")
                .tag("relationship", "borrowed")
                .tag("owner_tier", "enterprise")
                .tag("channel", "web")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    private static Map<String, String> toMap(final KeyValues tags) {
        return tags.stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }
}
