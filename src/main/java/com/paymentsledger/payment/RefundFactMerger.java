package com.paymentsledger.payment;

import static com.paymentsledger.payment.domain.PaymentIntentRules.rejected;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.dispute.DisputeService;
import com.paymentsledger.payment.domain.LocalRefund;
import com.paymentsledger.payment.domain.RefundFactRules;
import com.paymentsledger.payment.repository.RefundFactRepository;
import com.paymentsledger.payment.repository.RefundFactRepository.FactUpdate;
import com.paymentsledger.payment.repository.RefundIntentRepository;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merges provider refund facts (webhooks, queries, create results) into local refunds. Every fact is recorded as an
 * observation first. A fact that cannot be attributed to a captured payment is only observed; one for a refund this
 * platform never reserved creates an external refund, freezes the control and schedules a query that follows it to a
 * terminal status. Authoritative observations never perform provider I/O.
 */
final class RefundFactMerger {
    private final RefundFactRepository facts;
    private final RefundIntentRepository intents;
    private final TransactionTemplate tx;
    private final Supplier<Instant> now;
    private final Function<Object, String> encoder;
    private final Supplier<RevenueShareService> shares;
    private final Supplier<DisputeService> disputes;
    private final BiConsumer<UUID, List<String>> financialSourceLocks;

    RefundFactMerger(
            RefundFactRepository facts,
            RefundIntentRepository intents,
            TransactionTemplate tx,
            Supplier<Instant> now,
            Function<Object, String> encoder,
            Supplier<RevenueShareService> shares,
            Supplier<DisputeService> disputes,
            BiConsumer<UUID, List<String>> financialSourceLocks) {
        this.facts = facts;
        this.intents = intents;
        this.tx = tx;
        this.now = now;
        this.encoder = encoder;
        this.shares = shares;
        this.disputes = disputes;
        this.financialSourceLocks = financialSourceLocks;
    }

    Map<String, Object> lookup(ProviderScope scope, String providerRefundId) {
        requireTestMode(scope);
        return facts.lookupRow(scope, providerRefundId);
    }

    UUID paymentForCharge(ProviderScope scope, String chargeId) {
        requireTestMode(scope);
        return facts.paymentForCharge(scope, chargeId);
    }

    /** Caller authenticates provider scope; null local id never guesses an ambiguous reservation by amount. */
    UUID accept(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        return tx.execute(status -> acceptLocked(scope, fact, evidence, localRefundId));
    }

    /** Runs inside the caller's transaction; returns the refund the fact was applied to, or null if only observed. */
    UUID acceptLocked(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        UUID accepted = merge(scope, fact, evidence, localRefundId);
        UUID parent = accepted == null
                ? paymentForCharge(scope, fact.chargeId())
                : facts.refund(accepted).paymentId();
        if (parent != null) {
            if (disputes.get() != null) disputes.get().observeRefundOverlap(parent);
            shares.get().reconcile(parent, "refund.fact:" + fact.refundId());
        }
        return accepted;
    }

    /** Records a source query; a query that returned a fact is accepted like any other fact. */
    void recordSourceQuery(ProviderScope scope, String providerRefundId, ProviderCallResult<RefundFact> result) {
        if (result.payload() != null) {
            accept(scope, result.payload(), result.evidence(), null);
            return;
        }
        tx.execute(status -> {
            var fact = new LinkedHashMap<String, Object>();
            fact.put("refundId", providerRefundId);
            fact.put("classification", result.classification().name());
            facts.observeQuery(scope, providerRefundId, encoder.apply(fact), encoder.apply(result.evidence()));
            return null;
        });
    }

    private UUID merge(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID localRefundId) {
        requireTestMode(scope);
        UUID paymentId = paymentForCharge(scope, fact.chargeId());
        var bound = facts.findByProviderId(scope, fact.refundId()).orElse(null);
        if (bound != null && localRefundId == null) localRefundId = bound.id();
        var local = localRefundId == null
                ? null
                : facts.findInScope(localRefundId, scope).orElse(null);
        if (local != null) paymentId = local.paymentId();
        if (paymentId == null) {
            observe(scope, fact, evidence, null, null);
            return null;
        }
        var payment = facts.lockPayment(paymentId);
        intents.lockControl(payment);
        if (shares.get().enabled()) {
            shares.get().lock(paymentId);
            financialSourceLocks.accept(paymentId, List.of(fact.refundId()));
        }
        bound = facts.findByProviderId(scope, fact.refundId()).orElse(null);
        if (bound != null && local == null) local = bound;
        if (RefundFactRules.boundElsewhere(bound, local)) {
            observe(scope, fact, evidence, local.id(), paymentId);
            facts.freezeControl(paymentId);
            facts.markBindingConflict(local.id(), now.get());
            return local.id();
        }
        if (local != null) local = facts.lockRefund(local.id());
        if (local == null) {
            // Preserve genuine external facts even when R + H exceeds C. Freeze before changing amounts.
            facts.freezeControlStamped(paymentId);
            if (!fact.currency().equalsIgnoreCase(payment.currency())) {
                observe(scope, fact, evidence, null, null);
                return null;
            }
            UUID id = PaymentIds.newId();
            facts.insertExternal(id, payment, fact);
            local = facts.refund(id);
            scheduleExternalQuery(local, scope);
        }
        UUID refundId = local.id();
        observe(scope, fact, evidence, refundId, paymentId);
        var merge = RefundFactRules.merge(local, fact);
        if (merge.conflict()) {
            facts.freezeControl(paymentId);
            facts.markConflict(refundId, now.get());
            return refundId;
        }
        var settlement = merge.settlement();
        if (settlement != null) facts.settle(paymentId, settlement.releasedMinor(), settlement.refundedMinor());
        // Older pending evidence can add BT references but must not regress a terminal state or provider status.
        facts.applyFact(
                refundId, new FactUpdate(merge.status(), merge.recognized(), merge.providerStatus(), fact, now.get()));
        // A webhook/query may converge business facts, but cannot steal an active sender's operation lease.
        facts.wakeTasks(refundId, now.get());
        return refundId;
    }

    private void scheduleExternalQuery(LocalRefund refund, ProviderScope scope) {
        UUID operation = UUID.randomUUID();
        var command = Map.of("scope", scope, "refundId", refund.providerRefundId());
        facts.insertQueryOperation(
                refund, operation, encoder.apply(command), now.get().plus(Duration.ofDays(1)), now.get());
    }

    private void observe(ProviderScope scope, RefundFact fact, ProviderEvidence evidence, UUID refund, UUID payment) {
        facts.observe(scope, fact.refundId(), refund, payment, encoder.apply(fact), encoder.apply(evidence));
    }

    private static void requireTestMode(ProviderScope scope) {
        if (scope.livemode()) throw rejected(ErrorCode.FORBIDDEN);
    }
}
