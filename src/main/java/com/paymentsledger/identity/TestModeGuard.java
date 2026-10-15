package com.paymentsledger.identity;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail during context initialization, before either role accepts requests. */
@Component
@DependsOnDatabaseInitialization
public class TestModeGuard implements InitializingBean {
    private final IdentityRepository repository;
    private final Environment environment;

    public TestModeGuard(IdentityRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.getProperty("platform.livemode", Boolean.class, false))
            throw new IllegalStateException("Live mode is forbidden");
        for (String name : new String[] {"STRIPE_API_KEY", "stripe.api-key", "platform.stripe.api-key"}) {
            String key = environment.getProperty(name, "").trim();
            if (key.startsWith("sk_live_") || key.startsWith("rk_live_"))
                throw new IllegalStateException("Live provider credentials are forbidden");
        }
        repository.assertTestOnly();
    }
}
