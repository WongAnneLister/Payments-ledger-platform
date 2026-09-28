package com.paymentsledger.financialingestion;

import com.paymentsledger.provider.ProviderScope;

/** Queries a referenced refund outside transactions before replaying its financial facts. */
@FunctionalInterface
public interface RefundSourceResolver {
    void resolve(ProviderScope scope,String providerRefundId);
    static RefundSourceResolver unavailable(){return (scope,id)->{};}
}
