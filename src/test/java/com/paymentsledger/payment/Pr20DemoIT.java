package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Replayable accounting branches, each in its own fresh database and external fixture. */
class Pr20DemoIT extends Pr20RecoveryFixture {
    final List<Map<String, Object>> trace = new ArrayList<>();

    interface Case {
        void run(Map<String, Object> report) throws Exception;
    }

    void demo(String id, Case body) throws Exception {
        var report = Pr20RecoveryReports.begin("demo", id);
        Throwable failure = null;
        try {
            body.run(report);
            report.put("status", "PASS");
        } catch (Exception | AssertionError e) {
            failure = e;
            throw e;
        } finally {
            report.put("trace", trace);
            persistFixture(Pr20RecoveryReports.root("demo").resolve(id), report);
            Pr20RecoveryReports.finish("demo", id, report, failure);
        }
    }

    void checkpoint(String name, long psp, long share, long bank, long transit) {
        assertThat(balance("PSP_FUNDS")).as(name + " PSP").isEqualTo(psp);
        assertThat(balance("PARTNER_SHARE_EXPENSE")).as(name + " share").isEqualTo(share);
        assertThat(balance("BANK_CASH")).as(name + " bank").isEqualTo(bank);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).as(name + " transit").isEqualTo(transit);
        trace.add(Map.of(
                "step",
                name,
                "providerClock",
                fake.clock().instant(),
                "expected",
                Map.of(
                        "PSP_FUNDS",
                        psp,
                        "PARTNER_SHARE_EXPENSE",
                        share,
                        "BANK_CASH",
                        bank,
                        "BANK_PAYOUT_IN_TRANSIT",
                        transit),
                "actual",
                snapshot(),
                "audit",
                independentAudit()));
    }

    void aThroughPayout() {
        createPayment();
        capturePayment();
        post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
        checkpoint("A1-A2 capture BT and 20% share", 9700, 2000, 0, 0);
        reserve("b", 4000);
        checkpoint("A3 refund hold", 9700, 1200, 0, 0);
        refundFact("b", "succeeded", 4000, false);
        post(bt("refund", "refund", -4000, 0, "re_b", "refund"));
        checkpoint("A4 refund BT", 5700, 1200, 0, 0);
        payoutId = payout("payout");
        post(bt("payout-out", "payout", -5700, 0, "payout", "payout"));
        checkpoint("A5 actual available payout BT", 0, 1200, 0, 5700);
    }

    @Test
    void selectedRouteAReachesSimulatedBankWithExactTrialBalance() throws Exception {
        demo("DEMO-A-SUCCESS-BANK", report -> {
            aThroughPayout();
            bankLine = bankLine("bank", 5700, 0, "payout");
            matcher.match(scope, payoutId, bankLine, 5700);
            checkpoint("A6 simulated bank receipt", 0, 1200, 5700, 0);
            assertThat(reconcile(0, "full")).isEqualTo("PASS");
            var totals = jdbc.queryForMap(
                    "SELECT sum(CASE WHEN side='DEBIT' THEN amount_minor ELSE 0 END) debit_turnover,sum(CASE WHEN side='CREDIT' THEN amount_minor ELSE 0 END) credit_turnover FROM platform.journal_entry");
            assertThat(((Number) totals.get("debit_turnover")).longValue()).isEqualTo(28200);
            assertThat(((Number) totals.get("credit_turnover")).longValue()).isEqualTo(28200);
            long debit = jdbc.queryForObject(
                    "SELECT coalesce(sum(greatest(balance_minor,0)),0) FROM platform.balance_projection", Long.class);
            long credit = jdbc.queryForObject(
                    "SELECT coalesce(sum(greatest(-balance_minor,0)),0) FROM platform.balance_projection", Long.class);
            assertThat(debit).isEqualTo(11200);
            assertThat(credit).isEqualTo(11200);
            report.put(
                    "trialBalance",
                    Map.of("closingDebitsMinor", debit, "closingCreditsMinor", credit, "journalTurnover", totals));
            report.put("selectedBranch", "A1 through A6 success; fixture fees 300, simulated bank receipt 5700");
        });
    }

    @Test
    void selectedRouteAPayoutReturnIsAnIndependentBranch() throws Exception {
        demo("DEMO-A-PAYOUT-RETURN", report -> {
            aThroughPayout();
            post(bt("payout-return", "payout_failure", 5700, 0, "payout", "payout"));
            checkpoint("A6 alternative failed payout return", 5700, 1200, 0, 0);
            assertThat(reconcile(5700, "full")).isEqualTo("PASS");
            report.put("selectedBranch", "A1 through A5 then full return BT; no bank receipt");
        });
    }

    @Test
    void selectedRouteBEarlyRefundFailureThenLostDisputeLateWin() throws Exception {
        demo("DEMO-B-REFUND-FAIL-LATE-WIN", report -> {
            createPayment();
            capturePayment();
            post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
            reserve("a", 4000);
            checkpoint("B1 hold", 9700, 1200, 0, 0);
            var intent = intents.get("a");
            RefundPort timeout = new RefundPort() {
                public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand c) {
                    return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
                }

                public ProviderCallResult<RefundFact> queryRefund(ProviderScope s, String id) {
                    throw new AssertionError("No trusted refund anchor");
                }
            };
            new RefundExecutor(repository, refunds, timeout, "FAKE").execute(intent.operationId());
            checkpoint("B2 unknown retains hold", 9700, 1200, 0, 0);
            refundFact("a", "failed", 4000, false);
            checkpoint("B4 early explicit failure without debit BT", 9700, 2000, 0, 0);
            refundFact("a", "failed", 4000, false);
            checkpoint("B4 duplicate does not restore share twice", 9700, 2000, 0, 0);
            observeDispute("loss", "lost", "dispute-debit", "dispute-return");
            post(bt("dispute-debit", "adjustment", -3000, 1500, "du_loss", "dispute"));
            checkpoint("B dispute loss with actual debit", 5200, 0, 0, 0);
            assertThat(balance("DISPUTE_LOSS")).isEqualTo(3000);
            observeDispute("loss", "won", "dispute-debit", "dispute-return");
            post(bt("dispute-return", "adjustment", 3000, 0, "du_loss", "dispute"));
            checkpoint("B late win principal returned once", 8200, 2000, 0, 0);
            assertThat(balance("DISPUTE_LOSS")).isZero();
            assertThat(balance("DISPUTE_RECEIVABLE")).isZero();
            assertThat(balance("DISPUTE_FEE_EXPENSE")).isEqualTo(1500);
            assertThat(reconcile(8200, "full")).isEqualTo("PASS");
            report.put(
                    "selectedBranch",
                    "B1/B2/B4 early failure before refund debit, then lost dispute followed by late win; disputed fee 1500 remains");
        });
    }

    @Test
    void unknownUsesOriginalSafeOperationKeyAndDoesNotDuplicateCapture() throws Exception {
        demo("DEMO-UNKNOWN", report -> {
            var intent = repository.prepare(context, "unknown-api", command("unknown-order"), "FAKE");
            paymentId = intent.paymentId();
            var claim = repository.claim(intent.operationId(), "FAKE");
            var original = repository.operation(intent.operationId());
            var accepted = fake.createSession(
                    repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class));
            checkout = accepted.payload().sessionId();
            chargeId = fake.controls()
                    .attemptPayment(scope, checkout, FakeControls.AttemptOutcome.SUCCEED)
                    .latestChargeId();
            repository.finishCreate(
                    claim,
                    new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, accepted.evidence()));
            assertThat(repository.read(context, paymentId)).containsEntry("resolutionStatus", "UNKNOWN");
            var effects = fake.controls().effects(scope);
            fake.clock().advance(Duration.ofSeconds(6));
            new OperationRecoveryCoordinator(repository, executor).recoverDue(100);
            service.get(context, paymentId);
            fake.controls().linkChargeBalanceTransaction(scope, chargeId, "capture");
            service.get(context, paymentId);
            post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
            assertThat(repository.read(context, paymentId))
                    .containsEntry("resolutionStatus", "KNOWN")
                    .containsEntry("paymentStatus", "SUCCEEDED");
            assertThat(repository.operation(intent.operationId()).get("operation_key"))
                    .isEqualTo(original.get("operation_key"));
            assertThat(fake.controls().effects(scope)).isEqualTo(effects);
            checkpoint("UNKNOWN converged via original operation", 9700, 2000, 0, 0);
            report.put("operationKey", original.get("operation_key"));
            report.put("requestFingerprint", original.get("request_fingerprint"));
            report.put("effectsBefore", effects);
            report.put("effectsAfter", fake.controls().effects(scope));
        });
    }

    @Test
    void trustedOrderL3BackfillsMissingPaymentWithProvenanceOnce() throws Exception {
        demo("DEMO-L3", report -> {
            var trusted = trustedCheckout("lost-payment");
            assertThat(count("payment")).isZero();
            String charge = fake.controls()
                    .attemptPayment(scope, trusted.session(), FakeControls.AttemptOutcome.SUCCEED)
                    .latestChargeId();
            fake.controls().linkChargeBalanceTransaction(scope, charge, "lost-capture");
            var fact = bt("lost-capture", "charge", 10000, 300, charge, "charge");
            fake.controls().putBalanceTransaction(scope, fact, fake.clock().instant(), null);
            var effects = fake.controls().effects(scope);
            UUID run = rescanAndRecoverSources();
            assertThat(count("payment")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT origin FROM platform.payment", String.class))
                    .isEqualTo("RECONCILIATION");
            assertThat(count("reconciliation_payment_provenance")).isEqualTo(1);
            assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
            new ReconciliationSourceRecovery(jdbc, tx, json, fake, fake, repository, replay, fake.clock())
                    .drain(scope, run, 100);
            assertThat(count("payment")).isEqualTo(1);
            assertThat(count("journal")).isEqualTo(1);
            assertThat(fake.controls().effects(scope)).isEqualTo(effects);
            checkpoint("L3 recovered trusted order once", 9700, 0, 0, 0);
            report.put("trustedOrder", trusted);
            report.put("reconciliationRunId", run);
            report.put(
                    "eligibilityEvidence", jdbc.queryForList("SELECT * FROM platform.order_fulfillment_eligibility"));
            report.put("provenance", jdbc.queryForList("SELECT * FROM platform.reconciliation_payment_provenance"));
            report.put("independentClosingMinor", 9700);
        });
    }

    @Test
    void independentClosingDifferenceIsDetectedWhileLocalTrialBalancePasses() throws Exception {
        demo("DEMO-DIFFERENCE", report -> {
            createPayment();
            capturePayment();
            post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
            assertThat(reconcile(9701, "difference")).isEqualTo("FAIL");
            assertThat(independentlyRebuild().matches()).isTrue();
            checkpoint("Independent +1 closing difference detected with balanced local ledger", 9700, 2000, 0, 0);
            report.put("independentFixtureClosingMinor", 9701);
            report.put("expectedLedgerClosingMinor", 9700);
            report.put("expectedDifferenceMinor", 1);
            report.put("detectedCheck", reconciliationOutcomes.getLast());
            report.put(
                    "expectedNegativeSensitivityResult",
                    "I05 FAIL detects an independently supplied +1 minor-unit difference");
        });
    }
}
