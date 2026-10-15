package com.paymentsledger.financialingestion;

import com.paymentsledger.provider.ProviderScope;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded, opt-in work over a trusted configured account. Every service call owns its local transaction. */
public final class FinancialIngestionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FinancialIngestionWorker.class);
    private static final int BATCH = 25;
    private static final String SCOPE =
            "organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?";
    private final JdbcTemplate jdbc;
    private final FinancialImportService importer;
    private final FinancialReplayService replay;
    private final ProviderScope scope;
    private final Clock clock;
    private final RefundSourceResolver refundSources;
    private PayoutSourceResolver payoutSources = PayoutSourceResolver.unavailable();
    private DisputeSourceResolver disputeSources = DisputeSourceResolver.unavailable();

    public FinancialIngestionWorker(
            JdbcTemplate jdbc,
            FinancialImportService importer,
            FinancialReplayService replay,
            ProviderScope scope,
            Clock clock) {
        this(jdbc, importer, replay, scope, clock, RefundSourceResolver.unavailable());
    }

    public FinancialIngestionWorker(
            JdbcTemplate jdbc,
            FinancialImportService importer,
            FinancialReplayService replay,
            ProviderScope scope,
            Clock clock,
            RefundSourceResolver refundSources) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.replay = replay;
        this.scope = scope;
        this.clock = clock;
        this.refundSources = refundSources;
    }

    public FinancialIngestionWorker(
            JdbcTemplate jdbc,
            FinancialImportService importer,
            FinancialReplayService replay,
            ProviderScope scope,
            Clock clock,
            RefundSourceResolver refundSources,
            DisputeSourceResolver disputeSources) {
        this(jdbc, importer, replay, scope, clock, refundSources);
        this.disputeSources = disputeSources;
    }

    public FinancialIngestionWorker(
            JdbcTemplate jdbc,
            FinancialImportService importer,
            FinancialReplayService replay,
            ProviderScope scope,
            Clock clock,
            RefundSourceResolver refundSources,
            DisputeSourceResolver disputeSources,
            PayoutSourceResolver payoutSources) {
        this(jdbc, importer, replay, scope, clock, refundSources, disputeSources);
        this.payoutSources = payoutSources;
    }

    @Scheduled(
            scheduler = "financialIngestionScheduler",
            fixedDelayString = "${platform.financial-ingestion.poll-ms:300000}",
            initialDelayString = "${platform.financial-ingestion.poll-ms:300000}")
    public void tick() {
        guarded("WINDOW", this::importWindow);
        guarded("AVAILABILITY", this::refreshAvailability);
        guarded("REPLAY", this::replayPending);
    }

    private Object[] scopeArgs() {
        return new Object[] {scope.organizationId(), scope.environmentId(), scope.providerAccountId(), scope.livemode()
        };
    }

    private void importWindow() {
        var zones = jdbc.queryForList(
                "SELECT business_zone FROM platform.ingestion_policy WHERE " + SCOPE, String.class, scopeArgs());
        if (zones.isEmpty()) {
            LOG.warn(
                    "financial_ingestion reason=MISSING_OPENING_POLICY providerAccountId={}",
                    scope.providerAccountId());
            return;
        }
        ZoneId zone = ZoneId.of(zones.getFirst());
        var window = ImportWindows.overlap(LocalDate.now(clock.withZone(zone)), zone);
        var args = new ArrayList<>(Arrays.asList(scopeArgs()));
        args.add(Timestamp.from(window.from()));
        args.add(Timestamp.from(window.until()));
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM platform.import_window WHERE " + SCOPE
                        + " AND created_gte=? AND created_lt=? AND purpose='OVERLAP'",
                Long.class,
                args.toArray());
        if (existing == null || existing == 0) {
            importer.createWindow(scope, window.from(), window.until(), "OVERLAP");
        }
        // A rejected version-pinned backfill stays visible but must not prevent another run from progressing.
        // Successful/rejected page attempts update updated_at, so older work rotates through this bounded batch.
        var active = jdbc.queryForList(
                "SELECT id FROM platform.import_window WHERE " + SCOPE
                        + " AND state='INCOMPLETE' ORDER BY updated_at,id LIMIT 5",
                UUID.class,
                scopeArgs());
        for (UUID id : active) guarded("WINDOW_ITEM", () -> importer.runWindow(id, BATCH / 5));
    }

    private void refreshAvailability() {
        // Last observation also advances priority after a failed provider read; unavailable IDs cannot starve later
        // work.
        var ids = jdbc.queryForList("""
                SELECT b.bt_id FROM platform.balance_transaction b
                WHERE b.organization_id=? AND b.environment_id=? AND b.provider_account_id=? AND b.livemode=?
                  AND b.provider_status='pending'
                ORDER BY coalesce((SELECT max(o.fetched_at) FROM platform.provider_observation o
                    WHERE o.organization_id=b.organization_id AND o.environment_id=b.environment_id
                      AND o.provider_account_id=b.provider_account_id AND o.livemode=b.livemode
                      AND o.observation_domain='FINANCIAL' AND o.external_object_id=b.bt_id),b.fetched_at),b.id
                LIMIT 25
                """, String.class, scopeArgs());
        for (String id : ids) guarded("AVAILABILITY_ITEM", () -> importer.refreshPending(scope, id));
    }

    private void replayPending() {
        var ids = jdbc.queryForList(
                "SELECT bt_id FROM platform.balance_transaction WHERE " + SCOPE
                        + " AND state='PENDING_EVIDENCE' ORDER BY updated_at,id LIMIT 25",
                String.class,
                scopeArgs());
        for (String id : ids)
            guarded("REPLAY_ITEM", () -> {
                // Persist unresolved replay priority before a possibly unavailable source query.
                // A missing refund never monopolizes the bounded queue, and all network I/O is outside replay's
                // transaction.
                String state = replay.replay(scope, id);
                if ("PENDING_EVIDENCE".equals(state)) {
                    var sources = jdbc.queryForList(
                            "SELECT source_object_id FROM platform.balance_transaction WHERE " + SCOPE
                                    + " AND bt_id=? AND source_object_type='refund' AND source_object_id IS NOT NULL",
                            String.class,
                            scope.organizationId(),
                            scope.environmentId(),
                            scope.providerAccountId(),
                            scope.livemode(),
                            id);
                    if (!sources.isEmpty()) {
                        refundSources.resolve(scope, sources.getFirst());
                        replay.replay(scope, id);
                    }
                    var disputes = jdbc.queryForList(
                            "SELECT source_object_id FROM platform.balance_transaction WHERE " + SCOPE
                                    + " AND bt_id=? AND source_object_type='dispute' AND source_object_id IS NOT NULL",
                            String.class,
                            scope.organizationId(),
                            scope.environmentId(),
                            scope.providerAccountId(),
                            scope.livemode(),
                            id);
                    if (!disputes.isEmpty()) {
                        disputeSources.resolve(scope, disputes.getFirst());
                        replay.replay(scope, id);
                    }
                    var payouts = jdbc.queryForList(
                            "SELECT source_object_id FROM platform.balance_transaction WHERE " + SCOPE
                                    + " AND bt_id=? AND source_object_type='payout' AND source_object_id IS NOT NULL",
                            String.class,
                            scope.organizationId(),
                            scope.environmentId(),
                            scope.providerAccountId(),
                            scope.livemode(),
                            id);
                    if (!payouts.isEmpty()) {
                        payoutSources.resolve(scope, payouts.getFirst());
                        replay.replay(scope, id);
                    }
                }
            });
    }

    private void guarded(String stage, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            // Never log provider response bodies, credentials, or SQL exception text.
            LOG.warn(
                    "financial_ingestion stage={} reason=RETRY_REQUIRED providerAccountId={}",
                    stage,
                    scope.providerAccountId());
        }
    }
}
