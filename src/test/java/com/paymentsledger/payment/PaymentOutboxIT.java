package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.fake.FakeControls;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentOutboxIT extends PaymentCoreFixture {
    @Test
    void confirmedCaptureCommitsOneStableEventAndReobservationDoesNotDuplicate() {
        var resource =
                service.create(context, "notification-key", command("order")).resource();
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        service.get(context, payment(resource));
        assertThat(jdbc.queryForObject("SELECT to_regclass('platform.outbox_event')::text", String.class))
                .as("confirmed payment must commit a durable outbox event")
                .isNotNull();
        String payload = jdbc.queryForObject(
                "SELECT payload FROM platform.outbox_event WHERE event_type='payment.succeeded'", String.class);
        service.get(context, payment(resource));
        assertThat(jdbc.queryForList(
                        "SELECT payload FROM platform.outbox_event WHERE event_type='payment.succeeded'", String.class))
                .containsExactly(payload);
    }

    @Test
    void synchronousCreateAndCancelEmitConfirmedChangesOnly() {
        var resource = service.create(context, "sync-notify-key", command("sync-order"))
                .resource();
        assertThat(jdbc.queryForList(
                        "SELECT payload::jsonb->>'paymentStatus' FROM platform.outbox_event ORDER BY resource_version",
                        String.class))
                .containsExactly("CREATED", "AWAITING_CUSTOMER");
        service.get(context, payment(resource));
        assertThat(count("outbox_event")).isEqualTo(2);
        service.cancel(context, payment(resource), "cancel-notify-key", new PaymentRequests.Cancel("OTHER", null));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.canceled'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void webhookConfirmationAndRecoveryQueryUseTheSameAtomicWriter() {
        var webhook = service.create(context, "webhook-notify-key", command("webhook-order"))
                .resource();
        fake.controls().attemptPayment(scope, session(webhook), FakeControls.AttemptOutcome.SUCCEED);
        fake.clock().advance(Duration.ofSeconds(1));
        var observed = executor.query(repository.payment(payment(webhook))).facts();
        new TransactionTemplate(tx).executeWithoutResult(s -> repository.mergeWebhook(payment(webhook), observed));
        new TransactionTemplate(tx).executeWithoutResult(s -> repository.mergeWebhook(payment(webhook), observed));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE payment_id=? AND event_type='payment.succeeded'",
                        Long.class,
                        payment(webhook)))
                .isEqualTo(1);
        var recovery = service.create(context, "recovery-notify-key", command("recovery-order"))
                .resource();
        fake.controls().attemptPayment(scope, session(recovery), FakeControls.AttemptOutcome.SUCCEED);
        fake.clock().advance(Duration.ofSeconds(10));
        new OperationRecoveryCoordinator(
                        repository,
                        executor,
                        new RecoveryPolicy(
                                Duration.ofSeconds(30),
                                Duration.ofDays(1),
                                Duration.ofDays(1),
                                0,
                                Duration.ofHours(1),
                                Duration.ZERO))
                .recoverDue(10);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE payment_id=? AND event_type='payment.succeeded'",
                        Long.class,
                        payment(recovery)))
                .isEqualTo(1);
    }

    @Test
    void unknownOrConflictingStateCannotCreateSuccessAndPayloadIsBoundedAllowlist() throws Exception {
        var resource = service.create(context, "unknown-notify-key", command("unknown-order"))
                .resource();
        admin.update(
                "UPDATE platform.payment SET payment_status='SUCCEEDED',resolution_status='UNKNOWN',captured_amount_minor=amount_minor,version=version+1");
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT',version=version+1");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.succeeded'", Long.class))
                .isZero();
        String payload = jdbc.queryForObject(
                "SELECT payload FROM platform.outbox_event ORDER BY resource_version LIMIT 1", String.class);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertThat(body.size()).isEqualTo(18);
        assertThat(payload).doesNotContain("metadata", "hostedUrl", "successUrl", "secret", "customer");
        assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(16384);
    }

    @Test
    void controlledWriterDoesNotCreateANewEventForUnchangedNonterminalObservation() {
        var resource = service.create(context, "writer-notify-key", command("writer-order"))
                .resource();
        UUID original = jdbc.queryForObject(
                "SELECT id FROM platform.outbox_event WHERE payload::jsonb->>'paymentStatus'='AWAITING_CUSTOMER'",
                UUID.class);
        service.get(context, payment(resource));
        var replay = new TransactionTemplate(tx)
                .execute(s -> new com.paymentsledger.notification.OutboxWriter(jdbc).write(payment(resource)));
        assertThat(replay).isEqualTo(original);
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void partialCaptureDoesNotFreezePrematureSuccessPayloadBeforeFullCapture() {
        service.create(context, "partial-notify-key", command("partial-order"));
        admin.update(
                "UPDATE platform.payment SET payment_status='SUCCEEDED',captured_amount_minor=4000,version=version+1");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.succeeded'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.state_changed' AND payload::jsonb->>'capturedAmountMinor'='4000'",
                        Long.class))
                .isEqualTo(1);
        admin.update("UPDATE platform.payment SET captured_amount_minor=amount_minor,version=version+1");
        assertThat(jdbc.queryForObject(
                        "SELECT payload::jsonb->>'capturedAmountMinor' FROM platform.outbox_event WHERE event_type='payment.succeeded'",
                        String.class))
                .isEqualTo("10000");
    }

    @Test
    void outboxFailureRollsBackConfirmedPaymentState() {
        var resource =
                service.create(context, "notification-key", command("order")).resource();
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        admin.execute(
                "CREATE OR REPLACE FUNCTION platform.fail_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected outbox failure'; END $$");
        admin.execute(
                "CREATE TRIGGER fail_notification BEFORE INSERT ON platform.outbox_event FOR EACH ROW EXECUTE FUNCTION platform.fail_notification()");
        try {
            assertThatThrownBy(() -> service.get(context, payment(resource))).isInstanceOf(RuntimeException.class);
        } finally {
            admin.execute("DROP TRIGGER fail_notification ON platform.outbox_event");
        }
        assertThat(jdbc.queryForObject("SELECT payment_status FROM platform.payment", String.class))
                .isEqualTo("AWAITING_CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payment_attempt", Long.class))
                .isZero();
    }
}
