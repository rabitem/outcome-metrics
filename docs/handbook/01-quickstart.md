# Quickstart

## Run a sample

```bash
git clone https://github.com/rabitem/outcome-metrics.git
cd outcome-metrics
./mvnw -B -ntp install -DskipTests
./mvnw -f outcome-metrics-bom/pom.xml -B -ntp install
```

**Spring Boot** (port `18080`):

```bash
./mvnw -f samples/spring-boot-demo/pom.xml spring-boot:run
```

```bash
curl -s -X POST 'http://localhost:18080/api/orders?sku=SKU-1&channel=web'
curl -s -X POST 'http://localhost:18080/api/orders?sku=DECLINED&channel=pos'
curl -s http://localhost:18080/actuator/prometheus | rg 'demo_order_place_seconds_count'
```

| Request | `outcome` | `reason` |
|---|---|---|
| `SKU-1` | `success` | `none` |
| `DECLINED` | `failure` | `payment_declined` |

**Quarkus** (port `18081`):

```bash
./mvnw -f samples/quarkus-demo/pom.xml quarkus:dev
```

```bash
curl -s -X POST 'http://localhost:18081/api/shipments?orderId=O-1&carrier=dhl'
curl -s -X POST 'http://localhost:18081/api/shipments?orderId=TIMEOUT&carrier=ups'
curl -s http://localhost:18081/q/metrics | rg 'demo_shipment_dispatch_seconds_count'
```

## Add the dependency

Spring Boot 4:

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-spring-boot-starter</artifactId>
  <version>0.1.0-beta.1</version>
</dependency>
```

Needs an `ObservationRegistry` (Boot Actuator + micrometer observation/metrics starters).

Quarkus 3:

```xml
<dependency>
  <groupId>io.github.rabitem</groupId>
  <artifactId>outcome-metrics-quarkus</artifactId>
  <version>0.1.0-beta.1</version>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

Then: [API](02-api.md) · [Spring Boot](03-spring-boot.md) · [Quarkus](04-quarkus.md)
