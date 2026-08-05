package io.github.rabitem.outcomemetrics.samples.spring.domain;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;

/**
 * Domain failure that contributes a stable {@code reason} tag.
 */
public final class OrderRejectedException extends RuntimeException implements OutcomeReasonSource {

    private final transient OrderReason reason;

    public OrderRejectedException(final OrderReason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    @Override
    public OutcomeReason outcomeReason() {
        return reason;
    }
}
