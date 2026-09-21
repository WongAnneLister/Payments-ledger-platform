package com.paymentsledger.provider;

import java.time.Instant;
import java.util.Objects;

/** Evidence survives failures and timeouts; absent response fields remain null. */
public record ProviderEvidence(
        String providerRequestId,
        String idempotencyKeyUsed,
        Instant requestedAt,
        Instant respondedAt,
        Integer httpStatus,
        String providerErrorCode,
        String rawDigest,
        String apiVersion) {
    public ProviderEvidence {
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
