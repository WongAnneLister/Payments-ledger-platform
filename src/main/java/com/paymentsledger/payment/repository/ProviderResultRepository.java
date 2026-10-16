package com.paymentsledger.payment.repository;

import com.paymentsledger.payment.domain.PaymentProgress;
import com.paymentsledger.payment.domain.PaymentStatus;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
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
 * Data access for writing provider results back: observations, payment and operation outcomes, query stamps, the
 * anchors recovery queries, and the reads the executor needs.
 */
public class ProviderResultRepository {
    /** Tenant scope an observation is filed under, taken from its operation. */
    public record ObservationScope(
            UUID organizationId,
            UUID environmentId,
            UUID applicationId,
            UUID providerAccountId,
            String currency,
            UUID refundId) {}

    /** One provider call as persisted; evidence and fact are already-encoded JSON. */
    public record ObservationRow(
            UUID operationId,
            UUID leaseToken,
            String callType,
            String classification,
            String externalId,
            String evidence,
            String fact) {}

    /** The session details a successful create records on the payment. */
    public record SessionUpdate(
            CheckoutSessionFact session, PaymentStatus status, String providerStatus, Instant confirmedAt) {}

    public record CreateOperation(UUID id, String status) {}

    private static final RowMapper<PaymentProgress> PROGRESS = (rs, row) -> new PaymentProgress(
            rs.getLong("version"),
            PaymentStatus.valueOf(rs.getString("payment_status")),
            rs.getString("provider_status"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            rs.getString("checkout_session_id"),
            rs.getString("payment_intent_id"),
            rs.getLong("captured_amount_minor"),
            instant(rs.getTimestamp("unresolved_since")));

    private final NamedParameterJdbcTemplate jdbc;

    public ProviderResultRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public ObservationScope observationScope(UUID operationId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.provider_operation WHERE id=:id",
                Map.of("id", operationId),
                (rs, row) -> new ObservationScope(
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("environment_id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getObject("provider_account_id", UUID.class),
                        rs.getString("currency"),
                        rs.getObject("refund_id", UUID.class)));
    }

    public void insertRefundObservation(ObservationScope scope, ObservationRow observation) {
        jdbc.update("""
                INSERT INTO platform.provider_observation(organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_id,refund_id,observation_domain,lease_token,call_type,
                    classification,external_object_id,evidence,fact)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:operationId,
                    :refundId,'REFUND',:leaseToken,:callType,:classification,:externalId,:evidence::jsonb,:fact::jsonb)""", observation(scope, observation).addValue("refundId", scope.refundId()));
    }

    public void insertPaymentObservation(ObservationScope scope, UUID paymentId, ObservationRow observation) {
        jdbc.update("""
                INSERT INTO platform.provider_observation(organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_id,payment_id,lease_token,call_type,classification,
                    external_object_id,evidence,fact)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:operationId,
                    :paymentId,:leaseToken,:callType,:classification,:externalId,:evidence::jsonb,:fact::jsonb)""", observation(scope, observation).addValue("paymentId", paymentId));
    }

    public PaymentProgress progress(UUID paymentId) {
        return jdbc.queryForObject("SELECT * FROM platform.payment WHERE id=:id", Map.of("id", paymentId), PROGRESS);
    }

    public PaymentProgress lockProgress(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", paymentId), PROGRESS);
    }

    /** The whole payment row, locked; webhook handlers read columns by name. */
    public Map<String, Object> lockPaymentRow(UUID paymentId) {
        return first(jdbc.queryForList(
                        "SELECT * FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", paymentId)))
                .orElse(null);
    }

    public Map<String, Object> paymentWithOrder(UUID paymentId) {
        return first(jdbc.queryForList("""
                        SELECT p.*,o.order_key
                        FROM platform.payment p
                        JOIN platform.business_order o ON o.id=p.business_order_id
                        WHERE p.id=:id""", Map.of("id", paymentId))).orElse(null);
    }

    public Map<String, Object> operation(UUID operationId) {
        return first(jdbc.queryForList(
                        "SELECT * FROM platform.provider_operation WHERE id=:id", Map.of("id", operationId)))
                .orElse(null);
    }

    public UUID createOperationId(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT id
                FROM platform.provider_operation
                WHERE payment_id=:paymentId AND operation_type='CREATE_CHECKOUT'""", Map.of("paymentId", paymentId), UUID.class);
    }

    /** Cancels the payment if it is still at the version the decision was made on. */
    public void cancelPayment(UUID paymentId, long version) {
        jdbc.update("""
                UPDATE platform.payment
                SET
                    payment_status='CANCELED',
                    resolution_status='KNOWN',
                    unresolved_since=null,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id AND version=:version""", Map.of("id", paymentId, "version", version));
    }

    /** Cancels a payment the provider never heard of; the caller holds the payment lock. */
    public void cancelUnsentPayment(UUID paymentId) {
        jdbc.update("""
                UPDATE platform.payment
                SET
                    payment_status='CANCELED',
                    resolution_status='KNOWN',
                    unresolved_since=null,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id""", Map.of("id", paymentId));
    }

    public void recordSession(UUID paymentId, long version, SessionUpdate update) {
        var session = update.session();
        jdbc.update(
                """
                UPDATE platform.payment
                SET
                    checkout_session_id=:sessionId,
                    payment_intent_id=coalesce(payment_intent_id,:paymentIntentId),
                    hosted_url=:hostedUrl,
                    payment_status=:status,
                    provider_status=:providerStatus,
                    resolution_status='KNOWN',
                    unresolved_since=null,
                    last_provider_confirmed_at=:confirmedAt,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id AND version=:version""",
                new MapSqlParameterSource()
                        .addValue("sessionId", session.sessionId())
                        .addValue("paymentIntentId", session.paymentIntentId())
                        .addValue("hostedUrl", session.hostedUrl())
                        .addValue("status", update.status().name())
                        .addValue("providerStatus", update.providerStatus())
                        .addValue("confirmedAt", Timestamp.from(update.confirmedAt()))
                        .addValue("id", paymentId)
                        .addValue("version", version));
    }

    /** Marks the payment uncertain; CONFLICT is sticky and a CREATED payment moves to PROCESSING. */
    public void markUnknown(UUID paymentId, boolean conflict, Instant now) {
        jdbc.update(
                """
                UPDATE platform.payment
                SET
                    resolution_status=CASE WHEN resolution_status='CONFLICT' OR :conflict THEN 'CONFLICT' ELSE 'UNKNOWN'
                        END,
                    unresolved_since=coalesce(unresolved_since,:now),
                    payment_status=CASE WHEN payment_status='CREATED' THEN 'PROCESSING' ELSE payment_status END,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id""",
                new MapSqlParameterSource()
                        .addValue("conflict", conflict)
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", paymentId));
    }

    /**
     * Records the outcome and releases the lease, fenced by token and version; returns the rows updated (0 when the
     * lease was lost).
     */
    public int completeOperation(
            UUID operationId,
            UUID token,
            long version,
            String status,
            String resolution,
            String external,
            String providerStatus,
            Instant now) {
        return jdbc.update(
                """
                UPDATE platform.provider_operation
                SET
                    status=:status,
                    resolution=:resolution,
                    external_object_id=coalesce(:external,external_object_id),
                    last_provider_status=coalesce(:providerStatus,last_provider_status),
                    lease_until=null,
                    version=version+1,
                    updated_at=:now
                WHERE id=:id AND lease_token=:token AND version=:version AND status='SENDING'""",
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("resolution", resolution)
                        .addValue("external", external)
                        .addValue("providerStatus", providerStatus)
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", operationId)
                        .addValue("token", token)
                        .addValue("version", version));
    }

    public void stampQuery(UUID operationId, Instant now) {
        jdbc.update(
                "UPDATE platform.provider_operation SET last_query_at=:now WHERE id=:id",
                Map.of("now", Timestamp.from(now), "id", operationId));
    }

    /** Resolves an operation no sender owns; query evidence never touches SENDING operations. */
    public void resolveQueried(UUID operationId, Instant now) {
        jdbc.update("""
                UPDATE platform.provider_operation
                SET status='RESOLVED',resolution='KNOWN',last_query_at=:now,version=version+1,updated_at=now()
                WHERE id=:id AND status IN ('UNKNOWN','ACKNOWLEDGED')""", Map.of("now", Timestamp.from(now), "id", operationId));
    }

    public CreateOperation lockCreateOperation(UUID paymentId) {
        return jdbc.queryForObject(
                """
                SELECT *
                FROM platform.provider_operation
                WHERE payment_id=:paymentId AND operation_type='CREATE_CHECKOUT'
                FOR UPDATE""",
                Map.of("paymentId", paymentId),
                (rs, row) -> new CreateOperation(rs.getObject("id", UUID.class), rs.getString("status")));
    }

    /** Resolves a create that was never sent, and finishes its task. */
    public void resolveUnsentCreate(UUID operationId) {
        jdbc.update("""
                UPDATE platform.provider_operation
                SET status='RESOLVED',version=version+1,updated_at=now()
                WHERE id=:id AND status='PREPARED'""", Map.of("id", operationId));
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='DONE',version=version+1
                WHERE operation_id=:id""", Map.of("id", operationId));
    }

    /** Sessions successfully created, queried or expired for this operation, within its tenant scope. */
    public List<String> observedSessions(
            UUID operationId, UUID paymentId, UUID organizationId, UUID environmentId, UUID providerAccountId) {
        return jdbc.queryForList(
                """
            SELECT DISTINCT external_object_id FROM platform.provider_observation WHERE operation_id=:operationId AND payment_id=:paymentId
             AND organization_id=:organizationId AND environment_id=:environmentId AND provider_account_id=:providerAccountId
             AND call_type IN ('CREATE_SESSION','QUERY_SESSION','EXPIRE_SESSION') AND left(external_object_id,3)='cs_'
             AND classification IN ('SUCCESS','AUTH_REQUIRED')
            """,
                new MapSqlParameterSource()
                        .addValue("operationId", operationId)
                        .addValue("paymentId", paymentId)
                        .addValue("organizationId", organizationId)
                        .addValue("environmentId", environmentId)
                        .addValue("providerAccountId", providerAccountId),
                String.class);
    }

    /** Provider refunds successfully created or queried for this operation. */
    public List<String> observedRefunds(UUID operationId, UUID refundId) {
        return jdbc.queryForList("""
                SELECT DISTINCT external_object_id
                FROM platform.provider_observation
                WHERE operation_id=:operationId
                    AND refund_id=:refundId
                    AND call_type IN ('CREATE_REFUND','QUERY_REFUND')
                    AND classification IN ('SUCCESS','AUTH_REQUIRED')
                    AND external_object_id IS NOT NULL""", Map.of("operationId", operationId, "refundId", refundId), String.class);
    }

    /** The currency of any payment on the provider account, if there is one. */
    public Optional<String> anyPaymentCurrency(UUID organizationId, UUID environmentId, UUID providerAccountId) {
        return first(jdbc.queryForList(
                        """
                        SELECT id,currency
                        FROM platform.payment
                        WHERE organization_id=:organizationId AND environment_id=:environmentId \
                        AND provider_account_id=:providerAccountId
                        LIMIT 1""",
                        new MapSqlParameterSource()
                                .addValue("organizationId", organizationId)
                                .addValue("environmentId", environmentId)
                                .addValue("providerAccountId", providerAccountId)))
                .map(row -> (String) row.get("currency"));
    }

    public Optional<UUID> firstActiveApplication(UUID organizationId, UUID environmentId) {
        return first(jdbc.queryForList("""
                        SELECT id
                        FROM platform.application
                        WHERE organization_id=:organizationId AND environment_id=:environmentId AND status='ACTIVE'
                        ORDER BY id
                        LIMIT 1""", Map.of("organizationId", organizationId, "environmentId", environmentId)))
                .map(row -> (UUID) row.get("id"));
    }

    private static MapSqlParameterSource observation(ObservationScope scope, ObservationRow observation) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("environmentId", scope.environmentId())
                .addValue("applicationId", scope.applicationId())
                .addValue("providerAccountId", scope.providerAccountId())
                .addValue("currency", scope.currency())
                .addValue("operationId", observation.operationId())
                .addValue("leaseToken", observation.leaseToken())
                .addValue("callType", observation.callType())
                .addValue("classification", observation.classification())
                .addValue("externalId", observation.externalId())
                .addValue("evidence", observation.evidence())
                .addValue("fact", observation.fact());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
