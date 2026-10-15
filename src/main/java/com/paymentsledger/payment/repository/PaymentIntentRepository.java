package com.paymentsledger.payment.repository;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.domain.IdempotencyRecord;
import com.paymentsledger.payment.domain.PriceQuote;
import com.paymentsledger.payment.domain.ProviderBinding;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Data access for accepting payment create and cancel intents. All methods run inside the caller's transaction; the
 * advisory locks serialize requests per idempotency key and per business order key.
 */
public class PaymentIntentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Transaction-scoped advisory lock on an arbitrary key. */
    public void lock(String key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", key));
    }

    public Optional<IdempotencyRecord> findIdempotencyRecord(TenantContext tenant, String requestType, String key) {
        var rows = jdbc.query(
                """
                SELECT *
                FROM platform.api_idempotency_record
                WHERE organization_id=:organizationId
                    AND environment_id=:environmentId
                    AND application_id=:applicationId
                    AND request_type=:requestType
                    AND idempotency_key=:key""",
                application(tenant).addValue("requestType", requestType).addValue("key", key),
                (rs, row) -> new IdempotencyRecord(
                        rs.getString("request_fingerprint"),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("operation_id", UUID.class)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean orderKeyExists(TenantContext tenant, String orderKey) {
        return !jdbc.queryForList("""
                        SELECT id
                        FROM platform.business_order
                        WHERE organization_id=:organizationId AND environment_id=:environmentId \
                        AND application_id=:applicationId AND order_key=:orderKey""", application(tenant).addValue("orderKey", orderKey))
                .isEmpty();
    }

    /** The tenant's provider account, if it, its organization, environment and application are active and in test. */
    public Optional<ProviderBinding> findProviderBinding(TenantContext tenant) {
        var rows = jdbc.query(
                """
                SELECT p.provider,p.provider_account_ref FROM platform.provider_account p
                JOIN platform.organization o ON o.id=p.organization_id
                JOIN platform.environment_config e ON e.organization_id=p.organization_id AND e.id=p.environment_id
                JOIN platform.application a ON a.organization_id=p.organization_id AND a.environment_id=p.environment_id
                WHERE p.organization_id=:organizationId AND p.environment_id=:environmentId \
                AND p.id=:providerAccountId AND a.id=:applicationId
                AND p.status='ACTIVE' AND o.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE'
                AND NOT p.livemode AND NOT e.livemode
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", tenant.organizationId())
                        .addValue("environmentId", tenant.environmentId())
                        .addValue("providerAccountId", tenant.allowedProviderAccountId())
                        .addValue("applicationId", tenant.applicationId()),
                (rs, row) -> new ProviderBinding(rs.getString("provider"), rs.getString("provider_account_ref")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** The active price and share policy for a product version at {@code at}, if its currency is enabled. */
    public Optional<PriceQuote> findPrice(TenantContext tenant, String productRef, int priceVersion, Instant at) {
        Timestamp now = Timestamp.from(at);
        var rows = jdbc.query(
                """
                SELECT p.*,to_jsonb(p)::text price_snapshot,to_jsonb(s)::text policy_snapshot
                FROM platform.price_fixture p JOIN platform.share_policy_fixture s
                 ON s.id=p.share_policy_id AND s.organization_id=p.organization_id AND s.environment_id=p.environment_id AND s.product_ref=p.product_ref
                JOIN platform.org_currency_enabled e ON e.organization_id=p.organization_id AND e.environment_id=p.environment_id AND e.currency=p.currency
                WHERE p.organization_id=:organizationId AND p.environment_id=:environmentId \
                AND p.product_ref=:productRef AND p.price_version=:priceVersion
                 AND p.status='ACTIVE' AND s.status='ACTIVE' AND e.status='ACTIVE'
                 AND p.valid_from<=:now AND p.valid_until>:now AND s.valid_from<=:now AND s.valid_until>:now
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", tenant.organizationId())
                        .addValue("environmentId", tenant.environmentId())
                        .addValue("productRef", productRef)
                        .addValue("priceVersion", priceVersion)
                        .addValue("now", now),
                (rs, row) -> new PriceQuote(
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("price_snapshot"),
                        rs.getString("policy_snapshot")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public record NewOrder(
            UUID id,
            String currency,
            String orderKey,
            String requestFingerprint,
            String productRef,
            int priceVersion,
            int quantity,
            PriceQuote price,
            long amountMinor) {}

    public void insertOrder(TenantContext tenant, NewOrder order) {
        jdbc.update(
                """
                INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,
                    unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot)
                VALUES (:id,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:orderKey,
                    :fingerprint,:productRef,:priceVersion,:quantity,:unitAmount,:amount,:priceSnapshot::jsonb,
                    :policySnapshot::jsonb)""",
                account(tenant)
                        .addValue("id", order.id())
                        .addValue("currency", order.currency())
                        .addValue("orderKey", order.orderKey())
                        .addValue("fingerprint", order.requestFingerprint())
                        .addValue("productRef", order.productRef())
                        .addValue("priceVersion", order.priceVersion())
                        .addValue("quantity", order.quantity())
                        .addValue("unitAmount", order.price().unitAmountMinor())
                        .addValue("amount", order.amountMinor())
                        .addValue("priceSnapshot", order.price().priceSnapshot())
                        .addValue("policySnapshot", order.price().policySnapshot()));
    }

    public void insertPayment(
            TenantContext tenant, UUID paymentId, String currency, UUID orderId, long amountMinor, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,business_order_id,amount_minor,expires_at)
                VALUES (:id,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:orderId,
                    :amount,:expiresAt)""",
                account(tenant)
                        .addValue("id", paymentId)
                        .addValue("currency", currency)
                        .addValue("orderId", orderId)
                        .addValue("amount", amountMinor)
                        .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    public record NewOperation(
            UUID id,
            String currency,
            UUID paymentId,
            String type,
            String operationKey,
            String requestFingerprint,
            String commandJson,
            Instant expiresAt) {}

    /** Records the provider operation and its recovery task, due immediately, in the same transaction. */
    public void insertOperation(TenantContext tenant, NewOperation operation, Instant now) {
        jdbc.update(
                """
                INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,operation_target,payment_id,operation_type,operation_key,
                    request_fingerprint,command,expires_at)
                VALUES (:id,:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,'PAYMENT',
                    :paymentId,:type,:key,:fingerprint,:command::jsonb,:expiresAt)""",
                account(tenant)
                        .addValue("id", operation.id())
                        .addValue("currency", operation.currency())
                        .addValue("paymentId", operation.paymentId())
                        .addValue("type", operation.type())
                        .addValue("key", operation.operationKey())
                        .addValue("fingerprint", operation.requestFingerprint())
                        .addValue("command", operation.commandJson())
                        .addValue("expiresAt", Timestamp.from(operation.expiresAt())));
        jdbc.update(
                """
                INSERT INTO platform.recovery_task(organization_id,environment_id,application_id,provider_account_id,
                    currency,operation_id,payment_id,available_at)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:operationId,
                    :paymentId,:availableAt)""",
                account(tenant)
                        .addValue("currency", operation.currency())
                        .addValue("operationId", operation.id())
                        .addValue("paymentId", operation.paymentId())
                        .addValue("availableAt", Timestamp.from(now)));
    }

    public void insertIdempotencyRecord(
            TenantContext tenant,
            String currency,
            String requestType,
            String key,
            String requestFingerprint,
            UUID paymentId,
            UUID operationId) {
        jdbc.update(
                """
                INSERT INTO platform.api_idempotency_record(organization_id,environment_id,application_id,
                    provider_account_id,currency,request_type,idempotency_key,request_fingerprint,payment_id,
                    operation_id)
                VALUES (:organizationId,:environmentId,:applicationId,:providerAccountId,:currency,:requestType,:key,
                    :fingerprint,:paymentId,:operationId)""",
                account(tenant)
                        .addValue("currency", currency)
                        .addValue("requestType", requestType)
                        .addValue("key", key)
                        .addValue("fingerprint", requestFingerprint)
                        .addValue("paymentId", paymentId)
                        .addValue("operationId", operationId));
    }

    /** The payment's single cancel operation, if one was already prepared. */
    public Optional<UUID> findCancelOperation(UUID paymentId) {
        var rows = jdbc.query("""
                SELECT *
                FROM platform.provider_operation
                WHERE payment_id=:paymentId AND operation_type='CANCEL_PAYMENT'""", Map.of("paymentId", paymentId), (rs, row) -> rs.getObject("id", UUID.class));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static MapSqlParameterSource application(TenantContext tenant) {
        return new MapSqlParameterSource()
                .addValue("organizationId", tenant.organizationId())
                .addValue("environmentId", tenant.environmentId())
                .addValue("applicationId", tenant.applicationId());
    }

    private static MapSqlParameterSource account(TenantContext tenant) {
        return application(tenant).addValue("providerAccountId", tenant.allowedProviderAccountId());
    }
}
