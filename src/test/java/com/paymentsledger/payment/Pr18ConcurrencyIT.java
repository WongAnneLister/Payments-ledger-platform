package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.common.error.*;
import com.paymentsledger.dispute.DisputeService;
import com.paymentsledger.ledger.*;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.pr18.Pr18Artifacts;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Simultaneous PostgreSQL transactions from separately constructed API/worker components.
 * This suite does not claim separate JVMs or deployed application processes.
 */
class Pr18ConcurrencyIT extends Pr18Fixture {
    final List<Map<String, Object>> scenarios = new ArrayList<>();

    @Test
    void independentComponentsSerializeAtDatabaseLocksWithoutLostMoney() throws Exception {
        String status = "FAIL";
        try {
            scenario("competing-refunds", this::competingRefunds);
            scenario("same-key-refund", this::sameKey);
            scenario("refund-dispute-share", this::refundDisputeShare);
            scenario("payout-shared-line", this::payoutSharedLine);
            status = "PASS";
        } finally {
            Pr18Artifacts.write(
                    "concurrency-summary.json",
                    Map.of(
                            "status",
                            status,
                            "executionMode",
                            "ONE_JVM_SEPARATE_SERVICE_INSTANCES_AND_POSTGRESQL_CONNECTIONS",
                            "scenarios",
                            scenarios));
        }
    }

    interface Checked {
        void run(Map<String, Object> evidence) throws Exception;
    }

    void scenario(String name, Checked work) throws Exception {
        reset(180018 + scenarios.size());
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("name", name);
        evidence.put("status", "FAIL");
        scenarios.add(evidence);
        try {
            work.run(evidence);
            invariants();
            evidence.put("status", "PASS");
            evidence.put("databaseProviderMessageEvidence", snapshot());
        } catch (Throwable failure) {
            evidence.put("failure", Pr18Artifacts.failure(failure));
            evidence.put("databaseProviderMessageEvidence", snapshot());
            throw failure;
        } finally {
            Pr18Artifacts.write("concurrency-" + name + ".json", evidence);
        }
    }

    void thousandCapture() {
        admin.update("UPDATE platform.price_fixture SET amount_minor=1000");
        var request = new PaymentRequests.Create(
                "race-pay",
                "demo",
                1,
                1000,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
        var p = service.create(context, "race-pay", request).resource();
        paymentId = payment(p);
        checkout = session(p);
        capturePayment();
        post(bt("capture", "charge", 1000, 0, chargeId, "charge"));
    }

    void competingRefunds(Map<String, Object> evidence) throws Exception {
        thousandCapture();
        var a = new Actor("API-A");
        var b = new Actor("API-B");
        var result = race(evidence, List.of(a, b), List.of(() -> a.reserve("a", 600), () -> b.reserve("b", 600)));
        assertThat(result.stream()
                        .filter(x -> x instanceof RefundRepository.Intent)
                        .count())
                .isEqualTo(1);
        assertThat(result.stream()
                        .filter(x -> x == ErrorCode.REFUND_LIMIT_EXCEEDED)
                        .count())
                .isEqualTo(1);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(held()).isEqualTo(600);
        assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(80);
        long journalCount = count("journal");
        assertThatThrownBy(() -> a.refunds.prepare(refundContext, "another", refundRequest("another", 401), "FAKE"))
                .isInstanceOf(ApiException.class);
        assertThat(held()).isEqualTo(600);
        assertThat(count("journal")).isEqualTo(journalCount);
        assertThat(count("refund")).isEqualTo(1);
        evidence.put(
                "checks",
                List.of(
                        "one600reservation",
                        "other600rejected",
                        "H600<=C1000",
                        "rejected401rollsBackRefundOperationAndJournal",
                        "share80",
                        "I01I02I07"));
    }

    void sameKey(Map<String, Object> evidence) throws Exception {
        thousandCapture();
        var a = new Actor("API-A");
        var b = new Actor("API-B");
        var result = race(evidence, List.of(a, b), List.of(() -> a.reserve("same", 600), () -> b.reserve("same", 600)));
        var first = (RefundRepository.Intent) result.get(0);
        var second = (RefundRepository.Intent) result.get(1);
        assertThat(first.refundId()).isEqualTo(second.refundId());
        assertThat(first.operationId()).isEqualTo(second.operationId());
        assertThat(List.of(first.replay(), second.replay())).containsExactlyInAnyOrder(true, false);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(held()).isEqualTo(600);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.provider_operation
                        WHERE operation_type='CREATE_REFUND'""", Long.class)).isEqualTo(1);
        assertThat(fake.counters(scope).refundEffectCount()).isZero();
        evidence.put(
                "checks",
                List.of(
                        "stableRefundAndOperationIdentity",
                        "oneReservation",
                        "oneDurableProviderIntent",
                        "noProviderRefundSentForReservation",
                        "I01I02I07"));
    }

    void refundDisputeShare(Map<String, Object> evidence) throws Exception {
        thousandCapture();
        var a = new Actor("API");
        var b = new Actor("DISPUTE-WORKER");
        var c = new Actor("SHARE-WORKER");
        var result = race(
                evidence,
                List.of(a, b, c),
                List.of(
                        () -> a.reserve("race", 400),
                        () -> {
                            b.disputes.accept(scope, disputeFact("race", "warning_needs_response"), evidence());
                            return "DISPUTE_COMMITTED";
                        },
                        () -> {
                            c.shares.reconcile(paymentId, "race-catch-up");
                            return "SHARE_COMMITTED";
                        }));
        boolean reserved = result.getFirst() instanceof RefundRepository.Intent;
        if (!reserved) assertThat(result.getFirst()).isIn(ErrorCode.REFUND_BLOCKED, ErrorCode.EVIDENCE_CONFLICT);
        assertThat(held()).isEqualTo(reserved ? 400 : 0);
        assertThat(disputes.blocks(paymentId)).isTrue();
        assertThat(balance("PARTNER_SHARE_EXPENSE")).isZero();
        if (reserved)
            assertThat(jdbc.queryForObject(
                            "SELECT control_state FROM platform.refund_control WHERE payment_id=?",
                            String.class,
                            paymentId))
                    .isEqualTo("EXTERNAL_CONFLICT");
        evidence.put(
                "checks",
                List.of(
                        "bothAdmissibleSerializationsChecked",
                        "disputeBlocks",
                        "shareZero",
                        "noLostHold",
                        "parentBeforeAllocationLockTrace",
                        "I01I02I07"));
        evidence.put("refundWonRace", reserved);
    }

    void payoutSharedLine(Map<String, Object> evidence) throws Exception {
        UUID aId = payout("payout_a"), bId = payout("payout_b");
        post(bt("payout_a-out", "payout", -5700, 0, "payout_a", "payout"));
        post(bt("payout_b-out", "payout", -5700, 0, "payout_b", "payout"));
        UUID line = bankLine("shared-line", 5700, 101, "payout_a", "payout_b");
        var a = new Actor("BANK-WORKER-A");
        var b = new Actor("BANK-WORKER-B");
        var result = race(
                evidence,
                List.of(a, b),
                List.of(() -> a.matcher.match(scope, aId, line, 4000), () -> b.matcher.match(scope, bId, line, 4000)));
        assertThat(result.stream()
                        .filter(x -> x instanceof BankMatcher.MatchResult)
                        .count())
                .isEqualTo(1);
        assertThat(result.stream().filter(x -> x.equals("REJECTED_CAPACITY")).count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT allocated_minor FROM platform.bank_statement_line WHERE id=?", Long.class, line))
                .isEqualTo(4000);
        assertThat(count("payout_bank_match")).isEqualTo(1);
        assertThat(balance("BANK_CASH")).isEqualTo(3930);
        assertThat(balance("BANK_FEE_EXPENSE")).isEqualTo(70);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(7400);
        assertThat(jdbc.queryForObject("SELECT sum(bank_confirmed_minor) FROM platform.payout", Long.class))
                .isEqualTo(4000);
        evidence.put(
                "checks",
                List.of(
                        "one4000allocation",
                        "other4000capacityRejected",
                        "allocated4000<=line5700",
                        "feeFloor70",
                        "rollbackNoSecondJournal",
                        "payoutBeforeBankLineLockTrace",
                        "I01I02I07"));
    }

    List<Object> race(Map<String, Object> evidence, List<Actor> actors, List<Supplier<Object>> actions)
            throws Exception {
        var barrier = new CyclicBarrier(actors.size());
        var workers = Executors.newFixedThreadPool(actors.size());
        var futures = new ArrayList<Future<Object>>();
        try {
            for (int i = 0; i < actors.size(); i++) {
                var actor = actors.get(i);
                var action = actions.get(i);
                futures.add(workers.submit(() -> new TransactionTemplate(actor.tx).execute(status -> {
                    actor.jdbc.execute("SET LOCAL lock_timeout = '8s'");
                    actor.jdbc.execute("SET LOCAL statement_timeout = '15s'");
                    actor.pid = actor.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("barrier", e);
                    }
                    try {
                        return action.get();
                    } catch (ApiException rejected) {
                        status.setRollbackOnly();
                        return rejected.errorCode();
                    } catch (IllegalArgumentException rejected) {
                        status.setRollbackOnly();
                        return "REJECTED_CAPACITY";
                    }
                })));
            }
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertThat(actors.stream().map(a -> a.pid).distinct().count()).isEqualTo(actors.size());
            evidence.put("transactionTimeouts", Map.of("lockSeconds", 8, "statementSeconds", 15, "futureSeconds", 30));
            evidence.put("backendPids", actors.stream().map(a -> a.pid).toList());
            evidence.put("results", results.stream().map(Object::toString).toList());
            for (var actor : actors) actor.checkOrder();
            return results;
        } finally {
            futures.forEach(f -> f.cancel(true));
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            evidence.put(
                    "componentTraces",
                    actors.stream()
                            .map(a -> Map.of("component", a.name, "backendPid", a.pid, "sql", List.copyOf(a.trace)))
                            .toList());
        }
    }

    long held() {
        return jdbc.queryForObject("SELECT coalesce(sum(reserved_minor),0) FROM platform.refund_control", Long.class);
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
                        LEFT JOIN platform.journal j ON j.financial_event_id=e.id
                        WHERE j.id IS NULL""", Long.class)).isZero();
        assertThat(new BalanceRebuildService(jdbc, fake.clock())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .isTrue();
    }

    final class Actor {
        final String name;
        int pid;
        final List<String> trace = new CopyOnWriteArrayList<>();
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager tx;
        final RevenueShareService shares;
        final DisputeService disputes;
        final PaymentRepository repository;
        final RefundRepository refunds;
        final BankMatcher matcher;

        Actor(String name) {
            this.name = name;
            tx = new DataSourceTransactionManager(pool);
            jdbc = new JdbcTemplate(pool) {
                void capture(String sql) {
                    // Normalize before matching: a line break inside "FOR UPDATE" must not hide a lock.
                    String flat = sql.replaceAll("\\s+", " ");
                    if (flat.contains("pg_advisory_xact_lock")
                            || flat.contains("FOR UPDATE")
                            || flat.contains("platform.lock_")
                            || flat.contains("post_ledger")) trace.add(flat);
                }

                @Override
                public List<Map<String, Object>> queryForList(String sql, Object... args) {
                    capture(sql);
                    return super.queryForList(sql, args);
                }

                @Override
                public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                    capture(sql);
                    return super.queryForObject(sql, type, args);
                }
            };
            var ledger = new LedgerPostingService(jdbc, json);
            final DisputeService[] dispute = new DisputeService[1];
            shares = new RevenueShareService(
                    jdbc, tx, json, ledger, true, p -> dispute[0] == null || !dispute[0].blocks(p));
            disputes = new DisputeService(jdbc, tx, json, ledger, shares);
            dispute[0] = disputes;
            repository = new PaymentRepository(jdbc, tx, json, fake.clock(), shares);
            refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares, disputes);
            var payouts = new PayoutService(jdbc, tx, json, ledger);
            matcher = new BankMatcher(jdbc, tx, json, ledger, payouts);
        }

        Object reserve(String key, long amount) {
            return refunds.prepare(refundContext, "pr18-race-" + key, refundRequest(key, amount), "FAKE");
        }

        void checkOrder() {
            int payment = -1, allocation = -1, payout = -1, line = -1;
            for (int i = 0; i < trace.size(); i++) {
                String sql = trace.get(i);
                if (payment < 0 && sql.contains("FROM platform.payment ") && sql.contains("FOR UPDATE")) payment = i;
                if (allocation < 0 && sql.contains("FROM platform.share_allocation ") && sql.contains("FOR UPDATE"))
                    allocation = i;
                if (payout < 0 && sql.contains("FROM platform.payout ") && sql.contains("FOR UPDATE")) payout = i;
                if (line < 0 && sql.contains("FROM platform.bank_statement_line ") && sql.contains("FOR UPDATE"))
                    line = i;
            }
            if (allocation >= 0)
                assertThat(payment).as(name + " parent before allocation").isBetween(0, allocation - 1);
            if (line >= 0)
                assertThat(payout).as(name + " payout before shared line").isBetween(0, line - 1);
            assertThat(trace).as(name + " real lock trace").isNotEmpty();
        }
    }
}
