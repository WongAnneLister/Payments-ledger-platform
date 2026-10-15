package com.paymentsledger.provider.fake;

import java.nio.file.Path;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnProperty(name = "platform.provider.fake.enabled", havingValue = "true")
public class FakeProviderConfiguration {
    @Bean
    FakeStripeProvider fakeStripeProvider(
            @Value("${platform.provider.fake.directory}") String directory,
            @Value("${platform.provider.fake.seed}") long seed,
            @Value("${platform.provider.fake.initial-time}") String initial,
            @Value("${platform.provider.fake.retention}") String retention) {
        return new FakeStripeProvider(Path.of(directory), seed, Instant.parse(initial), Duration.parse(retention));
    }

    @Bean
    FakeWebhookVerifier fakeWebhookVerifier(FakeStripeProvider provider) {
        return new FakeWebhookVerifier(provider.clock(), Duration.ofMinutes(5));
    }
}
