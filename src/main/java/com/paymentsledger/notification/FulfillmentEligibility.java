package com.paymentsledger.notification;

import org.springframework.jdbc.core.JdbcTemplate;

/** Native fulfillment and proven historical recovery both require current server order eligibility. */
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
            if(!java.util.Set.of("NATIVE","RECONCILIATION").contains(event.origin()))return Decision.hold("ORIGIN_NOT_ELIGIBLE");
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
                 AND e.eligible AND e.policy_version=1 AND p.origin=?
                 AND ((?='NATIVE' AND e.origin='NATIVE') OR (?='RECONCILIATION' AND EXISTS(
                  SELECT 1 FROM platform.reconciliation_payment_provenance rp
                   JOIN platform.order_provider_binding b ON b.id=rp.binding_id
                   JOIN platform.reconciliation_source_observation ro ON ro.id=rp.observation_id
                  WHERE rp.payment_id=p.id AND b.order_id=o.id AND b.payment_intent_id=p.payment_intent_id
                   AND b.amount_minor=p.amount_minor AND rp.charge_id=p.latest_charge_id
                   AND rp.run_id=p.reconciliation_run_id AND rp.source_bt_id=p.reconciliation_source_bt_id
                   AND rp.evidence_digest=p.reconciliation_evidence_digest AND ro.outcome='LINKED'
                   AND ro.run_id=rp.run_id AND ro.source_bt_id=rp.source_bt_id AND ro.digest=rp.evidence_digest
                   AND NOT EXISTS(SELECT 1 FROM platform.bt_evidence_conflict conflict WHERE conflict.balance_transaction_id=rp.source_bt_id)
                   AND EXISTS(SELECT 1 FROM platform.balance_transaction bt WHERE bt.id=rp.source_bt_id AND bt.state NOT IN ('QUARANTINED','EXCLUDED'))
                   AND (b.eligible_until IS NULL OR b.eligible_until>current_timestamp))))
                 AND p.amount_minor=(?::jsonb->>'amountMinor')::bigint
                 AND p.captured_amount_minor=(?::jsonb->>'capturedAmountMinor')::bigint
                """,Integer.class,event.paymentId(),event.orderId(),event.organizationId(),event.environmentId(),
                    event.applicationId(),event.providerAccountId(),event.currency(),event.origin(),event.origin(),event.origin(),event.payload(),event.payload());
            return count!=null && count==1?Decision.allow():Decision.hold("FULFILLMENT_NOT_ELIGIBLE");
        }
    }
}
