package com.paymentsledger.notification;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Uses the same database writer as payment triggers, with payment-before-outbox lock order. */
public final class OutboxWriter {
    private final JdbcTemplate jdbc;
    public OutboxWriter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public UUID write(UUID paymentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox writer requires the existing payment transaction");
        return jdbc.queryForObject("SELECT platform.write_payment_notification(?)",UUID.class,paymentId);
    }
}
