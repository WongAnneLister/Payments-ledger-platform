package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.payment.domain.PaymentEvidenceRules;
import com.paymentsledger.payment.domain.PaymentEvidenceRules.StateDecision;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.PaymentSnapshot;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.payment.repository.PaymentStateRepository;
import com.paymentsledger.payment.repository.PaymentStateRepository.StateUpdate;
import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.Clock;
import java.util.UUID;

/**
 * Merges provider evidence (query, webhook or reconciliation read) into a payment within the caller's transaction.
 *
 * <p>Order: lock the payment, then its revenue shares and financial sources; record each charge as an attempt; read the
 * captured and posted amounts; write the merged state fenced by the locked version; let revenue sharing react.
 */
final class PaymentStateMerger {
    private final PaymentStateRepository states;
    private final RevenueShareService shares;
    private final ObjectMapper json;
    private final Clock clock;

    PaymentStateMerger(PaymentStateRepository states, RevenueShareService shares, ObjectMapper json, Clock clock) {
        this.states = states;
        this.shares = shares;
        this.json = json;
        this.clock = clock;
    }

    /** Returns true if the merged evidence leaves the payment KNOWN and was not stale. */
    boolean merge(UUID paymentId, PaymentFacts facts, boolean query) {
        PaymentSnapshot payment = states.lockPayment(paymentId);
        if (shares.enabled()) {
            shares.lock(paymentId);
            states.lockFinancialSources(
                    paymentId,
                    facts.charges().stream().map(ChargeFact::chargeId).toList());
        }
        boolean stale = PaymentEvidenceRules.isStale(payment, facts, query);
        var identities = PaymentEvidenceRules.identities(payment, facts);
        boolean conflict =
                facts.conflict() || payment.resolution() == ResolutionStatus.CONFLICT || identities.conflict();
        for (ChargeFact charge : facts.charges()) if (!recordCharge(payment, charge, facts, query)) conflict = true;

        long captured = states.capturedAmount(paymentId);
        Long posted = captured > 0 ? states.postedChargeAmount(paymentId) : null;
        StateDecision decision = PaymentEvidenceRules.decide(payment, facts, query, stale, conflict, captured, posted);
        var session = decision.session();
        states.update(
                payment,
                new StateUpdate(
                        decision.status(),
                        decision.providerStatus(),
                        decision.resolution(),
                        clock.instant(),
                        decision.financial(),
                        captured,
                        identities.sessionId(),
                        identities.paymentIntentId(),
                        decision.intent() == null ? null : decision.intent().latestChargeId(),
                        PaymentEvidenceRules.updatesHostedUrl(session, query),
                        session == null ? null : session.hostedUrl(),
                        PaymentEvidenceRules.confirmsProviderState(facts, stale),
                        facts.confirmedAt()));
        shares.reconcile(paymentId, "payment.observation");
        if (query && PaymentEvidenceRules.confirmsProviderState(facts, stale))
            states.markQueryConfirmed(paymentId, facts.confirmedAt());
        if (!query && !stale) states.markWebhookObserved(paymentId, facts.confirmedAt());
        return !stale && decision.resolution() == ResolutionStatus.KNOWN;
    }

    /** Records one charge observation; returns false if it contradicts what is already recorded. */
    private boolean recordCharge(PaymentSnapshot payment, ChargeFact charge, PaymentFacts facts, boolean query) {
        var previous = states.findAttempt(payment, charge.chargeId()).orElse(null);
        switch (PaymentEvidenceRules.charge(payment.id(), charge, previous, facts.confirmedAt())) {
            case CONFLICT -> {
                return false;
            }
            case SKIP_OUTDATED -> {
                return true;
            }
            case RECORD -> {}
        }
        if (PaymentEvidenceRules.confirmsCapture(charge)) {
            int accepted = states.recordCapture(
                    payment,
                    PaymentEvidenceRules.captureFactKey(charge),
                    charge,
                    encode(PaymentEvidenceRules.captureFact(payment.id(), charge)),
                    query ? "QUERY" : "WEBHOOK",
                    facts.confirmedAt());
            if (accepted == 0) return false;
        }
        return states.upsertAttempt(payment, charge, facts.confirmedAt()) != 0;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode controlled payment command", e);
        }
    }
}
