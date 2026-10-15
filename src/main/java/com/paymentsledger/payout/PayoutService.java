package com.paymentsledger.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Provider observations describe state; verified BTs alone move provider money. No external I/O. */
public final class PayoutService {
    private static final Set<String> STATES = Set.of("pending", "in_transit", "paid", "failed", "canceled");
    private static final Set<String> TERMINAL = Set.of("paid", "failed", "canceled");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final LedgerPostingService ledger;

    public PayoutService(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, LedgerPostingService ledger) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.mapper = mapper.copy().findAndRegisterModules();
        this.ledger = ledger;
    }

    public Map<String, Object> lookup(ProviderScope scope, String payoutId) {
        return find(scope, payoutId, false);
    }
    /** Caller must begin its transaction before obtaining rank 60; returns null for an unknown source. */
    public Map<String, Object> lock(ProviderScope scope, String payoutId) {
        requireTransaction();
        return find(scope, payoutId, true);
    }

    public UUID accept(ProviderScope scope, PayoutFact fact, ProviderEvidence evidence) {
        Objects.requireNonNull(evidence);
        return tx.execute(status -> {
            trusted(scope, fact.currency());
            jdbc.update(
                    "INSERT INTO platform.payout(organization_id,environment_id,provider_account_id,livemode,provider_payout_id,currency,amount_minor,automatic,arrival_date,provider_status,failure_code,last_observed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(organization_id,environment_id,provider_account_id,livemode,provider_payout_id) DO NOTHING",
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    scope.livemode(),
                    fact.payoutId(),
                    currency(fact.currency()),
                    fact.amountMinor(),
                    fact.automatic(),
                    stamp(fact.arrivalDate()),
                    fact.status(),
                    fact.failureCode(),
                    stamp(evidence.requestedAt()));
            var p = lock(scope, fact.payoutId());
            UUID id = uuid(p, "id");
            jdbc.update(
                    "INSERT INTO platform.payout_observation(payout_id,fact,evidence) VALUES (?,?::jsonb,?::jsonb)",
                    id,
                    mapper.valueToTree(fact).toString(),
                    safeEvidence(evidence).toString());
            boolean identity = !p.get("currency").equals(currency(fact.currency()))
                    || number(p, "amount_minor") != fact.amountMinor()
                    || !p.get("automatic").equals(fact.automatic());
            if (identity) conflict(id, "PAYOUT_IDENTITY_CONFLICT", mapper.valueToTree(fact));
            else {
                link(id, fact.balanceTransactionId(), "OUT");
                link(id, fact.failureBalanceTransactionId(), "RETURN");
            }
            boolean stale = evidence.requestedAt().isBefore(((Timestamp) p.get("last_observed_at")).toInstant());
            String old = Objects.toString(p.get("provider_status"), ""), next = Objects.toString(fact.status(), "");
            boolean known = STATES.contains(next), terminal = TERMINAL.contains(old) && !old.equals(next);
            if (!stale && !known) conflict(id, "UNKNOWN_PAYOUT_STATUS", mapper.valueToTree(fact));
            if (!stale && terminal) conflict(id, "PAYOUT_TERMINAL_STATE_CONFLICT", mapper.valueToTree(fact));
            if (!stale && !identity && !terminal && known)
                jdbc.update(
                        "UPDATE platform.payout SET provider_status=?,arrival_date=?,failure_code=?,last_observed_at=?,updated_at=now() WHERE id=?",
                        next,
                        stamp(fact.arrivalDate()),
                        fact.failureCode(),
                        stamp(evidence.requestedAt()),
                        id);
            return id;
        });
    }
    /** Caller already owns payout(60), then the BT(70). This method never reacquires a business lock. */
    public String postLocked(ProviderScope scope, BalanceTransactionFact fact, UUID btRowId) {
        requireTransaction();
        var b = one("SELECT * FROM platform.balance_transaction WHERE id=?", btRowId);
        if (b == null) throw new IllegalArgumentException("Unknown balance transaction");
        if (!Objects.equals(b.get("organization_id"), scope.organizationId())
                || !Objects.equals(b.get("environment_id"), scope.environmentId())
                || !Objects.equals(b.get("provider_account_id"), scope.providerAccountId())
                || !Objects.equals(b.get("livemode"), scope.livemode()))
            throw new IllegalArgumentException("Balance transaction scope mismatch");
        if ("POSTED".equals(b.get("state"))) return "POSTED";
        if (!"PENDING_EVIDENCE".equals(b.get("state"))) return b.get("state").toString();
        var p = lookup(scope, fact.sourceObjectId());
        if (p == null) return pending(btRowId, "PAYOUT_EVIDENCE_REQUIRED");
        UUID id = uuid(p, "id");
        trusted(scope, p.get("currency").toString());
        if (!STATES.contains(Objects.toString(p.get("provider_status"), "")))
            return pending(btRowId, "UNKNOWN_PAYOUT_STATUS");
        boolean out = "payout".equals(fact.type()) && fact.amountMinor() < 0;
        boolean returned = Set.of("payout_failure", "payout_cancel").contains(Objects.toString(fact.type(), ""))
                && fact.amountMinor() > 0;
        String role = out ? "OUT" : "RETURN";
        boolean invalid = (!out && !returned)
                || !"payout".equals(fact.sourceObjectType())
                || !"PAYMENTS".equals(fact.balanceDomain())
                || !p.get("currency").equals(currency(fact.currency()))
                || !Objects.equals(b.get("bt_id"), fact.btId())
                || number(b, "amount_minor") != fact.amountMinor()
                || number(b, "fee_minor") != fact.feeMinor()
                || number(b, "net_minor") != fact.netMinor()
                || !Objects.equals(b.get("currency"), currency(fact.currency()))
                || !Objects.equals(b.get("source_object_id"), fact.sourceObjectId())
                || !Objects.equals(b.get("source_object_type"), fact.sourceObjectType())
                || !Objects.equals(b.get("business_type"), fact.type())
                || !Objects.equals(b.get("balance_domain"), fact.balanceDomain());
        if (invalid) {
            conflict(id, "PAYOUT_BT_FACT_CONFLICT", mapper.valueToTree(fact));
            return quarantine(btRowId, "PAYOUT_BT_FACT_CONFLICT");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.payout_bt_link WHERE payout_id=? AND bt_id=? AND role=?)",
                Boolean.class,
                id,
                fact.btId(),
                role))) {
            conflict(id, "PAYOUT_BT_LINK_CONFLICT", mapper.valueToTree(fact));
            return quarantine(btRowId, "PAYOUT_BT_LINK_CONFLICT");
        }
        long principal = out ? Math.negateExact(fact.amountMinor()) : fact.amountMinor();
        if (out && (number(p, "outgoing_minor") != 0 || principal != number(p, "amount_minor"))) {
            conflict(id, "PAYOUT_OUT_AMOUNT_CONFLICT", mapper.valueToTree(fact));
            return quarantine(btRowId, "PAYOUT_OUT_AMOUNT_CONFLICT");
        }
        if (returned && number(p, "outgoing_minor") == 0) return pending(btRowId, "PAYOUT_RETURN_BEFORE_OUT");
        jdbc.update("UPDATE platform.balance_transaction SET payout_id=? WHERE id=?", id, btRowId);
        jdbc.queryForObject("SELECT platform.ensure_payout_accounts(?)", Object.class, id);
        jdbc.queryForObject("SELECT platform.lock_payout_accounts(?)", Object.class, id);
        var accounts = accounts(p);
        var lines = new ArrayList<PostingLine>();
        long allocated = returned ? Math.min(principal, number(p, "transit_minor")) : 0;
        long suspense = returned ? Math.subtractExact(principal, allocated) : 0;
        long transit = out ? principal : -allocated;
        add(lines, accounts, "PSP_FUNDS", fact.netMinor());
        add(lines, accounts, "PSP_FEE_EXPENSE", fact.feeMinor());
        add(lines, accounts, "BANK_PAYOUT_IN_TRANSIT", transit);
        add(lines, accounts, "SUSPENSE", -suspense);
        ObjectNode immutable = mapper.valueToTree(fact);
        immutable.remove(List.of("status", "availableOn", "rawDigest"));
        immutable.put("currency", currency(fact.currency()));
        UUID journal = ledger.post(
                new LedgerScope(
                        scope.organizationId(),
                        scope.environmentId(),
                        p.get("currency").toString(),
                        scope.providerAccountId()),
                new PostingCommand(
                        "bt:" + scope.providerAccountId() + ":" + scope.livemode() + ":" + fact.btId(),
                        "L-PAYOUT",
                        "PROVIDER",
                        fact.btId(),
                        immutable,
                        lines,
                        null));
        jdbc.update(
                "UPDATE platform.balance_transaction SET state='POSTED',journal_id=?,reason=NULL,version=version+1,updated_at=now() WHERE id=?",
                journal,
                btRowId);
        jdbc.update(
                "INSERT INTO platform.payout_fact(payout_id,balance_transaction_id,journal_id,kind,principal_minor,transit_delta,returned_delta,suspense_delta,fee_minor,organization_id,environment_id,currency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                btRowId,
                journal,
                out ? "OUT" : "RETURN",
                principal,
                transit,
                allocated,
                suspense,
                fact.feeMinor(),
                scope.organizationId(),
                scope.environmentId(),
                p.get("currency"));
        jdbc.update(
                "UPDATE platform.payout SET outgoing_minor=?,transit_minor=?,returned_minor=?,suspense_minor=?,psp_fee_minor=?,updated_at=now() WHERE id=?",
                Math.addExact(number(p, "outgoing_minor"), out ? principal : 0),
                Math.addExact(number(p, "transit_minor"), transit),
                Math.addExact(number(p, "returned_minor"), allocated),
                Math.addExact(number(p, "suspense_minor"), suspense),
                Math.addExact(number(p, "psp_fee_minor"), fact.feeMinor()),
                id);
        if (suspense > 0)
            conflict(
                    id,
                    "PAYOUT_RETURN_OVERLAP",
                    mapper.createObjectNode().put("bt_id", fact.btId()).put("suspense_minor", suspense));
        return "POSTED";
    }
    /** Read-only internal history access, always constrained by the explicit provider scope. */
    public List<Map<String, Object>> observations(ProviderScope scope, UUID payoutId) {
        return scopedHistory(scope, payoutId, "payout_observation");
    }

    public List<Map<String, Object>> financialFacts(ProviderScope scope, UUID payoutId) {
        return scopedHistory(scope, payoutId, "payout_fact");
    }

    public List<Map<String, Object>> cases(ProviderScope scope, UUID payoutId) {
        return scopedHistory(scope, payoutId, "payout_case");
    }

    public List<Map<String, Object>> bankMatches(ProviderScope scope, UUID payoutId) {
        return scopedHistory(scope, payoutId, "payout_bank_match");
    }

    private List<Map<String, Object>> scopedHistory(ProviderScope scope, UUID payoutId, String table) {
        return jdbc.queryForList(
                "SELECT h.* FROM platform." + table
                        + " h JOIN platform.payout p ON p.id=h.payout_id WHERE p.organization_id=? AND p.environment_id=? AND p.provider_account_id=? AND p.livemode=? AND p.id=? ORDER BY h.created_at,h.id",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                payoutId);
    }

    private void link(UUID id, String bt, String role) {
        if (bt != null && !bt.isBlank())
            jdbc.update(
                    "INSERT INTO platform.payout_bt_link(payout_id,bt_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    id,
                    bt,
                    role);
    }

    private void conflict(UUID id, String reason, JsonNode evidence) {
        jdbc.update(
                "UPDATE platform.payout SET conflict=true,bank_match_state='CONFLICT',updated_at=now() WHERE id=?", id);
        jdbc.update(
                "INSERT INTO platform.payout_case(payout_id,reason,evidence) VALUES (?,?,?::jsonb) ON CONFLICT(payout_id,reason) DO NOTHING",
                id,
                reason,
                evidence.toString());
    }

    private String pending(UUID id, String reason) {
        return mark(id, "PENDING_EVIDENCE", reason);
    }

    private String quarantine(UUID id, String reason) {
        return mark(id, "QUARANTINED", reason);
    }

    private String mark(UUID id, String state, String reason) {
        jdbc.update(
                "UPDATE platform.balance_transaction SET state=?,reason=?,version=version+1,updated_at=now() WHERE id=?",
                state,
                reason,
                id);
        return state;
    }

    private void trusted(ProviderScope scope, String currency) {
        if (scope.livemode()
                || !Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform.provider_account p JOIN platform.environment_config e ON e.organization_id=p.organization_id AND e.id=p.environment_id JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id AND c.environment_id=p.environment_id WHERE p.organization_id=? AND p.environment_id=? AND p.id=? AND NOT p.livemode AND NOT e.livemode AND p.status='ACTIVE' AND c.currency=?)",
                        Boolean.class,
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.providerAccountId(),
                        currency(currency))))
            throw new IllegalArgumentException("Trusted enabled test provider/currency required");
    }

    private Map<String, Object> find(ProviderScope scope, String id, boolean lock) {
        return one(
                "SELECT * FROM platform.payout WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND provider_payout_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                id);
    }

    private Map<String, UUID> accounts(Map<String, Object> p) {
        var result = new HashMap<String, UUID>();
        for (var a : jdbc.queryForList(
                "SELECT id,account_purpose FROM platform.ledger_account WHERE organization_id=? AND environment_id=? AND currency=? AND source_domain='PROVIDER' AND provider_account_id=? AND balance_domain='PAYMENTS' AND account_purpose IN('PSP_FUNDS','PSP_FEE_EXPENSE','BANK_PAYOUT_IN_TRANSIT','SUSPENSE')",
                p.get("organization_id"),
                p.get("environment_id"),
                p.get("currency"),
                p.get("provider_account_id")))
            result.put(a.get("account_purpose").toString(), uuid(a, "id"));
        return result;
    }

    private static void add(List<PostingLine> lines, Map<String, UUID> accounts, String purpose, long amount) {
        if (amount != 0)
            lines.add(new PostingLine(
                    Objects.requireNonNull(accounts.get(purpose)),
                    amount > 0 ? Side.DEBIT : Side.CREDIT,
                    amount > 0 ? amount : Math.negateExact(amount)));
    }

    private ObjectNode safeEvidence(ProviderEvidence e) {
        return mapper.createObjectNode()
                .put("providerRequestId", e.providerRequestId())
                .put("requestedAt", e.requestedAt().toString())
                .put(
                        "respondedAt",
                        e.respondedAt() == null ? null : e.respondedAt().toString())
                .put("httpStatus", e.httpStatus())
                .put("providerErrorCode", e.providerErrorCode())
                .put("rawDigest", e.rawDigest())
                .put("apiVersion", e.apiVersion());
    }

    private static String currency(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static Timestamp stamp(Instant s) {
        return s == null ? null : Timestamp.from(s);
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        return (UUID) p.get(key);
    }

    private static long number(Map<String, Object> p, String key) {
        return ((Number) p.get(key)).longValue();
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Payout locking/posting requires caller transaction");
    }
}
