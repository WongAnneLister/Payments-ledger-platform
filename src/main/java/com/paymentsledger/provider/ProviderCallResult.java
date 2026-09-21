package com.paymentsledger.provider;

import java.util.Objects;

/** Provider facts and evidence, never a local payment or accounting state. */
public record ProviderCallResult<T>(ProviderCallClassification classification, T payload, ProviderEvidence evidence) {
    public ProviderCallResult {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(evidence, "evidence");
        if (payload != null && classification != ProviderCallClassification.SUCCESS
                && classification != ProviderCallClassification.AUTH_REQUIRED) {
            throw new IllegalArgumentException("Only success or authentication-required results may contain facts");
        }
    }
}
