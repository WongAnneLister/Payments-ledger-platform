package com.paymentsledger.reconciliation;

import java.time.*;

/** Half-open provider windows are local-midnight boundaries, including DST transitions. */
public final class ReconciliationWindows {
    private ReconciliationWindows() {}

    public record Window(Instant from, Instant until) {
        public Window {
            if (from == null || until == null || !from.isBefore(until))
                throw new IllegalArgumentException("A nonempty half-open UTC interval is required");
        }
    }

    public static Window day(LocalDate date, ZoneId zone) {
        return new Window(
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
