package com.paymentsledger.notification;

import org.springframework.jdbc.core.JdbcTemplate;

/** PR15 may supply an independently reviewed reconciliation policy. Default is deny. */
public interface FulfillmentEligibility {
    record Decision(boolean eligible,String reason) {
        public static Decision allow(){return new Decision(true,"ELIGIBLE");}
        public static Decision hold(String reason){return new Decision(false,reason);}
    }
    Decision evaluate(NotificationEvent event);

    final class Database implements FulfillmentEligibility {
        private final JdbcTemplate jdbc;
        public Database(JdbcTemplate jdbc){this.jdbc=jdbc;}
        @Override public Decision evaluate(NotificationEvent event) {
            if(!event.eventType().equals("payment.succeeded"))return Decision.allow();
            if(!event.origin().equals("NATIVE"))return Decision.hold("ORIGIN_NOT_ELIGIBLE");
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment p
                JOIN platform.business_order o ON o.id=p.business_order_id
                 AND (o.organization_id,o.environment_id,o.application_id,o.provider_account_id,o.currency)
                   =(p.organization_id,p.environment_id,p.application_id,p.provider_account_id,p.currency)
                JOIN platform.order_fulfillment_eligibility e ON e.order_id=o.id
                 AND (e.organization_id,e.environment_id,e.application_id,e.provider_account_id,e.currency)
                   =(o.organization_id,o.environment_id,o.application_id,o.provider_account_id,o.currency)
                WHERE p.id=? AND o.id=? AND p.organization_id=? AND p.environment_id=? AND p.application_id=?
                 AND p.provider_account_id=? AND p.currency=? AND p.payment_status='SUCCEEDED'
                 AND p.resolution_status='KNOWN' AND p.captured_amount_minor=p.amount_minor
                 AND p.amount_minor=o.amount_minor AND o.amount_minor=e.amount_minor
                 AND e.eligible AND e.origin='NATIVE' AND e.policy_version=1
                 AND p.amount_minor=(?::jsonb->>'amountMinor')::bigint
                 AND p.captured_amount_minor=(?::jsonb->>'capturedAmountMinor')::bigint
                """,Integer.class,event.paymentId(),event.orderId(),event.organizationId(),event.environmentId(),
                    event.applicationId(),event.providerAccountId(),event.currency(),event.payload(),event.payload());
            return count!=null && count==1?Decision.allow():Decision.hold("FULFILLMENT_NOT_ELIGIBLE");
        }
    }
}
