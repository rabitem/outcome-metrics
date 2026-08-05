package io.github.rabitem.outcomemetrics.observation;

/**
 * Failure signal that carries a stable {@link OutcomeReason}.
 *
 * @since 0.1.0
 */
public interface OutcomeReasonSource {

    /**
     * Returns the reason this failure represents.
     *
     * @return outcome reason, never {@code null}
     */
    OutcomeReason outcomeReason();
}
