package com.paymentsledger.payment;

import static com.paymentsledger.payment.domain.PaymentIntentRules.rejected;

import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantAccessGuard;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.RefundBalanceTransaction;
import com.paymentsledger.payment.domain.RefundRecord;
import com.paymentsledger.payment.domain.RefundViewRules;
import com.paymentsledger.payment.repository.RefundRecordRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the refund API resource. Field order is part of the JSON contract; business, provider, certainty and
 * accounting status are exposed separately, with the payment's reservation figures.
 */
final class RefundReader {
    private final RefundRecordRepository records;
    private final TransactionTemplate tx;

    RefundReader(RefundRecordRepository records, TransactionTemplate tx) {
        this.records = records;
        this.tx = tx;
    }

    Map<String, Object> read(TenantContext tenant, UUID refundId) {
        TenantAccessGuard.requireScope(tenant, "refunds:read");
        return tx.execute(status -> {
            var refund = records.findScoped(refundId, tenant)
                    .filter(r -> tenant.allowedCurrencies().contains(r.currency()))
                    .orElseThrow(() -> rejected(ErrorCode.NOT_FOUND));
            return resource(refund);
        });
    }

    private Map<String, Object> resource(RefundRecord refund) {
        var control = records.control(refund.paymentId());
        var result = new LinkedHashMap<String, Object>();
        result.put("id", PaymentIds.format("ref", refund.id()));
        result.put("paymentId", PaymentIds.format("pay", refund.paymentId()));
        result.put("amountMinor", refund.amountMinor());
        result.put("currency", refund.currency());
        result.put("refundStatus", refund.status());
        result.put("resolutionStatus", refund.resolution().name());
        result.put("providerStatus", refund.providerStatus());
        result.put("providerRefundId", refund.providerRefundId());
        result.put("reason", refund.reason());
        result.put("controlState", control.state().name());
        result.put(
                "reservation",
                Map.of(
                        "capturedMinor",
                        control.capturedMinor(),
                        "refundedMinor",
                        control.refundedMinor(),
                        "reservedMinor",
                        control.reservedMinor(),
                        "availableMinor",
                        Math.max(0, control.availableMinor())));
        var transactions = records.balanceTransactions(refund);
        // Checked in order and only until the first conflict is found.
        boolean evidenceConflict = transactions.stream()
                .anyMatch(b -> "QUARANTINED".equals(b.state()) || records.hasEvidenceConflict(b.id()));
        result.put("financialStatus", RefundViewRules.financialStatus(refund, transactions, evidenceConflict));
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("refundBalanceTransactionId", refund.balanceTransactionId());
        evidence.put("failureBalanceTransactionId", refund.failureBalanceTransactionId());
        evidence.put("actualFeeRefundMinor", RefundViewRules.actualFeeRefund(transactions));
        evidence.put(
                "postedJournalIds",
                transactions.stream()
                        .map(RefundBalanceTransaction::journalId)
                        .filter(journal -> journal != null)
                        .map(UUID::toString)
                        .toList());
        result.put("evidence", evidence);
        result.put("createdAt", text(refund.createdAt()));
        result.put("updatedAt", text(refund.updatedAt()));
        result.put("lastProviderConfirmedAt", text(refund.lastProviderConfirmedAt()));
        return result;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
