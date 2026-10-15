package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Linux Instant.now() carries nanoseconds; PostgreSQL rounds them, so fixture time must stay microsecond aligned. */
class PersistentFakeClockPrecisionTest {
    @TempDir Path directory;
    static final Instant NANO_ORIGIN=Instant.parse("2026-10-12T17:31:58.956947600Z");

    @Test void nanosecondOriginAndAdvancesStayMicrosecondAligned() {
        var fake=new FakeStripeProvider(directory,42,NANO_ORIGIN,Duration.ofDays(1));
        assertThat(fake.clock().instant()).isEqualTo(Instant.parse("2026-10-12T17:31:58.956947Z"));
        assertThat(fake.clock().advance(Duration.ofHours(2).plusNanos(1_999))).isEqualTo(Instant.parse("2026-10-12T19:31:58.956948Z"));
        assertThat(fake.clock().instant().getNano()%1_000).isZero();
    }

    @Test void reopeningWithTheSameNanosecondOriginKeepsPersistedTime() {
        var first=new FakeStripeProvider(directory,42,NANO_ORIGIN,Duration.ofDays(1));
        first.clock().advance(Duration.ofSeconds(31));
        var reopened=new FakeStripeProvider(directory,42,NANO_ORIGIN,Duration.ofDays(1));
        assertThat(reopened.clock().instant()).isEqualTo(Instant.parse("2026-10-12T17:32:29.956947Z")).isEqualTo(reopened.clock().instant().truncatedTo(ChronoUnit.MICROS));
    }
}
