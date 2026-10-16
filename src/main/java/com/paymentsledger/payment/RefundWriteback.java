package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.uuid;
import static com.paymentsledger.payment.domain.PaymentIntentRules.rejected;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.payment.PaymentRepository.Claim;
import com.paymentsledger.payment.PaymentRepository.Observation;
import com.paymentsledger.payment.domain.LocalRefund;
import com.paymentsledger.payment.domain.ProviderResultRules;
import com.paymentsledger.payment.domain.RefundFactRules;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.payment.repository.RefundFactRepository;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes back the results of leased refund operations. Each writeback takes the payment, control and refund locks,
 * records the provider's answer, and changes state only while the lease is held (owns()), the common linearization
 * point with payment operations.
 *
 * <p>{@code operations} is a Spring proxy in production: only its methods may be used, never its fields.
 */
final class RefundWriteback {
    private final RefundFactRepository facts;
    private final RefundFactMerger merger;
    private final PaymentRepository operations;
    private final Supplier<RevenueShareService> shares;

    RefundWriteback(
            RefundFactRepository facts,
            RefundFactMerger merger,
            PaymentRepository operations,
            Supplier<RevenueShareService> shares) {
        this.facts = facts;
        this.merger = merger;
        this.operations = operations;
        this.shares = shares;
    }

    /** Freeze contradictory remote identities only at the common owned-lease linearization point. */
    void conflictingAnchors(Claim claim) {
        operations.transaction(() -> {
            UUID id = uuid(claim.operation(), "refund_id");
            lock(id);
            if (!operations.owns(claim)) return null;
            facts.freezeControlStamped(claim.paymentId());
            facts.markConflict(id, operations.now());
            // Ownership was checked under the retained operation lock. Do not recheck wall-clock expiry after changing
            // business state.
            shares.get().reconcile(claim.paymentId(), "refund.anchor-conflict");
            operations.escalateOwned(claim, "CONFLICTING_REFUND_ANCHORS");
            return null;
        });
    }

    /** Writes back a create ({@code query} false) or query result; escalates a refund uncertain for too long. */
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
            if (query) facts.stampQuery(claim.operationId(), operations.now());
            var current = facts.refund(id);
            boolean carriesFact = ProviderResultRules.carriesFact(result);
            if (carriesFact) merger.acceptLocked(scope(current), result.payload(), result.evidence(), id);
            else if (RefundFactRules.rejectionReleasesHold(query, result.classification(), current))
                facts.failRejected(current);
            else if (!current.terminal()) facts.markUnknown(id, operations.now());
            if (!carriesFact) shares.get().reconcile(claim.paymentId(), "refund.operation:" + claim.operationId());
            current = facts.refund(id);
            if (ProviderResultRules.ageLimitReached(
                    current.resolution(),
                    current.unresolvedSince(),
                    operations.recoveryPolicy().unresolvedAge(),
                    operations.now())) {
                operations.alertAge(claim);
                operations.escalate(claim, "UNRESOLVED_AGE_LIMIT");
                return null;
            }
            operations.completeOperation(
                    claim,
                    current.resolution() == ResolutionStatus.KNOWN ? "RESOLVED" : "UNKNOWN",
                    current.resolution().name(),
                    current.providerRefundId(),
                    current.providerStatus());
            return null;
        });
    }

    /** Marks the reservation SUBMITTED right before the create call; false if it must not be sent. */
    boolean submitted(Claim claim) {
        return operations.transaction(() -> {
            UUID id = uuid(claim.operation(), "refund_id");
            lock(id);
            if (!operations.owns(claim)) return false;
            var current = facts.refund(id);
            if (!RefundFactRules.sendable(current, facts.controlState(current.paymentId()))) {
                operations.completeOperation(
                        claim,
                        current.terminal() ? "RESOLVED" : "UNKNOWN",
                        current.resolution().name(),
                        current.providerRefundId(),
                        current.providerStatus());
                return false;
            }
            facts.submit(id);
            return true;
        });
    }

    private void lock(UUID refundId) {
        if (!facts.lockForWriteback(refundId)) throw rejected(ErrorCode.NOT_FOUND);
    }

    private static ProviderScope scope(LocalRefund refund) {
        return new ProviderScope(refund.organizationId(), refund.environmentId(), refund.providerAccountId(), false);
    }
}
