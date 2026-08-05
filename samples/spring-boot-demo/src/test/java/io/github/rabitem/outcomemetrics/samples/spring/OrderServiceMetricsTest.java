package io.github.rabitem.outcomemetrics.samples.spring;

import io.github.rabitem.outcomemetrics.samples.spring.domain.OrderRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("Spring Boot demo metrics")
class OrderServiceMetricsTest {

    @Autowired
    OrderService orderService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("records successful place with outcome success")
    void placeSuccess() {
        orderService.place("SKU-1", "web");

        assertThat(meterRegistry.get("demo.order.place")
                .tag("channel", "web")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("records reasoned failures from OutcomeReasonSource")
    void placeFailure() {
        assertThatThrownBy(() -> orderService.place("DECLINED", "pos"))
                .isInstanceOf(OrderRejectedException.class);

        assertThat(meterRegistry.get("demo.order.place")
                .tag("outcome", "failure")
                .tag("reason", "payment_declined")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("annotation path records reserve observations")
    void reserveAnnotation() {
        orderService.reserve("SKU-2");

        assertThat(meterRegistry.get("demo.order.reserve")
                .tag("step", "reserve")
                .tag("layer", "service")
                .tag("outcome", "success")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("classified payment keeps outcome schema and sanitized result tag")
    void classifiedPayment() {
        orderService.classifyPayment("RETRY");

        assertThat(meterRegistry.get("demo.order.payment")
                .tag("result", "retry")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer()
                .count()).isGreaterThanOrEqualTo(1);
    }
}
