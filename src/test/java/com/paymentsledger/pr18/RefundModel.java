package com.paymentsledger.pr18;

import java.util.*;

/** Local holds are distinct from provider success; repeated and stale facts are monotone. */
public final class RefundModel {
    private record Refund(long amount, String status) {}

    private final long captured;
    private final Map<String, Refund> refunds = new LinkedHashMap<>();

    public RefundModel(long captured) {
        this.captured = captured;
    }

    public boolean reserve(String key, long amount, boolean blocked) {
        var prior = refunds.get(key);
        if (prior != null) return prior.amount == amount;
        if (blocked || amount <= 0 || amount > captured - refunded() - held()) return false;
        refunds.put(key, new Refund(amount, "pending"));
        return true;
    }

    public void observe(String key, String status) {
        var before = Objects.requireNonNull(refunds.get(key));
        if (Set.of("succeeded", "failed", "canceled").contains(before.status)) return;
        if (!Set.of("pending", "succeeded", "failed", "canceled", "unknown").contains(status))
            throw new IllegalArgumentException(status);
        refunds.put(key, new Refund(before.amount, status));
    }

    public long held() {
        return refunds.values().stream()
                .filter(r -> !Set.of("succeeded", "failed", "canceled").contains(r.status))
                .mapToLong(Refund::amount)
                .sum();
    }

    public long refunded() {
        return refunds.values().stream()
                .filter(r -> r.status.equals("succeeded"))
                .mapToLong(Refund::amount)
                .sum();
    }

    public String status(String key) {
        return refunds.get(key).status;
    }

    public long amount(String key) {
        return refunds.get(key).amount;
    }
}
