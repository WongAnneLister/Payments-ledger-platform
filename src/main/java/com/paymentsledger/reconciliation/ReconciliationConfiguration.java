package com.paymentsledger.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.BalanceRebuildService;
import com.paymentsledger.payment.*;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.provider.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnProperty(
        name = {"platform.financial-ingestion.enabled", "platform.reconciliation.enabled"},
        havingValue = "true")
public class ReconciliationConfiguration {
    @Bean
    ReconciliationService reconciliationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            FinancialImportService importer,
            BalanceRebuildService rebuild,
            BankMatcher bank,
            Clock clock) {
        return new ReconciliationService(jdbc, tx, json, importer, rebuild, bank, clock);
    }

    @Bean
    ReconciliationSourceRecovery reconciliationSourceRecovery(
            PaymentRepositories store,
            PlatformTransactionManager tx,
            ObjectMapper json,
            ObjectProvider<ChargePort> charges,
            ObjectProvider<PaymentIntentPort> intents,
            PaymentRepository payments,
            FinancialReplayService replay,
            Clock clock) {
        return new ReconciliationSourceRecovery(
                store, tx, json, charges.getIfAvailable(), intents.getIfAvailable(), payments, replay, clock);
    }

    @Bean
    ReconciliationWorker reconciliationWorker(
            ReconciliationService engine,
            ReconciliationSourceRecovery sources,
            FinancialReplayService replay,
            JdbcTemplate jdbc,
            ObjectProvider<ReportPort> reports,
            Clock clock,
            @Value("${platform.financial-ingestion.organization-id}") String organization,
            @Value("${platform.financial-ingestion.environment-id}") String environment,
            @Value("${platform.financial-ingestion.provider-account-id}") String provider,
            @Value("${platform.reconciliation.currency:USD}") String currency,
            @Value("${platform.reconciliation.business-zone:UTC}") String zone) {
        ProviderScope scope = new ProviderScope(
                UUID.fromString(organization), UUID.fromString(environment), UUID.fromString(provider), false);
        return new ReconciliationWorker(
                engine, sources, replay, jdbc, reports.getIfAvailable(), clock, scope, currency, ZoneId.of(zone));
    }
}
