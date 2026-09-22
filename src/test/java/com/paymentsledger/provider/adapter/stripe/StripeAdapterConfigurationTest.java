package com.paymentsledger.provider.adapter.stripe;

import static org.assertj.core.api.Assertions.*;
import com.paymentsledger.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StripeAdapterConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(StripeAdapterConfiguration.class);
    ApplicationContextRunner configured() {
        return runner.withPropertyValues("platform.stripe.enabled=true","platform.stripe.api-key=sk_test_local_fixture",
                "platform.stripe.account-id=acct_fixture","platform.stripe.organization-id=00000000-0000-0000-0000-000000000001",
                "platform.stripe.environment-id=00000000-0000-0000-0000-000000000002",
                "platform.stripe.provider-account-id=00000000-0000-0000-0000-000000000003",
                "platform.stripe.webhook-api-version=2026-07-29.dahlia");
    }
    @Test void disabledByDefaultAndDoesNotRequireSecrets() {
        runner.run(c -> assertThat(c).doesNotHaveBean(StripeGateway.class));
    }
    @Test void explicitTestBindingWiresTenPortsWithoutNetworkAtStartup() {
        configured().run(c -> {
            assertThat(c).hasNotFailed();
            for (Class<?> port : new Class<?>[]{CheckoutPort.class,PaymentIntentPort.class,ChargePort.class,RefundPort.class,
                    BalanceTransactionPort.class,DisputePort.class,PayoutPort.class,BalancePort.class,ReportPort.class,WebhookVerifierPort.class})
                assertThat(c).hasSingleBean(port);
        });
    }
    @Test void refusesLiveCredentialMissingBindingAndSimultaneousFake() {
        configured().withPropertyValues("platform.stripe.api-key=sk_live_rejected").run(c -> assertThat(c).hasFailed());
        configured().withPropertyValues("platform.stripe.account-id=").run(c -> assertThat(c).hasFailed());
        configured().withPropertyValues("platform.provider.fake.enabled=true").run(c -> assertThat(c).hasFailed());
        configured().withPropertyValues("platform.stripe.webhook-api-version=wrong").run(c -> assertThat(c).hasFailed());
    }
}
