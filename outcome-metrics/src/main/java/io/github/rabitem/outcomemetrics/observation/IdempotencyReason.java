package io.github.rabitem.outcomemetrics.observation;

/**
 * Failure reasons for idempotency-key checks, with routing baked in.
 *
 * <p>These are the idempotency dispositions that are <em>not</em> successes: they signal an upstream
 * bug or an unverifiable redelivery, not a safe no-op. Throw them via {@link IdempotencyException}
 * (or any {@link OutcomeReasonSource}) so the {@code reason} and {@code alertability} tags are
 * derived automatically.
 *
 * @since 0.1.0
 */
public enum IdempotencyReason implements OutcomeReason {

    /**
     * Same key, different payload: an upstream producer bug threatening data integrity. Pages.
     */
    CONFLICT("idempotency_conflict", Alertability.PAGE),

    /**
     * Redelivery older than the deduplication window: cannot be verified as duplicate or new.
     * Tickets.
     */
    STALE_REPLAY("stale_replay", Alertability.TICKET),

    /**
     * The producer sent no idempotency key: broken contract. Tickets.
     */
    KEY_MISSING("idempotency_key_missing", Alertability.TICKET);

    private final String code;
    private final Alertability alertability;

    IdempotencyReason(final String code, final Alertability alertability) {
        this.code = code;
        this.alertability = alertability;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Alertability alertability() {
        return alertability;
    }
}
