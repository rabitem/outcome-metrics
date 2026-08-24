package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.DeferredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.github.rabitem.outcomemetrics.observation.OutcomeScope;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Environment contracts for outcome propagation across async boundaries (issue #72).
 *
 * <p>The library's documented semantics: {@link OutcomeScope} is thread-confined and fails open to
 * {@code occurrence=first} across handoffs (#18/#40), and {@link DeferredOutcome} settles correctly
 * from any thread (#39). Agents or instrumented executors that <em>propagate</em> thread-locals
 * would silently break the first contract — a scope carried onto worker threads makes concurrent
 * requests coalesce into each other's dedup windows, corrupting {@code occurrence}-filtered SLIs.
 * Run these contracts in your test suite against your real executors to catch that before
 * production does.
 *
 * @since 0.1.0
 */
public final class OutcomePropagationContracts {

    private OutcomePropagationContracts() {
    }

    /**
     * Asserts that an open {@link OutcomeScope} does not leak into work run on the given executor.
     *
     * <p>Fails when the executor runs tasks on the submitting thread or an agent propagates
     * thread-locals — both make occurrence deduplication span requests, which corrupts SLIs.
     *
     * @param executor the executor your application hands work to; must not be {@code null}
     * @throws AssertionError if the scope propagated
     */
    public static void assertScopeConfinedAcrossExecutor(final Executor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final OutcomeObservations observations = observations(meters);
        try (OutcomeScope scope = OutcomeScope.open()) {
            observations.record("propagation.probe", KeyValues.empty(), () -> {
            });
            await(CompletableFuture.runAsync(
                    () -> observations.record("propagation.probe", KeyValues.empty(), () -> {
                    }), executor));
        }
        if (meters.find("propagation.probe").tags("occurrence", "repeat").timer() != null) {
            throw new AssertionError("OutcomeScope propagated across the executor: work submitted"
                    + " from inside a scope was deduplicated against the submitter's scope."
                    + " Either the executor runs tasks on the submitting thread or an agent"
                    + " propagates thread-locals — both make occurrence deduplication span"
                    + " requests and corrupt occurrence-filtered SLIs.");
        }
    }

    /**
     * Asserts that a {@link DeferredOutcome} started on this thread settles correctly from a thread
     * of the given executor, with the full outcome schema.
     *
     * @param executor the executor your terminal callbacks run on; must not be {@code null}
     * @throws AssertionError if the deferred outcome did not settle as a success exactly once
     */
    public static void assertDeferredSettlesAcrossExecutor(final Executor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final DeferredOutcome outcome = observations(meters)
                .startDeferred("propagation.deferred", KeyValues.empty());
        await(CompletableFuture.runAsync(outcome::succeed, executor));
        if (meters.find("propagation.deferred")
                .tags("outcome", "success", "occurrence", "first").timer() == null) {
            throw new AssertionError("DeferredOutcome did not settle as a success from the executor"
                    + " thread; the terminal-binding contract (#39) is broken in this environment.");
        }
    }

    private static OutcomeObservations observations(final SimpleMeterRegistry meters) {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new OutcomeObservations(registry);
    }

    private static void await(final CompletableFuture<Void> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting the propagation probe", e);
        } catch (final ExecutionException | TimeoutException e) {
            throw new AssertionError("propagation probe did not complete", e);
        }
    }
}
