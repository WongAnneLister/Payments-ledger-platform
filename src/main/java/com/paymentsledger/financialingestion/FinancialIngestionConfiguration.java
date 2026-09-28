package com.paymentsledger.financialingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.BalanceTransactionPort;
import com.paymentsledger.provider.ProviderScope;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name="platform.financial-ingestion.enabled", havingValue="true")
public class FinancialIngestionConfiguration {
    @Bean(name="taskScheduler")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler applicationTaskScheduler(
            ObjectProvider<org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder> builders) {
        // Keep unqualified payment/webhook/notification schedules on their own default scheduler.
        return builders.getIfAvailable(org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder::new).build();
    }

    @Bean(name="financialIngestionScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler financialIngestionScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("financial-ingestion-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean FinancialImportService financialImportService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ObjectMapper mapper, BalanceTransactionPort provider, Clock clock) {
        return new FinancialImportService(jdbc, transactions, mapper, provider, clock);
    }

    @Bean FinancialReplayService financialReplayService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ObjectMapper mapper, LedgerPostingService posting, ObjectProvider<RefundEvidencePort> refunds) {
        return new FinancialReplayService(jdbc, transactions, mapper, posting,
                refunds.getIfAvailable(RefundEvidencePort::unavailable));
    }

    @Bean FinancialIngestionWorker financialIngestionWorker(JdbcTemplate jdbc, FinancialImportService importer,
            FinancialReplayService replay, Clock clock,ObjectProvider<RefundSourceResolver> refundSources,
            @Value("${platform.financial-ingestion.organization-id:}") String organization,
            @Value("${platform.financial-ingestion.environment-id:}") String environment,
            @Value("${platform.financial-ingestion.provider-account-id:}") String providerAccount) {
        ProviderScope scope;
        try {
            scope = new ProviderScope(UUID.fromString(organization), UUID.fromString(environment),
                    UUID.fromString(providerAccount), false);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Financial ingestion requires configured scope UUIDs");
        }
        return new FinancialIngestionWorker(jdbc, importer, replay, scope, clock,refundSources.getIfAvailable(RefundSourceResolver::unavailable));
    }
}
