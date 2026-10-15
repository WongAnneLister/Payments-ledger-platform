package com.paymentsledger.bank.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The principal buckets of one payout that bank matching moves between.
 *
 * <p>Outgoing principal sits in transit until the bank confirms it; a PSP return after a bank receipt is parked in
 * suspense until an explicit bank clawback.
 */
public record PayoutPosition(
        UUID id,
        String providerPayoutId,
        String currency,
        Instant arrivalDate,
        boolean conflict,
        BankMatchState matchState,
        long outgoingMinor,
        long transitMinor,
        long bankConfirmedMinor,
        long returnedMinor,
        long suspenseMinor) {}
