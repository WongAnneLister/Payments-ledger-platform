package com.paymentsledger.payment.domain;

import java.time.Instant;

/** One recovery task of a payment, with its operation's last provider query. */
public record RecoveryTaskView(
        String status, int attemptCount, Instant availableAt, Instant leaseUntil, String reason, Instant lastQueryAt) {}
