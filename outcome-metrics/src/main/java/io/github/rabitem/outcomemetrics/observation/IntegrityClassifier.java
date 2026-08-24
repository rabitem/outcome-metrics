package io.github.rabitem.outcomemetrics.observation;

/**
 * Classifies a successful result into a closed {@link OutcomeIntegrity} value.
 *
 * <p>The classifier encodes the caller's definition of a trustworthy result, for example "the
 * rendered PDF has at least one page" or "the reconciliation touched every expected row".
 *
 * <p>A classifier must return a non-{@code null} value; returning {@code null} fails the
 * observation. A thrown exception likewise propagates and records a failure. Both rules are
 * deliberate: an integrity check that silently defaults to {@link OutcomeIntegrity#OK} would itself
 * be the kind of quiet failure this classification exists to surface.
 *
 * @param <T> result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface IntegrityClassifier<T> {

    /**
     * Classifies a successful result.
     *
     * @param result the successful result; may be {@code null} if the operation can return {@code null}
     * @return the integrity classification, never {@code null}
     */
    OutcomeIntegrity classify(T result);
}
