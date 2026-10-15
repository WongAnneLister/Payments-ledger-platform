package com.paymentsledger.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pr02_isolated_identity")
            .withInitScript("db/pr02-runtime-role.sql");

    static final String SECRET = "a".repeat(43);
    static final String HASH = new BCryptPasswordEncoder(10).encode(SECRET);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
    }

    @Autowired
    JdbcTemplate runtimeJdbc;

    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    PlatformTransactionManager transactions;

    @Autowired
    org.flywaydb.core.Flyway flyway;

    @Autowired
    CredentialTokens tokens;

    @Autowired
    IdentityRepository repository;

    static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + "%012d".formatted(n));
    }

    void write(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> work.run());
    }

    ResponseEntity<String> get(String path, String auth) {
        HttpHeaders h = new HttpHeaders();
        if (auth != null) h.set(HttpHeaders.AUTHORIZATION, auth);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    String appToken(int credential) {
        return "Bearer app_" + id(credential) + "." + SECRET;
    }

    @BeforeEach
    void seed() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(source);
        transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        write(() -> {
            jdbc.execute("TRUNCATE platform.organization, platform.currency_config CASCADE");
            jdbc.update("""
                    INSERT INTO platform.currency_config(code,minor_unit_exponent)
                    VALUES ('USD',2),('JPY',0)""");
            for (int n : new int[] {1, 2}) {
                jdbc.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(n), "org" + n);
                jdbc.update("""
                        INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                        VALUES (?,?,'test',false)""", id(10 + n), id(n));
                jdbc.update("""
                        INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                            provider_account_ref,livemode)
                        VALUES (?,?,?,'FAKE',?,false)""", id(20 + n), id(n), id(10 + n), "fixture_account_" + n);
                jdbc.update("""
                        INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                        VALUES (?,?,'USD'),(?,?,'JPY')""", id(n), id(10 + n), id(n), id(10 + n));
            }
            for (int n : new int[] {1, 2, 3}) {
                int org = n == 2 ? 2 : 1;
                jdbc.update("""
                        INSERT INTO platform.application(id,organization_id,environment_id,code)
                        VALUES (?,?,?,?)""", id(30 + n), id(org), id(10 + org), "app" + n);
                jdbc.update("""
                        INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,
                            provider_account_id,secret_hash,not_before,expires_at,granted_scopes)
                        VALUES (?,?,?,?,?,?,?,now()-interval '1 day',now()+interval '1 day',ARRAY['payments:read'])""", id(40 + n), "key" + n, id(org), id(10 + org), id(30 + n), id(20 + org), HASH);
                jdbc.update("""
                        INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                        VALUES (?,?,?,'USD')""", id(40 + n), id(org), id(10 + org));
            }
            jdbc.update("""
                    INSERT INTO platform.ops_identity(id,organization_id,subject,role)
                    VALUES (?,?,'viewer','OPS_VIEWER')""", id(51), id(1));
            jdbc.update("""
                    INSERT INTO platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,
                        secret_hash,not_before,expires_at)
                    VALUES (?,?,?,?,'ops-key',?,now()-interval '1 day',now()+interval '1 day')""", id(61), id(51), id(1), id(11), HASH);
            jdbc.update("""
                    INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,
                        policy_version,partner_ref,share_basis_points)
                    VALUES (?, ?, ?, 'demo',1,'fixture_partner',1500)""", id(71), id(1), id(11));
            jdbc.update("""
                    INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,
                        currency,amount_minor,share_policy_id)
                    VALUES (?,?,?,'demo',1,'USD',10000,?)""", id(81), id(1), id(11), id(71));
        });
    }

    @Test
    void authenticatesFromStoredBindingAndHidesSecrets() {
        var response = get("/v1/identity", appToken(41));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains(id(1).toString(), id(31).toString(), id(21).toString(), "USD", "payments:read")
                .doesNotContain(SECRET, HASH);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void rejectsAbsentMalformedWrongAndUnknownCredentials() {
        for (String auth : new String[] {
            null, "Bearer garbage", appToken(41) + "x", appToken(99), "OpsBearer app_" + id(41) + "." + SECRET
        }) {
            var response = get("/v1/identity", auth);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("UNAUTHORIZED", "requestId").doesNotContain(SECRET);
        }
    }

    @Test
    void validityRevocationAndParentStateAreCheckedEveryRequest() {
        for (String mutation : new String[] {
            "UPDATE platform.application_credential SET expires_at=now()-interval '1 hour'",
            "UPDATE platform.application_credential SET not_before=now()+interval '1 hour'",
            "UPDATE platform.application_credential SET status='REVOKED'",
            "UPDATE platform.application SET status='DISABLED'",
            "UPDATE platform.organization SET status='DISABLED'",
            "UPDATE platform.environment_config SET status='DISABLED'",
            "UPDATE platform.provider_account SET status='DISABLED'",
            "UPDATE platform.org_currency_enabled SET status='DISABLED'"
        }) {
            seed();
            write(() -> jdbc.update(mutation));
            assertThat(get("/v1/identity", appToken(41)).getStatusCode())
                    .as(mutation)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void foreignOrganizationAndSiblingApplicationAreNotEnumerable() {
        assertThat(get("/v1/applications/" + id(31), appToken(41)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (int resource : new int[] {32, 33, 999})
            assertThat(get("/v1/applications/" + id(resource), appToken(41)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/prices/" + id(81), appToken(42)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsForgedQueryAndHeadersButAcceptsMatchingAssertions() {
        for (String field : new String[] {
            "organization_id", "organizationId", "X-Organization-Id", "provider_account_id", "account_id", "livemode"
        }) {
            String value = field.equals("livemode") ? "true" : id(2).toString();
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth("app_" + id(41) + "." + SECRET);
            h.set(field, value);
            assertThat(http.exchange("/v1/identity", HttpMethod.GET, new HttpEntity<>(h), String.class)
                            .getStatusCode())
                    .as(field)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(get("/v1/identity?organization_id=" + id(2), appToken(41)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/identity?organization_id=" + id(1), appToken(41)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void separatesOpsAndApplicationCredentials() {
        String ops = "OpsBearer ops_" + id(61) + "." + SECRET;
        assertThat(get("/ops/identity", ops).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/ops/identity", appToken(41)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/identity", ops).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/ops/identity?organization_id=" + id(2), ops).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void controlledPriceRequiresPermissionAndEnabledCurrency() {
        var price = get("/v1/prices/" + id(81), appToken(41));
        assertThat(price.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(price.getBody()).contains("10000", "USD", "1500");
        write(() -> jdbc.update("UPDATE platform.application_credential SET granted_scopes=ARRAY[]::text[]"));
        assertThat(get("/v1/prices/" + id(81), appToken(41)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void databaseRejectsCrossScopeWritesAndInvalidConfiguration() {
        for (String sql : new String[] {
            "UPDATE platform.application SET organization_id='" + id(2) + "' WHERE id='" + id(31) + "'",
            "UPDATE platform.application_credential SET provider_account_id='" + id(22) + "' WHERE id='" + id(41) + "'",
            "UPDATE platform.provider_account SET livemode=true",
            """
            UPDATE platform.provider_account
            SET provider_account_ref='fixture_account_1'
            WHERE id='""" + id(22) + "'",
            "UPDATE platform.price_fixture SET amount_minor=-1",
            "UPDATE platform.price_fixture SET currency='EUR'",
            "UPDATE platform.currency_config SET minor_unit_exponent=1 WHERE code='JPY'",
            "UPDATE platform.currency_config SET minor_unit_exponent=5",
            "UPDATE platform.share_policy_fixture SET share_basis_points=10001",
            "UPDATE platform.application_credential SET granted_scopes=ARRAY['ops:approve']"
        })
            assertThatThrownBy(() -> write(() -> jdbc.update(sql)))
                    .as(sql)
                    .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void healthIsPublicAndWebhookIsNotFalselyAcknowledged() {
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/webhooks/stripe", new HttpEntity<>("{}", headers), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void refusesLiveDatabaseOnStartupCheck() {
        write(() -> jdbc.update("""
                INSERT INTO platform.environment_config(organization_id,code,livemode)
                VALUES (?,'live',true)""", id(1)));
        assertThatThrownBy(repository::assertTestOnly).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localFixturesAreExplicitHashedAndIdempotent() throws Exception {
        write(() -> jdbc.execute("TRUNCATE platform.organization, platform.currency_config CASCADE"));
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("platform.fixtures.app-secret", SECRET)
                .withProperty("platform.fixtures.ops-secret", "b".repeat(43));
        env.setActiveProfiles("local");
        var initializer = new LocalFixtureInitializer(flyway, tokens, java.time.Clock.systemUTC(), env);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.organization", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM platform.application_credential", String.class))
                .startsWith("$2")
                .doesNotContain(SECRET);
        assertThat(get("/v1/identity", "Bearer app_" + LocalFixtureInitializer.APP_CREDENTIAL + "." + SECRET)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        env.setProperty("platform.fixtures.app-secret", "c".repeat(43));
        assertThatThrownBy(initializer::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("api");
        assertThatThrownBy(initializer::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jpyFixtureAndCurrencyPermissionAreIndependent() {
        write(() -> {
            jdbc.update("""
                    INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,
                        policy_version,partner_ref,share_basis_points)
                    VALUES (?,?,?,'jpy-demo',1,'fixture_partner',0)""", id(72), id(2), id(12));
            jdbc.update("""
                    INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,
                        currency,amount_minor,share_policy_id)
                    VALUES (?,?,?,'jpy-demo',1,'JPY',100,?)""", id(82), id(2), id(12), id(72));
        });
        assertThat(get("/v1/prices/" + id(82), appToken(42)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        write(() -> jdbc.update("""
                INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                VALUES (?,?,?,'JPY')""", id(42), id(2), id(12)));
        assertThat(get("/v1/prices/" + id(82), appToken(42)).getBody())
                .contains("\"minorUnitExponent\":0", "\"amountMinor\":100");
    }

    @Test
    void runtimeRoleCannotRewriteOrCreateConfiguration() {
        assertThat(runtimeJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo("payments_app");
        for (String sql : new String[] {
            """
            UPDATE platform.application_credential
            SET granted_scopes=ARRAY['payments:write']""", "DELETE FROM platform.organization", "INSERT INTO platform.organization(code) VALUES ('injected')"
        })
            assertThatThrownBy(() -> runtimeJdbc.update(sql))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void malformedResourceIdIsClientError() {
        assertThat(get("/v1/applications/not-a-uuid", appToken(41)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/v1/prices/not-a-uuid", appToken(41)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
