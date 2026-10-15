package com.paymentsledger.revenueshare;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.math.BigInteger;
import java.util.*;

/** Frozen order policy, with cumulative integer ceilings and platform-owned remainder. */
public record SharePolicy(String version, List<Partner> partners) {
    public SharePolicy {
        partners = List.copyOf(partners);
    }

    public record Partner(String ref, int basisPoints) {}

    public static SharePolicy parse(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        JsonNode version = node.get("policy_version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() <= 0)
            throw invalid();
        List<JsonNode> rows = new ArrayList<>();
        if (node.hasNonNull("allocations")) {
            if (!node.get("allocations").isArray() || node.get("allocations").isEmpty()) throw invalid();
            node.get("allocations").forEach(rows::add);
        } else rows.add(node);
        Set<String> refs = new HashSet<>();
        List<Partner> partners = new ArrayList<>();
        long total = 0;
        for (JsonNode row : rows) {
            JsonNode ref = row.get("partner_ref"), rate = row.get("share_basis_points");
            if (ref == null
                    || !ref.isTextual()
                    || ref.textValue().isBlank()
                    || ref.textValue().length() > 128
                    || !refs.add(ref.textValue())
                    || rate == null
                    || !rate.isIntegralNumber()
                    || !rate.canConvertToInt()
                    || rate.intValue() < 0
                    || rate.intValue() > 10000) throw invalid();
            total = Math.addExact(total, rate.intValue());
            if (total > 10000) throw invalid();
            partners.add(new Partner(ref.textValue(), rate.intValue()));
        }
        partners.sort(Comparator.comparing(Partner::ref));
        return new SharePolicy(version.asText(), partners);
    }

    public static long cap(long captured, long refunded, long held, int basisPoints) {
        if (captured < 0 || refunded < 0 || held < 0 || basisPoints < 0 || basisPoints > 10000) throw invalid();
        return BigInteger.valueOf(captured)
                .subtract(BigInteger.valueOf(refunded))
                .subtract(BigInteger.valueOf(held))
                .max(BigInteger.ZERO)
                .multiply(BigInteger.valueOf(basisPoints))
                .divide(BigInteger.valueOf(10000))
                .longValueExact();
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid immutable revenue share policy");
    }
}
