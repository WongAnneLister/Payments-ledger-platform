package com.paymentsledger.payment;

import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;

/** Explicit verified window; Stripe unknown-create retries remain disabled pending real contract verification. */
public record RecoveryPolicy(
        Duration lease,
        Duration fakeVerifiedRetention,
        Duration fakeRetention,
        double jitterRatio,
        Duration unresolvedAge,
        Duration accountBatchInterval,
        List<Duration> l1Delays) {
    public RecoveryPolicy(
            Duration lease, Duration verified, Duration retention, double jitter, Duration age, Duration interval) {
        this(
                lease,
                verified,
                retention,
                jitter,
                age,
                interval,
                List.of(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120)));
    }

    public RecoveryPolicy {
        l1Delays = List.copyOf(l1Delays);
        if (l1Delays.isEmpty() || l1Delays.stream().anyMatch(d -> d.isNegative() || d.isZero()))
            throw new IllegalArgumentException("Recovery delays must be positive");
        if (lease.isNegative()
                || lease.isZero()
                || fakeVerifiedRetention.isNegative()
                || fakeRetention.isNegative()
                || fakeVerifiedRetention.compareTo(fakeRetention) > 0
                || jitterRatio < 0
                || jitterRatio > 0.25
                || unresolvedAge.isNegative()
                || unresolvedAge.isZero()
                || accountBatchInterval.isNegative())
            throw new IllegalArgumentException("Invalid recovery lease, verified retention or schedule");
    }

    static RecoveryPolicy defaults() {
        return new RecoveryPolicy(
                Duration.ofSeconds(30), Duration.ZERO, Duration.ZERO, .1, Duration.ofHours(1), Duration.ofSeconds(1));
    }

    static RecoveryPolicy from(Environment e) {
        return new RecoveryPolicy(
                duration(e, "lease", "PT30S"),
                duration(e, "fake-verified-retention", "PT0S"),
                Duration.parse(e.getProperty("platform.provider.fake.retention", "PT0S")),
                e.getProperty("platform.recovery.jitter-ratio", Double.class, .1),
                duration(e, "unresolved-age", "PT1H"),
                duration(e, "account-batch-interval", "PT1S"),
                Arrays.stream(e.getProperty("platform.recovery.l1-delays", "PT5S,PT15S,PT30S,PT60S,PT120S")
                                .split(","))
                        .map(String::trim)
                        .map(Duration::parse)
                        .toList());
    }

    private static Duration duration(Environment e, String key, String fallback) {
        return Duration.parse(e.getProperty("platform.recovery." + key, fallback));
    }

    Duration verifiedWindow(String provider) {
        return "FAKE".equals(provider) ? fakeVerifiedRetention : Duration.ZERO;
    }

    Duration delay(UUID operation, int attempt) {
        long base = l1Delays.get(Math.min(l1Delays.size() - 1, Math.max(0, attempt - 1)))
                .toMillis();
        double fraction = (Math.floorMod(operation.hashCode() + attempt * 31, 2001) - 1000) / 1000.0;
        return Duration.ofMillis(Math.max(1, Math.round(base * (1 + jitterRatio * fraction))));
    }
}
