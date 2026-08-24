package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed disposition of an idempotency-key check for a successful operation.
 *
 * <p>At-least-once delivery makes duplicates expected: counting a skipped duplicate as failure
 * destroys SLOs, counting it as plain success hides the no-op rate. Successful idempotent operations
 * carry an {@code idempotency} tag with one of these values; failed ones carry
 * {@code idempotency=none} so the tag key stays present on every series of the observation name
 * (Prometheus requires consistent label sets per meter name).
 *
 * <p>Only genuine successes live here. A key conflict, a stale replay, or a missing key is a
 * failure — throw (for example an {@link IdempotencyException}) and let {@link IdempotencyReason}
 * carry the reason and its alertability.
 *
 * <p>Idempotency keys, message ids, and dedup-store values must never become tags — only this closed
 * disposition is tag-safe.
 *
 * @since 0.1.0
 */
public enum IdempotencyOutcome {

    /** The operation was applied for the first time. */
    APPLIED("applied"),

    /** The key was already processed; the operation was skipped as a safe no-op. */
    DUPLICATE_SKIPPED("duplicate_skipped");

    /** Tag name carrying the idempotency disposition. */
    public static final String TAG_IDEMPOTENCY = "idempotency";

    private final String tagValue;

    IdempotencyOutcome(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code idempotency} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
