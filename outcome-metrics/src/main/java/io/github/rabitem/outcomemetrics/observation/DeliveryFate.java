package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed fate of a message delivery attempt.
 *
 * <p>Broker binders expose lag and rates, not business fate: whether a failing message will retry,
 * was dead-lettered as poison, or was deliberately dropped. The {@code fate} tag carries that
 * disposition alongside {@code outcome} and {@code reason}: success → {@link #PROCESSED}; failure →
 * the {@link DeliveryFateClassifier}'s verdict; indeterminate → {@code unknown}.
 *
 * <p>Not named {@code disposition}: that tag belongs to reconciliation findings
 * ({@link ReconcileDisposition}); reusing the key for an unrelated vocabulary would make cross-name
 * queries quietly meaningless.
 *
 * <p>Message ids, offsets, and partitions must never become tags.
 *
 * @since 0.1.0
 */
public enum DeliveryFate {

    /** The delivery was processed successfully. */
    PROCESSED("processed"),

    /** Transient failure: the message will be redelivered. */
    RETRY("retry"),

    /** Poison message: routed to a dead-letter destination. */
    DEAD_LETTER("dead_letter"),

    /** Deliberately acknowledged without processing. */
    DROP("drop");

    /** Tag name carrying the delivery fate. */
    public static final String TAG_FATE = "fate";

    private final String tagValue;

    DeliveryFate(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code fate} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
