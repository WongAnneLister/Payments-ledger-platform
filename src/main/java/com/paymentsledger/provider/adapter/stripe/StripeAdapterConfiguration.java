package com.paymentsledger.provider.adapter.stripe;

import com.paymentsledger.provider.ProviderScope;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Explicit trusted deployment binding; request metadata cannot select credentials or scope. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.stripe.enabled", havingValue = "true")
public class StripeAdapterConfiguration {
    @Bean StripeGateway stripeGateway(
            @Value("${platform.stripe.api-key:${STRIPE_API_KEY:}}") String apiKey,
            @Value("${platform.stripe.account-id:${STRIPE_ACCOUNT_ID:}}") String accountId,
            @Value("${platform.stripe.organization-id:${STRIPE_ORGANIZATION_ID:}}") String organizationId,
            @Value("${platform.stripe.environment-id:${STRIPE_ENVIRONMENT_ID:}}") String environmentId,
            @Value("${platform.stripe.provider-account-id:${STRIPE_PROVIDER_ACCOUNT_ID:}}") String providerAccountId,
            @Value("${platform.provider.fake.enabled:false}") boolean fakeEnabled) {
        if (fakeEnabled) throw new IllegalArgumentException("Select exactly one provider implementation");
        return new StripeGateway(apiKey,scope(organizationId,environmentId,providerAccountId),accountId,Clock.systemUTC());
    }
    @Bean StripeCheckoutAdapter stripeCheckoutAdapter(StripeGateway gateway) { return new StripeCheckoutAdapter(gateway); }
    @Bean StripeRefundAdapter stripeRefundAdapter(StripeGateway gateway) { return new StripeRefundAdapter(gateway); }
    @Bean StripeFinancialReadAdapter stripeFinancialReadAdapter(StripeGateway gateway) { return new StripeFinancialReadAdapter(gateway); }
    @Bean StripeWebhookVerifier stripeWebhookVerifier(
            @Value("${platform.stripe.account-id:${STRIPE_ACCOUNT_ID:}}") String accountId,
            @Value("${platform.stripe.organization-id:${STRIPE_ORGANIZATION_ID:}}") String organizationId,
            @Value("${platform.stripe.environment-id:${STRIPE_ENVIRONMENT_ID:}}") String environmentId,
            @Value("${platform.stripe.provider-account-id:${STRIPE_PROVIDER_ACCOUNT_ID:}}") String providerAccountId,
            @Value("${platform.stripe.webhook-api-version:${STRIPE_WEBHOOK_API_VERSION:}}") String endpointApiVersion) {
        return new StripeWebhookVerifier(scope(organizationId,environmentId,providerAccountId),accountId,
                endpointApiVersion,Clock.systemUTC(),Duration.ofMinutes(5));
    }
    private static ProviderScope scope(String organizationId,String environmentId,String providerAccountId) {
        try { return new ProviderScope(UUID.fromString(organizationId),UUID.fromString(environmentId),UUID.fromString(providerAccountId),false); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Valid trusted Stripe scope IDs are required"); }
    }
}
