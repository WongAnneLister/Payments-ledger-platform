package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface WebhookVerifierPort {
    ProviderCallResult<VerifiedWebhook> verify(byte[] rawBytes, String signatureHeader, String secret);
}
