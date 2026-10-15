package com.paymentsledger.financialingestion;

import com.paymentsledger.provider.ProviderScope;

/** Read-only source recovery outside a local replay transaction. */
@FunctionalInterface
public interface DisputeSourceResolver {
    void resolve(ProviderScope scope, String providerDisputeId);

    static DisputeSourceResolver unavailable() {
        return (scope, id) -> {};
    }
}
