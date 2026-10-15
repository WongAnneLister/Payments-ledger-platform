package com.paymentsledger.bank.domain;

import java.time.Instant;
import java.util.List;

/**
 * A validated simulated bank statement in canonical form.
 *
 * <p>{@code canonicalJson} is the exact text whose SHA-256 is stored as {@code digest}; reimporting the same evidence
 * in any key, line or reference order produces the same bytes, which is what makes reimport idempotent.
 */
public record BankStatement(
        String fixtureId,
        String bankAccountRef,
        String currency,
        Instant coverageFrom,
        Instant coverageUntil,
        boolean complete,
        List<BankStatementLine> lines,
        String canonicalJson,
        String digest) {
    public BankStatement {
        lines = List.copyOf(lines);
    }
}
