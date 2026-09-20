package com.paymentsledger.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** All reads use a database snapshot; all resource lookups include trusted scope. */
@Repository
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class IdentityRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CredentialTokens tokens;
    public IdentityRepository(JdbcTemplate jdbc, Clock clock, CredentialTokens tokens) {
        this.jdbc=jdbc; this.clock=clock; this.tokens=tokens;
    }
    public TenantContext authenticateApplication(CredentialTokens.Parsed token) {
        Timestamp now=Timestamp.from(clock.instant());
        var results=jdbc.query("""
                SELECT c.*, e.livemode FROM platform.application_credential c
                JOIN platform.organization o ON o.id=c.organization_id
                JOIN platform.environment_config e ON e.id=c.environment_id AND e.organization_id=c.organization_id
                JOIN platform.application a ON a.id=c.application_id AND a.organization_id=c.organization_id AND a.environment_id=c.environment_id
                JOIN platform.provider_account p ON p.id=c.provider_account_id AND p.organization_id=c.organization_id AND p.environment_id=c.environment_id
                WHERE c.id=? AND c.status='ACTIVE' AND c.not_before<=? AND c.expires_at>?
                AND o.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE'
                AND NOT e.livemode AND NOT p.livemode
                """,(rs,row) -> {
            if (!tokens.matches(token,rs.getString("secret_hash"))) throw TenantAccessGuard.unauthorized();
            UUID org=rs.getObject("organization_id",UUID.class), env=rs.getObject("environment_id",UUID.class);
            List<String> currencies=jdbc.queryForList("""
                    SELECT c.currency FROM platform.credential_currency c
                    JOIN platform.org_currency_enabled e USING (organization_id,environment_id,currency)
                    WHERE c.credential_id=? AND c.organization_id=? AND c.environment_id=? AND e.status='ACTIVE'
                    """,String.class,token.id(),org,env);
            return new TenantContext(org,env,rs.getObject("application_id",UUID.class),
                    rs.getObject("provider_account_id",UUID.class),new HashSet<>(currencies),rs.getBoolean("livemode"),
                    new HashSet<>(Arrays.asList((String[])rs.getArray("granted_scopes").getArray())));
        },token.id(),now,now);
        if (results.isEmpty()) tokens.rejectUnknown(token);
        return results.getFirst();
    }
    public OpsContext authenticateOps(CredentialTokens.Parsed token) {
        Timestamp now=Timestamp.from(clock.instant());
        var results=jdbc.query("""
                SELECT c.secret_hash,c.environment_id,i.* FROM platform.ops_credential c
                JOIN platform.ops_identity i ON i.id=c.ops_identity_id AND i.organization_id=c.organization_id
                JOIN platform.organization o ON o.id=c.organization_id
                JOIN platform.environment_config e ON e.id=c.environment_id AND e.organization_id=c.organization_id
                WHERE c.id=? AND c.status='ACTIVE' AND c.not_before<=? AND c.expires_at>?
                AND i.status='ACTIVE' AND o.status='ACTIVE' AND e.status='ACTIVE' AND NOT e.livemode
                """,(rs,row) -> {
            if (!tokens.matches(token,rs.getString("secret_hash"))) throw TenantAccessGuard.unauthorized();
            return new OpsContext(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),
                    rs.getObject("environment_id",UUID.class),rs.getString("role"),rs.getObject("partner_id",UUID.class));
        },token.id(),now,now);
        if (results.isEmpty()) tokens.rejectUnknown(token);
        return results.getFirst();
    }
    public record ApplicationView(UUID id, String code) { }
    public ApplicationView application(TenantContext context, UUID id) {
        TenantAccessGuard.require(context);
        var rows=jdbc.query("""
                SELECT id,code FROM platform.application WHERE id=? AND id=? AND organization_id=?
                AND environment_id=? AND status='ACTIVE'
                """,(rs,n) -> new ApplicationView(rs.getObject("id",UUID.class),rs.getString("code")),
                id,context.applicationId(),context.organizationId(),context.environmentId());
        if(rows.isEmpty()) throw TenantAccessGuard.notFound();
        return rows.getFirst();
    }
    public record PriceView(UUID id, String productRef, int priceVersion, String currency, int minorUnitExponent,
                            long amountMinor, int policyVersion, String partnerRef, int shareBasisPoints) { }
    public PriceView price(TenantContext context, UUID id) {
        TenantAccessGuard.requireScope(context,"payments:read");
        Timestamp now=Timestamp.from(clock.instant());
        var rows=jdbc.query("""
                SELECT p.*,c.minor_unit_exponent,s.policy_version,s.partner_ref,s.share_basis_points
                FROM platform.price_fixture p
                JOIN platform.share_policy_fixture s ON s.id=p.share_policy_id AND s.organization_id=p.organization_id
                  AND s.environment_id=p.environment_id AND s.product_ref=p.product_ref
                JOIN platform.currency_config c ON c.code=p.currency
                JOIN platform.org_currency_enabled e ON e.organization_id=p.organization_id
                  AND e.environment_id=p.environment_id AND e.currency=p.currency
                WHERE p.id=? AND p.organization_id=? AND p.environment_id=? AND p.status='ACTIVE'
                  AND s.status='ACTIVE' AND e.status='ACTIVE'
                  AND p.valid_from<=? AND p.valid_until>? AND s.valid_from<=? AND s.valid_until>?
                """,(rs,n)->new PriceView(rs.getObject("id",UUID.class),rs.getString("product_ref"),rs.getInt("price_version"),
                rs.getString("currency"),rs.getInt("minor_unit_exponent"),rs.getLong("amount_minor"),rs.getInt("policy_version"),
                rs.getString("partner_ref"),rs.getInt("share_basis_points")),
                id,context.organizationId(),context.environmentId(),now,now,now,now);
        if(rows.isEmpty()) throw TenantAccessGuard.notFound();
        TenantAccessGuard.requireCurrency(context,rows.getFirst().currency());
        return rows.getFirst();
    }
    public void assertTestOnly() {
        Boolean live=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.environment_config WHERE livemode) OR EXISTS(SELECT 1 FROM platform.provider_account WHERE livemode)",Boolean.class);
        if (!Boolean.FALSE.equals(live)) throw new IllegalStateException("Live configuration is forbidden in this test-only platform");
    }
}
