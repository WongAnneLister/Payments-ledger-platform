package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

/** Explicit opt-in real Stripe check through the authenticated business API, in a disposable database. */
@Testcontainers
@ActiveProfiles("api")
@EnabledIfEnvironmentVariable(named = "PR06_STRIPE_CONTRACT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentRealContractIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pr06_real_isolated")
            .withInitScript("db/pr02-runtime-role.sql");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("platform.stripe.enabled", () -> true);
        r.add("platform.stripe.webhook-api-version", () -> "2026-07-29.dahlia");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    static UUID binding(String name) {
        return UUID.fromString(System.getenv(name));
    }

    static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void realAuthenticatedApplicationCreatesQueriesAndExpiresHostedSession() throws Exception {
        String key = System.getenv("STRIPE_API_KEY");
        assertThat(key).isNotNull();
        assertThat(key.startsWith("sk_test_") || key.startsWith("rk_test_")).isTrue();
        UUID org = binding("STRIPE_ORGANIZATION_ID"),
                env = binding("STRIPE_ENVIRONMENT_ID"),
                account = binding("STRIPE_PROVIDER_ACCOUNT_ID");
        UUID app = UUID.randomUUID(),
                credential = UUID.randomUUID(),
                policy = UUID.randomUUID(),
                price = UUID.randomUUID();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.SecureRandom.getSeed(32));
        var admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'pr06-real-contract')", org);
        admin.update("""
                INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                VALUES (?,?,'test',false)""", env, org);
        admin.update("""
                INSERT INTO platform.application(id,organization_id,environment_id,code)
                VALUES (?,?,?,'contract')""", app, org, env);
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'STRIPE',?,false)""", account, org, env, System.getenv("STRIPE_ACCOUNT_ID"));
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        admin.update("""
                INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                VALUES (?,?,'USD')""", org, env);
        admin.update("""
                INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,
                    provider_account_id,secret_hash,not_before,expires_at,granted_scopes)
                VALUES (?,'contract',?,?,?,?,?,now()-interval '1 minute',now()+interval '1 hour',
                    ARRAY['payments:read','payments:write'])""", credential, org, env, app, account, new BCryptPasswordEncoder(10).encode(secret));
        admin.update("""
                INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                VALUES (?,?,?,'USD')""", credential, org, env);
        admin.update("""
                INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,
                    policy_version,partner_ref,share_basis_points)
                VALUES (?,?,?,'pr06-contract',1,'test-partner',1000)""", policy, org, env);
        admin.update("""
                INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,
                    currency,amount_minor,share_policy_id)
                VALUES (?,?,?,'pr06-contract',1,'USD',100,?)""", price, org, env, policy);
        String run = UUID.randomUUID().toString();
        Path directory = Path.of("target/pr06-real", run);
        Files.createDirectories(directory);
        ObjectNode evidence = json.createObjectNode()
                .put("source", "REAL_STRIPE_THROUGH_BUSINESS_HTTP_API")
                .put("runId", run)
                .put("startedAtUtc", Instant.now().toString())
                .put("apiVersion", "2026-07-29.dahlia")
                .put("sdkVersion", "33.3.0")
                .put("mode", "test")
                .put("amountMinor", 100)
                .put("currency", "USD")
                .put("accountSha256", hash(System.getenv("STRIPE_ACCOUNT_ID")))
                .put("cardSubmitted", false)
                .put("status", "NOT_COMPLETED");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("app_" + credential + "." + secret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "pr06-create-" + run);
        ObjectNode body = json.createObjectNode()
                .put("orderKey", "pr06-order-" + run)
                .put("productRef", "pr06-contract")
                .put("priceVersion", 1)
                .put("amountMinor", 100)
                .put("currency", "USD")
                .put("successUrl", "https://example.com/success")
                .put("cancelUrl", "https://example.com/cancel");
        String path = null;
        try {
            var created =
                    http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            evidence.put("createHttpStatus", created.getStatusCode().value());
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode resource = created.getBody();
            path = created.getHeaders().getLocation().toString();
            Files.writeString(directory.resolve("state.local.json"), json.writeValueAsString(resource));
            Files.setPosixFilePermissions(
                    directory.resolve("state.local.json"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            evidence.put("paymentSha256", hash(resource.path("id").asText()))
                    .put(
                            "sessionSha256",
                            hash(resource.path("provider")
                                    .path("checkoutSessionId")
                                    .asText()));
            assertThat(resource.path("paymentStatus").asText()).isEqualTo("AWAITING_CUSTOMER");
            assertThat(resource.path("capturedAmountMinor").asLong()).isZero();
            String hosted = resource.path("nextAction").path("hostedUrl").asText();
            assertThat(hosted).startsWith("https://checkout.stripe.com/");
            evidence.put("hostedCheckoutReturned", true);
            var queried = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            evidence.put("queryHttpStatus", queried.getStatusCode().value());
            assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queried.getBody().path("resolutionStatus").asText()).isEqualTo("KNOWN");
            var replay =
                    http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
            evidence.put("replayHttpStatus", replay.getStatusCode().value());
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody().path("id")).isEqualTo(resource.path("id"));
            assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
            evidence.put("applicationCreateQueryReplay", "VERIFIED");
        } finally {
            try {
                if (path != null) {
                    headers.set("Idempotency-Key", "pr06-cancel-" + run);
                    var canceled =
                            http.exchange(path + "/cancel", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
                    evidence.put("cancelHttpStatus", canceled.getStatusCode().value())
                            .put(
                                    "finalPaymentStatus",
                                    canceled.getBody().path("paymentStatus").asText());
                    assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(canceled.getBody().path("paymentStatus").asText())
                            .isEqualTo("CANCELED");
                    evidence.put("applicationCancel", "VERIFIED");
                    if ("VERIFIED"
                            .equals(evidence.path("applicationCreateQueryReplay")
                                    .asText())) evidence.put("status", "VERIFIED");
                }
            } finally {
                // Retain all persisted commands locally for recovery even if HTTP lost its response; never archive this
                // file.
                Files.writeString(
                        directory.resolve("operations.local.json"),
                        json.writeValueAsString(admin.queryForList(
                                "SELECT id,operation_key,request_fingerprint FROM platform.provider_operation")));
                Files.setPosixFilePermissions(
                        directory.resolve("operations.local.json"),
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                evidence.put("endedAtUtc", Instant.now().toString());
                Files.writeString(
                        directory.resolve("evidence.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            }
        }
    }
}
