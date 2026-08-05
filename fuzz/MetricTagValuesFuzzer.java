import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import io.github.rabitem.outcomemetrics.MetricTagValues;

/**
 * Jazzer harness for {@link MetricTagValues} tag normalization.
 *
 * <p>Kept outside {@code src/test} so OpenSSF Scorecard can detect the
 * {@code FuzzedDataProvider} import. Built by ClusterFuzzLite under
 * {@code .clusterfuzzlite/}.
 */
public final class MetricTagValuesFuzzer {

    private MetricTagValuesFuzzer() {
    }

    public static void fuzzerTestOneInput(final FuzzedDataProvider data) {
        final String value = data.consumeRemainingAsString();
        MetricTagValues.sanitizeTagValue(value);
        MetricTagValues.toSnakeCase(value);
        MetricTagValues.reasonCode(new RuntimeException(value));
    }
}
