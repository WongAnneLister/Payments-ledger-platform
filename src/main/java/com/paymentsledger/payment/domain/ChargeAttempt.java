package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A charge already recorded as a payment attempt, keyed by the provider's charge ID within its account. */
public record ChargeAttempt(
        UUID paymentId,
        String paymentIntentId,
        String currency,
        long amountMinor,
        boolean captured,
        Long amountCapturedMinor,
        String balanceTransactionId,
        Instant fetchedAt) {}
