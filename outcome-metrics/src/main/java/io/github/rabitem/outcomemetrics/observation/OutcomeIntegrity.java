package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed integrity classification of a successful result.
 *
 * <p>An operation can complete technically ({@code outcome=success}) while delivering a degraded or
 * empty business result: a blank PDF, a swallowed webhook, a partial write. Integrity is a parallel
 * classification so such quiet failures become alertable without redefining {@code outcome=failure}
 * for SLOs that care about technical completion.
 *
 * <p>The vocabulary is deliberately closed and low-cardinality. What counts as degraded or empty is
 * the caller's domain knowledge, supplied through an {@link IntegrityClassifier}.
 *
 * @since 0.1.0
 */
public enum OutcomeIntegrity {

    /** The result is complete and trustworthy. */
    OK("ok"),

    /** The result was delivered but is incomplete or of reduced quality. */
    DEGRADED("degraded"),

    /** The operation succeeded technically but delivered no usable result. */
    EMPTY("empty");

    private final String tagValue;

    OutcomeIntegrity(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code integrity} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
