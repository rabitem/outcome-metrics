package io.github.rabitem.outcomemetrics.observation;

/**
 * Classifies a successful result into a closed {@link IdempotencyOutcome}.
 *
 * <p>A classifier must return a non-{@code null} value; returning {@code null} fails the
 * observation, and a thrown exception propagates and records a failure — the same fail-loud contract
 * as {@link IntegrityClassifier}.
 *
 * @param <T> result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface IdempotencyClassifier<T> {

    /**
     * Classifies a successful result.
     *
     * @param result the successful result; may be {@code null} if the operation can return {@code null}
     * @return the idempotency disposition, never {@code null}
     */
    IdempotencyOutcome classify(T result);
}
