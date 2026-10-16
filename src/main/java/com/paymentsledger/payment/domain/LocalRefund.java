package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A refund row as provider facts are merged into it. */
public record LocalRefund(
        UUID id,
        UUID paymentId,
        UUID organizationId,
        UUID environmentId,
        UUID applicationId,
        UUID providerAccountId,
        String currency,
        long amountMinor,
        String source,
        String status,
        ResolutionStatus resolution,
        String providerStatus,
        String providerRefundId,
        String providerChargeId,
        String balanceTransactionId,
        String failureBalanceTransactionId,
        Instant unresolvedSince) {
    /** Reserved by this platform, as opposed to an external refund discovered at the provider. */
    public boolean local() {
        return "LOCAL".equals(source);
    }

    public boolean terminal() {
        return RefundLifecycle.terminal(status);
    }
}
