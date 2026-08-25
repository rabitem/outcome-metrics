package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.CombinationGuard;
import io.github.rabitem.outcomemetrics.observation.IdempotencyOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservationConvention;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.ReasonBudget;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The library's label-set claims (#60) verified against a genuine {@link PrometheusMeterRegistry}
 * and its scrape exposition — {@code SimpleMeterRegistry} enforces nothing, so these tests are
 * where the claims meet the real client.
 */
@DisplayName("Prometheus registry")
class PrometheusSchemaTest {

    private PrometheusMeterRegistry prometheus;
    private OutcomeObservations observations;
    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(prometheus));
        observations = new OutcomeObservations(registry);
    }

    @Test
    @DisplayName("exposes the full always-on schema on success and failure series of one name")
    void alwaysOnSchema() {
        observations.record("order.place", KeyValues.of("channel", "web"), () -> {
        });
        assertThatThrownBy(() -> observations.record("order.place", KeyValues.of("channel", "web"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(seriesLine("order_place_seconds_count", "outcome=\"success\""))
                .contains("reason=\"none\"", "integrity=\"ok\"", "alertability=\"none\"",
                        "occurrence=\"first\"", "channel=\"web\"");
        assertThat(seriesLine("order_place_seconds_count", "outcome=\"failure\""))
                .contains("reason=\"unknown\"", "integrity=\"none\"", "alertability=\"page\"",
                        "occurrence=\"first\"", "channel=\"web\"");
    }

    @Test
    @DisplayName("keeps declared result-tag label sets consistent across outcomes in the scrape")
    void declaredResultTags() {
        observations.recordIdempotent("payment.capture", KeyValues.empty(),
                () -> "ok", result -> IdempotencyOutcome.APPLIED);
        assertThatThrownBy(() -> observations.recordIdempotent("payment.capture", KeyValues.empty(),
                () -> {
                    throw new IllegalStateException("boom");
                }, result -> IdempotencyOutcome.APPLIED))
                .isInstanceOf(IllegalStateException.class);
        observations.record("order.classify", KeyValues.empty(), () -> "RETRY",
                value -> KeyValues.of("result", value), "result");
        assertThatThrownBy(() -> observations.record("order.classify", KeyValues.empty(), () -> {
            throw new IllegalStateException("boom");
        }, value -> KeyValues.of("result", String.valueOf(value)), "result"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(seriesLine("payment_capture_seconds_count", "outcome=\"success\""))
                .contains("idempotency=\"applied\"");
        assertThat(seriesLine("payment_capture_seconds_count", "outcome=\"failure\""))
                .contains("idempotency=\"none\"");
        assertThat(seriesLine("order_classify_seconds_count", "outcome=\"success\""))
                .contains("result=\"retry\"");
        assertThat(seriesLine("order_classify_seconds_count", "outcome=\"failure\""))
                .contains("result=\"none\"");
        assertThatCode(() -> OutcomeMetricsAssertions.assertThatOutcomes(prometheus)
                .hasConsistentLabelSets()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("documents the real client's behavior: mixed label sets are exposed silently, not rejected")
    void mixedLabelSetsAreSilent() {
        prometheus.counter("mixed.meter", "a", "1").increment();

        // Empirical pin (Micrometer 1.17 / new Prometheus client): the second registration with a
        // different tag key set does NOT throw — the drift ships straight into the exposition.
        assertThatCode(() -> prometheus.counter("mixed.meter", "a", "1", "b", "2").increment())
                .doesNotThrowAnyException();
        assertThat(prometheus.scrape())
                .contains("mixed_meter_total{a=\"1\"}")
                .contains("mixed_meter_total{a=\"1\",b=\"2\"}");

        // ...which is exactly why the assertion exists: it is the only gate that fails loudly.
        assertThatThrownBy(() -> OutcomeMetricsAssertions.assertThatOutcomes(prometheus)
                .hasConsistentLabelSets())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("mixed.meter");
    }

    @Test
    @DisplayName("runs a fully composed convention end to end against the real client")
    void composedConvention() {
        final OutcomeObservations composed = new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder()
                        .reasonBudget(new ReasonBudget(4, 16))
                        .reasonRegistry(ReasonRegistry.builder().codes("db_down").build())
                        .combinationGuard(CombinationGuard.builder()
                                .keys("region").minSupport(10).window(Duration.ofMinutes(15)).build())
                        .tagPrivacyPolicy(TagPrivacyPolicy.saasDefaults())
                        .build());

        composed.record("composed.op",
                KeyValues.of("email", "x@y.z", "region", "eu_west"), () -> {
                });

        assertThat(seriesLine("composed_op_seconds_count", "outcome=\"success\""))
                .contains("email=\"redacted\"", "region=\"other\"", "integrity=\"ok\"",
                        "alertability=\"none\"", "occurrence=\"first\"");
        assertThatCode(() -> OutcomeMetricsAssertions.assertThatOutcomes(prometheus)
                .hasConsistentLabelSets()
                .hasOutcomeSchema("composed.op")).doesNotThrowAnyException();
    }

    private String seriesLine(final String metricName, final String discriminator) {
        final List<String> matches = prometheus.scrape().lines()
                .filter(line -> line.startsWith(metricName + "{") && line.contains(discriminator))
                .toList();
        assertThat(matches)
                .as("exactly one %s series with %s in the scrape", metricName, discriminator)
                .hasSize(1);
        return matches.get(0);
    }
}
