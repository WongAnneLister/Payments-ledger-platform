package com.paymentsledger.payment.domain;

import java.time.Instant;

/** Where a payment stands with its provider, as read when a provider result is written back. */
public record PaymentProgress(
        long version,
        PaymentStatus status,
        String providerStatus,
        ResolutionStatus resolution,
        String checkoutSessionId,
        String paymentIntentId,
        long capturedMinor,
        Instant unresolvedSince) {
    /** The provider already holds a payment intent or money for this payment. */
    public boolean remotelyKnown() {
        return paymentIntentId != null || capturedMinor > 0;
    }
}
