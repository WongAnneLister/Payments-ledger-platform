package com.paymentsledger.ratelimit;

import java.time.Duration;

public interface RateLimiter {
    /** Consumes one token from {@code key}; implementations throw when the shared store is unreachable. */
    Decision acquire(String key,RateLimitPolicy policy);

    record Decision(boolean allowed,Duration retryAfter,long remaining) {}
}
