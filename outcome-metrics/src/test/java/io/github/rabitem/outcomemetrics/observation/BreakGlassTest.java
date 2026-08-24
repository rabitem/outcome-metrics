package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BreakGlass")
class BreakGlassTest {

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("tags the full lifecycle as observations sharing one label set")
    void lifecycleStages() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        for (final BreakGlass.Stage stage : BreakGlass.Stage.values()) {
            observations.record("clinical.override",
                    KeyValues.of(stage.tag()).and("resource_class", "patient_record"), () -> {
                    });
        }

        for (final BreakGlass.Stage stage : BreakGlass.Stage.values()) {
            assertThat(meters.get("clinical.override")
                    .tag("break_glass", stage.tagValue())
                    .tag("resource_class", "patient_record")
                    .tag("outcome", "success")
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("records review lag with verdicts and clamps skew")
    void reviewLag() {
        BreakGlass.recordReviewLag(meters, "clinical.override.review_lag",
                KeyValues.of("cause", "Clinical-Emergency"), Duration.ofHours(20),
                BreakGlass.ReviewVerdict.JUSTIFIED);
        BreakGlass.recordReviewLag(meters, "clinical.override.review_lag",
                KeyValues.empty(), Duration.ofSeconds(-1), BreakGlass.ReviewVerdict.UNJUSTIFIED);
        assertThatCode(() -> BreakGlass.recordReviewLag(meters, "clinical.none",
                KeyValues.empty(), null, BreakGlass.ReviewVerdict.INCONCLUSIVE))
                .doesNotThrowAnyException();

        assertThat(meters.get("clinical.override.review_lag")
                .tag("verdict", "justified")
                .tag("cause", "clinical_emergency")
                .timer().totalTime(TimeUnit.HOURS)).isEqualTo(20.0);
        assertThat(meters.get("clinical.override.review_lag")
                .tag("verdict", "unjustified")
                .timer().totalTime(TimeUnit.SECONDS)).isZero();
        assertThat(meters.find("clinical.none").timer()).isNull();
    }

    @Test
    @DisplayName("routes the review-overdue reason as ticket")
    void overdueReason() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        assertThatThrownBy(() -> observations.record("breakglass.sweep",
                KeyValues.empty(), () -> {
                    throw new OverdueException();
                })).isInstanceOf(OverdueException.class);

        assertThat(meters.get("breakglass.sweep")
                .tag("reason", "review_overdue")
                .tag("alertability", "ticket")
                .timer().count()).isEqualTo(1);
    }

    private static final class OverdueException extends RuntimeException implements OutcomeReasonSource {

        @Override
        public OutcomeReason outcomeReason() {
            return BreakGlass.BreakGlassReason.REVIEW_OVERDUE;
        }
    }
}
