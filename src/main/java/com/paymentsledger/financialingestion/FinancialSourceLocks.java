package com.paymentsledger.financialingestion;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Association writers own payment/control/allocation locks before existing source BT locks. */
public final class FinancialSourceLocks {
    private FinancialSourceLocks() {}

    public static void lock(JdbcTemplate jdbc, UUID paymentId, Collection<String> sources) {
        var ids = sources.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        var args = new ArrayList<Object>();
        args.add(paymentId);
        args.addAll(ids);
        jdbc.queryForList(
                "SELECT b.id FROM platform.balance_transaction b JOIN platform.payment p ON p.organization_id=b.organization_id AND p.environment_id=b.environment_id AND p.provider_account_id=b.provider_account_id WHERE p.id=? AND b.source_object_id IN ("
                        + String.join(",", Collections.nCopies(ids.size(), "?"))
                        + ") ORDER BY b.bt_id COLLATE \"C\" FOR UPDATE OF b",
                args.toArray());
    }
}
