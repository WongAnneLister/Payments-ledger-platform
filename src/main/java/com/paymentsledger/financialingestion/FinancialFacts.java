package com.paymentsledger.financialingestion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.provider.ProviderModels.*;
import java.util.*;

public final class FinancialFacts {
    private FinancialFacts() {}

    public static ObjectNode immutable(ObjectMapper mapper, BalanceTransactionFact fact) {
        ObjectNode result = mapper.valueToTree(fact);
        result.remove(List.of("status", "availableOn", "rawDigest"));
        result.put("currency", fact.currency().toUpperCase(Locale.ROOT));
        result.put(
                "createdAt", fact.createdAt() == null ? null : fact.createdAt().toString());
        var fees = fact.feeDetails().stream()
                .map(f -> new FeeDetail(
                        f.amountMinor(), f.currency().toUpperCase(Locale.ROOT), f.type(), f.description()))
                .sorted(Comparator.comparing(FeeDetail::currency)
                        .thenComparing(f -> Objects.toString(f.type(), ""))
                        .thenComparingLong(FeeDetail::amountMinor)
                        .thenComparing(f -> Objects.toString(f.description(), "")))
                .toList();
        result.set("feeDetails", mapper.valueToTree(fees));
        return result;
    }
}
