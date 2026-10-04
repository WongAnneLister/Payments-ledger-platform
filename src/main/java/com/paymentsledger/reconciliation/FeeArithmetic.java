package com.paymentsledger.reconciliation;

import java.math.*;

/** Expectations only. Actual signed provider fees remain the accounting facts. */
public final class FeeArithmetic {
    private FeeArithmetic() { }
    public static long expected(long amount, int basisPoints, long flatMinor, String rounding) {
        if (basisPoints < 0 || basisPoints > 10000 || flatMinor < 0)
            throw new IllegalArgumentException("Invalid fee rate");
        if (amount == 0) return 0;
        BigDecimal absolute = new BigDecimal(BigInteger.valueOf(amount).abs());
        BigInteger percentage = absolute.multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.valueOf(rounding)).toBigIntegerExact();
        BigInteger total = percentage.add(BigInteger.valueOf(flatMinor));
        return (amount < 0 ? total.negate() : total).longValueExact();
    }
}
