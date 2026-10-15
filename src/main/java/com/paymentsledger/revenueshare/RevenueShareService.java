package com.paymentsledger.revenueshare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All hooks join the caller transaction. No network I/O; no locks acquired by reconcile(Locked). */
public final class RevenueShareService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RevenueShareService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final LedgerPostingService ledger;
    private final boolean enabled;
    private final ShareEligibilityPolicy eligibility;

    public RevenueShareService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            LedgerPostingService ledger,
            boolean enabled) {
        this(jdbc, manager, mapper, ledger, enabled, ShareEligibilityPolicy.allow());
    }

    public RevenueShareService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            LedgerPostingService ledger,
            boolean enabled,
            ShareEligibilityPolicy eligibility) {
        this.jdbc = jdbc;
        this.tx = manager == null ? null : new TransactionTemplate(manager);
        this.mapper = mapper;
        this.ledger = ledger;
        this.enabled = enabled;
        this.eligibility = Objects.requireNonNull(eligibility);
    }

    public static RevenueShareService disabled() {
        return new RevenueShareService(null, null, null, null, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public static SharePolicy validatePolicy(JsonNode snapshot) {
        return SharePolicy.parse(snapshot);
    }

    public static final class Locked {
        private final UUID paymentId;
        private final Object transaction;
        private final RevenueShareService owner;

        private Locked(UUID paymentId, Object transaction, RevenueShareService owner) {
            this.paymentId = paymentId;
            this.transaction = transaction;
            this.owner = owner;
        }

        public UUID paymentId() {
            return paymentId;
        }
    }

    public Locked lock(UUID paymentId) {
        return lockAll(List.of(paymentId)).getFirst();
    }
    /** Rank-wide phases: ALL payment(20), ALL controls(30), ALL allocations(50), then ledger. */
    public List<Locked> lockAll(Collection<UUID> paymentIds) {
        if (!enabled)
            return paymentIds.stream()
                    .distinct()
                    .map(id -> new Locked(id, null, this))
                    .toList();
        requireTransaction();
        var ids = paymentIds.stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        var payments = new ArrayList<Map<String, Object>>();
        for (UUID id : ids) {
            var p = one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", id);
            if (p == null) throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found");
            payments.add(p);
        }
        for (UUID id : ids)
            jdbc.queryForList("SELECT payment_id FROM platform.refund_control WHERE payment_id=? FOR UPDATE", id);
        if (!ids.isEmpty()) {
            String parameters = String.join(",", Collections.nCopies(ids.size(), "?"));
            jdbc.queryForList(
                    "SELECT id FROM platform.dispute WHERE payment_id IN (" + parameters + ") ORDER BY id FOR UPDATE",
                    ids.toArray());
        }
        for (var p : payments) {
            UUID id = uuid(p, "id");
            var order = one(
                    "SELECT share_policy_snapshot FROM platform.business_order WHERE id=?", p.get("business_order_id"));
            var policy = validatePolicy(json(order.get("share_policy_snapshot")));
            for (var partner : policy.partners())
                jdbc.update(
                        """
                        INSERT INTO platform.share_allocation(organization_id,environment_id,application_id,
                            provider_account_id,currency,payment_id,partner_ref,policy_version,rate_bps)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(payment_id,partner_ref)
                        DO NOTHING""",
                        p.get("organization_id"),
                        p.get("environment_id"),
                        p.get("application_id"),
                        p.get("provider_account_id"),
                        p.get("currency"),
                        id,
                        partner.ref(),
                        policy.version(),
                        partner.basisPoints());
        }
        if (!ids.isEmpty()) {
            String parameters = String.join(",", Collections.nCopies(ids.size(), "?"));
            jdbc.queryForList(
                    "SELECT id FROM platform.share_allocation WHERE payment_id IN (" + parameters
                            + ") ORDER BY id FOR UPDATE",
                    ids.toArray());
        }
        // Provision before any ledger posting. The helper cannot create arbitrary accounts.
        if (!ids.isEmpty())
            jdbc.queryForObject(
                    "SELECT platform.ensure_share_accounts(?::uuid[])",
                    Object.class,
                    "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}");
        Object transaction = transaction();
        return ids.stream().map(id -> new Locked(id, transaction, this)).toList();
    }

    public void reconcile(UUID paymentId, String cause) {
        if (enabled) tx.executeWithoutResult(status -> reconcile(lock(paymentId), cause));
    }
    /** Batch importer hooks prelock the union of account pairs, independent of payment currency order. */
    public void reconcileAll(List<Locked> locked, String cause) {
        if (!enabled || locked.isEmpty()) return;
        requireTransaction();
        for (Locked handle : locked)
            if (handle == null || handle.owner != this || handle.transaction != transaction())
                throw new IllegalStateException("Share locks belong to another transaction");
        String paymentArray = "{"
                + String.join(
                        ",",
                        locked.stream()
                                .map(handle -> handle.paymentId.toString())
                                .distinct()
                                .toList()) + "}";
        jdbc.queryForObject("SELECT platform.lock_share_accounts(?::uuid[])", Object.class, paymentArray);
        for (Locked handle : locked) reconcile(handle, cause);
    }

    public void reconcile(Locked locked, String cause) {
        if (!enabled) return;
        requireTransaction();
        if (locked == null || locked.owner != this || locked.transaction != transaction())
            throw new IllegalStateException("Share locks belong to another transaction");
        var p = one("SELECT * FROM platform.payment WHERE id=?", locked.paymentId);
        var control = one("SELECT * FROM platform.refund_control WHERE payment_id=?", locked.paymentId);
        long captured = number(p, "captured_amount_minor"),
                refunded = control == null ? 0 : number(control, "refunded_minor"),
                held = control == null ? 0 : number(control, "reserved_minor");
        boolean eligible = "KNOWN".equals(p.get("resolution_status"))
                && !"CONFLICT".equals(p.get("financial_status"))
                && (control == null || "NORMAL".equals(control.get("control_state")))
                && eligibility.eligible(locked.paymentId);
        BigDecimal covered = jdbc.queryForObject("""
                SELECT coalesce(sum(amount_minor),0)
                FROM platform.balance_transaction
                WHERE payment_id=? AND state='POSTED' AND lower(business_type)='charge' AND amount_minor>0""", BigDecimal.class, locked.paymentId);
        eligible = eligible && covered.compareTo(BigDecimal.valueOf(captured)) == 0;
        boolean conflicts = Boolean.TRUE.equals(jdbc.queryForObject(
                """
   SELECT EXISTS(SELECT 1 FROM platform.balance_transaction b
    WHERE (b.state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict c WHERE c.balance_transaction_id=b.id)) AND (b.payment_id=? OR b.refund_id IN (SELECT id FROM platform.refund WHERE payment_id=?)
     OR EXISTS(SELECT 1 FROM platform.payment_attempt a WHERE a.payment_id=? AND a.organization_id=b.organization_id AND a.environment_id=b.environment_id AND a.provider_account_id=b.provider_account_id AND a.provider_object_id=b.source_object_id)
     OR EXISTS(SELECT 1 FROM platform.refund r WHERE r.payment_id=? AND r.organization_id=b.organization_id AND r.environment_id=b.environment_id AND r.provider_account_id=b.provider_account_id AND r.provider_refund_id=b.source_object_id)))
   """, Boolean.class, locked.paymentId, locked.paymentId, locked.paymentId, locked.paymentId));
        eligible = eligible && !conflicts;
        var allocations = jdbc.queryForList("""
                SELECT
                    a.*,
                    coalesce((SELECT sum(delta_minor) FROM platform.share_adjustment WHERE allocation_id=a.id),0) AS
                        historical_minor
                FROM platform.share_allocation a
                WHERE payment_id=?
                ORDER BY id""", locked.paymentId);
        var scope = new LedgerScope(
                uuid(p, "organization_id"),
                uuid(p, "environment_id"),
                p.get("currency").toString(),
                null);
        var accounts = jdbc.queryForList("""
                SELECT id,account_purpose
                FROM platform.ledger_account
                WHERE organization_id=?
                    AND environment_id=?
                    AND currency=?
                    AND source_domain='INTERNAL'
                    AND provider_account_id IS NULL
                    AND account_purpose IN ('PARTNER_SHARE_EXPENSE','PARTNER_PAYABLE')""", scope.organizationId(), scope.environmentId(), scope.currency());
        UUID expense = account(accounts, "PARTNER_SHARE_EXPENSE"), payable = account(accounts, "PARTNER_PAYABLE");
        for (var a : allocations) {
            long current = ((BigDecimal) a.get("historical_minor")).longValueExact(),
                    cap =
                            eligible
                                    ? SharePolicy.cap(captured, refunded, held, ((Number) a.get("rate_bps")).intValue())
                                    : 0;
            long delta = Math.subtractExact(cap, current), generation = number(a, "generation");
            if (delta != 0) {
                generation = Math.incrementExact(generation);
                String type = delta > 0 ? "share.accrue" : "share.reverse";
                String eventKey = type + ":" + locked.paymentId + ":" + a.get("partner_ref") + ":"
                        + a.get("policy_version") + ":" + generation;
                var facts = mapper.createObjectNode()
                        .put("allocation_id", a.get("id").toString())
                        .put("payment_id", locked.paymentId.toString())
                        .put("partner_ref", a.get("partner_ref").toString())
                        .put("policy_version", a.get("policy_version").toString())
                        .put("generation", generation)
                        .put("delta_minor", delta)
                        .put("eligible_base", eligible ? SharePolicy.cap(captured, refunded, held, 10000) : 0)
                        .put("cap_minor", cap)
                        .put("cause", Objects.toString(cause, "reconcile"));
                long amount = Math.abs(delta);
                UUID journal = ledger.post(
                        scope,
                        new PostingCommand(
                                eventKey,
                                type,
                                "INTERNAL",
                                a.get("id").toString(),
                                facts,
                                List.of(
                                        new PostingLine(expense, delta > 0 ? Side.DEBIT : Side.CREDIT, amount),
                                        new PostingLine(payable, delta > 0 ? Side.CREDIT : Side.DEBIT, amount)),
                                null));
                jdbc.update(
                        """
                        INSERT INTO platform.share_adjustment(organization_id,environment_id,currency,allocation_id,
                            generation,delta_minor,journal_id,cause)
                        VALUES (?,?,?,?,?,?,?,?)""",
                        scope.organizationId(),
                        scope.environmentId(),
                        scope.currency(),
                        a.get("id"),
                        generation,
                        delta,
                        journal,
                        Objects.toString(cause, "reconcile"));
            }
            jdbc.update("""
                    UPDATE platform.share_allocation
                    SET effective_minor=?,cap_minor=?,generation=?,updated_at=now()
                    WHERE id=?""", cap, cap, generation, a.get("id"));
        }
    }
    /** Scoped query seam reserved for the authenticated PR16 endpoint. */
    public List<Map<String, Object>> allocations(TenantContext scope, UUID paymentId) {
        if (!enabled) return List.of();
        var result = jdbc.queryForList(
                """
                SELECT *
                FROM platform.share_allocation
                WHERE organization_id=?
                    AND environment_id=?
                    AND application_id=?
                    AND provider_account_id=?
                    AND payment_id=?
                ORDER BY partner_ref""",
                scope.organizationId(),
                scope.environmentId(),
                scope.applicationId(),
                scope.allowedProviderAccountId(),
                paymentId);
        for (var allocation : result)
            com.paymentsledger.identity.TenantAccessGuard.requireCurrency(
                    scope, allocation.get("currency").toString());
        return result;
    }

    public List<Map<String, Object>> adjustments(TenantContext scope, UUID paymentId) {
        if (!enabled) return List.of();
        var result = jdbc.queryForList(
                """
                SELECT h.*,a.partner_ref,a.payment_id
                FROM platform.share_adjustment h
                JOIN platform.share_allocation a ON a.id=h.allocation_id
                WHERE a.organization_id=?
                    AND a.environment_id=?
                    AND a.application_id=?
                    AND a.provider_account_id=?
                    AND a.payment_id=?
                ORDER BY a.partner_ref,h.generation""",
                scope.organizationId(),
                scope.environmentId(),
                scope.applicationId(),
                scope.allowedProviderAccountId(),
                paymentId);
        for (var adjustment : result)
            com.paymentsledger.identity.TenantAccessGuard.requireCurrency(
                    scope, adjustment.get("currency").toString());
        return result;
    }
    /** Attempts rotate durably even when one legacy snapshot is malformed; bounded to 100 payments. */
    public int catchUp(UUID organizationId, UUID environmentId, UUID providerAccountId, int limit) {
        if (!enabled) return 0;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Catch-up owns separate bounded local transactions");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Catch-up batch must be 1..100");
        var ids = jdbc.queryForList("""
                SELECT p.id
                FROM platform.payment p
                LEFT
                JOIN platform.share_catchup_cursor c ON c.payment_id=p.id
                WHERE p.organization_id=?
                    AND p.environment_id=?
                    AND p.provider_account_id=?
                    AND p.captured_amount_minor>0
                ORDER BY c.attempted_at NULLS FIRST,p.id
                LIMIT ?""", UUID.class, organizationId, environmentId, providerAccountId, limit);
        for (UUID id : ids) {
            tx.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO platform.share_catchup_cursor(payment_id)
                    VALUES (?)
                    ON CONFLICT(payment_id)
                    DO UPDATE SET attempted_at=clock_timestamp()""", id));
            try {
                reconcile(id, "historical-catch-up");
            } catch (RuntimeException failure) {
                LOG.warn("share_catchup reason=RETRY_REQUIRED paymentId={}", id);
            }
        }
        return ids.size();
    }

    private UUID account(List<Map<String, Object>> accounts, String purpose) {
        return accounts.stream()
                .filter(a -> purpose.equals(a.get("account_purpose")))
                .map(a -> uuid(a, "id"))
                .findFirst()
                .orElseThrow();
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Existing transaction required for share locks");
    }

    private Object transaction() {
        return TransactionSynchronizationManager.getResource(Objects.requireNonNull(jdbc.getDataSource()));
    }

    private JsonNode json(Object value) {
        try {
            return mapper.readTree(value.toString());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Malformed order share policy");
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static UUID uuid(Map<String, Object> row, String name) {
        return (UUID) row.get(name);
    }

    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }
}
