package com.paymentsledger.bank.domain;

import com.paymentsledger.provider.ProviderScope;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure rules for allocating bank statement principal to payouts and for judging bank evidence completeness.
 *
 * <p>A credit line confirms principal that left the PSP: it moves principal from transit to bank cash, with the line's
 * bank fee allocated pro rata. A return line claws back principal the bank had confirmed but the PSP later returned;
 * that principal waits in suspense until then. A bank receipt that overlaps a PSP return cannot be settled
 * automatically and is recorded as a conflict instead.
 */
public final class BankMatchRules {
    /** A bank booking may differ from the provider's arrival date by at most this many UTC dates. */
    public static final long BOOKING_WINDOW_DAYS = 7;

    private BankMatchRules() {}

    /** Ledger account purpose and signed amount (debit positive) of one posting line. */
    public record Posting(String purpose, long amountMinor) {}

    public sealed interface Allocation {}

    /** The receipt overlaps a PSP return: record a conflict case, post nothing. */
    public record ReturnOverlap() implements Allocation {}

    /** Post {@code postings} and move the payout and line to the new positions. */
    public record Settlement(
            String matchKind,
            String eventKey,
            String postingType,
            long feeMinor,
            long lineAllocatedMinor,
            long lineFeeAllocatedMinor,
            long transitMinor,
            long bankConfirmedMinor,
            long returnedMinor,
            long suspenseMinor,
            BankMatchState state,
            List<Posting> postings)
            implements Allocation {}

    public static String matchKind(BankLine line) {
        return line.kind() == BankLineKind.CREDIT ? "MATCH" : "CLAWBACK";
    }

    public static IllegalArgumentException lineScopeMismatch() {
        return new IllegalArgumentException("Bank line scope or currency mismatch");
    }

    /** The line must be in the payout's scope and currency, reference it, and be booked near its arrival. */
    public static void requireLineForPayout(ProviderScope scope, PayoutPosition payout, BankLine line) {
        if (!Objects.equals(line.organizationId(), scope.organizationId())
                || !Objects.equals(line.environmentId(), scope.environmentId())
                || !Objects.equals(line.providerAccountId(), scope.providerAccountId())
                || line.livemode() != scope.livemode()
                || !payout.currency().equals(line.currency())) throw lineScopeMismatch();
        if (!line.payoutReferences().contains(payout.providerPayoutId()))
            throw new IllegalArgumentException("Payout reference mismatch");
        if (payout.arrivalDate() == null) throw new IllegalArgumentException("Arrival date evidence required");
        long days = ChronoUnit.DAYS.between(
                payout.arrivalDate().atZone(ZoneOffset.UTC).toLocalDate(),
                line.bookedAt().atZone(ZoneOffset.UTC).toLocalDate());
        if (days < -BOOKING_WINDOW_DAYS || days > BOOKING_WINDOW_DAYS)
            throw new IllegalArgumentException("Bank booking outside +/-7 UTC dates");
    }

    /** A retried match must repeat the principal of the match it already created. */
    public static void requireSameRetry(long priorPrincipalMinor, long principalMinor) {
        if (priorPrincipalMinor != principalMinor)
            throw new IllegalArgumentException("Stable match identity conflicts with retry amount");
    }

    /** Decides how {@code principal} of {@code line} settles {@code payout}, or rejects the allocation. */
    public static Allocation allocate(UUID payoutId, PayoutPosition payout, BankLine line, long principal) {
        if (principal > line.unallocatedMinor()) throw new IllegalArgumentException("Bank line capacity exceeded");
        boolean credit = line.kind() == BankLineKind.CREDIT;
        if (credit && (payout.returnedMinor() > 0 || payout.suspenseMinor() > 0) && principal > payout.transitMinor())
            return new ReturnOverlap();
        if (credit && (payout.conflict() || principal > payout.transitMinor()))
            throw new IllegalArgumentException("Payout unresolved or principal capacity exceeded");
        if (!credit && (principal > payout.suspenseMinor() || principal > payout.bankConfirmedMinor()))
            throw new IllegalArgumentException("Verified overlap and prior net bank principal required for clawback");

        long consumed = Math.addExact(line.allocatedMinor(), principal);
        long fee = credit ? proRataFee(line, consumed) : 0;
        long transit = credit ? payout.transitMinor() - principal : payout.transitMinor();
        long bank = credit
                ? Math.addExact(payout.bankConfirmedMinor(), principal)
                : payout.bankConfirmedMinor() - principal;
        long returned = credit ? payout.returnedMinor() : Math.addExact(payout.returnedMinor(), principal);
        long suspense = credit ? payout.suspenseMinor() : payout.suspenseMinor() - principal;
        List<Posting> postings = credit
                ? List.of(
                        new Posting("BANK_CASH", principal - fee),
                        new Posting("BANK_FEE_EXPENSE", fee),
                        new Posting("BANK_PAYOUT_IN_TRANSIT", -principal))
                : List.of(new Posting("SUSPENSE", principal), new Posting("BANK_CASH", -principal));
        return new Settlement(
                matchKind(line),
                credit
                        ? "bank.match:" + payoutId + ":" + line.id()
                        : "bank.clawback:" + payoutId + ":" + line.id() + ":1",
                credit ? "L-BANK-IN" : "L-BANK-CLAWBACK",
                fee,
                consumed,
                Math.addExact(line.feeAllocatedMinor(), fee),
                transit,
                bank,
                returned,
                suspense,
                matchState(payout.conflict(), bank, payout.outgoingMinor()),
                postings);
    }

    /**
     * Fee allocated by this match: the line's fee pro rata to principal consumed so far, minus fee already allocated.
     * Allocating cumulatively (floor of the running total) makes split lines sum to exactly the line fee.
     */
    static long proRataFee(BankLine line, long consumedMinor) {
        long cumulative = BigInteger.valueOf(line.feeMinor())
                .multiply(BigInteger.valueOf(consumedMinor))
                .divide(BigInteger.valueOf(line.principalMinor()))
                .longValueExact();
        return Math.subtractExact(cumulative, line.feeAllocatedMinor());
    }

    static BankMatchState matchState(boolean conflict, long bankConfirmedMinor, long outgoingMinor) {
        if (conflict) return BankMatchState.CONFLICT;
        if (bankConfirmedMinor == outgoingMinor) return BankMatchState.MATCHED;
        return bankConfirmedMinor > 0 ? BankMatchState.PARTIAL : BankMatchState.UNMATCHED;
    }

    /** What, if anything, must still be read from the database to judge a payout's bank evidence. */
    public enum EvidenceCheck {
        /** The payout position alone decides the state. */
        NONE,
        /** Principal is fully explained; it is MATCHED only if every receipt's statement is complete. */
        SETTLEMENT_COVERAGE,
        /** Principal is still in transit; it is a SHORT_RECEIPT only if complete statements show no more lines. */
        SHORT_RECEIPT
    }

    public record EvidencePlan(EvidenceCheck check, BankEvidenceState state) {}

    public static EvidencePlan evidencePlan(PayoutPosition payout) {
        if (payout.conflict()) return decided(BankEvidenceState.CONFLICT);
        if (payout.outgoingMinor() == 0 || payout.arrivalDate() == null)
            return decided(BankEvidenceState.WAITING_EVIDENCE);
        if (payout.bankConfirmedMinor() == payout.outgoingMinor())
            return new EvidencePlan(EvidenceCheck.SETTLEMENT_COVERAGE, null);
        if (payout.returnedMinor() == payout.outgoingMinor()) return decided(BankEvidenceState.RETURNED);
        // A partial PSP return plus the remaining bank receipt also exhausts the principal.
        if (payout.bankConfirmedMinor() > 0
                && payout.returnedMinor() > 0
                && payout.transitMinor() == 0
                && payout.suspenseMinor() == 0
                && payout.bankConfirmedMinor() == payout.outgoingMinor() - payout.returnedMinor())
            return new EvidencePlan(EvidenceCheck.SETTLEMENT_COVERAGE, null);
        return new EvidencePlan(EvidenceCheck.SHORT_RECEIPT, null);
    }

    public static BankEvidenceState settlementEvidence(boolean everyReceiptCovered) {
        return everyReceiptCovered ? BankEvidenceState.MATCHED : BankEvidenceState.WAITING_EVIDENCE;
    }

    public static BankEvidenceState shortReceiptEvidence(
            long completeMatchedReceipts, long unallocatedReceipts, long transitMinor) {
        return completeMatchedReceipts > 0 && unallocatedReceipts == 0 && transitMinor > 0
                ? BankEvidenceState.SHORT_RECEIPT
                : BankEvidenceState.WAITING_EVIDENCE;
    }

    private static EvidencePlan decided(BankEvidenceState state) {
        return new EvidencePlan(EvidenceCheck.NONE, state);
    }
}
