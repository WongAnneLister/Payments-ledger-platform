package com.paymentsledger.webhook;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import com.paymentsledger.provider.adapter.stripe.StripeWebhookVerifier;
import java.time.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Trusted deployment binding; no request field can select an account or signing secret. */
@Component
@Profile("api")
@ConditionalOnProperty(name="platform.webhooks.stripe.enabled",havingValue="true")
public final class StripeWebhookEndpoint {
    private final ProviderScope scope;
    private final String accountRef,secret;
    private final StripeWebhookVerifier verifier;
    private final int maximumBytes;

    public StripeWebhookEndpoint(Environment environment,Clock clock){
        try {
            scope=new ProviderScope(UUID.fromString(value(environment,"organization-id","STRIPE_ORGANIZATION_ID")),
                    UUID.fromString(value(environment,"environment-id","STRIPE_ENVIRONMENT_ID")),
                    UUID.fromString(value(environment,"provider-account-id","STRIPE_PROVIDER_ACCOUNT_ID")),false);
            accountRef=value(environment,"account-id","STRIPE_ACCOUNT_ID");
            secret=value(environment,"webhook-secret","STRIPE_WEBHOOK_SECRET");
            if(secret==null || !secret.startsWith("whsec_") || secret.substring(6).isBlank())
                throw new IllegalArgumentException();
            maximumBytes=environment.getProperty("platform.webhooks.stripe.max-body-bytes",Integer.class,262144);
            if(maximumBytes<1 || maximumBytes>1048576)throw new IllegalArgumentException();
            Duration tolerance=Duration.parse(environment.getProperty("platform.webhooks.stripe.signature-tolerance","PT5M"));
            verifier=new StripeWebhookVerifier(scope,accountRef,value(environment,"webhook-api-version","STRIPE_WEBHOOK_API_VERSION"),clock,tolerance);
        } catch(RuntimeException invalid){
            // Never attach a parsing exception whose message may contain deployment credentials.
            throw new IllegalArgumentException("Enabled Stripe webhook requires valid trusted scope, account, endpoint version, signing secret and limits");
        }
    }
    private static String value(Environment environment,String key,String fallback){
        return environment.getProperty("platform.stripe."+key,environment.getProperty(fallback));
    }
    ProviderScope scope(){return scope;}
    String accountRef(){return accountRef;}
    int maximumBytes(){return maximumBytes;}
    ProviderCallResult<VerifiedWebhook> verify(byte[] raw,String signature){return verifier.verify(raw,signature,secret);}
}
