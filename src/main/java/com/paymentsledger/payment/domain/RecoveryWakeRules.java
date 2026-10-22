package com.paymentsledger.payment.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Pure rules for an operator waking an existing recovery task. A wake only makes the task due now; it never creates
 * an operation or sends a provider request.
 *
 * <ul>
 *   <li>A task that is leased to a worker, done or escalated is refused.
 *   <li>Its operation is refused while leased or once resolved or escalated.
 *   <li>A create whose outcome is uncertain (unknown, sending or escalated) is refused once its retry horizon has
 *       passed: retrying it would no longer be idempotent at the provider.
 * </ul>
 */
public final class RecoveryWakeRules {
    private RecoveryWakeRules() {}

    /** The task to wake. Operation tasks name the operation and its payment or refund; source tasks name a BT. */
    public record Task(
            UUID id,
            UUID providerAccountId,
            String currency,
            UUID operationId,
            UUID paymentId,
            UUID refundId,
            UUID sourceBtId,
            String status,
            Instant leaseUntil) {}

    /** The operation behind an operation task. */
    public record Operation(String status, String type, Instant retryUntil, Instant leaseUntil) {}

    public static boolean taskRefused(Task task, Instant now) {
        return leased(task.leaseUntil(), now) || Set.of("DONE", "ESCALATED").contains(task.status());
    }

    public static boolean operationRefused(Operation operation, Instant now) {
        boolean unknown = Set.of("UNKNOWN", "SENDING", "ESCALATED").contains(operation.status());
        boolean expired =
                operation.retryUntil() == null || !operation.retryUntil().isAfter(now);
        return leased(operation.leaseUntil(), now)
                || Set.of("RESOLVED", "ESCALATED").contains(operation.status())
                || unknown
                        && expired
                        && Set.of("CREATE_CHECKOUT", "CREATE_REFUND").contains(operation.type());
    }

    static boolean leased(Instant leaseUntil, Instant now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }
}
