package com.paymentsledger.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.identity.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.*;

class RateLimitFilterTest {
    static final TenantContext TENANT = new TenantContext(
            new UUID(0, 1),
            new UUID(0, 2),
            new UUID(0, 3),
            new UUID(0, 4),
            Set.of("USD"),
            false,
            Set.of("payments:read"));
    static final RateLimitPolicy READ = new RateLimitPolicy(100, 50), WRITE = new RateLimitPolicy(20, 10);

    final List<String> keys = new ArrayList<>();
    final List<RateLimitPolicy> policies = new ArrayList<>();
    final Deque<Object> replies = new ArrayDeque<>();
    Instant now = Instant.parse("2026-10-15T00:00:00Z");
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final RateLimiter limiter = (key, policy) -> {
        keys.add(key);
        policies.add(policy);
        Object reply = replies.isEmpty() ? new RateLimiter.Decision(true, Duration.ZERO, 1) : replies.removeFirst();
        if (reply instanceof RuntimeException ex) throw ex;
        return (RateLimiter.Decision) reply;
    };
    final RateLimitFilter filter = new RateLimitFilter(
            limiter,
            READ,
            WRITE,
            r -> Optional.ofNullable((TenantContext) r.getAttribute("tenant")),
            new ObjectMapper().findAndRegisterModules(),
            new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return now;
                }
            },
            Duration.ofSeconds(1),
            meters);

    MockHttpServletResponse call(String method, String path, boolean authenticated, MockFilterChain chain)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (authenticated) request.setAttribute("tenant", TENANT);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    double count(String bucket, String outcome) {
        var counter = meters.find("platform.ratelimit.decisions")
                .tag("bucket", bucket)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void runsAfterAuthenticationSoTheTenantIsTrusted() {
        assertThat(filter.getOrder()).isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Test
    void nonBusinessAndUnauthenticatedRoutesAreNotLimited() throws Exception {
        for (String path : List.of("/actuator/health", "/webhooks/stripe", "/ops/identity", "/v1x")) {
            var chain = new MockFilterChain();
            assertThat(call("GET", path, true, chain).getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
        var chain = new MockFilterChain();
        call("GET", "/v1/identity", false, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(keys).isEmpty();
    }

    @Test
    void readsAndWritesUseSeparateAccountScopedBuckets() throws Exception {
        call("GET", "/v1/payments/pay_1", true, new MockFilterChain());
        call("POST", "/v1/payments", true, new MockFilterChain());
        call("POST", "/v1/refunds", true, new MockFilterChain());
        String scope = "rl:{" + TENANT.organizationId() + ":" + TENANT.environmentId() + ":"
                + TENANT.allowedProviderAccountId() + "}:";
        assertThat(keys).containsExactly(scope + "read", scope + "write", scope + "write");
        assertThat(policies).containsExactly(READ, WRITE, WRITE);
        assertThat(count("read", "allowed")).isEqualTo(1);
        assertThat(count("write", "allowed")).isEqualTo(2);
    }

    @Test
    void exhaustedBucketRendersTheRateLimitedContract() throws Exception {
        replies.add(new RateLimiter.Decision(false, Duration.ofMillis(1_200), 0));
        var chain = new MockFilterChain();
        var response = call("POST", "/v1/payments", true, chain);
        assertThat(chain.getRequest()).as("handler must not run").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("retryable").asBoolean()).isTrue();
        assertThat(count("write", "limited")).isEqualTo(1);
    }

    @Test
    void subSecondRetryIsRoundedUpToOneSecond() throws Exception {
        replies.add(new RateLimiter.Decision(false, Duration.ofNanos(1_000), 0));
        assertThat(call("GET", "/v1/identity", true, new MockFilterChain()).getHeader("Retry-After"))
                .isEqualTo("1");
    }

    @Test
    void storeFailureFailsOpenAndSkipsRedisForTheBypassWindow() throws Exception {
        replies.add(new QueryTimeoutException("redis down"));
        var first = new MockFilterChain();
        assertThat(call("POST", "/v1/payments", true, first).getStatus()).isEqualTo(200);
        assertThat(first.getRequest()).isNotNull();

        now = now.plusMillis(999);
        var during = new MockFilterChain();
        call("POST", "/v1/payments", true, during);
        assertThat(during.getRequest()).isNotNull();
        assertThat(keys).as("no second Redis call inside the bypass window").hasSize(1);
        assertThat(count("write", "bypassed")).isEqualTo(2);

        now = now.plusMillis(1);
        replies.add(new RateLimiter.Decision(false, Duration.ofSeconds(1), 0));
        assertThat(call("POST", "/v1/payments", true, new MockFilterChain()).getStatus())
                .as("limiting resumes after the window")
                .isEqualTo(429);
        assertThat(keys).hasSize(2);
    }

    @Test
    void invalidPoliciesAreRejectedAtStartup() {
        assertThatThrownBy(() -> new RateLimitPolicy(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
