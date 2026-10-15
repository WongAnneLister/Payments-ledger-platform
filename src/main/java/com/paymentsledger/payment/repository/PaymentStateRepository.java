package com.paymentsledger.payment.repository;

import com.paymentsledger.financialingestion.FinancialSourceLocks;
import com.paymentsledger.payment.PaymentIds;
import com.paymentsledger.payment.domain.ChargeAttempt;
import com.paymentsledger.payment.domain.FinancialStatus;
import com.paymentsledger.payment.domain.PaymentSnapshot;
import com.paymentsledger.payment.domain.PaymentStatus;
import com.paymentsledger.payment.domain.ResolutionStatus;
import com.paymentsledger.provider.ProviderModels.ChargeFact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Data access for merging provider evidence into a payment: the payment row, its charge attempts, normalized capture
 * facts and the amounts that decide its accounting status. All methods run inside the caller's transaction, after
 * {@link #lockPayment}.
 */
public class PaymentStateRepository {
    private static final RowMapper<PaymentSnapshot> PAYMENT = (rs, row) -> new PaymentSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("application_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getString("currency"),
            rs.getLong("amount_minor"),
            rs.getLong("version"),
            PaymentStatus.valueOf(rs.getString("payment_status")),
            rs.getString("provider_status"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            FinancialStatus.valueOf(rs.getString("financial_status")),
            rs.getString("checkout_session_id"),
            rs.getString("payment_intent_id"),
            instant(rs, "last_provider_confirmed_at"),
            instant(rs, "last_webhook_created_at"),
            instant(rs, "last_query_confirmed_at"));

    private static final RowMapper<ChargeAttempt> ATTEMPT = (rs, row) -> new ChargeAttempt(
            rs.getObject("payment_id", UUID.class),
            rs.getString("payment_intent_id"),
            rs.getString("currency"),
            rs.getLong("amount_minor"),
            rs.getBoolean("captured"),
            rs.getObject("amount_captured_minor", Long.class),
            rs.getString("balance_transaction_id"),
            instant(rs, "fetched_at"));

    private final JdbcTemplate plain;
    private final NamedParameterJdbcTemplate jdbc;

    public PaymentStateRepository(JdbcTemplate jdbc) {
        this.plain = jdbc;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public PaymentSnapshot lockPayment(UUID paymentId) {
        return jdbc.query("SELECT * FROM platform.payment WHERE id=:id FOR UPDATE", Map.of("id", paymentId), PAYMENT)
                .getFirst();
    }

    /** Locks the financial sources (charges) the evidence names, in the order financial ingestion uses. */
    public void lockFinancialSources(UUID paymentId, Collection<String> chargeIds) {
        FinancialSourceLocks.lock(plain, paymentId, chargeIds);
    }

    public Optional<ChargeAttempt> findAttempt(PaymentSnapshot payment, String chargeId) {
        var rows = jdbc.query(
                """
                SELECT *
                FROM platform.payment_attempt
                WHERE organization_id=:organizationId AND environment_id=:environmentId \
                AND provider_account_id=:providerAccountId AND provider_object_id=:chargeId""",
                new MapSqlParameterSource()
                        .addValue("organizationId", payment.organizationId())
                        .addValue("environmentId", payment.environmentId())
                        .addValue("providerAccountId", payment.providerAccountId())
                        .addValue("chargeId", chargeId),
                ATTEMPT);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Records a capture fact once; returns 0 if a different fact is already recorded under the same key. */
    public int recordCapture(
            PaymentSnapshot payment, String factKey, ChargeFact charge, String factJson, String source, Instant at) {
        return jdbc.update(
                """
                INSERT INTO platform.normalized_observation(organization_id,environment_id,provider_account_id,
                    fact_key,fact_kind,object_id,payment_id,fact,first_source,last_source,first_confirmed_at,
                    last_confirmed_at)
                VALUES (:organizationId,:environmentId,:providerAccountId,:factKey,'CAPTURE',:objectId,:paymentId,
                    :fact::jsonb,:source,:source,:at,:at)
                ON CONFLICT(organization_id,environment_id,provider_account_id,fact_key)
                DO UPDATE SET
                    last_confirmed_at=greatest(normalized_observation.last_confirmed_at,excluded.last_confirmed_at),
                    last_source=CASE WHEN normalized_observation.last_source='QUERY' THEN 'QUERY' ELSE
                        excluded.last_source END WHERE normalized_observation.fact=excluded.fact""",
                new MapSqlParameterSource()
                        .addValue("organizationId", payment.organizationId())
                        .addValue("environmentId", payment.environmentId())
                        .addValue("providerAccountId", payment.providerAccountId())
                        .addValue("factKey", factKey)
                        .addValue("objectId", charge.chargeId())
                        .addValue("paymentId", payment.id())
                        .addValue("fact", factJson)
                        .addValue("source", source)
                        .addValue("at", Timestamp.from(at)));
    }

    /**
     * Inserts or advances the attempt for this charge. A captured attempt keeps its provider status and captured
     * amount. Returns 0 if the charge belongs to another payment, intent, currency or amount.
     */
    public int upsertAttempt(PaymentSnapshot payment, ChargeFact charge, Instant fetchedAt) {
        return jdbc.update(
                """
                INSERT INTO platform.payment_attempt(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,balance_transaction_id,failure_code,
                    provider_created_at,fetched_at)
                VALUES (:id,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:paymentId,
                    :chargeId,:status,:paymentIntentId,:captured,:amount,:amountCaptured,:balanceTransactionId,
                    :failureCode,:providerCreatedAt,:fetchedAt)
                ON CONFLICT (organization_id,environment_id,provider_account_id,provider_object_id)
                DO UPDATE SET
                    provider_status=CASE WHEN payment_attempt.captured THEN payment_attempt.provider_status ELSE
                        excluded.provider_status END,
                    captured=payment_attempt.captured OR excluded.captured,
                    amount_captured_minor=CASE WHEN payment_attempt.captured THEN
                        payment_attempt.amount_captured_minor ELSE greatest(payment_attempt.amount_captured_minor,
                        excluded.amount_captured_minor) END,
                    balance_transaction_id=coalesce(excluded.balance_transaction_id,
                        payment_attempt.balance_transaction_id),
                    fetched_at=greatest(payment_attempt.fetched_at,excluded.fetched_at),
                    version=payment_attempt.version+CASE WHEN (NOT payment_attempt.captured AND
                        (payment_attempt.provider_status,payment_attempt.captured,
                        payment_attempt.amount_captured_minor) IS DISTINCT FROM (excluded.provider_status,
                        excluded.captured,excluded.amount_captured_minor)) OR (excluded.balance_transaction_id IS NOT NULL AND
                        payment_attempt.balance_transaction_id IS DISTINCT FROM excluded.balance_transaction_id)
                        THEN 1 ELSE 0 END,
                    updated_at=now()
                WHERE payment_attempt.payment_id=excluded.payment_id
                    AND payment_attempt.currency=excluded.currency
                    AND payment_attempt.payment_intent_id=excluded.payment_intent_id
                    AND payment_attempt.amount_minor=excluded.amount_minor""",
                new MapSqlParameterSource()
                        .addValue("id", PaymentIds.newId())
                        .addValue("organizationId", payment.organizationId())
                        .addValue("environmentId", payment.environmentId())
                        .addValue("applicationId", payment.applicationId())
                        .addValue("providerAccountId", payment.providerAccountId())
                        .addValue("currency", payment.currency())
                        .addValue("paymentId", payment.id())
                        .addValue("chargeId", charge.chargeId())
                        .addValue("status", charge.status())
                        .addValue("paymentIntentId", charge.paymentIntentId())
                        .addValue("captured", charge.captured())
                        .addValue("amount", charge.amountMinor())
                        .addValue("amountCaptured", charge.amountCapturedMinor())
                        .addValue("balanceTransactionId", charge.balanceTransactionId())
                        .addValue("failureCode", charge.failureCode())
                        .addValue(
                                "providerCreatedAt",
                                charge.createdAt() == null ? null : Timestamp.from(charge.createdAt()))
                        .addValue("fetchedAt", Timestamp.from(fetchedAt)));
    }

    /** Total captured by succeeded attempts. */
    public long capturedAmount(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount_captured_minor),0)
                FROM platform.payment_attempt
                WHERE payment_id=:paymentId AND captured AND provider_status='succeeded'""", Map.of("paymentId", paymentId), Long.class);
    }

    /** Total of posted charge balance transactions: the captured money the ledger can account for. */
    public long postedChargeAmount(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount_minor),0) FROM platform.balance_transaction
                WHERE payment_id=:paymentId AND state='POSTED' AND lower(business_type)='charge'
                """, Map.of("paymentId", paymentId), Long.class);
    }

    /** The merged state; the update is fenced by the version read under {@link #lockPayment}. */
    public record StateUpdate(
            PaymentStatus status,
            String providerStatus,
            ResolutionStatus resolution,
            Instant unresolvedSince,
            FinancialStatus financial,
            long capturedMinor,
            String sessionId,
            String paymentIntentId,
            String latestChargeId,
            boolean updateHostedUrl,
            String hostedUrl,
            boolean confirmed,
            Instant confirmedAt) {}

    public void update(PaymentSnapshot payment, StateUpdate state) {
        jdbc.update(
                """
                UPDATE platform.payment
                SET
                    payment_status=:status,
                    provider_status=:providerStatus,
                    resolution_status=:resolution,
                    unresolved_since=CASE WHEN :resolution='KNOWN' THEN NULL ELSE coalesce(unresolved_since,:now) END,
                    financial_status=:financial,
                    captured_amount_minor=greatest(captured_amount_minor,:captured),
                    checkout_session_id=coalesce(checkout_session_id,:sessionId),
                    payment_intent_id=coalesce(payment_intent_id,:paymentIntentId),
                    latest_charge_id=coalesce(:latestChargeId,latest_charge_id),
                    hosted_url=CASE WHEN :updateHostedUrl THEN :hostedUrl ELSE hosted_url END,
                    last_provider_confirmed_at=CASE WHEN :confirmed THEN :confirmedAt ELSE last_provider_confirmed_at END,
                    version=version+1,
                    updated_at=now()
                WHERE id=:id AND version=:version""",
                new MapSqlParameterSource()
                        .addValue("status", state.status().name())
                        .addValue("providerStatus", state.providerStatus())
                        .addValue("resolution", state.resolution().name())
                        .addValue("now", Timestamp.from(state.unresolvedSince()))
                        .addValue("financial", state.financial().name())
                        .addValue("captured", state.capturedMinor())
                        .addValue("sessionId", state.sessionId())
                        .addValue("paymentIntentId", state.paymentIntentId())
                        .addValue("latestChargeId", state.latestChargeId())
                        .addValue("updateHostedUrl", state.updateHostedUrl())
                        .addValue("hostedUrl", state.hostedUrl())
                        .addValue("confirmed", state.confirmed())
                        .addValue("confirmedAt", Timestamp.from(state.confirmedAt()))
                        .addValue("id", payment.id())
                        .addValue("version", payment.version()));
    }

    public void markQueryConfirmed(UUID paymentId, Instant at) {
        jdbc.update(
                "UPDATE platform.payment SET last_query_confirmed_at=:at WHERE id=:id",
                Map.of("at", Timestamp.from(at), "id", paymentId));
    }

    public void markWebhookObserved(UUID paymentId, Instant at) {
        jdbc.update(
                "UPDATE platform.payment SET last_webhook_created_at=:at WHERE id=:id",
                Map.of("at", Timestamp.from(at), "id", paymentId));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static List<String> chargeIds(List<ChargeFact> charges) {
        return charges.stream().map(ChargeFact::chargeId).toList();
    }
}
