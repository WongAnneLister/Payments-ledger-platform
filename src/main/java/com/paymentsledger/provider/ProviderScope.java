package com.paymentsledger.provider;

import java.util.Objects;
import java.util.UUID;

/** Explicit remote account identity; each adapter enforces its allowed operating mode. */
public record ProviderScope(UUID organizationId, UUID environmentId, UUID providerAccountId, boolean livemode) {
    public ProviderScope {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(providerAccountId, "providerAccountId");
    }
}
