package com.paymentsledger.pr20.performance;

import java.util.Arrays;

final class PerformancePolicy {
    private PerformancePolicy() {}

    static long percentile(long[] values, int percent) {
        if (percent < 1 || percent > 100) throw new IllegalArgumentException("Percentile must be 1..100");
        if (values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(percent / 100.0 * sorted.length) - 1];
    }

    static boolean fullBudget(long history, int warmup, int measure, int writes, int queries) {
        return history == 1000000 && warmup == 300 && measure == 1800 && writes == 100 && queries == 200;
    }

    static String verdict(
            boolean full, double p95, double p99, double errors, long drops, long growth, boolean incorrect) {
        if (p95 > 300 || p99 > 1000 || errors >= 0.001 || drops > 0 || growth > 0 || incorrect) return "FAIL";
        return full ? "PASS" : "SMOKE_PASS";
    }

    static long positiveQueueGrowth(java.util.Map<String, Long> before, java.util.Map<String, Long> after) {
        var keys = new java.util.HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream()
                .mapToLong(key -> Math.max(0, after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L)))
                .sum();
    }

    static boolean drained(long queue, long kafka, int outstanding) {
        return queue == 0 && kafka == 0 && outstanding == 0;
    }

    static long scheduledNanos(long ordinal, int rps) {
        if (rps <= 0 || ordinal < 0)
            throw new IllegalArgumentException("Positive rate and nonnegative ordinal required");
        return Math.multiplyExact(ordinal, 1000000000L) / rps;
    }
}
