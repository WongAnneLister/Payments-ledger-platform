package com.paymentsledger.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PostingCommand(String eventKey, String eventType, String sourceDomain, String sourceRef,
                             JsonNode facts, List<PostingLine> entries, UUID reversalOf) {
    public PostingCommand {
        if (!validText(eventKey, 512) || !validText(eventType, 128) || !validText(sourceRef, 512)
                || sourceDomain == null || !Set.of("INTERNAL", "PROVIDER", "SIMULATED_BANK").contains(sourceDomain)
                || facts == null || !facts.isObject() || entries == null || entries.size() < 2
                || entries.stream().anyMatch(java.util.Objects::isNull))
            throw invalid();
        facts = facts.deepCopy();
        entries = List.copyOf(entries);
        BigInteger debit = BigInteger.ZERO, credit = BigInteger.ZERO;
        for (PostingLine line : entries) {
            if (line.side() == Side.DEBIT) debit = debit.add(BigInteger.valueOf(line.amount()));
            else credit = credit.add(BigInteger.valueOf(line.amount()));
        }
        if (!debit.equals(credit) || debit.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw invalid();
    }

    @Override public JsonNode facts() { return facts.deepCopy(); }

    private static boolean validText(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static ApiException invalid() { return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid or unbalanced ledger command"); }
}
