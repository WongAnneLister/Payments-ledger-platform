package com.paymentsledger.webhook;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** LOCAL_SIGNED_FIXTURE: actual raw HTTP, SDK signature verification and runtime-role PostgreSQL. */
@Testcontainers
@ActiveProfiles({"api", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StripeWebhookHttpIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static final String SECRET = "whsec_pr08_local_signed_fixture";

    static UUID id(int n) {
        return new UUID(8, n);
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("platform.webhooks.stripe.enabled", () -> true);
        r.add("platform.stripe.webhook-secret", () -> SECRET);
        r.add("platform.stripe.webhook-api-version", () -> "2026-07-29.dahlia");
        r.add("platform.stripe.account-id", () -> "acct_pr08fixture");
        r.add("platform.stripe.organization-id", () -> id(1).toString());
        r.add("platform.stripe.environment-id", () -> id(2).toString());
        r.add("platform.stripe.provider-account-id", () -> id(3).toString());
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    com.paymentsledger.payment.PaymentRepository payments;

    @Autowired
    WebhookProcessor processor;

    JdbcTemplate admin;

    @BeforeEach
    void seed() {
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        admin.execute("TRUNCATE platform.organization CASCADE");
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'pr08')", id(1));
        admin.update(
                "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",
                id(2),
                id(1));
        admin.update(
                "INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'STRIPE','acct_pr08fixture',false)",
                id(3),
                id(1),
                id(2));
    }

    byte[] body(String event) {
        return ("""
        { "id":"%s", "object":"event", "api_version":"2026-07-29.dahlia", "created":1790035200,
          "livemode":false,"type":"checkout.session.completed","data":{"object":{
          "id":"cs_test_pr08","object":"checkout.session","livemode":false,"status":"complete",
          "payment_status":"paid","payment_intent":"pi_pr08","amount_total":100,"currency":"usd",
          "expires_at":1790042400,"metadata":{"payment_id":"00000000-0000-0000-0000-000000000099","private":"private-metadata-sentinel"},
          "customer_details":{"email":"sensitive@example.invalid"},"client_secret":"secret-sentinel"}} }
        """).formatted(event).getBytes(StandardCharsets.UTF_8);
    }

    static String signature(byte[] raw, long time) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal(raw));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ResponseEntity<JsonNode> send(byte[] raw, String... signatures) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (String signature : signatures) headers.add("Stripe-Signature", signature);
        return http.postForEntity("/webhooks/stripe", new HttpEntity<>(raw, headers), JsonNode.class);
    }

    long count(String table) {
        return admin.queryForObject("SELECT count(*) FROM platform." + table, Long.class);
    }

    @Test
    void rawSignedReceiptIsDurableBeforeAnyProcessing() {
        byte[] raw = body("evt_pr08_receipt");
        var response = send(raw, signature(raw, Instant.now().getEpochSecond()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("received").asBoolean()).isTrue();
        assertThat(response.getBody().path("duplicate").asBoolean()).isFalse();
        assertThat(count("webhook_inbox")).isEqualTo(1);
        assertThat(count("payment")).isZero();
        assertThat(count("normalized_observation")).isZero();
        assertThat(count("journal")).isZero();
        String stored = admin.queryForObject("SELECT to_jsonb(w)::text FROM platform.webhook_inbox w", String.class);
        assertThat(stored)
                .doesNotContain("sensitive@example.invalid", "secret-sentinel", "private-metadata-sentinel", SECRET);
        var replay = send(raw, signature(raw, Instant.now().getEpochSecond()));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("duplicate").asBoolean()).isTrue();
        assertThat(count("webhook_inbox")).isEqualTo(1);
    }

    @Test
    void missingWrongStaleAndTamperedSignaturesNeverPersist() {
        byte[] raw = body("evt_bad");
        long now = Instant.now().getEpochSecond();
        for (var response : List.of(
                send(raw),
                send(raw, "t=" + now + ",v1=wrong"),
                send(raw, signature(raw, now - 601)),
                send(
                        new String(raw, StandardCharsets.UTF_8)
                                .replace("pi_pr08", "pi_tampered")
                                .getBytes(StandardCharsets.UTF_8),
                        signature(raw, now)))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("code").asText()).isEqualTo("SIGNATURE_VERIFICATION_FAILED");
        }
        assertThat(count("webhook_inbox")).isZero();
        assertThat(count("payment")).isZero();
        assertThat(count("journal")).isZero();
    }

    @Test
    void signedWrongAccountLiveAndWrongApiVersionAreRejected() {
        String original = new String(body("evt_wrong_scope"), StandardCharsets.UTF_8);
        for (String changed : List.of(
                original.replace("\"object\":\"event\"", "\"object\":\"event\",\"account\":\"acct_other\""),
                original.replace("\"livemode\":false", "\"livemode\":true"),
                original.replace("2026-07-29.dahlia", "2020-01-01"))) {
            byte[] raw = changed.getBytes(StandardCharsets.UTF_8);
            assertThat(send(raw, signature(raw, Instant.now().getEpochSecond())).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(count("webhook_inbox")).isZero();
    }

    @Test
    void databaseWriteFailureReturnsRetryable503InsteadOfAcknowledgement() {
        byte[] raw = body("evt_db_failure");
        admin.execute("REVOKE INSERT ON platform.webhook_inbox FROM payments_app");
        try {
            var response = send(raw, signature(raw, Instant.now().getEpochSecond()));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().path("retryable").asBoolean()).isTrue();
            assertThat(count("webhook_inbox")).isZero();
        } finally {
            admin.execute("GRANT INSERT ON platform.webhook_inbox TO payments_app");
        }
    }

    @Test
    void trustedEndpointBindingMustMatchPersistedStripeAccount() {
        admin.update("UPDATE platform.provider_account SET provider_account_ref='acct_changed' WHERE id=?", id(3));
        byte[] raw = body("evt_binding");
        assertThat(send(raw, signature(raw, Instant.now().getEpochSecond())).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(count("webhook_inbox")).isZero();
    }

    @Test
    void oversizedBodyAndMultipleSignatureHeadersAreRejectedWithoutPersistence() {
        byte[] raw = body("evt_headers");
        String sig = signature(raw, Instant.now().getEpochSecond());
        assertThat(send(raw, sig, sig).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        byte[] oversized = new byte[262145];
        Arrays.fill(oversized, (byte) ' ');
        assertThat(send(oversized, signature(oversized, Instant.now().getEpochSecond()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("webhook_inbox")).isZero();
    }

    @Test
    void sameEventWithChangedSignedContentCannotReplaceOriginalReceipt() {
        byte[] raw = body("evt_conflicting_receipt");
        assertThat(send(raw, signature(raw, Instant.now().getEpochSecond())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String digest = admin.queryForObject("SELECT raw_digest FROM platform.webhook_inbox", String.class);
        byte[] changed = new String(raw, StandardCharsets.UTF_8)
                .replace("pi_pr08", "pi_different")
                .getBytes(StandardCharsets.UTF_8);
        var conflict = send(changed, signature(changed, Instant.now().getEpochSecond()));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("code").asText()).isEqualTo("EVIDENCE_CONFLICT");
        assertThat(count("webhook_inbox")).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT raw_digest FROM platform.webhook_inbox", String.class))
                .isEqualTo(digest);
        assertThat(count("payment")).isZero();
        assertThat(count("journal")).isZero();
    }

    @Test
    void unsupportedContentTypeIsAClientErrorWithoutPersistence() {
        var response = http.postForEntity("/webhooks/stripe", "raw", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR").containsEntry("retryable", false);
        assertThat(count("webhook_inbox")).isZero();
    }

    @Test
    void signedHttpFactsReachPaymentThroughTheAsynchronousProcessor() throws Exception {
        admin.update(
                "INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,'signed-app')",
                id(4),
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2) ON CONFLICT(code) DO NOTHING");
        admin.update(
                "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD')",
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,policy_version,partner_ref,share_basis_points) VALUES (?,?,?,'demo',1,'partner',1000)",
                id(5),
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,currency,amount_minor,share_policy_id) VALUES (?,?,?,'demo',1,'USD',100,?)",
                id(6),
                id(1),
                id(2),
                id(5));
        var tenant = new com.paymentsledger.identity.TenantContext(
                id(1), id(2), id(4), id(3), Set.of("USD"), false, Set.of("payments:read", "payments:write"));
        var command = new com.paymentsledger.payment.PaymentRequests.Create(
                "signed-order",
                "demo",
                1,
                100,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
        var intent = payments.prepare(tenant, "signed-http-lifecycle", command, "STRIPE");
        long now = Instant.now().getEpochSecond();
        String pi =
                "{\"id\":\"pi_signed\",\"object\":\"payment_intent\",\"livemode\":false,\"status\":\"succeeded\",\"amount\":100,\"amount_received\":100,\"currency\":\"usd\",\"created\":"
                        + now + ",\"latest_charge\":\"ch_signed\",\"metadata\":{\"payment_id\":\"" + intent.paymentId()
                        + "\"}}";
        String charge =
                "{\"id\":\"ch_signed\",\"object\":\"charge\",\"livemode\":false,\"status\":\"succeeded\",\"captured\":true,\"amount\":100,\"amount_captured\":100,\"currency\":\"usd\",\"created\":"
                        + now + ",\"payment_intent\":\"pi_signed\"}";
        byte[] piEvent = envelope("evt_signed_pi", "payment_intent.succeeded", pi, now);
        assertThat(send(piEvent, signature(piEvent, now)).getStatusCode()).isEqualTo(HttpStatus.OK);
        process();
        assertThat(admin.queryForObject(
                        "SELECT captured_amount_minor FROM platform.payment WHERE id=?",
                        Long.class,
                        intent.paymentId()))
                .isZero();
        byte[] first = envelope("evt_signed_capture1", "charge.succeeded", charge, now);
        byte[] second = envelope("evt_signed_capture2", "charge.succeeded", charge, now);
        assertThat(send(first, signature(first, now)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(second, signature(second, now)).getStatusCode()).isEqualTo(HttpStatus.OK);
        process();
        var result = payments.read(tenant, intent.paymentId());
        assertThat(result)
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("financialStatus", "PENDING_EVIDENCE")
                .containsEntry("capturedAmountMinor", 100L);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(count("journal")).isZero();
        assertThat(admin.queryForObject(
                        "SELECT count(*) FROM platform.normalized_observation WHERE fact_kind='CAPTURE'", Long.class))
                .isEqualTo(1);
    }

    byte[] envelope(String id, String type, String object, long created) {
        return ("{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2026-07-29.dahlia\",\"created\":"
                        + created + ",\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":" + object
                        + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    void process() throws Exception {
        processor.processDue(20);
    }
}
