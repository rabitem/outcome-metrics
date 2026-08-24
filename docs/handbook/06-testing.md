# Testing

Assert against `MeterRegistry` in-process. Do not scrape Prometheus in unit tests.

## Contracts: outcome-metrics-test

Add `outcome-metrics-test` (test scope) for the contracts plain assertions can't express:

```java
import static io.github.rabitem.outcomemetrics.test.OutcomeMetricsAssertions.assertThatOutcomes;

assertThatOutcomes(meterRegistry)
    .hasConsistentLabelSets()                       // the #60 detector: Prometheus rejects drift in prod
    .hasOutcomeSchema("order.place")                // all five schema tags present
    .hasSeriesCardinalityAtMost("order.place", 24)  // budget at the assertion site, no annotation magic
    .hasNoPrivacyViolations(TagPrivacyPolicy.saasDefaults());

ReasonVocabularyContracts.assertWellFormed(PaymentReasons.class); // codes stable, unique, routed
```

Optional ArchUnit rules (add `com.tngtech.archunit` yourself — the dependency is optional):

```java
OutcomeArchRules.outcomeReasonsAreEnums().check(classes);            // opt-in strictness; blind to lambdas
OutcomeArchRules.observationsOnlyFrom("com.acme.metrics..").check(classes);
```

## Vocabulary attestation (CI gate for #64)

Commit the declared vocabularies; drift fails the build and regeneration is a reviewable diff:

```java
@Test
void vocabularyIsAttested() {
    VocabularyAttestation.builder()
        .reasons(reasonRegistry).slos(sloCatalog).experiments(experimentRegistry)
        .build()
        .assertMatches(Path.of("src/test/resources/outcome-vocabulary.json"));
}
```

Regenerate deliberately: `./mvnw test -Doutcome.metrics.attestation.update=true` rewrites the file
so the vocabulary change lands in the pull request.

## Propagation contracts (async environments)

Run against your real executors to catch thread-local-propagating agents before production does —
a propagated `OutcomeScope` makes concurrent requests coalesce into each other's dedup windows:

```java
OutcomePropagationContracts.assertScopeConfinedAcrossExecutor(myWorkerPool);
OutcomePropagationContracts.assertDeferredSettlesAcrossExecutor(myCallbackExecutor);
```

## Mutation gate (standard PIT, no custom mutators)

Vocabulary contract tests kill standard mutations — no custom mutation engine needed:

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <configuration>
        <targetClasses><param>com.acme.reasons.*</param></targetClasses>
        <mutators><mutator>EMPTY_RETURNS</mutator><mutator>NULL_RETURNS</mutator></mutators>
        <mutationThreshold>100</mutationThreshold>
    </configuration>
</plugin>
```

An `EMPTY_RETURNS` mutation on `code()` or a `NULL_RETURNS` on `alertability()` dies against
`ReasonVocabularyContracts.assertWellFormed(...)`.

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
