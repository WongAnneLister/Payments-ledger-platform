package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Pr18PayoutEvidenceIT extends Pr18Fixture {
    @Test void partialPspReturnAndRemainingBankReceiptProvideCompleteEvidence() {
        reset(180018);payoutId=payout("payout");
        post(bt("payout-out","payout",-5700,0,"payout","payout"));
        post(bt("payout-return","payout_failure",1200,0,"payout","payout"));
        bankLine=bankLine("mixed-bank",4500,0,"payout");matcher.match(scope,payoutId,bankLine,4500);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isZero();
        assertThat(payouts.lookup(scope,"payout").get("bank_match_state")).isEqualTo("PARTIAL");
        assertThat(matcher.evidenceState(scope,payoutId)).as("fully settled mixed principal").isEqualTo("MATCHED");
        assertThat(reconcile(-4500,"full")).isEqualTo("PASS");
        com.paymentsledger.pr18.Pr18Artifacts.write("regression-payout-mixed.json",java.util.Map.of("status","PASS","classification","REAL_DEFECT_FIXED","originalBehavior","WAITING_EVIDENCE","expectedBehavior","MATCHED","principal",5700,"actualReturn",1200,"bankPrincipal",4500,"reconciliationOutcomes",java.util.List.copyOf(reconciliationOutcomes),"databaseProviderMessageEvidence",snapshot()));
    }
    @Test void absentReturnMovementAndRemainingTransitCannotClaimCompleteEvidence() {
        reset(180019);payoutId=payout("payout");post(bt("payout-out","payout",-5700,0,"payout","payout"));
        matcher.match(scope,payoutId,bankLine("short-bank",4500,0,"payout"),4500);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(1200);
        assertThat(matcher.evidenceState(scope,payoutId)).isEqualTo("SHORT_RECEIPT");
        assertThat(reconcile(-5700,"missing")).isEqualTo("INCOMPLETE");
    }
    @Test void actualPartialReturnStillNeedsAllRemainingBankPrincipal() {
        reset(180021);payoutId=payout("payout");
        post(bt("payout-out","payout",-5700,0,"payout","payout"));
        post(bt("payout-return","payout_failure",1200,0,"payout","payout"));
        matcher.match(scope,payoutId,bankLine("incomplete-mixed-bank",4000,0,"payout"),4000);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(500);
        assertThat(matcher.evidenceState(scope,payoutId)).isEqualTo("SHORT_RECEIPT");
    }

    @Test void mixedSettlementWithIncompleteBankStatementRemainsWaiting() {
        reset(180022);payoutId=payout("payout");
        post(bt("payout-out","payout",-5700,0,"payout","payout"));post(bt("payout-return","payout_failure",1200,0,"payout","payout"));
        matcher.match(scope,payoutId,bankLine("incomplete-bank",4500,0,false,"payout"),4500);
        assertThat(matcher.evidenceState(scope,payoutId)).isEqualTo("WAITING_EVIDENCE");
        assertThat(reconcile(-4500,"incomplete-bank")).isEqualTo("PASS");
        assertThat(reconciliationOutcomes.getLast().get("I06")).isEqualTo("INCOMPLETE");
    }
    @Test void fullBankReceiptWithIncompleteStatementRemainsWaiting() {
        reset(180023);payoutId=payout("payout");post(bt("payout-out","payout",-5700,0,"payout","payout"));
        matcher.match(scope,payoutId,bankLine("incomplete-full-bank",5700,0,false,"payout"),5700);
        assertThat(matcher.evidenceState(scope,payoutId)).isEqualTo("WAITING_EVIDENCE");
        assertThat(reconcile(-5700,"incomplete-bank")).isEqualTo("PASS");
        assertThat(reconciliationOutcomes.getLast().get("I06")).isEqualTo("INCOMPLETE");
    }

    @Test void completeSharedStatementDoesNotRequireAllocatingAnotherPayoutsPrincipal() {
        reset(180024);payoutId=payout("payout");post(bt("payout-out","payout",-5700,0,"payout","payout"));
        matcher.match(scope,payoutId,bankLine("shared-complete-bank",10000,0,true,"payout","other_payout"),5700);
        assertThat(matcher.evidenceState(scope,payoutId)).isEqualTo("MATCHED");
        assertThat(reconcile(-5700,"full")).isEqualTo("PASS");
    }

}
