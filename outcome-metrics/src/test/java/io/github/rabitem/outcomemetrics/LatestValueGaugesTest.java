package io.github.rabitem.outcomemetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LatestValueGauges")
class LatestValueGaugesTest {

    @Test
    @DisplayName("updates one gauge series in place")
    void updatesSeriesInPlace() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final LatestValueGauges gauges = new LatestValueGauges(registry);
        final Tags tags = Tags.of("job_type", "CAPACITY_IMPORT", "tenant_id", "tenant-1");

        gauges.set("appointo_sync_failed_items", "Failed items", tags, 2);
        gauges.set("appointo_sync_failed_items", "Failed items", tags, 5);

        assertThat(registry.find("appointo_sync_failed_items").gauges()).hasSize(1);
        assertThat(registry.get("appointo_sync_failed_items")
                .tag("job_type", "CAPACITY_IMPORT")
                .tag("tenant_id", "tenant-1")
                .gauge()
                .value())
                .isEqualTo(5.0);
    }

    @Test
    @DisplayName("keeps separate holders for distinct tag series")
    void separatesTagSeries() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final LatestValueGauges gauges = new LatestValueGauges(registry);

        gauges.set("sync_items", "Items", Tags.of("tenant_id", "tenant-1"), 1);
        gauges.set("sync_items", "Items", Tags.of("tenant_id", "tenant-2"), 2);

        assertThat(registry.find("sync_items").gauges()).hasSize(2);
        assertThat(registry.get("sync_items").tag("tenant_id", "tenant-1").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("sync_items").tag("tenant_id", "tenant-2").gauge().value())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("rejects invalid construction and series input")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> new LatestValueGauges(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meterRegistry must not be null");

        final LatestValueGauges gauges = new LatestValueGauges(new SimpleMeterRegistry());
        assertThatThrownBy(() -> gauges.set(" ", "Items", Tags.empty(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Gauge name must not be blank");
        assertThatThrownBy(() -> gauges.set("sync_items", "Items", null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tags must not be null");
    }
}
