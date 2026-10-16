package com.paymentsledger.payment;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.RefundControl;
import com.paymentsledger.payment.domain.RefundablePayment;

/** PR13 extension point, called with payment/control locks before reserving. No dispute integration is enabled by PR11. */
@FunctionalInterface
public interface RefundEligibilityPolicy {
    void check(TenantContext tenant, RefundablePayment payment, RefundControl control);

    static RefundEligibilityPolicy unrestricted() {
        return (tenant, payment, control) -> {};
    }
}
