package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SloCatalog")
class SloCatalogTest {

    @Test
    @DisplayName("returns sanitized bindings for declared ids and throws for undeclared ones")
    void bindings() {
        final SloCatalog catalog = SloCatalog.of("checkout-success", "refund_latency");

        assertThat(catalog.binding("checkout-success").getValue()).isEqualTo("checkout_success");
        assertThat(catalog.binding("checkout_success").getKey()).isEqualTo("slo");
        assertThat(catalog.ids()).containsExactly("checkout_success", "refund_latency");
        assertThatThrownBy(() -> catalog.binding("checkout-typo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"checkout-typo\" is not declared");
    }

    @Test
    @DisplayName("registers one info gauge per declared id with value 1")
    void infoGauges() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SloCatalog.of("checkout-success", "refund_latency").bindTo(meters);

        assertThat(meters.get(SloCatalog.INFO_GAUGE_NAME)
                .tag("slo", "checkout_success").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get(SloCatalog.INFO_GAUGE_NAME)
                .tag("slo", "refund_latency").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("lands the slo tag beside the outcome schema in an observation")
    void integratesWithObservations() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);
        final SloCatalog catalog = SloCatalog.of("checkout-success");

        observations.record(
                "order.place",
                KeyValues.of(catalog.binding("checkout-success")).and("channel", "web"),
                () -> {
                });

        assertThat(meters.get("order.place")
                .tag("slo", "checkout_success")
                .tag("channel", "web")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates ids at construction and keeps the catalog immutable")
    void validation() {
        assertThatThrownBy(() -> SloCatalog.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SLO id must not be blank");
        assertThatThrownBy(SloCatalog::of)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one SLO id is required");
        assertThatThrownBy(() -> SloCatalog.of((String[]) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SloCatalog.of("checkout").ids().add("sneaky"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
