package io.github.rabitem.outcomemetrics.observation;

/**
 * Stable low-cardinality machine code explaining why an instrumented operation ended the way it did.
 *
 * @since 0.1.0
 */
public interface OutcomeReason {

    /**
     * Returns the stable machine code used as the {@code reason} tag.
     *
     * @return lower-case, snake-case-or-similar reason code, never {@code null} or blank
     */
    String code();

    /**
     * Returns how failures with this reason should be routed by alerting.
     *
     * <p>Defaults to {@link Alertability#PAGE}: a reason is treated as actionable until its author
     * explicitly downgrades it. Override for expected failures such as business declines or client
     * aborts.
     *
     * @return alertability level, never {@code null}; a {@code null} return is treated as
     * {@link Alertability#PAGE}
     */
    default Alertability alertability() {
        return Alertability.PAGE;
    }
}
