package com.paymentsledger.refund;

import com.paymentsledger.financialingestion.RefundSourceResolver;
import com.paymentsledger.payment.RefundExecutor;
import com.paymentsledger.provider.ProviderScope;
import org.springframework.stereotype.Component;

@Component
public final class RefundFinancialSourceResolver implements RefundSourceResolver {
    private final RefundExecutor executor;

    public RefundFinancialSourceResolver(RefundExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void resolve(ProviderScope scope, String refundId) {
        executor.refreshProviderRefund(scope, refundId);
    }
}
