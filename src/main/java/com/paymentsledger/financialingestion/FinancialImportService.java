package com.paymentsledger.financialingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled internal API. Fetches outside transactions; evidence, accepted page and cursor commit together. */
public class FinancialImportService {
    private RevenueShareService shares = RevenueShareService.disabled();
    private final JdbcTemplate jdbc;
    private final FinancialTransactions transactions;
    private final ObjectMapper mapper;
    private final BalanceTransactionPort provider;
    private final Clock clock;

    public FinancialImportService(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper mapper,
            BalanceTransactionPort provider,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new FinancialTransactions(tx);
        this.mapper = mapper.copy().findAndRegisterModules();
        this.provider = provider;
        this.clock = clock;
    }

    public FinancialImportService(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            BalanceTransactionPort provider,
            Clock clock,
            RevenueShareService shares) {
        this(jdbc, tx, json, provider, clock);
        this.shares = shares;
    }

    public UUID createWindow(ProviderScope scope, Instant from, Instant until, String purpose) {
        new ImportWindows.Window(from, until);
        if (!Set.of("OVERLAP", "BACKFILL").contains(purpose))
            throw new IllegalArgumentException("Unsupported import purpose");
        if (until.isAfter(clock.instant()))
            throw new IllegalArgumentException("Import high water must already have passed");
        return transactions.run(() -> {
            policy(scope);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO platform.import_window(id,organization_id,environment_id,provider_account_id,livemode,
                        created_gte,created_lt,purpose)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    id,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    ts(from),
                    ts(until),
                    purpose);
            jdbc.update("INSERT INTO platform.import_cursor(window_id) VALUES (?)", id);
            return id;
        });
    }

    public void runWindow(UUID windowId, int maxPages) {
        if (maxPages < 1) throw new IllegalArgumentException("Positive page budget required");
        for (int i = 0; i < maxPages; i++) if (!"ACCEPTED".equals(importPage(windowId))) return;
    }

    public String importPage(UUID windowId) {
        outsideTransaction();
        var snapshot = one("""
                SELECT w.*,c.next_cursor,c.api_version,c.version AS cursor_version
                FROM platform.import_window w
                JOIN platform.import_cursor c ON c.window_id=w.id
                WHERE w.id=?""", windowId);
        if (snapshot == null) throw new IllegalArgumentException("Unknown import window");
        if ("COMPLETE".equals(snapshot.get("state"))) return "COMPLETE";
        ProviderScope scope = scope(snapshot);
        var query = new ListBalanceTransactionsQuery(
                scope,
                instant(snapshot, "created_gte"),
                instant(snapshot, "created_lt"),
                (String) snapshot.get("next_cursor"),
                100,
                null);
        var result = provider.listBalanceTransactions(query);
        return transactions.run(() -> {
            var current = one("""
                    SELECT w.*,c.next_cursor,c.api_version,c.version AS cursor_version
                    FROM platform.import_window w
                    JOIN platform.import_cursor c ON c.window_id=w.id
                    WHERE w.id=?
                    FOR UPDATE OF w,c""", windowId);
            UUID observation = observe(scope, windowId, "LIST_BALANCE_TRANSACTIONS", null, result);
            if (!Objects.equals(current.get("cursor_version"), snapshot.get("cursor_version"))
                    || !Objects.equals(current.get("next_cursor"), snapshot.get("next_cursor"))
                    || "COMPLETE".equals(current.get("state"))) return "STALE";
            String invalid = validate(query, current, result);
            if (invalid != null) {
                quarantine(scope, observation, null, invalid);
                jdbc.update("""
                        UPDATE platform.import_window
                        SET last_error=?,updated_at=now(),version=version+1
                        WHERE id=?""", invalid, windowId);
                return result.classification() == ProviderCallClassification.SUCCESS ? "REJECTED" : "RETRY";
            }
            var shareLocks =
                    shares.lockAll(affectedPayments(scope, result.payload().items()));
            policy(scope); // protects policy freezing against concurrent controlled configuration changes
            var page = result.payload();
            for (var fact : page.items().stream()
                    .sorted(Comparator.comparing(BalanceTransactionFact::btId))
                    .toList()) accept(scope, fact, result.evidence(), observation);
            verifyOwners(scope, page.items(), shareLocks);
            shares.reconcileAll(shareLocks, "bt.import:" + windowId);
            jdbc.update(
                    """
                    INSERT INTO platform.import_page(window_id,request_cursor,next_cursor,terminal,observation_id,
                        bt_ids)
                    VALUES (?,?,?,?,?,?::text[])""",
                    windowId,
                    Objects.toString(query.startingAfterCursor(), ""),
                    page.nextCursor(),
                    !page.hasMore(),
                    observation,
                    textArray(page.items().stream()
                            .map(BalanceTransactionFact::btId)
                            .toList()));
            jdbc.update("""
                    UPDATE platform.import_cursor
                    SET next_cursor=?,api_version=coalesce(api_version,?),version=version+1,updated_at=now()
                    WHERE window_id=?""", page.nextCursor(), result.evidence().apiVersion(), windowId);
            jdbc.update(
                    """
                    UPDATE platform.import_window
                    SET state=?,completed_at=?,last_error=null,updated_at=now(),version=version+1
                    WHERE id=?""",
                    page.hasMore() ? "INCOMPLETE" : "COMPLETE",
                    page.hasMore() ? null : ts(clock.instant()),
                    windowId);
            return page.hasMore() ? "ACCEPTED" : "COMPLETE";
        });
    }

    public String refreshPending(ProviderScope scope, String btId) {
        outsideTransaction();
        var existing = bt(scope, btId, false);
        if (existing == null) throw new IllegalArgumentException("Unknown balance transaction");
        var result = provider.getBalanceTransaction(scope, btId);
        return transactions.run(() -> {
            UUID observation = observe(scope, null, "REFRESH_PENDING_BALANCE_TRANSACTION", btId, result);
            String error = null;
            if (result.classification() != ProviderCallClassification.SUCCESS || result.payload() == null)
                error = "INCOMPLETE_REFRESH";
            else if (!btId.equals(result.payload().btId())) error = "REFRESH_ID_MISMATCH";
            else if (!Objects.equals(
                    existing.get("api_version"), result.evidence().apiVersion())) error = "API_VERSION_DRIFT";
            else if (result.payload().createdAt() == null) error = "MISSING_CREATED_TIME";
            else if (!supportedStatus(result.payload())) error = "UNSUPPORTED_BT_STATUS";
            if (error != null) {
                quarantine(scope, observation, (UUID) existing.get("id"), error);
                return "REJECTED";
            }
            var shareLocks = shares.lockAll(affectedPayments(scope, List.of(result.payload())));
            policy(scope);
            accept(scope, result.payload(), result.evidence(), observation);
            verifyOwners(scope, List.of(result.payload()), shareLocks);
            shares.reconcileAll(shareLocks, "bt.refresh:" + btId);
            return "ACCEPTED";
        });
    }

    private void verifyOwners(
            ProviderScope scope, List<BalanceTransactionFact> facts, List<RevenueShareService.Locked> locks) {
        if (shares.enabled()
                && !new HashSet<>(locks.stream()
                                .map(RevenueShareService.Locked::paymentId)
                                .toList())
                        .containsAll(affectedPayments(scope, facts)))
            throw new FinancialTransactions.AssociationChanged();
    }
    /** Resolve only canonical stored source ownership before acquiring BT row locks. */
    private Set<UUID> affectedPayments(ProviderScope scope, List<BalanceTransactionFact> facts) {
        if (!shares.enabled()) return Set.of();
        var ids = new HashSet<UUID>();
        for (var fact : facts) {
            var stored = bt(scope, fact.btId(), false);
            if (stored != null && stored.get("payment_id") != null) ids.add((UUID) stored.get("payment_id"));
            String source = stored == null ? fact.sourceObjectId() : (String) stored.get("source_object_id");
            String type = stored == null ? fact.sourceObjectType() : (String) stored.get("source_object_type");
            if (source == null) continue;
            if ("charge".equals(type))
                ids.addAll(jdbc.queryForList(
                        """
                        SELECT payment_id
                        FROM platform.payment_attempt
                        WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_object_id=?""",
                        UUID.class,
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        source));
            if ("refund".equals(type))
                ids.addAll(jdbc.queryForList(
                        """
                        SELECT payment_id
                        FROM platform.refund
                        WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_refund_id=?""",
                        UUID.class,
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        source));
            if ("dispute".equals(type))
                ids.addAll(jdbc.queryForList(
                        """
                        SELECT payment_id
                        FROM platform.dispute
                        WHERE organization_id=?
                            AND environment_id=?
                            AND provider_account_id=?
                            AND provider_dispute_id=?
                            AND payment_id IS NOT NULL""",
                        UUID.class,
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        source));
        }
        return ids;
    }

    private String validate(
            ListBalanceTransactionsQuery query,
            Map<String, Object> current,
            ProviderCallResult<BalanceTransactionPage> result) {
        if (result.classification() != ProviderCallClassification.SUCCESS || result.payload() == null)
            return "INCOMPLETE_PROVIDER_PAGE";
        String version = result.evidence().apiVersion();
        if (version == null || version.isBlank()) return "MISSING_API_VERSION";
        if (current.get("api_version") != null && !version.equals(current.get("api_version")))
            return "API_VERSION_DRIFT";
        var page = result.payload();
        if (page.items().size() > query.limit()) return "PAGE_LIMIT_EXCEEDED";
        if (page.hasMore()
                && (page.items().isEmpty() || !page.items().getLast().btId().equals(page.nextCursor())))
            return "CURSOR_MISMATCH";
        if (!page.hasMore() && page.nextCursor() != null) return "TERMINAL_CURSOR_MISMATCH";
        if (page.nextCursor() != null
                && (page.nextCursor().equals(query.startingAfterCursor())
                        || jdbc.queryForObject("""
                                        SELECT count(*)
                                        FROM platform.import_page
                                        WHERE window_id=? AND request_cursor=?""", Long.class, current.get("id"), page.nextCursor()) > 0))
            return "CURSOR_CYCLE";
        Set<String> ids = new HashSet<>();
        for (var fact : page.items()) {
            if (!supportedStatus(fact)) return "UNSUPPORTED_BT_STATUS";
            if (!ids.add(fact.btId())
                    || jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.import_page WHERE window_id=? AND ?=ANY(bt_ids)",
                                    Long.class,
                                    current.get("id"),
                                    fact.btId())
                            > 0) return "REPEATED_BT_ID";
            if (fact.createdAt() == null
                    || fact.createdAt().isBefore(query.createdGte())
                    || !fact.createdAt().isBefore(query.createdLt())) return "OUTSIDE_CREATED_WINDOW";
        }
        return null;
    }

    private void accept(ProviderScope scope, BalanceTransactionFact fact, ProviderEvidence evidence, UUID observation) {
        String immutable = FinancialFacts.immutable(mapper, fact).toString();
        jdbc.update(
                """
                INSERT INTO platform.balance_transaction(organization_id,environment_id,provider_account_id,livemode,bt_id,amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,business_type,balance_domain,provider_created_at,available_on,provider_status,raw_digest,api_version,fetched_at,immutable_fact,fact,first_observation_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(organization_id,provider_account_id,livemode,bt_id) DO NOTHING
                """,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                fact.btId(),
                fact.amountMinor(),
                fact.feeMinor(),
                fact.netMinor(),
                fact.currency().toUpperCase(Locale.ROOT),
                fact.sourceObjectId(),
                fact.sourceObjectType(),
                fact.type(),
                fact.balanceDomain(),
                ts(fact.createdAt()),
                ts(fact.availableOn()),
                fact.status(),
                fact.rawDigest() == null ? evidence.rawDigest() : fact.rawDigest(),
                evidence.apiVersion(),
                ts(clock.instant()),
                immutable,
                encode(fact),
                observation);
        var stored = bt(scope, fact.btId(), true);
        if (!tree(stored.get("immutable_fact").toString()).equals(tree(immutable))) {
            jdbc.update(
                    """
                    INSERT INTO platform.bt_evidence_conflict(organization_id,environment_id,provider_account_id,
                        livemode,balance_transaction_id,observation_id,existing_fact,incoming_fact)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    stored.get("id"),
                    observation,
                    stored.get("immutable_fact").toString(),
                    immutable);
            return;
        }
        jdbc.update("""
                UPDATE platform.balance_transaction
                SET
                    provider_status=CASE WHEN provider_status='available' THEN provider_status ELSE ? END,
                    available_on=coalesce(?,available_on),
                    fetched_at=greatest(fetched_at,?),
                    version=version+1,
                    updated_at=now()
                WHERE id=?""", fact.status(), ts(fact.availableOn()), ts(clock.instant()), stored.get("id"));
    }

    private UUID observe(ProviderScope scope, UUID window, String call, String object, ProviderCallResult<?> result) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO platform.provider_observation(id,organization_id,environment_id,provider_account_id,
                    observation_domain,livemode,import_window_id,call_type,classification,external_object_id,
                    evidence,fact,fetched_at)
                VALUES (?,?,?,?,'FINANCIAL',?,?,?,?,?,?::jsonb,?::jsonb,?)""",
                id,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                window,
                call,
                result.classification().name(),
                object,
                encode(result.evidence()),
                result.payload() == null ? null : encode(result.payload()),
                ts(clock.instant()));
        return id;
    }

    private void quarantine(ProviderScope scope, UUID observation, UUID bt, String reason) {
        jdbc.update(
                """
                INSERT INTO platform.quarantine_observation(organization_id,environment_id,provider_account_id,
                    livemode,observation_id,balance_transaction_id,reason)
                VALUES (?,?,?,?,?,?,?)""",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                observation,
                bt,
                reason);
    }

    private void policy(ProviderScope scope) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT platform.lock_ingestion_policy(?,?,?,?)",
                Boolean.class,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode()))) throw new IllegalArgumentException("Explicit opening policy required");
    }

    private Map<String, Object> bt(ProviderScope scope, String btId, boolean lock) {
        return one(
                """
                SELECT *
                FROM platform.balance_transaction
                WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND bt_id=?""" + (lock ? " FOR UPDATE" : ""),
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                btId);
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid provider evidence", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid durable evidence", e);
        }
    }

    private static String textArray(List<String> values) {
        return "{"
                + String.join(
                        ",",
                        values.stream()
                                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                                .toList())
                + "}";
    }

    private static ProviderScope scope(Map<String, Object> row) {
        return new ProviderScope(
                (UUID) row.get("organization_id"),
                (UUID) row.get("environment_id"),
                (UUID) row.get("provider_account_id"),
                (Boolean) row.get("livemode"));
    }

    private static Instant instant(Map<String, Object> row, String field) {
        return ((Timestamp) row.get(field)).toInstant();
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static boolean supportedStatus(BalanceTransactionFact fact) {
        return "pending".equals(fact.status()) || "available".equals(fact.status());
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider reads require no local transaction");
    }
}
