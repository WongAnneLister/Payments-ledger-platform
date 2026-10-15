package com.paymentsledger.payment;

import com.paymentsledger.common.error.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow manual wake owner: preserve existing operation identity, horizon and worker lock order.
 */
@Service
public class RecoveryTaskOperations {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RecoveryTaskOperations(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> wake(UUID organization, UUID environment, UUID id) {
        var initial = scoped("recovery_task", organization, environment, id, false);
        var active = jdbc.queryForObject(
                """
            SELECT count(*) FROM platform.provider_account p JOIN platform.environment_config e ON e.id=p.environment_id
            JOIN platform.organization o ON o.id=p.organization_id JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id AND c.environment_id=p.environment_id
            WHERE p.id=? AND p.organization_id=? AND p.environment_id=? AND p.status='ACTIVE' AND e.status='ACTIVE'
             AND o.status='ACTIVE' AND c.status='ACTIVE' AND c.currency=? AND NOT p.livemode AND NOT e.livemode
            """,
                Long.class,
                initial.get("provider_account_id"),
                organization,
                environment,
                initial.get("currency"));
        if (active != 1) throw notFound();
        Map<String, Object> op = null;
        Map<String, Object> task;
        if (initial.get("operation_id") != null) {
            UUID parent = (UUID) initial.get("payment_id");
            if (parent == null)
                parent = (UUID) scoped("refund", organization, environment, (UUID) initial.get("refund_id"), false)
                        .get("payment_id");
            scoped("payment", organization, environment, parent, true);
            if (initial.get("refund_id") != null)
                scoped("refund", organization, environment, (UUID) initial.get("refund_id"), true);
            op = scoped("provider_operation", organization, environment, (UUID) initial.get("operation_id"), true);
            task = scoped("recovery_task", organization, environment, id, true);
        } else {
            // ReconciliationSourceRecovery.link locks task before source BT. Keep that order here.
            task = scoped("recovery_task", organization, environment, id, true);
            scoped("balance_transaction", organization, environment, (UUID) initial.get("source_bt_id"), true);
        }
        if (liveLease(task) || Set.of("DONE", "ESCALATED").contains(task.get("status"))) throw conflict();
        if (op != null) {
            boolean unknown = Set.of("UNKNOWN", "SENDING", "ESCALATED").contains(op.get("status"));
            boolean expired = op.get("retry_until") == null
                    || !((Timestamp) op.get("retry_until")).toInstant().isAfter(clock.instant());
            if (liveLease(op)
                    || Set.of("RESOLVED", "ESCALATED").contains(op.get("status"))
                    || unknown
                            && expired
                            && Set.of("CREATE_CHECKOUT", "CREATE_REFUND").contains(op.get("operation_type")))
                throw conflict();
        }
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='READY',available_at=?,updated_at=now(),version=version+1
                WHERE id=?""", Timestamp.from(clock.instant()), id);
        return Map.of(
                "taskId",
                id.toString(),
                "status",
                "PENDING",
                "nextScheduledAt",
                clock.instant().toString(),
                "note",
                "Existing recovery obligation awakened; no provider request sent");
    }

    private boolean liveLease(Map<String, Object> row) {
        return row.get("lease_until") instanceof Timestamp t && t.toInstant().isAfter(clock.instant());
    }

    private Map<String, Object> scoped(String table, UUID org, UUID env, UUID id, boolean lock) {
        var rows = jdbc.queryForList(
                "SELECT * FROM platform."
                        + table
                        + " WHERE id=? AND organization_id=? AND environment_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                id,
                org,
                env);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Recovery task not found in caller scope");
    }

    private static ApiException conflict() {
        return new ApiException(
                ErrorCode.EVIDENCE_CONFLICT, "Task is leased, final or has an uncertain expired operation");
    }
}
