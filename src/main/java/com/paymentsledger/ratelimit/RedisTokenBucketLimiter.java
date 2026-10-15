package com.paymentsledger.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Shared token bucket in Redis. The Lua script runs atomically, so every API instance
 * draws from the same bucket without a read-modify-write race (EVALSHA after first load).
 */
public final class RedisTokenBucketLimiter implements RateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("ratelimit/token_bucket.lua"));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;

    public RedisTokenBucketLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision acquire(String key, RateLimitPolicy policy) {
        List<?> reply = redis.execute(
                SCRIPT, List.of(key), Long.toString(policy.capacity()), Double.toString(policy.refillPerSecond()), "1");
        if (reply == null || reply.size() != 3) throw new IllegalStateException("Unexpected rate limit script reply");
        return new Decision(
                ((Number) reply.get(0)).longValue() == 1,
                Duration.ofNanos(((Number) reply.get(1)).longValue() * 1_000),
                ((Number) reply.get(2)).longValue());
    }
}
