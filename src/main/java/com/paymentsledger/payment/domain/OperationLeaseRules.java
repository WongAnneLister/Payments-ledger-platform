package com.paymentsledger.payment.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure rules for leasing provider operations to senders and recovery workers.
 *
 * <ul>
 *   <li>A first send takes only a PREPARED operation. Recovery takes only a due task whose previous lease has expired.
 *       An escalated operation is never taken again.
 *   <li>A lease is fenced by a fresh token and an incremented version; writes from an older lease are rejected.
 *   <li>The first send time, and the retry horizon derived from it, never move: retrying with the original idempotency
 *       key is safe only within the provider's verified retention window.
 * </ul>
 */
public final class OperationLeaseRules {
    private OperationLeaseRules() {}

    public static boolean claimable(
            boolean recovery,
            String operationStatus,
            Instant leaseUntil,
            String taskStatus,
            Instant taskAvailableAt,
            Instant now) {
        if ("ESCALATED".equals(operationStatus)) return false;
        if (!recovery) return "PREPARED".equals(operationStatus);
        if ("DONE".equals(taskStatus) || taskAvailableAt.isAfter(now)) return false;
        return leaseUntil == null || !leaseUntil.isAfter(now);
    }

    /** A checkout whose payment already has a remote intent or capture must be queried, not created again. */
    public static boolean remotePaymentKnown(String paymentIntentId, long capturedMinor) {
        return paymentIntentId != null || capturedMinor > 0;
    }

    /** Lease fields for a claim: first send and horizon are set once, the lease expiry every time. */
    public record LeaseGrant(Instant firstSendAt, Instant retryUntil, Instant leaseUntil) {}

    public static LeaseGrant grant(
            Instant firstSendAt, Instant retryUntil, Instant now, Duration verifiedWindow, Duration lease) {
        Instant first = firstSendAt, horizon = retryUntil;
        if (first == null) {
            first = now;
            if (!verifiedWindow.isZero()) horizon = first.plus(verifiedWindow);
        }
        return new LeaseGrant(first, horizon, now.plus(lease));
    }

    /** Recovery batches are metered per provider account across all processes. */
    public static boolean batchAvailable(Instant nextBatchAt, Instant now) {
        return !nextBatchAt.isAfter(now);
    }

    /** A payment operation's task is finished once acknowledged, canceled with certainty, or its cancel resolved. */
    public static boolean paymentTaskDone(
            String operationStatus, String operationType, String paymentStatus, String paymentResolution) {
        return "ACKNOWLEDGED".equals(operationStatus)
                || "CANCELED".equals(paymentStatus) && "KNOWN".equals(paymentResolution)
                || "CANCEL_PAYMENT".equals(operationType) && "RESOLVED".equals(operationStatus);
    }

    /** A refund operation's task is finished once the refund is terminal and certain. */
    public static boolean refundTaskDone(boolean refundTerminal, String refundResolution) {
        return refundTerminal && "KNOWN".equals(refundResolution);
    }

    /** The L2 scan may wake a task unless its operation is escalated or still leased. */
    public static boolean wakeable(String operationStatus, Instant leaseUntil, Instant now) {
        return !"ESCALATED".equals(operationStatus) && (leaseUntil == null || !leaseUntil.isAfter(now));
    }
}
