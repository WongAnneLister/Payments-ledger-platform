package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.List;

/** Pure rules for what the payment API tells a client to do next and how recovery is progressing. */
public final class PaymentViewRules {
    private PaymentViewRules() {}

    /** {@code hostedUrl} and {@code expiresAt} are set only for REDIRECT_TO_HOSTED_CHECKOUT. */
    public record NextAction(String type, String hostedUrl, Instant expiresAt) {}

    /**
     * Redirect only while the payment is certain, awaiting the customer and its checkout has not expired; settled
     * payments need nothing; everything else waits for provider confirmation.
     */
    public static NextAction nextAction(PaymentRecord payment, Instant now) {
        if (payment.resolution() != ResolutionStatus.KNOWN) return new NextAction("WAIT_FOR_CONFIRMATION", null, null);
        if (payment.status() == PaymentStatus.SUCCEEDED || payment.status() == PaymentStatus.CANCELED)
            return new NextAction("NONE", null, null);
        if (payment.hostedUrl() != null
                && (payment.status() == PaymentStatus.AWAITING_CUSTOMER
                        || payment.status() == PaymentStatus.REQUIRES_ACTION)
                && payment.expiresAt().isAfter(now))
            return new NextAction("REDIRECT_TO_HOSTED_CHECKOUT", payment.hostedUrl(), payment.expiresAt());
        return new NextAction("WAIT_FOR_CONFIRMATION", null, null);
    }

    public record RecoverySummary(
            String status, Instant nextAttemptAt, int attemptCount, Instant lastQueryAt, String reason) {}

    /**
     * @param tasks the payment's recovery tasks, most urgent first (escalated, running, waiting/ready, done)
     */
    public static RecoverySummary recoverySummary(List<RecoveryTaskView> tasks, Instant now) {
        int attempts = 0;
        Instant lastQuery = null;
        for (var task : tasks) {
            attempts += task.attemptCount();
            if (task.lastQueryAt() != null
                    && (lastQuery == null || task.lastQueryAt().isAfter(lastQuery))) lastQuery = task.lastQueryAt();
        }
        if (tasks.isEmpty()) return new RecoverySummary("IDLE", null, attempts, lastQuery, null);
        var urgent = tasks.getFirst();
        String status = switch (urgent.status()) {
            case "ESCALATED" -> "ESCALATED";
            case "RUNNING" -> "RUNNING";
            case "WAITING", "READY" -> "WAITING";
            default -> "IDLE";
        };
        // A lease that has expired no longer has a live owner; the task is waiting to be claimed again.
        if (status.equals("RUNNING")
                && (urgent.leaseUntil() == null || !urgent.leaseUntil().isAfter(now))) status = "WAITING";
        Instant next = status.equals("WAITING") ? urgent.availableAt() : null;
        return new RecoverySummary(status, next, attempts, lastQuery, urgent.reason());
    }
}
