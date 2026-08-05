package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remaps overflowing tag values to {@link MetricTagValues#OTHER} once configured limits are exceeded.
 *
 * <p>Meters stay visible (unlike {@code DENY}), so cardinality explosions become a single observable
 * overflow bucket instead of silent disappearance.
 */
final class BoundedTagValueMeterFilter implements MeterFilter {

    private final List<MeterTagLimit> limits;
    private final ConcurrentHashMap<MeterTagLimit, Set<String>> observedValues = new ConcurrentHashMap<>();

    BoundedTagValueMeterFilter(final List<MeterTagLimit> limits) {
        this.limits = List.copyOf(Objects.requireNonNull(limits, "limits must not be null"));
    }

    @Override
    public Meter.@NonNull Id map(final Meter.@NonNull Id id) {
        if (limits.isEmpty()) {
            return id;
        }
        List<Tag> replacement = null;
        for (final MeterTagLimit limit : limits) {
            if (!id.getName().startsWith(limit.meterNamePrefix())) {
                continue;
            }
            final String current = id.getTag(limit.tagKey());
            if (current == null) {
                continue;
            }
            final String mapped = mapValue(limit, current);
            if (mapped.equals(current)) {
                continue;
            }
            if (replacement == null) {
                replacement = new ArrayList<>(id.getTags());
            }
            for (int i = 0; i < replacement.size(); i++) {
                final Tag tag = replacement.get(i);
                if (tag.getKey().equals(limit.tagKey())) {
                    replacement.set(i, Tag.of(limit.tagKey(), mapped));
                }
            }
        }
        return replacement == null ? id : id.replaceTags(replacement);
    }

    private String mapValue(final MeterTagLimit limit, final String tagValue) {
        final Set<String> values = observedValues.computeIfAbsent(limit, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (values) {
            if (values.contains(tagValue)) {
                return tagValue;
            }
            if (values.size() >= limit.maximumValues()) {
                return MetricTagValues.OTHER;
            }
            values.add(tagValue);
            return tagValue;
        }
    }
}
