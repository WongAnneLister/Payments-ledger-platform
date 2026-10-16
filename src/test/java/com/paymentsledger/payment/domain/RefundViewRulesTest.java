package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundViewRulesTest {
    static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");

    static RefundRecord refund(String status, ResolutionStatus resolution, String bt, String failureBt) {
        return new RefundRecord(
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 4),
                new UUID(0, 5),
                400,
                "USD",
                status,
                resolution,
                null,
                null,
                "OTHER",
                bt,
                failureBt,
                NOW,
                NOW,
                null);
    }

    static RefundBalanceTransaction bt(String btId, String state, long fee) {
        return new RefundBalanceTransaction(
                UUID.randomUUID(), btId, state, fee, "POSTED".equals(state) ? UUID.randomUUID() : null);
    }

    @Test
    void conflictWinsOverEverything() {
        var posted = List.of(bt("txn_1", "POSTED", 0));
        assertThat(RefundViewRules.financialStatus(
                        refund("SUCCEEDED", ResolutionStatus.KNOWN, "txn_1", null), posted, true))
                .isEqualTo("CONFLICT");
        assertThat(RefundViewRules.financialStatus(
                        refund("SUCCEEDED", ResolutionStatus.CONFLICT, "txn_1", null), posted, false))
                .isEqualTo("CONFLICT");
    }

    @Test
    void postedOnlyWhenEveryNamedAndAttributedTransactionIsPosted() {
        var succeeded = refund("SUCCEEDED", ResolutionStatus.KNOWN, "txn_1", null);
        assertThat(RefundViewRules.financialStatus(succeeded, List.of(bt("txn_1", "POSTED", 0)), false))
                .isEqualTo("POSTED");
        assertThat(RefundViewRules.financialStatus(
                        succeeded, List.of(bt("txn_1", "POSTED", 0), bt("txn_2", "PENDING_EVIDENCE", 0)), false))
                .isEqualTo("PARTIALLY_POSTED");
        assertThat(RefundViewRules.financialStatus(
                        refund("FAILED", ResolutionStatus.KNOWN, "txn_1", "txn_f"),
                        List.of(bt("txn_1", "POSTED", 0)),
                        false))
                .as("the failure transaction is still missing")
                .isEqualTo("PARTIALLY_POSTED");
        assertThat(RefundViewRules.financialStatus(succeeded, List.of(bt("txn_other", "POSTED", 0)), false))
                .as("the named transaction is not the posted one")
                .isEqualTo("PARTIALLY_POSTED");
    }

    @Test
    void notApplicableOnlyWhenNoMoneyCanMove() {
        for (var status : List.of("RESERVED", "FAILED", "CANCELED"))
            assertThat(RefundViewRules.financialStatus(
                            refund(status, ResolutionStatus.KNOWN, null, null), List.of(), false))
                    .as(status)
                    .isEqualTo("NOT_APPLICABLE");
        for (var status : List.of("SUBMITTED", "PENDING", "REQUIRES_ACTION", "SUCCEEDED"))
            assertThat(RefundViewRules.financialStatus(
                            refund(status, ResolutionStatus.KNOWN, null, null), List.of(), false))
                    .as(status)
                    .isEqualTo("PENDING_EVIDENCE");
        assertThat(RefundViewRules.financialStatus(
                        refund("FAILED", ResolutionStatus.KNOWN, null, "txn_f"), List.of(), false))
                .as("a failure transaction is expected")
                .isEqualTo("PENDING_EVIDENCE");
        assertThat(RefundViewRules.financialStatus(
                        refund("CANCELED", ResolutionStatus.KNOWN, null, null),
                        List.of(bt("txn_1", "PENDING_EVIDENCE", 0)),
                        false))
                .isEqualTo("PENDING_EVIDENCE");
    }

    @Test
    void feeRefundCountsNegativeFeesOfPostedTransactionsOnly() {
        assertThat(RefundViewRules.actualFeeRefund(List.of(
                        bt("a", "POSTED", -30),
                        bt("b", "POSTED", 10),
                        bt("c", "PENDING_EVIDENCE", -50),
                        bt("d", "POSTED", -5))))
                .isEqualTo(35);
        assertThat(RefundViewRules.actualFeeRefund(List.of())).isZero();
    }
}
