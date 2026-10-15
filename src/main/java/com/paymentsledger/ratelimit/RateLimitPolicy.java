package com.paymentsledger.ratelimit;

/** Token bucket shape: {@code capacity} is the burst size, {@code refillPerSecond} the sustained rate. */
public record RateLimitPolicy(long capacity, double refillPerSecond) {
    public RateLimitPolicy {
        if(capacity<1)throw new IllegalArgumentException("Rate limit capacity must be positive");
        if(!(refillPerSecond>0) || Double.isInfinite(refillPerSecond))throw new IllegalArgumentException("Rate limit refill must be positive and finite");
    }
}
