package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins how provider evidence merges into a payment's four state dimensions before the merge logic is split into domain
 * rules and repositories: business status, provider status, certainty (resolution) and accounting status, plus the
 * stale-evidence, identity-conflict and query-only cancellation rules.
 */
class PaymentStateCharacterizationIT extends PaymentCoreFixture {
    UUID payment;
    String session, intent;

    UUID created(String order) {
        var resource = service.create(context, order + "-key", command(order)).resource();
        payment = payment(resource);
        var provider = (Map<?, ?>) resource.get("provider");
        session = provider.get("checkoutSessionId").toString();
        intent = provider.get("paymentIntentId").toString();
        return payment;
    }

    Instant later(long seconds) {
        return fake.clock().instant().plusSeconds(seconds);
    }

    CheckoutSessionFact sessionFact(String id, CheckoutSessionStatus status, String paymentStatus, String hostedUrl) {
        return new CheckoutSessionFact(id, status, hostedUrl, intent, paymentStatus, later(3600), Map.of());
    }

    PaymentIntentFact intentFact(String id, String status, Long received, String latestCharge) {
        return new PaymentIntentFact(
                id,
                status,
                10000,
                received,
                "usd",
                latestCharge == null ? List.of() : List.of(latestCharge),
                latestCharge,
                null,
                Map.of(),
                later(0));
    }

    ChargeFact charge(String id, String status, boolean captured, long amount, Long capturedAmount) {
        return new ChargeFact(id, intent, status, captured, amount, capturedAmount, "usd", null, null, null, later(0));
    }

    boolean query(PaymentRepository.Facts facts) {
        return new TransactionTemplate(tx).execute(s -> repository.mergeReconciliationQuery(payment, facts));
    }

    boolean webhook(PaymentRepository.Facts facts) {
        return new TransactionTemplate(tx).execute(s -> repository.mergeWebhook(payment, facts));
    }

    Map<String, Object> row() {
        return admin.queryForMap("""
                SELECT payment_status,provider_status,resolution_status,financial_status,captured_amount_minor,
                    checkout_session_id,payment_intent_id,latest_charge_id,hosted_url,unresolved_since IS NOT NULL
                    AS unresolved,last_query_confirmed_at,last_webhook_created_at
                FROM platform.payment WHERE id=?""", payment);
    }

    void assertState(String status, String provider, String resolution, String financial) {
        assertThat(row())
                .containsEntry("payment_status", status)
                .containsEntry("provider_status", provider)
                .containsEntry("resolution_status", resolution)
                .containsEntry("financial_status", financial);
    }

    PaymentRepository.Facts open(String intentStatus, long seconds) {
        return new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.OPEN, "unpaid", "https://checkout.test/open"),
                intentFact(intent, intentStatus, 0L, null),
                List.of(),
                true,
                false,
                later(seconds));
    }

    @Test
    void createdPaymentStartsAwaitingCustomerWithKnownResolution() {
        created("start");
        assertState("AWAITING_CUSTOMER", "open", "KNOWN", "NOT_APPLICABLE");
    }

    @Test
    void intentStatusDrivesTheUncapturedBusinessStatus() {
        created("intent");
        assertThat(query(open("requires_payment_method", 1))).isTrue();
        assertState("AWAITING_CUSTOMER", "requires_payment_method", "KNOWN", "NOT_APPLICABLE");
        query(open("requires_action", 2));
        assertState("REQUIRES_ACTION", "requires_action", "KNOWN", "NOT_APPLICABLE");
        query(open("processing", 3));
        assertState("PROCESSING", "processing", "KNOWN", "NOT_APPLICABLE");
    }

    @Test
    void sessionWithoutIntentUsesSessionStatus() {
        created("session");
        var facts = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.COMPLETE, "unpaid", null),
                null,
                List.of(),
                true,
                false,
                later(1));
        query(facts);
        assertState("PROCESSING", "complete", "KNOWN", "NOT_APPLICABLE");
        assertThat(row().get("hosted_url"))
                .as("a query replaces the hosted URL even with null")
                .isNull();
    }

    @Test
    void incompleteEvidenceIsUnknownNotFailure() {
        created("unknown");
        var facts = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.OPEN, "unpaid", null),
                null,
                List.of(),
                false,
                false,
                later(1));
        assertThat(query(facts)).isFalse();
        assertState("AWAITING_CUSTOMER", "open", "UNKNOWN", "NOT_APPLICABLE");
        assertThat(row()).containsEntry("unresolved", true);
        assertThat(query(open("requires_payment_method", 2))).isTrue();
        assertThat(row()).containsEntry("resolution_status", "KNOWN").containsEntry("unresolved", false);
    }

    @Test
    void capturedChargeSucceedsWithPendingAccountingEvidence() {
        created("capture");
        var facts = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.COMPLETE, "paid", null),
                intentFact(intent, "succeeded", 10000L, "ch_capture"),
                List.of(charge("ch_capture", "succeeded", true, 10000, 10000L)),
                true,
                false,
                later(1));
        assertThat(query(facts)).isTrue();
        assertState("SUCCEEDED", "succeeded", "KNOWN", "PENDING_EVIDENCE");
        assertThat(row())
                .containsEntry("captured_amount_minor", 10000L)
                .containsEntry("latest_charge_id", "ch_capture");
        assertThat(query(open("requires_payment_method", 2)))
                .as("a later unpaid snapshot cannot undo a capture")
                .isTrue();
        assertThat(row()).containsEntry("payment_status", "SUCCEEDED");
    }

    @Test
    void capturingMoreThanTheOrderAmountIsAConflict() {
        created("over");
        var facts = new PaymentRepository.Facts(
                null, null, List.of(charge("ch_over", "succeeded", true, 12000, 12000L)), true, false, later(1));
        assertThat(query(facts)).isFalse();
        assertState("SUCCEEDED", "open", "CONFLICT", "PENDING_EVIDENCE");
    }

    @Test
    void foreignSessionOrIntentIdentityIsAConflictAndIsNotAdopted() {
        created("identity");
        var foreignSession = new PaymentRepository.Facts(
                sessionFact("cs_foreign", CheckoutSessionStatus.OPEN, "unpaid", null),
                null,
                List.of(),
                true,
                false,
                later(1));
        assertThat(query(foreignSession)).isFalse();
        assertThat(row()).containsEntry("resolution_status", "CONFLICT").containsEntry("checkout_session_id", session);

        created("identity-intent");
        var foreignIntent = new PaymentRepository.Facts(
                null, intentFact("pi_foreign", "processing", 0L, null), List.of(), true, false, later(1));
        assertThat(query(foreignIntent)).isFalse();
        assertThat(row()).containsEntry("resolution_status", "CONFLICT").containsEntry("payment_intent_id", intent);
    }

    @Test
    void staleEvidenceDoesNotMoveTheState() {
        created("stale");
        query(open("requires_action", 10));
        assertState("REQUIRES_ACTION", "requires_action", "KNOWN", "NOT_APPLICABLE");

        assertThat(query(open("processing", 5)))
                .as("older than the last confirmation")
                .isFalse();
        assertState("REQUIRES_ACTION", "requires_action", "KNOWN", "NOT_APPLICABLE");

        var wrongVersion = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.OPEN, "unpaid", null),
                intentFact(intent, "processing", 0L, null),
                List.of(),
                true,
                false,
                later(20),
                999L);
        assertThat(query(wrongVersion))
                .as("snapshot of another payment version")
                .isFalse();
        assertState("REQUIRES_ACTION", "requires_action", "KNOWN", "NOT_APPLICABLE");
    }

    @Test
    void webhookOlderThanTheLastQueryIsStale() {
        created("webhook-order");
        query(open("requires_action", 10));
        assertThat(row().get("last_query_confirmed_at")).isNotNull();
        assertThat(webhook(open("processing", 10)))
                .as("not after the query confirmation")
                .isFalse();
        assertState("REQUIRES_ACTION", "requires_action", "KNOWN", "NOT_APPLICABLE");
        assertThat(webhook(open("processing", 11))).isTrue();
        assertState("PROCESSING", "processing", "KNOWN", "NOT_APPLICABLE");
        assertThat(row().get("last_webhook_created_at")).isNotNull();
    }

    @Test
    void onlyAQueryCanCancelAnExpiredUnpaidSession() {
        created("expire-webhook");
        var expired = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.EXPIRED, "unpaid", null),
                intentFact(intent, "requires_payment_method", 0L, null),
                List.of(),
                true,
                false,
                later(1));
        webhook(expired);
        assertState("PROCESSING", "requires_payment_method", "KNOWN", "NOT_APPLICABLE");

        created("expire-query");
        var expiredQuery = new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.EXPIRED, "unpaid", null),
                intentFact(intent, "requires_payment_method", 0L, null),
                List.of(),
                true,
                false,
                later(1));
        assertThat(query(expiredQuery)).isTrue();
        assertState("CANCELED", "requires_payment_method", "KNOWN", "NOT_APPLICABLE");
    }

    @Test
    void captureAfterCancellationIsAConflictThatKeepsTheCancellation() {
        created("cancel-then-capture");
        query(new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.EXPIRED, "unpaid", null),
                intentFact(intent, "requires_payment_method", 0L, null),
                List.of(),
                true,
                false,
                later(1)));
        var capture = new PaymentRepository.Facts(
                null, null, List.of(charge("ch_late", "succeeded", true, 10000, 10000L)), true, false, later(2));
        assertThat(query(capture)).isFalse();
        assertState("CANCELED", "requires_payment_method", "CONFLICT", "PENDING_EVIDENCE");
    }

    @Test
    void contradictoryRepeatOfAChargeIsAConflictAndOlderFailuresAreIgnored() {
        created("attempts");
        query(new PaymentRepository.Facts(
                null, null, List.of(charge("ch_failed", "failed", false, 10000, 0L)), true, false, later(10)));
        assertThat(query(new PaymentRepository.Facts(
                        null, null, List.of(charge("ch_failed", "failed", false, 10000, 0L)), true, false, later(5))))
                .as("an older uncaptured observation of a known charge is skipped")
                .isFalse();
        assertThat(row()).containsEntry("resolution_status", "KNOWN");

        assertThat(query(new PaymentRepository.Facts(
                        null, null, List.of(charge("ch_failed", "failed", false, 9999, 0L)), true, false, later(20))))
                .isFalse();
        assertThat(row()).containsEntry("resolution_status", "CONFLICT");
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment_attempt", Long.class))
                .isEqualTo(1);
    }

    @Test
    void webhookKeepsTheHostedUrlWhenTheEventOmitsIt() {
        created("hosted");
        String original = (String) row().get("hosted_url");
        assertThat(original).isNotNull();
        webhook(new PaymentRepository.Facts(
                sessionFact(session, CheckoutSessionStatus.OPEN, "unpaid", null),
                null,
                List.of(),
                true,
                false,
                later(1)));
        assertThat(row().get("hosted_url")).isEqualTo(original);
    }
}
