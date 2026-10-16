package com.paymentsledger.payment.repository;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.RefundControl;
import com.paymentsledger.payment.domain.RefundControlState;
import com.paymentsledger.payment.domain.RefundablePayment;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.provider.ProviderScope;
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

/**
 * Data access for refund reservations: request idempotency, the locked payment and its refund control, the reserved
 * refund with its provider operation and recovery task, and the audited unfreeze of a frozen control.
 */
public class RefundIntentRepository {
    /** A request already recorded under an idempotency or client refund key. */
    public record StoredIntent(String fingerprint, UUID refundId, UUID operationId) {}

    /** A reserved refund and the server-authored command that will create it at the provider. */
    public record NewRefund(
            UUID refundId,
            UUID operationId,
            UUID paymentId,
            long amountMinor,
            String currency,
            String clientRefundKey,
            String fingerprint,
            String reason,
            String note,
            String chargeId,
            String operationKey,
            String command,
            Instant expiresAt,
            Instant availableAt) {}

    private static final RowMapper<RefundablePayment> PAYMENT = (rs, row) -> new RefundablePayment(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("application_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getString("currency"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            rs.getLong("captured_amount_minor"),
            rs.getString("latest_charge_id"));

    private static final RowMapper<RefundControl> CONTROL = (rs, row) -> new RefundControl(
            rs.getObject("payment_id", UUID.class),
            rs.getLong("captured_minor"),
            rs.getLong("refunded_minor"),
            rs.getLong("reserved_minor"),
            RefundControlState.valueOf(rs.getString("control_state")),
            rs.getLong("version"));

    private static final RowMapper<StoredIntent> STORED = (rs, row) -> new StoredIntent(
            rs.getString("request_fingerprint"),
            rs.getObject("refund_id", UUID.class),
            rs.getObject("operation_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    public RefundIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Transaction-scoped advisory lock on a request key. */
    public void lock(String key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", key));
    }

    public Optional<StoredIntent> findByIdempotencyKey(TenantContext tenant, String key) {
        return first(jdbc.query("""
                SELECT *
                FROM platform.api_idempotency_record
                WHERE organization_id=:organizationId
                    AND environment_id=:environmentId
                    AND application_id=:applicationId
                    AND request_type='CREATE_REFUND'
                    AND idempotency_key=:key""", application(tenant).addValue("key", key), STORED));
    }

    public Optional<StoredIntent> findByClientRefundKey(TenantContext tenant, String clientRefundKey) {
        return first(jdbc.query("""
                SELECT r.id refund_id,r.request_fingerprint,o.id operation_id
                FROM platform.refund r
                JOIN platform.provider_operation o ON o.refund_id=r.id
                WHERE r.organization_id=:organizationId AND r.environment_id=:environmentId \
                AND r.application_id=:applicationId AND r.client_refund_key=:clientRefundKey""", application(tenant).addValue("clientRefundKey", clientRefundKey), STORED));
    }

    public void insertIdempotency(TenantContext tenant, String currency, String key, StoredIntent intent) {
        jdbc.update(
                """
                INSERT INTO platform.api_idempotency_record(organization_id,environment_id,application_id,
                    provider_account_id,currency,request_type,idempotency_key,request_fingerprint,refund_id,operation_id)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,'CREATE_REFUND',
                    :key,:fingerprint,:refundId,:operationId)""",
                tenantScope(tenant, currency)
                        .addValue("key", key)
                        .addValue("fingerprint", intent.fingerprint())
                        .addValue("refundId", intent.refundId())
                        .addValue("operationId", intent.operationId()));
    }

    /** The payment in the caller's application and provider account, locked; empty if there is none. */
    public Optional<RefundablePayment> lockPayment(UUID paymentId, TenantContext tenant) {
        return first(jdbc.query(
                """
                SELECT *
                FROM platform.payment
                WHERE id=:id AND organization_id=:organizationId AND environment_id=:environmentId \
                AND application_id=:applicationId AND provider_account_id=:providerAccountId
                FOR UPDATE""",
                application(tenant)
                        .addValue("id", paymentId)
                        .addValue("providerAccountId", tenant.allowedProviderAccountId()),
                PAYMENT));
    }

    /** The payment on the provider account, locked; empty if there is none. */
    public Optional<RefundablePayment> lockPayment(UUID paymentId, ProviderScope scope) {
        return first(jdbc.query("""
                SELECT *
                FROM platform.payment
                WHERE id=:id AND organization_id=:organizationId AND environment_id=:environmentId \
                AND provider_account_id=:providerAccountId
                FOR UPDATE""", providerScope(scope).addValue("id", paymentId), PAYMENT));
    }

    /**
     * Creates the payment's refund control on first use, raises its captured amount to the payment's, and locks it.
     * The caller holds the payment lock.
     */
    public RefundControl lockControl(RefundablePayment payment) {
        jdbc.update(
                """
                INSERT INTO platform.refund_control(organization_id,environment_id,application_id,provider_account_id,
                    currency,payment_id,captured_minor)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:paymentId,:captured)
                ON CONFLICT DO NOTHING""",
                new MapSqlParameterSource()
                        .addValue("organizationId", payment.organizationId())
                        .addValue("environmentId", payment.environmentId())
                        .addValue("applicationId", payment.applicationId())
                        .addValue("providerAccountId", payment.providerAccountId())
                        .addValue("currency", payment.currency())
                        .addValue("paymentId", payment.id())
                        .addValue("captured", payment.capturedMinor()));
        jdbc.update("""
                UPDATE platform.refund_control
                SET captured_minor=greatest(captured_minor,:captured),updated_at=now()
                WHERE payment_id=:paymentId""", Map.of("captured", payment.capturedMinor(), "paymentId", payment.id()));
        return jdbc.queryForObject(
                "SELECT * FROM platform.refund_control WHERE payment_id=:paymentId FOR UPDATE",
                Map.of("paymentId", payment.id()),
                CONTROL);
    }

    /** Reserves the refund's amount, then records the refund, its provider operation and its recovery task. */
    public void reserve(TenantContext tenant, NewRefund refund) {
        jdbc.update("""
                UPDATE platform.refund_control
                SET reserved_minor=reserved_minor+:amount,version=version+1,updated_at=now()
                WHERE payment_id=:paymentId""", Map.of("amount", refund.amountMinor(), "paymentId", refund.paymentId()));
        var scope = tenantScope(tenant, refund.currency()).addValue("refundId", refund.refundId());
        jdbc.update(
                """
                INSERT INTO platform.refund(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,payment_id,amount_minor,client_refund_key,request_fingerprint,source,reason,note,
                    provider_charge_id)
                VALUES (:refundId,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,
                    :paymentId,:amount,:clientRefundKey,:fingerprint,'LOCAL',:reason,:note,:chargeId)""",
                new MapSqlParameterSource(scope.getValues())
                        .addValue("paymentId", refund.paymentId())
                        .addValue("amount", refund.amountMinor())
                        .addValue("clientRefundKey", refund.clientRefundKey())
                        .addValue("fingerprint", refund.fingerprint())
                        .addValue("reason", refund.reason())
                        .addValue("note", refund.note())
                        .addValue("chargeId", refund.chargeId()));
        jdbc.update(
                """
                INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_target,refund_id,operation_type,operation_key,
                    request_fingerprint,command,expires_at)
                VALUES (:operationId,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,
                    'REFUND',:refundId,'CREATE_REFUND',:operationKey,:fingerprint,:command::jsonb,:expiresAt)""",
                new MapSqlParameterSource(scope.getValues())
                        .addValue("operationId", refund.operationId())
                        .addValue("operationKey", refund.operationKey())
                        .addValue("fingerprint", refund.fingerprint())
                        .addValue("command", refund.command())
                        .addValue("expiresAt", Timestamp.from(refund.expiresAt())));
        jdbc.update(
                """
                INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,
                    currency,operation_id,refund_id,available_at)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:operationId,
                    :refundId,:availableAt)""",
                new MapSqlParameterSource(scope.getValues())
                        .addValue("operationId", refund.operationId())
                        .addValue("availableAt", Timestamp.from(refund.availableAt())));
    }

    /** The observation an earlier unfreeze with this audit key was based on. */
    public Optional<UUID> auditedObservation(UUID paymentId, String auditKey) {
        return first(jdbc.queryForList("""
                SELECT observation_id
                FROM platform.refund_control_audit
                WHERE payment_id=:paymentId AND audit_key=:auditKey""", Map.of("paymentId", paymentId, "auditKey", auditKey), UUID.class));
    }

    public boolean observationExists(UUID observationId, ProviderScope scope, UUID paymentId) {
        return !jdbc.queryForList(
                        """
                        SELECT id
                        FROM platform.refund_source_observation
                        WHERE id=:observationId AND organization_id=:organizationId AND environment_id=:environmentId \
                        AND provider_account_id=:providerAccountId AND payment_id=:paymentId""",
                        providerScope(scope)
                                .addValue("observationId", observationId)
                                .addValue("paymentId", paymentId))
                .isEmpty();
    }

    /** Local refunds of the payment that are not yet terminal and certain. */
    public long countUnsettledRefunds(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.refund
                WHERE payment_id=:paymentId
                    AND (resolution_status<>'KNOWN' OR refund_status NOT IN ('SUCCEEDED','FAILED','CANCELED'))""", Map.of("paymentId", paymentId), Long.class);
    }

    public long sumSucceededRefunds(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount_minor),0)
                FROM platform.refund
                WHERE payment_id=:paymentId AND refund_status='SUCCEEDED'""", Map.of("paymentId", paymentId), Long.class);
    }

    /**
     * Provider refunds observed for the payment's charges that no settled local refund matches. Historical facts may
     * be associated after their charge arrives; only compatible evidence counts as matched: nonterminal observations
     * may advance, but terminal facts and supplied balance-transaction identities cannot change.
     */
    public long countUnmatchedObservations(ProviderScope scope, UUID paymentId) {
        return jdbc.queryForObject("""
       SELECT count(*) FROM platform.refund_source_observation o
       WHERE o.organization_id=:organizationId AND o.environment_id=:environmentId AND o.provider_account_id=:providerAccountId AND o.refund_id IS NULL
         AND o.fact->>'chargeId' IN (SELECT provider_object_id FROM platform.payment_attempt WHERE payment_id=:paymentId)
         AND NOT EXISTS (
           SELECT 1 FROM platform.refund r
           WHERE r.organization_id=o.organization_id AND r.environment_id=o.environment_id
             AND r.provider_account_id=o.provider_account_id AND r.provider_refund_id=o.provider_refund_id
             AND r.provider_charge_id=o.fact->>'chargeId' AND r.amount_minor::text=o.fact->>'amountMinor'
             AND r.currency=upper(o.fact->>'currency') AND r.resolution_status='KNOWN'
             AND (upper(o.fact->>'status') IN ('PENDING','REQUIRES_ACTION') OR upper(o.fact->>'status')=r.refund_status)
             AND (o.fact->>'balanceTransactionId' IS NULL OR o.fact->>'balanceTransactionId'=r.balance_transaction_id)
             AND (o.fact->>'failureBalanceTransactionId' IS NULL OR o.fact->>'failureBalanceTransactionId'=r.failure_balance_transaction_id)
         )
       """, providerScope(scope).addValue("paymentId", paymentId), Long.class);
    }

    /** Records the audited before/after control state, then returns the control to NORMAL. */
    public void unfreeze(
            ProviderScope scope, UUID paymentId, UUID observationId, String auditKey, String before, String after) {
        jdbc.update(
                """
                INSERT INTO platform.refund_control_audit(organization_id,environment_id,provider_account_id,
                    payment_id,observation_id,audit_key,before_state,after_state)
                VALUES (:organizationId,:environmentId,:providerAccountId,:paymentId,:observationId,:auditKey,
                    :before::jsonb,:after::jsonb)""",
                providerScope(scope)
                        .addValue("paymentId", paymentId)
                        .addValue("observationId", observationId)
                        .addValue("auditKey", auditKey)
                        .addValue("before", before)
                        .addValue("after", after));
        jdbc.update("""
                UPDATE platform.refund_control
                SET control_state='NORMAL',version=version+1,updated_at=now()
                WHERE payment_id=:paymentId""", Map.of("paymentId", paymentId));
    }

    private static MapSqlParameterSource application(TenantContext tenant) {
        return new MapSqlParameterSource()
                .addValue("organizationId", tenant.organizationId())
                .addValue("environmentId", tenant.environmentId())
                .addValue("applicationId", tenant.applicationId());
    }

    private static MapSqlParameterSource tenantScope(TenantContext tenant, String currency) {
        return application(tenant)
                .addValue("providerAccountId", tenant.allowedProviderAccountId())
                .addValue("currency", currency);
    }

    private static MapSqlParameterSource providerScope(ProviderScope scope) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("environmentId", scope.environmentId())
                .addValue("providerAccountId", scope.providerAccountId());
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
