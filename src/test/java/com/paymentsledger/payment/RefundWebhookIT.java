package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.webhook.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Verified-boundary fixture; real signature verification remains covered by StripeWebhookHttpIT. */
class RefundWebhookIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    WebhookInboxRepository inbox;
    WebhookProcessor processor;
    UUID paymentId;
    String chargeId;

    @BeforeEach
    void setupRefundWebhook() {
        var starts = java.sql.Timestamp.from(fake.clock().instant().minus(Duration.ofDays(1)));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", starts);
        admin.update("UPDATE platform.share_policy_fixture SET valid_from=?", starts);
        var resource = service.create(context, "refund-hook-pay", command("refund-hook-pay"))
                .resource();
        chargeId = fake.controls()
                .attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED)
                .latestChargeId();
        paymentId = payment(resource);
        service.get(context, paymentId);
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_test'
                WHERE id=?""", id(4));
        inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
        var refunds = new RefundRepository(jdbc, tx, json, fake.clock());
        processor = new WebhookProcessor(
                inbox, repository, json, fake.clock(), new RefundWebhookHandler(inbox, repository, refunds));
    }

    void receive(String eventId, String status, String charge, long amount) throws Exception {
        var root = json.createObjectNode();
        root.put("id", eventId);
        root.put("type", "refund.updated");
        root.put("created", fake.clock().instant().getEpochSecond());
        root.put("livemode", false);
        root.put("account", "acct_test");
        root.put("api_version", "2026-07-29.dahlia");
        var body = root.putObject("data").putObject("object");
        body.put("id", "re_hook");
        body.put("object", "refund");
        body.put("charge", charge);
        body.put("status", status);
        body.put("amount", amount);
        body.put("currency", "usd");
        body.put("created", fake.clock().instant().getEpochSecond());
        body.put("balance_transaction", "txn_hook");
        body.put("client_secret", "STRIPPED_TEST_SECRET");
        var verified = new VerifiedWebhook(
                eventId,
                "refund.updated",
                "re_hook",
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                false);
        inbox.receive(
                scope,
                "acct_test",
                root.toString().getBytes(StandardCharsets.UTF_8),
                new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        verified,
                        new ProviderEvidence(
                                null,
                                null,
                                fake.clock().instant(),
                                fake.clock().instant(),
                                200,
                                null,
                                null,
                                "2026-07-29.dahlia")));
    }

    void process() {
        for (var claim : inbox.claimDue(100)) processor.process(claim);
    }

    @Test
    void externalRefundIsProcessedAndDuplicateOrOldPendingCannotUndoSuccess() throws Exception {
        receive("evt_refund_success", "succeeded", chargeId, 4000);
        process();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("DONE");
        assertThat(count("refund")).isEqualTo(1);
        receive("evt_refund_duplicate", "succeeded", chargeId, 4000);
        process();
        receive("evt_refund_old", "pending", chargeId, 4000);
        process();
        assertThat(jdbc.queryForObject("SELECT refund_status FROM platform.refund", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT refunded_minor FROM platform.refund_control WHERE payment_id=?", Long.class, paymentId))
                .isEqualTo(4000);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?", Long.class, paymentId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?",
                        String.class,
                        paymentId))
                .isEqualTo("EXTERNAL_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='refund.succeeded'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT object_snapshot::text FROM platform.webhook_inbox LIMIT 1", String.class))
                .doesNotContain("STRIPPED_TEST_SECRET");
        assertThat(count("journal")).isZero();
    }

    @Test
    void staleInboxOwnerCannotApplyRefundAfterLeaseReclaimed() throws Exception {
        receive("evt_refund_lease", "succeeded", chargeId, 4000);
        var old = inbox.claimDue(1).getFirst();
        fake.clock().advance(Duration.ofSeconds(31));
        var current = inbox.claimDue(1).getFirst();
        processor.process(old);
        assertThat(count("refund")).isZero();
        processor.process(current);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refunded_minor FROM platform.refund_control", Long.class))
                .isEqualTo(4000);
    }

    @Test
    void inboxCompletionFailureRollsBackRefundControlAndTerminalOutbox() throws Exception {
        receive("evt_refund_rollback", "succeeded", chargeId, 4000);
        admin.execute("""
                CREATE FUNCTION platform.pr11_fail_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF
                    NEW.status='DONE' THEN RAISE EXCEPTION 'injected refund acknowledgement crash'; END IF; RETURN
                    NEW; END $$""");
        admin.execute("""
                CREATE TRIGGER pr11_fail_ack BEFORE UPDATE ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION
                    platform.pr11_fail_ack()""");
        try {
            assertThatThrownBy(this::process).isInstanceOf(RuntimeException.class);
            assertThat(count("refund")).isZero();
            assertThat(count("refund_control")).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM platform.outbox_event WHERE event_type LIKE 'refund.%'", Long.class))
                    .isZero();
        } finally {
            admin.execute("DROP TRIGGER pr11_fail_ack ON platform.webhook_inbox");
            admin.execute("DROP FUNCTION platform.pr11_fail_ack()");
        }
        fake.clock().advance(Duration.ofSeconds(31));
        process();
        assertThat(count("refund")).isEqualTo(1);
    }

    @Test
    void changedRefundMoneyKeepsOriginalFactAndFreezesControl() throws Exception {
        receive("evt_refund_original", "succeeded", chargeId, 4000);
        process();
        receive("evt_refund_changed", "succeeded", chargeId, 4001);
        process();
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM platform.refund", Long.class))
                .isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM platform.refund", String.class))
                .isEqualTo("CONFLICT");
        assertThat(jdbc.queryForObject("SELECT refunded_minor FROM platform.refund_control", Long.class))
                .isEqualTo(4000);
        assertThat(count("refund_source_observation")).isEqualTo(2);
    }

    @Test
    void refundBeforeCaptureAssociationRetainsObservationAndReplaysAfterQuery() throws Exception {
        admin.update("""
                UPDATE platform.provider_account
                SET provider='FAKE',provider_account_ref='fixture'
                WHERE id=?""", id(4));
        var p = service.create(context, "refund-late-charge", command("refund-late-charge"))
                .resource();
        String lateCharge = fake.controls()
                .attemptPayment(scope, session(p), FakeControls.AttemptOutcome.SUCCEED)
                .latestChargeId();
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_test'
                WHERE id=?""", id(4));
        receive("evt_refund_missing_charge", "pending", lateCharge, 4000);
        process();
        assertThat(count("refund")).isZero();
        assertThat(count("refund_source_observation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("WAITING");
        admin.update("""
                UPDATE platform.provider_account
                SET provider='FAKE',provider_account_ref='fixture'
                WHERE id=?""", id(4));
        service.get(context, payment(p));
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_test'
                WHERE id=?""", id(4));
        fake.clock().advance(Duration.ofMinutes(1));
        process();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("DONE");
        assertThat(count("refund")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_status FROM platform.refund", String.class))
                .isEqualTo("PENDING");
    }
}
