package com.paymentsledger.provider.fake;

import java.time.*;
import java.util.Objects;

/** Time is part of the fixture and survives process restart. Never uses wall-clock sleeps. */
public final class PersistentFakeClock extends Clock {
    private final FakeStateStore store;
    private final ZoneId zone;
    PersistentFakeClock(FakeStateStore store) { this(store,ZoneOffset.UTC); }
    private PersistentFakeClock(FakeStateStore store, ZoneId zone) { this.store=store; this.zone=zone; }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new PersistentFakeClock(store,Objects.requireNonNull(zone)); }
    @Override public Instant instant() { return store.update(s -> Instant.parse(s.path("now").asText())); }
    public Instant advance(Duration duration) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException("Clock cannot move backwards");
        return store.update(s -> {
            Instant next = Instant.parse(s.path("now").asText()).plus(duration);
            s.put("now",next.toString()); return next;
        });
    }
}
