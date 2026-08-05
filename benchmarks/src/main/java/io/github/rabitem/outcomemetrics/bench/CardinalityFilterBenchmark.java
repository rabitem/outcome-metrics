package io.github.rabitem.outcomemetrics.bench;

import io.github.rabitem.outcomemetrics.MeterTagLimit;
import io.github.rabitem.outcomemetrics.MetricsMeterFilters;
import io.github.rabitem.outcomemetrics.OverflowAwareMeterFilter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hot-path cost of the bounded tag-value {@link io.micrometer.core.instrument.config.MeterFilter}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class CardinalityFilterBenchmark {

    private OverflowAwareMeterFilter filter;
    private Meter.Id withinLimitId;
    private Meter.Id overflowId;
    private Meter.Id unaffectedId;
    private final AtomicInteger overflowSeq = new AtomicInteger();

    @Setup(Level.Trial)
    public void setup() {
        filter = MetricsMeterFilters.boundedTagValues(List.of(
                new MeterTagLimit("bench.", "channel", 8)));

        // Saturate the allowed set so subsequent novel values remap to "other".
        for (int i = 0; i < 8; i++) {
            filter.map(id("chan" + i));
        }

        withinLimitId = id("chan0");
        overflowId = id("novel");
        unaffectedId = new Meter.Id(
                "other.metric",
                Tags.of("channel", "chan0"),
                null,
                null,
                Meter.Type.TIMER);
    }

    @Benchmark
    public Meter.Id mapWithinLimit() {
        return filter.map(withinLimitId);
    }

    @Benchmark
    public Meter.Id mapOverflowSteady() {
        // Always a value not in the saturated set → remap to other (steady-state overflow path).
        return filter.map(overflowId);
    }

    @Benchmark
    public Meter.Id mapUnaffectedPrefix() {
        return filter.map(unaffectedId);
    }

    @Benchmark
    @Threads(4)
    public Meter.Id mapWithinLimitContended() {
        return filter.map(withinLimitId);
    }

    @Benchmark
    @Threads(4)
    public Meter.Id mapOverflowContended() {
        // Distinct values under contention; still remaps once the set is full.
        return filter.map(id("n" + overflowSeq.incrementAndGet()));
    }

    private static Meter.Id id(final String channel) {
        return new Meter.Id(
                "bench.order.place",
                Tags.of("channel", channel, "outcome", "success"),
                null,
                null,
                Meter.Type.TIMER);
    }
}
