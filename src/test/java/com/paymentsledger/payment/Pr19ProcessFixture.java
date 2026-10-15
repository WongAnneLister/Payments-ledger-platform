package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.ledger.*;
import com.paymentsledger.pr19.*;
import java.util.*;

/** Parent fixture only creates independent inputs and inspects durable database/Fake evidence. */
abstract class Pr19ProcessFixture extends Pr19FaultFixture {
    static final String LIMITATIONS =
            "Test-classpath child JVMs wire production repositories/services against actual PostgreSQL; no deployed HTTP cluster, real Stripe, real bank or provider completion SLA. File barriers control entry; lease expiry uses the shared deterministic Fake clock.";
    final List<Pr19ProcessHarness.Child> children = new ArrayList<>();

    void fault(String id, String variant, long seed, String injection, String recovery, FaultEvidence.Checked work)
            throws Exception {
        reset(seed);
        children.clear();
        FaultEvidence.run(id, variant, seed, injection, recovery, LIMITATIONS, e -> {
            e.put(
                    "dataset",
                    Map.of(
                            "scope",
                            scope,
                            "clock",
                            fake.clock().instant().toString(),
                            "currency",
                            "USD",
                            "seed",
                            seed,
                            "database",
                            jdbc.queryForObject("SELECT version()", String.class),
                            "merchantDeliveryExercised",
                            false));
            e.put("executionMode", "SEPARATE_JVMS_AND_POSTGRESQL_CONNECTIONS");
            e.put(
                    "countMeaning",
                    "requests/accepted=independent durable Fake counters; webhooks=durable Inbox rows; statusTransitions=payment/refund status audit rows; merchantEffects=independent simulated merchant database, no transport invoked");
            e.put("operationKeys", new ArrayList<>());
            e.put("observations", new LinkedHashMap<>());
            e.put("intermediateEvidence", new LinkedHashMap<>());
            e.put("recoveryMillis", 0L);
            try {
                work.accept(e);
                invariants();
            } finally {
                for (var child : children) child.close();
                e.put(
                        "processes",
                        children.stream()
                                .map(Pr19ProcessHarness.Child::evidence)
                                .toList());
                e.put("counts", counts());
                e.put("finalEvidence", durableSnapshot());
            }
        });
    }

    Pr19ProcessHarness.Child actor(String name, String action, Map<String, String> arguments) throws Exception {
        var env = new HashMap<String, String>(arguments);
        env.put("PR19_ACTION", action);
        env.put("PR19_JDBC_URL", DB.getJdbcUrl());
        env.put("PR19_DB_USER", "payments_app");
        env.put("PR19_DB_PASSWORD", "pr02_test_only");
        env.put(
                "PR19_FAKE_DIRECTORY",
                directory.resolve("provider").toAbsolutePath().toString());
        env.put("PR19_SEED", Long.toString(currentSeed));
        env.put("PR19_FROM", from.toString());
        env.put("PR19_UNTIL", until.toString());
        if (paymentId != null) env.put("PR19_PAYMENT_ID", paymentId.toString());
        if (chargeId != null) env.put("PR19_CHARGE_ID", chargeId);
        var child =
                Pr19ProcessHarness.start(Pr19PaymentChild.class.getName(), directory.resolve("processes"), name, env);
        children.add(child);
        return child;
    }

    List<com.fasterxml.jackson.databind.JsonNode> race(
            Map<String, Object> e, List<String> actions, List<Map<String, String>> args) throws Exception {
        var actors = new ArrayList<Pr19ProcessHarness.Child>();
        for (int i = 0; i < actions.size(); i++) actors.add(actor("race-" + i, actions.get(i), args.get(i)));
        var ready = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var a : actors) ready.add(a.awaitMarker("ready"));
        assertThat(ready.stream()
                        .map(x -> x.path("javaPid").asLong())
                        .distinct()
                        .count())
                .isEqualTo(actions.size());
        assertThat(ready.stream()
                        .map(x -> x.path("backendPid").asInt())
                        .distinct()
                        .count())
                .isEqualTo(actions.size());
        e.put("intermediateEvidence", Map.of("ready", ready, "beforeRelease", durableSnapshot()));
        long released = System.nanoTime();
        e.put("recoveryStartedAtUtc", java.time.Instant.now().toString());
        for (var a : actors) a.release("ready");
        var results = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var a : actors) results.add(a.awaitResult());
        for (var result : results) {
            assertThat(result.path("transactionCommits").isArray())
                    .as("actual committed PostgreSQL transaction trace")
                    .isTrue();
            if (result.path("outcome").asText().equals("ACCEPTED"))
                assertThat(result.path("transactionCommits").size()).isPositive();
            else
                assertThat(result.path("transactionRollbacks").size())
                        .as("rejected transaction rolled back")
                        .isPositive();
        }
        e.put("recoveryMillis", (System.nanoTime() - released) / 1_000_000);
        e.put("recoveredAtUtc", java.time.Instant.now().toString());
        e.put(
                "observations",
                Map.of(
                        "results",
                        results,
                        "executionMode",
                        "SEPARATE_JVMS_AND_POSTGRESQL_CONNECTIONS",
                        "barrierDeadlineSeconds",
                        25));
        return results;
    }

    void thousandCapture(boolean posted) {
        admin.update("UPDATE platform.price_fixture SET amount_minor=1000");
        var request = new PaymentRequests.Create(
                "pr19-payment",
                "demo",
                1,
                1000,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
        var resource = service.create(context, "pr19-payment", request).resource();
        paymentId = payment(resource);
        checkout = session(resource);
        capturePayment();
        if (posted) post(bt("capture", "charge", 1000, 0, chargeId, "charge"));
    }

    long held() {
        return jdbc.queryForObject("SELECT coalesce(sum(reserved_minor),0) FROM platform.refund_control", Long.class);
    }

    Map<String, Object> counts() {
        var c = fake.counters(scope);
        return Map.of(
                "requests",
                c.requestCount(),
                "accepted",
                c.acceptedCount(),
                "webhooks",
                count("webhook_inbox"),
                "statusTransitions",
                count("pr19_status_transition"),
                "journals",
                count("journal"),
                "merchantEffects",
                merchant.effects(),
                "providerMoneyEffects",
                (long) fake.controls().effects(scope).size());
    }

    Map<String, Object> durableSnapshot() {
        var result = snapshotFault();
        for (String table : List.of(
                "api_idempotency_record",
                "webhook_inbox",
                "dependency_wait",
                "normalized_observation",
                "import_window",
                "import_page",
                "import_cursor"))
            try {
                result.put(
                        table,
                        json.readTree(jdbc.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]'::jsonb)::text FROM platform." + table
                                        + " t",
                                String.class)));
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot inspect durable test evidence", ex);
            }
        result.put("fakeCounters", fake.counters(scope));
        result.put(
                "independentProjectionRebuild",
                new BalanceRebuildService(jdbc, fake.clock())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null));
        return result;
    }

    void invariants() {
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM (SELECT journal_id FROM platform.journal_entry GROUP BY journal_id HAVING sum(CASE WHEN
                            side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END)<>0) x""", Long.class)).isZero();
        assertThat(count("journal")).isEqualTo(count("financial_event"));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.financial_event e
                        LEFT
                        JOIN platform.journal j ON j.financial_event_id=e.id
                        WHERE j.id IS NULL""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.refund_control
                        WHERE control_state='NORMAL' AND refunded_minor+reserved_minor>captured_minor""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.bank_statement_line
                        WHERE allocated_minor>principal_minor OR fee_allocated_minor>fee_minor""", Long.class)).isZero();
        assertThat(new BalanceRebuildService(jdbc, fake.clock())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .isTrue();
    }
}
