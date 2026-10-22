package com.paymentsledger.payment.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The payment module's data access, built from one JdbcTemplate. Services receive this instead of a JdbcTemplate so
 * that SQL stays in this package; all repositories are stateless and safe to share.
 */
@Component
public final class PaymentRepositories {
    private final PaymentStateRepository states;
    private final PaymentIntentRepository intents;
    private final PaymentRecordRepository records;
    private final OperationLeaseRepository leases;
    private final ProviderResultRepository results;
    private final RefundIntentRepository refundIntents;
    private final RefundRecordRepository refundRecords;
    private final RefundFactRepository refundFacts;
    private final RecoveryTaskRepository recoveryTasks;
    private final ReconciliationSourceRepository reconciliationSources;

    public PaymentRepositories(JdbcTemplate jdbc) {
        this.states = new PaymentStateRepository(jdbc);
        this.intents = new PaymentIntentRepository(jdbc);
        this.records = new PaymentRecordRepository(jdbc);
        this.leases = new OperationLeaseRepository(jdbc);
        this.results = new ProviderResultRepository(jdbc);
        this.refundIntents = new RefundIntentRepository(jdbc);
        this.refundRecords = new RefundRecordRepository(jdbc);
        this.refundFacts = new RefundFactRepository(jdbc);
        this.recoveryTasks = new RecoveryTaskRepository(jdbc);
        this.reconciliationSources = new ReconciliationSourceRepository(jdbc);
    }

    public PaymentStateRepository states() {
        return states;
    }

    public PaymentIntentRepository intents() {
        return intents;
    }

    public PaymentRecordRepository records() {
        return records;
    }

    public OperationLeaseRepository leases() {
        return leases;
    }

    public ProviderResultRepository results() {
        return results;
    }

    public RefundIntentRepository refundIntents() {
        return refundIntents;
    }

    public RefundRecordRepository refundRecords() {
        return refundRecords;
    }

    public RefundFactRepository refundFacts() {
        return refundFacts;
    }

    public RecoveryTaskRepository recoveryTasks() {
        return recoveryTasks;
    }

    public ReconciliationSourceRepository reconciliationSources() {
        return reconciliationSources;
    }
}
