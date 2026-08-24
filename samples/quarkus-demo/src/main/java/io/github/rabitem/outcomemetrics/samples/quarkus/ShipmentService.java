package io.github.rabitem.outcomemetrics.samples.quarkus;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MetricsTags;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.samples.quarkus.domain.ShipmentFailedException;
import io.github.rabitem.outcomemetrics.samples.quarkus.domain.ShipmentReason;
import io.micrometer.common.KeyValues;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
@MeasuredOutcome(name = "demo.shipment.type", tags = {"layer=service"})
public class ShipmentService {

    private final OutcomeObservations observations;
    private final AtomicLong sequence = new AtomicLong();

    @Inject
    public ShipmentService(final OutcomeObservations observations) {
        this.observations = observations;
    }

    public Map<String, Object> dispatch(final String orderId, final String carrier) {
        final String safeCarrier = normalizeCarrier(carrier);
        return observations.record(
                "demo.shipment.dispatch",
                MetricsTags.pairs("carrier=" + safeCarrier),
                () -> doDispatch(orderId, safeCarrier));
    }

    @MeasuredOutcome(name = "demo.shipment.label", tags = {"step=label"})
    public Map<String, Object> printLabel(final String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new ShipmentFailedException(ShipmentReason.ADDRESS_INVALID, "orderId required");
        }
        if ("BAD-ADDRESS".equalsIgnoreCase(orderId)) {
            throw new ShipmentFailedException(ShipmentReason.ADDRESS_INVALID, "address invalid");
        }
        return Map.of(
                "labelId", "L-" + sequence.incrementAndGet(),
                "orderId", orderId.strip());
    }

    public String classifyDelay(final String code) {
        return observations.record(
                "demo.shipment.delay",
                KeyValues.of("step", "classify"),
                () -> code == null ? "UNKNOWN" : code.strip().toUpperCase(Locale.ROOT),
                value -> MetricsTags.pairs("result=" + value),
                "result");
    }

    private Map<String, Object> doDispatch(final String orderId, final String carrier) {
        if ("TIMEOUT".equalsIgnoreCase(orderId)) {
            throw new ShipmentFailedException(ShipmentReason.CARRIER_TIMEOUT, "carrier timeout");
        }
        return Map.of(
                "shipmentId", "S-" + sequence.incrementAndGet(),
                "orderId", orderId == null ? "" : orderId.strip(),
                "carrier", carrier,
                "status", "DISPATCHED");
    }

    private static String normalizeCarrier(final String carrier) {
        if (carrier == null || carrier.isBlank()) {
            return "dhl";
        }
        return carrier.strip().toLowerCase(Locale.ROOT);
    }
}
