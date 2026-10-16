package com.paymentsledger.payment.domain;

/** platform.refund_control.control_state: whether new refunds may be reserved against the payment. */
public enum RefundControlState {
    NORMAL,
    /** The provider reported refunds that disagree with local records; an audited unfreeze is required. */
    EXTERNAL_CONFLICT,
    DISPUTE_BLOCKED
}
