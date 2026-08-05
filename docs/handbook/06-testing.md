# Testing

Assert against `MeterRegistry` in-process. Do not scrape Prometheus in unit tests.

## Spring

```java
@SpringBootTest
class OrderServiceMetricsTest {
    @Autowired OrderService orderService;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void placeFailure() {
        assertThatThrownBy(() -> orderService.place("DECLINED", "pos"))
            .isInstanceOf(OrderRejectedException.class);

        assertThat(meterRegistry.get("demo.order.place")
            .tag("outcome", "failure")
            .tag("reason", "payment_declined")
            .timer()
            .count()).isGreaterThanOrEqualTo(1);
    }
}
```

Full suite: `samples/spring-boot-demo`.

## Quarkus

```java
@QuarkusTest
class ShipmentMetricsTest {
    @Inject ShipmentService shipmentService;
    @Inject MeterRegistry meterRegistry;

    @Test
    void dispatchFailure() {
        assertThatThrownBy(() -> shipmentService.dispatch("TIMEOUT", "ups"))
            .isInstanceOf(ShipmentFailedException.class);

        assertThat(meterRegistry.get("demo.shipment.dispatch")
            .tag("outcome", "failure")
            .tag("reason", "carrier_timeout")
            .timer()
            .count()).isGreaterThanOrEqualTo(1);
    }
}
```

Full suite: `samples/quarkus-demo`.

## Useful assertions

- `outcome` / `reason` on the timer
- Reason codes for domain exceptions
- Sanitized result tags (`RETRY` → `retry`)
- Remap to `other` when over `tag-limits`

Avoid asserting exact global counts across a shared registry, or latency SLOs in unit tests.

```bash
./mvnw -B verify
./mvnw -f outcome-metrics-bom/pom.xml -B install
./mvnw -f samples/pom.xml -B verify
```
