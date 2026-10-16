package com.paymentsledger.payment;

import static com.paymentsledger.payment.domain.PaymentIntentRules.rejected;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantAccessGuard;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.RefundRepository.Intent;
import com.paymentsledger.payment.domain.RefundControlState;
import com.paymentsledger.payment.domain.RefundReservationRules;
import com.paymentsledger.payment.domain.RefundReservationRules.UnfreezeEvidence;
import com.paymentsledger.payment.repository.RefundIntentRepository;
import com.paymentsledger.payment.repository.RefundIntentRepository.NewRefund;
import com.paymentsledger.payment.repository.RefundIntentRepository.StoredIntent;
import com.paymentsledger.provider.ProviderModels.CreateRefundCommand;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserves refunds and unfreezes refund controls. A reservation holds the payment and control locks while it checks
 * the available amount, so concurrent reservations can never together exceed what was captured; no provider call is
 * made here.
 */
final class RefundIntentService {
    private final RefundIntentRepository intents;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BiConsumer<TenantContext, String> binding;
    private final Function<Object, String> encoder;
    private final Supplier<RefundEligibilityPolicy> eligibility;
    private final Supplier<RevenueShareService> shares;

    RefundIntentService(
            RefundIntentRepository intents,
            TransactionTemplate tx,
            Clock clock,
            BiConsumer<TenantContext, String> binding,
            Function<Object, String> encoder,
            Supplier<RefundEligibilityPolicy> eligibility,
            Supplier<RevenueShareService> shares) {
        this.intents = intents;
        this.tx = tx;
        this.clock = clock;
        this.binding = binding;
        this.encoder = encoder;
        this.eligibility = eligibility;
        this.shares = shares;
    }

    Intent prepare(TenantContext tenant, String key, RefundRequests.Create request, String provider) {
        TenantAccessGuard.requireScope(tenant, "refunds:write");
        PaymentRequests.idempotencyKey(key);
        return tx.execute(status -> {
            intents.lock(RefundReservationRules.lockKey(tenant, "CREATE_REFUND", key));
            var prior = intents.findByIdempotencyKey(tenant, key);
            if (prior.isPresent()) return replay(prior.get(), request.fingerprint());
            TenantAccessGuard.requireCurrency(tenant, request.currency());
            binding.accept(tenant, provider);
            if (request.clientRefundKey() != null) {
                intents.lock(RefundReservationRules.lockKey(tenant, "CLIENT_REFUND", request.clientRefundKey()));
                var existing = intents.findByClientRefundKey(tenant, request.clientRefundKey());
                if (existing.isPresent()) {
                    var replay = replay(existing.get(), request.fingerprint());
                    intents.insertIdempotency(
                            tenant,
                            request.currency(),
                            key,
                            new StoredIntent(request.fingerprint(), replay.refundId(), replay.operationId()));
                    return replay;
                }
            }
            var payment = intents.lockPayment(request.paymentId(), tenant).orElse(null);
            RefundReservationRules.requireRefundable(tenant, payment, request.currency());
            var control = intents.lockControl(payment);
            eligibility.get().check(tenant, payment, control);
            RefundReservationRules.requireReservable(control, request.amountMinor());
            UUID refund = PaymentIds.newId(), operation = UUID.randomUUID();
            var command = new CreateRefundCommand(
                    new ProviderScope(
                            tenant.organizationId(), tenant.environmentId(), tenant.allowedProviderAccountId(), false),
                    "refund_create_" + operation,
                    payment.latestChargeId(),
                    null,
                    request.amountMinor(),
                    RefundReservationRules.providerReason(request.reason()));
            Instant now = clock.instant();
            intents.reserve(
                    tenant,
                    new NewRefund(
                            refund,
                            operation,
                            request.paymentId(),
                            request.amountMinor(),
                            request.currency(),
                            request.clientRefundKey(),
                            request.fingerprint(),
                            request.reason(),
                            request.note(),
                            payment.latestChargeId(),
                            command.operationIdempotencyKey(),
                            encoder.apply(command),
                            now.plus(Duration.ofDays(1)),
                            now));
            intents.insertIdempotency(
                    tenant, request.currency(), key, new StoredIntent(request.fingerprint(), refund, operation));
            shares.get().reconcile(request.paymentId(), "refund.reserve:" + refund);
            return new Intent(refund, operation, false);
        });
    }

    /** Conservative audited unfreeze. Unresolved reservations or ambiguous source associations require investigation. */
    void resolveExternalConflict(
            ProviderScope scope, UUID paymentId, long expectedVersion, UUID observationId, String auditKey) {
        RefundReservationRules.requireUnfreezeRequest(scope, auditKey);
        tx.execute(status -> {
            var payment = intents.lockPayment(paymentId, scope).orElseThrow(() -> rejected(ErrorCode.NOT_FOUND));
            var control = intents.lockControl(payment);
            var prior = intents.auditedObservation(paymentId, auditKey);
            if (prior.isPresent()) {
                if (!observationId.equals(prior.get())) throw rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
                return null;
            }
            boolean observationFound = intents.observationExists(observationId, scope, paymentId);
            long unsettled = intents.countUnsettledRefunds(paymentId);
            long succeeded = intents.sumSucceededRefunds(paymentId);
            long unmatched = intents.countUnmatchedObservations(scope, paymentId);
            var evidence = new UnfreezeEvidence(observationFound, unsettled, unmatched, succeeded);
            if (!RefundReservationRules.unfreezable(control, expectedVersion, evidence))
                throw rejected(ErrorCode.EVIDENCE_CONFLICT);
            intents.unfreeze(
                    scope,
                    paymentId,
                    observationId,
                    auditKey,
                    encoder.apply(RefundReservationRules.auditedControl(
                            RefundControlState.EXTERNAL_CONFLICT, control, succeeded, expectedVersion)),
                    encoder.apply(RefundReservationRules.auditedControl(
                            RefundControlState.NORMAL, control, succeeded, expectedVersion + 1)));
            shares.get().reconcile(paymentId, "refund.unfreeze:" + auditKey);
            return null;
        });
    }

    private static Intent replay(StoredIntent stored, String fingerprint) {
        RefundReservationRules.requireSameRequest(stored.fingerprint(), fingerprint);
        return new Intent(stored.refundId(), stored.operationId(), true);
    }
}
