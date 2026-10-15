package com.paymentsledger.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.FinancialReplayService;
import com.paymentsledger.payment.ReconciliationSourceRecovery;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Each nightly overlap is a new evidence version. Bounded polls resume pages and existing task leases. */
public final class ReconciliationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationWorker.class);
    private final ReconciliationService engine;
    private final ReconciliationSourceRecovery sources;
    private final FinancialReplayService replay;
    private final JdbcTemplate jdbc;
    private final ReportPort reports;
    private final Clock clock;
    private final ProviderScope scope;
    private final String currency;
    private final ZoneId zone;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ReconciliationWorker(
            ReconciliationService engine,
            ReconciliationSourceRecovery sources,
            FinancialReplayService replay,
            JdbcTemplate jdbc,
            ReportPort reports,
            Clock clock,
            ProviderScope scope,
            String currency,
            ZoneId zone) {
        this.engine = engine;
        this.sources = sources;
        this.replay = replay;
        this.jdbc = jdbc;
        this.reports = reports;
        this.clock = clock;
        this.scope = scope;
        this.currency = currency;
        this.zone = zone;
    }

    @Scheduled(
            scheduler = "financialIngestionScheduler",
            cron = "${platform.reconciliation.cron:0 10 2 * * *}",
            zone = "${platform.reconciliation.business-zone:UTC}")
    public void nightly() {
        outside();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        for (int lookback = 1; lookback <= 7; lookback++)
            engine.createDay(scope, currency, today.minusDays(lookback), zone);
    }

    @Scheduled(
            scheduler = "financialIngestionScheduler",
            fixedDelayString = "${platform.reconciliation.poll-ms:5000}",
            initialDelayString = "${platform.reconciliation.poll-ms:5000}")
    public void drain() {
        outside();
        // Reconciliation-source recovery uses the same durable task leases even after a report captured an earlier
        // snapshot.
        var due = jdbc.queryForList(
                """
   SELECT reconciliation_run_id FROM platform.recovery_task
   WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND currency=?
    AND task_type='RECONCILIATION_SOURCE' AND status IN ('READY','WAITING','RUNNING') AND available_at<=?
    AND (lease_until IS NULL OR lease_until<=?) GROUP BY reconciliation_run_id ORDER BY min(available_at) LIMIT 5
   """,
                UUID.class,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                currency,
                ts(clock.instant()),
                ts(clock.instant()));
        for (UUID run : due)
            guard(run, () -> {
                for (var claim : sources.claim(scope, run, 5)) sources.recover(claim);
            });
        var active = jdbc.queryForList(
                """
                SELECT r.id
                FROM platform.reconciliation_run r
                JOIN platform.reconciliation_cursor c ON c.run_id=r.id
                WHERE r.organization_id=?
                    AND r.environment_id=?
                    AND r.provider_account_id=?
                    AND r.livemode=?
                    AND r.currency=?
                    AND r.finished_at IS NULL
                ORDER BY c.updated_at,r.id
                LIMIT 5""",
                UUID.class,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                currency);
        for (UUID run : active) guard(run, () -> process(run));
    }

    private void process(UUID runId) {
        engine.advance(runId, 5);
        var run = engine.query(scope, runId);
        String state = jdbc.queryForObject(
                "SELECT state FROM platform.import_window WHERE id=?", String.class, run.importWindowId());
        if (!"COMPLETE".equals(state)) return;
        sources.drain(scope, runId, 5);
        String replayAfter = jdbc.queryForObject(
                "SELECT replay_after_bt FROM platform.reconciliation_cursor WHERE run_id=?", String.class, runId);
        var btIds = jdbc.queryForList(
                """
   SELECT DISTINCT b.bt_id FROM platform.import_page p JOIN platform.balance_transaction b ON b.bt_id=ANY(p.bt_ids)
   WHERE p.window_id=? AND b.organization_id=? AND b.environment_id=? AND b.provider_account_id=? AND b.livemode=? AND b.currency=? AND b.bt_id>?
   ORDER BY b.bt_id LIMIT 25
   """,
                String.class,
                run.importWindowId(),
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                currency,
                replayAfter);
        for (String btId : btIds) {
            replay.replay(scope, btId);
            // Replaying after a crash is idempotent; only advance the durable cursor after the existing owner commits.
            jdbc.update("""
                    UPDATE platform.reconciliation_cursor
                    SET replay_after_bt=?
                    WHERE run_id=? AND replay_after_bt<?""", btId, runId, btId);
        }
        if (btIds.size() == 25) return;
        Long notQueued = jdbc.queryForObject(
                """
   SELECT count(*) FROM platform.balance_transaction b WHERE b.organization_id=? AND b.environment_id=? AND b.provider_account_id=? AND b.livemode=? AND b.currency=?
    AND b.business_type='charge' AND b.balance_domain='PAYMENTS' AND b.source_object_type='charge'
    AND EXISTS(SELECT 1 FROM platform.import_page p WHERE p.window_id=? AND b.bt_id=ANY(p.bt_ids))
    AND NOT EXISTS(SELECT 1 FROM platform.recovery_task t WHERE t.reconciliation_run_id=? AND t.source_bt_id=b.id)
   """,
                Long.class,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                currency,
                run.importWindowId(),
                runId);
        if (notQueued > 0) return;
        ReportRead report = independentReport(run);
        if (!report.pending()) engine.finish(runId, report.evidence());
    }

    private record ReportRead(IndependentBalanceEvidence evidence, boolean pending) {}

    private static final ReportRead MISSING_REPORT = new ReportRead(null, false);

    private ReportRead independentReport(ReconciliationReport run) {
        if (reports == null) return MISSING_REPORT;
        outside();
        ProviderCallResult<ReportFact> result;
        var prior = jdbc.queryForList("""
                SELECT payload->'report'->>'reportId' AS report_id
                FROM platform.reconciliation_evidence
                WHERE run_id=?
                    AND evidence_kind='PROVIDER_REPORT_READ'
                    AND payload->>'classification'='SUCCESS'
                    AND payload->'evidence'->>'httpStatus'='200'
                    AND payload->'report'->>'status' IN ('pending','queued','running')
                    AND payload->'report'->'createdGte'=payload->'requestedFrom'
                    AND payload->'report'->'createdLt'=payload->'requestedUntil'
                    AND nullif(payload->'report'->>'reportId','') IS NOT NULL
                ORDER BY created_at DESC,id DESC
                LIMIT 1""", run.runId());
        String reportId = prior.isEmpty() ? null : (String) prior.getFirst().get("report_id");
        try {
            result = reportId == null
                    ? reports.requestReport(new ReportRequest(scope, "balance.period", run.from(), run.until()))
                    : reports.fetchReport(scope, reportId);
        } catch (RuntimeException unavailable) {
            recordUnavailable(run);
            return new ReportRead(null, reportId != null && beforeReportDeadline(run));
        }
        // Preserve response classification and bytes-safe DTO evidence without claiming availability means historical
        // comparability.
        record(run, result);
        if (result.classification() != ProviderCallClassification.SUCCESS
                || result.payload() == null
                || result.evidence() == null
                || !Objects.equals(result.evidence().httpStatus(), 200)) {
            boolean transientRead = Set.of(
                            ProviderCallClassification.TRANSIENT_EMPTY,
                            ProviderCallClassification.RETRYABLE_TRANSIENT,
                            ProviderCallClassification.RATE_LIMITED,
                            ProviderCallClassification.TIMEOUT_UNKNOWN)
                    .contains(result.classification());
            return new ReportRead(null, reportId != null && transientRead && beforeReportDeadline(run));
        }
        ReportFact report = result.payload();
        if (report.reportId() == null
                || report.reportId().isBlank()
                || reportId != null && !reportId.equals(report.reportId())
                || !run.from().equals(report.createdGte())
                || !run.until().equals(report.createdLt())) return MISSING_REPORT;
        if (Set.of("pending", "queued", "running").contains(report.status())) {
            // A pending historical report is durable work, not a current balance or a completed proof.
            return new ReportRead(null, beforeReportDeadline(run));
        }
        if (!Set.of("available", "complete", "succeeded").contains(report.status())) return MISSING_REPORT;
        var balances = report.balances().stream()
                .filter(b -> currency.equalsIgnoreCase(b.currency()) && "PAYMENTS".equals(b.balanceDomain()))
                .toList();
        var opening =
                balances.stream().filter(b -> b.capturedAt().equals(run.from())).toList();
        var closing = balances.stream()
                .filter(b -> b.capturedAt().equals(run.until()))
                .toList();
        if (opening.size() != 1
                || closing.size() != 1
                || result.evidence().apiVersion() == null
                || report.rawDigest() == null
                || !report.rawDigest().matches("[a-f0-9]{64}")) return MISSING_REPORT;
        return new ReportRead(
                new IndependentBalanceEvidence(
                        scope,
                        currency,
                        "PAYMENTS",
                        run.from(),
                        run.until(),
                        Math.addExact(
                                opening.getFirst().availableMinor(),
                                opening.getFirst().pendingMinor()),
                        Math.addExact(
                                closing.getFirst().availableMinor(),
                                closing.getFirst().pendingMinor()),
                        report.reportId(),
                        result.evidence().apiVersion(),
                        report.rawDigest(),
                        result.evidence().apiVersion(),
                        IndependentBalanceEvidence.Kind.PERIOD_REPORT,
                        false),
                false);
    }

    private boolean beforeReportDeadline(ReconciliationReport run) {
        Instant created = jdbc.queryForObject(
                        "SELECT created_at FROM platform.reconciliation_run WHERE id=?", Timestamp.class, run.runId())
                .toInstant();
        return clock.instant().isBefore(created.plus(Duration.ofDays(1)));
    }

    private void record(ReconciliationReport run, ProviderCallResult<ReportFact> result) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("requestedFrom", run.from());
            payload.put("requestedUntil", run.until());
            payload.put("classification", result.classification());
            payload.put("evidence", result.evidence());
            payload.put("report", result.payload());
            String digest = result.evidence() != null && result.evidence().rawDigest() != null
                    ? result.evidence().rawDigest()
                    : "0".repeat(64);
            String version = result.evidence() != null && result.evidence().apiVersion() != null
                    ? result.evidence().apiVersion()
                    : "UNAVAILABLE";
            jdbc.update(
                    """
                    INSERT INTO platform.reconciliation_evidence(run_id,organization_id,environment_id,
                        provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                    VALUES (?,?,?,?,?,?,'PROVIDER_REPORT_READ','REPORT_PORT',?,?,?::jsonb)""",
                    run.runId(),
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    currency,
                    version,
                    digest,
                    json.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new IllegalArgumentException("Invalid report DTO evidence", malformed);
        }
    }

    private void recordUnavailable(ReconciliationReport run) {
        jdbc.update(
                """
                INSERT INTO platform.reconciliation_evidence(run_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                VALUES (?,?,?,?,?,?,'PROVIDER_REPORT_READ','REPORT_PORT','UNAVAILABLE',?,
                    '{\"classification\":\"READ_THROWN\"}')""",
                run.runId(),
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                currency,
                "0".repeat(64));
    }

    private void guard(UUID run, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOG.warn("reconciliation runId={} reason=RETRY_REQUIRED", run);
        }
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reconciliation provider reads require no local transaction");
    }
}
