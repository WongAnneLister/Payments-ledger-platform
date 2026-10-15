package com.paymentsledger.payment.domain;

/**
 * platform.payment.payment_status: what the customer has done. There is no FAILED: a declined attempt is recorded on
 * its payment_attempt, and the customer can still pay.
 */
public enum PaymentStatus {
    CREATED,
    AWAITING_CUSTOMER,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    CANCELED
}
