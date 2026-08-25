# Testing

Assert against `MeterRegistry` in-process. Do not scrape Prometheus in unit tests.

Two layers: plain assertions on the meters your code emitted, and the **contracts** in
`outcome-metrics-test` for the properties plain assertions can't express — label-set consistency,
vocabulary drift, PII leakage, and async propagation.

## Plain assertions

Spring:

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

Quarkus is identical with `@QuarkusTest` / `@Inject` (full suites: `samples/spring-boot-demo`,
`samples/quarkus-demo`).

Worth asserting: `outcome`/`reason` on the timer, reason codes for domain exceptions, sanitized
result tags (`RETRY` → `retry`), remap to `other` when over `tag-limits`. Avoid exact global
counts across a shared registry and latency SLOs in unit tests.

## Contracts: outcome-metrics-test

Add `outcome-metrics-test` (test scope):

```java
import static io.github.rabitem.outcomemetrics.test.OutcomeMetricsAssertions.assertThatOutcomes;

assertThatOutcomes(meterRegistry)
    .hasConsistentLabelSets()                       // the label-set gate: modern clients expose drift silently
    .hasOutcomeSchema("order.place")                // all five schema tags present
    .hasSeriesCardinalityAtMost("order.place", 24)  // budget at the assertion site
    .hasNoPrivacyViolations(TagPrivacyPolicy.saasDefaults());

ReasonVocabularyContracts.assertWellFormed(PaymentReasons.class); // codes stable, unique, routed
```

`hasConsistentLabelSets()` matters because the failure mode is quiet in production: legacy
Prometheus clients crash on mixed label sets, but the current client (Micrometer 1.13+) silently
exposes both series and splits your aggregations — this assertion is the only loud gate. The
module's own suite verifies that behavior against a real `PrometheusMeterRegistry`.

Optional ArchUnit rules (add `com.tngtech.archunit` yourself — the dependency is optional):

```java
OutcomeArchRules.outcomeReasonsAreEnums().check(classes);            // opt-in strictness; blind to lambdas
OutcomeArchRules.observationsOnlyFrom("com.acme.metrics..").check(classes);
```

## Vocabulary attestation (CI gate)

Commit the declared vocabularies; drift fails the build, and regeneration is a reviewable diff:

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

## Build commands

```bash
./mvnw -B verify
./mvnw -f outcome-metrics-bom/pom.xml -B install
./mvnw -f samples/pom.xml -B verify
```
