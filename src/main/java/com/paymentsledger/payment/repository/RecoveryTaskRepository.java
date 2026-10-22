package com.paymentsledger.payment.repository;

import com.paymentsledger.payment.domain.RecoveryWakeRules.Operation;
import com.paymentsledger.payment.domain.RecoveryWakeRules.Task;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Data access for an operator waking a recovery task. Every row is read within the caller's organization and
 * environment; a row outside that scope reads as absent. Locks follow the worker order: payment, refund, operation,
 * task for operation tasks, and task before balance transaction for reconciliation source tasks.
 */
public class RecoveryTaskRepository {
    private static final RowMapper<Task> TASK = (rs, n) -> new Task(
            rs.getObject("id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getString("currency"),
            rs.getObject("operation_id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getObject("refund_id", UUID.class),
            rs.getObject("source_bt_id", UUID.class),
            rs.getString("status"),
            instant(rs, "lease_until"));
    private static final RowMapper<Operation> OPERATION = (rs, n) -> new Operation(
            rs.getString("status"),
            rs.getString("operation_type"),
            instant(rs, "retry_until"),
            instant(rs, "lease_until"));

    private final NamedParameterJdbcTemplate jdbc;

    public RecoveryTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public Optional<Task> task(UUID organization, UUID environment, UUID id, boolean lock) {
        return scoped("recovery_task", organization, environment, id, lock, TASK);
    }

    /** The payment a refund belongs to, read without a lock. */
    public Optional<UUID> refundPayment(UUID organization, UUID environment, UUID refund) {
        return scoped(
                "refund", organization, environment, refund, false, (rs, n) -> rs.getObject("payment_id", UUID.class));
    }

    public boolean lockPayment(UUID organization, UUID environment, UUID id) {
        return lock("payment", organization, environment, id);
    }

    public boolean lockRefund(UUID organization, UUID environment, UUID id) {
        return lock("refund", organization, environment, id);
    }

    public boolean lockBalanceTransaction(UUID organization, UUID environment, UUID id) {
        return lock("balance_transaction", organization, environment, id);
    }

    public Optional<Operation> lockOperation(UUID organization, UUID environment, UUID id) {
        return scoped("provider_operation", organization, environment, id, true, OPERATION);
    }

    /** Whether the task's provider account, environment, organization and currency are all active and in test mode. */
    public boolean activeTestAccount(UUID organization, UUID environment, UUID providerAccount, String currency) {
        var active = jdbc.queryForObject(
                """
            SELECT count(*) FROM platform.provider_account p JOIN platform.environment_config e ON e.id=p.environment_id
            JOIN platform.organization o ON o.id=p.organization_id JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id AND c.environment_id=p.environment_id
            WHERE p.id=:providerAccount AND p.organization_id=:organization AND p.environment_id=:environment AND p.status='ACTIVE' AND e.status='ACTIVE'
             AND o.status='ACTIVE' AND c.status='ACTIVE' AND c.currency=:currency AND NOT p.livemode AND NOT e.livemode
            """,
                new MapSqlParameterSource()
                        .addValue("providerAccount", providerAccount)
                        .addValue("organization", organization)
                        .addValue("environment", environment)
                        .addValue("currency", currency),
                Long.class);
        return active == 1;
    }

    /** Makes the task due at {@code now}; its lease, attempts and operation are left as they are. */
    public void ready(UUID id, Instant now) {
        jdbc.update(
                """
                UPDATE platform.recovery_task
                SET status='READY',available_at=:now,updated_at=now(),version=version+1
                WHERE id=:id""",
                new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", id));
    }

    private boolean lock(String table, UUID organization, UUID environment, UUID id) {
        return scoped(table, organization, environment, id, true, (rs, n) -> Boolean.TRUE)
                .isPresent();
    }

    private <T> Optional<T> scoped(
            String table, UUID organization, UUID environment, UUID id, boolean lock, RowMapper<T> mapper) {
        List<T> rows = jdbc.query(
                "SELECT * FROM platform."
                        + table
                        + " WHERE id=:id AND organization_id=:organization AND environment_id=:environment"
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("organization", organization)
                        .addValue("environment", environment),
                mapper);
        return rows.stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
