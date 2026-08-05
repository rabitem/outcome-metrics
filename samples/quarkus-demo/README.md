# Quarkus demo

Exercises `outcome-metrics-quarkus` (preview) on Quarkus 3.38.

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -f outcome-metrics-bom/pom.xml -B -ntp install
./mvnw -f samples/quarkus-demo/pom.xml quarkus:dev
```

`http://localhost:18081`

```bash
curl -s -X POST 'http://localhost:18081/api/shipments?orderId=O-1&carrier=dhl'
curl -s -X POST 'http://localhost:18081/api/shipments?orderId=TIMEOUT&carrier=ups'  # reason=carrier_timeout
curl -s -X POST 'http://localhost:18081/api/shipments/label?orderId=O-2'
curl -s http://localhost:18081/q/metrics | rg 'demo_shipment_|tag_value_overflows'
```

```bash
./mvnw -f samples/quarkus-demo/pom.xml test
```
