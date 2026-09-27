package com.paymentsledger.notification;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxRepository {
    public record Claim(NotificationEvent event, UUID token,int attempt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    public OutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);this.clock=clock;
    }
    public NotificationEvent find(UUID eventId) {
        var rows=jdbc.query("SELECT * FROM platform.outbox_event WHERE id=?",NotificationEvent::read,eventId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Claim claim(Duration lease) {
        return tx.execute(s->{
            var rows=jdbc.query("""
                SELECT * FROM platform.outbox_event WHERE status<>'PUBLISHED' AND available_at<=?
                  AND (lease_until IS NULL OR lease_until<=?)
                ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,NotificationEvent::read,now(),now());
            if(rows.isEmpty())return null;
            var event=rows.getFirst();var token=UUID.randomUUID();
            jdbc.update("""
                UPDATE platform.outbox_event SET status='RUNNING',lease_token=?,lease_until=?,
                attempt_count=attempt_count+1 WHERE id=?
                """,token,Timestamp.from(clock.instant().plus(lease)),event.id());
            int attempt=jdbc.queryForObject("SELECT attempt_count FROM platform.outbox_event WHERE id=?",Integer.class,event.id());
            return new Claim(event,token,attempt);
        });
    }
    public boolean published(Claim claim) {
        return tx.execute(s->jdbc.update("""
            UPDATE platform.outbox_event SET status='PUBLISHED',published_at=?,lease_until=null,last_reason=null
            WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>?
            """,now(),claim.event().id(),claim.token(),now())==1);
    }
    public boolean retry(Claim claim,Duration backoff) {
        return tx.execute(s->jdbc.update("""
            UPDATE platform.outbox_event SET status='READY',available_at=?,lease_until=null,last_reason='BROKER_UNAVAILABLE'
            WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>?
            """,Timestamp.from(clock.instant().plus(backoff)),claim.event().id(),claim.token(),now())==1);
    }
    private Timestamp now(){return Timestamp.from(clock.instant());}
}
