package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.notification.fixture.SimulatedMerchantServer;
import com.paymentsledger.payment.*;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.pr19.*;
import com.paymentsledger.provider.fake.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Actual broker and child-JVM crashes; bank evidence is not exercised here. */
class Pr19KafkaFaultIT extends NotificationFixture {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");

    @Container
    static final PostgreSQLContainer<?> MERCHANT = new PostgreSQLContainer<>("postgres:16");

    static final long SEED = 190007;
    static final String SECRET = "pr19-fixture-merchant-signing-secret-32bytes";
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void deterministic() {
        // Prices and Outbox availability use database wall time; an old fixed provider date
        // makes the scenario fail before injection. Record a controlled clock ahead of setup.
        fake = new FakeStripeProvider(
                directory.resolve("pr19-provider"), SEED, Instant.now().plusSeconds(120), Duration.ofDays(1));
        repository = new PaymentRepository(new PaymentRepositories(jdbc), tx, json, fake.clock());
        executor = new PaymentExecutor(repository, fake, fake, fake, "FAKE");
        service = new PaymentService(repository, executor);
        new JdbcTemplate(new DriverManagerDataSource(
                        MERCHANT.getJdbcUrl(), MERCHANT.getUsername(), MERCHANT.getPassword()))
                .execute("DROP SCHEMA IF EXISTS merchant_fixture CASCADE");
        admin.execute("""
                CREATE TABLE IF NOT EXISTS platform.pr19_notification_transition(payment_id uuid,old_status text,
                    new_status text)""");
        admin.execute("TRUNCATE platform.pr19_notification_transition");
        admin.execute("GRANT INSERT,SELECT ON platform.pr19_notification_transition TO payments_app");
        admin.execute("""
                CREATE OR REPLACE FUNCTION platform.pr19_notification_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF OLD.payment_status IS DISTINCT FROM NEW.payment_status THEN INSERT INTO
                    platform.pr19_notification_transition
                VALUES(NEW.id,OLD.payment_status,NEW.payment_status); END IF; RETURN NEW; END $$""");
        admin.execute("DROP TRIGGER IF EXISTS pr19_notification_audit ON platform.payment");
        admin.execute("""
                CREATE TRIGGER pr19_notification_audit AFTER UPDATE ON platform.payment FOR EACH ROW EXECUTE FUNCTION
                    platform.pr19_notification_audit()""");
    }

    @Test
    void brokerOutage() throws Exception {
        experiment("F-07", "broker-outage", "BROKER");
    }

    @Test
    void producerCrashAfterSend() throws Exception {
        experiment("F-07", "outbox-after-send", "OUTBOX");
    }

    @Test
    void consumerCrashAfterCommit() throws Exception {
        experiment("F-07", "consumer-commit-before-ack", "CONSUMER");
    }

    @Test
    void f06OutboxProcessDeath() throws Exception {
        experiment("F-06", "outbox-after-send", "OUTBOX");
    }

    @Test
    void f06ConsumerProcessDeath() throws Exception {
        experiment("F-06", "consumer-after-commit-before-ack", "CONSUMER");
    }

    void experiment(String id, String variant, String mode) throws Exception {
        FaultEvidence.run(
                id,
                variant,
                SEED,
                "BROKER".equals(mode) ? "pause actual Kafka container" : "SIGKILL child JVM at durable marker",
                "unpause/reclaim and production inbox + notifier",
                "Local containers, Fake provider; marker-controlled child component rather than deployed service; no real accounts",
                e -> {
                    String topic = "pr19-" + variant + "-" + UUID.randomUUID(), group = "pr19-" + UUID.randomUUID();
                    e.put(
                            "dataset",
                            Map.of(
                                    "clock",
                                    fake.clock().instant().toString(),
                                    "amountMinor",
                                    10000,
                                    "currency",
                                    "USD",
                                    "topic",
                                    topic,
                                    "group",
                                    group,
                                    "postgresVersion",
                                    jdbc.queryForObject("SELECT version()", String.class),
                                    "kafkaBrokerImage",
                                    "apache/kafka:3.9.0",
                                    "kafkaClientVersion",
                                    org.apache.kafka.common.utils.AppInfoParser.getVersion()));
                    e.put("operationKeys", List.of("pr19-notification-key", "pr19-notification-order"));
                    var ds = new DriverManagerDataSource(
                            MERCHANT.getJdbcUrl(), MERCHANT.getUsername(), MERCHANT.getPassword());
                    try (var merchant = new SimulatedMerchantServer(
                                    new InetSocketAddress("127.0.0.1", 0), ds, SECRET, fake.clock());
                            var producer = Pr19KafkaClients.producer(KAFKA.getBootstrapServers())) {
                        admin.update("""
                                INSERT INTO platform.merchant_notification_target(organization_id,environment_id,application_id,url,
                                    key_ref,version)
                                VALUES (?,?,?,?,?,1)""", id(1), id(2), id(3), merchant.uri().toString(), "pr19-fixture");
                        var payment = service.create(
                                        context, "pr19-notification-key", command("pr19-notification-order"))
                                .resource();
                        fake.clock().advance(Duration.ofSeconds(1));
                        fake.controls().attemptPayment(scope, session(payment), FakeControls.AttemptOutcome.SUCCEED);
                        service.get(context, payment(payment));
                        fake.clock().advance(Duration.ofMinutes(1));
                        var event = outbox().find(jdbc.queryForObject(
                                "SELECT id FROM platform.outbox_event WHERE event_type='payment.succeeded'",
                                UUID.class));
                        long priorEvents = jdbc.queryForObject(
                                "SELECT count(*) FROM platform.outbox_event WHERE id<>?", Long.class, event.id());
                        for (int i = 0; i < priorEvents; i++) {
                            var prior = outbox().claim(Duration.ofSeconds(30));
                            assertThat(prior.event().id()).isNotEqualTo(event.id());
                            producer.send(new ProducerRecord<>(
                                            topic + "-prior",
                                            prior.event().businessKey(),
                                            prior.event().bytes()))
                                    .get(10, TimeUnit.SECONDS);
                            assertThat(outbox().published(prior)).isTrue();
                        }
                        e.put("priorStateNotificationsNormallyPublished", priorEvents);
                        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                        long recoveryStart;
                        int publishes;
                        e.put("injectedAtUtc", Instant.now().toString());
                        if (mode.equals("BROKER")) {
                            producer.partitionsFor(topic);
                            var publisher = new OutboxPublisher(
                                    outbox(),
                                    r -> producer.send(new ProducerRecord<>(topic, r.businessKey(), r.bytes()))
                                            .get(4, TimeUnit.SECONDS),
                                    Duration.ofSeconds(30));
                            KAFKA.getDockerClient()
                                    .pauseContainerCmd(KAFKA.getContainerId())
                                    .exec();
                            try {
                                assertThat(publisher.drain(1)).isZero();
                                e.put("intermediateEvidence", snapshot());
                                assertThat(jdbc.queryForObject(
                                                "SELECT status FROM platform.outbox_event WHERE id=?",
                                                String.class,
                                                event.id()))
                                        .isEqualTo("READY");
                                assertThat(new NotificationMetrics(jdbc, fake.clock()).value("outbox_backlog"))
                                        .isEqualTo(1);
                            } finally {
                                KAFKA.getDockerClient()
                                        .unpauseContainerCmd(KAFKA.getContainerId())
                                        .exec();
                            }
                            recoveryStart = System.nanoTime();
                            fake.clock().advance(Duration.ofMinutes(1));
                            assertThat(publisher.drain(1)).isEqualTo(1);
                            publishes = 1;
                            try (var consumer = Pr19KafkaClients.consumer(KAFKA.getBootstrapServers(), topic, group)) {
                                var r = Pr19KafkaClients.poll(consumer);
                                inbox().consume(r.value(), () -> Pr19KafkaClients.ack(consumer, r));
                            }
                        } else if (mode.equals("OUTBOX")) {
                            try (var child = Pr19ProcessHarness.start(
                                    Pr19NotificationChild.class.getName(),
                                    directory.resolve(variant),
                                    "producer",
                                    childEnvironment(mode, topic, group))) {
                                var marker = child.awaitMarker("outbox-sent");
                                assertThat(marker.path("processPid").asLong())
                                        .isNotEqualTo(ProcessHandle.current().pid());
                                child.killForcibly();
                                e.put("processEvidence", child.evidence());
                                e.put("injectionMarker", marker);
                            }
                            e.put("intermediateEvidence", snapshot());
                            assertThat(jdbc.queryForObject(
                                            "SELECT status FROM platform.outbox_event WHERE id=?",
                                            String.class,
                                            event.id()))
                                    .isEqualTo("RUNNING");
                            recoveryStart = System.nanoTime();
                            fake.clock().advance(Duration.ofSeconds(31));
                            var claim = outbox().claim(Duration.ofSeconds(30));
                            assertThat(claim).isNotNull();
                            producer.send(new ProducerRecord<>(
                                            topic,
                                            claim.event().businessKey(),
                                            claim.event().bytes()))
                                    .get(10, TimeUnit.SECONDS);
                            assertThat(outbox().published(claim)).isTrue();
                            publishes = 2;
                            try (var consumer = Pr19KafkaClients.consumer(KAFKA.getBootstrapServers(), topic, group)) {
                                for (int i = 0; i < 2; i++) {
                                    var r = Pr19KafkaClients.poll(consumer);
                                    inbox().consume(r.value(), () -> Pr19KafkaClients.ack(consumer, r));
                                }
                            }
                        } else {
                            var claim = outbox().claim(Duration.ofSeconds(30));
                            producer.send(new ProducerRecord<>(
                                            topic,
                                            claim.event().businessKey(),
                                            claim.event().bytes()))
                                    .get(10, TimeUnit.SECONDS);
                            assertThat(outbox().published(claim)).isTrue();
                            publishes = 1;
                            try (var child = Pr19ProcessHarness.start(
                                    Pr19NotificationChild.class.getName(),
                                    directory.resolve(variant),
                                    "consumer",
                                    childEnvironment(mode, topic, group))) {
                                var marker = child.awaitMarker("consumer-committed");
                                assertThat(marker.path("acceptance").asText()).isEqualTo("CREATED");
                                child.killForcibly();
                                e.put("processEvidence", child.evidence());
                                e.put("injectionMarker", marker);
                            }
                            e.put("intermediateEvidence", snapshot());
                            assertThat(count("consumer_inbox")).isEqualTo(1);
                            assertThat(count("delivery_task")).isEqualTo(1);
                            recoveryStart = System.nanoTime();
                            try (var consumer = Pr19KafkaClients.consumer(KAFKA.getBootstrapServers(), topic, group)) {
                                var partition = new TopicPartition(topic, 0);
                                assertThat(consumer.committed(Set.of(partition), Duration.ofSeconds(5))
                                                .get(partition))
                                        .isNull();
                                var r = Pr19KafkaClients.poll(consumer);
                                assertThat(r.offset()).isZero();
                                inbox().consume(r.value(), () -> Pr19KafkaClients.ack(consumer, r));
                                assertThat(consumer.committed(Set.of(partition), Duration.ofSeconds(5))
                                                .get(partition)
                                                .offset())
                                        .isEqualTo(1);
                            }
                        }
                        var notifier = new MerchantNotifier(
                                delivery(),
                                new HttpMerchantTransport(
                                        NotificationTargetPolicy.localFixture(merchant.uri()),
                                        ref -> SECRET,
                                        fake.clock()));
                        merchant.dropNextResponse();
                        notifier.drain(1);
                        assertThat(jdbc.queryForObject("SELECT status FROM platform.delivery_task", String.class))
                                .isEqualTo("READY");
                        fake.clock().advance(Duration.ofHours(1));
                        notifier.drain(1);
                        assertThat(merchant.effects()).isEqualTo(1);
                        assertThat(merchant.receipts()).isEqualTo(1);
                        assertThat(count("consumer_inbox")).isEqualTo(1);
                        assertThat(count("delivery_task")).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT status FROM platform.delivery_task", String.class))
                                .isEqualTo("DELIVERED");
                        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
                        e.put("recoveryMillis", (System.nanoTime() - recoveryStart) / 1_000_000);
                        e.put("recoveredAtUtc", Instant.now().toString());
                        var counters = fake.counters(scope);
                        e.put(
                                "counts",
                                Map.of(
                                        "requests",
                                        counters.requestCount(),
                                        "accepted",
                                        counters.acceptedCount(),
                                        "webhooks",
                                        0L,
                                        "statusTransitions",
                                        count("pr19_notification_transition"),
                                        "journals",
                                        count("journal"),
                                        "merchantEffects",
                                        merchant.effects(),
                                        "providerMoneyEffects",
                                        counters.paymentEffectCount() + counters.refundEffectCount()));
                        e.put(
                                "observations",
                                Map.of(
                                        "acknowledgedBrokerPublishes",
                                        publishes,
                                        "consumerInbox",
                                        count("consumer_inbox"),
                                        "deliveryTasks",
                                        count("delivery_task"),
                                        "merchantReceipts",
                                        merchant.receipts(),
                                        "merchantEffects",
                                        merchant.effects(),
                                        "deliveryAttempts",
                                        jdbc.queryForObject(
                                                "SELECT attempt_count FROM platform.delivery_task", Integer.class),
                                        "counterMeaning",
                                        "statusTransitions measured by test-only database audit; requests/accepted from independent provider store"));
                        var fin = snapshot();
                        fin.put(
                                "independentMerchant",
                                new JdbcTemplate(ds).queryForList("SELECT * FROM merchant_fixture.fulfillment"));
                        e.put("finalEvidence", fin);
                    }
                });
    }

    Map<String, String> childEnvironment(String mode, String topic, String group) {
        return Map.of(
                "PR19_JDBC_URL",
                DB.getJdbcUrl(),
                "PR19_JDBC_USER",
                "payments_app",
                "PR19_JDBC_PASSWORD",
                "pr02_test_only",
                "PR19_CLOCK",
                fake.clock().instant().toString(),
                "PR19_MODE",
                mode,
                "PR19_BOOTSTRAP",
                KAFKA.getBootstrapServers(),
                "PR19_TOPIC",
                topic,
                "PR19_GROUP",
                group);
    }

    Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        for (var table : List.of(
                "payment",
                "outbox_event",
                "consumer_inbox",
                "delivery_task",
                "journal",
                "journal_entry",
                "balance_projection",
                "pr19_notification_transition")) m.put(table, jdbc.queryForList("SELECT * FROM platform." + table));
        m.put("providerCounters", fake.counters(scope));
        m.put("providerEffects", fake.controls().effects(scope));
        m.put("backlog", new NotificationMetrics(jdbc, fake.clock()).value("outbox_backlog"));
        m.put("oldestAgeSeconds", new NotificationMetrics(jdbc, fake.clock()).value("oldest_age_seconds"));
        return m;
    }
}
