package io.github.rabitem.outcomemetrics.samples.quarkus.domain;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;

public final class ShipmentFailedException extends RuntimeException implements OutcomeReasonSource {

    private final transient ShipmentReason reason;

    public ShipmentFailedException(final ShipmentReason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    @Override
    public OutcomeReason outcomeReason() {
        return reason;
    }
}
