package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.error;
import static com.paymentsledger.payment.PaymentRepository.instant;
import static com.paymentsledger.payment.PaymentRepository.number;
import static com.paymentsledger.payment.PaymentRepository.str;
import static com.paymentsledger.payment.PaymentRepository.uuid;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.PaymentRepository.Claim;
import com.paymentsledger.payment.domain.OperationLeaseRules;
import com.paymentsledger.payment.domain.RefundLifecycle;
import com.paymentsledger.payment.domain.StripeDeployment;
import com.paymentsledger.payment.repository.OperationLeaseRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands provider operations to senders and recovery workers under a fenced lease, and schedules, escalates and wakes
 * their recovery tasks. Claim transactions never call a provider.
 *
 * <p>Methods documented as running inside the caller's transaction expect the payment lock to be held already.
 */
final class OperationLeases {
    /** Marks a payment's state uncertain when its operation is escalated. */
    interface PaymentUncertainty {
        void markUnknown(UUID paymentId, boolean conflict);
    }

    // Escalation alerts keep their established logger category.
    private static final Logger log = LoggerFactory.getLogger(PaymentRepository.class);

    private final OperationLeaseRepository leases;
    private final PaymentIntentService intents;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Supplier<RecoveryPolicy> policy;
    private final Supplier<StripeDeployment> stripe;
    private final PaymentUncertainty payments;

    OperationLeases(
            OperationLeaseRepository leases,
            PaymentIntentService intents,
            TransactionTemplate tx,
            Clock clock,
            Supplier<RecoveryPolicy> policy,
            Supplier<StripeDeployment> stripe,
            PaymentUncertainty payments) {
        this.leases = leases;
        this.intents = intents;
        this.tx = tx;
        this.clock = clock;
        this.policy = policy;
        this.stripe = stripe;
        this.payments = payments;
    }

    /** Operations waiting for their first send. */
    List<UUID> prepared(int limit, String provider) {
        if (provider == null) return List.of();
        return tx.execute(s -> leases.sendable(provider, stripe.get(), clamp(limit)));
    }

    /** Operations whose recovery task is due. */
    List<UUID> due(int limit, String provider) {
        if (provider == null) return List.of();
        return tx.execute(s -> leases.due(provider, stripe.get(), clock.instant(), clamp(limit)));
    }

    /**
     * Takes the lease on an operation, or returns null if it is not claimable now. A first send ({@code recovery} false)
     * takes only a PREPARED operation; recovery takes a due task and spends one batch of the account's budget.
     */
    Claim claim(UUID id, String provider, boolean recovery) {
        return tx.execute(s -> {
            var target = leases.findTarget(id).orElse(null);
            if (target == null) return null;
            boolean refundTarget = target.refundId() != null;
            UUID parent = refundTarget ? leases.refundPayment(target.refundId()) : target.paymentId();
            leases.lockPayment(parent);
            if (refundTarget) leases.lockRefund(parent, target.refundId());
            var op = leases.lockOperation(id).orElse(null);
            var task = leases.lockTask(id).orElse(null);
            Instant now = clock.instant();
            if (op == null
                    || !OperationLeaseRules.claimable(
                            recovery,
                            str(op, "status"),
                            instant(op, "lease_until"),
                            task == null ? null : str(task, "status"),
                            task == null ? null : instant(task, "available_at"),
                            now)) return null;
            var c = new TenantContext(
                    uuid(op, "organization_id"),
                    uuid(op, "environment_id"),
                    uuid(op, "application_id"),
                    uuid(op, "provider_account_id"),
                    Set.of(str(op, "currency")),
                    false,
                    Set.of());
            intents.requireBinding(c, provider);
            var p = refundTarget ? leases.refundRow(target.refundId()) : leases.paymentRow(parent);
            if (!recovery && "CONFLICT".equals(p.get("resolution_status"))) throw error(ErrorCode.EVIDENCE_CONFLICT);
            if (!recovery
                    && "CREATE_CHECKOUT".equals(op.get("operation_type"))
                    && (p.get("payment_intent_id") != null || number(p, "captured_amount_minor") > 0)) {
                leases.deferToQuery(id, now);
                return null;
            }
            if (!leases.currencyActive(c.organizationId(), c.environmentId(), str(op, "currency")))
                throw error(ErrorCode.CURRENCY_NOT_ENABLED);
            // A shared account row meters recovery batches across processes. Never sleep while holding locks.
            if (recovery && !reserveBatch(c, id, now)) return null;
            UUID token = UUID.randomUUID();
            long version = number(op, "version") + 1;
            var lease = OperationLeaseRules.grant(
                    instant(op, "first_send_at"),
                    instant(op, "retry_until"),
                    now,
                    policy.get().verifiedWindow(provider),
                    policy.get().lease());
            leases.takeLease(id, token, version, lease, now);
            // Original status determines whether the first command is known to be unsent.
            op.put("first_send_at", Timestamp.from(lease.firstSendAt()));
            op.put("retry_until", lease.retryUntil() == null ? null : Timestamp.from(lease.retryUntil()));
            return new Claim(id, parent, token, version, str(op, "operation_type"), op, p);
        });
    }

    private boolean reserveBatch(TenantContext account, UUID operationId, Instant now) {
        Instant next = leases.lockAccountBatch(
                account.organizationId(), account.environmentId(), account.allowedProviderAccountId(), now);
        if (!OperationLeaseRules.batchAvailable(next, now)) {
            leases.deferTaskUntil(operationId, next);
            return false;
        }
        leases.scheduleNextBatch(
                account.organizationId(),
                account.environmentId(),
                account.allowedProviderAccountId(),
                now.plus(policy.get().accountBatchInterval()));
        return true;
    }

    /** Runs inside the caller's transaction. */
    void lockPayment(UUID paymentId) {
        leases.lockPayment(paymentId);
    }

    /** Runs inside the caller's transaction; locks the operation row while the lease is confirmed. */
    boolean owns(Claim claim) {
        return leases.holdsLease(claim.operationId(), claim.token(), claim.version(), clock.instant());
    }

    /** Releases the task's lease and schedules its next attempt, or finishes it. Runs inside the caller's transaction. */
    void schedule(UUID operationId) {
        var op = leases.operationRow(operationId);
        boolean done;
        if (op.refundId() != null) {
            var refund = leases.refundOutcome(op.refundId());
            done = OperationLeaseRules.refundTaskDone(RefundLifecycle.terminal(refund.status()), refund.resolution());
        } else {
            var payment = leases.paymentOutcome(op.paymentId());
            done = OperationLeaseRules.paymentTaskDone(op.status(), op.type(), payment.status(), payment.resolution());
        }
        Instant now = clock.instant();
        leases.reschedule(operationId, done, now.plus(policy.get().delay(operationId, op.attemptCount())), now);
    }

    void escalate(Claim claim, String reason) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            if (owns(claim)) escalateOwned(claim, reason);
            return null;
        });
    }

    /** Caller must retain the business/operation locks after a successful owns() check in this transaction. */
    void escalateOwned(Claim claim, String reason) {
        Instant now = clock.instant();
        Object refundId = claim.operation().get("refund_id");
        if (refundId != null) leases.markRefundUnknown((UUID) refundId, now);
        else
            payments.markUnknown(
                    claim.paymentId(), "CONFLICT".equals(claim.payment().get("resolution_status")));
        leases.escalate(claim.operationId(), reason, now);
        log.warn(
                "Payment recovery escalated operation={} payment={} reason={}",
                claim.operationId(),
                claim.paymentId(),
                reason);
    }

    void alertAge(Claim claim) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            if (owns(claim)) alertAgeOwned(claim);
            return null;
        });
    }

    /** Caller must hold the lease, checked by owns() in this transaction. */
    void alertAgeOwned(Claim claim) {
        leases.alertAge(claim.operationId(), clock.instant());
    }

    /** L2: wakes the tasks of operations whose outcome is still uncertain or unposted; returns how many were found. */
    int wakeL2(int limit, String provider) {
        if (provider == null) return 0;
        var ids = tx.execute(s -> leases.stranded(provider, stripe.get(), clock.instant(), clamp(limit)));
        for (UUID id : ids)
            tx.execute(s -> {
                var target = leases.findTarget(id).orElseThrow();
                UUID parent = target.paymentId() != null ? target.paymentId() : leases.refundPayment(target.refundId());
                leases.lockPayment(parent);
                Map<String, Object> op = leases.lockOperation(id).orElseThrow();
                Instant now = clock.instant();
                if (OperationLeaseRules.wakeable(str(op, "status"), instant(op, "lease_until"), now))
                    leases.wake(id, now);
                return null;
            });
        return ids.size();
    }

    private static int clamp(int limit) {
        return Math.min(100, Math.max(1, limit));
    }
}
