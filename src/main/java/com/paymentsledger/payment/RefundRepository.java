package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.payment.PaymentRepository.Claim;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refund entry point: reservations and the audited unfreeze ({@link RefundIntentService}), the read model
 * ({@link RefundReader}), provider facts ({@link RefundFactMerger}) and leased operation writeback
 * ({@link RefundWriteback}). Payment/control locks serialize reservations; authoritative observations never perform
 * provider I/O.
 */
@Repository
public class RefundRepository {
    private com.paymentsledger.dispute.DisputeService disputes;
    private RevenueShareService shares = RevenueShareService.disabled();
    private final TransactionTemplate tx;
    private final PaymentRepository operations;
    private final RefundIntentService intents;
    private final RefundReader reader;
    private final RefundFactMerger facts;
    private final RefundWriteback writeback;
    private RefundEligibilityPolicy eligibility = RefundEligibilityPolicy.unrestricted();

    public RefundRepository(
            PaymentRepositories store, PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
        this(store, manager, json, clock, new PaymentRepository(store, manager, json, clock));
    }

    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations) {
        this.tx = new TransactionTemplate(manager);
        this.operations = operations;
        this.intents = new RefundIntentService(
                store.refundIntents(),
                tx,
                // A method reference, not the field: Spring proxies PaymentRepository and a proxy's fields are unset.
                operations::now,
                operations::checkBinding,
                operations::encode,
                () -> eligibility,
                () -> shares);
        this.reader = new RefundReader(store.refundRecords(), tx);
        this.facts = new RefundFactMerger(
                store.refundFacts(),
                store.refundIntents(),
                tx,
                operations::now,
                operations::encode,
                () -> shares,
                () -> disputes);
        this.writeback = new RefundWriteback(store.refundFacts(), facts, operations, () -> shares);
    }

    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies) {
        this(store, manager, json, clock, operations);
        this.eligibility = policies.getIfAvailable(RefundEligibilityPolicy::unrestricted);
    }

    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            RevenueShareService shares) {
        this(store, tx, json, clock, operations);
        this.shares = shares;
    }

    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,
            RevenueShareService shares) {
        this(store, tx, json, clock, operations, policies);
        this.shares = shares;
    }

    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            RevenueShareService shares,
            com.paymentsledger.dispute.DisputeService disputes) {
        this(store, tx, json, clock, operations, shares);
        this.disputes = disputes;
        this.eligibility = (tenant, payment, control) -> {
            if (disputes.blocks(payment.id())) throw error(ErrorCode.REFUND_BLOCKED);
        };
    }

    @Autowired
    public RefundRepository(
            PaymentRepositories store,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            PaymentRepository operations,
            org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,
            RevenueShareService shares,
            com.paymentsledger.dispute.DisputeService disputes) {
        this(store, tx, json, clock, operations, policies, shares);
        this.disputes = disputes;
    }

    public record Intent(UUID refundId, UUID operationId, boolean replay) {}

    public Intent prepare(TenantContext c, String key, RefundRequests.Create request, String provider) {
        return intents.prepare(c, key, request, provider);
    }

    /** Conservative audited unfreeze. Unresolved reservations or ambiguous source associations require investigation. */
    public void resolveExternalConflict(
            ProviderScope scope, UUID paymentId, long expectedVersion, UUID observationId, String auditKey) {
        intents.resolveExternalConflict(scope, paymentId, expectedVersion, observationId, auditKey);
    }

    public Map<String, Object> read(TenantContext c, UUID id) {
        return reader.read(c, id);
    }

    public Map<String, Object> lookup(ProviderScope scope, String providerRefundId) {
        return facts.lookup(scope, providerRefundId);
    }

    public UUID paymentForCharge(ProviderScope scope, String chargeId) {
        return facts.paymentForCharge(scope, chargeId);
    }

    /** Caller authenticates provider scope; null local id never guesses an ambiguous reservation by amount. */
    public UUID accept(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        return facts.accept(scope, fact, evidence, localRefundId);
    }

    public void recordSourceQuery(ProviderScope scope, String providerRefundId, ProviderCallResult<RefundFact> result) {
        facts.recordSourceQuery(scope, providerRefundId, result);
    }

    /** Freeze contradictory remote identities only at the common owned-lease linearization point. */
    void conflictingAnchors(Claim claim) {
        writeback.conflictingAnchors(claim);
    }

    void finish(Claim claim, ProviderCallResult<RefundFact> result, boolean query) {
        writeback.finish(claim, result, query);
    }

    boolean submitted(Claim claim) {
        return writeback.submitted(claim);
    }

    static ProviderScope scope(Map<String, Object> row) {
        return new ProviderScope(
                uuid(row, "organization_id"), uuid(row, "environment_id"), uuid(row, "provider_account_id"), false);
    }
}
