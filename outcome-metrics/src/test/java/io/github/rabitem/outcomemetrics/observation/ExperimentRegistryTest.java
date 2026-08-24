package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExperimentRegistry")
class ExperimentRegistryTest {

    private SimpleMeterRegistry meters;
    private ExperimentRegistry experiments;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        experiments = ExperimentRegistry.builder()
                .experiment("checkout-v2")
                .experiment("onboarding", "control", "gentle", "aggressive")
                .build();
        experiments.bindTo(meters);
    }

    @Test
    @DisplayName("slices registered experiments with sanitized round-trip ids and declared arms")
    void registeredSlices() {
        assertThat(toMap(experiments.slice("checkout-v2", "treatment")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "experiment", "checkout_v2", "variant", "treatment"));
        assertThat(toMap(experiments.slice("onboarding", "aggressive")))
                .containsEntry("variant", "aggressive");
        assertThat(experiments.ids()).containsExactly("checkout_v2", "onboarding");
    }

    @Test
    @DisplayName("collapses unregistered ids entirely and undeclared arms partially, counting both")
    void collapses() {
        assertThat(toMap(experiments.slice("raw-flag-key-from-sdk", "treatment")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "experiment", "unregistered", "variant", "unknown"));
        assertThat(toMap(experiments.slice("checkout-v2", "arm_from_misconfigured_sdk")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "experiment", "checkout_v2", "variant", "unknown"));
        assertThat(toMap(experiments.slice(null, null)))
                .containsEntry("experiment", "unregistered");

        assertThat(meters.get(ExperimentRegistry.UNREGISTERED_COUNTER_NAME)
                .functionCounter().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("provides the none bundle so sliced and unsliced events share a label set")
    void noneBundleAndLabelConsistency() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        observations.record("checkout.place",
                experiments.slice("checkout-v2", "control").and("surface", "web"), () -> {
                });
        observations.record("checkout.place",
                ExperimentRegistry.none().and("surface", "web"), () -> {
                });

        assertThat(meters.get("checkout.place")
                .tag("experiment", "checkout_v2").tag("variant", "control")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("checkout.place")
                .tag("experiment", "none").tag("variant", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("registers one info gauge per experiment")
    void infoGauges() {
        assertThat(meters.get(ExperimentRegistry.INFO_GAUGE_NAME)
                .tag("experiment", "checkout_v2").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get(ExperimentRegistry.INFO_GAUGE_NAME)
                .tag("experiment", "onboarding").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("enforces the active cap and arm cap at build time")
    void buildTimeCaps() {
        final ExperimentRegistry.Builder overCap = ExperimentRegistry.builder().maxActive(1)
                .experiment("first").experiment("second");
        assertThatThrownBy(overCap::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxActive is 1");
        assertThatThrownBy(() -> ExperimentRegistry.builder()
                .experiment("many_arms", "a", "b", "c", "d", "e", "f", "g"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 6 arms");
        assertThatThrownBy(() -> ExperimentRegistry.builder().experiment(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("experiment id must not be blank");
        assertThatThrownBy(() -> ExperimentRegistry.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one experiment is required");
        assertThatThrownBy(() -> experiments.ids().add("sneaky"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<String, String> toMap(final KeyValues tags) {
        return tags.stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }
}
