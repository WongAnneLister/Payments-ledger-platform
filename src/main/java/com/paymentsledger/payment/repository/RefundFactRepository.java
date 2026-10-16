package com.paymentsledger.payment.repository;

import com.paymentsledger.financialingestion.FinancialSourceLocks;
import com.paymentsledger.payment.domain.LocalRefund;
import com.paymentsledger.payment.domain.RefundControlState;
import com.paymentsledger.payment.domain.RefundablePayment;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.provider.ProviderModels.RefundFact;
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
 * Data access for merging provider refund facts: source observations, the refund and payment rows a fact applies to,
 * external refunds with their query operation, control freezes and settlements, and the leased writeback of refund
 * operations.
 */
public class RefundFactRepository {
    /** How a merged fact is written onto its refund. */
    public record FactUpdate(
            String status, boolean recognized, String providerStatus, RefundFact fact, Instant confirmedAt) {}

    private static final RowMapper<LocalRefund> REFUND = (rs, row) -> new LocalRefund(
            rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("application_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getString("currency"),
            rs.getLong("amount_minor"),
            rs.getString("source"),
            rs.getString("refund_status"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            rs.getString("provider_status"),
            rs.getString("provider_refund_id"),
            rs.getString("provider_charge_id"),
            rs.getString("balance_transaction_id"),
            rs.getString("failure_balance_transaction_id"),
            instant(rs.getTimestamp("unresolved_since")));

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

    private final NamedParameterJdbcTemplate jdbc;

    public RefundFactRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String BY_PROVIDER_ID = """
            SELECT *
            FROM platform.refund
            WHERE organization_id=:organizationId AND environment_id=:environmentId \
            AND provider_account_id=:providerAccountId AND provider_refund_id=:providerRefundId""";

    /** The whole refund row recorded for a provider refund id; webhook handlers read columns by name. */
    public Map<String, Object> lookupRow(ProviderScope scope, String providerRefundId) {
        return first(jdbc.queryForList(BY_PROVIDER_ID, scope(scope).addValue("providerRefundId", providerRefundId)))
                .orElse(null);
    }

    public Optional<LocalRefund> findByProviderId(ProviderScope scope, String providerRefundId) {
        return first(jdbc.query(BY_PROVIDER_ID, scope(scope).addValue("providerRefundId", providerRefundId), REFUND));
    }

    /** The payment whose captured charge this is; null if the charge is not known as captured. */
    public UUID paymentForCharge(ProviderScope scope, String chargeId) {
        return first(jdbc.queryForList("""
                        SELECT payment_id
                        FROM platform.payment_attempt
                        WHERE organization_id=:organizationId
                            AND environment_id=:environmentId
                            AND provider_account_id=:providerAccountId
                            AND provider_object_id=:chargeId
                            AND captured""", scope(scope).addValue("chargeId", chargeId), UUID.class))
                .orElse(null);
    }

    public Optional<LocalRefund> findInScope(UUID refundId, ProviderScope scope) {
        return first(jdbc.query("""
                SELECT *
                FROM platform.refund
                WHERE id=:id AND organization_id=:organizationId AND environment_id=:environmentId \
                AND provider_account_id=:providerAccountId""", scope(scope).addValue("id", refundId), REFUND));
    }

    /** Locks the financial sources a refund fact names, in the order financial ingestion uses. */
    public void lockFinancialSources(UUID paymentId, List<String> sources) {
        FinancialSourceLocks.lock(jdbc.getJdbcTemplate(), paymentId, sources);
    }

    public RefundablePayment lockPayment(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", paymentId), PAYMENT);
    }

    public LocalRefund lockRefund(UUID refundId) {
        return jdbc.queryForObject(
                "SELECT * FROM platform.refund WHERE id=:id FOR UPDATE", Map.of("id", refundId), REFUND);
    }

    public LocalRefund refund(UUID refundId) {
        return jdbc.queryForObject("SELECT * FROM platform.refund WHERE id=:id", Map.of("id", refundId), REFUND);
    }

    /** The whole refund row; callers outside this package read columns by name. */
    public Map<String, Object> refundRow(UUID refundId) {
        return first(jdbc.queryForList("SELECT * FROM platform.refund WHERE id=:id", Map.of("id", refundId)))
                .orElse(null);
    }

    /** Records what the provider reported; refund and payment are null when the fact could not be attributed. */
    public void observe(
            ProviderScope scope, String providerRefundId, UUID refundId, UUID paymentId, String fact, String evidence) {
        jdbc.update(
                """
                INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,
                    provider_refund_id,refund_id,payment_id,fact,evidence)
                VALUES (:organizationId,:environmentId,:providerAccountId,:providerRefundId,:refundId,:paymentId,
                    :fact::jsonb,:evidence::jsonb)""",
                scope(scope)
                        .addValue("providerRefundId", providerRefundId)
                        .addValue("refundId", refundId)
                        .addValue("paymentId", paymentId)
                        .addValue("fact", fact)
                        .addValue("evidence", evidence));
    }

    /** Records a source query that returned no fact. */
    public void observeQuery(ProviderScope scope, String providerRefundId, String fact, String evidence) {
        jdbc.update(
                """
                INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,
                    provider_refund_id,fact,evidence)
                VALUES (:organizationId,:environmentId,:providerAccountId,:providerRefundId,:fact::jsonb,
                    :evidence::jsonb)""",
                scope(scope)
                        .addValue("providerRefundId", providerRefundId)
                        .addValue("fact", fact)
                        .addValue("evidence", evidence));
    }

    /** Freezes the payment's refund control after a contradiction. */
    public void freezeControl(UUID paymentId) {
        jdbc.update("""
                UPDATE platform.refund_control
                SET control_state='EXTERNAL_CONFLICT',version=version+1
                WHERE payment_id=:paymentId""", Map.of("paymentId", paymentId));
    }

    /** Freezes the payment's refund control and stamps it, before an external refund changes its amounts. */
    public void freezeControlStamped(UUID paymentId) {
        jdbc.update("""
                UPDATE platform.refund_control
                SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now()
                WHERE payment_id=:paymentId""", Map.of("paymentId", paymentId));
    }

    /** Marks a refund whose provider id is bound to another refund. */
    public void markBindingConflict(UUID refundId, Instant now) {
        jdbc.update("""
                UPDATE platform.refund
                SET resolution_status='CONFLICT',unresolved_since=coalesce(unresolved_since,:now),version=version+1
                WHERE id=:id""", Map.of("now", Timestamp.from(now), "id", refundId));
    }

    public void markConflict(UUID refundId, Instant now) {
        jdbc.update("""
                UPDATE platform.refund
                SET
                    resolution_status='CONFLICT',
                    unresolved_since=coalesce(unresolved_since,:now),
                    version=version+1,
                    updated_at=now()
                WHERE id=:id""", Map.of("now", Timestamp.from(now), "id", refundId));
    }

    /** Records a refund the provider reports but this platform never reserved. */
    public void insertExternal(UUID refundId, RefundablePayment payment, RefundFact fact) {
        jdbc.update(
                """
                INSERT INTO platform.refund(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,payment_id,amount_minor,request_fingerprint,source,provider_charge_id,provider_refund_id,
                    refund_status)
                VALUES (:id,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:paymentId,
                    :amount,:fingerprint,'EXTERNAL',:chargeId,:providerRefundId,'PENDING')""",
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("organizationId", payment.organizationId())
                        .addValue("environmentId", payment.environmentId())
                        .addValue("applicationId", payment.applicationId())
                        .addValue("providerAccountId", payment.providerAccountId())
                        .addValue("currency", payment.currency())
                        .addValue("paymentId", payment.id())
                        .addValue("amount", fact.amountMinor())
                        .addValue("fingerprint", "external:" + fact.refundId())
                        .addValue("chargeId", fact.chargeId())
                        .addValue("providerRefundId", fact.refundId()));
    }

    /** A QUERY_REFUND operation and its recovery task, so an external refund is followed to a terminal status. */
    public void insertQueryOperation(
            LocalRefund refund, UUID operationId, String command, Instant expiresAt, Instant availableAt) {
        var scope = new MapSqlParameterSource()
                .addValue("organizationId", refund.organizationId())
                .addValue("environmentId", refund.environmentId())
                .addValue("applicationId", refund.applicationId())
                .addValue("providerAccountId", refund.providerAccountId())
                .addValue("currency", refund.currency())
                .addValue("refundId", refund.id())
                .addValue("operationId", operationId);
        jdbc.update(
                """
                INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_target,refund_id,operation_type,operation_key,
                    request_fingerprint,command,expires_at,external_object_id)
                VALUES (:operationId,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,
                    'REFUND',:refundId,'QUERY_REFUND',:operationKey,:fingerprint,:command::jsonb,:expiresAt,
                    :providerRefundId)""",
                new MapSqlParameterSource(scope.getValues())
                        .addValue("operationKey", "refund_query_" + operationId)
                        .addValue("fingerprint", "external-query:" + refund.providerRefundId())
                        .addValue("command", command)
                        .addValue("expiresAt", Timestamp.from(expiresAt))
                        .addValue("providerRefundId", refund.providerRefundId()));
        jdbc.update(
                """
                INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,
                    currency,operation_id,refund_id,available_at)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:operationId,
                    :refundId,:availableAt)""", new MapSqlParameterSource(scope.getValues()).addValue("availableAt", Timestamp.from(availableAt)));
    }

    /** Releases a terminal refund's hold and adds what it actually refunded. */
    public void settle(UUID paymentId, long releasedMinor, long refundedMinor) {
        jdbc.update("""
                UPDATE platform.refund_control
                SET
                    reserved_minor=reserved_minor-:released,
                    refunded_minor=refunded_minor+:refunded,
                    version=version+1,
                    updated_at=now()
                WHERE payment_id=:paymentId""", Map.of("released", releasedMinor, "refunded", refundedMinor, "paymentId", paymentId));
    }

    /** Writes the fact; identities and failure details are only ever filled in, never replaced. */
    public void applyFact(UUID refundId, FactUpdate update) {
        var fact = update.fact();
        var confirmedAt = Timestamp.from(update.confirmedAt());
        jdbc.update(
                """
                UPDATE platform.refund
                SET
                    refund_status=:status,
                    resolution_status=:resolution,
                    provider_status=:providerStatus,
                    provider_refund_id=coalesce(provider_refund_id,:providerRefundId),
                    balance_transaction_id=coalesce(balance_transaction_id,:bt),
                    failure_balance_transaction_id=coalesce(failure_balance_transaction_id,:failureBt),
                    failure_reason=coalesce(failure_reason,:failureReason),
                    last_provider_confirmed_at=:confirmedAt,
                    unresolved_since=CASE WHEN :recognized THEN NULL ELSE coalesce(unresolved_since,:unresolvedAt) END,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id""",
                new MapSqlParameterSource()
                        .addValue("status", update.status())
                        .addValue("resolution", update.recognized() ? "KNOWN" : "UNKNOWN")
                        .addValue("providerStatus", update.providerStatus())
                        .addValue("providerRefundId", fact.refundId())
                        .addValue("bt", fact.balanceTransactionId())
                        .addValue("failureBt", fact.failureBalanceTransactionId())
                        .addValue("failureReason", fact.failureReason())
                        .addValue("confirmedAt", confirmedAt)
                        .addValue("recognized", update.recognized())
                        .addValue("unresolvedAt", confirmedAt)
                        .addValue("id", refundId));
    }

    /** Makes the refund's idle tasks due now; a webhook or query cannot steal an active sender's lease. */
    public void wakeTasks(UUID refundId, Instant now) {
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status='WAITING',available_at=least(available_at,:now),version=version+1
                WHERE refund_id=:refundId AND status<>'RUNNING' AND status<>'ESCALATED'""", Map.of("now", Timestamp.from(now), "refundId", refundId));
    }

    /** Takes the refund's locks in the global order: payment, refund control, refund. False if it does not exist. */
    public boolean lockForWriteback(UUID refundId) {
        var payment = first(jdbc.queryForList(
                "SELECT payment_id FROM platform.refund WHERE id=:id", Map.of("id", refundId), UUID.class));
        if (payment.isEmpty()) return false;
        jdbc.queryForList("SELECT id FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", payment.get()));
        jdbc.queryForList(
                "SELECT payment_id FROM platform.refund_control WHERE payment_id=:id FOR UPDATE",
                Map.of("id", payment.get()));
        jdbc.queryForList("SELECT id FROM platform.refund WHERE id=:id FOR UPDATE", Map.of("id", refundId));
        return true;
    }

    public RefundControlState controlState(UUID paymentId) {
        return RefundControlState.valueOf(jdbc.queryForObject(
                "SELECT control_state FROM platform.refund_control WHERE payment_id=:paymentId",
                Map.of("paymentId", paymentId),
                String.class));
    }

    public void stampQuery(UUID operationId, Instant now) {
        jdbc.update(
                "UPDATE platform.provider_operation SET last_query_at=:now WHERE id=:id",
                Map.of("now", Timestamp.from(now), "id", operationId));
    }

    /** A create the provider definitively rejected: the hold is released and the refund FAILED with certainty. */
    public void failRejected(LocalRefund refund) {
        jdbc.update("""
                UPDATE platform.refund_control
                SET reserved_minor=reserved_minor-:amount,version=version+1
                WHERE payment_id=:paymentId""", Map.of("amount", refund.amountMinor(), "paymentId", refund.paymentId()));
        jdbc.update("""
                UPDATE platform.refund
                SET refund_status='FAILED',resolution_status='KNOWN',version=version+1,updated_at=now()
                WHERE id=:id""", Map.of("id", refund.id()));
    }

    /** The provider's answer is unusable: the refund becomes UNKNOWN unless already CONFLICT. */
    public void markUnknown(UUID refundId, Instant now) {
        jdbc.update("""
                UPDATE platform.refund
                SET
                    resolution_status=CASE WHEN resolution_status='CONFLICT' THEN 'CONFLICT' ELSE 'UNKNOWN' END,
                    unresolved_since=coalesce(unresolved_since,:now),
                    version=version+1
                WHERE id=:id""", Map.of("now", Timestamp.from(now), "id", refundId));
    }

    public void submit(UUID refundId) {
        jdbc.update("""
                UPDATE platform.refund
                SET refund_status='SUBMITTED',version=version+1,updated_at=now()
                WHERE id=:id AND refund_status='RESERVED'""", Map.of("id", refundId));
    }

    private static MapSqlParameterSource scope(ProviderScope scope) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("environmentId", scope.environmentId())
                .addValue("providerAccountId", scope.providerAccountId());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
