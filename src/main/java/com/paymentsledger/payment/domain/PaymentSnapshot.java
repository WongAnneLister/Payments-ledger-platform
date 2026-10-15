package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** The locked payment row as evidence merging sees it. */
public record PaymentSnapshot(
        UUID id,
        UUID organizationId,
        UUID environmentId,
        UUID applicationId,
        UUID providerAccountId,
        String currency,
        long amountMinor,
        long version,
        PaymentStatus status,
        String providerStatus,
        ResolutionStatus resolution,
        FinancialStatus financial,
        String checkoutSessionId,
        String paymentIntentId,
        Instant lastProviderConfirmedAt,
        Instant lastWebhookCreatedAt,
        Instant lastQueryConfirmedAt) {}
