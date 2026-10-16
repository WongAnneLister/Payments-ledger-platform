package com.paymentsledger.payment.repository;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.RefundBalanceTransaction;
import com.paymentsledger.payment.domain.RefundControl;
import com.paymentsledger.payment.domain.RefundControlState;
import com.paymentsledger.payment.domain.RefundRecord;
import com.paymentsledger.payment.domain.ResolutionStatus;
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

/** Reads behind the refund API resource. */
public class RefundRecordRepository {
    private static final RowMapper<RefundRecord> REFUND = (rs, row) -> new RefundRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("refund_status"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            rs.getString("provider_status"),
            rs.getString("provider_refund_id"),
            rs.getString("reason"),
            rs.getString("balance_transaction_id"),
            rs.getString("failure_balance_transaction_id"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")),
            instant(rs.getTimestamp("last_provider_confirmed_at")));

    private static final RowMapper<RefundBalanceTransaction> TRANSACTION = (rs, row) -> new RefundBalanceTransaction(
            rs.getObject("id", UUID.class),
            rs.getString("bt_id"),
            rs.getString("state"),
            rs.getObject("fee_minor", Long.class),
            rs.getObject("journal_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    public RefundRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** The refund if it belongs to the caller's application and provider account. */
    public Optional<RefundRecord> findScoped(UUID refundId, TenantContext tenant) {
        var rows = jdbc.query(
                """
                SELECT *
                FROM platform.refund
                WHERE id=:id AND organization_id=:organizationId AND environment_id=:environmentId \
                AND application_id=:applicationId AND provider_account_id=:providerAccountId""",
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("organizationId", tenant.organizationId())
                        .addValue("environmentId", tenant.environmentId())
                        .addValue("applicationId", tenant.applicationId())
                        .addValue("providerAccountId", tenant.allowedProviderAccountId()),
                REFUND);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public RefundControl control(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.refund_control WHERE payment_id=:paymentId",
                Map.of("paymentId", paymentId),
                (rs, row) -> new RefundControl(
                        rs.getObject("payment_id", UUID.class),
                        rs.getLong("captured_minor"),
                        rs.getLong("refunded_minor"),
                        rs.getLong("reserved_minor"),
                        RefundControlState.valueOf(rs.getString("control_state")),
                        rs.getLong("version")));
    }

    /** Balance transactions attributed to the refund or named by it as its refund or failure transaction. */
    public List<RefundBalanceTransaction> balanceTransactions(RefundRecord refund) {
        return jdbc.query(
                """
                SELECT *
                FROM platform.balance_transaction
                WHERE organization_id=:organizationId
                    AND environment_id=:environmentId
                    AND provider_account_id=:providerAccountId
                    AND (refund_id=:refundId OR bt_id IN (:btId,:failureBtId))""",
                new MapSqlParameterSource()
                        .addValue("organizationId", refund.organizationId())
                        .addValue("environmentId", refund.environmentId())
                        .addValue("providerAccountId", refund.providerAccountId())
                        .addValue("refundId", refund.id())
                        .addValue("btId", refund.balanceTransactionId())
                        .addValue("failureBtId", refund.failureBalanceTransactionId()),
                TRANSACTION);
    }

    public boolean hasEvidenceConflict(UUID balanceTransactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform.bt_evidence_conflict WHERE balance_transaction_id=:id)""", Map.of("id", balanceTransactionId), Boolean.class));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
