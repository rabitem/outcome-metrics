package io.github.rabitem.outcomemetrics.observation;

import java.util.Objects;

/**
 * Failure of an idempotency-key check, carrying its {@link IdempotencyReason}.
 *
 * <p>Ready-made {@link OutcomeReasonSource} so handlers can fail a conflicting, stale, or key-less
 * delivery and get {@code reason} and {@code alertability} tags without boilerplate.
 *
 * @since 0.1.0
 */
public class IdempotencyException extends RuntimeException implements OutcomeReasonSource {

    private final transient IdempotencyReason reason;

    /**
     * Creates an idempotency failure.
     *
     * @param reason idempotency failure reason; must not be {@code null}
     */
    public IdempotencyException(final IdempotencyReason reason) {
        super(Objects.requireNonNull(reason, "reason must not be null").code());
        this.reason = reason;
    }

    /**
     * Creates an idempotency failure with a cause.
     *
     * @param reason idempotency failure reason; must not be {@code null}
     * @param cause  underlying cause; may be {@code null}
     */
    public IdempotencyException(final IdempotencyReason reason, final Throwable cause) {
        super(Objects.requireNonNull(reason, "reason must not be null").code(), cause);
        this.reason = reason;
    }

    @Override
    public OutcomeReason outcomeReason() {
        return reason;
    }
}
