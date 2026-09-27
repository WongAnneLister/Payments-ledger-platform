package com.paymentsledger.notification;

import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Network calls never hold these short claim/completion transactions open. */
public final class DeliveryRepository {
    public record Claim(UUID id,UUID token,NotificationEvent event,MerchantTransport.Target target,long targetVersion,int attempt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final FulfillmentEligibility eligibility;
    public DeliveryRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,FulfillmentEligibility eligibility) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);this.clock=clock;this.eligibility=eligibility;
    }
    public Claim claim(Duration lease){return claim(lease,8);}
    public Claim claim(Duration lease,int maxAttempts) {
        return tx.execute(s->{
            // Bound work per transaction; held records are postponed so one invalid order cannot starve the queue.
            for(int i=0;i<25;i++) {
                var rows=jdbc.queryForList("""
                    SELECT * FROM platform.delivery_task WHERE status IN ('READY','RUNNING','HELD')
                     AND available_at<=? AND (lease_until IS NULL OR lease_until<=?)
                    ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,now(),now());
                if(rows.isEmpty())return null;
                var row=rows.getFirst();UUID id=(UUID)row.get("id");
                var event=jdbc.queryForObject("SELECT * FROM platform.outbox_event WHERE id=?",NotificationEvent::read,row.get("event_id"));
                var decision=eligibility.evaluate(event);
                if(!decision.eligible()){hold(id,decision.reason());continue;}
                var targets=targets(event);
                if(targets.isEmpty() || !Boolean.TRUE.equals(targets.getFirst().get("enabled"))){hold(id,"TARGET_UNAVAILABLE");continue;}
                var target=targets.getFirst();
                if(row.get("target_version")!=null && !sameTarget(row,target)){hold(id,"TARGET_CHANGED");continue;}
                URI uri;
                try {uri=URI.create((String)target.get("url"));if(uri.getHost()==null)throw new IllegalArgumentException();}
                catch(IllegalArgumentException e){hold(id,"INVALID_DESTINATION");continue;}
                int attempt=((Number)row.get("attempt_count")).intValue();
                if(attempt>=maxAttempts){jdbc.update("UPDATE platform.delivery_task SET status='DEAD',last_reason='ATTEMPTS_EXHAUSTED',lease_until=null,completed_at=? WHERE id=?",now(),id);continue;}
                var token=UUID.randomUUID();
                jdbc.update("""
                    UPDATE platform.delivery_task SET status='RUNNING',target_url=?,target_key_ref=?,target_version=?,
                     lease_token=?,lease_until=?,attempt_count=attempt_count+1,
                     first_attempt_at=coalesce(first_attempt_at,?),last_attempt_at=?,last_reason=null WHERE id=?
                    """,target.get("url"),target.get("key_ref"),target.get("version"),token,
                        Timestamp.from(clock.instant().plus(lease)),now(),now(),id);
                return new Claim(id,token,event,new MerchantTransport.Target(uri,(String)target.get("key_ref")),
                        ((Number)target.get("version")).longValue(),attempt+1);
            }
            return null;
        });
    }
    /** Last eligibility/configuration check immediately before the external HTTP boundary. */
    public boolean recheck(Claim claim) {
        return tx.execute(s->{
            var rows=jdbc.queryForList("""
                SELECT * FROM platform.delivery_task WHERE id=? AND lease_token=? AND status='RUNNING'
                 AND lease_until>? FOR UPDATE
                """,claim.id(),claim.token(),now());
            if(rows.isEmpty())return false;
            var decision=eligibility.evaluate(claim.event());
            var targets=targets(claim.event());
            String reason=!decision.eligible()?decision.reason():targets.isEmpty() || !Boolean.TRUE.equals(targets.getFirst().get("enabled"))?
                    "TARGET_UNAVAILABLE":!sameTarget(rows.getFirst(),targets.getFirst())?"TARGET_CHANGED":null;
            if(reason!=null){hold(claim.id(),reason);return false;}
            return true;
        });
    }
    public boolean complete(Claim claim,MerchantTransport.Result result,int maxAttempts,Duration base,Duration cap) {
        return tx.execute(s->{
            String reason=controlledReason(result);
            boolean held=Set.of("KEY_UNAVAILABLE","INVALID_DESTINATION").contains(reason);
            String status=held?"HELD":result.outcome()==MerchantTransport.Outcome.DELIVERED?"DELIVERED":
                    result.outcome()==MerchantTransport.Outcome.PERMANENT_REJECT || claim.attempt()>=maxAttempts?"DEAD":"READY";
            long seconds=Math.min(cap.toSeconds(),Math.max(1,base.toSeconds())*(1L<<Math.min(20,claim.attempt()-1)));
            return jdbc.update("""
                UPDATE platform.delivery_task SET status=?,available_at=?,lease_until=null,last_reason=?,last_status=?,
                 attempt_count=attempt_count-CASE WHEN ? THEN 1 ELSE 0 END,
                 completed_at=CASE WHEN ? IN ('DELIVERED','DEAD') THEN ?::timestamptz ELSE null END
                WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>?
                """,status,Timestamp.from(clock.instant().plusSeconds(held?60:seconds)),reason,result.status(),held,status,now(),
                    claim.id(),claim.token(),now())==1;
        });
    }
    private static String controlledReason(MerchantTransport.Result result) {
        if(Set.of("KEY_UNAVAILABLE","INVALID_DESTINATION").contains(result.code()))return result.code();
        return switch(result.outcome()) {
            case DELIVERED->"HTTP_DELIVERED";case RETRYABLE->"HTTP_RETRYABLE";case PERMANENT_REJECT->"HTTP_PERMANENT";
        };
    }
    private List<Map<String,Object>> targets(NotificationEvent event) {
        return jdbc.queryForList("""
            SELECT * FROM platform.merchant_notification_target
            WHERE organization_id=? AND environment_id=? AND application_id=?
            """,event.organizationId(),event.environmentId(),event.applicationId());
    }
    private boolean sameTarget(Map<String,Object> task,Map<String,Object> target) {
        return Objects.equals(task.get("target_url"),target.get("url"))
                && Objects.equals(task.get("target_key_ref"),target.get("key_ref"))
                && Objects.equals(task.get("target_version"),target.get("version"));
    }
    private void hold(UUID id,String reason) {
        jdbc.update("""
            UPDATE platform.delivery_task SET status='HELD',last_reason=?,lease_until=null,available_at=? WHERE id=?
            """,reason,Timestamp.from(clock.instant().plusSeconds(60)),id);
    }
    private Timestamp now(){return Timestamp.from(clock.instant());}
}
