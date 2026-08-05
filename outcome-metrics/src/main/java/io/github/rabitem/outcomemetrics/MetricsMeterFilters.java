package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Factory methods for reusable Micrometer meter filters.
 *
 * @since 0.1.0
 */
public final class MetricsMeterFilters {

    private MetricsMeterFilters() {
    }

    /**
     * Creates a meter filter that removes selected tags from cache meters.
     *
     * @param removedTags tag keys to remove from meter names starting with {@code cache.}; must not be
     *                    {@code null}
     * @return a cache tag-normalizing filter, never {@code null}
     */
    public static MeterFilter cacheTagNormalizer(final Set<String> removedTags) {
        final Set<String> tagsToRemove = Set.copyOf(Objects.requireNonNull(removedTags, "removedTags must not be null"));
        return new MeterFilter() {
            @Override
            public Meter.@NonNull Id map(final Meter.@NonNull Id id) {
                if (!id.getName().startsWith("cache.")) {
                    return id;
                }
                return id.replaceTags(id.getTags().stream()
                        .filter(tag -> !tagsToRemove.contains(tag.getKey()))
                        .toList());
            }
        };
    }

    /**
     * Creates a last-resort total meter count ceiling.
     *
     * @param maxMeters maximum number of meters; must be positive
     * @return a meter-count ceiling, never {@code null}
     */
    public static MeterFilter maximumAllowableMetrics(final int maxMeters) {
        if (maxMeters < 1) {
            throw new IllegalArgumentException("maxMeters must be positive");
        }
        return MeterFilter.maximumAllowableMetrics(maxMeters);
    }

    /**
     * Creates a tag-value cardinality filter.
     *
     * @param limits tag-value limits; must not be {@code null}
     * @return a tag-value cardinality filter, never {@code null}
     */
    public static MeterFilter boundedTagValues(final List<MeterTagLimit> limits) {
        return new BoundedTagValueMeterFilter(limits);
    }
}
