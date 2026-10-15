package com.paymentsledger.bank.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.bank.domain.BankMatchRules.EvidenceCheck;
import com.paymentsledger.bank.domain.BankMatchRules.Posting;
import com.paymentsledger.bank.domain.BankMatchRules.ReturnOverlap;
import com.paymentsledger.bank.domain.BankMatchRules.Settlement;
import com.paymentsledger.provider.ProviderScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankMatchRulesTest {
    static final ProviderScope SCOPE = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 4), false);
    static final Instant ARRIVAL = Instant.parse("2026-03-01T00:01:40Z");
    static final UUID PAYOUT = new UUID(0, 60), LINE = new UUID(0, 65);

    /** A payout of 5700 that left the PSP and is waiting in transit for bank confirmation. */
    static PayoutPosition inTransit() {
        return new PayoutPosition(PAYOUT, "po_1", "USD", ARRIVAL, false, BankMatchState.UNMATCHED, 5700, 5700, 0, 0, 0);
    }

    static BankLine line(BankLineKind kind, long principal, long fee, long allocated, long feeAllocated) {
        return new BankLine(
                LINE,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                SCOPE.providerAccountId(),
                false,
                "USD",
                kind,
                ARRIVAL.plusSeconds(100),
                principal,
                fee,
                allocated,
                feeAllocated,
                List.of("po_1"));
    }

    static Settlement settle(PayoutPosition payout, BankLine line, long principal) {
        return (Settlement) BankMatchRules.allocate(PAYOUT, payout, line, principal);
    }

    @Test
    void lineMustBelongToThePayout() {
        var payout = inTransit();
        BankMatchRules.requireLineForPayout(SCOPE, payout, line(BankLineKind.CREDIT, 5700, 0, 0, 0));

        var otherCurrency = new BankLine(
                LINE,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                SCOPE.providerAccountId(),
                false,
                "JPY",
                BankLineKind.CREDIT,
                ARRIVAL,
                5700,
                0,
                0,
                0,
                List.of("po_1"));
        assertThatThrownBy(() -> BankMatchRules.requireLineForPayout(SCOPE, payout, otherCurrency))
                .hasMessage("Bank line scope or currency mismatch");
        var otherAccount = new BankLine(
                LINE,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                new UUID(0, 99),
                false,
                "USD",
                BankLineKind.CREDIT,
                ARRIVAL,
                5700,
                0,
                0,
                0,
                List.of("po_1"));
        assertThatThrownBy(() -> BankMatchRules.requireLineForPayout(SCOPE, payout, otherAccount))
                .hasMessage("Bank line scope or currency mismatch");
        var otherReference = new BankLine(
                LINE,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                SCOPE.providerAccountId(),
                false,
                "USD",
                BankLineKind.CREDIT,
                ARRIVAL,
                5700,
                0,
                0,
                0,
                List.of("po_2"));
        assertThatThrownBy(() -> BankMatchRules.requireLineForPayout(SCOPE, payout, otherReference))
                .hasMessage("Payout reference mismatch");

        var noArrival =
                new PayoutPosition(PAYOUT, "po_1", "USD", null, false, BankMatchState.UNMATCHED, 5700, 5700, 0, 0, 0);
        assertThatThrownBy(() ->
                        BankMatchRules.requireLineForPayout(SCOPE, noArrival, line(BankLineKind.CREDIT, 5700, 0, 0, 0)))
                .hasMessage("Arrival date evidence required");
    }

    @Test
    void bookingWindowIsSevenUtcDatesEitherSide() {
        var payout = inTransit();
        for (long days : new long[] {-7, 7})
            BankMatchRules.requireLineForPayout(SCOPE, payout, bookedDaysAfterArrival(days));
        for (long days : new long[] {-8, 8})
            assertThatThrownBy(() -> BankMatchRules.requireLineForPayout(SCOPE, payout, bookedDaysAfterArrival(days)))
                    .hasMessage("Bank booking outside +/-7 UTC dates");
    }

    private static BankLine bookedDaysAfterArrival(long days) {
        return new BankLine(
                LINE,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                SCOPE.providerAccountId(),
                false,
                "USD",
                BankLineKind.CREDIT,
                ARRIVAL.plusSeconds(days * 86400),
                5700,
                0,
                0,
                0,
                List.of("po_1"));
    }

    @Test
    void fullCreditMovesTransitToBankCash() {
        var settlement = settle(inTransit(), line(BankLineKind.CREDIT, 5700, 0, 0, 0), 5700);
        assertThat(settlement.matchKind()).isEqualTo("MATCH");
        assertThat(settlement.eventKey()).isEqualTo("bank.match:" + PAYOUT + ":" + LINE);
        assertThat(settlement.postingType()).isEqualTo("L-BANK-IN");
        assertThat(settlement.transitMinor()).isZero();
        assertThat(settlement.bankConfirmedMinor()).isEqualTo(5700);
        assertThat(settlement.state()).isEqualTo(BankMatchState.MATCHED);
        assertThat(settlement.postings())
                .containsExactly(
                        new Posting("BANK_CASH", 5700),
                        new Posting("BANK_FEE_EXPENSE", 0),
                        new Posting("BANK_PAYOUT_IN_TRANSIT", -5700));
    }

    @Test
    void splitLineFeeIsAllocatedCumulativelySoPartsSumToTheLineFee() {
        var first = settle(inTransit(), line(BankLineKind.CREDIT, 5700, 101, 0, 0), 2000);
        assertThat(first.feeMinor()).isEqualTo(35); // floor(101 * 2000 / 5700)
        assertThat(first.state()).isEqualTo(BankMatchState.PARTIAL);
        var rest = settle(inTransit(), line(BankLineKind.CREDIT, 5700, 101, 2000, 35), 3700);
        assertThat(rest.feeMinor()).isEqualTo(66); // floor(101 * 5700 / 5700) - 35
        assertThat(first.feeMinor() + rest.feeMinor()).isEqualTo(101);
        assertThat(rest.lineAllocatedMinor()).isEqualTo(5700);
        assertThat(rest.lineFeeAllocatedMinor()).isEqualTo(101);
        assertThat(rest.postings())
                .containsExactly(
                        new Posting("BANK_CASH", 3634),
                        new Posting("BANK_FEE_EXPENSE", 66),
                        new Posting("BANK_PAYOUT_IN_TRANSIT", -3700));
    }

    @Test
    void creditRejectsOverCapacityAndUnresolvedPayouts() {
        assertThatThrownBy(() -> settle(inTransit(), line(BankLineKind.CREDIT, 5700, 0, 5700, 0), 1))
                .hasMessage("Bank line capacity exceeded");
        assertThatThrownBy(() -> settle(inTransit(), line(BankLineKind.CREDIT, 6000, 0, 0, 0), 5800))
                .hasMessage("Payout unresolved or principal capacity exceeded");
        var conflicted =
                new PayoutPosition(PAYOUT, "po_1", "USD", ARRIVAL, true, BankMatchState.CONFLICT, 5700, 5700, 0, 0, 0);
        assertThatThrownBy(() -> settle(conflicted, line(BankLineKind.CREDIT, 5700, 0, 0, 0), 100))
                .hasMessage("Payout unresolved or principal capacity exceeded");
    }

    @Test
    void creditOverlappingAPspReturnBecomesAConflictInsteadOfPosting() {
        var returned = new PayoutPosition(
                PAYOUT, "po_1", "USD", ARRIVAL, false, BankMatchState.UNMATCHED, 5700, 0, 0, 5700, 0);
        assertThat(BankMatchRules.allocate(PAYOUT, returned, line(BankLineKind.CREDIT, 5700, 0, 0, 0), 5700))
                .isEqualTo(new ReturnOverlap());
    }

    @Test
    void clawbackReleasesSuspenseOnlyForVerifiedOverlap() {
        var overlapped = new PayoutPosition(
                PAYOUT, "po_1", "USD", ARRIVAL, true, BankMatchState.CONFLICT, 5700, 0, 5700, 0, 5700);
        var clawback = settle(overlapped, line(BankLineKind.RETURN, 5700, 0, 0, 0), 5700);
        assertThat(clawback.matchKind()).isEqualTo("CLAWBACK");
        assertThat(clawback.eventKey()).isEqualTo("bank.clawback:" + PAYOUT + ":" + LINE + ":1");
        assertThat(clawback.postingType()).isEqualTo("L-BANK-CLAWBACK");
        assertThat(clawback.feeMinor()).isZero();
        assertThat(clawback.bankConfirmedMinor()).isZero();
        assertThat(clawback.returnedMinor()).isEqualTo(5700);
        assertThat(clawback.suspenseMinor()).isZero();
        assertThat(clawback.state()).isEqualTo(BankMatchState.CONFLICT);
        assertThat(clawback.postings()).containsExactly(new Posting("SUSPENSE", 5700), new Posting("BANK_CASH", -5700));

        assertThatThrownBy(() -> settle(inTransit(), line(BankLineKind.RETURN, 5700, 0, 0, 0), 5700))
                .hasMessage("Verified overlap and prior net bank principal required for clawback");
    }

    @Test
    void retryMustRepeatThePrincipal() {
        BankMatchRules.requireSameRetry(5700, 5700);
        assertThatThrownBy(() -> BankMatchRules.requireSameRetry(5700, 5699))
                .hasMessage("Stable match identity conflicts with retry amount");
    }

    @Test
    void evidencePlanDecidesFromThePositionOrNamesTheCheckNeeded() {
        assertThat(BankMatchRules.evidencePlan(position(true, ARRIVAL, 5700, 5700, 0, 0, 0))
                        .state())
                .isEqualTo(BankEvidenceState.CONFLICT);
        assertThat(BankMatchRules.evidencePlan(position(false, null, 5700, 5700, 0, 0, 0))
                        .state())
                .isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
        assertThat(BankMatchRules.evidencePlan(position(false, ARRIVAL, 0, 0, 0, 0, 0))
                        .state())
                .isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
        assertThat(BankMatchRules.evidencePlan(position(false, ARRIVAL, 5700, 0, 5700, 0, 0))
                        .check())
                .isEqualTo(EvidenceCheck.SETTLEMENT_COVERAGE);
        assertThat(BankMatchRules.evidencePlan(position(false, ARRIVAL, 5700, 0, 0, 5700, 0))
                        .state())
                .isEqualTo(BankEvidenceState.RETURNED);
        assertThat(BankMatchRules.evidencePlan(position(false, ARRIVAL, 5700, 0, 3700, 2000, 0))
                        .check())
                .isEqualTo(EvidenceCheck.SETTLEMENT_COVERAGE);
        assertThat(BankMatchRules.evidencePlan(position(false, ARRIVAL, 5700, 700, 5000, 0, 0))
                        .check())
                .isEqualTo(EvidenceCheck.SHORT_RECEIPT);

        assertThat(BankMatchRules.settlementEvidence(true)).isEqualTo(BankEvidenceState.MATCHED);
        assertThat(BankMatchRules.settlementEvidence(false)).isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
        assertThat(BankMatchRules.shortReceiptEvidence(1, 0, 700)).isEqualTo(BankEvidenceState.SHORT_RECEIPT);
        assertThat(BankMatchRules.shortReceiptEvidence(1, 1, 700)).isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
        assertThat(BankMatchRules.shortReceiptEvidence(0, 0, 700)).isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
        assertThat(BankMatchRules.shortReceiptEvidence(1, 0, 0)).isEqualTo(BankEvidenceState.WAITING_EVIDENCE);
    }

    private static PayoutPosition position(
            boolean conflict, Instant arrival, long outgoing, long transit, long bank, long returned, long suspense) {
        return new PayoutPosition(
                PAYOUT,
                "po_1",
                "USD",
                arrival,
                conflict,
                BankMatchState.UNMATCHED,
                outgoing,
                transit,
                bank,
                returned,
                suspense);
    }
}
