package io.github.rabitem.outcomemetrics.samples.spring;

import io.github.rabitem.outcomemetrics.samples.spring.domain.OrderRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(final OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, Object> place(
            @RequestParam final String sku,
            @RequestParam(defaultValue = "web") final String channel) {
        return orderService.place(sku, channel);
    }

    @PostMapping("/reserve")
    public Map<String, Object> reserve(@RequestParam final String sku) {
        return orderService.reserve(sku);
    }

    @GetMapping("/payment-class")
    public Map<String, String> classify(@RequestParam final String status) {
        return Map.of("classification", orderService.classifyPayment(status));
    }

    @ExceptionHandler(OrderRejectedException.class)
    ProblemDetail rejected(final OrderRejectedException ex) {
        final ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        detail.setTitle("Order rejected");
        detail.setProperty("reason", ex.outcomeReason().code());
        return detail;
    }
}
