package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.dispute.DisputeService;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.pr19.Pr19ProcessHarness;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeStripeProvider;
import com.paymentsledger.refund.RefundFinancialEvidence;
import com.paymentsledger.revenueshare.RevenueShareService;
import com.paymentsledger.webhook.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** Entry point in an independent JVM. Instantiates real production components, never reuses parent objects. */
public final class Pr19PaymentChild {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource();
    private final List<String> trace = new ArrayList<>();
    private final List<Map<String, Object>> commitTrace = new ArrayList<>();
    private final List<Map<String, Object>> rollbackTrace = new ArrayList<>();
    private final JdbcTemplate jdbc;
    private final GatedTransactions tx;
    private final FakeStripeProvider fake;
    private final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);
    private final TenantContext paymentContext = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("payments:write", "payments:read"));
    private final TenantContext refundContext = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:write", "refunds:read"));
    private final PaymentRepository repository;
    private final PaymentExecutor executor;
    private final RefundRepository refunds;
    private final RevenueShareService shares;
    private final DisputeService disputes;
    private final BankMatcher matcher;
    private final PayoutService payouts;
    private final LedgerPostingService posting;
    private final RecoveryPolicy policy = new RecoveryPolicy(
            Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofDays(1), 0, Duration.ofDays(7), Duration.ZERO);

    private Pr19PaymentChild() {
        pool.setJdbcUrl(env("PR19_JDBC_URL"));
        pool.setUsername(env("PR19_DB_USER"));
        pool.setPassword(env("PR19_DB_PASSWORD"));
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(5000);
        pool.setAutoCommit(true);
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
        jdbc.execute("SET lock_timeout='8s'");
        jdbc.execute("SET statement_timeout='15s'");
        if (!"payments_app".equals(jdbc.queryForObject("SELECT current_user", String.class)))
            throw new IllegalStateException("Runtime role required");
        tx = new GatedTransactions();
        fake = new FakeStripeProvider(
                Path.of(env("PR19_FAKE_DIRECTORY")),
                Long.parseLong(env("PR19_SEED")),
                Instant.parse("2026-10-07T12:00:00Z"),
                Duration.ofDays(1));
        posting = new LedgerPostingService(jdbc, json);
        final DisputeService[] dispute = new DisputeService[1];
        shares = new RevenueShareService(
                jdbc, tx, json, posting, true, p -> dispute[0] == null || !dispute[0].blocks(p));
        disputes = new DisputeService(jdbc, tx, json, posting, shares);
        dispute[0] = disputes;
        repository = new PaymentRepository(jdbc, tx, json, fake.clock(), shares);
        repository.recoveryPolicy(policy);
        executor = new PaymentExecutor(repository, fake, fake, fake, "FAKE");
        refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares, disputes);
        payouts = new PayoutService(jdbc, tx, json, posting);
        matcher = new BankMatcher(jdbc, tx, json, posting, payouts);
    }

    public static void main(String[] args) {
        Pr19PaymentChild actor = null;
        try {
            actor = new Pr19PaymentChild();
            var result = actor.execute();
            result.put("status", "PASS");
            result.put("lockTrace", List.copyOf(actor.trace));
            result.put("transactionCommits", List.copyOf(actor.commitTrace));
            result.put("transactionRollbacks", List.copyOf(actor.rollbackTrace));
            result.put("runtimeRole", actor.jdbc.queryForObject("SELECT current_user", String.class));
            result.put("fakeCounters", actor.fake.counters(actor.scope));
            Pr19ProcessHarness.mark("result", result);
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                System.err.println(cause.getClass().getName());
                for (var frame : cause.getStackTrace()) System.err.println("  at " + frame);
            }
            Pr19ProcessHarness.mark(
                    "result",
                    Map.of("status", "FAIL", "failureType", failure.getClass().getName()));
            System.exit(1);
        } finally {
            if (actor != null) actor.pool.close();
        }
    }

    private Map<String, Object> execute() {
        String action = env("PR19_ACTION");
        return switch (action) {
            case "create-payment", "reserve-refund", "share", "dispute", "bank-match" -> transactional(action);
            case "execute-refund" -> {
                ready();
                new RefundExecutor(repository, refunds, gatedRefundPort(), "FAKE").execute(uuid("PR19_OPERATION_ID"));
                yield accepted(action, "executed");
            }
            case "recover" -> {
                ready();
                var refundExecutor = new RefundExecutor(repository, refunds, fake, "FAKE");
                new OperationRecoveryCoordinator(repository, executor, refundExecutor, policy).recoverDue(100);
                yield accepted(action, "recovered");
            }
            case "process-inbox" -> {
                ready();
                var inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
                var claims = inbox.claimDue(100);
                tx.gate = System.getenv("PR19_COMMIT_GATE");
                var processor = new WebhookProcessor(inbox, repository, json, fake.clock());
                for (var claim : claims) processor.process(claim);
                yield accepted(action, Map.of("claimed", claims.size()));
            }
            case "import-bt" -> {
                ready();
                var importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock(), shares);
                var window = importer.createWindow(
                        scope,
                        Instant.parse(env("PR19_FROM")),
                        Instant.parse(env("PR19_UNTIL")),
                        env("PR19_IMPORT_PURPOSE"));
                String imported = importer.importPage(window);
                var replay = new FinancialReplayService(
                        jdbc, tx, json, posting, new RefundFinancialEvidence(refunds), shares, disputes, payouts);
                String posted = replay.replay(scope, "capture");
                yield accepted(action, Map.of("windowId", window, "importResult", imported, "replayResult", posted));
            }
            default -> throw new IllegalArgumentException("Unknown bounded actor action");
        };
    }

    private Map<String, Object> transactional(String action) {
        var result = new TransactionTemplate(tx).execute(status -> {
            checkpointReady();
            try {
                Object value = switch (action) {
                    case "create-payment" ->
                        repository.prepare(
                                paymentContext,
                                env("PR19_KEY"),
                                new PaymentRequests.Create(
                                        "pr19-order",
                                        "demo",
                                        1,
                                        10000,
                                        "USD",
                                        1,
                                        "https://merchant.test/ok",
                                        "https://merchant.test/cancel",
                                        null,
                                        Map.of()),
                                "FAKE");
                    case "reserve-refund" ->
                        refunds.prepare(
                                refundContext,
                                "pr19-refund-" + env("PR19_KEY"),
                                new RefundRequests.Create(
                                        uuid("PR19_PAYMENT_ID"),
                                        amount(),
                                        "USD",
                                        "REQUESTED_BY_CUSTOMER",
                                        null,
                                        env("PR19_KEY")),
                                "FAKE");
                    case "share" -> {
                        shares.reconcile(uuid("PR19_PAYMENT_ID"), "pr19-share-catchup");
                        yield "reconciled";
                    }
                    case "dispute" -> {
                        disputes.accept(
                                scope,
                                new DisputeFact(
                                        "du_pr19-race",
                                        env("PR19_CHARGE_ID"),
                                        "warning_needs_response",
                                        "fraudulent",
                                        3000,
                                        "usd",
                                        Instant.parse(env("PR19_UNTIL")),
                                        List.of()),
                                evidence());
                        yield "committed";
                    }
                    case "bank-match" ->
                        matcher.match(scope, uuid("PR19_PAYOUT_ID"), uuid("PR19_BANK_LINE_ID"), amount());
                    default -> throw new IllegalArgumentException("Unsupported transaction actor");
                };
                tx.gate = System.getenv("PR19_COMMIT_GATE");
                return accepted(action, value);
            } catch (ApiException rejected) {
                status.setRollbackOnly();
                return rejected(action, rejected.errorCode().name());
            } catch (IllegalArgumentException rejected) {
                if (!action.equals("bank-match")
                        || !Set.of("Bank line capacity exceeded", "Payout unresolved or principal capacity exceeded")
                                .contains(rejected.getMessage())) throw rejected;
                status.setRollbackOnly();
                return rejected(action, "REJECTED_CAPACITY");
            }
        });
        if (action.equals("create-payment") && "ACCEPTED".equals(result.get("outcome")))
            executor.execute(((PaymentRepository.Intent) result.get("value")).operationId());
        return result;
    }

    private RefundPort gatedRefundPort() {
        return new RefundPort() {
            public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand command) {
                String gate = System.getenv("PR19_REMOTE_GATE");
                if ("remote-before-call".equals(gate))
                    Pr19ProcessHarness.checkpoint(
                            gate,
                            Map.of(
                                    "operationKey",
                                    command.operationIdempotencyKey(),
                                    "phase",
                                    "before Fake independent store call",
                                    "transactionActive",
                                    TransactionSynchronizationManager.isActualTransactionActive()));
                var result = fake.createRefund(command);
                if ("remote-after-call".equals(gate))
                    Pr19ProcessHarness.checkpoint(
                            gate,
                            Map.of(
                                    "operationKey",
                                    command.operationIdempotencyKey(),
                                    "phase",
                                    "after Fake independent store committed, before production writeback",
                                    "classification",
                                    result.classification(),
                                    "fakeCounters",
                                    fake.counters(scope),
                                    "transactionActive",
                                    TransactionSynchronizationManager.isActualTransactionActive()));
                return result;
            }

            public ProviderCallResult<RefundFact> queryRefund(ProviderScope scope, String refundId) {
                return fake.queryRefund(scope, refundId);
            }
        };
    }

    private void ready() {
        new TransactionTemplate(tx).execute(status -> {
            checkpointReady();
            return null;
        });
    }

    private void checkpointReady() {
        Pr19ProcessHarness.checkpoint(
                "ready",
                Map.of(
                        "backendPid",
                        jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                        "databaseTransaction",
                        jdbc.queryForObject("SELECT txid_current()", Long.class),
                        "runtimeRole",
                        jdbc.queryForObject("SELECT current_user", String.class),
                        "action",
                        env("PR19_ACTION"),
                        "lockTimeoutSeconds",
                        8,
                        "statementTimeoutSeconds",
                        15));
    }

    private final class GatedTransactions extends DataSourceTransactionManager {
        String gate;

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("backendPid", jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            detail.put("databaseTransaction", jdbc.queryForObject("SELECT txid_current()", Long.class));
            super.doRollback(status);
            detail.put("rolledBackAtUtc", Instant.now().toString());
            rollbackTrace.add(detail);
            Pr19ProcessHarness.mark("rolled-back-" + rollbackTrace.size(), detail);
        }

        GatedTransactions() {
            super(Pr19PaymentChild.this.pool);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            String current = gate;
            gate = null;
            Map<String, Object> detail = Map.of(
                    "backendPid",
                    jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                    "databaseTransaction",
                    jdbc.queryForObject("SELECT txid_current()", Long.class),
                    "transactionActive",
                    TransactionSynchronizationManager.isActualTransactionActive());
            if (current != null && current.endsWith("before-commit")) Pr19ProcessHarness.checkpoint(current, detail);
            super.doCommit(status);
            var committed = new LinkedHashMap<String, Object>(detail);
            committed.put("committedAtUtc", Instant.now().toString());
            commitTrace.add(committed);
            Pr19ProcessHarness.mark("committed-" + commitTrace.size(), committed);
            if (current != null && current.endsWith("after-commit")) Pr19ProcessHarness.checkpoint(current, detail);
        }
    }

    private ProviderEvidence evidence() {
        var now = fake.clock().instant();
        return new ProviderEvidence("pr19-fixture", null, now, now, 200, null, "a".repeat(64), "fixture-v1");
    }

    private static Map<String, Object> accepted(String action, Object value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("action", action);
        result.put("outcome", "ACCEPTED");
        result.put("value", value);
        return result;
    }

    private static Map<String, Object> rejected(String action, String code) {
        var result = new LinkedHashMap<String, Object>();
        result.put("action", action);
        result.put("outcome", "REJECTED");
        result.put("errorCode", code);
        return result;
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private static String env(String key) {
        return Objects.requireNonNull(System.getenv(key), key + " required");
    }

    private static UUID uuid(String key) {
        return UUID.fromString(env(key));
    }

    private static long amount() {
        return Long.parseLong(env("PR19_AMOUNT"));
    }
}
