package com.paymentsledger.pr18;

import java.util.Set;

public record SequenceCommand(String id, Kind kind, String key, long amount, Set<String> requires) {
    public enum Kind {
        CREATE,
        PAYMENT_UNKNOWN,
        PAYMENT_RECOVER,
        PAYMENT_AUTH_REQUIRED,
        PAYMENT_DECLINED,
        CAPTURE,
        POST_CAPTURE,
        RESERVE,
        REFUND_UNKNOWN,
        KEY_EXPIRED,
        REFUND_FAILED,
        REFUND_STALE,
        SAME_KEY,
        REFUND_SUCCEEDED,
        POST_REFUND,
        EARLY_REFUND,
        EARLY_DISPUTE_RETURN,
        EARLY_PAYOUT_RETURN,
        CHANGED_KEY,
        DUPLICATE_POST,
        DISPUTE_WARNING,
        DISPUTE_CLOSED,
        ILLEGAL_REFUND,
        DISPUTE_LOST,
        DISPUTE_DEBIT,
        DISPUTE_WON,
        DISPUTE_RETURN,
        SHARE,
        PAYOUT_ACCEPT,
        PAYOUT_OUT,
        PAYOUT_RETURN,
        BANK_RECEIPT,
        DUPLICATE_BANK,
        WRONG_SCOPE,
        MISSING_EVIDENCE,
        WRONG_CUTOFF,
        FULL_RECONCILIATION
    }

    public SequenceCommand {
        requires = Set.copyOf(requires);
    }
}
