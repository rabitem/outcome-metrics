package io.github.rabitem.outcomemetrics.samples.quarkus;

import io.github.rabitem.outcomemetrics.samples.quarkus.domain.ShipmentFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@DisplayName("Quarkus demo metrics")
class ShipmentMetricsTest {

    @Inject
    ShipmentService shipmentService;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("records successful dispatch")
    void dispatchSuccess() {
        shipmentService.dispatch("O-1", "dhl");

        assertThat(meterRegistry.get("demo.shipment.dispatch")
                .tag("carrier", "dhl")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("records reasoned carrier failures")
    void dispatchFailure() {
        assertThatThrownBy(() -> shipmentService.dispatch("TIMEOUT", "ups"))
                .isInstanceOf(ShipmentFailedException.class);

        assertThat(meterRegistry.get("demo.shipment.dispatch")
                .tag("outcome", "failure")
                .tag("reason", "carrier_timeout")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("annotation path records label observations")
    void labelAnnotation() {
        shipmentService.printLabel("O-2");

        assertThat(meterRegistry.get("demo.shipment.label")
                .tag("step", "label")
                .tag("layer", "service")
                .tag("outcome", "success")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }
}
