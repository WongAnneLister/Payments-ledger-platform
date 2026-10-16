package com.paymentsledger.payment.domain;

import java.util.Set;

/** Refund status facts shared by reservation, writeback and recovery. */
public final class RefundLifecycle {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    private RefundLifecycle() {}

    /** A terminal refund status is final: the database refuses to change it. */
    public static boolean terminal(String status) {
        return TERMINAL.contains(status);
    }
}
