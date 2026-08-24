package io.github.rabitem.outcomemetrics.observation;

/**
 * Classifies a successful reconciliation pass into a closed {@link ReconcileDisposition}.
 *
 * <p>A classifier must return a non-{@code null} value; returning {@code null} fails the
 * observation, and a thrown exception propagates and records a failure — the same fail-loud contract
 * as {@link IntegrityClassifier} and {@link IdempotencyClassifier}.
 *
 * @param <T> result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface ReconcileClassifier<T> {

    /**
     * Classifies a successful reconciliation result.
     *
     * @param result the successful result; may be {@code null} if the operation can return {@code null}
     * @return the reconciliation finding, never {@code null}
     */
    ReconcileDisposition classify(T result);
}
