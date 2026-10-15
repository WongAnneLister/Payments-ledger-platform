package com.paymentsledger.payment.domain;

import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionStatus;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure rules for merging provider evidence into a payment's four state dimensions.
 *
 * <ul>
 *   <li>Evidence never moves a payment backwards: a captured payment stays SUCCEEDED, and stale snapshots do not change
 *       business or provider status.
 *   <li>Contradictory evidence is kept as CONFLICT instead of picking a side.
 *   <li>Incomplete evidence makes the payment UNKNOWN, not failed; only a complete query showing an expired, unpaid
 *       session cancels it.
 * </ul>
 */
public final class PaymentEvidenceRules {
    private PaymentEvidenceRules() {}

    /**
     * A snapshot is stale if it was taken for another payment version or is older than the last provider confirmation.
     * Webhooks are ordered differently: a webhook is stale if it predates the last webhook or does not postdate the last
     * successful query, because a query reads current state.
     */
    public static boolean isStale(PaymentSnapshot payment, PaymentFacts facts, boolean query) {
        if (!query) {
            Instant lastWebhook = payment.lastWebhookCreatedAt(), lastQuery = payment.lastQueryConfirmedAt();
            return (lastWebhook != null && facts.confirmedAt().isBefore(lastWebhook))
                    || (lastQuery != null && !facts.confirmedAt().isAfter(lastQuery));
        }
        boolean otherVersion = facts.paymentVersion() != null && facts.paymentVersion() != payment.version();
        boolean olderThanConfirmed = payment.lastProviderConfirmedAt() != null
                && facts.confirmedAt().toEpochMilli()
                        < payment.lastProviderConfirmedAt().toEpochMilli();
        return otherVersion || olderThanConfirmed;
    }

    /**
     * Provider object IDs observed in the evidence. A session or intent different from the one already linked is a
     * conflict and is not adopted; missing links can be filled even from an older snapshot.
     */
    public record Identities(String sessionId, String paymentIntentId, boolean conflict) {}

    public static Identities identities(PaymentSnapshot payment, PaymentFacts facts) {
        String session = facts.session() == null ? null : facts.session().sessionId();
        String intent = facts.intent() != null
                ? facts.intent().paymentIntentId()
                : facts.session() == null ? null : facts.session().paymentIntentId();
        boolean conflict = false;
        if (session != null && payment.checkoutSessionId() != null && !session.equals(payment.checkoutSessionId())) {
            conflict = true;
            session = null;
        }
        if (intent != null && payment.paymentIntentId() != null && !intent.equals(payment.paymentIntentId())) {
            conflict = true;
            intent = null;
        }
        return new Identities(session, intent, conflict);
    }

    public enum ChargeDecision {
        /** The charge contradicts what is already recorded for it. */
        CONFLICT,
        /** An older uncaptured observation of a known charge adds nothing. */
        SKIP_OUTDATED,
        RECORD
    }

    public static ChargeDecision charge(
            UUID paymentId, ChargeFact charge, ChargeAttempt previous, Instant confirmedAt) {
        if (previous == null) return ChargeDecision.RECORD;
        boolean otherBalanceTransaction = previous.balanceTransactionId() != null
                && charge.balanceTransactionId() != null
                && !charge.balanceTransactionId().equals(previous.balanceTransactionId());
        boolean otherCapturedAmount = previous.captured()
                && charge.captured()
                && !Objects.equals(charge.amountCapturedMinor(), previous.amountCapturedMinor());
        if (otherBalanceTransaction
                || !paymentId.equals(previous.paymentId())
                || !Objects.equals(charge.paymentIntentId(), previous.paymentIntentId())
                || !charge.currency().equalsIgnoreCase(previous.currency())
                || charge.amountMinor() != previous.amountMinor()
                || otherCapturedAmount) return ChargeDecision.CONFLICT;
        if (!charge.captured() && confirmedAt.isBefore(previous.fetchedAt())) return ChargeDecision.SKIP_OUTDATED;
        return ChargeDecision.RECORD;
    }

    /** A succeeded capture is also recorded as a normalized fact, so a different capture of the same charge conflicts. */
    public static boolean confirmsCapture(ChargeFact charge) {
        return charge.captured() && charge.status().equals("succeeded");
    }

    public static String captureFactKey(ChargeFact charge) {
        return "CAPTURE:" + charge.chargeId();
    }

    public static Map<String, Object> captureFact(UUID paymentId, ChargeFact charge) {
        var fact = new LinkedHashMap<String, Object>();
        fact.put("charge_id", charge.chargeId());
        fact.put("payment_intent_id", charge.paymentIntentId());
        fact.put("payment_id", paymentId.toString());
        fact.put("amount_minor", charge.amountMinor());
        fact.put("amount_captured_minor", charge.amountCapturedMinor());
        fact.put("currency", charge.currency().toUpperCase(Locale.ROOT));
        return fact;
    }

    /**
     * The merged state. {@code session} and {@code intent} are null for stale evidence, so stale snapshots do not
     * update the hosted URL or the latest charge.
     */
    public record StateDecision(
            PaymentStatus status,
            String providerStatus,
            ResolutionStatus resolution,
            FinancialStatus financial,
            CheckoutSessionFact session,
            PaymentIntentFact intent) {}

    /**
     * @param capturedMinor total of succeeded captured attempts, after this evidence was recorded
     * @param postedChargeMinor total of posted charge balance transactions; read only when something was captured
     */
    public static StateDecision decide(
            PaymentSnapshot payment,
            PaymentFacts facts,
            boolean query,
            boolean stale,
            boolean conflict,
            long capturedMinor,
            Long postedChargeMinor) {
        PaymentStatus status = payment.status();
        String providerStatus = payment.providerStatus();
        FinancialStatus financial = payment.financial();
        if (capturedMinor > 0) {
            // A capture is a fact: it moves the payment to SUCCEEDED, but cannot overwrite a cancellation.
            if (status == PaymentStatus.CANCELED) conflict = true;
            else status = PaymentStatus.SUCCEEDED;
            financial = financialStatus(financial, capturedMinor, postedChargeMinor);
            if (capturedMinor > payment.amountMinor()) conflict = true;
        } else if (!stale && status != PaymentStatus.SUCCEEDED && status != PaymentStatus.CANCELED) {
            if (facts.intent() != null) {
                providerStatus = facts.intent().status();
                status = uncapturedStatus(providerStatus, facts.session());
            } else if (facts.session() != null) {
                providerStatus = sessionStatus(facts.session());
                status = facts.session().status() == CheckoutSessionStatus.OPEN
                        ? PaymentStatus.AWAITING_CUSTOMER
                        : PaymentStatus.PROCESSING;
            }
        }
        if (query
                && !stale
                && !conflict
                && capturedMinor == 0
                && status != PaymentStatus.SUCCEEDED
                && unpaid(facts)
                && facts.session().status() == CheckoutSessionStatus.EXPIRED) status = PaymentStatus.CANCELED;
        if (!stale && facts.intent() != null) providerStatus = facts.intent().status();
        ResolutionStatus resolution = conflict
                ? ResolutionStatus.CONFLICT
                : stale ? payment.resolution() : facts.complete() ? ResolutionStatus.KNOWN : ResolutionStatus.UNKNOWN;
        return new StateDecision(
                status,
                providerStatus,
                resolution,
                financial,
                stale ? null : facts.session(),
                stale ? null : facts.intent());
    }

    /** Captured money is POSTED only when posted charge transactions cover it exactly; more is a conflict. */
    static FinancialStatus financialStatus(FinancialStatus current, long capturedMinor, long postedChargeMinor) {
        if (current == FinancialStatus.CONFLICT || postedChargeMinor > capturedMinor) return FinancialStatus.CONFLICT;
        if (postedChargeMinor == capturedMinor) return FinancialStatus.POSTED;
        return postedChargeMinor > 0 ? FinancialStatus.PARTIALLY_POSTED : FinancialStatus.PENDING_EVIDENCE;
    }

    static PaymentStatus uncapturedStatus(String intentStatus, CheckoutSessionFact session) {
        return switch (intentStatus) {
            case "requires_action" -> PaymentStatus.REQUIRES_ACTION;
            case "requires_payment_method" ->
                session != null && session.status() == CheckoutSessionStatus.OPEN
                        ? PaymentStatus.AWAITING_CUSTOMER
                        : PaymentStatus.PROCESSING;
            default -> PaymentStatus.PROCESSING;
        };
    }

    /** True if the snapshot itself should be recorded as the latest provider confirmation. */
    public static boolean confirmsProviderState(PaymentFacts facts, boolean stale) {
        return !stale && facts.complete() && !facts.conflict();
    }

    /** A query always refreshes the hosted URL; a webhook only when it carries one. */
    public static boolean updatesHostedUrl(CheckoutSessionFact session, boolean query) {
        return session != null && (query || session.hostedUrl() != null);
    }

    public static String sessionStatus(CheckoutSessionFact session) {
        return session.status().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Complete evidence that no money moved: the session is not paid, no charge was captured or is still pending, and the
     * intent (if any) received nothing.
     */
    public static boolean unpaid(PaymentFacts facts) {
        if (!facts.complete()
                || facts.conflict()
                || facts.session() == null
                || "paid".equals(facts.session().paymentStatus())) return false;
        if (facts.charges().stream().anyMatch(c -> c.captured() || !c.status().equals("failed"))) return false;
        var intent = facts.intent();
        return intent == null
                || Set.of("requires_payment_method", "canceled").contains(intent.status())
                        && intent.amountReceivedMinor() != null
                        && intent.amountReceivedMinor() == 0;
    }
}
