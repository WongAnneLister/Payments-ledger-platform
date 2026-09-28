package com.paymentsledger.payment;

import com.paymentsledger.identity.TenantContext;
import java.util.Map;

/** PR13 extension point, called with payment/control locks before reserving. No dispute integration is enabled by PR11. */
@FunctionalInterface
public interface RefundEligibilityPolicy {
 void check(TenantContext tenant,Map<String,Object> payment,Map<String,Object> control);
 static RefundEligibilityPolicy unrestricted(){return (tenant,payment,control)->{};}
}
