package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.payment.domain.PaymentEvidenceRules.ChargeDecision;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentEvidenceRulesTest {
    static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");
    static final UUID PAYMENT = new UUID(0, 7);

    static PaymentSnapshot payment(PaymentStatus status, ResolutionStatus resolution, FinancialStatus financial) {
        return new PaymentSnapshot(
                PAYMENT,
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 4),
                "USD",
                10000,
                5,
                status,
                "open",
                resolution,
                financial,
                "cs_1",
                "pi_1",
                T0,
                null,
                null);
    }

    static PaymentSnapshot awaiting() {
        return payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.KNOWN, FinancialStatus.NOT_APPLICABLE);
    }

    static CheckoutSessionFact session(String id, CheckoutSessionStatus status, String paymentStatus) {
        return new CheckoutSessionFact(id, status, "https://pay.test/" + id, "pi_1", paymentStatus, T0, Map.of());
    }

    static PaymentIntentFact intent(String id, String status, Long received) {
        return new PaymentIntentFact(id, status, 10000, received, "usd", List.of(), "ch_1", null, Map.of(), T0);
    }

    static ChargeFact charge(String status, boolean captured, long amount, Long capturedAmount) {
        return new ChargeFact("ch_1", "pi_1", status, captured, amount, capturedAmount, "usd", null, null, null, T0);
    }

    static PaymentFacts facts(CheckoutSessionFact s, PaymentIntentFact i, boolean complete, long seconds) {
        return new PaymentFacts(s, i, List.of(), complete, false, T0.plusSeconds(seconds));
    }

    @Test
    void queriesAreStaleWhenOlderThanTheLastConfirmationOrForAnotherVersion() {
        var p = awaiting();
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, 1), true))
                .isFalse();
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, 0), true))
                .as("same millisecond is not older")
                .isFalse();
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, -1), true))
                .isTrue();
        var otherVersion = new PaymentFacts(null, null, List.of(), true, false, T0.plusSeconds(9), 4L);
        assertThat(PaymentEvidenceRules.isStale(p, otherVersion, true)).isTrue();
    }

    @Test
    void webhooksAreOrderedAgainstTheLastWebhookAndTheLastQuery() {
        var p = new PaymentSnapshot(
                PAYMENT,
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 4),
                "USD",
                10000,
                5,
                PaymentStatus.AWAITING_CUSTOMER,
                "open",
                ResolutionStatus.KNOWN,
                FinancialStatus.NOT_APPLICABLE,
                "cs_1",
                "pi_1",
                T0.plusSeconds(100),
                T0.plusSeconds(10),
                T0.plusSeconds(20));
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, 9), false))
                .as("before last webhook")
                .isTrue();
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, 20), false))
                .as("not after query")
                .isTrue();
        assertThat(PaymentEvidenceRules.isStale(p, facts(null, null, true, 21), false))
                .as("webhooks ignore last_provider_confirmed_at")
                .isFalse();
    }

    @Test
    void foreignIdentitiesConflictAndAreNotAdopted() {
        var p = awaiting();
        var same = PaymentEvidenceRules.identities(
                p, facts(session("cs_1", CheckoutSessionStatus.OPEN, "unpaid"), null, true, 1));
        assertThat(same).isEqualTo(new PaymentEvidenceRules.Identities("cs_1", "pi_1", false));
        var foreign = PaymentEvidenceRules.identities(
                p,
                facts(
                        session("cs_2", CheckoutSessionStatus.OPEN, "unpaid"),
                        intent("pi_2", "processing", 0L),
                        true,
                        1));
        assertThat(foreign).isEqualTo(new PaymentEvidenceRules.Identities(null, null, true));
    }

    @Test
    void chargeDecisions() {
        assertThat(PaymentEvidenceRules.charge(PAYMENT, charge("failed", false, 10000, 0L), null, T0))
                .isEqualTo(ChargeDecision.RECORD);
        var recorded = new ChargeAttempt(PAYMENT, "pi_1", "USD", 10000, false, 0L, null, T0.plusSeconds(10));
        assertThat(PaymentEvidenceRules.charge(PAYMENT, charge("failed", false, 10000, 0L), recorded, T0))
                .isEqualTo(ChargeDecision.SKIP_OUTDATED);
        assertThat(PaymentEvidenceRules.charge(
                        PAYMENT, charge("failed", false, 9999, 0L), recorded, T0.plusSeconds(20)))
                .isEqualTo(ChargeDecision.CONFLICT);
        assertThat(PaymentEvidenceRules.charge(new UUID(0, 8), charge("failed", false, 10000, 0L), recorded, T0))
                .isEqualTo(ChargeDecision.CONFLICT);
        var captured = new ChargeAttempt(PAYMENT, "pi_1", "USD", 10000, true, 10000L, "txn_1", T0);
        assertThat(PaymentEvidenceRules.charge(
                        PAYMENT, charge("succeeded", true, 10000, 9000L), captured, T0.plusSeconds(1)))
                .isEqualTo(ChargeDecision.CONFLICT);
        assertThat(PaymentEvidenceRules.charge(
                        PAYMENT, charge("succeeded", true, 10000, 10000L), captured, T0.plusSeconds(1)))
                .isEqualTo(ChargeDecision.RECORD);
    }

    @Test
    void financialStatusComparesPostedChargesWithCapturedMoney() {
        var p = awaiting();
        var none = facts(null, null, true, 1);
        assertThat(decide(p, none, 10000, 0L).financial()).isEqualTo(FinancialStatus.PENDING_EVIDENCE);
        assertThat(decide(p, none, 10000, 4000L).financial()).isEqualTo(FinancialStatus.PARTIALLY_POSTED);
        assertThat(decide(p, none, 10000, 10000L).financial()).isEqualTo(FinancialStatus.POSTED);
        assertThat(decide(p, none, 10000, 10001L).financial()).isEqualTo(FinancialStatus.CONFLICT);
        var alreadyConflicting = payment(PaymentStatus.SUCCEEDED, ResolutionStatus.KNOWN, FinancialStatus.CONFLICT);
        assertThat(decide(alreadyConflicting, none, 10000, 10000L).financial()).isEqualTo(FinancialStatus.CONFLICT);
    }

    @Test
    void captureSucceedsButNeverOverwritesCancellationOrExceedsTheOrder() {
        var none = facts(null, null, true, 1);
        var captured = decide(awaiting(), none, 10000, 0L);
        assertThat(captured.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(captured.resolution()).isEqualTo(ResolutionStatus.KNOWN);
        var canceled = decide(
                payment(PaymentStatus.CANCELED, ResolutionStatus.KNOWN, FinancialStatus.NOT_APPLICABLE),
                none,
                10000,
                0L);
        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.resolution()).isEqualTo(ResolutionStatus.CONFLICT);
        assertThat(decide(awaiting(), none, 12000, 0L).resolution()).isEqualTo(ResolutionStatus.CONFLICT);
    }

    @Test
    void uncapturedStatusFollowsIntentThenSession() {
        var open = session("cs_1", CheckoutSessionStatus.OPEN, "unpaid");
        assertThat(decide(awaiting(), facts(open, intent("pi_1", "requires_payment_method", 0L), true, 1), 0, null)
                        .status())
                .isEqualTo(PaymentStatus.AWAITING_CUSTOMER);
        assertThat(decide(awaiting(), facts(null, intent("pi_1", "requires_payment_method", 0L), true, 1), 0, null)
                        .status())
                .as("no open session to pay in")
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(decide(awaiting(), facts(open, intent("pi_1", "requires_action", 0L), true, 1), 0, null)
                        .status())
                .isEqualTo(PaymentStatus.REQUIRES_ACTION);
        var complete = session("cs_1", CheckoutSessionStatus.COMPLETE, "unpaid");
        var bySession = decide(awaiting(), facts(complete, null, true, 1), 0, null);
        assertThat(bySession.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(bySession.providerStatus()).isEqualTo("complete");
    }

    @Test
    void incompleteEvidenceIsUnknownAndStaleEvidenceKeepsEverything() {
        var p = awaiting();
        var open = session("cs_1", CheckoutSessionStatus.OPEN, "unpaid");
        var unknown = decide(p, facts(open, intent("pi_1", "processing", 0L), false, 1), 0, null);
        assertThat(unknown.resolution()).isEqualTo(ResolutionStatus.UNKNOWN);
        assertThat(unknown.status()).isEqualTo(PaymentStatus.PROCESSING);

        var stale = PaymentEvidenceRules.decide(
                p, facts(open, intent("pi_1", "processing", 0L), true, -5), true, true, false, 0, null);
        assertThat(stale.status()).isEqualTo(PaymentStatus.AWAITING_CUSTOMER);
        assertThat(stale.providerStatus()).isEqualTo("open");
        assertThat(stale.resolution()).isEqualTo(ResolutionStatus.KNOWN);
        assertThat(stale.session()).isNull();
        assertThat(stale.intent()).isNull();
    }

    @Test
    void onlyACompleteQueryOfAnExpiredUnpaidSessionCancels() {
        var expired = facts(
                session("cs_1", CheckoutSessionStatus.EXPIRED, "unpaid"),
                intent("pi_1", "requires_payment_method", 0L),
                true,
                1);
        assertThat(PaymentEvidenceRules.unpaid(expired)).isTrue();
        assertThat(decide(awaiting(), expired, 0, null).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(PaymentEvidenceRules.decide(awaiting(), expired, false, false, false, 0, null)
                        .status())
                .as("webhook")
                .isEqualTo(PaymentStatus.PROCESSING);

        var paid = facts(session("cs_1", CheckoutSessionStatus.EXPIRED, "paid"), null, true, 1);
        assertThat(PaymentEvidenceRules.unpaid(paid)).isFalse();
        var received = facts(
                session("cs_1", CheckoutSessionStatus.EXPIRED, "unpaid"),
                intent("pi_1", "requires_payment_method", 100L),
                true,
                1);
        assertThat(PaymentEvidenceRules.unpaid(received)).isFalse();
        var pendingCharge = new PaymentFacts(
                session("cs_1", CheckoutSessionStatus.EXPIRED, "unpaid"),
                null,
                List.of(charge("pending", false, 10000, 0L)),
                true,
                false,
                T0);
        assertThat(PaymentEvidenceRules.unpaid(pendingCharge)).isFalse();
        assertThat(PaymentEvidenceRules.unpaid(
                        facts(session("cs_1", CheckoutSessionStatus.EXPIRED, "unpaid"), null, false, 1)))
                .as("incomplete evidence proves nothing")
                .isFalse();
    }

    @Test
    void hostedUrlAndConfirmationFlags() {
        var withUrl = session("cs_1", CheckoutSessionStatus.OPEN, "unpaid");
        var withoutUrl =
                new CheckoutSessionFact("cs_1", CheckoutSessionStatus.OPEN, null, "pi_1", "unpaid", T0, Map.of());
        assertThat(PaymentEvidenceRules.updatesHostedUrl(withoutUrl, true)).isTrue();
        assertThat(PaymentEvidenceRules.updatesHostedUrl(withoutUrl, false)).isFalse();
        assertThat(PaymentEvidenceRules.updatesHostedUrl(withUrl, false)).isTrue();
        assertThat(PaymentEvidenceRules.updatesHostedUrl(null, true)).isFalse();
        assertThat(PaymentEvidenceRules.confirmsProviderState(facts(null, null, true, 1), false))
                .isTrue();
        assertThat(PaymentEvidenceRules.confirmsProviderState(facts(null, null, true, 1), true))
                .isFalse();
        assertThat(PaymentEvidenceRules.confirmsProviderState(facts(null, null, false, 1), false))
                .isFalse();
    }

    private static PaymentEvidenceRules.StateDecision decide(
            PaymentSnapshot p, PaymentFacts f, long captured, Long posted) {
        return PaymentEvidenceRules.decide(p, f, true, false, false, captured, posted);
    }
}
