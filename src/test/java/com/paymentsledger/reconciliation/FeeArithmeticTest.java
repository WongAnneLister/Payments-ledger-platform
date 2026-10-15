package com.paymentsledger.reconciliation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FeeArithmeticTest {
    @Test
    void exactSignedPercentageAndFlatFeeDoNotOverflowIntermediateLong() throws Exception {
        var method = ReconciliationWindowsTest.require("com.paymentsledger.reconciliation.FeeArithmetic")
                .getMethod("expected", long.class, int.class, long.class, String.class);
        assertThat(method.invoke(null, 1001L, 290, 30L, "HALF_UP")).isEqualTo(59L);
        assertThat(method.invoke(null, -1001L, 290, 30L, "HALF_UP")).isEqualTo(-59L);
        assertThat(method.invoke(null, Long.MAX_VALUE, 10000, 0L, "DOWN")).isEqualTo(Long.MAX_VALUE);
    }
}
