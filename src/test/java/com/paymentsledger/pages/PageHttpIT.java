package com.paymentsledger.pages;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real API-profile page delivery and trusted HTTP boundary; no browser/database shortcuts. */
@Testcontainers
@ActiveProfiles({"api", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageHttpIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static final String SECRET = "p".repeat(43), HASH = new BCryptPasswordEncoder(10).encode(SECRET);
    static final Path FAKE = directory();

    static Path directory() {
        try {
            return Files.createTempDirectory("pr17-page-http-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("platform.provider.fake.enabled", () -> true);
        r.add("platform.provider.fake.directory", FAKE::toString);
        r.add("platform.provider.fake.seed", () -> 17);
        r.add("platform.provider.fake.initial-time", () -> Instant.now().toString());
        r.add("platform.provider.fake.retention", () -> "P1D");
    }

    @Autowired
    TestRestTemplate http;

    JdbcTemplate admin;

    static UUID id(int n) {
        return new UUID(17, n);
    }

    @BeforeEach
    void seed() {
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        for (int o : List.of(1, 2)) {
            admin.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(o), "page-" + o);
            admin.update("""
                    INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                    VALUES (?,?,'test',false)""", id(10 + o), id(o));
            admin.update("""
                    INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                        provider_account_ref,livemode)
                    VALUES (?,?,?,'FAKE',?,false)""", id(20 + o), id(o), id(10 + o), "page-fixture-" + o);
            admin.update("""
                    INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                    VALUES (?,?,'USD')""", id(o), id(10 + o));
            admin.update("""
                    INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,
                        policy_version,partner_ref,share_basis_points)
                    VALUES (?,?,?,'demo',1,'partner',1000)""", id(70 + o), id(o), id(10 + o));
            admin.update("""
                    INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,
                        currency,amount_minor,share_policy_id)
                    VALUES (?,?,?,'demo',1,'USD',100,?)""", id(80 + o), id(o), id(10 + o), id(70 + o));
            admin.update("""
                    INSERT INTO platform.application(id,organization_id,environment_id,code)
                    VALUES (?,?,?,?)""", id(30 + o), id(o), id(10 + o), "page-app-" + o);
            admin.update("""
                    INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,
                        provider_account_id,secret_hash,not_before,expires_at,granted_scopes)
                    VALUES (?,'page',?,?,?,?,?,now()-interval '1 day',now()+interval '1 day',ARRAY['payments:read',
                        'payments:write'])""", id(40 + o), id(o), id(10 + o), id(30 + o), id(20 + o), HASH);
            admin.update("""
                    INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                    VALUES (?,?,?,'USD')""", id(40 + o), id(o), id(10 + o));
            admin.update("""
                    INSERT INTO platform.ops_identity(id,organization_id,subject,role)
                    VALUES (?,?,?,'OPS_VIEWER')""", id(100 + o), id(o), "page-viewer-" + o);
            admin.update("""
                    INSERT INTO platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,
                        secret_hash,not_before,expires_at)
                    VALUES (?,?,?,?,'page',?,now()-interval '1 day',now()+interval '1 day')""", id(110 + o), id(100 + o), id(o), id(10 + o), HASH);
        }
    }

    ResponseEntity<JsonNode> get(String path, String authorization) {
        HttpHeaders h = new HttpHeaders();
        if (authorization != null) h.set("Authorization", authorization);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }

    String create() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("app_" + id(41) + "." + SECRET);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", "page-create-key");
        String body =
                "{\"orderKey\":\"page-order\",\"productRef\":\"demo\",\"priceVersion\":1,\"amountMinor\":100,\"currency\":\"USD\",\"successUrl\":\"https://merchant.test/payment-result.html\",\"cancelUrl\":\"https://merchant.test/payment-result.html\"}";
        var result = http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return result.getBody().path("id").asText();
    }

    @Test
    void separateResultPageHasQueryOnlyModuleAndAccessibleNotice() {
        var page = http.getForEntity("/payment-result.html?success=true", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody())
                .contains("原支付", "aria-live=\"polite\"", "type=\"module\"", "/payment-result.mjs")
                .doesNotContain(SECRET, HASH);
        var module = http.getForEntity("/payment-result.mjs", String.class);
        assertThat(module.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(module.getBody()).contains("createPaymentResultController").doesNotContain("/v1/payments', 'POST");
    }

    @Test
    void opsConsoleAndSharedModulesAreServedWithoutEmbeddedIdentityOrSecrets() {
        var page = http.getForEntity("/ops-console.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody())
                .contains("运营", "aria-live=\"polite\"", "/ops-console.mjs", "<label")
                .doesNotContain(SECRET, HASH);
        for (String asset : List.of("/ops-console.mjs", "/ops-console.css", "/ui/core.mjs"))
            assertThat(http.getForEntity(asset, String.class).getStatusCode())
                    .as(asset)
                    .isEqualTo(HttpStatus.OK);
    }

    @Test
    void existingEntryUsesSeparateReturnPage() {
        var page = http.getForEntity("/payment-test.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("/payment-result.html");
    }

    @Test
    void pagesDoNotBypassApiIdentityOrCrossScopeAuthorization() {
        String payment = create();
        assertThat(get("/v1/payments/" + payment, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/ops/payments/" + payment, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/payments/" + payment, "Bearer app_" + id(42) + "." + SECRET)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/ops/payments/" + payment, "OpsBearer ops_" + id(112) + "." + SECRET)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/ops/identity", "OpsBearer ops_" + id(111) + "." + SECRET)
                        .getBody()
                        .path("role")
                        .asText())
                .isEqualTo("OPS_VIEWER");
    }

    @Test
    void opsHttpKeepsUnknownAndPendingEvidenceIndependentAndNeverRoundsMoney() {
        String payment = create();
        UUID row = admin.queryForObject("SELECT id FROM platform.payment WHERE organization_id=?", UUID.class, id(1));
        admin.update("""
                UPDATE platform.payment
                SET resolution_status='UNKNOWN',financial_status='PENDING_EVIDENCE'
                WHERE id=?""", row);
        var response = get("/ops/payments/" + payment, "OpsBearer ops_" + id(111) + "." + SECRET);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("resolutionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(response.getBody().path("financialStatus").asText()).isEqualTo("PENDING_EVIDENCE");
        assertThat(response.getBody().has("timeline")).isTrue();
        assertThat(response.getBody().has("truncatedSections")).isTrue();
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody().toString()).doesNotContain(SECRET, HASH, "leaseToken", "clientSecret");
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment", Long.class))
                .isEqualTo(1);
    }
}
