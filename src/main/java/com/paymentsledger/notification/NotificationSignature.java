package com.paymentsledger.notification;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** V1 signs the exact stored payload, with each identity and the attempt timestamp. */
public final class NotificationSignature {
    private NotificationSignature() {}

    public static String sign(byte[] body, UUID id, String key, long timestamp, String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("KEY_UNAVAILABLE");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "." + id + "." + key + ".").getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SIGNING_UNAVAILABLE");
        }
    }

    public static boolean verify(
            byte[] body, UUID id, String key, String timestamp, String signature, String secret, Clock clock) {
        if (timestamp == null
                || !timestamp.matches("[0-9]{1,12}")
                || signature == null
                || !signature.matches("v1=[a-f0-9]{64}")) return false;
        try {
            long supplied = Long.parseLong(timestamp), now = clock.instant().getEpochSecond();
            if (supplied < now - 300 || supplied > now + 300) return false;
            return MessageDigest.isEqual(
                    sign(body, id, key, supplied, secret).getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
