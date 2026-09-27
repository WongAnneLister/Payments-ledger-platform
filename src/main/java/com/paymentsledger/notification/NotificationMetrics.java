package com.paymentsledger.notification;

import io.micrometer.core.instrument.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persistent backlog, not process-local counters, remains observable across restarts. */
public final class NotificationMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public NotificationMetrics(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void bind(MeterRegistry meters) {
        for(String name:List.of("outbox_backlog","delivery_backlog","held","dead","delivered","retry_backlog","delivery_attempts","oldest_age_seconds"))
            Gauge.builder("payments.notifications."+name,this,m->m.value(name)).register(meters);
    }
    public double value(String metric) {
        String sql=switch(metric) {
            case "outbox_backlog"->"SELECT count(*) FROM platform.outbox_event WHERE status<>'PUBLISHED'";
            case "delivery_backlog"->"SELECT count(*) FROM platform.delivery_task WHERE status IN ('READY','RUNNING','HELD')";
            case "held"->"SELECT count(*) FROM platform.delivery_task WHERE status='HELD'";
            case "dead"->"SELECT count(*) FROM platform.delivery_task WHERE status='DEAD'";
            case "delivered"->"SELECT count(*) FROM platform.delivery_task WHERE status='DELIVERED'";
            case "retry_backlog"->"SELECT count(*) FROM platform.delivery_task WHERE status='READY' AND attempt_count>0";
            case "delivery_attempts"->"SELECT coalesce(sum(attempt_count),0) FROM platform.delivery_task";
            case "oldest_age_seconds"->null;
            default->throw new IllegalArgumentException("Unknown notification metric");
        };
        if(sql!=null)return jdbc.queryForObject(sql,Double.class);
        return jdbc.queryForObject("""
            SELECT greatest(0,coalesce(extract(epoch FROM (?::timestamptz-min(created_at))),0))
            FROM (SELECT occurred_at created_at FROM platform.outbox_event WHERE status<>'PUBLISHED'
            UNION ALL SELECT created_at FROM platform.delivery_task WHERE status IN ('READY','RUNNING','HELD')) pending
            """,Double.class,Timestamp.from(clock.instant()));
    }
}
