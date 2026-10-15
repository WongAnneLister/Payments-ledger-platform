package com.paymentsledger.payment;

import com.paymentsledger.common.error.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/** ULID wire representation, stored as the same 128 bits in PostgreSQL uuid columns. */
public final class PaymentIds {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PaymentIds() {}

    public static UUID newId() {
        return new UUID((System.currentTimeMillis() << 16) | (RANDOM.nextLong() & 0xffffL), RANDOM.nextLong());
    }

    public static String format(String prefix, UUID id) {
        if (!prefix.matches("[a-z]+")) throw new IllegalArgumentException("Invalid resource prefix");
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        for (int i = 25; i >= 0; i--) {
            encoded[i] = ALPHABET.charAt(value.and(BigInteger.valueOf(31)).intValue());
            value = value.shiftRight(5);
        }
        return prefix + "_" + new String(encoded);
    }

    public static UUID payment(String external) {
        return parse("pay", external);
    }

    public static UUID parse(String prefix, String external) {
        if (external == null || !external.startsWith(prefix + "_") || external.length() != prefix.length() + 27)
            throw invalid();
        String encoded = external.substring(prefix.length() + 1);
        if (encoded.charAt(0) < '0' || encoded.charAt(0) > '7') throw invalid();
        BigInteger value = BigInteger.ZERO;
        for (char digit : encoded.toCharArray()) {
            int n = ALPHABET.indexOf(digit);
            if (n < 0) throw invalid();
            value = value.shiftLeft(5).or(BigInteger.valueOf(n));
        }
        return new UUID(value.shiftRight(64).longValue(), value.longValue());
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid payment resource ID");
    }
}
