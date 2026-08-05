package io.github.rabitem.outcomemetrics.samples.spring.domain;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;

/**
 * Closed failure vocabulary for the demo order flow.
 */
public enum OrderReason implements OutcomeReason {
    INVENTORY_SHORTAGE("inventory_shortage"),
    PAYMENT_DECLINED("payment_declined"),
    CROSS_TENANT("cross_tenant");

    private final String code;

    OrderReason(final String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
