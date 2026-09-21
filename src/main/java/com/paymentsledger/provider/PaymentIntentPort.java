package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface PaymentIntentPort {
    ProviderCallResult<PaymentIntentFact> queryPaymentIntent(ProviderScope scope, String paymentIntentId);
}
