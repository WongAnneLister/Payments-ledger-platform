package com.paymentsledger.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PayoutConfiguration {
    @Bean
    PayoutService payoutService(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, LedgerPostingService ledger) {
        return new PayoutService(jdbc, manager, mapper, ledger);
    }
}
