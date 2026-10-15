package com.paymentsledger.refund;

import com.paymentsledger.financialingestion.RefundEvidencePort;
import com.paymentsledger.payment.RefundRepository;
import com.paymentsledger.provider.ProviderScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only persisted provider associations qualify; business success is not a prerequisite for actual movement. */
@Component
public final class RefundFinancialEvidence implements RefundEvidencePort {
    private final RefundRepository repository;

    public RefundFinancialEvidence(RefundRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Evidence> find(ProviderScope scope, String refundId) {
        var row = repository.lookup(scope, refundId);
        if (row == null) return Optional.empty();
        return Optional.of(new Evidence(
                (UUID) row.get("payment_id"),
                (String) row.get("provider_refund_id"),
                (String) row.get("balance_transaction_id"),
                ((Number) row.get("amount_minor")).longValue(),
                (String) row.get("currency"),
                !"CONFLICT".equals(row.get("resolution_status")),
                (String) row.get("failure_balance_transaction_id"),
                (UUID) row.get("id")));
    }
}
