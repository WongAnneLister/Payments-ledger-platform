package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.GlobalExceptionHandler;
import com.paymentsledger.financialingestion.FinancialImportService;
import com.paymentsledger.notification.FulfillmentEligibility;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import com.paymentsledger.webhook.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** F01-04: signed raw-byte boundary plus real PostgreSQL production recovery/ingestion paths. */
class Pr19WebhookFaultIT extends Pr19FaultFixture {
    static final String ACCOUNT = "acct_pr19fixture",
            SECRET = "whsec_pr19_LOCAL_SIGNED_FIXTURE_ONLY",
            VERSION = "2026-07-29.dahlia";
    WebhookInboxRepository inbox;
    WebhookProcessor processor;
    MockMvc http;

    void webhook() {
        admin.update(
                "UPDATE platform.provider_account SET provider='STRIPE',provider_account_ref=? WHERE id=?",
                ACCOUNT,
                id(4));
        inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
        processor = new WebhookProcessor(
                inbox,
                repository,
                json,
                fake.clock(),
                new RefundWebhookHandler(inbox, repository, refunds),
                new DisputeWebhookHandler(inbox, repository, disputes),
                new PayoutWebhookHandler(inbox, payouts));
        var env = new MockEnvironment()
                .withProperty("platform.stripe.organization-id", id(1).toString())
                .withProperty("platform.stripe.environment-id", id(2).toString())
                .withProperty("platform.stripe.provider-account-id", id(4).toString())
                .withProperty("platform.stripe.account-id", ACCOUNT)
                .withProperty("platform.stripe.webhook-secret", SECRET)
                .withProperty("platform.stripe.webhook-api-version", VERSION);
        var beans = new StaticListableBeanFactory();
        beans.addBean("endpoint", new StripeWebhookEndpoint(env, fake.clock()));
        http = MockMvcBuilders.standaloneSetup(
                        new StripeWebhookController(beans.getBeanProvider(StripeWebhookEndpoint.class), inbox))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    byte[] raw(String event, String type, Map<String, Object> object, Instant created) throws Exception {
        // Deliberate whitespace and UTF-8 prove that exact input bytes, not reserialization, are signed.
        return (" \n"
                        + json.writeValueAsString(Map.of(
                                "id",
                                event,
                                "object",
                                "event",
                                "type",
                                type,
                                "created",
                                created.getEpochSecond(),
                                "livemode",
                                false,
                                "api_version",
                                VERSION,
                                "data",
                                Map.of("object", object)))
                        + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    String signature(byte[] raw, long timestamp, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(raw));
    }

    int receive(byte[] bytes, String signed) throws Exception {
        webhookRequests++;
        var response = http.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/stripe")
                                .contentType("application/json")
                                .header("Stripe-Signature", signed)
                                .content(bytes))
                .andReturn()
                .getResponse();
        observations.add(Map.of(
                "kind",
                "SIGNED_LOCAL_FIXTURE_HTTP",
                "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "bytes",
                bytes.length,
                "responseStatus",
                response.getStatus(),
                "responseBody",
                response.getContentAsString(),
                "signatureSource",
                "TEST_ONLY_HMAC_SHA256 over original bytes; production Stripe verifier"));
        return response.getStatus();
    }

    void receive(String event, String type, Map<String, Object> object) throws Exception {
        receive(event, type, object, fake.clock().instant());
    }

    void receive(String event, String type, Map<String, Object> object, Instant created) throws Exception {
        byte[] bytes = raw(event, type, object, created);
        assertThat(receive(bytes, signature(bytes, fake.clock().instant().getEpochSecond(), SECRET)))
                .isEqualTo(200);
        processor.processDue(100);
    }

    Map<String, Object> intentObject(String pi, UUID payment, String status) {
        return Map.of(
                "id",
                pi,
                "object",
                "payment_intent",
                "status",
                status,
                "amount",
                10000,
                "amount_received",
                status.equals("succeeded") ? 10000 : 0,
                "currency",
                "usd",
                "metadata",
                Map.of("payment_id", payment.toString()));
    }

    Map<String, Object> chargeObject(String pi, String charge) {
        return Map.of(
                "id",
                charge,
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
                pi,
                "balance_transaction",
                "capture");
    }

    String remoteCapture() {
        var pi = fake.controls().attemptPayment(scope, checkout, FakeControls.AttemptOutcome.SUCCEED);
        chargeId = pi.latestChargeId();
        fake.controls().linkChargeBalanceTransaction(scope, chargeId, "capture");
        return pi.paymentIntentId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"known-l1", "known-l2"})
    void droppedWebhookKnownOperation(String variant) throws Exception {
        fault(
                "F-01",
                variant,
                190101,
                "persisted Fake payment capture; emit notification but omit delivery",
                variant.equals("known-l1") ? "L1 service.get authoritative query" : "L2 scanL2 and recoverDue",
                e -> {
                    createPayment();
                    String pi = remoteCapture();
                    if (variant.equals("known-l2")) {
                        webhook();
                        receive(
                                "evt_pr19_partial_session",
                                "checkout.session.completed",
                                Map.of(
                                        "id",
                                        checkout,
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
                                        pi,
                                        "metadata",
                                        Map.of("payment_id", paymentId.toString())));
                        admin.update(
                                "UPDATE platform.provider_account SET provider='FAKE',provider_account_ref='fixture' WHERE id=?",
                                id(4));
                        assertThat(jdbc.queryForObject(
                                        "SELECT captured_amount_minor FROM platform.payment", Long.class))
                                .isZero();
                    }
                    String event = fake.controls().emitWebhook(scope, "charge.succeeded", chargeId);
                    assertThat(fake.controls().takeDeliveries(scope)).isEmpty();
                    assertThat(count("webhook_inbox")).isEqualTo(variant.equals("known-l2") ? 1 : 0);
                    fake.controls()
                            .enqueue(
                                    scope,
                                    FailureScript.Operation.QUERY_SESSION,
                                    checkout,
                                    FailureScript.Outcome.TRANSIENT_EMPTY);
                    service.get(context, paymentId);
                    assertThat(repository.read(context, paymentId)).containsEntry("resolutionStatus", "UNKNOWN");
                    if (variant.equals("known-l2"))
                        admin.update(
                                "UPDATE platform.recovery_task SET status='DONE',available_at=? WHERE payment_id=?",
                                java.sql.Timestamp.from(fake.clock().instant()),
                                paymentId);
                    e.put("intermediateEvidence", snapshotFault());
                    observations.add(Map.of("droppedEventId", event, "delivered", false));
                    recovery(e, ignored -> {
                        if (variant.equals("known-l1")) service.get(context, paymentId);
                        else {
                            fake.clock().advance(Duration.ofSeconds(16));
                            var coordinator = new OperationRecoveryCoordinator(repository, executor);
                            coordinator.scanL2(100);
                            coordinator.recoverDue(100);
                        }
                        post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
                        deliverNativeSucceededTwice();
                    });
                    assertThat(repository.read(context, paymentId))
                            .containsEntry("paymentStatus", "SUCCEEDED")
                            .containsEntry("resolutionStatus", "KNOWN");
                    assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
                    assertThat(sourceJournals("capture")).isEqualTo(1);
                    assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                    assertThat(merchant.effects()).isEqualTo(1);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"trusted-order-l3", "no-order-l3"})
    void droppedWebhookWithoutPayment(String variant) throws Exception {
        fault(
                "F-01",
                variant,
                190102,
                "external capture with no local payment/operation; all webhook deliveries omitted",
                "L1/L2 prove no coverage; L3 authoritative BT scan + ReconciliationSourceRecovery",
                e -> {
                    var accepted = fake.createSession(new CreateCheckoutSessionCommand(
                            scope,
                            "pr19-external-capture",
                            10000,
                            "USD",
                            "https://merchant.test/ok",
                            "https://merchant.test/cancel",
                            Map.of(),
                            Map.of(),
                            fake.clock().instant().plusSeconds(3600)));
                    checkout = accepted.payload().sessionId();
                    String pi = remoteCapture();
                    UUID order = null;
                    if (variant.equals("trusted-order-l3")) {
                        order = UUID.randomUUID();
                        admin.update(
                                "INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot) VALUES(?,?,?,?,?,'USD','pr19-trusted-order',?,'demo',1,1,10000,10000,'{}','{\"policy_version\":1,\"partner_ref\":\"partner\",\"share_basis_points\":2000}')",
                                order,
                                id(1),
                                id(2),
                                id(3),
                                id(4),
                                "a".repeat(64));
                        admin.update(
                                "INSERT INTO platform.order_provider_binding(organization_id,environment_id,application_id,provider_account_id,livemode,currency,order_id,payment_intent_id,amount_minor,proof_kind,proof_ref,proof_digest) VALUES(?,?,?,?,false,'USD',?,?,10000,'SERVER_ORDER_FIXTURE','pr19-independent-order',?)",
                                id(1),
                                id(2),
                                id(3),
                                id(4),
                                order,
                                pi,
                                "c".repeat(64));
                    }
                    var coordinator = new OperationRecoveryCoordinator(repository, executor);
                    coordinator.recoverDue(100);
                    coordinator.scanL2(100);
                    coordinator.recoverDue(100);
                    assertThat(count("payment")).isZero();
                    assertThat(count("provider_operation")).isZero();
                    var capture = bt("capture", "charge", 10000, 300, chargeId, "charge");
                    fake.controls()
                            .putBalanceTransaction(scope, capture, fake.clock().instant(), null);
                    providerFacts.put("capture", capture);
                    importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock(), shares);
                    reconciliation = new com.paymentsledger.reconciliation.ReconciliationService(
                            jdbc,
                            tx,
                            json,
                            importer,
                            new com.paymentsledger.ledger.BalanceRebuildService(jdbc, fake.clock()),
                            matcher,
                            fake.clock());
                    var run = reconciliation.createBackfill(scope, "USD", from, until, ZoneOffset.UTC);
                    reconciliation.advance(run, 10);
                    e.put("intermediateEvidence", snapshotFault());
                    observations.add(Map.of(
                            "externalOperationKey",
                            "pr19-external-capture",
                            "localInitialState",
                            variant,
                            "L1L2Payments",
                            0));
                    recovery(e, ignored -> {
                        var l3 = new ReconciliationSourceRecovery(
                                jdbc, tx, json, fake, fake, repository, replay, fake.clock());
                        l3.drain(scope, run, 100);
                        l3.drain(scope, run, 100);
                    });
                    if (order != null) {
                        assertThat(count("payment")).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT business_order_id FROM platform.payment", UUID.class))
                                .isEqualTo(order);
                        assertThat(sourceJournals("capture")).isEqualTo(1);
                        assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
                        var event = event(jdbc.queryForMap(
                                "SELECT * FROM platform.outbox_event WHERE event_type='payment.succeeded'"));
                        assertThat(event.origin()).isEqualTo("RECONCILIATION");
                        assertThat(new FulfillmentEligibility.Database(jdbc)
                                        .evaluate(event)
                                        .eligible())
                                .isTrue();
                    } else {
                        assertThat(count("business_order")).isZero();
                        assertThat(count("payment")).isZero();
                        assertThat(count("journal")).isZero();
                        assertThat(count("outbox_event")).isZero();
                        assertThat(count("reconciliation_source_observation")).isPositive();
                        assertThat(jdbc.queryForObject(
                                        "SELECT reason FROM platform.recovery_task WHERE task_type='RECONCILIATION_SOURCE'",
                                        String.class))
                                .isEqualTo("NO_TRUSTED_ORDER");
                        assertThat(merchant.effects()).isZero();
                    }
                    assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-event", "same-charge-bt", "concurrent-same-bt"})
    void duplicateWebhookAndFinancialFact(String variant) throws Exception {
        fault(
                "F-02",
                variant,
                190202,
                "duplicate signed event or distinct event referencing same Charge/BT; same BT races actual PG import/replay",
                "WebhookProcessor then canonical FinancialImport/Replay; signed duplicate delivery to independent merchant DB",
                e -> {
                    createPayment();
                    String pi = remoteCapture();
                    webhook();
                    receive("evt_pr19_pi", "payment_intent.succeeded", intentObject(pi, paymentId, "succeeded"));
                    byte[] first = raw(
                            "evt_pr19_charge",
                            "charge.succeeded",
                            chargeObject(pi, chargeId),
                            fake.clock().instant());
                    String sig = signature(first, fake.clock().instant().getEpochSecond(), SECRET);
                    assertThat(receive(first, sig)).isEqualTo(200);
                    processor.processDue(100);
                    e.put("intermediateEvidence", snapshotFault());
                    recovery(e, ignored -> {
                        if (variant.equals("same-event")) {
                            assertThat(receive(first, sig)).isEqualTo(200);
                            processor.processDue(100);
                        } else receive("evt_pr19_charge2", "charge.succeeded", chargeObject(pi, chargeId));
                        var capture = bt("capture", "charge", 10000, 300, chargeId, "charge");
                        if (variant.equals("concurrent-same-bt")) {
                            jdbc.setQueryTimeout(15);
                            pool.setConnectionTimeout(5000);
                            providerFacts.put("capture", capture);
                            for (int i = 0; i < 2; i++)
                                pages.add(new com.paymentsledger.provider.ProviderCallResult<>(
                                        com.paymentsledger.provider.ProviderCallClassification.SUCCESS,
                                        new BalanceTransactionPage(List.of(capture), false, null),
                                        evidence()));
                            UUID a = importer.createWindow(scope, from, until, "BACKFILL"),
                                    b = importer.createWindow(scope, from.minusSeconds(1), until, "BACKFILL");
                            var barrier = new CyclicBarrier(2);
                            var workers = Executors.newFixedThreadPool(2);
                            var futures = new ArrayList<Future<?>>();
                            try {
                                var one = workers.submit(() -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return importer.importPage(a);
                                });
                                futures.add(one);
                                var two = workers.submit(() -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return importer.importPage(b);
                                });
                                futures.add(two);
                                assertThat(one.get(30, TimeUnit.SECONDS)).isEqualTo("COMPLETE");
                                assertThat(two.get(30, TimeUnit.SECONDS)).isEqualTo("COMPLETE");
                                var replayBarrier = new CyclicBarrier(2);
                                var r1 = workers.submit(() -> {
                                    replayBarrier.await(10, TimeUnit.SECONDS);
                                    return replay.replay(scope, "capture");
                                });
                                futures.add(r1);
                                var r2 = workers.submit(() -> {
                                    replayBarrier.await(10, TimeUnit.SECONDS);
                                    return replay.replay(scope, "capture");
                                });
                                futures.add(r2);
                                assertThat(r1.get(30, TimeUnit.SECONDS)).isEqualTo("POSTED");
                                assertThat(r2.get(30, TimeUnit.SECONDS)).isEqualTo("POSTED");
                            } finally {
                                for (var future : futures) future.cancel(true);
                                workers.shutdownNow();
                                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS))
                                        .as("bounded experiment worker cleanup")
                                        .isTrue();
                            }
                            observations.add(Map.of(
                                    "race",
                                    "two threads/two PG connections/import windows; F08 covers separate JVMs",
                                    "barrierParties",
                                    2));
                        } else {
                            post(capture);
                            ingest(capture);
                            assertThat(replay.replay(scope, "capture")).isEqualTo("POSTED");
                        }
                        deliverNativeSucceededTwice();
                    });
                    assertThat(count("webhook_inbox")).isEqualTo(variant.equals("same-event") ? 2 : 3);
                    assertThat(count("payment_attempt")).isEqualTo(1);
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.normalized_observation WHERE fact_kind='CAPTURE'",
                                    Long.class))
                            .isEqualTo(1);
                    assertThat(count("balance_transaction")).isEqualTo(1);
                    assertThat(sourceJournals("capture")).isEqualTo(1);
                    assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
                    assertThat(merchant.effects()).isEqualTo(1);
                    assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund-bt-first", "dispute-bt-first", "old-failure-after-success", "payout-return-first"})
    void outOfOrderFactsConverge(String variant) throws Exception {
        fault(
                "F-03",
                variant,
                190303,
                "dependency BT precedes resource; old failure follows successful capture; payout return before payout result",
                "retain PENDING_EVIDENCE then signed dependency delivery and canonical replay",
                e -> {
                    createPayment();
                    String pi = remoteCapture();
                    service.get(context, paymentId);
                    post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
                    String refundId = null;
                    if (variant.equals("refund-bt-first")) {
                        refundId = fake.createRefund(new CreateRefundCommand(
                                        scope, "pr19-external-refund", chargeId, null, 1000, null))
                                .payload()
                                .refundId();
                        fake.controls().setRefundStatus(scope, refundId, "succeeded", null);
                        fake.controls().linkRefundTransactions(scope, refundId, "refund", null);
                        ingest(bt("refund", "refund", -1000, 0, refundId, "refund"));
                        assertThat(replay.replay(scope, "refund")).isEqualTo("PENDING_EVIDENCE");
                        assertThat(count("refund")).isZero();
                    } else if (variant.equals("dispute-bt-first")) {
                        ingest(bt("dispute_debit", "adjustment", -3000, 1500, "du_pr19", "dispute"));
                        assertThat(replay.replay(scope, "dispute_debit")).isEqualTo("PENDING_EVIDENCE");
                        assertThat(count("dispute")).isZero();
                    } else if (variant.equals("payout-return-first")) {
                        ingest(bt("payout_return", "payout_failure", 1200, 0, "po_pr19", "payout"));
                        assertThat(replay.replay(scope, "payout_return")).isEqualTo("PENDING_EVIDENCE");
                        assertThat(count("payout")).isZero();
                    }
                    webhook();
                    e.put("intermediateEvidence", snapshotFault());
                    String re = refundId;
                    recovery(e, ignored -> {
                        switch (variant) {
                            case "refund-bt-first" -> {
                                receive(
                                        "evt_refund",
                                        "refund.updated",
                                        Map.of(
                                                "id",
                                                re,
                                                "object",
                                                "refund",
                                                "charge",
                                                chargeId,
                                                "status",
                                                "succeeded",
                                                "amount",
                                                1000,
                                                "currency",
                                                "usd",
                                                "balance_transaction",
                                                "refund"));
                                assertThat(replay.replay(scope, "refund")).isEqualTo("POSTED");
                                assertThat(replay.replay(scope, "refund")).isEqualTo("POSTED");
                                assertThat(balance("PSP_FUNDS")).isEqualTo(8700);
                                assertThat(count("refund")).isEqualTo(1);
                            }
                            case "dispute-bt-first" -> {
                                receive(
                                        "evt_dispute",
                                        "charge.dispute.closed",
                                        Map.of(
                                                "id",
                                                "du_pr19",
                                                "object",
                                                "dispute",
                                                "charge",
                                                chargeId,
                                                "status",
                                                "lost",
                                                "reason",
                                                "fraudulent",
                                                "amount",
                                                3000,
                                                "currency",
                                                "usd",
                                                "balance_transactions",
                                                List.of("dispute_debit")));
                                assertThat(replay.replay(scope, "dispute_debit"))
                                        .isEqualTo("POSTED");
                                assertThat(replay.replay(scope, "dispute_debit"))
                                        .isEqualTo("POSTED");
                                assertThat(balance("PSP_FUNDS")).isEqualTo(5200);
                                assertThat(count("dispute")).isEqualTo(1);
                            }
                            case "old-failure-after-success" -> {
                                receive(
                                        "evt_old_failure",
                                        "payment_intent.payment_failed",
                                        intentObject(pi, paymentId, "requires_payment_method"),
                                        fake.clock().instant().minusSeconds(60));
                                assertThat(repository.read(context, paymentId))
                                        .containsEntry("paymentStatus", "SUCCEEDED");
                                assertThat(jdbc.queryForObject(
                                                "SELECT captured_amount_minor FROM platform.payment", Long.class))
                                        .isEqualTo(10000);
                                assertThat(sourceJournals("capture")).isEqualTo(1);
                            }
                            case "payout-return-first" -> {
                                receive("evt_payout_pending", "payout.created", payoutObject("pending"));
                                post(bt("payout_out", "payout", -5700, 0, "po_pr19", "payout"));
                                assertThat(replay.replay(scope, "payout_return"))
                                        .isEqualTo("POSTED");
                                assertThat(payouts.lookup(scope, "po_pr19").get("returned_minor"))
                                        .isEqualTo(1200L);
                                receive("evt_payout_failed", "payout.failed", payoutObject("failed"));
                                assertThat(replay.replay(scope, "payout_return"))
                                        .isEqualTo("POSTED");
                                assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(4500);
                            }
                            default -> throw new AssertionError(variant);
                        }
                    });
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.webhook_inbox WHERE status<>'DONE'", Long.class))
                            .isZero();
                    assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                    observations.add(Map.of(
                            "originalOrder",
                            variant,
                            "finalCanonicalEvents",
                            jdbc.queryForList("SELECT event_type,source_ref FROM platform.financial_event")));
                });
    }

    Map<String, Object> payoutObject(String status) {
        return Map.of(
                "id",
                "po_pr19",
                "object",
                "payout",
                "status",
                status,
                "amount",
                5700,
                "currency",
                "usd",
                "automatic",
                false,
                "arrival_date",
                until.getEpochSecond(),
                "balance_transaction",
                "payout_out",
                "failure_balance_transaction",
                "payout_return");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "body-byte-tamper",
                "wrong-signature",
                "wrong-endpoint-secret",
                "outside-tolerance",
                "inbox-persistence-failure",
                "legal-raw-bytes"
            })
    void signedRawByteBoundary(String variant) throws Exception {
        fault(
                "F-04",
                variant,
                190404,
                "local HMAC fixture: byte/signature/secret/timestamp fault or INSERT trigger failure",
                "production StripeWebhookController/verifier; legal fixture replay after rejecting or removing persistence trigger",
                e -> {
                    webhook();
                    byte[] legal = raw(
                            "evt_pr19_security",
                            "payment_intent.succeeded",
                            Map.of("id", "pi_security", "object", "payment_intent", "note", "合法 UTF-8 fixture"),
                            fake.clock().instant());
                    long timestamp = fake.clock().instant().getEpochSecond();
                    String valid = signature(legal, timestamp, SECRET);
                    byte[] supplied = legal.clone();
                    String signed = valid;
                    switch (variant) {
                        case "body-byte-tamper" -> supplied[0] = '\t';
                        case "wrong-signature" -> signed = "t=" + timestamp + ",v1=" + "0".repeat(64);
                        case "wrong-endpoint-secret" ->
                            signed = signature(legal, timestamp, "whsec_DIFFERENT_LOCAL_ENDPOINT");
                        case "outside-tolerance" -> signed = signature(legal, timestamp - 301, SECRET);
                        case "inbox-persistence-failure" -> {
                            admin.execute(
                                    "CREATE FUNCTION platform.pr19_fail_inbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'PR19_INJECTED_INBOX_UNAVAILABLE'; END $$");
                            admin.execute(
                                    "CREATE TRIGGER pr19_fail_inbox BEFORE INSERT ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION platform.pr19_fail_inbox()");
                        }
                        case "legal-raw-bytes" -> {}
                        default -> throw new AssertionError(variant);
                    }
                    int response;
                    try {
                        response = receive(supplied, signed);
                        if (variant.equals("legal-raw-bytes")) {
                            assertThat(response).isEqualTo(200);
                            assertThat(count("webhook_inbox")).isEqualTo(1);
                        } else {
                            assertThat(response).isEqualTo(variant.equals("inbox-persistence-failure") ? 503 : 400);
                            assertThat(count("webhook_inbox")).isZero();
                        }
                    } finally {
                        if (variant.equals("inbox-persistence-failure")) {
                            admin.execute("DROP TRIGGER pr19_fail_inbox ON platform.webhook_inbox");
                            admin.execute("DROP FUNCTION platform.pr19_fail_inbox()");
                        }
                    }
                    assertThat(count("payment")).isZero();
                    assertThat(count("normalized_observation")).isZero();
                    assertThat(count("journal")).isZero();
                    assertThat(count("outbox_event")).isZero();
                    assertThat(fake.controls().effects(scope)).isEmpty();
                    assertThat(merchant.effects()).isZero();
                    e.put("intermediateEvidence", snapshotFault());
                    recovery(e, ignored -> {
                        assertThat(receive(legal, valid)).isEqualTo(200);
                        assertThat(count("webhook_inbox")).isEqualTo(1);
                    });
                    assertThat(jdbc.queryForObject("SELECT raw_digest FROM platform.webhook_inbox", String.class))
                            .isEqualTo(HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(legal)));
                });
    }
}
