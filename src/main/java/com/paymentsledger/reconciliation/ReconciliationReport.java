package com.paymentsledger.reconciliation;

import com.paymentsledger.provider.ProviderScope;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public record ReconciliationReport(UUID runId, ProviderScope scope, String currency, Instant from,
        Instant until, UUID importWindowId, String state, String ruleVersion, List<Check> checks) {
    public ReconciliationReport { checks=List.copyOf(checks); }
    public record Check(int number, String name, String outcome, BigInteger residual, String reason,
            String owner, Map<String,Object> inputVersions, String snapshot, Map<String,Object> completeness) {
        public Check { inputVersions=Map.copyOf(inputVersions); completeness=Map.copyOf(completeness); }
    }
}
