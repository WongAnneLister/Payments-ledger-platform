package com.paymentsledger.financialingestion;

import com.paymentsledger.provider.ProviderModels.BalanceTransactionFact;
import java.util.Locale;
import java.util.Set;

/** Classification never invents ownership. CHARGE and REFUND still need trusted business evidence. */
public final class FinancialEventNormalizer {
    public enum Kind {
        CHARGE,
        REFUND,
        REFUND_FAILURE,
        DISPUTE,
        PAYOUT,
        UNKNOWN,
        PENDING,
        QUARANTINE
    }

    public Kind classify(BalanceTransactionFact fact) {
        String type = fact.type() == null ? "" : fact.type().toLowerCase(Locale.ROOT);
        String source =
                fact.sourceObjectType() == null ? "" : fact.sourceObjectType().toLowerCase(Locale.ROOT);
        if (!"PAYMENTS".equals(fact.balanceDomain())
                || type.contains("currency")
                || type.contains("exchange")
                || type.contains("fx")
                || fact.feeDetails().stream().anyMatch(f -> !f.currency().equalsIgnoreCase(fact.currency()))
                || !Set.of("pending", "available").contains(fact.status() == null ? "" : fact.status()))
            return Kind.QUARANTINE;
        if (source.equals("payout"))
            return Set.of("payout", "payout_failure", "payout_cancel").contains(type)
                            && (type.equals("payout") ? fact.amountMinor() < 0 : fact.amountMinor() > 0)
                    ? Kind.PAYOUT
                    : Kind.QUARANTINE;
        if (source.equals("dispute"))
            return Set.of("adjustment", "dispute", "dispute_reversal", "dispute_fee")
                            .contains(type)
                    ? Kind.DISPUTE
                    : Kind.QUARANTINE;
        if (type.equals("charge"))
            return fact.amountMinor() > 0 && fact.feeMinor() >= 0 && fact.netMinor() >= 0 && source.equals("charge")
                    ? Kind.CHARGE
                    : Kind.QUARANTINE;
        if (type.equals("refund"))
            return fact.amountMinor() < 0 && fact.netMinor() <= 0 && source.equals("refund")
                    ? Kind.REFUND
                    : Kind.QUARANTINE;
        if (type.equals("refund_failure"))
            return fact.amountMinor() > 0 && fact.netMinor() >= 0 && source.equals("refund")
                    ? Kind.REFUND_FAILURE
                    : Kind.QUARANTINE;
        if (type.contains("dispute")
                || type.contains("payout")
                || source.contains("dispute")
                || source.contains("payout")
                || source.equals("refund")
                || source.equals("charge")) return Kind.PENDING;
        return Kind.UNKNOWN;
    }
}
