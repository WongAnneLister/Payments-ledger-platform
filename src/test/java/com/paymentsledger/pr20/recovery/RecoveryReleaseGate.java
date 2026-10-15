package com.paymentsledger.pr20.recovery;

import java.util.*;

/** Test-only recovery orchestration gate. It never changes production business eligibility. */
public final class RecoveryReleaseGate {
    public static final Set<String> REQUIRED = Set.of(
            "ISOLATION",
            "ARCHIVE",
            "PERMISSIONS",
            "REBUILD",
            "TRIAL_BALANCE",
            "EVENT_COVERAGE",
            "EXTERNAL_CONTINUITY",
            "BT_COVERAGE",
            "UNKNOWN_DISPOSITION",
            "OUTBOX_REPLAY",
            "INDEPENDENT_BALANCE",
            "MANUAL_LOSS_DISPOSITION");
    private final Map<String, Boolean> evidence = new LinkedHashMap<>();
    private final Set<UUID> eligible = new LinkedHashSet<>();

    public void record(String name, boolean passed) {
        if (!REQUIRED.contains(name)) throw new IllegalArgumentException("Unknown recovery gate " + name);
        evidence.put(name, passed);
    }

    public boolean released() {
        return REQUIRED.stream().allMatch(name -> Boolean.TRUE.equals(evidence.get(name)));
    }

    public Map<String, Boolean> evidence() {
        return Map.copyOf(evidence);
    }

    public void authorize(UUID operation, boolean trustedOrder, boolean safeOriginalKey) {
        if (operation == null || !trustedOrder || !safeOriginalKey)
            throw new IllegalArgumentException("Trusted order and safe original operation key required");
        eligible.add(operation);
    }

    public void requireFundsWrite() {
        throw new IllegalStateException("Unrestricted global funds execution remains held");
    }

    public void requireFundsWrite(UUID operation) {
        if (!released() || !eligible.contains(operation))
            throw new IllegalStateException("Recovery release gates or individual operation eligibility incomplete");
    }

    public static boolean externalContinuous(List<?> before, List<?> after) {
        return before != null && after != null && before.equals(after);
    }
}
