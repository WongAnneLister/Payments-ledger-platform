package com.paymentsledger.dispute;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DisputeAmountsTest {
    @Test
    void returnUsesReceivableThenConfirmedLossThenSuspense() {
        assertThat(DisputeAmounts.allocateReturn(3500, 1000, 2000))
                .isEqualTo(new DisputeAmounts.ReturnAllocation(1000, 2000, 500));
    }

    @Test
    void largeReturnDoesNotOverflowWhenOutstandingBalancesHaveLargeSum() {
        assertThat(DisputeAmounts.allocateReturn(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE))
                .isEqualTo(new DisputeAmounts.ReturnAllocation(Long.MAX_VALUE - 1, 1, 0));
    }

    @Test
    void negativeBalanceCannotBecomeAValidReturnAllocation() {
        assertThatThrownBy(() -> DisputeAmounts.allocateReturn(100, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
