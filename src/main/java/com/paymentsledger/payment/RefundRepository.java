package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.payment.domain.RefundLifecycle;
import com.paymentsledger.payment.repository.RefundIntentRepository;
import com.paymentsledger.payment.repository.RefundRecordRepository;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment/control locks serialize reservations; authoritative observations never perform provider I/O. */
@Repository
public class RefundRepository {
    private com.paymentsledger.dispute.DisputeService disputes;
    private RevenueShareService shares = RevenueShareService.disabled();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentRepository operations;
    private final RefundIntentService intents;
    private final RefundReader reader;
    private RefundEligibilityPolicy eligibility = RefundEligibilityPolicy.unrestricted();
    private static final String SCOPE = "organization_id,environment_id,application_id,provider_account_id,currency";

    public RefundRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
        this(jdbc, manager, json, clock, new PaymentRepository(jdbc, manager, json, clock));
    }

    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.operations = operations;
        this.intents = new RefundIntentService(
                new RefundIntentRepository(jdbc),
                tx,
                operations.clock,
                operations::checkBinding,
                operations::encode,
                () -> eligibility,
                () -> shares);
        this.reader = new RefundReader(new RefundRecordRepository(jdbc), tx);
    }

    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies) {
        this(jdbc, manager, json, clock, operations);
        this.eligibility = policies.getIfAvailable(RefundEligibilityPolicy::unrestricted);
    }

    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            RevenueShareService shares) {
        this(jdbc, tx, json, clock, operations);
        this.shares = shares;
    }

    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,
            RevenueShareService shares) {
        this(jdbc, tx, json, clock, operations, policies);
        this.shares = shares;
    }

    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            RevenueShareService shares,
            com.paymentsledger.dispute.DisputeService disputes) {
        this(jdbc, tx, json, clock, operations, shares);
        this.disputes = disputes;
        this.eligibility = (tenant, payment, control) -> {
            if (disputes.blocks(payment.id())) throw error(ErrorCode.REFUND_BLOCKED);
        };
    }

    @Autowired
    public RefundRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,
            RevenueShareService shares,
            com.paymentsledger.dispute.DisputeService disputes) {
        this(jdbc, tx, json, clock, operations, policies, shares);
        this.disputes = disputes;
    }

    public record Intent(UUID refundId, UUID operationId, boolean replay) {}

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Intent prepare(TenantContext c, String key, RefundRequests.Create request, String provider) {
        return intents.prepare(c, key, request, provider);
    }

    /** Conservative audited unfreeze. Unresolved reservations or ambiguous source associations require investigation. */
    public void resolveExternalConflict(
            ProviderScope scope, UUID paymentId, long expectedVersion, UUID observationId, String auditKey) {
        intents.resolveExternalConflict(scope, paymentId, expectedVersion, observationId, auditKey);
    }

    private Map<String, Object> control(Map<String, Object> payment) {
        jdbc.update(
                "INSERT INTO platform.refund_control(" + SCOPE
                        + ",payment_id,captured_minor) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                payment.get("organization_id"),
                payment.get("environment_id"),
                payment.get("application_id"),
                payment.get("provider_account_id"),
                payment.get("currency"),
                payment.get("id"),
                payment.get("captured_amount_minor"));
        jdbc.update("""
                UPDATE platform.refund_control
                SET captured_minor=greatest(captured_minor,?),updated_at=now()
                WHERE payment_id=?""", payment.get("captured_amount_minor"), payment.get("id"));
        return one("SELECT * FROM platform.refund_control WHERE payment_id=? FOR UPDATE", payment.get("id"));
    }

    public Map<String, Object> lookup(ProviderScope scope, String providerRefundId) {
        if (scope.livemode()) throw error(ErrorCode.FORBIDDEN);
        return one("""
                SELECT *
                FROM platform.refund
                WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_refund_id=?""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), providerRefundId);
    }

    public UUID paymentForCharge(ProviderScope scope, String chargeId) {
        if (scope.livemode()) throw error(ErrorCode.FORBIDDEN);
        var row = one("""
                SELECT payment_id
                FROM platform.payment_attempt
                WHERE organization_id=?
                    AND environment_id=?
                    AND provider_account_id=?
                    AND provider_object_id=?
                    AND captured""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), chargeId);
        return row == null ? null : uuid(row, "payment_id");
    }
    /** Caller authenticates provider scope; null local id never guesses an ambiguous reservation by amount. */
    public UUID accept(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        return tx.execute(status -> acceptLocked(scope, fact, evidence, localRefundId));
    }

    private UUID acceptLocked(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        UUID accepted = mergeFact(scope, fact, evidence, localRefundId);
        UUID parent =
                accepted == null ? paymentForCharge(scope, fact.chargeId()) : uuid(refund(accepted), "payment_id");
        if (parent != null) {
            if (disputes != null) disputes.observeRefundOverlap(parent);
            shares.reconcile(parent, "refund.fact:" + fact.refundId());
        }
        return accepted;
    }

    private UUID mergeFact(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        if (scope.livemode()) throw error(ErrorCode.FORBIDDEN);
        UUID paymentId = paymentForCharge(scope, fact.chargeId());
        var bound = lookup(scope, fact.refundId());
        if (bound != null && localRefundId == null) localRefundId = uuid(bound, "id");
        var local = localRefundId == null
                ? null
                : one("""
                        SELECT *
                        FROM platform.refund
                        WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=?""", localRefundId, scope.organizationId(), scope.environmentId(), scope.providerAccountId());
        if (local != null) paymentId = uuid(local, "payment_id");
        if (paymentId == null) {
            observe(scope, fact, evidence, null, null);
            return null;
        }
        var payment = one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", paymentId);
        control(payment);
        if (shares.enabled()) {
            shares.lock(paymentId);
            com.paymentsledger.financialingestion.FinancialSourceLocks.lock(jdbc, paymentId, List.of(fact.refundId()));
        }
        bound = lookup(scope, fact.refundId());
        if (bound != null && local == null) local = bound;
        if (bound != null && local != null && !bound.get("id").equals(local.get("id"))) {
            observe(scope, fact, evidence, uuid(local, "id"), paymentId);
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET control_state='EXTERNAL_CONFLICT',version=version+1
                    WHERE payment_id=?""", paymentId);
            jdbc.update("""
                    UPDATE platform.refund
                    SET resolution_status='CONFLICT',unresolved_since=coalesce(unresolved_since,?),version=version+1
                    WHERE id=?""", Timestamp.from(operations.now()), local.get("id"));
            return uuid(local, "id");
        }
        if (local != null) local = one("SELECT * FROM platform.refund WHERE id=? FOR UPDATE", local.get("id"));
        boolean external = local == null;
        if (external) {
            // Preserve genuine external facts even when R + H exceeds C. Freeze before changing amounts.
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now()
                    WHERE payment_id=?""", paymentId);
            if (!fact.currency().equalsIgnoreCase(str(payment, "currency"))) {
                observe(scope, fact, evidence, null, null);
                return null;
            }
            UUID id = PaymentIds.newId();
            jdbc.update(
                    "INSERT INTO platform.refund(id," + SCOPE
                            + ",payment_id,amount_minor,request_fingerprint,source,provider_charge_id,provider_refund_id,refund_status) VALUES (?,?,?,?,?,?,?,?,?,'EXTERNAL',?,?,'PENDING')",
                    id,
                    payment.get("organization_id"),
                    payment.get("environment_id"),
                    payment.get("application_id"),
                    payment.get("provider_account_id"),
                    payment.get("currency"),
                    paymentId,
                    fact.amountMinor(),
                    "external:" + fact.refundId(),
                    fact.chargeId(),
                    fact.refundId());
            local = one("SELECT * FROM platform.refund WHERE id=?", id);
            prepareExternalQuery(local, scope);
        }
        UUID refundId = uuid(local, "id");
        observe(scope, fact, evidence, refundId, paymentId);
        boolean conflict = fact.amountMinor() != number(local, "amount_minor")
                || !fact.currency().equalsIgnoreCase(str(local, "currency"))
                || !Objects.equals(fact.chargeId(), local.get("provider_charge_id"))
                || local.get("provider_refund_id") != null && !fact.refundId().equals(local.get("provider_refund_id"))
                || local.get("balance_transaction_id") != null
                        && fact.balanceTransactionId() != null
                        && !fact.balanceTransactionId().equals(local.get("balance_transaction_id"))
                || local.get("failure_balance_transaction_id") != null
                        && fact.failureBalanceTransactionId() != null
                        && !fact.failureBalanceTransactionId().equals(local.get("failure_balance_transaction_id"));
        String before = str(local, "refund_status"),
                state =
                        switch (Objects.toString(fact.status(), "")) {
                            case "succeeded" -> "SUCCEEDED";
                            case "failed" -> "FAILED";
                            case "canceled" -> "CANCELED";
                            case "pending" -> "PENDING";
                            case "requires_action" -> "REQUIRES_ACTION";
                            default -> before;
                        };
        boolean recognized = Set.of("succeeded", "failed", "canceled", "pending", "requires_action")
                .contains(Objects.toString(fact.status(), ""));
        if (terminal(before) && terminal(state) && !before.equals(state)) conflict = true;
        if (conflict || "CONFLICT".equals(local.get("resolution_status"))) {
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET control_state='EXTERNAL_CONFLICT',version=version+1
                    WHERE payment_id=?""", paymentId);
            jdbc.update("""
                    UPDATE platform.refund
                    SET
                        resolution_status='CONFLICT',
                        unresolved_since=coalesce(unresolved_since,?),
                        version=version+1,
                        updated_at=now()
                    WHERE id=?""", Timestamp.from(operations.now()), refundId);
            return refundId;
        }
        if (terminal(before)) {
            state = before;
            recognized = true;
        }
        if (!terminal(before) && terminal(state)) {
            long held = "LOCAL".equals(local.get("source")) ? number(local, "amount_minor") : 0;
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET
                        reserved_minor=reserved_minor-?,
                        refunded_minor=refunded_minor+?,
                        version=version+1,
                        updated_at=now()
                    WHERE payment_id=?""", held, "SUCCEEDED".equals(state) ? number(local, "amount_minor") : 0, paymentId);
        }
        // Older pending evidence can add BT references but must not regress a terminal state or provider status.
        String providerStatus = terminal(before) ? str(local, "provider_status") : fact.status();
        jdbc.update(
                """
                UPDATE platform.refund
                SET
                    refund_status=?,
                    resolution_status=?,
                    provider_status=?,
                    provider_refund_id=coalesce(provider_refund_id,?),
                    balance_transaction_id=coalesce(balance_transaction_id,?),
                    failure_balance_transaction_id=coalesce(failure_balance_transaction_id,?),
                    failure_reason=coalesce(failure_reason,?),
                    last_provider_confirmed_at=?,
                    unresolved_since=CASE WHEN ? THEN NULL ELSE coalesce(unresolved_since,?) END,
                    version=version+1,
                    updated_at=now()
                WHERE id=?""",
                state,
                recognized ? "KNOWN" : "UNKNOWN",
                providerStatus,
                fact.refundId(),
                fact.balanceTransactionId(),
                fact.failureBalanceTransactionId(),
                fact.failureReason(),
                Timestamp.from(operations.now()),
                recognized,
                Timestamp.from(operations.now()),
                refundId);
        // A webhook/query may converge business facts, but cannot steal an active sender's operation lease.
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='WAITING',available_at=least(available_at,?),version=version+1
                WHERE refund_id=? AND status<>'RUNNING' AND status<>'ESCALATED'""", Timestamp.from(operations.now()), refundId);
        return refundId;
    }

    private void prepareExternalQuery(Map<String, Object> refund, ProviderScope scope) {
        UUID operation = UUID.randomUUID();
        String key = "refund_query_" + operation;
        var command = Map.of("scope", scope, "refundId", refund.get("provider_refund_id"));
        jdbc.update(
                "INSERT INTO platform.provider_operation(id," + SCOPE
                        + ",operation_target,refund_id,operation_type,operation_key,request_fingerprint,command,expires_at,external_object_id) VALUES (?,?,?,?,?,?,'REFUND',?,'QUERY_REFUND',?,?,?::jsonb,?,?)",
                operation,
                refund.get("organization_id"),
                refund.get("environment_id"),
                refund.get("application_id"),
                refund.get("provider_account_id"),
                refund.get("currency"),
                refund.get("id"),
                key,
                "external-query:" + refund.get("provider_refund_id"),
                operations.encode(command),
                Timestamp.from(operations.now().plus(Duration.ofDays(1))),
                refund.get("provider_refund_id"));
        jdbc.update(
                "INSERT INTO platform.recovery_task(" + SCOPE
                        + ",operation_id,refund_id,available_at) VALUES (?,?,?,?,?,?,?,?)",
                refund.get("organization_id"),
                refund.get("environment_id"),
                refund.get("application_id"),
                refund.get("provider_account_id"),
                refund.get("currency"),
                operation,
                refund.get("id"),
                Timestamp.from(operations.now()));
    }

    private void observe(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID refund, UUID payment) {
        jdbc.update(
                """
                INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,
                    provider_refund_id,refund_id,payment_id,fact,evidence)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                scope.organizationId(),
                scope.environmentId(),
                scope.providerAccountId(),
                fact.refundId(),
                refund,
                payment,
                operations.encode(fact),
                operations.encode(evidence));
    }

    public void recordSourceQuery(ProviderScope scope, String providerRefundId, ProviderCallResult<RefundFact> result) {
        if (result.payload() != null) {
            accept(scope, result.payload(), result.evidence(), null);
            return;
        }
        tx.execute(status -> {
            var fact = new LinkedHashMap<String, Object>();
            fact.put("refundId", providerRefundId);
            fact.put("classification", result.classification().name());
            jdbc.update(
                    """
                    INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,
                        provider_refund_id,fact,evidence)
                    VALUES (?,?,?,?,?::jsonb,?::jsonb)""",
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    providerRefundId,
                    operations.encode(fact),
                    operations.encode(result.evidence()));
            return null;
        });
    }

    static boolean terminal(String state) {
        return RefundLifecycle.terminal(state);
    }

    void lock(UUID refund) {
        var r = one("SELECT payment_id FROM platform.refund WHERE id=?", refund);
        if (r == null) throw error(ErrorCode.NOT_FOUND);
        one("SELECT id FROM platform.payment WHERE id=? FOR UPDATE", r.get("payment_id"));
        one("SELECT payment_id FROM platform.refund_control WHERE payment_id=? FOR UPDATE", r.get("payment_id"));
        one("SELECT id FROM platform.refund WHERE id=? FOR UPDATE", refund);
    }

    Map<String, Object> refund(UUID id) {
        return one("SELECT * FROM platform.refund WHERE id=?", id);
    }
    /** Freeze contradictory remote identities only at the common owned-lease linearization point. */
    void conflictingAnchors(Claim claim) {
        operations.transaction(() -> {
            UUID id = uuid(claim.operation(), "refund_id");
            lock(id);
            if (!operations.owns(claim)) return null;
            jdbc.update("""
                    UPDATE platform.refund_control
                    SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now()
                    WHERE payment_id=?""", claim.paymentId());
            jdbc.update("""
                    UPDATE platform.refund
                    SET
                        resolution_status='CONFLICT',
                        unresolved_since=coalesce(unresolved_since,?),
                        version=version+1,
                        updated_at=now()
                    WHERE id=?""", Timestamp.from(operations.now()), id);
            // Ownership was checked under the retained operation lock. Do not recheck wall-clock expiry after changing
            // business state.
            shares.reconcile(claim.paymentId(), "refund.anchor-conflict");
            operations.escalateOwned(claim, "CONFLICTING_REFUND_ANCHORS");
            return null;
        });
    }

    void finish(Claim claim, ProviderCallResult<RefundFact> result, boolean query) {
        operations.transaction(() -> {
            UUID id = uuid(claim.operation(), "refund_id");
            lock(id);
            operations.observations(
                    claim.operationId(),
                    claim.paymentId(),
                    claim.token(),
                    List.of(new Observation(
                            query ? "QUERY_REFUND" : "CREATE_REFUND",
                            result,
                            result.payload() == null ? null : result.payload().refundId())));
            if (!operations.owns(claim)) return null;
            if (query)
                jdbc.update(
                        "UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",
                        Timestamp.from(operations.now()),
                        claim.operationId());
            var current = refund(id);
            if (result.payload() != null
                    && (result.classification() == ProviderCallClassification.SUCCESS
                            || result.classification() == ProviderCallClassification.AUTH_REQUIRED)) {
                acceptLocked(scope(current), result.payload(), result.evidence(), id);
            } else if (!query
                    && result.classification() == ProviderCallClassification.DEFINITIVE_REJECT
                    && current.get("provider_refund_id") == null
                    && !terminal(str(current, "refund_status"))) {
                jdbc.update("""
                        UPDATE platform.refund_control
                        SET reserved_minor=reserved_minor-?,version=version+1
                        WHERE payment_id=?""", current.get("amount_minor"), current.get("payment_id"));
                jdbc.update("""
                        UPDATE platform.refund
                        SET refund_status='FAILED',resolution_status='KNOWN',version=version+1,updated_at=now()
                        WHERE id=?""", id);
            } else if (!terminal(str(current, "refund_status"))) jdbc.update("""
                        UPDATE platform.refund
                        SET
                            resolution_status=CASE WHEN resolution_status='CONFLICT' THEN 'CONFLICT' ELSE 'UNKNOWN' END,
                            unresolved_since=coalesce(unresolved_since,?),
                            version=version+1
                        WHERE id=?""", Timestamp.from(operations.now()), id);
            if (result.payload() == null
                    || (result.classification() != ProviderCallClassification.SUCCESS
                            && result.classification() != ProviderCallClassification.AUTH_REQUIRED))
                shares.reconcile(claim.paymentId(), "refund.operation:" + claim.operationId());
            current = refund(id);
            String resolution = str(current, "resolution_status");
            Instant unresolved = instant(current, "unresolved_since");
            if (!"KNOWN".equals(resolution)
                    && unresolved != null
                    && !unresolved
                            .plus(operations.recoveryPolicy().unresolvedAge())
                            .isAfter(operations.now())) {
                operations.alertAge(claim);
                operations.escalate(claim, "UNRESOLVED_AGE_LIMIT");
                return null;
            }
            operations.completeOperation(
                    claim,
                    "KNOWN".equals(resolution) ? "RESOLVED" : "UNKNOWN",
                    resolution,
                    str(current, "provider_refund_id"),
                    str(current, "provider_status"));
            return null;
        });
    }

    boolean submitted(Claim claim) {
        return operations.transaction(() -> {
            UUID id = uuid(claim.operation(), "refund_id");
            lock(id);
            if (!operations.owns(claim)) return false;
            var current = refund(id);
            var control = one(
                    "SELECT control_state FROM platform.refund_control WHERE payment_id=?", current.get("payment_id"));
            if (terminal(str(current, "refund_status"))
                    || current.get("provider_refund_id") != null
                    || !"NORMAL".equals(control.get("control_state"))
                    || "CONFLICT".equals(current.get("resolution_status"))) {
                operations.completeOperation(
                        claim,
                        terminal(str(current, "refund_status")) ? "RESOLVED" : "UNKNOWN",
                        str(current, "resolution_status"),
                        str(current, "provider_refund_id"),
                        str(current, "provider_status"));
                return false;
            }
            jdbc.update("""
                    UPDATE platform.refund
                    SET refund_status='SUBMITTED',version=version+1,updated_at=now()
                    WHERE id=? AND refund_status='RESERVED'""", id);
            return true;
        });
    }

    static ProviderScope scope(Map<String, Object> row) {
        return new ProviderScope(
                uuid(row, "organization_id"), uuid(row, "environment_id"), uuid(row, "provider_account_id"), false);
    }

    public Map<String, Object> read(TenantContext c, UUID id) {
        return reader.read(c, id);
    }
}
