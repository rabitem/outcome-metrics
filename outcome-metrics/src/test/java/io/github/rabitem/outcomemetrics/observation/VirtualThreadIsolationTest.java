package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof for #40: per JEP 444, thread-locals are confined to their virtual thread — carriers never
 * expose one virtual thread's {@link OutcomeScope} to another. Thousands of scoped virtual threads
 * over a handful of shared carriers must show zero cross-thread contamination.
 */
@DisplayName("OutcomeScope on virtual threads")
class VirtualThreadIsolationTest {

    private static final int VIRTUAL_THREADS = 2_000;

    @Test
    @DisplayName("keeps scopes isolated across virtual threads sharing carriers")
    void scopesAreConfinedToTheirVirtualThread() throws Exception {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<?>> tasks = new ArrayList<>(VIRTUAL_THREADS);
            for (int i = 0; i < VIRTUAL_THREADS; i++) {
                tasks.add(virtualThreads.submit(() -> {
                    // each virtual thread opens its own scope and records the same series twice
                    try (OutcomeScope scope = OutcomeScope.open()) {
                        observations.record("loom.op", KeyValues.of("channel", "vt"), () -> {
                            Thread.yield(); // encourage carrier switching mid-scope
                        });
                        observations.record("loom.op", KeyValues.of("channel", "vt"), () -> {
                        });
                    }
                }));
            }
            for (final Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        }

        // Isolation holds iff every virtual thread saw exactly its own scope: one first and one
        // repeat each. Any bleed across carriers would skew the split.
        assertThat(meters.get("loom.op")
                .tag("occurrence", "first")
                .timer().count()).isEqualTo(VIRTUAL_THREADS);
        assertThat(meters.get("loom.op")
                .tag("occurrence", "repeat")
                .timer().count()).isEqualTo(VIRTUAL_THREADS);
    }
}
