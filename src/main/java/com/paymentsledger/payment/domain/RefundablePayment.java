package com.paymentsledger.payment.domain;

import java.util.UUID;

/** The payment a refund is reserved against, as locked for the reservation. */
public record RefundablePayment(
        UUID id,
        UUID organizationId,
        UUID environmentId,
        UUID applicationId,
        UUID providerAccountId,
        String currency,
        ResolutionStatus resolution,
        long capturedMinor,
        String latestChargeId) {}
