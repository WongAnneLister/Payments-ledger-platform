package com.paymentsledger.reconciliation;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class ReconciliationWindowsTest {
    @Test
    void localMidnightsProduce23And25HourWindows() throws Exception {
        Class<?> windows = require("com.paymentsledger.reconciliation.ReconciliationWindows");
        for (var fixture : new Object[][] {{LocalDate.of(2026, 3, 8), 23L}, {LocalDate.of(2026, 11, 1), 25L}}) {
            Object window = windows.getMethod("day", LocalDate.class, ZoneId.class)
                    .invoke(null, fixture[0], ZoneId.of("America/New_York"));
            Instant from = (Instant) window.getClass().getMethod("from").invoke(window);
            Instant until = (Instant) window.getClass().getMethod("until").invoke(window);
            assertThat(Duration.between(from, until).toHours()).isEqualTo(fixture[1]);
        }
    }

    static Class<?> require(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Missing reconciliation implementation: " + name);
        }
    }
}
