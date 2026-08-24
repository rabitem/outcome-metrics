package io.github.rabitem.outcomemetrics.samples.spring;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MetricsTags;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.samples.spring.domain.OrderReason;
import io.github.rabitem.outcomemetrics.samples.spring.domain.OrderRejectedException;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo order service showing programmatic and annotation-driven outcome metrics.
 */
@Service
@MeasuredOutcome(name = "demo.order.type", tags = {"layer=service"})
public class OrderService {

    private final OutcomeObservations observations;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, AtomicLong> channelHits = new ConcurrentHashMap<>();

    public OrderService(final OutcomeObservations observations) {
        this.observations = observations;
    }

    /**
     * Programmatic API: explicit observation around a unit of work.
     */
    public Map<String, Object> place(final String sku, final String channel) {
        final String safeChannel = normalizeChannel(channel);
        return observations.record(
                "demo.order.place",
                MetricsTags.pairs("channel=" + safeChannel),
                () -> doPlace(sku, safeChannel));
    }

    /**
     * Annotation API: interceptor records success/failure automatically.
     */
    @MeasuredOutcome(name = "demo.order.reserve", tags = {"step=reserve"})
    public Map<String, Object> reserve(final String sku) {
        if (sku == null || sku.isBlank()) {
            throw new OrderRejectedException(OrderReason.INVENTORY_SHORTAGE, "sku required");
        }
        if ("FORBIDDEN".equalsIgnoreCase(sku)) {
            throw new OrderRejectedException(OrderReason.CROSS_TENANT, "sku not visible");
        }
        return Map.of(
                "reservationId", "R-" + sequence.incrementAndGet(),
                "sku", sku.strip());
    }

    /**
     * Classified success: result tags describe business outcome without throwing.
     */
    public String classifyPayment(final String status) {
        return observations.record(
                "demo.order.payment",
                KeyValues.of("step", "classify"),
                () -> status == null ? "UNKNOWN" : status.strip().toUpperCase(Locale.ROOT),
                value -> MetricsTags.pairs("result=" + value),
                "result");
    }

    private Map<String, Object> doPlace(final String sku, final String channel) {
        channelHits.computeIfAbsent(channel, ignored -> new AtomicLong()).incrementAndGet();
        if ("DECLINED".equalsIgnoreCase(sku)) {
            throw new OrderRejectedException(OrderReason.PAYMENT_DECLINED, "card declined");
        }
        if ("OOS".equalsIgnoreCase(sku)) {
            throw new OrderRejectedException(OrderReason.INVENTORY_SHORTAGE, "out of stock");
        }
        return Map.of(
                "orderId", "O-" + sequence.incrementAndGet(),
                "sku", sku == null ? "" : sku.strip(),
                "channel", channel,
                "status", "ACCEPTED");
    }

    private static String normalizeChannel(final String channel) {
        if (channel == null || channel.isBlank()) {
            return "web";
        }
        return channel.strip().toLowerCase(Locale.ROOT);
    }
}
