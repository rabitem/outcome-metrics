package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains last-value gauges keyed by metric name and explicit low-cardinality tags.
 *
 * <p>Micrometer gauges keep only a weak reference to observed state unless the registry
 * retains the state holder. This helper owns those holders and updates them by series.
 * Callers must keep tag values bounded; pair with {@link MetricsMeterFilters#boundedTagValues(List)}
 * for untrusted dimensions. A hard series cap prevents unbounded holder growth.
 *
 * @since 0.1.0
 */
public final class LatestValueGauges {

    /** Default maximum distinct gauge series retained in memory. */
    public static final int DEFAULT_MAX_SERIES = 10_000;

    private final MeterRegistry meterRegistry;
    private final int maxSeries;
    private final ConcurrentMap<GaugeSeries, AtomicLong> holders = new ConcurrentHashMap<>();
    private final Object createLock = new Object();

    /**
     * Creates a registry-backed gauge helper with {@link #DEFAULT_MAX_SERIES}.
     *
     * @param meterRegistry Micrometer registry; must not be {@code null}
     */
    public LatestValueGauges(final MeterRegistry meterRegistry) {
        this(meterRegistry, DEFAULT_MAX_SERIES);
    }

    /**
     * Creates a registry-backed gauge helper.
     *
     * @param meterRegistry Micrometer registry; must not be {@code null}
     * @param maxSeries     maximum distinct series to retain; must be positive
     */
    public LatestValueGauges(final MeterRegistry meterRegistry, final int maxSeries) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        if (maxSeries < 1) {
            throw new IllegalArgumentException("maxSeries must be positive");
        }
        this.maxSeries = maxSeries;
    }

    /**
     * Sets the current value for one gauge series, creating the gauge on first use.
     *
     * @param name        meter name; must not be blank
     * @param description meter description; may be {@code null}
     * @param tags        explicit low-cardinality tags; must not be {@code null}
     * @param value       current value to expose
     * @throws IllegalStateException if a new series would exceed {@code maxSeries}
     */
    public void set(final String name, final String description, final Iterable<Tag> tags, final long value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Gauge name must not be blank");
        }
        final List<Tag> tagList = Tags.of(Objects.requireNonNull(tags, "tags must not be null")).stream().toList();
        final GaugeSeries series = new GaugeSeries(name.strip(), tagList);
        final AtomicLong existing = holders.get(series);
        if (existing != null) {
            existing.set(value);
            return;
        }
        synchronized (createLock) {
            final AtomicLong raced = holders.get(series);
            if (raced != null) {
                raced.set(value);
                return;
            }
            if (holders.size() >= maxSeries) {
                throw new IllegalStateException(
                        "LatestValueGauges series limit exceeded (" + maxSeries + "); reduce tag cardinality");
            }
            final AtomicLong fresh = new AtomicLong(value);
            Gauge.builder(series.name(), fresh, AtomicLong::doubleValue)
                    .description(description)
                    .tags(tagList)
                    .register(meterRegistry);
            holders.put(series, fresh);
        }
    }

    /**
     * Returns how many distinct series are currently retained.
     *
     * @return series count
     */
    public int seriesCount() {
        return holders.size();
    }

    private record GaugeSeries(String name, List<Tag> tags) {

        private GaugeSeries {
            name = name.strip();
            tags = List.copyOf(tags);
        }
    }
}
