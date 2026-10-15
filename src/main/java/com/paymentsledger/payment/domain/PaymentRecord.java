package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A payment row with its business order key, as the API reads it and as cancellation checks it. */
public record PaymentRecord(
        UUID id,
        UUID businessOrderId,
        String orderKey,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String providerStatus,
        ResolutionStatus resolution,
        FinancialStatus financial,
        long capturedAmountMinor,
        String checkoutSessionId,
        String paymentIntentId,
        String latestChargeId,
        String hostedUrl,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastProviderConfirmedAt) {}
