package com.paymentsledger.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiError;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.common.request.RequestContext;
import com.paymentsledger.identity.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Account-scoped rate limit for business APIs ({@code /v1/**}), as promised by the RATE_LIMITED contract.
 *
 * <p>Runs right after authentication so the bucket key comes from the stored credential binding,
 * never from client-supplied headers. Reads and writes have separate buckets: a burst of status
 * polling must not starve payment creation and refunds.
 *
 * <p>Fails open (ADR-0001): Redis is a protective layer, not a correctness source. Idempotency and
 * money invariants live in PostgreSQL, so a Redis outage lets requests through instead of rejecting
 * payments. After a store failure the limiter is bypassed for a short window so every request does
 * not pay the connection timeout.
 */
public final class RateLimitFilter extends OncePerRequestFilter implements Ordered {
    /** After {@code AuthenticationFilter} (HIGHEST_PRECEDENCE+20), which establishes the tenant. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final RateLimitPolicy read, write;
    private final Function<HttpServletRequest, Optional<TenantContext>> tenants;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration bypassAfterFailure;
    private final MeterRegistry meters;
    private volatile Instant bypassUntil = Instant.MIN;

    public RateLimitFilter(
            RateLimiter limiter,
            RateLimitPolicy read,
            RateLimitPolicy write,
            Function<HttpServletRequest, Optional<TenantContext>> tenants,
            ObjectMapper mapper,
            Clock clock,
            Duration bypassAfterFailure,
            MeterRegistry meters) {
        if (bypassAfterFailure.isNegative()) throw new IllegalArgumentException("Bypass window cannot be negative");
        this.limiter = limiter;
        this.read = read;
        this.write = write;
        this.tenants = tenants;
        this.mapper = mapper;
        this.clock = clock;
        this.bypassAfterFailure = bypassAfterFailure;
        this.meters = meters;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    static String key(TenantContext tenant, String bucket) {
        // Hash tag keeps one account's buckets on the same Redis Cluster slot.
        return "rl:{" + tenant.organizationId() + ":" + tenant.environmentId() + ":" + tenant.allowedProviderAccountId()
                + "}:" + bucket;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<TenantContext> tenant =
                path.equals("/v1") || path.startsWith("/v1/") ? tenants.apply(request) : Optional.empty();
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        boolean writes = !READ_METHODS.contains(request.getMethod());
        String bucket = writes ? "write" : "read";
        Instant now = clock.instant();
        if (now.isBefore(bypassUntil)) {
            count(bucket, "bypassed");
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision;
        try {
            decision = limiter.acquire(key(tenant.get(), bucket), writes ? write : read);
        } catch (RuntimeException ex) {
            bypassUntil = now.plus(bypassAfterFailure);
            count(bucket, "bypassed");
            log.warn(
                    "rate_limit_store_unavailable action=fail_open bypass_ms={} error={}",
                    bypassAfterFailure.toMillis(),
                    ex.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }
        if (decision.allowed()) {
            count(bucket, "allowed");
            chain.doFilter(request, response);
            return;
        }
        count(bucket, "limited");
        long seconds = Math.max(1, (decision.retryAfter().toNanos() + 999_999_999L) / 1_000_000_000L);
        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus().value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        mapper.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        ErrorCode.RATE_LIMITED,
                        "Too many requests for this account scope",
                        RequestContext.currentRequestId()));
    }

    private void count(String bucket, String outcome) {
        Counter.builder("platform.ratelimit.decisions")
                .tag("bucket", bucket)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
