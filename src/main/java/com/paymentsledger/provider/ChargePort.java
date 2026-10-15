package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface ChargePort {
    ProviderCallResult<ChargeFact> queryCharge(ProviderScope scope, String chargeId);

    ProviderCallResult<java.util.List<ChargeFact>> listChargeAttempts(ProviderScope scope, String paymentIntentId);
}
