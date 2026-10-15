package com.paymentsledger.financialingestion;

import java.time.*;

public final class ImportWindows {
    private ImportWindows() {}

    public record Window(Instant from, Instant until) {
        public Window {
            if (from == null || until == null || !from.isBefore(until) || from.getNano() != 0 || until.getNano() != 0)
                throw new IllegalArgumentException("Whole-second increasing UTC window required");
        }
    }
    /** Seven completed business dates, using local midnights across DST. */
    public static Window overlap(LocalDate businessDate, ZoneId zone) {
        return new Window(
                businessDate.minusDays(7).atStartOfDay(zone).toInstant(),
                businessDate.atStartOfDay(zone).toInstant());
    }
}
