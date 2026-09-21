package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface PayoutPort {
    ProviderCallResult<Page<PayoutFact>> listPayouts(PageQuery query);
    ProviderCallResult<PayoutFact> getPayout(ProviderScope scope, String payoutId);
}
