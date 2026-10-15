package com.paymentsledger.common.request;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared finite identifier guard. Secret-like tokens and card-like digit runs are never echoed. */
public final class SafeCorrelationId {
    private static final Pattern CHARACTERS = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CARD_DIGITS = Pattern.compile("[0-9]{13,}");
    private static final Pattern KEY_PREFIX = Pattern.compile("(?:sk|pk|rk)_(?:test|live)_");

    private SafeCorrelationId() {}
    /** Typed local IDs come from validated safe DTOs, never from arbitrary inbound headers/MDC. */
    public static boolean acceptsAssociation(String field, String value) {
        String prefix = switch (field) {
            case "paymentId" -> "pay";
            case "businessOrderId" -> "bor";
            default -> null;
        };
        if (prefix != null && value != null && value.matches(prefix + "_[0-7][0-9A-HJKMNP-TV-Z]{25}")) return true;
        return accepts(value, 128);
    }

    public static boolean accepts(String value, int maximumLength) {
        if (value == null
                || value.isEmpty()
                || value.length() > maximumLength
                || value.equals("-")
                || !CHARACTERS.matcher(value).matches()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !KEY_PREFIX.matcher(lower).find()
                && !CARD_DIGITS.matcher(value).find()
                && !lower.contains("secret")
                && !lower.contains("whsec_")
                && !lower.contains("bank_token")
                && !lower.contains("banktoken")
                && !lower.startsWith("tok_")
                && !lower.startsWith("pm_");
    }
}
