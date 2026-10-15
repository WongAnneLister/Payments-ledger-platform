package com.paymentsledger.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Full API role: authentication → account-scoped Redis bucket → RATE_LIMITED contract → fail-open.
 *
 * <p>Uses the JDK client on purpose: TestRestTemplate's HttpClient 5 transparently retries a GET after
 * 429 and sleeps for Retry-After, which hides the 429 and stalls the test for the whole refill time.
 */
@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitHttpIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final String SECRET = "r".repeat(43), HASH = new BCryptPasswordEncoder(10).encode(SECRET);
    static final int READ_CAPACITY = 2;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", () -> "payments_app");
        r.add("spring.datasource.password", () -> "pr02_test_only");
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Production keeps a tight timeout; a shared CI runner must not turn a slow reply into fail-open here.
        r.add("spring.data.redis.timeout", () -> "2s");
        r.add("platform.rate-limit.enabled", () -> true);
        r.add("platform.rate-limit.read.capacity", () -> READ_CAPACITY);
        r.add("platform.rate-limit.read.refill-per-second", () -> 0.001);
    }

    @LocalServerPort
    int port;

    final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    StringRedisTemplate redis;

    final ObjectMapper json = new ObjectMapper();

    static UUID id(int n) {
        return new UUID(7, n);
    }

    @BeforeEach
    void seed() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        jdbc.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        jdbc.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        for (int org : new int[] {1, 2}) {
            jdbc.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(org), "org" + org);
            jdbc.update("""
                    INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                    VALUES (?,?,'test',false)""", id(10 + org), id(org));
            jdbc.update("""
                    INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                        provider_account_ref,livemode)
                    VALUES (?,?,?,'FAKE',?,false)""", id(20 + org), id(org), id(10 + org), "rate_limit_account_" + org);
            jdbc.update("""
                    INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                    VALUES (?,?,'USD')""", id(org), id(10 + org));
            jdbc.update("""
                    INSERT INTO platform.application(id,organization_id,environment_id,code)
                    VALUES (?,?,?,?)""", id(30 + org), id(org), id(10 + org), "app" + org);
            jdbc.update("""
                    INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,
                        provider_account_id,secret_hash,not_before,expires_at,granted_scopes)
                    VALUES (?,?,?,?,?,?,?,now()-interval '1 day',now()+interval '1 day',ARRAY['payments:read'])""", id(40 + org), "key" + org, id(org), id(10 + org), id(30 + org), id(20 + org), HASH);
            jdbc.update("""
                    INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency)
                    VALUES (?,?,?,'USD')""", id(40 + org), id(org), id(10 + org));
        }
        if (REDIS.isRunning())
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
                c.serverCommands().flushAll();
                return null;
            });
    }

    HttpResponse<String> get(String path, Integer credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (credential != null) request.header("Authorization", "Bearer app_" + id(credential) + "." + SECRET);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> identity(Integer credential) throws Exception {
        return get("/v1/identity", credential);
    }

    @Test
    @Order(1)
    void exhaustedAccountGets429WhileOtherAccountsAndUnauthenticatedCallsAreUnaffected() throws Exception {
        for (int i = 0; i < READ_CAPACITY; i++)
            assertThat(identity(41).statusCode()).isEqualTo(200);
        var limited = identity(41);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive();
        assertThat(limited.headers().firstValue("X-Request-Id").orElseThrow()).isNotBlank();
        var body = json.readTree(limited.body());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("retryable").asBoolean()).isTrue();
        assertThat(body.path("requestId").asText())
                .isEqualTo(limited.headers().firstValue("X-Request-Id").orElseThrow());

        assertThat(identity(42).statusCode())
                .as("another account scope has its own bucket")
                .isEqualTo(200);
        assertThat(identity(null).statusCode())
                .as("authentication still decides first")
                .isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void redisOutageFailsOpenAndKeepsTheInstanceHealthy() throws Exception {
        REDIS.stop();
        for (int i = 0; i < READ_CAPACITY + 3; i++)
            assertThat(identity(41).statusCode()).isEqualTo(200);
        var health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
    }
}
