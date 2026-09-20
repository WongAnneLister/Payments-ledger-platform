package com.paymentsledger.identity;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;

public final class TenantAccessGuard {
    private TenantAccessGuard() { }
    public static TenantContext require(TenantContext context) {
        if (context==null) throw unauthorized();
        return context;
    }
    public static void requireScope(TenantContext context, String scope) {
        if (!require(context).grantedScopes().contains(scope)) throw forbidden();
    }
    public static void requireCurrency(TenantContext context, String currency) {
        if (!require(context).allowedCurrencies().contains(currency))
            throw new ApiException(ErrorCode.CURRENCY_NOT_ENABLED,"Currency is not enabled for caller");
    }
    static ApiException unauthorized() { return new ApiException(ErrorCode.UNAUTHORIZED,"Valid credentials required"); }
    static ApiException forbidden() { return new ApiException(ErrorCode.FORBIDDEN,"Request is outside caller permissions"); }
    static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND,"Resource not found in caller scope"); }
}
