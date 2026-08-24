package io.github.rabitem.outcomemetrics.reactor;

import io.github.rabitem.outcomemetrics.observation.DeferredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Objects;

/**
 * Terminal-signal outcome binding for Reactor publishers.
 *
 * <p>Observations settle on {@code onComplete}/{@code onError}/cancel instead of at publisher
 * assembly, so WebFlux SLIs stop lying. One observation per <em>subscription</em>: a retry is two
 * attempts and counts as two. Cancellation records {@code reason=cancelled, alertability=none}.
 *
 * <p>A thin adapter over {@link OutcomeObservations#startDeferred} (Micrometer's own
 * {@code Micrometer.observation()} tap binds terminals too, but does not speak this library's
 * outcome schema and enforcement pipeline). An infinite {@code Flux} is a never-stopped
 * observation — bind at request/operation granularity.
 *
 * @since 0.1.0
 */
public final class ReactorOutcomes {

    private ReactorOutcomes() {
    }

    /**
     * Binds a {@link Mono} to a terminal-settled observation.
     *
     * @param <T>          element type
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param source       publisher to bind; must not be {@code null}
     * @return the bound publisher, observing each subscription
     */
    public static <T> Mono<T> record(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Mono<T> source) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return Mono.defer(() -> {
            final DeferredOutcome outcome = observations.startDeferred(name, dimensions);
            return source
                    .doOnError(outcome::fail)
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            outcome.cancel();
                        } else if (signal == SignalType.ON_COMPLETE) {
                            outcome.succeed();
                        }
                        // ON_ERROR was settled by doOnError, which carries the throwable.
                    });
        });
    }

    /**
     * Binds a {@link Flux} to a terminal-settled observation ({@code onComplete} after all
     * elements is the success terminal).
     *
     * @param <T>          element type
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param source       publisher to bind; must not be {@code null}
     * @return the bound publisher, observing each subscription
     */
    public static <T> Flux<T> record(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Flux<T> source) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return Flux.defer(() -> {
            final DeferredOutcome outcome = observations.startDeferred(name, dimensions);
            return source
                    .doOnError(outcome::fail)
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            outcome.cancel();
                        } else if (signal == SignalType.ON_COMPLETE) {
                            outcome.succeed();
                        }
                        // ON_ERROR was settled by doOnError, which carries the throwable.
                    });
        });
    }

    /**
     * Returns whether a method return type is a Reactor-bindable publisher.
     *
     * @param returnType declared return type; may be {@code null}
     * @return {@code true} for {@link Publisher} subtypes
     */
    public static boolean isReactiveReturn(final Class<?> returnType) {
        return returnType != null && Publisher.class.isAssignableFrom(returnType);
    }

    /**
     * Binds a publisher of unknown concrete type (Mono or Flux) for annotation interceptors.
     *
     * @param observations observation helper; must not be {@code null}
     * @param name         observation name; must not be blank
     * @param dimensions   low-cardinality dimension tags; must not be {@code null}
     * @param publisher    the value returned by the intercepted method; may be {@code null}
     * @return the bound publisher, or the value unchanged when it is not a Mono or Flux
     */
    public static Object bind(
            final OutcomeObservations observations,
            final String name,
            final KeyValues dimensions,
            final Object publisher) {
        if (publisher instanceof Mono<?> mono) {
            return record(observations, name, dimensions, mono);
        }
        if (publisher instanceof Flux<?> flux) {
            return record(observations, name, dimensions, flux);
        }
        return publisher;
    }
}
