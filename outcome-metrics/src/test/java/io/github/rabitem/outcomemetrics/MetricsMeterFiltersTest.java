package io.github.rabitem.outcomemetrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MetricsMeterFilters")
class MetricsMeterFiltersTest {

    @Test
    @DisplayName("removes selected high-cardinality tags from cache meters")
    void cacheTags() {
        final MeterFilter filter = MetricsMeterFilters.cacheTagNormalizer(Set.of("cache.manager", "name"));
        final Meter.Id id = id("cache.gets", Tags.of(
                "cache", "geocoding-results",
                "cache.manager", "geocoding",
                "name", "geocoding-results",
                "result", "miss"));

        final Meter.Id mapped = filter.map(id);

        assertThat(mapped.getTag("cache")).isEqualTo("geocoding-results");
        assertThat(mapped.getTag("result")).isEqualTo("miss");
        assertThat(mapped.getTag("cache.manager")).isNull();
        assertThat(mapped.getTag("name")).isNull();
    }

    @Test
    @DisplayName("leaves non-cache meters unchanged")
    void nonCacheTags() {
        final MeterFilter filter = MetricsMeterFilters.cacheTagNormalizer(Set.of("name"));
        final Meter.Id id = id("http.server.requests", Tags.of("name", "api", "status", "200"));

        final Meter.Id mapped = filter.map(id);

        assertThat(mapped.getTag("name")).isEqualTo("api");
        assertThat(mapped.getTag("status")).isEqualTo("200");
    }

    @Test
    @DisplayName("remaps overflowing tag values to other and counts overflows")
    void tagValueLimit() {
        final OverflowAwareMeterFilter filter = MetricsMeterFilters.boundedTagValues(List.of(
                new MeterTagLimit("websocket.", "destination", 2)));

        assertThat(filter.map(id("websocket.send", Tags.of("destination", "admin")))
                .getTag("destination")).isEqualTo("admin");
        assertThat(filter.map(id("websocket.send", Tags.of("destination", "public")))
                .getTag("destination")).isEqualTo("public");
        assertThat(filter.map(id("websocket.send", Tags.of("destination", "admin")))
                .getTag("destination")).isEqualTo("admin");
        assertThat(filter.map(id("websocket.send", Tags.of("destination", "private")))
                .getTag("destination")).isEqualTo(MetricTagValues.OTHER);
        assertThat(filter.overflowCount()).isEqualTo(1);
        assertThat(filter.map(id("http.server.requests", Tags.of("destination", "private")))
                .getTag("destination")).isEqualTo("private");
        assertThat(filter.map(id("websocket.send", Tags.of("status", "ok")))
                .getTag("status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("keeps overflow mapping race-safe under concurrent first-seen values")
    void tagValueLimitConcurrent() throws Exception {
        final MeterFilter filter = MetricsMeterFilters.boundedTagValues(List.of(
                new MeterTagLimit("job.", "type", 8)));
        final int threads = 32;
        final CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            final List<Future<String>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return filter.map(id("job.run", Tags.of("type", "t-" + i))).getTag("type");
                    }))
                    .toList();
            start.countDown();
            final List<String> values = futures.stream().map(f -> {
                try {
                    return f.get(5, TimeUnit.SECONDS);
                } catch (final Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).toList();
            final long distinctNonOther = values.stream()
                    .filter(v -> !MetricTagValues.OTHER.equals(v))
                    .distinct()
                    .count();
            assertThat(distinctNonOther).isLessThanOrEqualTo(8);
            assertThat(values).contains(MetricTagValues.OTHER);
        }
    }

    @Test
    @DisplayName("validates meter and tag limits")
    void invalidLimits() {
        assertThatThrownBy(() -> MetricsMeterFilters.maximumAllowableMetrics(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxMeters must be positive");

        assertThatThrownBy(() -> new MeterTagLimit("cache.", "name", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumValues must be positive");

        assertThatThrownBy(() -> new MeterTagLimit(" ", "name", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("meterNamePrefix must not be blank");

        assertThatThrownBy(() -> new MeterTagLimit("cache.", " ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tagKey must not be blank");

        assertThatThrownBy(() -> MetricsMeterFilters.cacheTagNormalizer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("removedTags must not be null");

        assertThatThrownBy(() -> MetricsMeterFilters.boundedTagValues(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("limits must not be null");
    }

    private Meter.Id id(final String name, final Tags tags) {
        return new Meter.Id(name, tags, null, null, Meter.Type.COUNTER);
    }
}
