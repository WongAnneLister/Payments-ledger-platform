package com.paymentsledger.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.financialingestion.FinancialImportService;
import com.paymentsledger.ledger.BalanceRebuildService;
import com.paymentsledger.provider.ProviderScope;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Coordinates existing ingestion. Reports freeze a single repeatable-read local snapshot. */
public class ReconciliationService {
    static final String RULE_VERSION = "PR15-1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write, snapshot;
    private final ObjectMapper json;
    private final FinancialImportService importer;
    private final Clock clock;
    private final BankMatcher bank;

    public ReconciliationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            FinancialImportService importer,
            BalanceRebuildService rebuild,
            BankMatcher bank,
            Clock clock) {
        this.jdbc = jdbc;
        this.json = mapper.copy().findAndRegisterModules();
        this.importer = importer;
        this.bank = bank;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        snapshot = new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public UUID createDay(ProviderScope scope, String currency, LocalDate day, ZoneId zone) {
        var window = ReconciliationWindows.day(day, zone);
        return create(scope, currency, window.from(), window.until(), zone, day, "OVERLAP");
    }

    public UUID createBackfill(ProviderScope scope, String currency, Instant from, Instant until, ZoneId zone) {
        new ReconciliationWindows.Window(from, until);
        return create(scope, currency, from, until, zone, from.atZone(zone).toLocalDate(), "BACKFILL");
    }

    private UUID create(
            ProviderScope scope,
            String currency,
            Instant from,
            Instant until,
            ZoneId zone,
            LocalDate day,
            String purpose) {
        if (scope == null || scope.livemode() || currency == null || !currency.matches("[A-Z]{3}") || zone == null)
            throw new IllegalArgumentException("Explicit active test scope and uppercase currency required");
        return write.execute(status -> {
            Long active = jdbc.queryForObject(
                    """
                SELECT count(*) FROM platform.provider_account p JOIN platform.environment_config e ON e.id=p.environment_id
                JOIN platform.organization o ON o.id=p.organization_id JOIN platform.org_currency_enabled c ON c.organization_id=o.id AND c.environment_id=e.id
                WHERE p.id=? AND p.organization_id=? AND p.environment_id=? AND NOT p.livemode
                AND p.status='ACTIVE' AND e.status='ACTIVE' AND o.status='ACTIVE' AND c.status='ACTIVE' AND c.currency=?
                """,
                    Long.class,
                    scope.providerAccountId(),
                    scope.organizationId(),
                    scope.environmentId(),
                    currency);
            if (active != 1) throw new IllegalArgumentException("Scope or currency is not active");
            UUID window = importer.createWindow(scope, from, until, purpose), run = UUID.randomUUID();
            jdbc.update(
                    """
                INSERT INTO platform.reconciliation_run(id,organization_id,environment_id,provider_account_id,livemode,currency,business_date,business_zone,from_at,until_at,import_window_id,rule_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    run,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    currency,
                    day,
                    zone.getId(),
                    ts(from),
                    ts(until),
                    window,
                    RULE_VERSION);
            jdbc.update(
                    "INSERT INTO platform.reconciliation_cursor(run_id,organization_id,environment_id,provider_account_id,livemode,currency) VALUES (?,?,?,?,?,?)",
                    run,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    currency);
            return run;
        });
    }

    public void advance(UUID runId, int pages) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider reads require no local transaction");
        var run = run(runId);
        if (run.get("finished_at") != null) throw new IllegalArgumentException("Create a new run for later evidence");
        importer.runWindow((UUID) run.get("import_window_id"), pages);
        write.executeWithoutResult(status -> {
            jdbc.update("""
                UPDATE platform.reconciliation_cursor r SET next_cursor=c.next_cursor,api_version=c.api_version,import_version=c.version,
                accepted_pages=(SELECT count(*) FROM platform.import_page p WHERE p.window_id=c.window_id),updated_at=now()
                FROM platform.import_cursor c WHERE r.run_id=? AND c.window_id=?
                """, runId, run.get("import_window_id"));
        });
    }

    public ReconciliationReport finish(UUID runId, IndependentBalanceEvidence independent) {
        return snapshot.execute(status -> {
            var run = one("SELECT * FROM platform.reconciliation_run WHERE id=? FOR UPDATE", runId);
            if (run == null) throw new IllegalArgumentException("Unknown reconciliation run");
            if (run.get("finished_at") != null) return query(scope(run), runId);
            String snap = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
            var evaluation = new ReconciliationChecks(jdbc, json, clock, bank, run, snap).evaluate(independent);
            if (independent != null)
                jdbc.update(
                        """
                INSERT INTO platform.reconciliation_evidence(run_id,organization_id,environment_id,provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """,
                        runId,
                        run.get("organization_id"),
                        run.get("environment_id"),
                        run.get("provider_account_id"),
                        run.get("livemode"),
                        run.get("currency"),
                        independent.kind().name(),
                        independent.source(),
                        independent.version(),
                        independent.digest(),
                        encode(independent));
            for (var check : evaluation.checks())
                jdbc.update(
                        """
                INSERT INTO platform.reconciliation_check(run_id,check_number,name,organization_id,environment_id,provider_account_id,livemode,currency,outcome,residual,reason,owner,input_versions,snapshot,completeness)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb)
                """,
                        runId,
                        check.number(),
                        check.name(),
                        run.get("organization_id"),
                        run.get("environment_id"),
                        run.get("provider_account_id"),
                        run.get("livemode"),
                        run.get("currency"),
                        check.outcome(),
                        new java.math.BigDecimal(check.residual()),
                        check.reason(),
                        check.owner(),
                        encode(evaluation.versions()),
                        snap,
                        encode(check.completeness()));
            for (var failure : evaluation.exceptions()) {
                var inputs = new LinkedHashMap<>(evaluation.versions());
                inputs.putAll(failure.versions());
                jdbc.update(
                        """
                    INSERT INTO platform.reconciliation_exception(run_id,organization_id,environment_id,provider_account_id,livemode,currency,reason,owner,source_ref,residual,input_versions,evidence)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """,
                        runId,
                        run.get("organization_id"),
                        run.get("environment_id"),
                        run.get("provider_account_id"),
                        run.get("livemode"),
                        run.get("currency"),
                        failure.reason(),
                        failure.owner(),
                        failure.source(),
                        new java.math.BigDecimal(failure.residual()),
                        encode(inputs),
                        encode(failure.evidence()));
            }
            jdbc.update(
                    "UPDATE platform.reconciliation_run SET state=?,snapshot=?,input_versions=?::jsonb,ledger_cutoff=transaction_timestamp(),finished_at=? WHERE id=?",
                    evaluation.complete() ? "COMPLETE" : "INCOMPLETE",
                    snap,
                    encode(evaluation.versions()),
                    ts(clock.instant()),
                    runId);
            return query(scope(run), runId);
        });
    }

    public ReconciliationReport query(ProviderScope scope, UUID runId) {
        var row = run(runId);
        if (!scope(row).equals(scope)) throw new IllegalArgumentException("Reconciliation run outside scope");
        var checks = jdbc
                .queryForList("SELECT * FROM platform.reconciliation_check WHERE run_id=? ORDER BY check_number", runId)
                .stream()
                .map(c -> new ReconciliationReport.Check(
                        ((Number) c.get("check_number")).intValue(),
                        c.get("name").toString(),
                        c.get("outcome").toString(),
                        new BigInteger(c.get("residual").toString()),
                        c.get("reason").toString(),
                        c.get("owner").toString(),
                        decode(c.get("input_versions")),
                        c.get("snapshot").toString(),
                        decode(c.get("completeness"))))
                .toList();
        return new ReconciliationReport(
                runId,
                scope,
                row.get("currency").toString(),
                instant(row, "from_at"),
                instant(row, "until_at"),
                (UUID) row.get("import_window_id"),
                row.get("state").toString(),
                row.get("rule_version").toString(),
                checks);
    }

    Map<String, Object> run(UUID id) {
        var row = one("SELECT * FROM platform.reconciliation_run WHERE id=?", id);
        if (row == null) throw new IllegalArgumentException("Unknown reconciliation run");
        return row;
    }

    Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid reconciliation evidence", failure);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> decode(Object value) {
        try {
            return json.readValue(value.toString(), Map.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid stored reconciliation evidence", failure);
        }
    }

    static ProviderScope scope(Map<String, Object> row) {
        return new ProviderScope(
                (UUID) row.get("organization_id"),
                (UUID) row.get("environment_id"),
                (UUID) row.get("provider_account_id"),
                (Boolean) row.get("livemode"));
    }

    static Instant instant(Map<String, Object> row, String field) {
        return ((Timestamp) row.get(field)).toInstant();
    }

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
