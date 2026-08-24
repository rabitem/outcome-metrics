package io.github.rabitem.outcomemetrics.mutiny;

import io.github.rabitem.outcomemetrics.observation.DeferredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.Objects;

/**
 * Terminal-signal outcome binding for Mutiny pipelines — the {@code Uni}/{@code Multi} mirror of
 * the Reactor module (#39/#81).
 *
 * <p>Observations settle on item/failure/cancellation instead of at pipeline assembly. One
 * observation per <em>subscription</em>: a retry is two attempts. Cancellation records
 * {@code reason=cancelled, alertability=none} (schema floor). An unterminated {@code Multi} is a
 * never-stopped observation — bind at request/operation granularity.
 *
 * @since 0.1.0
 */
public final class MutinyOutcomes {

    private MutinyOutcomes() {
    }

    /**
     * Binds a {@link Uni} to a terminal-settled observation.
     *
     * @param <T>          item type
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param source       pipeline to bind; must not be {@code null}
     * @return the bound pipeline, observing each subscription
     */
    public static <T> Uni<T> record(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Uni<T> source) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return Uni.createFrom().deferred(() -> {
            final DeferredOutcome outcome = observations.startDeferred(name, dimensions);
            return source
                    .onItem().invoke(item -> outcome.succeed())
                    .onFailure().invoke(outcome::fail)
                    .onCancellation().invoke(outcome::cancel);
        });
    }

    /**
     * Binds a {@link Multi} to a terminal-settled observation (completion after all items is the
     * success terminal).
     *
     * @param <T>          item type
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param source       pipeline to bind; must not be {@code null}
     * @return the bound pipeline, observing each subscription
     */
    public static <T> Multi<T> record(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Multi<T> source) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return Multi.createFrom().deferred(() -> {
            final DeferredOutcome outcome = observations.startDeferred(name, dimensions);
            return source
                    .onCompletion().invoke(outcome::succeed)
                    .onFailure().invoke(outcome::fail)
                    .onCancellation().invoke(outcome::cancel);
        });
    }

    /**
     * Returns whether a method return type is a Mutiny-bindable pipeline.
     *
     * @param returnType declared return type; may be {@code null}
     * @return {@code true} for {@link Uni} or {@link Multi} subtypes
     */
    public static boolean isReactiveReturn(final Class<?> returnType) {
        return returnType != null
                && (Uni.class.isAssignableFrom(returnType) || Multi.class.isAssignableFrom(returnType));
    }

    /**
     * Binds a pipeline of unknown concrete type (Uni or Multi) for annotation interceptors.
     *
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param pipeline     the value returned by the intercepted method; may be {@code null}
     * @return the bound pipeline, or the value unchanged when it is not a Uni or Multi
     */
    public static Object bind(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Object pipeline) {
        if (pipeline instanceof Uni<?> uni) {
            return record(observations, name, dimensions, uni);
        }
        if (pipeline instanceof Multi<?> multi) {
            return record(observations, name, dimensions, multi);
        }
        return pipeline;
    }
}
