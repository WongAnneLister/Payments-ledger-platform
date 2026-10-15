package com.paymentsledger.provider.fake;

/** A one-shot outcome, queued by exact provider scope, operation and target. Persisted with the fixture. */
public record FailureScript(Operation operation, String target, Outcome outcome) {
    public FailureScript {
        if (operation == null || target == null || target.isBlank() || outcome == null)
            throw new IllegalArgumentException("Script target required");
    }

    public enum Operation {
        CREATE_SESSION,
        QUERY_SESSION,
        EXPIRE_SESSION,
        QUERY_PAYMENT_INTENT,
        QUERY_CHARGE,
        LIST_CHARGE_ATTEMPTS,
        CREATE_REFUND,
        QUERY_REFUND,
        LIST_BALANCE_TRANSACTIONS,
        GET_BALANCE_TRANSACTION,
        LIST_DISPUTES,
        GET_DISPUTE,
        LIST_PAYOUTS,
        GET_PAYOUT,
        GET_BALANCE,
        REQUEST_REPORT,
        FETCH_REPORT
    }

    public enum Outcome {
        BEFORE_SEND_FAILURE,
        RESPONSE_LOST,
        CRASH_AFTER_COMMIT,
        TRANSIENT_EMPTY,
        TRANSIENT_ERROR,
        CACHED_TRANSIENT_ERROR,
        RATE_LIMIT,
        REJECT,
        AUTH_REQUIRED,
        CONTRACT_MISMATCH
    }
}
