package com.paymentsledger.payment.repository;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.FinancialStatus;
import com.paymentsledger.payment.domain.PaymentRecord;
import com.paymentsledger.payment.domain.PaymentStatus;
import com.paymentsledger.payment.domain.RecoveryTaskView;
import com.paymentsledger.payment.domain.ResolutionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads payments within a tenant's scope, with what the API shows about their attempts and recovery. */
public class PaymentRecordRepository {
    /** A charge attempt as the API lists it. */
    public record AttemptRow(
            UUID id,
            String providerObjectId,
            String providerStatus,
            String failureCode,
            Boolean captured,
            Long amountMinor,
            String balanceTransactionId,
            Instant providerCreatedAt) {}

    private static final RowMapper<PaymentRecord> PAYMENT = (rs, row) -> new PaymentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("business_order_id", UUID.class),
            rs.getString("order_key"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            PaymentStatus.valueOf(rs.getString("payment_status")),
            rs.getString("provider_status"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            FinancialStatus.valueOf(rs.getString("financial_status")),
            rs.getLong("captured_amount_minor"),
            rs.getString("checkout_session_id"),
            rs.getString("payment_intent_id"),
            rs.getString("latest_charge_id"),
            rs.getString("hosted_url"),
            instant(rs, "expires_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "last_provider_confirmed_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** The payment if it belongs to the tenant's organization, environment, application and provider account. */
    public Optional<PaymentRecord> findScoped(TenantContext tenant, UUID paymentId, boolean lock) {
        var rows = jdbc.query(
                """
                SELECT p.*,o.order_key
                FROM platform.payment p
                JOIN platform.business_order o ON o.id=p.business_order_id
                WHERE p.id=:id
                    AND p.organization_id=:organizationId
                    AND p.environment_id=:environmentId
                    AND p.application_id=:applicationId
                    AND p.provider_account_id=:providerAccountId""" + (lock ? " FOR UPDATE OF p" : ""),
                new MapSqlParameterSource()
                        .addValue("id", paymentId)
                        .addValue("organizationId", tenant.organizationId())
                        .addValue("environmentId", tenant.environmentId())
                        .addValue("applicationId", tenant.applicationId())
                        .addValue("providerAccountId", tenant.allowedProviderAccountId()),
                PAYMENT);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Recovery tasks, most urgent first: escalated, running, waiting or ready, then the rest; earliest due first. */
    public List<RecoveryTaskView> recoveryTasks(UUID paymentId) {
        return jdbc.query(
                """
                SELECT t.*,o.last_query_at,o.status operation_status
                FROM platform.recovery_task t
                JOIN platform.provider_operation o ON o.id=t.operation_id
                WHERE t.payment_id=:paymentId
                ORDER BY
                    CASE t.status WHEN 'ESCALATED' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'WAITING' THEN 2 WHEN 'READY'
                        THEN 2 ELSE 3 END,
                    t.available_at""",
                Map.of("paymentId", paymentId),
                (rs, row) -> new RecoveryTaskView(
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        instant(rs, "available_at"),
                        instant(rs, "lease_until"),
                        rs.getString("reason"),
                        instant(rs, "last_query_at")));
    }

    public List<AttemptRow> attempts(UUID paymentId) {
        return jdbc.query(
                """
                SELECT *
                FROM platform.payment_attempt
                WHERE payment_id=:paymentId
                ORDER BY provider_created_at,id""",
                Map.of("paymentId", paymentId),
                (rs, row) -> new AttemptRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider_object_id"),
                        rs.getString("provider_status"),
                        rs.getString("failure_code"),
                        rs.getObject("captured", Boolean.class),
                        rs.getObject("amount_minor", Long.class),
                        rs.getString("balance_transaction_id"),
                        instant(rs, "provider_created_at")));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
