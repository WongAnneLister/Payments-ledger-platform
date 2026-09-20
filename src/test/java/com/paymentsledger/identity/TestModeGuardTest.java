package com.paymentsledger.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TestModeGuardTest {
    @Test void rejectsLiveSettingsBeforeContextStartsWithoutLeakingKey() {
        for(String setting:new String[]{"platform.livemode=true","STRIPE_API_KEY=sk_live_private", "stripe.api-key=rk_live_private"})
            new ApplicationContextRunner().withBean(IdentityRepository.class,()->mock(IdentityRepository.class))
                    .withUserConfiguration(TestModeGuard.class).withPropertyValues(setting)
                    .run(context -> assertThat(context).hasFailed());
    }
    @Test void refusesDatabaseLiveConfigurationDuringStartup() {
        var repository=mock(IdentityRepository.class);
        doThrow(new IllegalStateException("Live configuration is forbidden")).when(repository).assertTestOnly();
        new ApplicationContextRunner().withBean(IdentityRepository.class,()->repository)
                .withUserConfiguration(TestModeGuard.class)
                .run(context -> assertThat(context).hasFailed());
    }
}
