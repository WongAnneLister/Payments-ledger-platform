package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.ledger.*;
import com.paymentsledger.notification.*;
import com.paymentsledger.notification.fixture.SimulatedMerchantServer;
import com.paymentsledger.pr19.FaultEvidence;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test driver only. Merchant effects live in a separate database; Fake effects in its persistent file. */
abstract class Pr19FaultFixture extends Pr18Fixture {
    static final String LIMITATIONS =
            "ISOLATED_REAL_POSTGRESQL; PRODUCTION_SERVICES; PERSISTENT_FAKE_PROVIDER; SIGNED_LOCAL_FIXTURE; no Stripe or bank contract claim; merchant database shares the isolated PostgreSQL server; recovery time is local measurement";
    static final String MERCHANT_SECRET = "pr19-local-merchant-secret-000000000000";
    SimulatedMerchantServer merchant;
    JdbcTemplate merchantJdbc;
    long webhookRequests;
    final List<Map<String, Object>> observations = new ArrayList<>();

    void fault(String id, String variant, long seed, String injection, String recovery, FaultEvidence.Checked body)
            throws Exception {
        FaultEvidence.run(id, variant, seed, injection, recovery, LIMITATIONS, e -> {
            reset(seed);
            webhookRequests = 0;
            observations.clear();
            e.put(
                    "dataset",
                    Map.of(
                            "amountMinor",
                            10000L,
                            "currency",
                            "USD",
                            "provider",
                            "PERSISTENT_FAKE",
                            "postgres",
                            jdbc.queryForObject("SELECT version()", String.class),
                            "clock",
                            fake.clock().instant().toString()));
            e.put("intermediateEvidence", snapshotFault());
            e.put("recoveryMillis", 0L);
            e.put("recoveryOutcome", "NOT_ATTEMPTED; retained only if failure prevents recovery entry");
            try {
                body.accept(e);
                verifyMoney();
            } finally {
                e.put(
                        "operationKeys",
                        Map.of(
                                "localOperations",
                                jdbc.queryForList("""
                                        SELECT id,operation_key,request_fingerprint,first_send_at,retry_until
                                        FROM platform.provider_operation
                                        ORDER BY id"""),
                                "providerCallKeys",
                                fake.controls().calls(scope),
                                "fixture",
                                id + "/" + variant));
                e.put(
                        "observations",
                        Map.of(
                                "driver",
                                List.copyOf(observations),
                                "providerCalls",
                                fake.controls().calls(scope),
                                "webhookRequests",
                                webhookRequests));
                e.put(
                        "counts",
                        Map.of(
                                "requests",
                                fake.counters(scope).requestCount() + webhookRequests,
                                "accepted",
                                fake.counters(scope).acceptedCount(),
                                "webhooks",
                                count("webhook_inbox"),
                                "statusTransitions",
                                count("pr19_status_transition"),
                                "journals",
                                count("journal"),
                                "merchantEffects",
                                merchant.effects(),
                                "providerMoneyEffects",
                                (long) fake.controls().effects(scope).size()));
                e.put("finalEvidence", snapshotFault());
            }
        });
    }

    @Override
    void reset(long seed) {
        if (merchant != null) {
            merchant.close();
            merchant = null;
        }
        super.reset(seed);
        admin.execute("""
                CREATE TABLE IF NOT EXISTS platform.pr19_status_transition(id uuid PRIMARY KEY DEFAULT
                    gen_random_uuid(),resource_id uuid NOT NULL,resource_type text NOT NULL,old_status text,
                    new_status text NOT NULL,observed_at timestamptz NOT NULL DEFAULT now())""");
        admin.execute("TRUNCATE platform.pr19_status_transition");
        admin.execute("GRANT SELECT,INSERT ON platform.pr19_status_transition TO payments_app");
        admin.execute("""
          CREATE OR REPLACE FUNCTION platform.pr19_status_audit() RETURNS trigger LANGUAGE plpgsql AS $$
          DECLARE previous text; current text;
          BEGIN
           IF TG_TABLE_NAME='payment' THEN previous=to_jsonb(OLD)->>'payment_status';current=to_jsonb(NEW)->>'payment_status';
           ELSE previous=to_jsonb(OLD)->>'refund_status';current=to_jsonb(NEW)->>'refund_status';END IF;
           IF previous IS DISTINCT FROM current THEN INSERT INTO platform.pr19_status_transition(resource_id,resource_type,old_status,new_status) VALUES(NEW.id,TG_TABLE_NAME,previous,current);END IF;
           RETURN NEW;
          END $$
          """);
        for (String table : List.of("payment", "refund")) {
            admin.execute("DROP TRIGGER IF EXISTS pr19_status_audit ON platform." + table);
            admin.execute("CREATE TRIGGER pr19_status_audit AFTER UPDATE ON platform." + table
                    + " FOR EACH ROW EXECUTE FUNCTION platform.pr19_status_audit()");
        }
        if (admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='pr19_merchant'", Long.class) == 0)
            admin.execute("CREATE DATABASE pr19_merchant");
        var ds = new DriverManagerDataSource(
                DB.getJdbcUrl().replace("/" + DB.getDatabaseName(), "/pr19_merchant"),
                DB.getUsername(),
                DB.getPassword());
        merchantJdbc = new JdbcTemplate(ds);
        try {
            merchant = new SimulatedMerchantServer(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ds, MERCHANT_SECRET, fake.clock());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        merchantJdbc.execute("""
                TRUNCATE merchant_fixture.inbox,merchant_fixture.fulfillment,merchant_fixture.refund_effect""");
    }

    @AfterEach
    void closeMerchant() {
        if (merchant != null) merchant.close();
    }

    Map<String, Object> snapshotFault() {
        var result = new LinkedHashMap<String, Object>(snapshot());
        for (String table : List.of(
                "webhook_inbox",
                "dependency_wait",
                "normalized_observation",
                "reconciliation_source_observation",
                "reconciliation_payment_provenance",
                "order_provider_binding",
                "pr19_status_transition")) result.put(table, jdbc.queryForList("SELECT * FROM platform." + table));
        result.put("merchantInbox", merchantJdbc.queryForList("SELECT * FROM merchant_fixture.inbox"));
        result.put("merchantFulfillment", merchantJdbc.queryForList("SELECT * FROM merchant_fixture.fulfillment"));
        result.put("clock", fake.clock().instant().toString());
        return result;
    }

    void recovery(Map<String, Object> e, FaultEvidence.Checked action) throws Exception {
        e.put("recoveryStartedAt", java.time.Instant.now().toString());
        e.put("recoveryOutcome", "RUNNING");
        long start = System.nanoTime();
        try {
            action.accept(e);
            e.put("recoveryOutcome", "RETURNED");
        } catch (Exception | Error failure) {
            e.put("recoveryOutcome", "FAILED");
            throw failure;
        } finally {
            e.put("recoveryMillis", (System.nanoTime() - start) / 1_000_000L);
            e.put("recoveryFinishedAt", java.time.Instant.now().toString());
        }
    }

    void deliverNativeSucceededTwice() throws Exception {
        var client = HttpClient.newHttpClient();
        for (var row : jdbc.queryForList("""
                SELECT *
                FROM platform.outbox_event
                WHERE event_type='payment.succeeded' AND origin='NATIVE'""")) {
            var event = event(row);
            assertThat(new FulfillmentEligibility.Database(jdbc).evaluate(event).eligible())
                    .isTrue();
            for (int duplicate = 0; duplicate < 2; duplicate++) {
                long timestamp = fake.clock().instant().getEpochSecond();
                var request = HttpRequest.newBuilder(merchant.uri())
                        .timeout(java.time.Duration.ofSeconds(10))
                        .header("X-Payments-Event-Id", event.id().toString())
                        .header("X-Payments-Business-Key", event.businessKey())
                        .header("X-Payments-Timestamp", Long.toString(timestamp))
                        .header(
                                "X-Payments-Signature",
                                NotificationSignature.sign(
                                        event.bytes(), event.id(), event.businessKey(), timestamp, MERCHANT_SECRET))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(event.bytes()))
                        .build();
                assertThat(client.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                        .isEqualTo(200);
            }
        }
    }

    NotificationEvent event(Map<String, Object> row) {
        return new NotificationEvent(
                (UUID) row.get("id"),
                id(1),
                id(2),
                id(3),
                id(4),
                (UUID) row.get("payment_id"),
                (UUID) row.get("order_id"),
                "USD",
                row.get("event_type").toString(),
                ((Number) row.get("resource_version")).longValue(),
                row.get("business_key").toString(),
                row.get("origin").toString(),
                ((Timestamp) row.get("occurred_at")).toInstant(),
                row.get("payload").toString(),
                row.get("payload_digest").toString());
    }

    long sourceJournals(String source) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.journal j
                JOIN platform.financial_event f ON f.id=j.financial_event_id
                WHERE f.source_domain='PROVIDER' AND f.source_ref=?""", Long.class, source);
    }

    void verifyMoney() {
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM (SELECT journal_id FROM platform.journal_entry GROUP BY journal_id HAVING sum(CASE WHEN
                            side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END)<>0) bad""", Long.class)).as("balanced journals").isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM (SELECT financial_event_id FROM platform.journal WHERE financial_event_id IS NOT NULL GROUP BY
                            financial_event_id HAVING count(*)>1) bad""", Long.class))
                .as("canonical financial event unique")
                .isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.refund_control
                        WHERE control_state='NORMAL' AND refunded_minor+reserved_minor>captured_minor""", Long.class)).as("R+H <= C").isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.share_allocation a
                        JOIN platform.payment p ON p.id=a.payment_id
                        LEFT
                        JOIN platform.refund_control r ON r.payment_id=p.id
                        WHERE a.effective_minor>floor(greatest(p.captured_amount_minor-coalesce(r.refunded_minor,
                            0)-coalesce(r.reserved_minor,0),0)::numeric*a.rate_bps/10000)""", Long.class))
                .as("share cumulative ceiling")
                .isZero();
        assertThat(new BalanceRebuildService(jdbc, fake.clock())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .isTrue();
    }
}
