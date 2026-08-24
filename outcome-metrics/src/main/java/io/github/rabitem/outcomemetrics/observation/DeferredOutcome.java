package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.observation.Observation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal-signal outcome binding for asynchronous work.
 *
 * <p>Wrapping a publisher or future in a synchronous {@code record(...)} times the <em>assembly</em>
 * and stamps success before anything ran. This primitive starts the observation now and settles it
 * when the terminal signal fires — from any thread, exactly once: the first of {@link #succeed()},
 * {@link #fail(Throwable)}, or {@link #cancel()} wins and later calls are no-ops (racing terminals
 * and double completion are normal in async code).
 *
 * <p>Terminal binding is not framework-specific: Reactor, Mutiny, coroutines, and
 * {@code CompletableFuture} all reduce to this. The {@code outcome-metrics-reactor} module adapts
 * Reactor; anything else can bind directly:
 *
 * <pre>{@code
 * DeferredOutcome outcome = observations.startDeferred("order.async", dims);
 * future.whenComplete((value, error) -> {
 *     if (error instanceof CancellationException) outcome.cancel();
 *     else if (error != null) outcome.fail(error);
 *     else outcome.succeed();
 * });
 * }</pre>
 *
 * <p>{@link #cancel()} records {@code outcome=failure, reason=cancelled, alertability=none}: a
 * client disconnect is an expected terminal that must wake nobody, rate-alertable on the tag;
 * {@code cancelled} is schema floor (registries admit it, budgets never charge it).
 *
 * <p>No {@code Observation.Scope} is opened — the terminal fires on another thread and a scope
 * would leak. Metrics and spans work; MDC/current-span propagation is micrometer
 * context-propagation's job; {@link OutcomeScope} fails open to {@code occurrence=first} across
 * thread hops.
 *
 * @since 0.1.0
 */
public final class DeferredOutcome {

    private final Observation observation;
    private final OutcomeObservationContext context;
    private final AtomicBoolean terminal = new AtomicBoolean();

    DeferredOutcome(final Observation observation, final OutcomeObservationContext context) {
        this.observation = observation;
        this.context = context;
    }

    /**
     * Settles the observation as a success. First terminal wins; later calls are no-ops.
     */
    public void succeed() {
        if (terminal.compareAndSet(false, true)) {
            context.markSettled();
            observation.stop();
        }
    }

    /**
     * Settles the observation as a failure. First terminal wins; later calls are no-ops.
     *
     * @param error the failure; {@code null} records an unclassified failure ({@code reason=unknown})
     */
    public void fail(final Throwable error) {
        if (terminal.compareAndSet(false, true)) {
            context.markSettled();
            observation.error(error == null ? new IllegalStateException("async failure without error") : error);
            observation.stop();
        }
    }

    /**
     * Settles the observation as cancelled ({@code reason=cancelled}, {@code alertability=none}).
     * First terminal wins; later calls are no-ops.
     */
    public void cancel() {
        if (terminal.compareAndSet(false, true)) {
            context.markSettled();
            observation.error(new CancelledOutcomeException());
            observation.stop();
        }
    }

    private static final class CancelledOutcomeException extends RuntimeException
            implements OutcomeReasonSource {

        private CancelledOutcomeException() {
            super(MetricTagValues.CANCELLED, null, false, false);
        }

        @Override
        public OutcomeReason outcomeReason() {
            return new OutcomeReason() {
                @Override
                public String code() {
                    return MetricTagValues.CANCELLED;
                }

                @Override
                public Alertability alertability() {
                    return Alertability.NONE;
                }
            };
        }
    }
}
