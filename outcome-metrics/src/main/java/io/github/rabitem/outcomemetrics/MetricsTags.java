package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creates Micrometer key values from bounded, explicit tag pairs.
 *
 * @since 0.1.0
 */
public final class MetricsTags {

    private MetricsTags() {
    }

    /**
     * Parses {@code key=value} pairs into low-cardinality key values.
     *
     * @param pairs explicit tag pairs; must not be {@code null}
     * @return parsed key values, never {@code null}
     * @throws IllegalArgumentException if a pair is malformed or has a blank key
     */
    public static KeyValues pairs(final String... pairs) {
        Objects.requireNonNull(pairs, "pairs must not be null");
        if (pairs.length == 0) {
            return KeyValues.empty();
        }
        final List<KeyValue> values = new ArrayList<>(pairs.length);
        for (final String pair : pairs) {
            values.add(parse(pair));
        }
        return KeyValues.of(values);
    }

    /**
     * Creates a single key value.
     *
     * @param key   tag key; must not be blank
     * @param value tag value; {@code null} is normalized to {@code unknown}
     * @return a key value, never {@code null}
     * @throws IllegalArgumentException if the key is blank
     */
    public static KeyValue of(final String key, final Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Metric tag key must not be blank");
        }
        return KeyValue.of(key.strip(), MetricTagValues.sanitizeTagValue(value));
    }

    /**
     * Sanitizes every key/value in {@code tags} using {@link #of(String, Object)}.
     *
     * <p>Blank keys are dropped. {@code null} tags become empty.
     *
     * @param tags tags to sanitize; may be {@code null}
     * @return sanitized tags, never {@code null}
     */
    public static KeyValues sanitize(final KeyValues tags) {
        if (tags == null) {
            return KeyValues.empty();
        }
        final List<KeyValue> values = new ArrayList<>();
        for (final KeyValue tag : tags) {
            if (tag.getKey() == null || tag.getKey().isBlank()) {
                continue;
            }
            values.add(of(tag.getKey(), tag.getValue()));
        }
        return values.isEmpty() ? KeyValues.empty() : KeyValues.of(values);
    }

    private static KeyValue parse(final String pair) {
        if (pair == null || pair.isBlank()) {
            throw new IllegalArgumentException("Metric tag pair must not be blank");
        }
        final int separator = pair.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException("Metric tag pair must use key=value format");
        }
        final String key = pair.substring(0, separator).strip();
        final String value = pair.substring(separator + 1);
        return of(key, value);
    }
}
