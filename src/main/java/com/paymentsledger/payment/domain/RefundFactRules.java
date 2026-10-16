package com.paymentsledger.payment.domain;

import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import java.util.Map;
import java.util.Objects;

/**
 * Pure rules for merging provider refund facts into local refunds.
 *
 * <ul>
 *   <li>A fact that contradicts the refund's amount, currency, charge or provider identities, or moves it from one
 *       terminal status to another, is a conflict: the refund is marked CONFLICT and the control frozen.
 *   <li>Terminal status is final. Older non-terminal facts may still add balance-transaction references but never
 *       regress the status or the provider status.
 *   <li>Reaching a terminal status settles the reservation exactly once: a local refund releases its hold, and only a
 *       success adds to the refunded amount.
 * </ul>
 */
public final class RefundFactRules {
    private static final Map<String, String> STATUSES = Map.of(
            "succeeded", "SUCCEEDED",
            "failed", "FAILED",
            "canceled", "CANCELED",
            "pending", "PENDING",
            "requires_action", "REQUIRES_ACTION");

    private RefundFactRules() {}

    /** The provider refund id already recorded on another local refund. */
    public static boolean boundElsewhere(LocalRefund bound, LocalRefund local) {
        return bound != null && local != null && !bound.id().equals(local.id());
    }

    /** The fact disagrees with what the local refund already records. */
    public static boolean contradicts(LocalRefund local, RefundFact fact) {
        return fact.amountMinor() != local.amountMinor()
                || !fact.currency().equalsIgnoreCase(local.currency())
                || !Objects.equals(fact.chargeId(), local.providerChargeId())
                || local.providerRefundId() != null && !fact.refundId().equals(local.providerRefundId())
                || differs(local.balanceTransactionId(), fact.balanceTransactionId())
                || differs(local.failureBalanceTransactionId(), fact.failureBalanceTransactionId());
    }

    private static boolean differs(String recorded, String reported) {
        return recorded != null && reported != null && !reported.equals(recorded);
    }

    /** How a reservation settles when its refund reaches a terminal status. */
    public record Settlement(long releasedMinor, long refundedMinor) {}

    /**
     * The outcome of merging a fact.
     *
     * @param settlement non-null when this fact moves the refund to a terminal status
     */
    public record Merge(
            boolean conflict, String status, boolean recognized, String providerStatus, Settlement settlement) {}

    public static Merge merge(LocalRefund local, RefundFact fact) {
        String reported = Objects.toString(fact.status(), "");
        String before = local.status();
        String status = STATUSES.getOrDefault(reported, before);
        boolean recognized = STATUSES.containsKey(reported);
        boolean conflict = contradicts(local, fact)
                || RefundLifecycle.terminal(before) && RefundLifecycle.terminal(status) && !before.equals(status);
        if (conflict || local.resolution() == ResolutionStatus.CONFLICT)
            return new Merge(true, before, false, null, null);
        if (RefundLifecycle.terminal(before)) return new Merge(false, before, true, local.providerStatus(), null);
        var settlement = RefundLifecycle.terminal(status)
                ? new Settlement(
                        local.local() ? local.amountMinor() : 0, "SUCCEEDED".equals(status) ? local.amountMinor() : 0)
                : null;
        return new Merge(false, status, recognized, fact.status(), settlement);
    }

    /** A create the provider definitively rejected releases the hold, unless the refund is already known remotely. */
    public static boolean rejectionReleasesHold(
            boolean query, ProviderCallClassification classification, LocalRefund refund) {
        return !query
                && classification == ProviderCallClassification.DEFINITIVE_REJECT
                && refund.providerRefundId() == null
                && !refund.terminal();
    }

    /** A reservation may be sent to the provider only while it is unsent, certain and its control NORMAL. */
    public static boolean sendable(LocalRefund refund, RefundControlState control) {
        return !refund.terminal()
                && refund.providerRefundId() == null
                && control == RefundControlState.NORMAL
                && refund.resolution() != ResolutionStatus.CONFLICT;
    }
}
