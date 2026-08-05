package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MetricTagValues")
class MetricTagValuesTest {

    @Test
    @DisplayName("derives snake-case codes from exception class names")
    void exceptionCode() {
        final String code = MetricTagValues.exceptionCode(new IllegalStateException("boom"));

        assertThat(code).isEqualTo("illegal_state_exception");
    }

    @Test
    @DisplayName("uses reason source codes and maps other throwables to unknown")
    void reasonSource() {
        assertThat(MetricTagValues.reasonCode(new ReasonedFailure())).isEqualTo("cross_tenant");
        assertThat(MetricTagValues.reasonCode(new IllegalStateException("boom"))).isEqualTo("unknown");
        assertThat(MetricTagValues.reasonCode(new RuntimeException("wrap", new ReasonedFailure())))
                .isEqualTo("cross_tenant");
    }

    @Test
    @DisplayName("normalizes null and Unicode tag values")
    void normalizeValues() {
        assertThat(MetricTagValues.sanitizeTagValue(null)).isEqualTo("unknown");
        assertThat(MetricTagValues.toSnakeCase("  HTTPStatus-ÖPNV  ")).isEqualTo("http_status_öpnv");
        assertThat(MetricTagValues.toSnakeCase("  ")).isEqualTo("unknown");
        assertThat(MetricTagValues.toSnakeCase("!!!")).isEqualTo("unknown");
        assertThat(MetricTagValues.toSnakeCase("Cache2Miss")).isEqualTo("cache2_miss");
        assertThat(MetricTagValues.enumValue(TestStatus.NEEDS_RETRY)).isEqualTo("needs_retry");
        assertThat(MetricTagValues.enumValue(null)).isEqualTo("unknown");
        assertThat(MetricTagValues.reasonCode(null)).isEqualTo("none");
    }

    @Test
    @DisplayName("requires exception input for exception code")
    void exceptionCodeNull() {
        assertThatThrownBy(() -> MetricTagValues.exceptionCode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("error must not be null");
    }

    private enum TestStatus {
        NEEDS_RETRY
    }

    private static final class ReasonedFailure extends RuntimeException implements OutcomeReasonSource {

        @Override
        public OutcomeReason outcomeReason() {
            return () -> "cross_tenant";
        }
    }
}
