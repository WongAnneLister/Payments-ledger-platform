package com.paymentsledger.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.PayoutSourceResolver;
import com.paymentsledger.provider.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Missing-source queries persist success/failure evidence and perform no external writes. */
@Component
public final class PayoutSourceRecovery implements PayoutSourceResolver {
    private final PayoutPort provider;
    private final PayoutService payouts;
    private final PayoutIngestionService reads;

    @org.springframework.beans.factory.annotation.Autowired
    public PayoutSourceRecovery(
            ObjectProvider<PayoutPort> provider, PayoutService payouts, PayoutIngestionService reads) {
        this.provider = provider.getIfAvailable();
        this.payouts = payouts;
        this.reads = reads;
    }

    public PayoutSourceRecovery(
            PayoutPort provider,
            PayoutService payouts,
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json) {
        this.provider = provider;
        this.payouts = payouts;
        this.reads = new PayoutIngestionService(jdbc, tx, json, provider, null, payouts);
    }

    @Override
    public void resolve(ProviderScope scope, String id) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Payout query requires outside transaction");
        reads.validateScope(scope);
        if (provider == null) return;
        var result = provider.getPayout(scope, id);
        reads.recordRead(scope, null, "GET_PAYOUT", id, result);
        if (PayoutIngestionService.evidenceError(result) == null
                && id.equals(result.payload().payoutId())) payouts.accept(scope, result.payload(), result.evidence());
    }
}
