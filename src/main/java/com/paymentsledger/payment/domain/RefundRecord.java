package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A refund as the read model shows it. */
public record RefundRecord(
        UUID id,
        UUID paymentId,
        UUID organizationId,
        UUID environmentId,
        UUID providerAccountId,
        long amountMinor,
        String currency,
        String status,
        ResolutionStatus resolution,
        String providerStatus,
        String providerRefundId,
        String reason,
        String balanceTransactionId,
        String failureBalanceTransactionId,
        Instant createdAt,
        Instant updatedAt,
        Instant lastProviderConfirmedAt) {}
