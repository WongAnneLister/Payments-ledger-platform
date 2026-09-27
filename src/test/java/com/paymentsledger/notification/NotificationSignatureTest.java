package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationSignatureTest {
    static final String SECRET="fixture-only-32-byte-secret-not-for-production";
    private final UUID id=UUID.fromString("ec9d8e3d-312b-44bd-8dcb-21df20408314");
    private final byte[] body="{\"amountMinor\":100}".getBytes(StandardCharsets.UTF_8);
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"),ZoneOffset.UTC);
    @Test void bindsExactBytesEventBusinessIdentityAndRecentTimestamp() {
        long now=clock.instant().getEpochSecond();
        String signature=NotificationSignature.sign(body,id,"o:e:a:order:payment.succeeded",now,SECRET);
        assertThat(signature).matches("v1=[a-f0-9]{64}");
        assertThat(NotificationSignature.verify(body,id,"o:e:a:order:payment.succeeded",Long.toString(now),signature,SECRET,clock)).isTrue();
        assertThat(NotificationSignature.verify("{}".getBytes(),id,"o:e:a:order:payment.succeeded",Long.toString(now),signature,SECRET,clock)).isFalse();
        assertThat(NotificationSignature.verify(body,UUID.randomUUID(),"o:e:a:order:payment.succeeded",Long.toString(now),signature,SECRET,clock)).isFalse();
        assertThat(NotificationSignature.verify(body,id,"different",Long.toString(now),signature,SECRET,clock)).isFalse();
        for(long timestamp:new long[]{now-301,now+301,Long.MIN_VALUE,Long.MAX_VALUE}) {
            assertThat(NotificationSignature.verify(body,id,"key",Long.toString(timestamp),NotificationSignature.sign(body,id,"key",timestamp,SECRET),SECRET,clock)).isFalse();
        }
        assertThat(NotificationSignature.verify(body,id,"key","invalid",signature,SECRET,clock)).isFalse();
    }
}
