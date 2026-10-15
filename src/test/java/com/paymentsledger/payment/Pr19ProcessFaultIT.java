package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.webhook.WebhookInboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** F-06 real Process.destroyForcibly windows; lease sample explicitly uses a controlled delayed worker. */
class Pr19ProcessFaultIT extends Pr19ProcessFixture {
    @Test
    void transactionBeforeCommit() throws Exception {
        transactionCrash("transaction-before-commit", 190601, false);
    }

    @Test
    void transactionAfterCommit() throws Exception {
        transactionCrash("transaction-after-commit", 190602, true);
    }

    void transactionCrash(String variant, long seed, boolean committed) throws Exception {
        fault(
                "F-06",
                variant,
                seed,
                "force-kill JVM at production refund transaction commit boundary",
                "new JVM retries same refund API/operation identity",
                e -> {
                    thousandCapture(true);
                    e.put("operationKeys", List.of("pr19-refund-crash", "capture"));
                    long before = count("journal");
                    var child = actor(
                            "crashed",
                            "reserve-refund",
                            Map.of("PR19_KEY", "crash", "PR19_AMOUNT", "600", "PR19_COMMIT_GATE", variant));
                    child.awaitMarker("ready");
                    child.release("ready");
                    var marker = child.awaitMarker(variant);
                    child.killForcibly();
                    e.put("intermediateEvidence", Map.of("checkpoint", marker, "afterKill", durableSnapshot()));
                    assertThat(count("refund")).isEqualTo(committed ? 1 : 0);
                    assertThat(held()).isEqualTo(committed ? 600 : 0);
                    assertThat(count("journal")).isEqualTo(before + (committed ? 1 : 0));
                    assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(committed ? 80 : 200);
                    long started = System.nanoTime();
                    var next = actor("recovery", "reserve-refund", Map.of("PR19_KEY", "crash", "PR19_AMOUNT", "600"));
                    next.awaitMarker("ready");
                    next.release("ready");
                    var result = next.awaitResult();
                    e.put("recoveryMillis", (System.nanoTime() - started) / 1_000_000);
                    assertThat(result.path("value").path("replay").asBoolean()).isEqualTo(committed);
                    assertThat(count("refund")).isEqualTo(1);
                    assertThat(held()).isEqualTo(600);
                    assertThat(count("journal")).isEqualTo(before + 1);
                    assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(80);
                    e.put(
                            "observations",
                            Map.of(
                                    "commitVisibleAfterKill",
                                    committed,
                                    "recoveryResult",
                                    result,
                                    "refundEffects",
                                    fake.counters(scope).refundEffectCount()));
                });
    }

    @Test
    void remoteBeforeCall() throws Exception {
        remoteCrash("remote-before-call", 190603, false);
    }

    @Test
    void remoteAfterCall() throws Exception {
        remoteCrash("remote-after-call", 190604, true);
    }

    void remoteCrash(String variant, long seed, boolean accepted) throws Exception {
        fault(
                "F-06",
                variant,
                seed,
                "force-kill JVM around independent durable Fake CREATE_REFUND call",
                "advance shared clock beyond lease and new JVM common recovery coordinator",
                e -> {
                    thousandCapture(true);
                    var intent = reserve("remote-crash", 600);
                    e.put(
                            "operationKeys",
                            List.of(
                                    "pr18-api-remote-crash",
                                    intent.operationId().toString(),
                                    operationKey(intent.operationId())));
                    var child = actor(
                            "crashed",
                            "execute-refund",
                            Map.of("PR19_OPERATION_ID", intent.operationId().toString(), "PR19_REMOTE_GATE", variant));
                    child.awaitMarker("ready");
                    child.release("ready");
                    var marker = child.awaitMarker(variant);
                    child.killForcibly();
                    e.put("intermediateEvidence", Map.of("checkpoint", marker, "afterKill", durableSnapshot()));
                    assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(accepted ? 1 : 0);
                    assertThat(jdbc.queryForObject(
                                    "SELECT status FROM platform.provider_operation WHERE id=?",
                                    String.class,
                                    intent.operationId()))
                            .isEqualTo("SENDING");
                    assertThat(jdbc.queryForObject(
                                    "SELECT provider_refund_id FROM platform.refund WHERE id=?",
                                    String.class,
                                    intent.refundId()))
                            .isNull();
                    assertThat(held()).isEqualTo(600);
                    long started = System.nanoTime();
                    fake.clock().advance(Duration.ofSeconds(31));
                    recover("recovery");
                    e.put("recoveryMillis", (System.nanoTime() - started) / 1_000_000);
                    assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
                    assertThat(count("refund")).isEqualTo(1);
                    assertThat(held()).isEqualTo(600);
                    assertThat(jdbc.queryForObject(
                                    "SELECT provider_refund_id FROM platform.refund WHERE id=?",
                                    String.class,
                                    intent.refundId()))
                            .isNotBlank();
                    var calls = fake.controls().calls(scope).stream()
                            .filter(x -> x.path("operation").asText().equals("CREATE_REFUND"))
                            .toList();
                    assertThat(calls).hasSize(accepted ? 2 : 1);
                    assertThat(calls)
                            .allSatisfy(x -> assertThat(x.path("target").asText())
                                    .isEqualTo(operationKey(intent.operationId())));
                    e.put(
                            "observations",
                            Map.of(
                                    "acceptedBeforeKill",
                                    accepted,
                                    "refundCreateCalls",
                                    calls,
                                    "independentMoneyEffects",
                                    fake.controls().effects(scope)));
                });
    }

    @Test
    void oldWorkerLeaseIsFencedWithControlledClock() throws Exception {
        fault(
                "F-06",
                "lease-fencing-controlled",
                190605,
                "old JVM pauses after remote acceptance; shared Fake clock expires lease",
                "new JVM reclaims; old JVM returns with stale token (controlled delay, no kill)",
                e -> {
                    thousandCapture(true);
                    var intent = reserve("lease", 600);
                    e.put(
                            "operationKeys",
                            List.of(intent.operationId().toString(), operationKey(intent.operationId())));
                    var old = actor(
                            "old-worker",
                            "execute-refund",
                            Map.of(
                                    "PR19_OPERATION_ID",
                                    intent.operationId().toString(),
                                    "PR19_REMOTE_GATE",
                                    "remote-after-call"));
                    old.awaitMarker("ready");
                    old.release("ready");
                    var checkpoint = old.awaitMarker("remote-after-call");
                    var oldToken = jdbc.queryForObject(
                            "SELECT lease_token::text FROM platform.provider_operation WHERE id=?",
                            String.class,
                            intent.operationId());
                    e.put(
                            "intermediateEvidence",
                            Map.of(
                                    "checkpoint",
                                    checkpoint,
                                    "oldLeaseToken",
                                    oldToken,
                                    "beforeReclaim",
                                    durableSnapshot()));
                    long started = System.nanoTime();
                    fake.clock().advance(Duration.ofSeconds(31));
                    recover("new-worker");
                    var before = jdbc.queryForMap(
                            "SELECT version,provider_refund_id,refund_status FROM platform.refund WHERE id=?",
                            intent.refundId());
                    var operation = jdbc.queryForMap(
                            "SELECT version,lease_token FROM platform.provider_operation WHERE id=?",
                            intent.operationId());
                    assertThat(operation.get("lease_token").toString()).isNotEqualTo(oldToken);
                    old.release("remote-after-call");
                    old.awaitResult();
                    e.put("recoveryMillis", (System.nanoTime() - started) / 1_000_000);
                    assertThat(jdbc.queryForMap(
                                    "SELECT version,provider_refund_id,refund_status FROM platform.refund WHERE id=?",
                                    intent.refundId()))
                            .isEqualTo(before);
                    assertThat(jdbc.queryForMap(
                                    "SELECT version,lease_token FROM platform.provider_operation WHERE id=?",
                                    intent.operationId()))
                            .isEqualTo(operation);
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.provider_observation WHERE operation_id=? AND lease_token::text=?",
                                    Long.class,
                                    intent.operationId(),
                                    oldToken))
                            .isEqualTo(1);
                    assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
                    assertThat(held()).isEqualTo(600);
                    e.put(
                            "observations",
                            Map.of(
                                    "controlledSample",
                                    true,
                                    "processDeath",
                                    false,
                                    "oldLeaseToken",
                                    oldToken,
                                    "newOperation",
                                    operation,
                                    "oldResultRetainedAsObservation",
                                    true));
                });
    }

    @Test
    void inboxBeforeCommit() throws Exception {
        inboxCrash("inbox-before-commit", 190606, false);
    }

    @Test
    void inboxAfterCommit() throws Exception {
        inboxCrash("inbox-after-commit", 190607, true);
    }

    void inboxCrash(String variant, long seed, boolean committed) throws Exception {
        fault(
                "F-06",
                variant,
                seed,
                "force-kill Webhook JVM at business-effects plus Inbox DONE transaction commit",
                "new JVM reclaims Inbox after lease expiry; duplicate same event remains one row",
                e -> {
                    admin.update("UPDATE platform.price_fixture SET amount_minor=1000");
                    var request = new PaymentRequests.Create(
                            "inbox-crash",
                            "demo",
                            1,
                            1000,
                            "USD",
                            1,
                            "https://merchant.test/ok",
                            "https://merchant.test/cancel",
                            null,
                            Map.of());
                    var resource =
                            service.create(context, "inbox-crash", request).resource();
                    paymentId = payment(resource);
                    checkout = session(resource);
                    var intent = fake.controls().attemptPayment(scope, checkout, FakeControls.AttemptOutcome.SUCCEED);
                    chargeId = intent.latestChargeId();
                    admin.update(
                            "UPDATE platform.provider_account SET provider='STRIPE',provider_account_ref='acct_pr19_fixture' WHERE id=?",
                            id(4));
                    var raw = json.createObjectNode()
                            .put("id", "evt_pr19_crash")
                            .put("type", "charge.succeeded")
                            .put("created", fake.clock().instant().getEpochSecond())
                            .put("livemode", false)
                            .put("account", "acct_pr19_fixture")
                            .put("api_version", "fixture-pr19");
                    var object = raw.putObject("data").putObject("object");
                    object.put("object", "charge")
                            .put("id", chargeId)
                            .put("payment_intent", intent.paymentIntentId())
                            .put("status", "succeeded")
                            .put("captured", true)
                            .put("amount", 1000)
                            .put("amount_captured", 1000)
                            .put("currency", "usd");
                    var inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
                    var verification = new ProviderCallResult<>(
                            ProviderCallClassification.SUCCESS,
                            new VerifiedWebhook(
                                    "evt_pr19_crash",
                                    "charge.succeeded",
                                    chargeId,
                                    fake.clock().instant(),
                                    false),
                            new ProviderEvidence(
                                    null,
                                    null,
                                    fake.clock().instant(),
                                    fake.clock().instant(),
                                    200,
                                    null,
                                    null,
                                    "fixture-pr19"));
                    byte[] bytes = raw.toString().getBytes(StandardCharsets.UTF_8);
                    inbox.receive(scope, "acct_pr19_fixture", bytes, verification);
                    e.put("operationKeys", List.of("evt_pr19_crash", paymentId.toString(), chargeId));
                    var child = actor("crashed", "process-inbox", Map.of("PR19_COMMIT_GATE", variant));
                    child.awaitMarker("ready");
                    child.release("ready");
                    var checkpoint = child.awaitMarker(variant);
                    child.killForcibly();
                    e.put("intermediateEvidence", Map.of("checkpoint", checkpoint, "afterKill", durableSnapshot()));
                    assertThat(count("payment_attempt")).isEqualTo(committed ? 1 : 0);
                    assertThat(jdbc.queryForObject(
                                    "SELECT captured_amount_minor FROM platform.payment WHERE id=?",
                                    Long.class,
                                    paymentId))
                            .isEqualTo(committed ? 1000 : 0);
                    assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                            .isEqualTo(committed ? "DONE" : "RUNNING");
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.succeeded'",
                                    Long.class))
                            .isEqualTo(committed ? 1 : 0);
                    long started = System.nanoTime();
                    fake.clock().advance(Duration.ofSeconds(31));
                    var next = actor("recovery", "process-inbox", Map.of());
                    next.awaitMarker("ready");
                    next.release("ready");
                    var result = next.awaitResult();
                    var duplicate = inbox.receive(scope, "acct_pr19_fixture", bytes, verification);
                    e.put("recoveryMillis", (System.nanoTime() - started) / 1_000_000);
                    assertThat(duplicate.duplicate()).isTrue();
                    assertThat(count("webhook_inbox")).isEqualTo(1);
                    assertThat(count("payment_attempt")).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                            .isEqualTo("DONE");
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.succeeded'",
                                    Long.class))
                            .isEqualTo(1);
                    assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                    e.put(
                            "observations",
                            Map.of(
                                    "commitVisibleAfterKill",
                                    committed,
                                    "recovery",
                                    result,
                                    "duplicateReceipt",
                                    duplicate));
                });
    }

    String operationKey(UUID id) {
        return jdbc.queryForObject(
                "SELECT operation_key FROM platform.provider_operation WHERE id=?", String.class, id);
    }

    void recover(String name) throws Exception {
        var child = actor(name, "recover", Map.of());
        child.awaitMarker("ready");
        child.release("ready");
        child.awaitResult();
    }
}
