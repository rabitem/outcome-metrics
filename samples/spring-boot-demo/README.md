# Spring Boot demo

Exercises `outcome-metrics-spring-boot-starter` on Boot 4.1.

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -f outcome-metrics-bom/pom.xml -B -ntp install
./mvnw -f samples/spring-boot-demo/pom.xml spring-boot:run
```

`http://localhost:18080`

```bash
curl -s -X POST 'http://localhost:18080/api/orders?sku=SKU-1&channel=web'
curl -s -X POST 'http://localhost:18080/api/orders?sku=DECLINED&channel=pos'   # reason=payment_declined
curl -s -X POST 'http://localhost:18080/api/orders/reserve?sku=SKU-2'         # @MeasuredOutcome
curl -s 'http://localhost:18080/api/orders/payment-class?status=RETRY'
curl -s http://localhost:18080/actuator/prometheus | rg 'demo_order_|tag_value_overflows'
```

```bash
./mvnw -f samples/spring-boot-demo/pom.xml test
```
