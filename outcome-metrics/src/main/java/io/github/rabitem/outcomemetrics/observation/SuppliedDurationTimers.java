package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricsTags;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Shared mechanics for timers fed historical, store-computed durations.
 *
 * <p>No code is being timed: witness gaps (#32) and rail divergence windows (#33) are data from the
 * application's store. Emission-safe by contract: never throws, negative durations (clock skew
 * between store timestamps) clamp to zero and still record, null inputs record nothing.
 */
final class SuppliedDurationTimers {

    private SuppliedDurationTimers() {
    }

    static void record(
            final MeterRegistry meterRegistry,
            final String name,
            final KeyValues dimensions,
            final Duration duration,
            final String tagKey,
            final String tagValue) {
        if (meterRegistry == null || name == null || name.isBlank() || duration == null || tagValue == null) {
            return;
        }
        final Timer.Builder timer = Timer.builder(name.strip()).tag(tagKey, tagValue);
        for (final KeyValue dimension : MetricsTags.sanitize(dimensions)) {
            timer.tag(dimension.getKey(), dimension.getValue());
        }
        timer.register(meterRegistry).record(duration.isNegative() ? Duration.ZERO : duration);
    }
}
