package com.paymentsledger.ledger;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantAccessGuard;
import com.paymentsledger.identity.TenantContext;
import java.util.UUID;

/** Internal service scope, constructed from authenticated identity or trusted worker configuration. */
public record LedgerScope(UUID organizationId, UUID environmentId, String currency, UUID providerAccountId) {
    public LedgerScope {
        if (organizationId == null || environmentId == null || currency == null || !currency.matches("[A-Z]{3}"))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Explicit ledger scope is required");
    }

    public static LedgerScope from(TenantContext tenant, String currency) {
        TenantAccessGuard.requireCurrency(tenant, currency);
        return new LedgerScope(tenant.organizationId(), tenant.environmentId(), currency, tenant.allowedProviderAccountId());
    }

    public LedgerScope moneyScope() { return new LedgerScope(organizationId, environmentId, currency, null); }
}
