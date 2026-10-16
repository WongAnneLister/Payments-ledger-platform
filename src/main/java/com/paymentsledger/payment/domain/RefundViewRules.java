package com.paymentsledger.payment.domain;

import java.util.List;

/** Pure rules for the refund read model. */
public final class RefundViewRules {
    private RefundViewRules() {}

    /**
     * Accounting status of a refund: CONFLICT on contradictory evidence; POSTED once every attributed balance
     * transaction is posted (PARTIALLY_POSTED before that); NOT_APPLICABLE when no money can move; otherwise
     * PENDING_EVIDENCE.
     *
     * @param evidenceConflict an attributed balance transaction is quarantined or has recorded conflicting evidence
     */
    public static String financialStatus(
            RefundRecord refund, List<RefundBalanceTransaction> transactions, boolean evidenceConflict) {
        if (evidenceConflict || refund.resolution() == ResolutionStatus.CONFLICT) return "CONFLICT";
        long posted =
                transactions.stream().filter(RefundBalanceTransaction::posted).count();
        if (posted > 0)
            return unposted(refund.balanceTransactionId(), transactions)
                            || unposted(refund.failureBalanceTransactionId(), transactions)
                            || posted < transactions.size()
                    ? "PARTIALLY_POSTED"
                    : "POSTED";
        boolean noMoneyMoves = RefundLifecycle.terminal(refund.status()) && !"SUCCEEDED".equals(refund.status())
                || "RESERVED".equals(refund.status());
        return noMoneyMoves
                        && transactions.isEmpty()
                        && refund.balanceTransactionId() == null
                        && refund.failureBalanceTransactionId() == null
                ? "NOT_APPLICABLE"
                : "PENDING_EVIDENCE";
    }

    /** The refund names a balance transaction that is not among the posted ones. */
    private static boolean unposted(String btId, List<RefundBalanceTransaction> transactions) {
        return btId != null && transactions.stream().noneMatch(b -> btId.equals(b.btId()) && b.posted());
    }

    /** Provider fees returned by posted refund transactions (negative fees), as a positive amount. */
    public static long actualFeeRefund(List<RefundBalanceTransaction> transactions) {
        long total = 0;
        for (var transaction : transactions)
            if (transaction.posted() && transaction.feeMinor() < 0)
                total = Math.addExact(total, Math.negateExact(transaction.feeMinor()));
        return total;
    }
}
