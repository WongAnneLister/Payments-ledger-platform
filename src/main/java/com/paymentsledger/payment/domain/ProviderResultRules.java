package com.paymentsledger.payment.domain;

import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionStatus;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure rules for writing provider results back onto payments and operations.
 *
 * <ul>
 *   <li>A provider answer is trusted only when it carries a fact (SUCCESS or AUTH_REQUIRED). Anything else leaves the
 *       payment UNKNOWN, or CONFLICT when the provider answered with something that cannot be accepted.
 *   <li>A payment is never rebound: a session or intent that differs from the one already recorded is a conflict.
 *   <li>Recovery queries one anchor. More than one candidate session or refund is a conflict, never a guess.
 * </ul>
 */
public final class ProviderResultRules {
    public static final String CONFLICTING_SESSION_ANCHORS = "CONFLICTING_SESSION_ANCHORS";
    public static final String CONFLICTING_REFUND_ANCHORS = "CONFLICTING_REFUND_ANCHORS";

    private ProviderResultRules() {}

    /** The provider stands behind the result's payload. */
    public static boolean carriesFact(ProviderCallResult<?> result) {
        return result.payload() != null
                && (result.classification() == ProviderCallClassification.SUCCESS
                        || result.classification() == ProviderCallClassification.AUTH_REQUIRED);
    }

    public static boolean matchesMetadata(Map<String, String> metadata, UUID paymentId) {
        return paymentId.toString().equals(metadata.get("payment_id"));
    }

    /** A rejected create may cancel the payment only if nothing of it ever reached the provider. */
    public static boolean rejectLeftRemoteTrace(PaymentProgress payment) {
        return payment.capturedMinor() > 0 || payment.checkoutSessionId() != null || payment.paymentIntentId() != null;
    }

    /** A session is recorded only if it names this payment, expires, and, while open, has a hosted URL. */
    public static boolean usableSession(ProviderCallResult<CheckoutSessionFact> result, UUID paymentId) {
        var fact = result.payload();
        return carriesFact(result)
                && matchesMetadata(fact.metadata(), paymentId)
                && fact.expiresAt() != null
                && (fact.status() != CheckoutSessionStatus.OPEN
                        || fact.hostedUrl() != null && !fact.hostedUrl().isBlank());
    }

    /** The payment is already bound to another session or payment intent than the provider returned. */
    public static boolean contradictsAnchors(PaymentProgress payment, CheckoutSessionFact fact) {
        return payment.checkoutSessionId() != null
                        && !payment.checkoutSessionId().equals(fact.sessionId())
                || payment.paymentIntentId() != null
                        && fact.paymentIntentId() != null
                        && !payment.paymentIntentId().equals(fact.paymentIntentId());
    }

    /** A terminal status stands; otherwise an open session awaits the customer and any other is processing. */
    public static PaymentStatus statusAfterSession(CheckoutSessionFact fact, PaymentStatus current) {
        if (current == PaymentStatus.SUCCEEDED || current == PaymentStatus.CANCELED) return current;
        return fact.status() == CheckoutSessionStatus.OPEN ? PaymentStatus.AWAITING_CUSTOMER : PaymentStatus.PROCESSING;
    }

    /** An unusable create result conflicts if the provider answered with something that cannot be accepted. */
    public static boolean unusableResultConflicts(ProviderCallResult<?> result) {
        return carriesFact(result)
                || result.classification() == ProviderCallClassification.CONTRACT_MISMATCH
                || result.classification() == ProviderCallClassification.IDEMPOTENCY_CONFLICT;
    }

    /** Calls that read provider state rather than change it. */
    public static boolean isQuery(String callType) {
        return callType.startsWith("QUERY_") || callType.equals("LIST_CHARGE_ATTEMPTS");
    }

    /** A query resolves its operation only with complete, consistent evidence that settled the payment. */
    public static boolean queryResolves(boolean applicable, PaymentFacts facts) {
        return applicable && facts.complete() && !facts.conflict();
    }

    /** An unsent create may be canceled locally while the provider knows nothing of the payment. */
    public static boolean cancelableLocally(PaymentProgress payment) {
        return payment.resolution() != ResolutionStatus.CONFLICT && !payment.remotelyKnown();
    }

    /**
     * What a cancel attempt established.
     *
     * @param cancelPayment mark the payment CANCELED: the cancel succeeded with complete evidence and nothing captured
     * @param markConflict the provider reported a cancel although money was captured
     */
    public record CancelOutcome(boolean cancelPayment, boolean markConflict, String status, String resolution) {}

    public static CancelOutcome cancelOutcome(
            boolean canceled, boolean applicable, PaymentProgress payment, PaymentFacts facts) {
        boolean captured = payment.capturedMinor() > 0;
        boolean conflict = payment.resolution() == ResolutionStatus.CONFLICT || facts.conflict();
        boolean resolved = applicable && facts.complete() && !conflict && (captured || canceled);
        return new CancelOutcome(
                canceled && applicable && !captured && !conflict && facts.complete(),
                canceled && captured,
                resolved ? "RESOLVED" : "UNKNOWN",
                resolved ? "KNOWN" : captured || conflict ? "CONFLICT" : "UNKNOWN");
    }

    /** The checkout session to query: the bound one, the operation's, or the one observed; null if none. */
    public static String sessionAnchor(String bound, String operationExternal, Collection<String> observed) {
        var anchors = new LinkedHashSet<String>();
        if (bound != null) anchors.add(bound);
        if (operationExternal != null && operationExternal.startsWith("cs_")) anchors.add(operationExternal);
        anchors.addAll(observed);
        return single(anchors, CONFLICTING_SESSION_ANCHORS);
    }

    /** The provider refund to query: the bound one, the operation's, or the one observed; null if none. */
    public static String refundAnchor(String bound, String operationExternal, Collection<String> observed) {
        var anchors = new LinkedHashSet<String>();
        if (bound != null) anchors.add(bound);
        if (operationExternal != null) anchors.add(operationExternal);
        anchors.addAll(observed);
        return single(anchors, CONFLICTING_REFUND_ANCHORS);
    }

    private static String single(Set<String> anchors, String conflict) {
        if (anchors.size() > 1) return conflict;
        return anchors.isEmpty() ? null : anchors.iterator().next();
    }

    /** Automatic recovery stops once a payment has stayed uncertain for the configured age. */
    public static boolean ageLimitReached(
            ResolutionStatus resolution, Instant unresolvedSince, Duration limit, Instant now) {
        return resolution != ResolutionStatus.KNOWN
                && unresolvedSince != null
                && !unresolvedSince.plus(limit).isAfter(now);
    }

    /** The project-owned fields of a provider payload that may be persisted; never SDK objects or hosted URLs. */
    public static Object redacted(Object value) {
        if (value == null) return null;
        if (value instanceof CheckoutSessionFact f)
            return Map.of(
                    "sessionId",
                    f.sessionId(),
                    "status",
                    f.status(),
                    "paymentStatus",
                    Objects.toString(f.paymentStatus(), ""),
                    "paymentIntentId",
                    Objects.toString(f.paymentIntentId(), ""));
        if (value instanceof PaymentIntentFact f) {
            var fact = new LinkedHashMap<String, Object>();
            fact.put("paymentIntentId", f.paymentIntentId());
            fact.put("status", f.status());
            fact.put("amountMinor", f.amountMinor());
            fact.put("currency", f.currency());
            fact.put("amountReceivedMinor", f.amountReceivedMinor());
            fact.put("chargeIds", f.chargeIds());
            return fact;
        }
        if (value instanceof ChargeFact f) return f;
        if (value instanceof RefundFact f) return f;
        if (value instanceof List<?> list)
            return list.stream().map(ProviderResultRules::redacted).toList();
        throw new IllegalArgumentException("Unrecognized provider observation payload");
    }
}
