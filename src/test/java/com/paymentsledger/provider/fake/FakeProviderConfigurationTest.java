package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FakeProviderConfigurationTest {
    @TempDir Path directory;
    @Test void simulatorRequiresTestProfileAndExplicitOptIn() {
        var runner=new ApplicationContextRunner().withUserConfiguration(FakeProviderConfiguration.class)
                .withPropertyValues("platform.provider.fake.directory="+directory,"platform.provider.fake.seed=42",
                        "platform.provider.fake.initial-time=2026-01-01T00:00:00Z","platform.provider.fake.retention=PT10M");
        runner.withPropertyValues("platform.provider.fake.enabled=true").run(c -> assertThat(c).doesNotHaveBean(FakeStripeProvider.class));
        runner.withPropertyValues("spring.profiles.active=test").run(c -> assertThat(c).doesNotHaveBean(FakeStripeProvider.class));
        runner.withPropertyValues("spring.profiles.active=test","platform.provider.fake.enabled=true")
                .run(c -> { assertThat(c).hasSingleBean(FakeStripeProvider.class);assertThat(c).hasSingleBean(FakeWebhookVerifier.class); });
    }
}
