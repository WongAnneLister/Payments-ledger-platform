package com.paymentsledger.payment;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.PaymentRecord;
import com.paymentsledger.payment.domain.PaymentViewRules;
import com.paymentsledger.payment.repository.PaymentRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the payment API resource. Field order is part of the JSON contract; business, provider, certainty and
 * accounting status are exposed separately.
 */
final class PaymentReader {
    private final PaymentIntentService intents;
    private final PaymentRecordRepository records;
    private final TransactionTemplate tx;
    private final Clock clock;

    PaymentReader(PaymentIntentService intents, PaymentRecordRepository records, TransactionTemplate tx, Clock clock) {
        this.intents = intents;
        this.records = records;
        this.tx = tx;
        this.clock = clock;
    }

    Map<String, Object> read(TenantContext tenant, UUID paymentId) {
        return tx.execute(status -> resource(intents.scoped(tenant, paymentId, false)));
    }

    private Map<String, Object> resource(PaymentRecord payment) {
        var result = new LinkedHashMap<String, Object>();
        result.put("recovery", recovery(payment.id()));
        result.put("id", PaymentIds.format("pay", payment.id()));
        result.put("businessOrderId", PaymentIds.format("bor", payment.businessOrderId()));
        result.put("orderKey", payment.orderKey());
        result.put("amountMinor", payment.amountMinor());
        result.put("currency", payment.currency());
        result.put("paymentStatus", payment.status().name());
        result.put("providerStatus", payment.providerStatus());
        result.put("resolutionStatus", payment.resolution().name());
        result.put("financialStatus", payment.financial().name());
        result.put("capturedAmountMinor", payment.capturedAmountMinor());
        result.put("revenueShares", List.of());
        result.put("createdAt", payment.createdAt().toString());
        result.put("updatedAt", payment.updatedAt().toString());
        result.put("lastProviderConfirmedAt", text(payment.lastProviderConfirmedAt()));
        var provider = new LinkedHashMap<String, Object>();
        provider.put("checkoutSessionId", payment.checkoutSessionId());
        provider.put("paymentIntentId", payment.paymentIntentId());
        provider.put("latestChargeId", payment.latestChargeId());
        provider.put("attempts", attempts(payment.id()));
        result.put("provider", provider);
        result.put("nextAction", nextAction(payment));
        return result;
    }

    private Map<String, Object> recovery(UUID paymentId) {
        var summary = PaymentViewRules.recoverySummary(records.recoveryTasks(paymentId), clock.instant());
        var result = new LinkedHashMap<String, Object>();
        result.put("status", summary.status());
        result.put("nextAttemptAt", text(summary.nextAttemptAt()));
        result.put("attemptCount", summary.attemptCount());
        result.put("lastQueryAt", text(summary.lastQueryAt()));
        result.put("reason", summary.reason());
        return result;
    }

    private List<Map<String, Object>> attempts(UUID paymentId) {
        var views = new ArrayList<Map<String, Object>>();
        for (var attempt : records.attempts(paymentId)) {
            var view = new LinkedHashMap<String, Object>();
            view.put("attemptId", PaymentIds.format("att", attempt.id()));
            view.put("providerObjectType", "CHARGE");
            view.put("providerObjectId", attempt.providerObjectId());
            view.put("providerStatus", attempt.providerStatus());
            view.put("failureCode", attempt.failureCode());
            view.put("captured", attempt.captured());
            view.put("amountMinor", attempt.amountMinor());
            view.put("balanceTransactionId", attempt.balanceTransactionId());
            view.put("createdAt", text(attempt.providerCreatedAt()));
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> nextAction(PaymentRecord payment) {
        var action = PaymentViewRules.nextAction(payment, clock.instant());
        if (action.hostedUrl() == null) return Map.of("type", action.type());
        return Map.of("type", action.type(), "hostedUrl", action.hostedUrl(), "expiresAt", text(action.expiresAt()));
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
