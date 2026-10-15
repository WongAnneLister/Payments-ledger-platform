package com.paymentsledger.pr18;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PropertyModelsTest {
    @Test
    void paymentCaptureIsMonotoneAndRequiresKnownPostedUnblockedEvidenceForShare() {
        var payment = new PaymentModel();
        payment.capture(1000);
        payment.capture(600);
        assertThat(payment.captured()).isEqualTo(1000);
        assertThat(payment.eligible()).isFalse();
        payment.posted();
        assertThat(payment.eligible()).isTrue();
        payment.known(false);
        assertThat(payment.eligible()).isFalse();
        payment.known(true);
        payment.blocked(true);
        assertThat(payment.eligible()).isFalse();
        payment.blocked(false);
        assertThat(payment.eligible()).isTrue();
    }

    @Test
    void competingReservationsCannotSpendTheSameCapture() {
        var refunds = new RefundModel(1000);
        assertThat(refunds.reserve("a", 600, false)).isTrue();
        assertThat(refunds.reserve("b", 600, false)).isFalse();
        assertThat(refunds.held()).isEqualTo(600);
    }

    @Test
    void failureAndStalePendingNeverRestoreOrConsumeTwice() {
        var refunds = new RefundModel(10000);
        refunds.reserve("a", 4000, false);
        refunds.observe("a", "failed");
        refunds.observe("a", "failed");
        refunds.observe("a", "pending");
        assertThat(refunds.held()).isZero();
        assertThat(refunds.refunded()).isZero();
        assertThat(refunds.status("a")).isEqualTo("failed");
    }

    @Test
    void shareCeilingUsesCaptureMinusRefundMinusHoldWithIntegerFloor() {
        assertThat(new RevenueShareModel(2000).ceiling(10000, 0, 4000, true)).isEqualTo(1200);
        assertThat(new RevenueShareModel(3333).ceiling(1001, 1, 1, true)).isEqualTo(332);
        assertThat(new RevenueShareModel(2000).ceiling(10000, 0, 0, false)).isZero();
    }

    @Test
    void duplicateJournalHasNoEffectAndConflictingEventFails() {
        var ledger = new LedgerModel();
        var posting = Map.of("PSP_FUNDS", 9700L, "PSP_FEE_EXPENSE", 300L, "SALES_REVENUE", -10000L);
        assertThat(ledger.post("capture", posting)).isTrue();
        assertThat(ledger.post("capture", posting)).isFalse();
        assertThat(ledger.balance("PSP_FUNDS")).isEqualTo(9700);
        assertThatThrownBy(() -> ledger.post("capture", Map.of("PSP_FUNDS", 1L, "SUSPENSE", -1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payoutPrincipalAndBankFeesRemainIndependent() {
        var payout = new PayoutModel();
        payout.outgoing(5700, 30);
        payout.returned(1200, -5);
        assertThat(payout.transit()).isEqualTo(4500);
        assertThat(payout.psp()).isEqualTo(-4525);
        assertThat(payout.allocate(4501, 4501, 0)).isFalse();
        assertThat(payout.allocate(4500, 4500, 11)).isTrue();
        assertThat(payout.cash()).isEqualTo(4489);
        assertThat(payout.transit()).isZero();
    }

    @Test
    void missingEvidenceAndWrongCutoffCannotBecomePass() {
        var cutoff = Instant.parse("2026-03-02T00:00:00Z");
        var model = new ReconciliationModel("scope", cutoff);
        assertThat(model.compare(100, null)).isEqualTo("INCOMPLETE");
        assertThat(model.compare(100, new ReconciliationModel.Evidence("other", cutoff, 100, true)))
                .isEqualTo("INCOMPLETE");
        assertThat(model.compare(100, new ReconciliationModel.Evidence("scope", cutoff.minusSeconds(1), 100, true)))
                .isEqualTo("INCOMPLETE");
        assertThat(model.compare(100, new ReconciliationModel.Evidence("scope", cutoff, 99, true)))
                .isEqualTo("FAIL");
    }
}
