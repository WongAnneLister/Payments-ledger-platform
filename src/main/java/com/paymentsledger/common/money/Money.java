package com.paymentsledger.common.money;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.math.BigDecimal;

/** Exact configuration conversion; no floating point, rounding or currency inference. */
public final class Money {
    private Money() {}

    public static long toMinor(String amount, String currency, int exponent) {
        if (currency == null
                || !currency.matches("[A-Z]{3}")
                || exponent < 0
                || exponent > 4
                || (currency.equals("USD") && exponent != 2)
                || (currency.equals("JPY") && exponent != 0)) throw invalid();
        try {
            long minor = new BigDecimal(amount).movePointRight(exponent).longValueExact();
            if (minor <= 0) throw invalid();
            return minor;
        } catch (NumberFormatException | ArithmeticException | NullPointerException ex) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid positive minor-unit amount or currency exponent");
    }
}
