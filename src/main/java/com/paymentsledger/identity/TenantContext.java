package com.paymentsledger.identity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TenantContext(UUID organizationId, UUID environmentId, UUID applicationId,
                            UUID allowedProviderAccountId, Set<String> allowedCurrencies,
                            boolean livemode, Set<String> grantedScopes) {
    public TenantContext {
        Objects.requireNonNull(organizationId); Objects.requireNonNull(environmentId);
        Objects.requireNonNull(applicationId); Objects.requireNonNull(allowedProviderAccountId);
        allowedCurrencies=Set.copyOf(allowedCurrencies); grantedScopes=Set.copyOf(grantedScopes);
        if (livemode || allowedCurrencies.isEmpty()) throw TenantAccessGuard.unauthorized();
    }
}
