package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface CheckoutPort {
    ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand command);

    ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope scope, String sessionId);

    ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope scope, String sessionId);
}
