package com.paymentsledger.financialingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.BalanceTransactionPort;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class FinancialIngestionWorkerTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, FinancialIngestionConfiguration.class)
            .withPropertyValues(
                    "platform.financial-ingestion.organization-id=00000000-0000-0000-0000-000000000001",
                    "platform.financial-ingestion.environment-id=00000000-0000-0000-0000-000000000002",
                    "platform.financial-ingestion.provider-account-id=00000000-0000-0000-0000-000000000003",
                    "platform.financial-ingestion.poll-ms=3600000");

    @Test
    void apiCannotEnableSchedulerEvenWithExplicitFlag() {
        context.withPropertyValues("spring.profiles.active=api", "platform.financial-ingestion.enabled=true")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(FinancialIngestionWorker.class));
    }

    @Test
    void workerIsDisabledUntilExplicitlyEnabled() {
        context.withPropertyValues("spring.profiles.active=worker")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(FinancialIngestionWorker.class));
    }

    @Test
    void enabledWorkerRequiresExplicitTrustedScopeAndProvider() {
        context.withPropertyValues("spring.profiles.active=worker", "platform.financial-ingestion.enabled=true")
                .run(c -> assertThat(c)
                        .hasNotFailed()
                        .hasSingleBean(FinancialIngestionWorker.class)
                        .hasSingleBean(FinancialImportService.class)
                        .hasSingleBean(FinancialReplayService.class));
        context.withPropertyValues(
                        "spring.profiles.active=worker",
                        "platform.financial-ingestion.enabled=true",
                        "platform.financial-ingestion.provider-account-id=")
                .run(c -> assertThat(c).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(FinancialIngestionConfiguration.class)
                .withPropertyValues("spring.profiles.active=worker", "platform.financial-ingestion.enabled=true")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void slowFinancialWorkDoesNotOccupyTheDefaultScheduler() {
        context.withPropertyValues("spring.profiles.active=worker", "platform.financial-ingestion.enabled=true")
                .run(c -> {
                    var method = FinancialIngestionWorker.class.getMethod("tick");
                    var scheduled = method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
                    assertThat(scheduled.scheduler()).isEqualTo("financialIngestionScheduler");
                    var financial = c.getBean(
                            "financialIngestionScheduler", org.springframework.scheduling.TaskScheduler.class);
                    var shared = c.getBean("taskScheduler", org.springframework.scheduling.TaskScheduler.class);
                    assertThat(financial).isNotSameAs(shared);
                    var entered = new java.util.concurrent.CountDownLatch(1);
                    var release = new java.util.concurrent.CountDownLatch(1);
                    var otherTick = new java.util.concurrent.CountDownLatch(1);
                    financial.schedule(
                            () -> {
                                entered.countDown();
                                try {
                                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            },
                            java.time.Instant.now());
                    try {
                        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                .isTrue();
                        shared.schedule(otherTick::countDown, java.time.Instant.now());
                        assertThat(otherTick.await(2, java.util.concurrent.TimeUnit.SECONDS))
                                .as("unrelated scheduled work progresses while financial provider I/O is blocked")
                                .isTrue();
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        DataSource source() {
            return mock(DataSource.class);
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        BalanceTransactionPort provider() {
            return mock(BalanceTransactionPort.class);
        }

        @Bean
        LedgerPostingService posting(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new LedgerPostingService(jdbc, mapper);
        }
    }
}
