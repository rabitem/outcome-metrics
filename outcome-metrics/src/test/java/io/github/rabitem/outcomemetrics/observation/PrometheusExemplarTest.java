package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof for the exemplars recipe: an outcome timer recorded while a span is active carries a
 * {@code trace_id}/{@code span_id} exemplar in the OpenMetrics exposition — the click-through from
 * an alert on {@code reason="payment_declined"} to the trace that produced it.
 *
 * <p>Verified mechanism (micrometer-registry-prometheus 1.17): the registry accepts a Prometheus
 * client {@link SpanContext}; exemplars attach to counter/{@code _count} samples out of the box and
 * to histogram {@code _bucket} samples when {@code publishPercentileHistogram} is enabled (verified
 * empirically — bucket exemplars are what latency heatmaps link through), and only the OpenMetrics
 * content type renders them. A test-scoped fixed-id {@link SpanContext} stands in for a tracing bridge — the
 * contract is identical.
 */
@DisplayName("Prometheus exemplars")
class PrometheusExemplarTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String OPENMETRICS = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private PrometheusMeterRegistry prometheus;
    private OutcomeObservations observations;

    @BeforeEach
    void setUp() {
        prometheus = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, new PrometheusRegistry(), Clock.SYSTEM, fixedSpanContext());
        prometheus.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(
                    final Meter.Id id, final DistributionStatisticConfig config) {
                // bucket-level exemplars (latency heatmap click-through) need percentile histograms
                return id.getName().startsWith("checkout.")
                        ? DistributionStatisticConfig.builder().percentilesHistogram(true).build().merge(config)
                        : config;
            }
        });
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(prometheus));
        observations = new OutcomeObservations(registry);
    }

    @Test
    @DisplayName("attaches the active trace to failure-series buckets: alert to trace click-through")
    void failureSeriesCarriesExemplar() {
        assertThatThrownBy(() -> observations.record(
                "checkout.place", KeyValues.of("channel", "web"), () -> {
                    throw new DeclinedException();
                })).isInstanceOf(DeclinedException.class);

        final String scrape = prometheus.scrape(OPENMETRICS);
        final String failureBuckets = seriesLines(scrape, "checkout_place_seconds_bucket", "payment_declined");
        assertThat(failureBuckets).contains("trace_id=\"" + TRACE_ID + "\"");
        assertThat(failureBuckets).contains("span_id=\"" + SPAN_ID + "\"");
    }

    @Test
    @DisplayName("attaches exemplars to success buckets too")
    void successSeriesCarriesExemplar() {
        observations.record("checkout.place", KeyValues.of("channel", "web"), () -> {
        });

        assertThat(seriesLines(prometheus.scrape(OPENMETRICS), "checkout_place_seconds_bucket", "success"))
                .contains("trace_id=\"" + TRACE_ID + "\"");
    }

    @Test
    @DisplayName("attaches exemplars to plain timers' count samples even without histogram buckets")
    void plainTimerCountCarriesExemplar() {
        // name outside the histogram filter: exported as count/sum/max without buckets —
        // verified empirically: the _count sample still carries the exemplar on this version
        observations.record("plain.op", KeyValues.empty(), () -> {
        });

        assertThat(seriesLines(prometheus.scrape(OPENMETRICS), "plain_op_seconds_count", ""))
                .contains("trace_id=\"" + TRACE_ID + "\"");
    }

    private static String seriesLines(final String scrape, final String namePrefix, final String tagValue) {
        final StringBuilder matching = new StringBuilder();
        for (final String line : scrape.split("\n")) {
            if (line.startsWith(namePrefix) && line.contains(tagValue)) {
                matching.append(line).append('\n');
            }
        }
        return matching.toString();
    }

    private static SpanContext fixedSpanContext() {
        return new SpanContext() {
            @Override
            public String getCurrentTraceId() {
                return TRACE_ID;
            }

            @Override
            public String getCurrentSpanId() {
                return SPAN_ID;
            }

            @Override
            public boolean isCurrentSpanSampled() {
                return true;
            }

            @Override
            public void markCurrentSpanAsExemplar() {
                // no-op: a real tracing bridge marks the span for exemplar storage
            }
        };
    }

    private static final class DeclinedException extends RuntimeException implements OutcomeReasonSource {

        @Override
        public OutcomeReason outcomeReason() {
            return new OutcomeReason() {
                @Override
                public String code() {
                    return "payment_declined";
                }

                @Override
                public Alertability alertability() {
                    return Alertability.NONE;
                }
            };
        }
    }
}
