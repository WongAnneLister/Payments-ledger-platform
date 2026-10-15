package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationPipelineIT extends NotificationFixture {
    @Test
    void committedBusinessSurvivesPublisherCrashAndOldLeaseCannotFinish() {
        var event = success();
        var repo = outbox();
        var first = repo.claim(Duration.ofSeconds(30));
        assertThat(first).isNotNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
        admin.update(
                "UPDATE platform.outbox_event SET lease_until=now()-interval '1 second' WHERE id=?",
                first.event().id());
        var second = repo.claim(Duration.ofSeconds(30));
        assertThat(second.event().id()).isEqualTo(first.event().id());
        assertThat(second.event().payload()).isEqualTo(first.event().payload());
        assertThat(repo.published(first)).isFalse();
        assertThat(repo.published(second)).isTrue();
        assertThat(repo.find(event.id())).isNotNull();
    }

    @Test
    void concurrentConsumersCreateOneEffectAndAckOnlyAfterCommit() throws Exception {
        var event = success();
        var calls = new ArrayList<Future<?>>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++)
                calls.add(threads.submit(() -> inbox().consume(event.bytes(), () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    assertThat(count("delivery_task")).isEqualTo(1);
                })));
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("consumer_inbox")).isEqualTo(1);
        assertThat(count("delivery_task")).isEqualTo(1);
    }

    @Test
    void consumerFailureRollsBackInboxAndNeverAcknowledgesThenRetryWorks() {
        var event = success();
        var ack = new AtomicInteger();
        admin.execute(
                "CREATE OR REPLACE FUNCTION platform.fail_delivery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        admin.execute(
                "CREATE TRIGGER fail_delivery BEFORE INSERT ON platform.delivery_task FOR EACH ROW EXECUTE FUNCTION platform.fail_delivery()");
        try {
            assertThatThrownBy(() -> inbox().consume(event.bytes(), ack::incrementAndGet))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            admin.execute("DROP TRIGGER fail_delivery ON platform.delivery_task");
        }
        assertThat(ack).hasValue(0);
        assertThat(count("consumer_inbox")).isZero();
        assertThatThrownBy(() -> inbox().consume(event.bytes(), () -> {
                    throw new IllegalStateException("crash after commit before ack");
                }))
                .isInstanceOf(IllegalStateException.class);
        inbox().consume(event.bytes(), ack::incrementAndGet);
        assertThat(ack).hasValue(1);
        assertThat(count("delivery_task")).isEqualTo(1);
    }

    @Test
    void forgedOrMalformedBrokerBodiesAreDurablyRejectedWithoutDelivery() {
        var event = success();
        inbox().consume("bad".getBytes(StandardCharsets.UTF_8), () -> {});
        inbox().consume(event.payload().replace("10000", "99999").getBytes(StandardCharsets.UTF_8), () -> {});
        assertThat(count("notification_rejection")).isEqualTo(2);
        assertThat(count("delivery_task")).isZero();
    }

    @Test
    void missingPolicyAndTargetAreHeldAndTargetChangeNeverRedirects() {
        var event = success();
        inbox().accept(event.bytes());
        var repo = delivery();
        assertThat(repo.claim(Duration.ofSeconds(30))).isNull();
        assertThat(state()).isEqualTo("HELD");
        target();
        due();
        var claim = repo.claim(Duration.ofSeconds(30));
        assertThat(claim).isNotNull();
        admin.update(
                "UPDATE platform.merchant_notification_target SET url='https://other.example/notifications',version=2");
        assertThat(repo.recheck(claim)).isFalse();
        assertThat(state()).isEqualTo("HELD");
        due();
        assertThat(repo.claim(Duration.ofSeconds(30))).isNull();
        assertThat(jdbc.queryForObject("SELECT target_url FROM platform.delivery_task", String.class))
                .isEqualTo("https://merchant.example/notifications");
    }

    @Test
    void explicitOriginalOrderPolicyAndCurrentCaptureRequiredAtSend() {
        var event = success();
        inbox().accept(event.bytes());
        target();
        admin.update("UPDATE platform.order_fulfillment_eligibility SET eligible=false");
        assertThat(delivery().claim(Duration.ofSeconds(30))).isNull();
        assertThat(state()).isEqualTo("HELD");
        admin.update("UPDATE platform.order_fulfillment_eligibility SET eligible=true,origin='RECONCILIATION'");
        due();
        assertThat(delivery().claim(Duration.ofSeconds(30))).isNull();
    }

    @Test
    void retriesAreBoundedAndExpiredTokenCannotCompleteNewClaim() {
        var event = success();
        inbox().accept(event.bytes());
        target();
        var repo = delivery();
        var first = repo.claim(Duration.ofSeconds(30));
        admin.update("UPDATE platform.delivery_task SET lease_until=now()-interval '1 second'");
        var second = repo.claim(Duration.ofSeconds(30));
        var failed = new MerchantTransport.Result(MerchantTransport.Outcome.RETRYABLE, 503, "HTTP_RETRYABLE");
        assertThat(repo.complete(first, failed, 2, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isFalse();
        assertThat(repo.complete(second, failed, 2, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isTrue();
        assertThat(state()).isEqualTo("DEAD");
    }

    @Test
    void transportRunsOutsideTransactionAndPermanentFailureIsVisible() {
        var event = success();
        inbox().accept(event.bytes());
        target();
        var transport = (MerchantTransport) (t, id, key, body) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(id).isEqualTo(event.id());
            assertThat(body).isEqualTo(event.bytes());
            return new MerchantTransport.Result(MerchantTransport.Outcome.PERMANENT_REJECT, 400, "HTTP_PERMANENT");
        };
        new MerchantNotifier(delivery(), transport).drain(1);
        assertThat(state()).isEqualTo("DEAD");
    }

    @Test
    void claimsAcrossConnectionsDoNotOverlap() throws Exception {
        success();
        success();
        var claims = ConcurrentHashMap.<UUID>newKeySet();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++)
                futures.add(threads.submit(() -> {
                    var c = outbox().claim(Duration.ofSeconds(30));
                    if (c != null) assertThat(claims.add(c.event().id())).isTrue();
                }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(claims).hasSize((int) count("outbox_event"));
    }

    @Test
    void writerRequiresTransactionAndEvidenceCannotBeChangedOrDeleted() {
        var event = success();
        assertThatThrownBy(() -> new OutboxWriter(jdbc).write(event.paymentId()))
                .isInstanceOf(IllegalStateException.class);
        new TransactionTemplate(tx)
                .executeWithoutResult(s -> assertThat(new OutboxWriter(jdbc).write(event.paymentId()))
                        .isEqualTo(event.id()));
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.outbox_event SET payload='{}' WHERE id=?", event.id()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.outbox_event"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void missingSigningKeyRemainsHeldWithoutConsumingNetworkRetryAllowance() {
        var event = success();
        inbox().accept(event.bytes());
        target();
        var notifier = new MerchantNotifier(
                delivery(),
                (t, id, key, body) ->
                        new MerchantTransport.Result(MerchantTransport.Outcome.RETRYABLE, 0, "KEY_UNAVAILABLE"));
        notifier.drain(1);
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.delivery_task", Integer.class))
                .isZero();
    }

    @Test
    void durableBacklogHeldDeadAndAgeAreObservable() {
        var event = success();
        inbox().accept(event.bytes());
        delivery().claim(Duration.ofSeconds(30));
        var metrics = new NotificationMetrics(jdbc, fake.clock());
        assertThat(metrics.value("held")).isEqualTo(1);
        assertThat(metrics.value("dead")).isZero();
        assertThat(metrics.value("delivered")).isZero();
        assertThat(metrics.value("retry_backlog")).isZero();
        assertThat(metrics.value("delivery_attempts")).isZero();
        assertThat(metrics.value("outbox_backlog")).isEqualTo(count("outbox_event"));
        assertThat(metrics.value("delivery_backlog")).isEqualTo(1);
        assertThat(metrics.value("oldest_age_seconds")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void consumerFreezesConfiguredTargetBeforeFirstClaim() {
        var event = success();
        target();
        inbox().accept(event.bytes());
        admin.update(
                "UPDATE platform.merchant_notification_target SET url='https://other.example/notifications',version=2");
        assertThat(delivery().claim(Duration.ofSeconds(30))).isNull();
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT target_url FROM platform.delivery_task", String.class))
                .isEqualTo("https://merchant.example/notifications");
    }

    @Test
    void publisherRetryBackoffGrowsAndCapsWithoutDroppingDurableObligation() {
        var event = success();
        admin.update("UPDATE platform.outbox_event SET status='PUBLISHED' WHERE id<>?", event.id());
        var publisher = new OutboxPublisher(
                outbox(),
                e -> {
                    throw new IllegalStateException("offline");
                },
                Duration.ofSeconds(30));
        publisher.drain(1);
        assertThat(publishDelay()).isEqualTo(5);
        fake.clock().advance(Duration.ofSeconds(6));
        publisher.drain(1);
        assertThat(publishDelay()).isEqualTo(10);
        admin.update(
                "UPDATE platform.outbox_event SET attempt_count=30,available_at=now()-interval '1 minute' WHERE id=?",
                event.id());
        publisher.drain(1);
        assertThat(publishDelay()).isEqualTo(3600);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.outbox_event WHERE id=?", String.class, event.id()))
                .isEqualTo("READY");
    }

    @Test
    void runtimeCannotMountDefinerPolicyTriggerOnATemporaryTableToAuthorizeLegacyOrder() {
        var event = success();
        admin.update("DELETE FROM platform.order_fulfillment_eligibility WHERE order_id=?", event.orderId());
        assertThat(count("order_fulfillment_eligibility")).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(tx).executeWithoutResult(s -> {
                    jdbc.execute(
                            "CREATE TEMP TABLE spoof_order (LIKE platform.business_order INCLUDING DEFAULTS) ON COMMIT DROP");
                    jdbc.execute(
                            "CREATE TRIGGER spoof_policy AFTER INSERT ON spoof_order FOR EACH ROW EXECUTE FUNCTION platform.pr09_native_order_policy()");
                    jdbc.update(
                            "INSERT INTO spoof_order SELECT * FROM platform.business_order WHERE id=?",
                            event.orderId());
                    assertThat(count("order_fulfillment_eligibility"))
                            .as("unguarded definer trigger creates policy for an existing order")
                            .isEqualTo(1);
                }))
                .isInstanceOfSatisfying(
                        org.springframework.dao.DataAccessException.class,
                        error -> assertThat(((java.sql.SQLException) error.getMostSpecificCause()).getSQLState())
                                .isEqualTo("42501"));
        assertThat(count("order_fulfillment_eligibility")).isZero();
        service.create(context, "legitimate-native-key", command("legitimate-native-order"));
        assertThat(count("order_fulfillment_eligibility"))
                .as("migration-owned original trigger still authorizes genuinely new native orders")
                .isEqualTo(1);
    }

    @Test
    void runtimeCannotForgeAuthoritativeOutboxPayloadAndBypassFulfillmentChecks() {
        var source = success();
        target();
        UUID forged = UUID.randomUUID();
        UUID forgedOrder = UUID.randomUUID();
        String body = source.payload()
                .replace(source.id().toString(), forged.toString())
                .replace(source.orderId().toString(), forgedOrder.toString());
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO platform.outbox_event(id,organization_id,environment_id,application_id,
                provider_account_id,currency,payment_id,order_id,event_type,resource_version,
                business_key,origin,occurred_at,payload,payload_digest)
            SELECT ?,organization_id,environment_id,application_id,provider_account_id,currency,
                payment_id,order_id,'payment.state_changed',resource_version+100,business_key,
                origin,occurred_at,?,encode(sha256(convert_to(?,'UTF8')),'hex')
            FROM platform.outbox_event WHERE id=?
            """, forged, body, body, source.id()))
                .isInstanceOfSatisfying(
                        org.springframework.dao.DataAccessException.class,
                        error -> assertThat(((java.sql.SQLException) error.getMostSpecificCause()).getSQLState())
                                .isEqualTo("42501"));
        assertThat(outbox().find(forged)).isNull();
        new TransactionTemplate(tx)
                .executeWithoutResult(s -> assertThat(new OutboxWriter(jdbc).write(source.paymentId()))
                        .isEqualTo(source.id()));
        var legitimate = success();
        assertThat(outbox().find(legitimate.id())).isNotNull();
    }

    long publishDelay() {
        return jdbc.queryForObject(
                "SELECT extract(epoch FROM (available_at-?::timestamptz))::bigint FROM platform.outbox_event WHERE status='READY'",
                Long.class,
                java.sql.Timestamp.from(fake.clock().instant()));
    }

    String state() {
        return jdbc.queryForObject("SELECT status FROM platform.delivery_task", String.class);
    }

    void due() {
        admin.update("UPDATE platform.delivery_task SET available_at=now()-interval '1 minute'");
    }
}
