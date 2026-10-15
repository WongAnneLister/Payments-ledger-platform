package com.paymentsledger.payment.domain;

/**
 * The single Stripe account this deployment is configured for. A Stripe-bound request must match it exactly; any value
 * may be null when the deployment has no Stripe configuration, which then rejects every Stripe request.
 */
public record StripeDeployment(
        String accountId, String organizationId, String environmentId, String providerAccountId) {}
