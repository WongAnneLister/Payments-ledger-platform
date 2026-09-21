package com.paymentsledger.provider;

/** The complete error vocabulary that callers may use for recovery decisions. */
public enum ProviderCallClassification {
    SUCCESS,
    DEFINITIVE_REJECT,
    AUTH_REQUIRED,
    NOT_FOUND,
    TRANSIENT_EMPTY,
    RETRYABLE_TRANSIENT,
    RATE_LIMITED,
    TIMEOUT_UNKNOWN,
    IDEMPOTENCY_CONFLICT,
    CAPABILITY_UNAVAILABLE,
    CONTRACT_MISMATCH
}
