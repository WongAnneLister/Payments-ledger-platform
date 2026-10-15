package com.paymentsledger.bank.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored bank statement line with its running principal and fee allocation. */
public record BankLine(
        UUID id,
        UUID organizationId,
        UUID environmentId,
        UUID providerAccountId,
        boolean livemode,
        String currency,
        BankLineKind kind,
        Instant bookedAt,
        long principalMinor,
        long feeMinor,
        long allocatedMinor,
        long feeAllocatedMinor,
        List<String> payoutReferences) {
    public BankLine {
        payoutReferences = List.copyOf(payoutReferences);
    }

    public long unallocatedMinor() {
        return Math.subtractExact(principalMinor, allocatedMinor);
    }
}
