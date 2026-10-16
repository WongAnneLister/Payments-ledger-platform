package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.error;
import static com.paymentsledger.payment.PaymentRepository.str;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.PaymentRepository.Claim;
import com.paymentsledger.payment.PaymentRepository.Observation;
import com.paymentsledger.payment.domain.PaymentEvidenceRules;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.ProviderResultRules;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.payment.repository.PaymentStateRepository;
import com.paymentsledger.payment.repository.ProviderResultRepository;
import com.paymentsledger.payment.repository.ProviderResultRepository.ObservationRow;
import com.paymentsledger.payment.repository.ProviderResultRepository.SessionUpdate;
import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes provider results back onto payments and their operations. Every writeback first locks the payment and records
 * what the provider said; only the current lease holder (owns()) may then change the payment or complete the
 * operation. Query and webhook evidence is merged without taking ownership.
 *
 * <p>Methods documented as running inside the caller's transaction expect the payment lock to be held already.
 */
final class ProviderResults {
    private final ProviderResultRepository results;
    private final PaymentStateRepository states;
    private final OperationLeases leases;
    private final PaymentIntentService intents;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Supplier<RecoveryPolicy> policy;
    private final Supplier<RevenueShareService> shares;

    ProviderResults(
            ProviderResultRepository results,
            PaymentStateRepository states,
            OperationLeases leases,
            PaymentIntentService intents,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock,
            Supplier<RecoveryPolicy> policy,
            Supplier<RevenueShareService> shares) {
        this.results = results;
        this.states = states;
        this.leases = leases;
        this.intents = intents;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.shares = shares;
    }

    Map<String, Object> payment(UUID paymentId) {
        return tx.execute(s -> results.paymentWithOrder(paymentId));
    }

    Map<String, Object> operation(UUID operationId) {
        return tx.execute(s -> results.operation(operationId));
    }

    UUID createOperation(UUID paymentId) {
        return tx.execute(s -> results.createOperationId(paymentId));
    }

    /**
     * Persists provider calls under the operation's scope; {@code token} is null for queries made without a lease. Runs
     * inside the caller's transaction.
     */
    void observations(UUID operationId, UUID paymentId, UUID token, List<Observation> observations) {
        var scope = results.observationScope(operationId);
        for (var observation : observations) {
            var result = observation.result();
            // Persist only project-owned fields; never serialize arbitrary provider payloads or SDK objects.
            var row = new ObservationRow(
                    operationId,
                    token,
                    observation.type(),
                    result.classification().name(),
                    observation.externalId(),
                    encode(result.evidence()),
                    result.payload() == null ? null : encode(ProviderResultRules.redacted(result.payload())));
            if (scope.refundId() != null) results.insertRefundObservation(scope, row);
            else results.insertPaymentObservation(scope, paymentId, row);
        }
    }

    /** Recheck the committed payment immediately before a create call, without holding locks across the provider. */
    boolean deferCreateWithRemoteFact(Claim claim) {
        return tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            if (!leases.owns(claim)) return true;
            var payment = results.progress(claim.paymentId());
            if (!payment.remotelyKnown()) return false;
            completeOperation(
                    claim,
                    "UNKNOWN",
                    payment.resolution().name(),
                    payment.checkoutSessionId(),
                    payment.providerStatus());
            return true;
        });
    }

    void finishCreate(Claim claim, ProviderCallResult<CheckoutSessionFact> result) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            String sessionId =
                    result.payload() == null ? null : result.payload().sessionId();
            observations(
                    claim.operationId(),
                    claim.paymentId(),
                    claim.token(),
                    List.of(new Observation("CREATE_SESSION", result, sessionId)));
            if (!leases.owns(claim)) return null;
            var current = results.progress(claim.paymentId());
            if (current.resolution() == ResolutionStatus.CONFLICT) {
                completeOperation(claim, "UNKNOWN", "CONFLICT", sessionId, null);
                return null;
            }
            if (result.classification() == ProviderCallClassification.DEFINITIVE_REJECT) {
                if (ProviderResultRules.rejectLeftRemoteTrace(current)) {
                    markUnknown(claim.paymentId(), true);
                    completeOperation(claim, "UNKNOWN", "CONFLICT", null, null);
                } else {
                    results.cancelPayment(claim.paymentId(), current.version());
                    completeOperation(claim, "ACKNOWLEDGED", "KNOWN", null, null);
                }
                return null;
            }
            if (!ProviderResultRules.usableSession(result, claim.paymentId())) {
                boolean conflict = ProviderResultRules.unusableResultConflicts(result);
                markUnknown(claim.paymentId(), conflict);
                completeOperation(claim, "UNKNOWN", conflict ? "CONFLICT" : "UNKNOWN", sessionId, null);
                return null;
            }
            var fact = result.payload();
            var payment = results.lockProgress(claim.paymentId());
            if (ProviderResultRules.contradictsAnchors(payment, fact)) {
                markUnknown(claim.paymentId(), true);
                completeOperation(claim, "UNKNOWN", "CONFLICT", null, null);
                return null;
            }
            String providerStatus = PaymentEvidenceRules.sessionStatus(fact);
            results.recordSession(
                    claim.paymentId(),
                    payment.version(),
                    new SessionUpdate(
                            fact,
                            ProviderResultRules.statusAfterSession(fact, payment.status()),
                            providerStatus,
                            clock.instant()));
            completeOperation(claim, "RESOLVED", "KNOWN", fact.sessionId(), providerStatus);
            return null;
        });
    }

    /**
     * Records the operation's outcome, releases its lease and schedules its task. Runs inside the caller's transaction;
     * throws if the lease was lost, which rolls back every write made under it.
     */
    void completeOperation(Claim claim, String status, String resolution, String external, String providerStatus) {
        // owns() is the authorization linearization point while the payment/operation locks are held.
        // Re-reading the wall clock here could reject the operation after payment facts already changed.
        int updated = results.completeOperation(
                claim.operationId(),
                claim.token(),
                claim.version(),
                status,
                resolution,
                external,
                providerStatus,
                clock.instant());
        if (updated != 1)
            throw new IllegalStateException("Lost operation ownership inside locked writeback transaction");
        leases.schedule(claim.operationId());
    }

    /** Runs inside the caller's transaction. */
    void markUnknown(UUID paymentId, boolean conflict) {
        results.markUnknown(paymentId, conflict, clock.instant());
        shares.get().reconcile(paymentId, "payment.resolution");
    }

    /** Merges a query made without a lease; resolves the operation only if no sender owns it. */
    void mergeQuery(UUID paymentId, UUID operationId, List<Observation> observations, PaymentFacts facts) {
        tx.execute(s -> {
            leases.lockPayment(paymentId);
            observations(operationId, paymentId, null, observations);
            boolean applicable = merge(paymentId, facts, true);
            results.stampQuery(operationId, clock.instant());
            // Query evidence never changes ownership or version while a sender owns the operation.
            if (ProviderResultRules.queryResolves(applicable, facts))
                results.resolveQueried(operationId, clock.instant());
            return null;
        });
    }

    /** Called only by the inbox transaction after acquiring the payment lock before the inbox lock. */
    Map<String, Object> lockWebhookPayment(UUID paymentId) {
        requireTransaction("Webhook merge requires transaction");
        return results.lockPaymentRow(paymentId);
    }

    boolean mergeWebhook(UUID paymentId, PaymentFacts facts) {
        requireTransaction("Webhook merge requires transaction");
        return merge(paymentId, facts, false);
    }

    /** Verified reconciliation reads use query ordering; they are not webhook events. */
    boolean mergeReconciliationQuery(UUID paymentId, PaymentFacts facts) {
        requireTransaction("Reconciliation query merge requires transaction");
        return merge(paymentId, facts, true);
    }

    void finishCancel(Claim claim, List<Observation> observations, PaymentFacts facts, boolean canceled) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            observations(claim.operationId(), claim.paymentId(), claim.token(), observations);
            if (!leases.owns(claim)) return null;
            if (observations.stream().anyMatch(o -> ProviderResultRules.isQuery(o.type())))
                results.stampQuery(claim.operationId(), clock.instant());
            boolean applicable = merge(claim.paymentId(), facts, true);
            var payment = results.lockProgress(claim.paymentId());
            var outcome = ProviderResultRules.cancelOutcome(canceled, applicable, payment, facts);
            if (outcome.cancelPayment()) results.cancelPayment(claim.paymentId(), payment.version());
            if (outcome.markConflict()) markUnknown(claim.paymentId(), true);
            completeOperation(
                    claim,
                    outcome.status(),
                    outcome.resolution(),
                    payment.checkoutSessionId(),
                    payment.providerStatus());
            return null;
        });
    }

    /** Cancels locally when the create was never sent: the provider has nothing to cancel. */
    boolean cancelUnsent(Claim claim) {
        return tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            if (!leases.owns(claim)) return false;
            if (!ProviderResultRules.cancelableLocally(results.progress(claim.paymentId()))) return false;
            var create = results.lockCreateOperation(claim.paymentId());
            if (!"PREPARED".equals(create.status())) return false;
            results.resolveUnsentCreate(create.id());
            results.cancelUnsentPayment(claim.paymentId());
            completeOperation(claim, "RESOLVED", "KNOWN", null, null);
            return true;
        });
    }

    /** The account may be used for provider calls: an active application and a valid binding exist. */
    void checkProviderScope(ProviderScope scope, String provider) {
        var currency =
                results.anyPaymentCurrency(scope.organizationId(), scope.environmentId(), scope.providerAccountId());
        var application = results.firstActiveApplication(scope.organizationId(), scope.environmentId())
                .orElseThrow(() -> error(ErrorCode.SERVICE_UNAVAILABLE));
        intents.requireBinding(
                new TenantContext(
                        scope.organizationId(),
                        scope.environmentId(),
                        application,
                        scope.providerAccountId(),
                        Set.of(currency.orElse("USD")),
                        false,
                        Set.of()),
                provider);
    }

    String refundRecoveryAnchor(Claim claim) {
        String bound = str(claim.payment(), "provider_refund_id");
        String external = str(claim.operation(), "external_object_id");
        var observed = tx.execute(s -> results.observedRefunds(
                claim.operationId(), (UUID) claim.operation().get("refund_id")));
        return ProviderResultRules.refundAnchor(bound, external, observed);
    }

    String recoveryAnchor(Claim claim) {
        String bound = str(claim.payment(), "checkout_session_id");
        String external = str(claim.operation(), "external_object_id");
        var observed = tx.execute(s -> results.observedSessions(
                claim.operationId(),
                claim.paymentId(),
                (UUID) claim.operation().get("organization_id"),
                (UUID) claim.operation().get("environment_id"),
                (UUID) claim.operation().get("provider_account_id")));
        return ProviderResultRules.sessionAnchor(bound, external, observed);
    }

    /** Merges a recovery query under the lease, escalating a payment that stayed uncertain too long. */
    void finishRecoveryQuery(Claim claim, ProviderQueryService.Queried query) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            observations(claim.operationId(), claim.paymentId(), claim.token(), query.observations());
            if (!leases.owns(claim)) return null;
            boolean applicable = merge(claim.paymentId(), query.facts(), true);
            var payment = results.progress(claim.paymentId());
            results.stampQuery(claim.operationId(), clock.instant());
            if (ProviderResultRules.ageLimitReached(
                    payment.resolution(),
                    payment.unresolvedSince(),
                    policy.get().unresolvedAge(),
                    clock.instant())) {
                leases.alertAgeOwned(claim);
                leases.escalateOwned(claim, "UNRESOLVED_AGE_LIMIT");
                return null;
            }
            completeOperation(
                    claim,
                    applicable ? "RESOLVED" : "UNKNOWN",
                    payment.resolution().name(),
                    payment.checkoutSessionId(),
                    payment.providerStatus());
            return null;
        });
    }

    /** Recovery found more than one session for the payment: mark it a conflict instead of guessing. */
    void conflictingAnchor(Claim claim) {
        tx.execute(s -> {
            leases.lockPayment(claim.paymentId());
            if (leases.owns(claim)) markUnknown(claim.paymentId(), true);
            return null;
        });
    }

    private boolean merge(UUID paymentId, PaymentFacts facts, boolean query) {
        return new PaymentStateMerger(states, shares.get(), json, clock).merge(paymentId, facts, query);
    }

    private static void requireTransaction(String message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(message);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode controlled payment command", e);
        }
    }
}
