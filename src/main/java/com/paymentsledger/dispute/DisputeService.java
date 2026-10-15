package com.paymentsledger.dispute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.financialingestion.FinancialSourceLocks;
import com.paymentsledger.identity.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Dispute observations never move PSP funds. Only an accepted balance transaction does. */
public final class DisputeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final LedgerPostingService ledger;
    private final RevenueShareService shares;

    public DisputeService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            LedgerPostingService ledger,
            RevenueShareService shares) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.mapper = mapper;
        this.ledger = ledger;
        this.shares = shares;
    }

    public UUID paymentForCharge(ProviderScope scope, String charge) {
        if (charge == null) return null;
        var rows = jdbc.queryForList(
                """
                SELECT DISTINCT p.id
                FROM platform.payment p
                LEFT
                JOIN platform.payment_attempt a ON a.payment_id=p.id
                WHERE p.organization_id=?
                    AND p.environment_id=?
                    AND p.provider_account_id=?
                    AND (p.latest_charge_id=? OR a.provider_object_id=?)
                ORDER BY p.id""",
                UUID.class,
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                charge,
                charge);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public Map<String, Object> lookup(ProviderScope scope, String id) {
        return one("""
                SELECT *
                FROM platform.dispute
                WHERE organization_id=?
                    AND environment_id=?
                    AND provider_account_id=?
                    AND livemode=?
                    AND provider_dispute_id=?""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), scope.livemode(), id);
    }

    public UUID accept(ProviderScope scope, DisputeFact fact, ProviderEvidence evidence) {
        Objects.requireNonNull(evidence);
        return tx.execute(status -> acceptLocked(scope, fact, evidence));
    }

    private UUID acceptLocked(ProviderScope scope, DisputeFact fact, ProviderEvidence evidence) {
        var prior = lookup(scope, fact.disputeId());
        UUID payment = prior == null ? null : (UUID) prior.get("payment_id");
        if (payment == null)
            payment = paymentForCharge(
                    scope,
                    prior != null && prior.get("provider_charge_id") != null
                            ? prior.get("provider_charge_id").toString()
                            : fact.chargeId());
        Map<String, Object> p = null;
        if (payment != null) {
            p = one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", payment);
            jdbc.update("""
                    INSERT INTO platform.refund_control(payment_id,organization_id,environment_id,application_id,
                        provider_account_id,currency,captured_minor)
                    SELECT
                        id,
                        organization_id,
                        environment_id,
                        application_id,
                        provider_account_id,
                        currency,
                        captured_amount_minor
                    FROM platform.payment
                    WHERE id=?
                    ON CONFLICT(payment_id)
                    DO NOTHING""", payment);
            jdbc.queryForList("SELECT payment_id FROM platform.refund_control WHERE payment_id=? FOR UPDATE", payment);
            jdbc.queryForList("SELECT id FROM platform.dispute WHERE payment_id=? ORDER BY id FOR UPDATE", payment);
        }
        String currency = p == null
                ? fact.currency().toUpperCase(Locale.ROOT)
                : p.get("currency").toString();
        jdbc.update(
                """
                INSERT INTO platform.dispute(organization_id,environment_id,provider_account_id,livemode,
                    provider_dispute_id,application_id,payment_id,provider_charge_id,currency,provider_status,
                    reason,evidence_due_by,last_observed_at,disputed_principal_minor)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(organization_id,environment_id,provider_account_id,livemode,provider_dispute_id)
                DO NOTHING""",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                fact.disputeId(),
                p == null ? null : p.get("application_id"),
                payment,
                fact.chargeId(),
                currency,
                fact.status(),
                fact.reason(),
                stamp(fact.evidenceDueBy()),
                stamp(evidence.requestedAt()),
                fact.amountMinor());
        var d = one(
                """
                SELECT *
                FROM platform.dispute
                WHERE organization_id=?
                    AND environment_id=?
                    AND provider_account_id=?
                    AND livemode=?
                    AND provider_dispute_id=?
                FOR
                UPDATE""",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                scope.livemode(),
                fact.disputeId());
        UUID id = uuid(d, "id");
        // A concurrent association changing the required payment locks must retry from the transaction root.
        if (d.get("payment_id") != null && !Objects.equals(d.get("payment_id"), payment))
            throw new IllegalStateException("Dispute association changed; retry observation");
        RevenueShareService.Locked share = payment == null ? null : shares.lock(payment);
        if (payment != null) {
            FinancialSourceLocks.lock(jdbc, payment, List.of(fact.disputeId()));
            jdbc.queryForObject("SELECT platform.ensure_dispute_accounts(?)", Object.class, payment);
        }
        jdbc.update(
                """
                INSERT INTO platform.dispute_observation(dispute_id,fact,evidence)
                VALUES(?,?::jsonb,?::jsonb)""",
                id,
                mapper.valueToTree(fact).toString(),
                safeEvidence(evidence).toString());
        for (String bt : fact.balanceTransactionIds()) jdbc.update("""
                    INSERT INTO platform.dispute_bt_link(dispute_id,bt_id)
                    VALUES(?,?)
                    ON CONFLICT
                    DO NOTHING""", id, bt);
        boolean identityConflict = !d.get("currency").equals(fact.currency().toUpperCase(Locale.ROOT))
                || number(d, "disputed_principal_minor") != fact.amountMinor()
                || d.get("provider_charge_id") != null && !Objects.equals(d.get("provider_charge_id"), fact.chargeId());
        if (p != null
                && (fact.amountMinor() > number(p, "captured_amount_minor")
                        || !Objects.equals(p.get("currency"), fact.currency().toUpperCase(Locale.ROOT))))
            identityConflict = true;
        if (identityConflict) conflict(id, "DISPUTE_IDENTITY_OR_AMOUNT_CONFLICT", mapper.valueToTree(fact));
        if (payment != null && d.get("payment_id") == null) {
            jdbc.update("""
                    UPDATE platform.dispute
                    SET payment_id=?,application_id=?,currency=?,updated_at=now()
                    WHERE id=?""", payment, p.get("application_id"), p.get("currency"), id);
        }
        Instant previous = ((Timestamp) d.get("last_observed_at")).toInstant();
        String old = Objects.toString(d.get("provider_status"), "");
        String next = Objects.toString(fact.status(), "");
        boolean stale = evidence.requestedAt().isBefore(previous);
        boolean terminal = old.equals("won") || old.equals("lost") || old.equals("warning_closed");
        boolean regression = terminal && !(old.equals(next) || old.equals("lost") && next.equals("won"));
        if (!stale && regression) conflict(id, "DISPUTE_TERMINAL_STATE_CONFLICT", mapper.valueToTree(fact));
        if (!stale && !regression && !identityConflict)
            jdbc.update(
                    """
                    UPDATE platform.dispute
                    SET provider_status=?,reason=?,evidence_due_by=?,last_observed_at=?,updated_at=now()
                    WHERE id=?""", fact.status(), fact.reason(), stamp(fact.evidenceDueBy()), stamp(evidence.requestedAt()), id);
        if (payment != null) {
            lockAccounts(payment);
            reclassify(id);
            refresh(id);
            observeRefundOverlap(payment);
            shares.reconcile(share, "dispute.observation:" + fact.disputeId());
        } else refresh(id);
        return id;
    }

    public boolean blocks(UUID paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM platform.dispute d WHERE d.payment_id=? AND (d.blocking OR d.conflict OR EXISTS(
    SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=d.organization_id AND b.environment_id=d.environment_id AND b.provider_account_id=d.provider_account_id AND b.livemode=d.livemode
    AND (b.dispute_id=d.id OR b.source_object_id=d.provider_dispute_id OR b.bt_id IN(SELECT bt_id FROM platform.dispute_bt_link WHERE dispute_id=d.id))
    AND (b.state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict c WHERE c.balance_transaction_id=b.id)))))
   """, Boolean.class, paymentId));
    }
    /** Called before refund/share ledger work with the caller's business locks already held. */
    public void observeRefundOverlap(UUID paymentId) {
        requireTransaction();
        if (!blocks(paymentId)) return;
        var c = one("SELECT * FROM platform.refund_control WHERE payment_id=?", paymentId);
        if (c != null && (number(c, "reserved_minor") > 0 || number(c, "refunded_minor") > 0)) {
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now()
                    WHERE payment_id=? AND control_state<>'EXTERNAL_CONFLICT'""", paymentId);
            for (var d : jdbc.queryForList(
                    "SELECT id FROM platform.dispute WHERE payment_id=? AND blocking ORDER BY id", paymentId))
                conflict(
                        uuid(d, "id"),
                        "REFUND_DISPUTE_OVERLAP",
                        mapper.createObjectNode().put("payment_id", paymentId.toString()));
        }
    }
    /** Caller already owns payment/control/dispute/allocation and current source BT locks. No business locks here. */
    public String postLocked(ProviderScope scope, BalanceTransactionFact fact, UUID rowId) {
        requireTransaction();
        var b = one("SELECT * FROM platform.balance_transaction WHERE id=?", rowId);
        if ("POSTED".equals(b.get("state"))) return "POSTED";
        var d = lookup(scope, fact.sourceObjectId());
        if (d == null || d.get("payment_id") == null) return pending(rowId, "MISSING_DISPUTE_CHARGE");
        UUID id = uuid(d, "id"), payment = uuid(d, "payment_id");
        boolean invalid = !d.get("currency").equals(fact.currency().toUpperCase(Locale.ROOT))
                || !"dispute".equals(fact.sourceObjectType())
                || !"PAYMENTS".equals(fact.balanceDomain());
        if (invalid) {
            conflict(id, "DISPUTE_BT_SCOPE_CONFLICT", mapper.valueToTree(fact));
            return quarantine(rowId, "DISPUTE_BT_SCOPE_CONFLICT");
        }
        jdbc.update("""
                INSERT INTO platform.dispute_bt_link(dispute_id,bt_id)
                VALUES(?,?)
                ON CONFLICT
                DO NOTHING""", id, fact.btId());
        jdbc.update("UPDATE platform.balance_transaction SET dispute_id=?,payment_id=? WHERE id=?", id, payment, rowId);
        long principal = fact.amountMinor(),
                ar = number(d, "outstanding_receivable_minor"),
                loss = number(d, "confirmed_loss_minor"),
                debited = number(d, "debited_principal_minor"),
                returned = number(d, "returned_principal_minor");
        if (principal > 0 && debited == 0) return pending(rowId, "DISPUTE_RETURN_BEFORE_DEBIT");
        long debit = principal < 0 ? Math.negateExact(principal) : 0;
        if (debit > 0 && debit > Math.subtractExact(number(d, "disputed_principal_minor"), debited)) {
            conflict(id, "DISPUTE_DEBIT_EXCEEDS_PRINCIPAL", mapper.valueToTree(fact));
            return quarantine(rowId, "DISPUTE_DEBIT_EXCEEDS_PRINCIPAL");
        }
        if (principal == 0 && fact.feeMinor() == 0) {
            jdbc.update("""
                    UPDATE platform.balance_transaction
                    SET state='ZERO',reason='ZERO_MOVEMENT',updated_at=now()
                    WHERE id=?""", rowId);
            refresh(id);
            return "ZERO";
        }
        jdbc.update("""
                UPDATE platform.dispute
                SET blocking=true,local_handling=CASE WHEN conflict THEN 'NEEDS_REVIEW' ELSE 'WAITING_EVIDENCE' END
                WHERE id=?""", id);
        jdbc.queryForObject("SELECT platform.ensure_dispute_accounts(?)", Object.class, payment);
        lockAccounts(payment);
        var accounts = accounts(d);
        var lines = new ArrayList<PostingLine>();
        add(lines, accounts, "PSP_FUNDS", fact.netMinor());
        add(lines, accounts, "DISPUTE_FEE_EXPENSE", fact.feeMinor());
        long arDelta = 0, lossDelta = 0, suspense = 0;
        if (debit > 0) {
            arDelta = debit;
            add(lines, accounts, "DISPUTE_RECEIVABLE", debit);
        }
        if (principal > 0) {
            var allocation = DisputeAmounts.allocateReturn(principal, ar, loss);
            arDelta = -allocation.receivable();
            lossDelta = -allocation.loss();
            suspense = allocation.suspense();
            add(lines, accounts, "DISPUTE_RECEIVABLE", arDelta);
            add(lines, accounts, "DISPUTE_LOSS", lossDelta);
            add(lines, accounts, "SUSPENSE", -suspense);
        }
        ObjectNode immutable = mapper.valueToTree(fact);
        immutable.remove(List.of("status", "availableOn", "rawDigest"));
        UUID journal = ledger.post(
                ledgerScope(d),
                new PostingCommand(
                        "bt:" + scope.providerAccountId() + ":" + scope.livemode() + ":" + fact.btId(),
                        "L-DISPUTE",
                        "PROVIDER",
                        fact.btId(),
                        immutable,
                        lines,
                        null));
        jdbc.update("""
                UPDATE platform.balance_transaction
                SET state='POSTED',journal_id=?,reason=NULL,version=version+1,updated_at=now()
                WHERE id=?""", journal, rowId);
        jdbc.update(
                """
                INSERT INTO platform.dispute_financial_fact(organization_id,environment_id,currency,dispute_id,
                    balance_transaction_id,journal_id,kind,principal_minor,fee_minor,receivable_delta,loss_delta,
                    suspense_minor)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                d.get("organization_id"),
                d.get("environment_id"),
                d.get("currency"),
                id,
                rowId,
                journal,
                debit > 0 ? "DEBIT" : principal > 0 ? "RETURN" : "FEE",
                debit > 0 ? debit : principal,
                fact.feeMinor(),
                arDelta,
                lossDelta,
                suspense);
        jdbc.update(
                """
                UPDATE platform.dispute
                SET
                    outstanding_receivable_minor=?,
                    confirmed_loss_minor=?,
                    debited_principal_minor=?,
                    returned_principal_minor=?,
                    dispute_fee_minor=?,
                    updated_at=now()
                WHERE id=?""",
                Math.addExact(ar, arDelta),
                Math.addExact(loss, lossDelta),
                Math.addExact(debited, debit),
                Math.addExact(returned, principal > 0 ? principal - suspense : 0),
                Math.addExact(number(d, "dispute_fee_minor"), fact.feeMinor()),
                id);
        if (suspense > 0)
            conflict(
                    id,
                    "DISPUTE_EXCESS_RETURN",
                    mapper.createObjectNode().put("bt_id", fact.btId()).put("suspense_minor", suspense));
        reclassify(id);
        refresh(id);
        observeRefundOverlap(payment);
        return "POSTED";
    }

    private void reclassify(UUID id) {
        var d = one("SELECT * FROM platform.dispute WHERE id=?", id);
        long amount = number(d, "outstanding_receivable_minor");
        if (!"lost".equals(d.get("provider_status")) || amount == 0) return;
        long generation = Math.incrementExact(number(d, "loss_generation"));
        var accounts = accounts(d);
        var facts = mapper.createObjectNode()
                .put("dispute_id", id.toString())
                .put("generation", generation)
                .put("principal_minor", amount);
        UUID journal = ledger.post(
                ledgerScope(d),
                new PostingCommand(
                        "dispute.loss:" + id + ":" + generation,
                        "dispute.loss",
                        "INTERNAL",
                        id.toString(),
                        facts,
                        List.of(
                                new PostingLine(accounts.get("DISPUTE_LOSS"), Side.DEBIT, amount),
                                new PostingLine(accounts.get("DISPUTE_RECEIVABLE"), Side.CREDIT, amount)),
                        null));
        jdbc.update(
                """
                INSERT INTO platform.dispute_financial_fact(organization_id,environment_id,currency,dispute_id,
                    journal_id,kind,principal_minor,receivable_delta,loss_delta,loss_generation)
                VALUES(?,?,?,?,?,'LOSS',?,?,?,?)""",
                d.get("organization_id"),
                d.get("environment_id"),
                d.get("currency"),
                id,
                journal,
                amount,
                -amount,
                amount,
                generation);
        jdbc.update("""
                UPDATE platform.dispute
                SET outstanding_receivable_minor=0,confirmed_loss_minor=?,loss_generation=?,updated_at=now()
                WHERE id=?""", Math.addExact(number(d, "confirmed_loss_minor"), amount), generation, id);
    }

    private void refresh(UUID id) {
        var d = one("SELECT * FROM platform.dispute WHERE id=?", id);
        long links =
                jdbc.queryForObject("SELECT count(*) FROM platform.dispute_bt_link WHERE dispute_id=?", Long.class, id);
        boolean complete = !Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM platform.dispute_bt_link l WHERE l.dispute_id=? AND NOT EXISTS(SELECT 1
                    FROM platform.balance_transaction b WHERE b.organization_id=? AND b.environment_id=? AND
                    b.provider_account_id=? AND b.livemode=? AND b.bt_id=l.bt_id AND b.dispute_id=? AND b.state
                    IN('POSTED','ZERO') AND NOT EXISTS(SELECT 1 FROM platform.bt_evidence_conflict c WHERE
                    c.balance_transaction_id=b.id)))""",
                Boolean.class,
                id,
                d.get("organization_id"),
                d.get("environment_id"),
                d.get("provider_account_id"),
                d.get("livemode"),
                id));
        boolean zero = number(d, "outstanding_receivable_minor") == 0 && number(d, "confirmed_loss_minor") == 0;
        boolean resolved = d.get("payment_id") != null
                && !Boolean.TRUE.equals(d.get("conflict"))
                && complete
                && zero
                && ("warning_closed".equals(d.get("provider_status")) && links == 0
                        || "won".equals(d.get("provider_status"))
                                && number(d, "debited_principal_minor") > 0
                                && number(d, "returned_principal_minor") == number(d, "debited_principal_minor"));
        jdbc.update(
                """
                UPDATE platform.dispute
                SET blocking=?,local_handling=?,updated_at=now()
                WHERE id=?""",
                !resolved,
                Boolean.TRUE.equals(d.get("conflict")) ? "NEEDS_REVIEW" : resolved ? "RESOLVED" : "WAITING_EVIDENCE",
                id);
    }

    private void conflict(UUID id, String reason, com.fasterxml.jackson.databind.JsonNode evidence) {
        jdbc.update("""
                UPDATE platform.dispute
                SET conflict=true,blocking=true,local_handling='NEEDS_REVIEW',updated_at=now()
                WHERE id=?""", id);
        jdbc.update("""
                INSERT INTO platform.dispute_case(dispute_id,reason,evidence)
                VALUES(?,?,?::jsonb)
                ON CONFLICT(dispute_id,reason)
                DO NOTHING""", id, reason, evidence.toString());
    }

    private String pending(UUID id, String reason) {
        jdbc.update("""
                UPDATE platform.balance_transaction
                SET state='PENDING_EVIDENCE',reason=?,updated_at=now()
                WHERE id=?""", reason, id);
        return "PENDING_EVIDENCE";
    }

    private String quarantine(UUID id, String reason) {
        jdbc.update("""
                UPDATE platform.balance_transaction
                SET state='QUARANTINED',reason=?,updated_at=now()
                WHERE id=?""", reason, id);
        return "QUARANTINED";
    }

    private void lockAccounts(UUID payment) {
        jdbc.queryForObject("SELECT platform.lock_dispute_accounts(?)", Object.class, payment);
    }

    private Map<String, UUID> accounts(Map<String, Object> d) {
        var result = new HashMap<String, UUID>();
        for (var a : jdbc.queryForList(
                """
                SELECT id,account_purpose
                FROM platform.ledger_account
                WHERE organization_id=?
                    AND environment_id=?
                    AND currency=?
                    AND ((source_domain='PROVIDER' AND provider_account_id=? AND balance_domain='PAYMENTS') OR
                        (source_domain='INTERNAL' AND account_purpose='DISPUTE_LOSS'))""",
                d.get("organization_id"),
                d.get("environment_id"),
                d.get("currency"),
                d.get("provider_account_id")))
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

    private LedgerScope ledgerScope(Map<String, Object> d) {
        return new LedgerScope(
                uuid(d, "organization_id"),
                uuid(d, "environment_id"),
                d.get("currency").toString(),
                uuid(d, "provider_account_id"));
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

    public List<Map<String, Object>> disputes(TenantContext scope, UUID paymentId) {
        return scoped(scope, paymentId, "d.*", "platform.dispute d");
    }

    public List<Map<String, Object>> financialFacts(TenantContext scope, UUID paymentId) {
        return scoped(
                scope,
                paymentId,
                "f.*",
                "platform.dispute d JOIN platform.dispute_financial_fact f ON f.dispute_id=d.id");
    }

    public List<Map<String, Object>> cases(TenantContext scope, UUID paymentId) {
        return scoped(
                scope,
                paymentId,
                "c.*,d.currency",
                "platform.dispute d JOIN platform.dispute_case c ON c.dispute_id=d.id");
    }

    public List<Map<String, Object>> observations(TenantContext scope, UUID paymentId) {
        return scoped(
                scope,
                paymentId,
                "o.*,d.currency",
                "platform.dispute d JOIN platform.dispute_observation o ON o.dispute_id=d.id");
    }

    private List<Map<String, Object>> scoped(TenantContext scope, UUID paymentId, String fields, String tables) {
        var rows = jdbc.queryForList(
                "SELECT " + fields + " FROM " + tables
                        + " WHERE d.organization_id=? AND d.environment_id=? AND d.application_id=? AND d.provider_account_id=? AND d.payment_id=? ORDER BY d.id",
                scope.organizationId(),
                scope.environmentId(),
                scope.applicationId(),
                scope.allowedProviderAccountId(),
                paymentId);
        for (var row : rows)
            TenantAccessGuard.requireCurrency(scope, row.get("currency").toString());
        return rows;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dispute mutations require the caller transaction");
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Timestamp stamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        return (UUID) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
