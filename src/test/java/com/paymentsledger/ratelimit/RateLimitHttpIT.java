package com.paymentsledger.ratelimit;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Full API role: authentication → account-scoped Redis bucket → RATE_LIMITED contract → fail-open. */
@Testcontainers
@ActiveProfiles("api")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitHttpIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final String SECRET="r".repeat(43),HASH=new BCryptPasswordEncoder(10).encode(SECRET);
    static final int READ_CAPACITY=2;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",()->"payments_app");r.add("spring.datasource.password",()->"pr02_test_only");
        r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("platform.rate-limit.enabled",()->true);
        r.add("platform.rate-limit.read.capacity",()->READ_CAPACITY);r.add("platform.rate-limit.read.refill-per-second",()->0.001);
    }
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    final ObjectMapper json=new ObjectMapper();
    static UUID id(int n){return new UUID(7,n);}

    @BeforeEach void seed() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        jdbc.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        jdbc.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        for(int org:new int[]{1,2}) {
            jdbc.update("INSERT INTO platform.organization(id,code) VALUES (?,?)",id(org),"org"+org);
            jdbc.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",id(10+org),id(org));
            jdbc.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE',?,false)",id(20+org),id(org),id(10+org),"rate_limit_account_"+org);
            jdbc.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD')",id(org),id(10+org));
            jdbc.update("INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,?)",id(30+org),id(org),id(10+org),"app"+org);
            jdbc.update("INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,provider_account_id,secret_hash,not_before,expires_at,granted_scopes) VALUES (?,?,?,?,?,?,?,now()-interval '1 day',now()+interval '1 day',ARRAY['payments:read'])",id(40+org),"key"+org,id(org),id(10+org),id(30+org),id(20+org),HASH);
            jdbc.update("INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency) VALUES (?,?,?,'USD')",id(40+org),id(org),id(10+org));
        }
        if(REDIS.isRunning())redis.execute((org.springframework.data.redis.core.RedisCallback<Void>)c->{c.serverCommands().flushAll();return null;});
    }
    ResponseEntity<String> identity(Integer credential) {
        var headers=new HttpHeaders();
        if(credential!=null)headers.set(HttpHeaders.AUTHORIZATION,"Bearer app_"+id(credential)+"."+SECRET);
        return http.exchange("/v1/identity",HttpMethod.GET,new HttpEntity<>(headers),String.class);
    }

    @Test @Order(1) void exhaustedAccountGets429WhileOtherAccountsAndUnauthenticatedCallsAreUnaffected() throws Exception {
        for(int i=0;i<READ_CAPACITY;i++)assertThat(identity(41).getStatusCode()).isEqualTo(HttpStatus.OK);
        var limited=identity(41);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isPositive();
        assertThat(limited.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        var body=json.readTree(limited.getBody());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("retryable").asBoolean()).isTrue();
        assertThat(body.path("requestId").asText()).isEqualTo(limited.getHeaders().getFirst("X-Request-Id"));

        assertThat(identity(42).getStatusCode()).as("another account scope has its own bucket").isEqualTo(HttpStatus.OK);
        assertThat(identity(null).getStatusCode()).as("authentication still decides first").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/actuator/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(2) void redisOutageFailsOpenAndKeepsTheInstanceHealthy() {
        REDIS.stop();
        for(int i=0;i<READ_CAPACITY+3;i++)assertThat(identity(41).getStatusCode()).isEqualTo(HttpStatus.OK);
        var health=http.getForEntity("/actuator/health",String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
    }
}
