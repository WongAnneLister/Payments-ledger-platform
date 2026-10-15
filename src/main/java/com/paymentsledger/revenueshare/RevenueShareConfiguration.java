package com.paymentsledger.revenueshare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.ProviderScope;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Sharing is enabled only in the application graph that supplies every transactional hook. */
@Configuration(proxyBeanMethods = false)
public class RevenueShareConfiguration {
    @Bean
    RevenueShareService revenueShareService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            LedgerPostingService ledger,
            ObjectProvider<ShareEligibilityPolicy> policy,
            @Value("${platform.revenue-share.enabled:true}") boolean enabled) {
        return new RevenueShareService(
                jdbc, manager, mapper, ledger, enabled, policy.getIfAvailable(ShareEligibilityPolicy::allow));
    }

    @Bean
    @Profile("worker")
    @ConditionalOnProperty(name = "platform.financial-ingestion.enabled", havingValue = "true")
    RevenueShareWorker revenueShareWorker(
            RevenueShareService service,
            @Value("${platform.financial-ingestion.organization-id:}") String organization,
            @Value("${platform.financial-ingestion.environment-id:}") String environment,
            @Value("${platform.financial-ingestion.provider-account-id:}") String providerAccount) {
        return new RevenueShareWorker(
                service,
                new ProviderScope(
                        UUID.fromString(organization),
                        UUID.fromString(environment),
                        UUID.fromString(providerAccount),
                        false));
    }
}
