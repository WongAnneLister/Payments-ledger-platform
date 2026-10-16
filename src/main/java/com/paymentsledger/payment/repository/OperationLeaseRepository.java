package com.paymentsledger.payment.repository;

import com.paymentsledger.payment.domain.OperationLeaseRules.LeaseGrant;
import com.paymentsledger.payment.domain.StripeDeployment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Data access for leasing provider operations: the scheduler queries, row locks taken in the global order (payment,
 * refund control, refund, operation, task), lease writes fenced by token and version, per-account batch metering,
 * escalation and rescheduling.
 *
 * <p>Operation, task, payment and refund rows are returned as maps where they travel inside
 * {@code PaymentRepository.Claim}, whose consumers still read columns by name.
 */
public class OperationLeaseRepository {
    /** The business object an operation acts on. */
    public record Target(UUID paymentId, UUID refundId) {}

    /** What rescheduling needs to know about an operation. */
    public record OperationRow(UUID id, UUID paymentId, UUID refundId, String status, String type, int attemptCount) {}

    /** Status and certainty of the payment or refund an operation acts on. */
    public record Outcome(String status, String resolution) {}

    private final NamedParameterJdbcTemplate jdbc;

    public OperationLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** PREPARED operations with READY tasks, oldest first, on active test accounts of {@code provider}. */
    public List<UUID> sendable(String provider, StripeDeployment stripe, int limit) {
        return jdbc.queryForList("""
            SELECT o.id FROM platform.provider_operation o
            JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.payment pay ON pay.id=o.payment_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            WHERE o.status='PREPARED' AND t.status='READY' AND pay.resolution_status<>'CONFLICT'
              AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=:provider
              AND (:provider<>'STRIPE' OR (p.provider_account_ref=:stripeAccount AND o.organization_id::text=:stripeOrganization AND o.environment_id::text=:stripeEnvironment AND o.provider_account_id::text=:stripeProviderAccount))
            ORDER BY o.created_at,o.id LIMIT :limit
            """, deployment(provider, stripe).addValue("limit", limit), UUID.class);
    }

    /** Recoverable operations whose task is due, whose lease has expired and whose account batch is open. */
    public List<UUID> due(String provider, StripeDeployment stripe, Instant now, int limit) {
        return jdbc.queryForList(
                """
            SELECT o.id FROM platform.provider_operation o JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            LEFT JOIN platform.recovery_account_limit lim ON lim.organization_id=o.organization_id AND lim.environment_id=o.environment_id AND lim.provider_account_id=o.provider_account_id
            WHERE t.status IN ('READY','WAITING','RUNNING') AND t.available_at<=:now AND (o.lease_until IS NULL OR o.lease_until<=:now)
              AND o.status<>'ESCALATED' AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=:provider AND (lim.next_batch_at IS NULL OR lim.next_batch_at<=:now)
              AND (:provider<>'STRIPE' OR (p.provider_account_ref=:stripeAccount AND o.organization_id::text=:stripeOrganization AND o.environment_id::text=:stripeEnvironment AND o.provider_account_id::text=:stripeProviderAccount))
            ORDER BY t.available_at,o.id LIMIT :limit
            """,
                deployment(provider, stripe)
                        .addValue("now", Timestamp.from(now))
                        .addValue("limit", limit),
                UUID.class);
    }

    /**
     * L2 candidates: unleased operations whose payment or refund is not yet settled with certainty or posted evidence,
     * and whose task is finished or due.
     */
    public List<UUID> stranded(String provider, StripeDeployment stripe, Instant now, int limit) {
        return jdbc.queryForList(
                """
            SELECT o.id FROM platform.provider_operation o LEFT JOIN platform.payment p ON p.id=o.payment_id LEFT JOIN platform.refund r ON r.id=o.refund_id JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.provider_account a ON a.id=o.provider_account_id
            JOIN platform.application app ON app.id=o.application_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config env ON env.id=o.environment_id
            JOIN platform.org_currency_enabled cur ON cur.organization_id=o.organization_id AND cur.environment_id=o.environment_id AND cur.currency=o.currency
            WHERE a.status='ACTIVE' AND app.status='ACTIVE' AND org.status='ACTIVE' AND env.status='ACTIVE' AND cur.status='ACTIVE' AND NOT a.livemode AND NOT env.livemode AND a.provider=:provider
             AND (:provider<>'STRIPE' OR (a.provider_account_ref=:stripeAccount AND o.organization_id::text=:stripeOrganization AND o.environment_id::text=:stripeEnvironment AND o.provider_account_id::text=:stripeProviderAccount))
             AND o.status<>'ESCALATED' AND (o.lease_until IS NULL OR o.lease_until<=:now)
             AND (p.resolution_status<>'KNOWN' OR p.payment_status NOT IN ('SUCCEEDED','CANCELED') OR p.financial_status='PENDING_EVIDENCE' OR r.resolution_status<>'KNOWN' OR r.refund_status NOT IN ('SUCCEEDED','FAILED','CANCELED')
               OR (r.refund_status='SUCCEEDED' AND r.balance_transaction_id IS NULL)
               OR (r.balance_transaction_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=r.organization_id AND b.environment_id=r.environment_id AND b.provider_account_id=r.provider_account_id AND b.bt_id=r.balance_transaction_id AND b.state='POSTED'))
               OR (r.failure_balance_transaction_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=r.organization_id AND b.environment_id=r.environment_id AND b.provider_account_id=r.provider_account_id AND b.bt_id=r.failure_balance_transaction_id AND b.state='POSTED')))
             AND (t.status='DONE' OR t.available_at<=:now) ORDER BY t.available_at,o.id LIMIT :limit
            """,
                deployment(provider, stripe)
                        .addValue("now", Timestamp.from(now))
                        .addValue("limit", limit),
                UUID.class);
    }

    public Optional<Target> findTarget(UUID operationId) {
        var rows = jdbc.query(
                "SELECT payment_id,refund_id FROM platform.provider_operation WHERE id=:id",
                Map.of("id", operationId),
                (rs, row) -> new Target(rs.getObject("payment_id", UUID.class), rs.getObject("refund_id", UUID.class)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public UUID refundPayment(UUID refundId) {
        return jdbc.queryForObject(
                "SELECT payment_id FROM platform.refund WHERE id=:id", Map.of("id", refundId), UUID.class);
    }

    public void lockPayment(UUID paymentId) {
        jdbc.queryForList("SELECT id FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", paymentId));
    }

    /** Refund capacity and the refund itself, after the payment lock. */
    public void lockRefund(UUID paymentId, UUID refundId) {
        jdbc.queryForList(
                "SELECT payment_id FROM platform.refund_control WHERE payment_id=:id FOR UPDATE",
                Map.of("id", paymentId));
        jdbc.queryForList("SELECT id FROM platform.refund WHERE id=:id FOR UPDATE", Map.of("id", refundId));
    }

    public Optional<Map<String, Object>> lockOperation(UUID operationId) {
        return first(jdbc.queryForList(
                "SELECT * FROM platform.provider_operation WHERE id=:id FOR UPDATE", Map.of("id", operationId)));
    }

    public Optional<Map<String, Object>> lockTask(UUID operationId) {
        return first(jdbc.queryForList(
                "SELECT * FROM platform.recovery_task WHERE operation_id=:id FOR UPDATE", Map.of("id", operationId)));
    }

    public Map<String, Object> refundRow(UUID refundId) {
        return first(jdbc.queryForList("SELECT * FROM platform.refund WHERE id=:id", Map.of("id", refundId)))
                .orElse(null);
    }

    public Map<String, Object> paymentRow(UUID paymentId) {
        return first(jdbc.queryForList("""
                        SELECT p.*,o.order_key
                        FROM platform.payment p
                        JOIN platform.business_order o ON o.id=p.business_order_id
                        WHERE p.id=:id""", Map.of("id", paymentId))).orElse(null);
    }

    /** A first send that would duplicate a known remote payment waits for a query instead. */
    public void deferToQuery(UUID operationId, Instant now) {
        jdbc.update("""
                UPDATE platform.provider_operation
                SET status='UNKNOWN',resolution='UNKNOWN',version=version+1
                WHERE id=:id""", Map.of("id", operationId));
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='WAITING',reason='REMOTE_FACT_REQUIRES_QUERY',available_at=:now,version=version+1
                WHERE operation_id=:id""", Map.of("now", Timestamp.from(now), "id", operationId));
    }

    public boolean currencyActive(UUID organizationId, UUID environmentId, String currency) {
        return !jdbc.queryForList(
                        """
                        SELECT id
                        FROM platform.org_currency_enabled
                        WHERE organization_id=:organizationId AND environment_id=:environmentId \
                        AND currency=:currency AND status='ACTIVE'""",
                        new MapSqlParameterSource()
                                .addValue("organizationId", organizationId)
                                .addValue("environmentId", environmentId)
                                .addValue("currency", currency))
                .isEmpty();
    }

    /** Creates the account's batch meter if missing, then locks it and returns when the next batch may start. */
    public Instant lockAccountBatch(UUID organizationId, UUID environmentId, UUID providerAccountId, Instant now) {
        var account = account(organizationId, environmentId, providerAccountId);
        jdbc.update("""
                INSERT INTO platform.recovery_account_limit(organization_id,environment_id,provider_account_id,
                    next_batch_at)
                VALUES (:organizationId,:environmentId,:providerAccountId,:now)
                ON CONFLICT
                DO NOTHING""", account.addValue("now", Timestamp.from(now)));
        return jdbc.queryForObject("""
                        SELECT next_batch_at
                        FROM platform.recovery_account_limit
                        WHERE organization_id=:organizationId AND environment_id=:environmentId \
                        AND provider_account_id=:providerAccountId
                        FOR UPDATE""", account, Timestamp.class).toInstant();
    }

    public void deferTaskUntil(UUID operationId, Instant next) {
        jdbc.update("""
                UPDATE platform.recovery_task
                SET available_at=greatest(available_at,:next),version=version+1
                WHERE operation_id=:id""", Map.of("next", Timestamp.from(next), "id", operationId));
    }

    public void scheduleNextBatch(UUID organizationId, UUID environmentId, UUID providerAccountId, Instant next) {
        jdbc.update(
                """
                UPDATE platform.recovery_account_limit
                SET next_batch_at=:next
                WHERE organization_id=:organizationId AND environment_id=:environmentId \
                AND provider_account_id=:providerAccountId""",
                new MapSqlParameterSource()
                        .addValue("next", Timestamp.from(next))
                        .addValue("organizationId", organizationId)
                        .addValue("environmentId", environmentId)
                        .addValue("providerAccountId", providerAccountId));
    }

    /** Writes the lease on the operation and its task with the same token and expiry. */
    public void takeLease(UUID operationId, UUID token, long version, LeaseGrant lease, Instant now) {
        jdbc.update(
                """
                UPDATE platform.provider_operation
                SET
                    status='SENDING',
                    lease_token=:token,
                    lease_owner='pr07',
                    lease_until=:until,
                    attempt_count=attempt_count+1,
                    first_send_at=:first,
                    retry_until=:horizon,
                    version=:version,
                    updated_at=:now
                WHERE id=:id""",
                new MapSqlParameterSource()
                        .addValue("token", token)
                        .addValue("until", Timestamp.from(lease.leaseUntil()))
                        .addValue("first", Timestamp.from(lease.firstSendAt()))
                        .addValue("horizon", lease.retryUntil() == null ? null : Timestamp.from(lease.retryUntil()))
                        .addValue("version", version)
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", operationId));
        jdbc.update(
                """
                UPDATE platform.recovery_task
                SET
                    status='RUNNING',
                    lease_token=:token,
                    lease_until=:until,
                    attempt_count=attempt_count+1,
                    version=version+1,
                    updated_at=:now
                WHERE operation_id=:id""",
                new MapSqlParameterSource()
                        .addValue("token", token)
                        .addValue("until", Timestamp.from(lease.leaseUntil()))
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", operationId));
    }

    /** True if the lease is still held: same token and version, unexpired, and the task carries the same lease. */
    public boolean holdsLease(UUID operationId, UUID token, long version, Instant now) {
        return !jdbc.queryForList(
                        """
                        SELECT o.id
                        FROM platform.provider_operation o
                        JOIN platform.recovery_task t ON t.operation_id=o.id
                        WHERE o.id=:id
                            AND o.lease_token=:token
                            AND o.version=:version
                            AND o.status='SENDING'
                            AND o.lease_until>:now
                            AND t.status='RUNNING'
                            AND t.lease_token=o.lease_token
                            AND t.lease_until=o.lease_until
                        FOR UPDATE OF o""",
                        new MapSqlParameterSource()
                                .addValue("id", operationId)
                                .addValue("token", token)
                                .addValue("version", version)
                                .addValue("now", Timestamp.from(now)))
                .isEmpty();
    }

    public void markRefundUnknown(UUID refundId, Instant now) {
        jdbc.update("""
                UPDATE platform.refund
                SET
                    resolution_status=CASE WHEN resolution_status='CONFLICT' THEN 'CONFLICT' ELSE 'UNKNOWN' END,
                    unresolved_since=coalesce(unresolved_since,:now),
                    version=version+1
                WHERE id=:id""", Map.of("now", Timestamp.from(now), "id", refundId));
    }

    /** Stops automatic recovery: the operation and its task are escalated and their lease released. */
    public void escalate(UUID operationId, String reason, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("reason", reason)
                .addValue("now", Timestamp.from(now))
                .addValue("id", operationId);
        jdbc.update("""
                UPDATE platform.provider_operation
                SET
                    status='ESCALATED',
                    resolution=coalesce((SELECT resolution_status FROM platform.payment WHERE
                        id=provider_operation.payment_id),(SELECT resolution_status FROM platform.refund WHERE
                        id=provider_operation.refund_id)),
                    escalation_reason=:reason,
                    escalated_at=coalesce(escalated_at,:now),
                    lease_until=null,
                    version=version+1,
                    updated_at=:now
                WHERE id=:id""", params);
        jdbc.update("""
                UPDATE platform.recovery_task
                SET
                    status='ESCALATED',
                    reason=:reason,
                    escalated_at=coalesce(escalated_at,:now),
                    lease_until=null,
                    lease_token=null,
                    version=version+1,
                    updated_at=:now
                WHERE operation_id=:id""", params);
    }

    public void alertAge(UUID operationId, Instant now) {
        var params = Map.of("now", Timestamp.from(now), "id", operationId);
        jdbc.update("""
                UPDATE platform.provider_operation
                SET age_alert_at=coalesce(age_alert_at,:now)
                WHERE id=:id""", params);
        jdbc.update("""
                UPDATE platform.recovery_task
                SET age_alert_at=coalesce(age_alert_at,:now)
                WHERE operation_id=:id""", params);
    }

    public OperationRow operationRow(UUID operationId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.provider_operation WHERE id=:id",
                Map.of("id", operationId),
                (rs, row) -> new OperationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("refund_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("operation_type"),
                        rs.getInt("attempt_count")));
    }

    public Outcome refundOutcome(UUID refundId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.refund WHERE id=:id",
                Map.of("id", refundId),
                (rs, row) -> new Outcome(rs.getString("refund_status"), rs.getString("resolution_status")));
    }

    public Outcome paymentOutcome(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.payment WHERE id=:id",
                Map.of("id", paymentId),
                (rs, row) -> new Outcome(rs.getString("payment_status"), rs.getString("resolution_status")));
    }

    /** Releases the task's lease and schedules its next attempt, or finishes it. */
    public void reschedule(UUID operationId, boolean done, Instant next, Instant now) {
        jdbc.update(
                """
                UPDATE platform.recovery_task
                SET status=:status,available_at=:next,lease_until=null,lease_token=null,version=version+1,updated_at=:now
                WHERE operation_id=:id""",
                new MapSqlParameterSource()
                        .addValue("status", done ? "DONE" : "WAITING")
                        .addValue("next", Timestamp.from(next))
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", operationId));
    }

    /** Makes the task due now unless it is escalated; an earlier due time is kept. */
    public void wake(UUID operationId, Instant now) {
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='WAITING',available_at=least(available_at,:now),version=version+1
                WHERE operation_id=:id AND status<>'ESCALATED'""", Map.of("now", Timestamp.from(now), "id", operationId));
    }

    private static MapSqlParameterSource deployment(String provider, StripeDeployment stripe) {
        return new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("stripeAccount", stripe.accountId())
                .addValue("stripeOrganization", stripe.organizationId())
                .addValue("stripeEnvironment", stripe.environmentId())
                .addValue("stripeProviderAccount", stripe.providerAccountId());
    }

    private static MapSqlParameterSource account(UUID organizationId, UUID environmentId, UUID providerAccountId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("environmentId", environmentId)
                .addValue("providerAccountId", providerAccountId);
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
