package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface DisputePort {
    ProviderCallResult<Page<DisputeFact>> listDisputes(PageQuery query);

    ProviderCallResult<DisputeFact> getDispute(ProviderScope scope, String disputeId);
}
