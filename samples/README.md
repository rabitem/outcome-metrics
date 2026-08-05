# Samples

| Demo | Port | Metrics |
|---|---|---|
| [spring-boot-demo](spring-boot-demo/) | `18080` | `/actuator/prometheus` |
| [quarkus-demo](quarkus-demo/) | `18081` | `/q/metrics` |

Not part of the library reactor:

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -f outcome-metrics-bom/pom.xml -B -ntp install
./mvnw -f samples/pom.xml -B verify
```

Docs: [handbook](../docs/handbook/README.md).
