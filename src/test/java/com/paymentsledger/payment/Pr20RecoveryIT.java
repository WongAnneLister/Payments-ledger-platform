package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.ledger.*;
import com.paymentsledger.notification.*;
import com.paymentsledger.pr20.recovery.RecoveryReleaseGate;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import com.paymentsledger.reconciliation.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Consistent binary archive restore with external money facts deliberately kept ahead of the backup. */
class Pr20RecoveryIT extends Pr20RecoveryFixture {
    PostgreSQLContainer<?> target;
    final RecoveryReleaseGate gate = new RecoveryReleaseGate();
    final List<Map<String, Object>> assertions = new ArrayList<>();

    void check(String name, boolean passed, Object expected, Object actual) {
        assertions.add(Map.of("name", name, "passed", passed, "expected", expected, "actual", actual));
        assertThat(passed)
                .as(name + " expected=" + expected + " actual=" + actual)
                .isTrue();
    }

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    Map<String, Long> sqlCounts(JdbcTemplate db) {
        var map = new LinkedHashMap<String, Long>();
        for (String table : List.of(
                "business_order",
                "payment",
                "refund",
                "provider_operation",
                "recovery_task",
                "balance_transaction",
                "financial_event",
                "journal",
                "journal_entry",
                "balance_projection",
                "outbox_event",
                "consumer_inbox",
                "delivery_task",
                "fee_model")) map.put(table, db.queryForObject("SELECT count(*) FROM platform." + table, Long.class));
        return map;
    }

    void attach(PostgreSQLContainer<?> db) {
        if (pool != null) pool.close();
        DB = db;
        admin = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
        pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(db.getJdbcUrl());
        pool.setUsername("payments_app");
        pool.setPassword("pr02_test_only");
        pool.setMaximumPoolSize(8);
        jdbc = new JdbcTemplate(pool);
        tx = new DataSourceTransactionManager(pool);
        wire();
    }

    @Test
    void consistentArchiveRestoresIntoNewDatabaseWithoutRollingBackExternalFacts() throws Exception {
        String id = "RECOVERY-ARCHIVE-EXTERNAL-CONTINUITY";
        var report = Pr20RecoveryReports.begin("recovery", id);
        Path root = Pr20RecoveryReports.root("recovery");
        Files.createDirectories(root);
        Throwable failure = null;
        var source = DB;
        String sourceId = source.getContainerId();
        Instant restoreStarted = null, cutoff = null, lossDetected = null;
        long restoreNanos = 0;
        try {
            check(
                    "source isolation",
                    source.getDatabaseName().equals("pr20_source_" + fixtureId),
                    "new pr20_source_ random database",
                    source.getDatabaseName());
            check(
                    "source role restricted",
                    !admin.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname='payments_app'", Boolean.class),
                    false,
                    false);
            // Predeclared independent simulator fee contract, saved in the archive before any money facts.
            for (String businessType : List.of("charge", "refund", "payout"))
                admin.update(
                        "INSERT INTO platform.fee_model(organization_id,environment_id,provider_account_id,livemode,currency,balance_domain,business_type,model_version,effective_from,effective_until,basis_points,flat_minor,rounding,fee_direction) VALUES (?,?,?,false,'USD','PAYMENTS',?,'pr20-simulator-contract-v1',?,?,0,?,'DOWN','EXPENSE')",
                        id(1),
                        id(2),
                        id(4),
                        businessType,
                        Timestamp.from(from),
                        Timestamp.from(until),
                        businessType.equals("charge") ? 300 : 0);
            report.put(
                    "predeclaredFeeModels",
                    json.readTree(jdbc.queryForObject(
                            "SELECT jsonb_agg(to_jsonb(f))::text FROM platform.fee_model f", String.class)));
            createPayment();
            capturePayment();
            var capture = bt("capture", "charge", 10000, 300, chargeId, "charge");
            fake.controls().putBalanceTransaction(scope, capture, fake.clock().instant(), null);
            post(capture);
            payoutId = payout("backup_payout");
            var payoutBt = bt("backup_payout-out", "payout", -5700, 0, "backup_payout", "payout");
            fake.controls().putBalanceTransaction(scope, payoutBt, fake.clock().instant(), "backup_payout");
            fake.controls()
                    .putPayout(
                            scope,
                            payoutFact("backup_payout", "backup_payout-out", "backup_payout-return"),
                            from.plusSeconds(100),
                            fake.clock().instant());
            post(payoutBt);
            bankLine = bankLine("pre-backup-independent-bank", 5700, 0, "backup_payout");
            matcher.match(scope, payoutId, bankLine, 5700);
            check(
                    "prebackup bank payout independently matched",
                    matcher.evidenceState(scope, payoutId).equals("MATCHED"),
                    "MATCHED",
                    matcher.evidenceState(scope, payoutId));
            Pr20RecoveryReports.write(
                    root.resolve("simulated-bank-evidence-before-backup.json"),
                    jdbc.queryForList("SELECT * FROM platform.bank_statement"));
            var refund = reserve("backup-refund", 4000);
            var refundOperation = repository.operation(refund.operationId());
            String originalRefundKey = refundOperation.get("operation_key").toString();
            var trusted = trustedCheckout("post-backup-lost-payment");
            var expired = repository.prepare(context, "expired-api", command("expired-order"), "FAKE");
            var expiredOp = repository.operation(expired.operationId());
            fake.controls()
                    .enqueue(
                            scope,
                            FailureScript.Operation.CREATE_SESSION,
                            expiredOp.get("operation_key").toString(),
                            FailureScript.Outcome.BEFORE_SEND_FAILURE);
            executor.execute(expired.operationId());
            report.put(
                    "sourceIsolation",
                    Map.of(
                            "database",
                            source.getDatabaseName(),
                            "containerId",
                            sourceId,
                            "jdbcHost",
                            source.getHost(),
                            "mappedPort",
                            source.getMappedPort(5432),
                            "label",
                            fixtureId,
                            "userDatabaseAccess",
                            false));
            report.put("postgresVersion", admin.queryForObject("SELECT version()", String.class));
            report.put(
                    "pgDumpVersion",
                    source.execInContainer("pg_dump", "--version").getStdout().trim());
            report.put("preBackupExternalEffects", fake.controls().effects(scope));
            Path archive = root.resolve("consistent-backup.dump");
            try (Connection snapshotConnection =
                    DriverManager.getConnection(source.getJdbcUrl(), source.getUsername(), source.getPassword())) {
                snapshotConnection.setAutoCommit(false);
                snapshotConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                snapshotConnection.setReadOnly(true);
                var snapshotDb = new JdbcTemplate(new SingleConnectionDataSource(snapshotConnection, true));
                String snapshot = snapshotDb.queryForObject("SELECT pg_export_snapshot()", String.class);
                cutoff = snapshotDb
                        .queryForObject("SELECT transaction_timestamp()", Timestamp.class)
                        .toInstant();
                report.put("backupStartedAt", Instant.now().toString());
                report.put("backupCutoff", cutoff.toString());
                report.put("exportedSnapshot", snapshot);
                report.put("backupSnapshotCounts", sqlCounts(snapshotDb));
                String[] command = {
                    "pg_dump",
                    "--format=custom",
                    "--snapshot=" + snapshot,
                    "--schema=platform",
                    "--username=" + source.getUsername(),
                    "--dbname=" + source.getDatabaseName(),
                    "--file=/tmp/pr20-consistent.dump"
                };
                report.put("pgDumpCommand", List.of(command));
                var dump = source.execInContainer(command);
                Files.writeString(
                        root.resolve("pg_dump.log"),
                        "exitCode=" + dump.getExitCode() + "\n" + dump.getStdout() + dump.getStderr());
                check("pg_dump completed", dump.getExitCode() == 0, 0, dump.getExitCode());
                source.copyFileFromContainer("/tmp/pr20-consistent.dump", archive.toString());
                snapshotConnection.commit();
            }
            String archiveDigest = sha(archive);
            Files.writeString(root.resolve("archive.sha256"), archiveDigest + "  consistent-backup.dump\n");
            report.put("backupSha256", archiveDigest);
            report.put("backupBytes", Files.size(archive));
            report.put("backupCompletedAt", Instant.now().toString());
            gate.record("ARCHIVE", Files.size(archive) > 0);
            // External mutations happen after the archive; both original local commits and external facts exist here.
            new RefundExecutor(repository, refunds, fake, "FAKE").execute(refund.operationId());
            String providerRefund = jdbc.queryForObject(
                    "SELECT provider_refund_id FROM platform.refund WHERE id=?", String.class, refund.refundId());
            fake.controls().setRefundStatus(scope, providerRefund, "succeeded", null);
            fake.controls().linkRefundTransactions(scope, providerRefund, "post-backup-refund", null);
            new RefundExecutor(repository, refunds, fake, "FAKE").refreshProviderRefund(scope, providerRefund);
            var refundBt = bt("post-backup-refund", "refund", -4000, 0, providerRefund, "refund");
            fake.controls().putBalanceTransaction(scope, refundBt, fake.clock().instant(), null);
            post(refundBt);
            String lostCharge = fake.controls()
                    .attemptPayment(scope, trusted.session(), FakeControls.AttemptOutcome.SUCCEED)
                    .latestChargeId();
            fake.controls().linkChargeBalanceTransaction(scope, lostCharge, "post-backup-capture");
            var lostBt = bt("post-backup-capture", "charge", 10000, 300, lostCharge, "charge");
            fake.controls().putBalanceTransaction(scope, lostBt, fake.clock().instant(), null);
            UUID sourceRun = rescanAndRecoverSources();
            UUID lostLocalPayment = jdbc.queryForObject(
                    "SELECT id FROM platform.payment WHERE business_order_id=?", UUID.class, trusted.order());
            var localOnly =
                    repository.prepare(context, "lost-local-api", command("untrusted-local-only-order"), "FAKE");
            report.put(
                    "missingLocalRecords",
                    List.of(
                            Map.of(
                                    "id",
                                    lostLocalPayment.toString(),
                                    "kind",
                                    "POST_BACKUP_PAYMENT",
                                    "disposition",
                                    "RECOVERABLE_FROM_PRE_BACKUP_TRUSTED_ORDER_AND_EXTERNAL_BT"),
                            Map.of(
                                    "id",
                                    localOnly.paymentId().toString(),
                                    "kind",
                                    "LOCAL_ONLY_ORDER_PAYMENT_OPERATION",
                                    "disposition",
                                    "UNRECOVERABLE_WITHOUT_AUTHENTICATED_MERCHANT_SOURCE")));
            check(
                    "postbackup local commits existed",
                    count("payment")
                            > ((Number) ((Map<?, ?>) report.get("backupSnapshotCounts")).get("payment")).longValue(),
                    "more than backup",
                    count("payment"));
            report.put("preLossSnapshot", snapshot());
            report.put("preLossCounts", sqlCounts(jdbc));
            report.put("sourceRecoveryRun", sourceRun.toString());
            var independentPeriod = new ReportFact(
                    "pr20-independent-period",
                    "available",
                    from,
                    until,
                    List.of(
                            new BalanceSnapshot("USD", "PAYMENTS", 0, 0, from),
                            new BalanceSnapshot("USD", "PAYMENTS", 9700, 0, until)),
                    "c".repeat(64));
            fake.controls()
                    .putReport(new ReportRequest(scope, "pr20-independent-period", from, until), independentPeriod);
            report.put("independentPeriodEvidenceBeforeLoss", independentPeriod);
            var effectsBefore = fake.controls().effects(scope);
            report.put("externalEffectsBeforeRestore", effectsBefore);
            Files.copy(
                    directory.resolve("independent-provider/state.json"),
                    root.resolve("external-state-before-restore.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            String independentStateDigest = sha(root.resolve("external-state-before-restore.json"));
            report.put("externalStateBeforeRestoreSha256", independentStateDigest);
            fake.clock().advance(Duration.ofHours(2));
            lossDetected = Instant.now();
            report.put("lossDetectedAt", lossDetected.toString());
            pool.close();
            source.stop();
            restoreStarted = Instant.now();
            restoreNanos = System.nanoTime();
            target = new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("pr20_restore_" + fixtureId)
                    .withInitScript("db/pr02-runtime-role.sql")
                    .withLabel("pr20-isolated", fixtureId);
            target.start();
            check(
                    "new distinct target",
                    !target.getContainerId().equals(sourceId)
                            && target.getDatabaseName().equals("pr20_restore_" + fixtureId),
                    "new container and new pr20_restore database",
                    target.getContainerId() + "/" + target.getDatabaseName());
            gate.record("ISOLATION", true);
            report.put(
                    "targetIsolation",
                    Map.of(
                            "database",
                            target.getDatabaseName(),
                            "containerId",
                            target.getContainerId(),
                            "newDistinctTarget",
                            true,
                            "jdbcHost",
                            target.getHost(),
                            "mappedPort",
                            target.getMappedPort(5432),
                            "userDatabaseAccess",
                            false));
            check("archive checksum unchanged", sha(archive).equals(archiveDigest), archiveDigest, sha(archive));
            target.copyFileToContainer(MountableFile.forHostPath(archive), "/tmp/pr20-consistent.dump");
            String[] restoreCommand = {
                "pg_restore",
                "--clean",
                "--if-exists",
                "--exit-on-error",
                "--username=" + target.getUsername(),
                "--dbname=" + target.getDatabaseName(),
                "/tmp/pr20-consistent.dump"
            };
            report.put("pgRestoreCommand", List.of(restoreCommand));
            report.put(
                    "pgRestoreVersion",
                    target.execInContainer("pg_restore", "--version")
                            .getStdout()
                            .trim());
            var restored = target.execInContainer(restoreCommand);
            Files.writeString(
                    root.resolve("pg_restore.log"),
                    "exitCode=" + restored.getExitCode() + "\n" + restored.getStdout() + restored.getStderr());
            check("pg_restore completed", restored.getExitCode() == 0, 0, restored.getExitCode());
            attach(target);
            report.put("restoredSnapshotCounts", sqlCounts(jdbc));
            check(
                    "restored archive snapshot count",
                    report.get("backupSnapshotCounts").equals(report.get("restoredSnapshotCounts")),
                    report.get("backupSnapshotCounts"),
                    report.get("restoredSnapshotCounts"));
            Flyway.configure()
                    .dataSource(target.getJdbcUrl(), target.getUsername(), target.getPassword())
                    .schemas("platform")
                    .load()
                    .validate();
            report.put("migrationValidation", "PASS");
            fake = new FakeStripeProvider(
                    directory.resolve("independent-provider"),
                    200020L,
                    Instant.parse("2026-10-07T12:00:00Z"),
                    Duration.ofDays(1));
            wire();
            check(
                    "external state continuity",
                    RecoveryReleaseGate.externalContinuous(
                            effectsBefore, fake.controls().effects(scope)),
                    effectsBefore,
                    fake.controls().effects(scope));
            gate.record("EXTERNAL_CONTINUITY", true);
            admin.execute("ALTER ROLE payments_app SET default_transaction_read_only=on");
            attach(target);
            check(
                    "app recovery connection read only",
                    jdbc.queryForObject("SHOW transaction_read_only", String.class)
                            .equals("on"),
                    "on",
                    jdbc.queryForObject("SHOW transaction_read_only", String.class));
            assertThatThrownBy(() -> jdbc.update("UPDATE platform.payment SET version=version+1 WHERE id=?", paymentId))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            check(
                    "app cannot forge server binding",
                    !jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,'platform.order_provider_binding','INSERT')",
                            Boolean.class),
                    false,
                    false);
            check(
                    "app cannot mutate journal",
                    !jdbc.queryForObject(
                            "SELECT has_table_privilege(current_user,'platform.journal_entry','UPDATE')",
                            Boolean.class),
                    false,
                    false);
            gate.record("PERMISSIONS", true);
            var beforeAudit = independentAudit();
            report.put("restoredIndependentAudit", beforeAudit);
            gate.record("REBUILD", true);
            gate.record("TRIAL_BALANCE", true);
            gate.record("EVENT_COVERAGE", true);
            assertThatThrownBy(gate::requireFundsWrite).isInstanceOf(IllegalStateException.class);
            // Permit evidence transactions only; external funds execution remains individually gated.
            admin.execute("ALTER ROLE payments_app RESET default_transaction_read_only");
            attach(target);
            var eligible = repository.prepare(context, "eligible-api", command("eligible-order"), "FAKE");
            var eligibleBefore = repository.operation(eligible.operationId());
            report.put(
                    "eligibleOperationCreatedAfterLocalAuditAt", Instant.now().toString());
            Map<String, UUID> guardedKeys = Map.of(
                    eligibleBefore.get("operation_key").toString(),
                    eligible.operationId(),
                    expiredOp.get("operation_key").toString(),
                    expired.operationId());
            CheckoutPort guardedCheckout = new CheckoutPort() {
                public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
                    gate.requireFundsWrite(guardedKeys.get(c.operationIdempotencyKey()));
                    return fake.createSession(c);
                }

                public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String key) {
                    return fake.querySession(s, key);
                }

                public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String key) {
                    gate.requireFundsWrite();
                    return fake.expireSession(s, key);
                }
            };
            RefundPort guardedRefund = new RefundPort() {
                public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand c) {
                    gate.requireFundsWrite(
                            c.operationIdempotencyKey().equals(originalRefundKey) ? refund.operationId() : null);
                    return fake.createRefund(c);
                }

                public ProviderCallResult<RefundFact> queryRefund(ProviderScope s, String key) {
                    return fake.queryRefund(s, key);
                }
            };
            var restoredRefundExecutor = new RefundExecutor(repository, refunds, guardedRefund, "FAKE");
            executor = new PaymentExecutor(repository, guardedCheckout, fake, fake, "FAKE");
            service = new PaymentService(repository, executor);
            long heldRequests = fake.counters(scope).requestCount();
            assertThatThrownBy(() -> guardedCheckout.createSession(
                            repository.decode(eligibleBefore.get("command"), CreateCheckoutSessionCommand.class)))
                    .isInstanceOf(IllegalStateException.class);
            check(
                    "external write blocked at provider port before financial gates",
                    fake.counters(scope).requestCount() == heldRequests,
                    heldRequests,
                    fake.counters(scope).requestCount());
            report.put("localEvidenceWritesEnabledAt", Instant.now().toString());
            UUID run = rescanAndRecoverSources();
            check(
                    "postbackup trusted payment backfilled",
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.payment WHERE business_order_id=? AND origin='RECONCILIATION'",
                                    Long.class,
                                    trusted.order())
                            == 1,
                    1,
                    1);
            check(
                    "local-only record remains missing",
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.payment WHERE id=?",
                                    Long.class,
                                    localOnly.paymentId())
                            == 0,
                    0,
                    0);
            // The independent provider's successful receipt ties the refund ID to the original scoped operation key.
            JsonNode receipt = fake.controls().calls(scope).stream()
                    .filter(c -> c.path("operation").asText().equals("CREATE_REFUND")
                            && c.path("target").asText().equals(originalRefundKey)
                            && c.path("classification").asText().equals("SUCCESS"))
                    .findFirst()
                    .orElseThrow();
            String receiptRefund = receipt.path("payload").path("refundId").asText();
            check(
                    "safe original refund key receipt",
                    receiptRefund.equals(providerRefund),
                    providerRefund,
                    receiptRefund);
            var query = fake.queryRefund(scope, receiptRefund);
            var originalCommand = repository.decode(refundOperation.get("command"), CreateRefundCommand.class);
            check(
                    "receipt matches original trusted refund command",
                    query.payload().chargeId().equals(originalCommand.chargeId())
                            && query.payload().amountMinor() == originalCommand.amountMinor(),
                    originalCommand.amountMinor(),
                    query.payload().amountMinor());
            refunds.accept(scope, query.payload(), query.evidence(), refund.refundId());
            check(
                    "postbackup refund replay",
                    replay.replay(scope, "post-backup-refund").equals("POSTED"),
                    "POSTED",
                    "POSTED");
            var refundClaim = repository.claimRecovery(refund.operationId(), "FAKE", true);
            if (refundClaim != null) restoredRefundExecutor.recover(refundClaim);
            admin.update(
                    "UPDATE platform.recovery_task SET available_at=? WHERE operation_id=?",
                    Timestamp.from(fake.clock().instant().plus(Duration.ofDays(1))),
                    eligible.operationId());
            long requestsBeforeExpired = fake.counters(scope).requestCount();
            int callsBeforeExpired = fake.controls().calls(scope).size();
            new OperationRecoveryCoordinator(repository, executor).recoverDue(100);
            var coordinatorCalls = List.copyOf(fake.controls()
                    .calls(scope)
                    .subList(callsBeforeExpired, fake.controls().calls(scope).size()));
            check(
                    "expired unknown escalation used no provider request",
                    coordinatorCalls.stream()
                            .noneMatch(c -> c.path("target").asText().equals(expiredOp.get("operation_key"))),
                    "no request for expired operation key",
                    coordinatorCalls);
            check(
                    "coordinator other operations only performed reads",
                    coordinatorCalls.stream()
                            .allMatch(c -> Set.of(
                                            "QUERY_SESSION",
                                            "QUERY_PAYMENT_INTENT",
                                            "LIST_CHARGE_ATTEMPTS",
                                            "QUERY_CHARGE",
                                            "QUERY_REFUND")
                                    .contains(c.path("operation").asText())),
                    "read-only provider calls",
                    coordinatorCalls);
            report.put("unknownDispositionCoordinatorCalls", coordinatorCalls);
            check(
                    "expired unknown escalated",
                    repository.operation(expired.operationId()).get("status").equals("ESCALATED"),
                    "ESCALATED",
                    repository.operation(expired.operationId()).get("status"));
            check(
                    "expired unknown no blind resend",
                    fake.controls().calls(scope).stream()
                                    .filter(c -> c.path("operation").asText().equals("CREATE_SESSION")
                                            && c.path("target").asText().equals(expiredOp.get("operation_key")))
                                    .count()
                            == 1,
                    "one before-send request only",
                    1);
            report.put(
                    "expiredUnknown",
                    Map.of(
                            "operationId",
                            expired.operationId().toString(),
                            "reason",
                            repository.operation(expired.operationId()).get("escalation_reason"),
                            "providerRequestsBeforeCoordinator",
                            requestsBeforeExpired,
                            "excludedFromReleasedOperations",
                            true));
            gate.record("UNKNOWN_DISPOSITION", true);
            for (String bt : List.of("capture", "backup_payout-out", "post-backup-capture", "post-backup-refund"))
                assertThat(replay.replay(scope, bt)).isEqualTo("POSTED");
            check(
                    "BT coverage after scoped rescan",
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.balance_transaction WHERE state<>'POSTED'",
                                    Long.class)
                            == 0,
                    0,
                    0);
            gate.record("BT_COVERAGE", true);
            report.put(
                    "rescan",
                    Map.of(
                            "organizationId",
                            id(1).toString(),
                            "environmentId",
                            id(2).toString(),
                            "providerAccountId",
                            id(4).toString(),
                            "livemode",
                            false,
                            "currency",
                            "USD",
                            "from",
                            from.toString(),
                            "until",
                            until.toString(),
                            "runId",
                            run.toString(),
                            "pages",
                            json.readTree(jdbc.queryForObject(
                                    "SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]'::jsonb)::text FROM platform.import_page p",
                                    String.class))));
            var inbox = new ConsumerInboxService(jdbc, tx, json, Clock.systemUTC());
            var seen = new ArrayList<UUID>();
            var publisher = new OutboxPublisher(
                    new OutboxRepository(jdbc, tx, Clock.systemUTC()),
                    event -> {
                        seen.add(event.id());
                        inbox.accept(event.bytes());
                        inbox.accept(event.bytes());
                    },
                    Duration.ofSeconds(30));
            publisher.drain(100);
            long deliveries = count("delivery_task");
            publisher.drain(100);
            long pendingOutbox = jdbc.queryForObject(
                    "SELECT count(*) FROM platform.outbox_event WHERE status<>'PUBLISHED'", Long.class);
            check("outbox replay drains publication", pendingOutbox == 0, 0, pendingOutbox);
            check(
                    "event replay creates one inbox and delivery per event",
                    count("consumer_inbox") == count("outbox_event") && count("delivery_task") == deliveries,
                    deliveries,
                    count("delivery_task"));
            gate.record("OUTBOX_REPLAY", true);
            report.put(
                    "notificationReplay",
                    Map.of(
                            "clockDomain",
                            "DATABASE_WALL_CLOCK_FOR_NOTIFICATION_AVAILABILITY",
                            "mode",
                            "REAL_OUTBOX_AND_CONSUMER_INBOX_DIRECT_SENDER",
                            "events",
                            seen,
                            "inboxCount",
                            count("consumer_inbox"),
                            "deliveryCount",
                            deliveries,
                            "heldDeliveries",
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.delivery_task WHERE status='HELD'", Long.class),
                            "merchantDelivery",
                            "HELD: no target configured; manual controlled release required"));
            var periodRead = fake.fetchReport(scope, "pr20-independent-period");
            assertThat(periodRead.classification()).isEqualTo(ProviderCallClassification.SUCCESS);
            check(
                    "independent external period exact cutoffs",
                    periodRead.payload().createdGte().equals(from)
                            && periodRead.payload().createdLt().equals(until),
                    from + "/" + until,
                    periodRead.payload().createdGte() + "/"
                            + periodRead.payload().createdLt());
            long independentClosing = periodRead.payload().balances().getLast().availableMinor()
                    + periodRead.payload().balances().getLast().pendingMinor();
            report.put("independentPeriodEvidenceAfterRestore", periodRead);
            var independent = new IndependentBalanceEvidence(
                    scope,
                    "USD",
                    "PAYMENTS",
                    from,
                    until,
                    0,
                    independentClosing,
                    "persistent-independent-period-fixture",
                    "pr20-recovery-v1",
                    periodRead.payload().rawDigest(),
                    FakeStripeProvider.API_VERSION,
                    IndependentBalanceEvidence.Kind.INDEPENDENT_FIXTURE,
                    false);
            reconciliation.finish(run, independent);
            var checks = jdbc.queryForList(
                    "SELECT check_number,outcome,residual,reason,completeness FROM platform.reconciliation_check WHERE run_id=? ORDER BY check_number",
                    run);
            report.put("reconciliationChecks", checks);
            for (int number : List.of(1, 3, 4, 5, 6, 7)) {
                String outcome = jdbc.queryForObject(
                        "SELECT outcome FROM platform.reconciliation_check WHERE run_id=? AND check_number=?",
                        String.class,
                        run,
                        number);
                check("financial reconciliation check " + number, outcome.equals("PASS"), "PASS", outcome);
            }
            check(
                    "independent external closing",
                    balance("PSP_FUNDS") == independentClosing,
                    independentClosing,
                    balance("PSP_FUNDS"));
            gate.record("INDEPENDENT_BALANCE", true);
            check(
                    "restored simulated bank principal and independent match",
                    balance("BANK_CASH") == 5700
                            && matcher.evidenceState(scope, payoutId).equals("MATCHED"),
                    "5700/MATCHED",
                    balance("BANK_CASH") + "/" + matcher.evidenceState(scope, payoutId));
            report.put(
                    "bankEvidenceRestored",
                    Map.of(
                            "evidenceType",
                            "SIMULATED_BANK_EVIDENCE",
                            "statementCount",
                            count("bank_statement"),
                            "statementLineCount",
                            count("bank_statement_line"),
                            "matchedPayoutId",
                            payoutId.toString(),
                            "bankPrincipalMinor",
                            5700,
                            "bankEvidencePath",
                            root.resolve("simulated-bank-evidence-before-backup.json")
                                    .toString(),
                            "matchState",
                            matcher.evidenceState(scope, payoutId)));
            String operationalOutcome = jdbc.queryForObject(
                    "SELECT outcome FROM platform.reconciliation_check WHERE run_id=? AND check_number=2",
                    String.class,
                    run);
            check(
                    "operational reconciliation retains expired UNKNOWN disposition",
                    "INCOMPLETE".equals(operationalOutcome),
                    "INCOMPLETE",
                    operationalOutcome);
            report.put(
                    "operationalReconciliationStatus",
                    "INCOMPLETE: expired UNKNOWN remains escalated for investigation");
            report.put(
                    "unrecoverableRecords",
                    List.of(
                            Map.of(
                                    "paymentId",
                                    localOnly.paymentId().toString(),
                                    "classification",
                                    "LOCAL_ONLY_UNTRUSTED_LOSS",
                                    "automaticRecovery",
                                    false,
                                    "reason",
                                    "Order, API idempotency record and operation committed after backup; no provider money fact or pre-backup trusted order exists")));
            report.put(
                    "manualSteps",
                    List.of(
                            "Investigate expired UNKNOWN using scoped provider support evidence; do not rotate its key or blindly resend",
                            "Ask authenticated merchant to resubmit the missing local-only order with the original business key after investigation; no automatic reconstruction was attempted",
                            "Review held merchant delivery tasks and configure an authenticated target before delivery",
                            "Preserve original archive, provider-state checksums, SQL snapshots and recovery provenance for review"));
            gate.record("MANUAL_LOSS_DISPOSITION", true);
            boolean trustedEligible = jdbc.queryForObject(
                            "SELECT count(*) FROM platform.payment p JOIN platform.business_order o ON o.id=p.business_order_id JOIN platform.order_fulfillment_eligibility e ON e.order_id=o.id WHERE p.id=? AND e.eligible AND e.policy_version=1 AND p.organization_id=? AND p.environment_id=? AND p.provider_account_id=? AND p.currency='USD' AND p.amount_minor=o.amount_minor",
                            Long.class,
                            eligible.paymentId(),
                            id(1),
                            id(2),
                            id(4))
                    == 1;
            check(
                    "released operation has trusted scoped order and original unused key",
                    trustedEligible && eligibleBefore.get("first_send_at") == null,
                    "trusted active immutable server order and original unsent operation",
                    eligible.operationId().toString());
            gate.authorize(
                    eligible.operationId(),
                    trustedEligible,
                    "PREPARED".equals(eligibleBefore.get("status")) && eligibleBefore.get("first_send_at") == null);
            gate.requireFundsWrite(eligible.operationId());
            assertThatThrownBy(() -> gate.requireFundsWrite(expired.operationId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(gate::requireFundsWrite).isInstanceOf(IllegalStateException.class);
            executor.execute(eligible.operationId());
            executor.execute(eligible.operationId());
            check(
                    "released eligible operation preserves key",
                    repository
                            .operation(eligible.operationId())
                            .get("operation_key")
                            .equals(eligibleBefore.get("operation_key")),
                    eligibleBefore.get("operation_key"),
                    repository.operation(eligible.operationId()).get("operation_key"));
            check(
                    "eligible checkout accepted once",
                    fake.controls().calls(scope).stream()
                                    .filter(c -> c.path("operation").asText().equals("CREATE_SESSION")
                                            && c.path("target").asText().equals(eligibleBefore.get("operation_key")))
                                    .count()
                            == 1,
                    1,
                    1);
            check(
                    "independent external effects not duplicated",
                    RecoveryReleaseGate.externalContinuous(
                            effectsBefore, fake.controls().effects(scope)),
                    effectsBefore,
                    fake.controls().effects(scope));
            report.put("externalEffectsAfterRecovery", fake.controls().effects(scope));
            report.put("externalEffectsBeforeRecovery", effectsBefore);
            report.put("externalStateContinuity", true);
            report.put("postBackupLossExercised", true);
            report.put("noDuplicateExternalEffects", true);
            report.put("releaseGates", gate.evidence());
            report.put(
                    "writeGateRelease",
                    Map.of(
                            "mode",
                            "ELIGIBLE_OPERATIONS_ONLY",
                            "globalWritesReleased",
                            false,
                            "releasedOperationIds",
                            List.of(eligible.operationId().toString()),
                            "excludedOperationIds",
                            List.of(expired.operationId().toString()),
                            "excludedManualRecords",
                            List.of(localOnly.paymentId().toString()),
                            "releasedAt",
                            Instant.now().toString()));
            check(
                    "released eligible checkout is confirmed",
                    repository
                            .read(context, eligible.paymentId())
                            .get("resolutionStatus")
                            .equals("KNOWN"),
                    "KNOWN",
                    repository.read(context, eligible.paymentId()).get("resolutionStatus"));
            report.put("finalIndependentAudit", independentAudit());
            report.put("status", "PASS");
        } catch (Exception | AssertionError e) {
            failure = e;
            throw e;
        } finally {
            Instant completed = Instant.now();
            report.put("restoreStartedAt", restoreStarted == null ? "NOT_RUN" : restoreStarted.toString());
            report.put("recoveryCompletedAt", completed.toString());
            report.put(
                    "rpoDefinition",
                    "Elapsed wall-clock time from exported consistent backup transaction cutoff to simulated local-loss detection; it bounds lost local commits, not external money loss");
            report.put(
                    "rtoDefinition",
                    "Elapsed monotonic time from starting the new isolated target through financial checks, operational disposition, and individually eligible execution release");
            report.put(
                    "rpoMillis",
                    cutoff == null || lossDetected == null
                            ? null
                            : Duration.between(cutoff, lossDetected).toMillis());
            report.put("rtoMillis", restoreNanos == 0 ? null : (System.nanoTime() - restoreNanos) / 1_000_000);
            report.put(
                    "dataLossWindow",
                    Map.of(
                            "fromInclusive",
                            cutoff == null ? "NOT_RUN" : cutoff.toString(),
                            "untilInclusive",
                            lossDetected == null ? "NOT_RUN" : lossDetected.toString()));
            report.put(
                    "backup",
                    Map.of(
                            "path",
                            root.resolve("consistent-backup.dump").toString(),
                            "sha256",
                            Objects.toString(report.get("backupSha256"), "NOT_RUN"),
                            "cutoff",
                            Objects.toString(report.get("backupCutoff"), "NOT_RUN"),
                            "snapshot",
                            Objects.toString(report.get("exportedSnapshot"), "NOT_RUN"),
                            "bytes",
                            report.getOrDefault("backupBytes", 0),
                            "format",
                            "PG_DUMP_CUSTOM_EXPORTED_REPEATABLE_READ_SNAPSHOT"));
            report.put(
                    "isolation",
                    Map.of(
                            "source",
                            report.getOrDefault("sourceIsolation", Map.of()),
                            "target",
                            report.getOrDefault("targetIsolation", Map.of()),
                            "newDistinctTarget",
                            target != null
                                    && target.getContainerId() != null
                                    && !target.getContainerId().equals(sourceId),
                            "userDatabaseAccess",
                            false));
            report.put(
                    "externalState",
                    Map.of(
                            "preserved",
                            Boolean.TRUE.equals(report.get("externalStateContinuity")),
                            "postBackupLossExercised",
                            Boolean.TRUE.equals(report.get("postBackupLossExercised")),
                            "effectsBefore",
                            report.getOrDefault("externalEffectsBeforeRestore", List.of()),
                            "effectsAfter",
                            report.getOrDefault("externalEffectsAfterRecovery", List.of()),
                            "beforeSha256",
                            report.getOrDefault("externalStateBeforeRestoreSha256", "NOT_RUN"),
                            "path",
                            root.resolve("provider-state.json").toString(),
                            "noDuplicateEffects",
                            Boolean.TRUE.equals(report.get("noDuplicateExternalEffects"))));
            report.put(
                    "gates",
                    Map.of(
                            "writeGateReleased",
                            gate.released() && report.get("writeGateRelease") != null,
                            "releaseMode",
                            "ELIGIBLE_OPERATIONS_ONLY",
                            "globalWritesReleased",
                            false,
                            "evidence",
                            gate.evidence(),
                            "releaseScope",
                            report.getOrDefault("writeGateRelease", Map.of()),
                            "operationalStatus",
                            report.getOrDefault("operationalReconciliationStatus", "NOT_RUN"),
                            "financialChecks",
                            report.getOrDefault("reconciliationChecks", List.of())));
            var rpo = new LinkedHashMap<String, Object>();
            rpo.put("definition", report.get("rpoDefinition"));
            rpo.put("measuredMillis", report.get("rpoMillis"));
            rpo.put("dataLossWindow", report.get("dataLossWindow"));
            report.put("rpo", rpo);
            var rto = new LinkedHashMap<String, Object>();
            rto.put("definition", report.get("rtoDefinition"));
            rto.put("measuredMillis", report.get("rtoMillis"));
            rto.put("startedAt", report.get("restoreStartedAt"));
            rto.put("completedAt", completed.toString());
            report.put("rto", rto);
            report.put(
                    "actualCommands",
                    List.of(
                            report.getOrDefault("pgDumpCommand", List.of()),
                            report.getOrDefault("pgRestoreCommand", List.of()),
                            System.getProperty(
                                    "pr20.command", "JAVA_TEST_ENTRYPOINT com.paymentsledger.payment.Pr20RecoveryIT")));
            report.put("assertionResults", assertions);
            report.put(
                    "actualCommand",
                    System.getProperty(
                            "pr20.command", "JAVA_TEST_ENTRYPOINT com.paymentsledger.payment.Pr20RecoveryIT"));
            persistFixture(root, report);
            Pr20RecoveryReports.finish("recovery", id, report, failure);
            if (target != null && target.isRunning()) {
                if (pool != null) pool.close();
                target.stop();
            }
        }
    }
}
