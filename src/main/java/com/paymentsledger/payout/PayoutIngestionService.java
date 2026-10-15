package com.paymentsledger.payout;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Read-only external pages; durable scoped cursor and raw page evidence. No membership money posting. */
@Service
public class PayoutIngestionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PayoutPort provider;
    private final BalanceTransactionPort btProvider;
    private final PayoutService payouts;

    @org.springframework.beans.factory.annotation.Autowired
    public PayoutIngestionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            ObjectProvider<PayoutPort> provider,
            ObjectProvider<BalanceTransactionPort> btProvider,
            PayoutService payouts) {
        this(jdbc, manager, json, provider.getIfAvailable(), btProvider.getIfAvailable(), payouts);
    }

    public PayoutIngestionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            PayoutPort provider,
            BalanceTransactionPort btProvider,
            PayoutService payouts) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.json = json.copy().findAndRegisterModules();
        this.provider = provider;
        this.btProvider = btProvider;
        this.payouts = payouts;
    }

    public UUID createWindow(ProviderScope scope, Instant from, Instant until) {
        return create(scope, null, from, until);
    }

    public UUID createMembershipWindow(ProviderScope scope, UUID payout, Instant from, Instant until) {
        var row = scopedPayout(scope, payout);
        if (!Boolean.TRUE.equals(row.get("automatic"))) return null;
        return create(scope, payout, from, until);
    }

    private UUID create(ProviderScope scope, UUID payout, Instant from, Instant until) {
        validateScope(scope);
        if (from == null || until == null || !from.isBefore(until) || until.isAfter(Instant.now()))
            throw new IllegalArgumentException("Passed half-open UTC window required");
        return tx.execute(status -> {
            UUID observation = payout == null ? null : jdbc.queryForObject("""
                            SELECT id
                            FROM platform.payout_observation
                            WHERE payout_id=?
                            ORDER BY created_at DESC,id DESC
                            LIMIT 1""", UUID.class, payout);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO platform.payout_read_run(id,organization_id,environment_id,provider_account_id,livemode,
                        kind,payout_id,source_observation_id,created_gte,created_lt)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(payout_id,source_observation_id,created_gte,created_lt)
                    DO NOTHING""",
                    id,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    payout == null ? "PAYOUTS" : "MEMBERSHIP",
                    payout,
                    observation,
                    Timestamp.from(from),
                    Timestamp.from(until));
            if (payout != null)
                return jdbc.queryForObject(
                        """
                        SELECT id
                        FROM platform.payout_read_run
                        WHERE payout_id=? AND source_observation_id=? AND created_gte=? AND created_lt=?""", UUID.class, payout, observation, Timestamp.from(from), Timestamp.from(until));
            return id;
        });
    }

    public String importPage(UUID runId) {
        outside();
        var snapshot = one("SELECT * FROM platform.payout_read_run WHERE id=?", runId);
        if (snapshot == null) throw new IllegalArgumentException("Unknown payout read run");
        if ("COMPLETE".equals(snapshot.get("state"))) return "COMPLETE";
        var scope = new ProviderScope(
                (UUID) snapshot.get("organization_id"),
                (UUID) snapshot.get("environment_id"),
                (UUID) snapshot.get("provider_account_id"),
                (boolean) snapshot.get("livemode"));
        validateScope(scope);
        Instant from = ((Timestamp) snapshot.get("created_gte")).toInstant(),
                until = ((Timestamp) snapshot.get("created_lt")).toInstant();
        String cursor = (String) snapshot.get("next_cursor");
        jdbc.update("UPDATE platform.payout_read_run SET updated_at=clock_timestamp() WHERE id=?", runId);
        boolean membership = "MEMBERSHIP".equals(snapshot.get("kind"));
        String po = membership
                ? scopedPayout(scope, (UUID) snapshot.get("payout_id"))
                        .get("provider_payout_id")
                        .toString()
                : null;
        if (membership
                && !Boolean.TRUE.equals(
                        scopedPayout(scope, (UUID) snapshot.get("payout_id")).get("automatic")))
            throw new IllegalArgumentException("Manual membership unavailable");
        if (membership ? btProvider == null : provider == null) {
            jdbc.update("""
                    UPDATE platform.payout_read_run
                    SET last_error='PROVIDER_UNAVAILABLE'
                    WHERE id=?""", runId);
            return "UNAVAILABLE";
        }
        ProviderCallResult<?> result = membership
                ? btProvider.listBalanceTransactions(
                        new ListBalanceTransactionsQuery(scope, from, until, cursor, 100, po))
                : provider.listPayouts(new PageQuery(scope, from, until, cursor, 100));
        UUID observation = recordRead(scope, runId, membership ? "LIST_PAYOUT_MEMBERSHIP" : "LIST_PAYOUTS", po, result);
        List<?> items = List.of();
        boolean more = false;
        String next = null;
        String invalid = evidenceError(result);
        if (invalid == null) {
            if (membership && result.payload() instanceof BalanceTransactionPage page) {
                items = page.items();
                more = page.hasMore();
                next = page.nextCursor();
            } else if (!membership && result.payload() instanceof Page<?> page) {
                items = page.items();
                more = page.hasMore();
                next = page.nextCursor();
            } else invalid = "MALFORMED_PAGE";
        }
        var ids = new ArrayList<String>();
        var facts = new HashMap<String, Object>();
        if (invalid == null) {
            for (Object item : items) {
                String id;
                if (membership && item instanceof BalanceTransactionFact b) {
                    id = b.btId();
                    if (b.createdAt() == null
                            || b.createdAt().isBefore(from)
                            || !b.createdAt().isBefore(until)
                            || !scopedPayout(scope, (UUID) snapshot.get("payout_id"))
                                    .get("currency")
                                    .toString()
                                    .equalsIgnoreCase(b.currency())
                            || !"PAYMENTS".equals(b.balanceDomain())
                            || "payout".equals(b.sourceObjectType())
                            || Objects.equals(po, b.sourceObjectId())) invalid = "INVALID_MEMBER_FACT";
                } else if (!membership && item instanceof PayoutFact p) id = p.payoutId();
                else {
                    invalid = "MALFORMED_ITEM";
                    break;
                }
                if (ids.contains(id) || Objects.equals(id, cursor)) invalid = "PAGE_ID_CYCLE";
                ids.add(id);
                facts.put(id, item);
            }
            if (items.size() > 100
                    || more && (ids.isEmpty() || !Objects.equals(next, ids.getLast()))
                    || !more && next != null) invalid = "MALFORMED_CONTINUATION";
            if (snapshot.get("api_version") != null
                    && !snapshot.get("api_version").equals(result.evidence().apiVersion()))
                invalid = "API_VERSION_DRIFT";
            if (next != null && jdbc.queryForObject("""
                                    SELECT count(*)
                                    FROM platform.payout_read_page
                                    WHERE run_id=? AND request_cursor=?""", Long.class, runId, next) > 0) invalid = "CURSOR_CYCLE";
        }
        if (invalid != null) {
            String error = invalid;
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE platform.payout_read_run
                    SET last_error=?,updated_at=clock_timestamp()
                    WHERE id=?""", error, runId));
            return result.classification() == ProviderCallClassification.SUCCESS ? "REJECTED" : "RETRY";
        }
        // Each canonical object commits separately, so no transaction locks multiple payout parents after BT locks.
        // Cursor is not advanced until all objects are durable. Crash/retry simply reaccepts the same immutable
        // observations.
        if (!membership)
            for (String id : ids.stream().sorted().toList())
                payouts.accept(scope, (PayoutFact) facts.get(id), result.evidence());
        final boolean terminal = !more;
        final String nextCursor = next;
        return tx.execute(status -> {
            var current = one("SELECT * FROM platform.payout_read_run WHERE id=? FOR UPDATE", runId);
            if (!Objects.equals(current.get("version"), snapshot.get("version"))
                    || !Objects.equals(current.get("next_cursor"), cursor)
                    || "COMPLETE".equals(current.get("state"))) return "STALE";
            if (membership)
                for (String id : ids) {
                    var existing = one(
                            "SELECT fact FROM platform.payout_membership WHERE payout_id=? AND bt_id=?",
                            snapshot.get("payout_id"),
                            id);
                    JsonNode fact = com.paymentsledger.financialingestion.FinancialFacts.immutable(
                            json, (BalanceTransactionFact) facts.get(id));
                    if (existing != null) {
                        try {
                            if (!json.readTree(existing.get("fact").toString()).equals(json.readTree(fact.toString())))
                                throw new IllegalArgumentException("Membership evidence conflict");
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    } else jdbc.update("""
                                INSERT INTO platform.payout_membership(payout_id,bt_id,run_id,observation_id,fact)
                                VALUES (?,?,?,?,?::jsonb)""", snapshot.get("payout_id"), id, runId, observation, fact.toString());
                }
            jdbc.update("""
                    INSERT INTO platform.payout_read_page(run_id,request_cursor,next_cursor,terminal,observation_id,
                        object_ids)
                    VALUES (?,?,?,?,?,?::text[])""", runId, Objects.toString(cursor, ""), nextCursor, terminal, observation, array(ids));
            jdbc.update("""
                    UPDATE platform.payout_read_run
                    SET
                        next_cursor=?,
                        api_version=coalesce(api_version,?),
                        state=?,
                        last_error=null,
                        version=version+1,
                        updated_at=clock_timestamp()
                    WHERE id=?""", nextCursor, result.evidence().apiVersion(), terminal ? "COMPLETE" : "INCOMPLETE", runId);
            return terminal ? "COMPLETE" : "ACCEPTED";
        });
    }

    public UUID recordRead(ProviderScope scope, UUID run, String operation, String id, ProviderCallResult<?> result) {
        validateScope(scope);
        return tx.execute(status -> {
            UUID observation = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO platform.payout_read_observation(id,organization_id,environment_id,provider_account_id,
                        livemode,run_id,operation,object_id,classification,payload,evidence)
                    VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                    observation,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    run,
                    operation,
                    id,
                    result.classification().name(),
                    result.payload() == null
                            ? null
                            : json.valueToTree(result.payload()).toString(),
                    json.valueToTree(result.evidence()).toString());
            return observation;
        });
    }

    static String evidenceError(ProviderCallResult<?> result) {
        if (result == null || result.classification() != ProviderCallClassification.SUCCESS || result.payload() == null)
            return "INCOMPLETE_QUERY";
        var e = result.evidence();
        return e.apiVersion() == null
                        || e.apiVersion().isBlank()
                        || e.respondedAt() == null
                        || e.respondedAt().isBefore(e.requestedAt())
                        || e.httpStatus() == null
                        || e.httpStatus() < 200
                        || e.httpStatus() >= 300
                ? "UNVERIFIED_QUERY_EVIDENCE"
                : null;
    }

    void validateScope(ProviderScope scope) {
        if (scope.livemode()
                || jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM platform.provider_account p
                                JOIN platform.environment_config e ON e.id=p.environment_id AND e.organization_id=p.organization_id
                                JOIN platform.organization o ON o.id=p.organization_id
                                WHERE p.id=?
                                    AND p.organization_id=?
                                    AND p.environment_id=?
                                    AND NOT p.livemode
                                    AND NOT e.livemode
                                    AND p.status='ACTIVE'
                                    AND e.status='ACTIVE'
                                    AND o.status='ACTIVE'""",
                                Long.class,
                                scope.providerAccountId(),
                                scope.organizationId(),
                                scope.environmentId())
                        != 1) throw new IllegalArgumentException("Trusted test scope required");
    }

    Map<String, Object> scopedPayout(ProviderScope scope, UUID id) {
        var p = one(
                """
                SELECT *
                FROM platform.payout
                WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?""", id, scope.organizationId(), scope.environmentId(), scope.providerAccountId(), scope.livemode());
        if (p == null) throw new IllegalArgumentException("Payout not in scope");
        return p;
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String array(List<String> ids) {
        return "{"
                + String.join(
                        ",",
                        ids.stream()
                                .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                                .toList())
                + "}";
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider reads require an external transaction boundary");
    }
}
