package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import com.paymentsledger.webhook.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** VERIFIED_BOUNDARY_FIXTURE: durable dependency and scope tests, no remote Stripe calls. */
class WebhookAssociationIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper();
    WebhookInboxRepository inbox;
    WebhookProcessor processor;

    @BeforeEach
    void webhookFixture() {
        admin.update(
                "UPDATE platform.provider_account SET provider='STRIPE',provider_account_ref='acct_fixture' WHERE id=?",
                id(4));
        repository = boundRepository(id(4), "acct_fixture");
        inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
        processor = new WebhookProcessor(inbox, repository, json, fake.clock());
    }

    PaymentRepository boundRepository(UUID account, String reference) {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("platform.stripe.account-id", reference)
                .withProperty("platform.stripe.organization-id", id(1).toString())
                .withProperty("platform.stripe.environment-id", id(2).toString())
                .withProperty("platform.stripe.provider-account-id", account.toString());
        return new PaymentRepository(jdbc, tx, json.findAndRegisterModules(), fake.clock(), environment);
    }

    UUID prepare(String order) {
        return repository
                .prepare(context, "key-" + order, command(order), "STRIPE")
                .paymentId();
    }

    void receive(String event, String type, Map<String, Object> object) throws Exception {
        var now = fake.clock().instant();
        byte[] raw = json.writeValueAsBytes(Map.of(
                "id",
                event,
                "object",
                "event",
                "type",
                type,
                "created",
                now.getEpochSecond(),
                "livemode",
                false,
                "api_version",
                "2026-07-29.dahlia",
                "data",
                Map.of("object", object)));
        inbox.receive(
                scope,
                "acct_fixture",
                raw,
                new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        new VerifiedWebhook(
                                event,
                                type,
                                object.get("id").toString(),
                                Instant.ofEpochSecond(now.getEpochSecond()),
                                false),
                        new ProviderEvidence(null, null, now, now, 200, null, null, "2026-07-29.dahlia")));
    }

    Map<String, Object> intent(UUID payment) {
        return Map.of(
                "id",
                "pi_association",
                "object",
                "payment_intent",
                "status",
                "succeeded",
                "amount",
                10000,
                "amount_received",
                10000,
                "currency",
                "usd",
                "metadata",
                Map.of("payment_id", payment.toString()));
    }

    Map<String, Object> charge() {
        return Map.of(
                "id",
                "ch_association",
                "object",
                "charge",
                "status",
                "succeeded",
                "captured",
                true,
                "amount",
                10000,
                "amount_captured",
                10000,
                "currency",
                "usd",
                "payment_intent",
                "pi_association");
    }

    Map<String, Object> session(UUID payment) {
        return Map.of(
                "id",
                "cs_association",
                "object",
                "checkout.session",
                "status",
                "complete",
                "payment_status",
                "paid",
                "amount_total",
                10000,
                "currency",
                "usd",
                "payment_intent",
                "pi_association",
                "metadata",
                Map.of("payment_id", payment.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIC", "SCI", "ISC", "ICS", "CSI", "CIS"})
    void everySessionIntentChargeOrderConvergesWithoutDoubleCapture(String order) throws Exception {
        UUID payment = prepare("order-" + order);
        for (char part : order.toCharArray()) {
            switch (part) {
                case 'S' -> receive("evt_session", "checkout.session.completed", session(payment));
                case 'I' -> receive("evt_intent", "payment_intent.succeeded", intent(payment));
                case 'C' -> receive("evt_charge", "charge.succeeded", charge());
            }
            processor.processDue(25);
        }
        fake.clock().advance(Duration.ofMinutes(6));
        processor.processDue(25);
        assertThat(jdbc.queryForMap(
                        "SELECT payment_status,financial_status,captured_amount_minor FROM platform.payment WHERE id=?",
                        payment))
                .containsEntry("payment_status", "SUCCEEDED")
                .containsEntry("financial_status", "PENDING_EVIDENCE")
                .containsEntry("captured_amount_minor", 10000L);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.webhook_inbox WHERE status<>'DONE'", Integer.class))
                .isZero();
        assertThat(count("journal")).isZero();
    }

    @Test
    void receiptWaitingForAbsentLocalPaymentConvergesWhenPaymentAppears() throws Exception {
        UUID payment = UUID.randomUUID(), order = UUID.randomUUID();
        receive("evt_before_local", "payment_intent.succeeded", intent(payment));
        processor.processDue(25);
        var first = jdbc.queryForMap("SELECT id,first_seen_at FROM platform.dependency_wait");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("WAITING");
        admin.update("""
          INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,
          currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,
          price_snapshot,share_policy_snapshot) VALUES (?,?,?,?,?,'USD','late-order','fixture','demo',1,1,10000,10000,'{}','{}')
          """, order, id(1), id(2), id(3), id(4));
        admin.update("""
          INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,
          business_order_id,amount_minor,expires_at) VALUES (?,?,?,?,?,'USD',?,10000,now()+interval '1 hour')
          """, payment, id(1), id(2), id(3), id(4), order);
        fake.clock().advance(Duration.ofSeconds(6));
        processor.processDue(25);
        assertThat(jdbc.queryForMap("SELECT id,first_seen_at FROM platform.dependency_wait"))
                .isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT resolved_at IS NOT NULL FROM platform.dependency_wait", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT payment_intent_id FROM platform.payment WHERE id=?", String.class, payment))
                .isEqualTo("pi_association");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("DONE");
    }

    @Test
    void metadataCannotBindAnEventToAnotherProviderAccount() throws Exception {
        admin.update(
                "INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'STRIPE','acct_other',false)",
                id(40),
                id(1),
                id(2));
        var other = new com.paymentsledger.identity.TenantContext(
                id(1), id(2), id(3), id(40), Set.of("USD"), false, Set.of("payments:read", "payments:write"));
        UUID payment = boundRepository(id(40), "acct_other")
                .prepare(other, "key-other", command("other"), "STRIPE")
                .paymentId();
        receive("evt_wrong_scope", "payment_intent.succeeded", intent(payment));
        processor.processDue(25);
        assertThat(jdbc.queryForObject(
                        "SELECT payment_intent_id FROM platform.payment WHERE id=?", String.class, payment))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT reason FROM platform.dependency_wait", String.class))
                .isEqualTo("SCOPE_CONFLICT");
        assertThat(count("normalized_observation")).isZero();
        assertThat(count("payment_attempt")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund", "dispute", "payout"})
    void futureResourcesRetainAProcessingObligation(String type) throws Exception {
        String eventType = type.equals("dispute") ? "charge.dispute.created" : type + ".updated";
        receive(
                "evt_future",
                eventType,
                Map.of("id", type + "_fixture", "object", type, "status", "pending", "amount", 100, "currency", "usd"));
        processor.processDue(25);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT reason FROM platform.dependency_wait", String.class))
                .isEqualTo("FUTURE_RESOURCE_HANDLER");
        assertThat(count("normalized_observation")).isZero();
        assertThat(count("journal")).isZero();
    }
}
