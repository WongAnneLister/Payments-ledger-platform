package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface RefundPort {
    ProviderCallResult<RefundFact> createRefund(CreateRefundCommand command);

    ProviderCallResult<RefundFact> queryRefund(ProviderScope scope, String refundId);
}
