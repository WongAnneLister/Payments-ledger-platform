package com.paymentsledger.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local-only provisioning using the migration role, never the runtime role. */
@Component
@DependsOn("testModeGuard")
@ConditionalOnProperty(name = "platform.fixtures.enabled", havingValue = "true")
public class LocalFixtureInitializer implements InitializingBean {
    public static final UUID APP_CREDENTIAL = id(41), OPS_CREDENTIAL = id(61), PRICE = id(81);
    private final Flyway flyway;
    private final CredentialTokens tokens;
    private final Clock clock;
    private final Environment environment;

    public LocalFixtureInitializer(Flyway flyway, CredentialTokens tokens, Clock clock, Environment environment) {
        this.flyway = flyway;
        this.tokens = tokens;
        this.clock = clock;
        this.environment = environment;
    }

    private static UUID id(int n) {
        return UUID.fromString("10000000-0000-0000-0000-" + "%012d".formatted(n));
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("local")))
            throw new IllegalStateException("Fixture initialization requires local profile");
        CredentialTokens.Parsed app, ops;
        try {
            app = tokens.parse(
                    "app_" + APP_CREDENTIAL + "." + environment.getRequiredProperty("platform.fixtures.app-secret"),
                    "app_");
            ops = tokens.parse(
                    "ops_" + OPS_CREDENTIAL + "." + environment.getRequiredProperty("platform.fixtures.ops-secret"),
                    "ops_");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Two random 43-character local fixture secrets are required");
        }
        if (app.secret().equals(ops.secret()))
            throw new IllegalStateException("Application and ops fixture secrets must differ");
        var source = flyway.getConfiguration().getDataSource();
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(20002)");
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM platform.environment_config WHERE livemode)", Boolean.class)))
                throw new IllegalStateException("Cannot provision fixtures with live configuration");
            var existing = jdbc.queryForList("SELECT id FROM platform.organization", UUID.class);
            if (!existing.isEmpty()) {
                if (existing.size() != 1 || !existing.getFirst().equals(id(1)))
                    throw new IllegalStateException(
                            "Fixture initialization requires an empty or matching fixture database");
                verifyExisting(jdbc, "application_credential", app);
                verifyExisting(jdbc, "ops_credential", ops);
                return;
            }
            jdbc.update("INSERT INTO platform.organization(id,code) VALUES (?,'local-fixture')", id(1));
            jdbc.update("""
                    INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                    VALUES (?,?,'local',false)""", id(11), id(1));
            jdbc.update("""
                    INSERT INTO platform.application(id,organization_id,environment_id,code)
                    VALUES (?,?,?,'demo')""", id(31), id(1), id(11));
            jdbc.update("""
                    INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                        provider_account_ref,livemode)
                    VALUES (?,?,?,'FAKE','fixture_local_account',false)""", id(21), id(1), id(11));
            jdbc.update("""
                    INSERT INTO platform.currency_config(code,minor_unit_exponent)
                    VALUES ('USD',2)
                    ON CONFLICT (code)
                    DO NOTHING""");
            jdbc.update("""
                    INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                    VALUES (?,?,'USD')""", id(1), id(11));
            var start = Timestamp.from(clock.instant());
            var end = Timestamp.from(clock.instant().plus(365, ChronoUnit.DAYS));
            jdbc.update("""
                    INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,provider_account_id,secret_hash,not_before,expires_at,granted_scopes)
                    VALUES (?,'local',?,?,?,?,?,?,?,ARRAY['payments:read','payments:write','refunds:read','refunds:write'])
                    """, APP_CREDENTIAL, id(1), id(11), id(31), id(21), tokens.hash(app), start, end);
            jdbc.update("""
                    INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                    VALUES (?,?,?,'USD')""", APP_CREDENTIAL, id(1), id(11));
            jdbc.update("""
                    INSERT INTO platform.ops_identity(id,organization_id,subject,role)
                    VALUES (?,?,'local-viewer','OPS_VIEWER')""", id(51), id(1));
            jdbc.update("""
                    INSERT INTO platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,
                        secret_hash,not_before,expires_at)
                    VALUES (?,?,?,?,'local',?,?,?)""", OPS_CREDENTIAL, id(51), id(1), id(11), tokens.hash(ops), start, end);
            jdbc.update("""
                    INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,
                        policy_version,partner_ref,share_basis_points)
                    VALUES (?,?,?,'demo-product',1,'fixture-partner',1500)""", id(71), id(1), id(11));
            jdbc.update("""
                    INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,
                        currency,amount_minor,share_policy_id)
                    VALUES (?,?,?,'demo-product',1,'USD',10000,?)""", PRICE, id(1), id(11), id(71));
        });
    }

    private void verifyExisting(JdbcTemplate jdbc, String table, CredentialTokens.Parsed token) {
        // table is a private constant chosen above, never request data.
        var hashes = jdbc.queryForList(
                "SELECT secret_hash FROM platform." + table + " WHERE id=?", String.class, token.id());
        if (hashes.size() != 1 || !tokens.matches(token, hashes.getFirst()))
            throw new IllegalStateException(
                    "Existing fixture credentials differ; provisioning never rotates or overwrites them");
    }
}
