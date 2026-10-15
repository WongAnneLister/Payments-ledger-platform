package com.paymentsledger.payment.domain;

import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import java.time.Instant;
import java.util.List;

/**
 * Provider evidence about one payment, from a query, a webhook or a reconciliation read.
 *
 * @param complete every object needed to judge the payment was read successfully
 * @param conflict the provider objects contradict each other
 * @param confirmedAt when the provider confirmed this snapshot; older snapshots are stale
 * @param paymentVersion the payment row version the snapshot was taken for, or null if not version-bound
 */
public record PaymentFacts(
        CheckoutSessionFact session,
        PaymentIntentFact intent,
        List<ChargeFact> charges,
        boolean complete,
        boolean conflict,
        Instant confirmedAt,
        Long paymentVersion) {
    public PaymentFacts(
            CheckoutSessionFact session,
            PaymentIntentFact intent,
            List<ChargeFact> charges,
            boolean complete,
            boolean conflict,
            Instant confirmedAt) {
        this(session, intent, charges, complete, conflict, confirmedAt, null);
    }
}
