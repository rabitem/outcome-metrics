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
}
