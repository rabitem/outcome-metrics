package io.github.rabitem.outcomemetrics.bench;

import io.github.rabitem.outcomemetrics.observation.CombinationGuard;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservationConvention;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;
import io.github.rabitem.outcomemetrics.observation.OutcomeScope;
import io.github.rabitem.outcomemetrics.observation.ReasonBudget;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Overhead of the fully composed convention pipeline versus the unenforced paths.
 *
 * <p>The convention can run a reason registry, reason budget, combination guard, and privacy
 * policy per observation; this measures what that composition costs on a trivial unit of work.
 * The payload is intentionally tiny so scores show instrumentation cost, not business work.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class PipelineOverheadBenchmark {

    private MeterRegistry meterRegistry;
    private Timer rawTimer;
    private OutcomeObservations plain;
    private OutcomeObservations enforced;
    private KeyValues dimensions;

    @Setup(Level.Trial)
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();
        rawTimer = meterRegistry.timer("bench.raw.timer", "op", "work");
        dimensions = KeyValues.of("op", "work");

        plain = new OutcomeObservations(observationRegistry());

        // Full enforcement: registry membership, cardinality budget, combination guard on the
        // dimension key (steady state = revealed path), and the privacy policy's detectors.
        final ReasonRegistry reasonRegistry = ReasonRegistry.builder().codes("bench_failure").build();
        final ReasonBudget reasonBudget = new ReasonBudget(8, 64);
        final CombinationGuard combinationGuard = CombinationGuard.builder()
                .keys("op")
                .minSupport(2)
                .window(Duration.ofHours(1))
                .build();
        final TagPrivacyPolicy privacyPolicy = TagPrivacyPolicy.saasDefaults();
        enforced = new OutcomeObservations(
                observationRegistry(),
                OutcomeObservationConvention.builder()
                        .reasonRegistry(reasonRegistry)
                        .reasonBudget(reasonBudget)
                        .combinationGuard(combinationGuard)
                        .tagPrivacyPolicy(privacyPolicy)
                        .build());
    }

    private ObservationRegistry observationRegistry() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        return registry;
    }

    @Benchmark
    public int rawMicrometerTimer(final Blackhole blackhole) {
        return rawTimer.record(() -> work(blackhole));
    }

    @Benchmark
    public int plainSuccess(final Blackhole blackhole) {
        return plain.record("bench.plain.success", dimensions, () -> work(blackhole));
    }

    @Benchmark
    public int plainReasonedFailure(final Blackhole blackhole) {
        try {
            return plain.record("bench.plain.failure", dimensions, () -> {
                work(blackhole);
                throw new BenchFailure();
            });
        } catch (final BenchFailure ignored) {
            return -1;
        }
    }

    @Benchmark
    public int enforcedSuccess(final Blackhole blackhole) {
        return enforced.record("bench.enforced.success", dimensions, () -> work(blackhole));
    }

    @Benchmark
    public int enforcedReasonedFailure(final Blackhole blackhole) {
        try {
            return enforced.record("bench.enforced.failure", dimensions, () -> {
                work(blackhole);
                throw new BenchFailure();
            });
        } catch (final BenchFailure ignored) {
            return -1;
        }
    }

    @Benchmark
    public int enforcedSuccessInScope(final Blackhole blackhole) {
        try (OutcomeScope scope = OutcomeScope.open()) {
            return enforced.record("bench.enforced.scoped", dimensions, () -> work(blackhole));
        }
    }

    @Benchmark
    public int deferredSucceed(final Blackhole blackhole) {
        final int result = work(blackhole);
        enforced.startDeferred("bench.enforced.deferred", dimensions).succeed();
        return result;
    }

    private static int work(final Blackhole blackhole) {
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
