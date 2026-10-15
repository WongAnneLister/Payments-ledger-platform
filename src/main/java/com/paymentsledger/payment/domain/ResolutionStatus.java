package com.paymentsledger.payment.domain;

/**
 * platform.payment.resolution_status: how certain the platform is of the payment status. UNKNOWN is not a failure; it
 * means the provider's outcome is not yet confirmed and must be queried (ADR-0003).
 */
public enum ResolutionStatus {
    KNOWN,
    UNKNOWN,
    CONFLICT
}
