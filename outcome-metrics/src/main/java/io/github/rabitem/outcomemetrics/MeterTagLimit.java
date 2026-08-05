package io.github.rabitem.outcomemetrics;

import java.util.Objects;

/**
 * Cardinality limit for one metric-name prefix and one tag key.
 *
 * @param meterNamePrefix metric name prefix to match; must not be blank
 * @param tagKey          tag key to count; must not be blank
 * @param maximumValues   maximum distinct tag values to allow; must be positive
 * @since 0.1.0
 */
public record MeterTagLimit(String meterNamePrefix, String tagKey, int maximumValues) {

    public MeterTagLimit {
        Objects.requireNonNull(meterNamePrefix, "meterNamePrefix must not be null");
        Objects.requireNonNull(tagKey, "tagKey must not be null");
        if (meterNamePrefix.isBlank()) {
            throw new IllegalArgumentException("meterNamePrefix must not be blank");
        }
        if (tagKey.isBlank()) {
            throw new IllegalArgumentException("tagKey must not be blank");
        }
        if (maximumValues < 1) {
            throw new IllegalArgumentException("maximumValues must be positive");
        }
        meterNamePrefix = meterNamePrefix.strip();
        tagKey = tagKey.strip();
    }
}
