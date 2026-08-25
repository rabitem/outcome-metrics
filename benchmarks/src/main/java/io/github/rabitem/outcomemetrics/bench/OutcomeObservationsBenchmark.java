package io.github.rabitem.outcomemetrics.bench;

import io.github.rabitem.outcomemetrics.MetricsTags;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Instrumentation overhead for a trivial unit of work.
 *
 * <p>Compares no metrics, raw Micrometer {@link Timer}, raw Micrometer {@link Observation},
 * and {@link OutcomeObservations} success / failure / classified-success paths.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class OutcomeObservationsBenchmark {

    private MeterRegistry meterRegistry;
    private ObservationRegistry observationRegistry;
    private OutcomeObservations observations;
    private Timer timer;
    private KeyValues dimensions;

    @Setup(Level.Trial)
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        observations = new OutcomeObservations(observationRegistry);
        timer = meterRegistry.timer("bench.raw.timer", "op", "work");
        dimensions = KeyValues.of("op", "work");
    }

    @Benchmark
    public int baseline(final Blackhole blackhole) {
        return work(blackhole);
    }

    @Benchmark
    public int micrometerTimer(final Blackhole blackhole) {
        return timer.record(() -> work(blackhole));
    }

    @Benchmark
    public int micrometerObservation(final Blackhole blackhole) {
        return Observation.createNotStarted("bench.raw.observation", observationRegistry)
                .lowCardinalityKeyValue("op", "work")
                .observe(() -> work(blackhole));
    }

    @Benchmark
    public int outcomeObservationsSuccess(final Blackhole blackhole) {
        return observations.record("bench.outcome.success", dimensions, () -> work(blackhole));
    }

    @Benchmark
    public int outcomeObservationsFailure(final Blackhole blackhole) {
        try {
            return observations.record("bench.outcome.failure", dimensions, () -> {
                work(blackhole);
                throw new BenchFailure();
            });
        } catch (final BenchFailure ignored) {
            return -1;
        }
    }

    @Benchmark
    public int outcomeObservationsClassified(final Blackhole blackhole) {
        return observations.record(
                "bench.outcome.classified",
                dimensions,
                () -> work(blackhole),
                value -> MetricsTags.pairs("result=ok"),
                "result");
    }

    private static int work(final Blackhole blackhole) {
        // Keep the payload tiny so the measurement is dominated by instrumentation.
        blackhole.consume(1);
        return 1;
    }

    private static final class BenchFailure extends RuntimeException implements OutcomeReasonSource {
        private static final OutcomeReason REASON = () -> "bench_failure";

        private BenchFailure() {
            // stack traces dominate failure-path cost; suppress for apples-to-apples instrumentation cost
            super(null, null, false, false);
        }

        @Override
        public OutcomeReason outcomeReason() {
            return REASON;
        }
    }
}
