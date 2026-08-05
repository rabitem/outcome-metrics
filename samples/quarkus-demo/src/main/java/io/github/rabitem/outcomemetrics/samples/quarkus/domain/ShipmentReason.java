package io.github.rabitem.outcomemetrics.samples.quarkus.domain;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;

public enum ShipmentReason implements OutcomeReason {
    CARRIER_TIMEOUT("carrier_timeout"),
    ADDRESS_INVALID("address_invalid");

    private final String code;

    ShipmentReason(final String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
