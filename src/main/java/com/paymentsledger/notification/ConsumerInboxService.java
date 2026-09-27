package com.paymentsledger.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Acknowledgment is a separate step, strictly after durable inbox/effect commit. */
public final class ConsumerInboxService {
    public static final String CONSUMER_NAME="merchant-notifications-v1";
    public enum Acceptance { CREATED, DUPLICATE, REJECTED }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ConsumerInboxService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);this.json=json;
    }
    public void consume(byte[] body,Runnable acknowledge) {
        accept(body);
        acknowledge.run();
    }
    public Acceptance accept(byte[] body) {
        outsideTransaction();
        UUID eventId=null;
        if(body!=null && body.length<=16384) {
            try {eventId=UUID.fromString(json.readTree(body).required("eventId").asText());}
            catch(Exception ignored) { /* Persist controlled rejection below, never the untrusted body. */ }
        }
        final UUID id=eventId;
        final byte[] bytes=body==null?new byte[0]:body;
        return tx.execute(s->{
            if(id==null)return reject(bytes,"MALFORMED");
            var rows=jdbc.query("SELECT * FROM platform.outbox_event WHERE id=?",NotificationEvent::read,id);
            if(rows.isEmpty())return reject(bytes,"UNKNOWN_EVENT");
            var event=rows.getFirst();
            if(!Arrays.equals(bytes,event.bytes()))return reject(bytes,"PAYLOAD_CONFLICT");
            int inserted=jdbc.update("""
                INSERT INTO platform.consumer_inbox(consumer_name,event_id,organization_id,environment_id,application_id,payload_digest)
                VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,CONSUMER_NAME,id,event.organizationId(),event.environmentId(),event.applicationId(),event.digest());
            if(inserted==0)return Acceptance.DUPLICATE;
            jdbc.update("""
                INSERT INTO platform.delivery_task(event_id,organization_id,environment_id,application_id,
                    target_url,target_key_ref,target_version,status,last_reason)
                SELECT e.id,e.organization_id,e.environment_id,e.application_id,t.url,t.key_ref,t.version,
                    CASE WHEN t.enabled THEN 'READY' ELSE 'HELD' END,
                    CASE WHEN t.enabled THEN null ELSE 'TARGET_UNAVAILABLE' END
                FROM platform.outbox_event e LEFT JOIN platform.merchant_notification_target t
                    ON (t.organization_id,t.environment_id,t.application_id)=(e.organization_id,e.environment_id,e.application_id)
                WHERE e.id=?
                """,id);
            return Acceptance.CREATED;
        });
    }
    private Acceptance reject(byte[] body,String reason) {
        jdbc.update("""
            INSERT INTO platform.notification_rejection(consumer_name,payload_digest,reason)
            VALUES(?,?,?) ON CONFLICT DO NOTHING
            """,CONSUMER_NAME,digest(body),reason);
        return Acceptance.REJECTED;
    }
    private static String digest(byte[] body) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Notification orchestration requires no active database transaction");
    }
}
