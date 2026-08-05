package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MetricsTags")
class MetricsTagsTest {

    @Test
    @DisplayName("parses explicit key-value pairs")
    void pairs() {
        final KeyValues tags = MetricsTags.pairs("target=admin", "result=NEEDS_RETRY");

        assertThat(tags).containsExactlyInAnyOrder(
                MetricsTags.of("target", "admin"),
                MetricsTags.of("result", "NEEDS_RETRY"));
        assertThat(MetricsTags.pairs()).isEmpty();
        assertThat(MetricsTags.pairs("empty=")).containsExactly(MetricsTags.of("empty", ""));
    }

    @Test
    @DisplayName("normalizes null values to unknown")
    void nullValue() {
        assertThat(MetricsTags.of("result", null).getValue()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("rejects malformed pairs and blank keys")
    void invalidInput() {
        assertThatThrownBy(() -> MetricsTags.pairs("broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric tag pair must use key=value format");

        assertThatThrownBy(() -> MetricsTags.pairs((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric tag pair must not be blank");

        assertThatThrownBy(() -> MetricsTags.pairs((String[]) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pairs must not be null");

        assertThatThrownBy(() -> MetricsTags.of(" ", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric tag key must not be blank");
    }
}
