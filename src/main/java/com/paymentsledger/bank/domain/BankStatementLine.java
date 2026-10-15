package com.paymentsledger.bank.domain;

import java.time.Instant;
import java.util.List;

/** One validated line of a simulated bank statement; payout references are sorted and unique. */
public record BankStatementLine(
        String lineId,
        BankLineKind kind,
        Instant bookedAt,
        long principalMinor,
        long feeMinor,
        List<String> payoutReferences) {
    public BankStatementLine {
        payoutReferences = List.copyOf(payoutReferences);
    }
}
