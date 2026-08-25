package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
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
    /**
     * Drops Micrometer's handler-added {@code error} tag from outcome meters (issue #97).
     *
     * <p>{@code DefaultMeterObservationHandler} stamps every observation-backed meter with an
     * {@code error} tag holding {@code none} or the throwable's <em>simple class name</em> — an
     * uncontrolled open vocabulary riding beside this library's closed schema: any new exception
     * type mints a new series, and class names can leak internals. The information is redundant
     * here: {@code outcome} already says failure and {@code reason} already says why, through the
     * registry/budget pipeline.
     *
     * <p>Precisely scoped: the tag is removed only from meters that also carry the {@code outcome}
     * tag, so foreign observation metrics (for example {@code http.server.requests}) keep their
     * {@code error} tag untouched. Dropped uniformly per outcome meter name, label sets stay
     * consistent. Static drop at the filter layer is legitimate — nothing ever needs to promote.
     * Wired by default in the Spring starter and Quarkus extension
     * ({@code outcome.metrics.drop-error-tag=false} to keep the raw tag).
     *
     * @return meter filter removing {@code error} from outcome meters
     */
    public static MeterFilter dropRedundantErrorTag() {
        return new MeterFilter() {
            @Override
            public Meter.@NonNull Id map(final Meter.@NonNull Id id) {
                if (id.getTag("outcome") == null || id.getTag("error") == null) {
                    return id;
                }
                Tags kept = Tags.empty();
                for (final Tag tag : id.getTags()) {
                    if (!"error".equals(tag.getKey())) {
                        kept = kept.and(tag);
                    }
                }
                return id.replaceTags(kept);
            }
        };
    }

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
     * Creates a tag-value cardinality filter that remaps overflows to {@link MetricTagValues#OTHER}.
     *
     * <p>The returned filter implements {@link OverflowAwareMeterFilter} so callers can expose
     * {@link OverflowAwareMeterFilter#overflowCount()} as a gauge.
     *
     * @param limits tag-value limits; must not be {@code null}
     * @return a tag-value cardinality filter, never {@code null}
     */
    public static OverflowAwareMeterFilter boundedTagValues(final List<MeterTagLimit> limits) {
        return new BoundedTagValueMeterFilter(limits);
    }
}
