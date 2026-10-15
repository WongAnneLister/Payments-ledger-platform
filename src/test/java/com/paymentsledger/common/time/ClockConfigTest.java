package com.paymentsledger.common.time;

import static org.assertj.core.api.Assertions.*;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class ClockConfigTest {
    @Test
    void applicationClockNeverExceedsPostgresMicrosecondPrecision() {
        var clock = new ClockConfig().clock();
        for (int i = 0; i < 1_000; i++) {
            var now = clock.instant();
            assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
        }
    }
}
