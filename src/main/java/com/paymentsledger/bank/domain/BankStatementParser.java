package com.paymentsledger.bank.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validates the fixed simulated bank statement schema (version 1) and produces its canonical form.
 *
 * <p>Canonical form: object keys sorted, currency upper-cased, instants re-rendered by {@link Instant#toString()},
 * lines sorted by line ID and payout references sorted. The SHA-256 of that text is the stored digest, so this
 * output must stay byte-for-byte stable for existing statements to reimport idempotently.
 */
public final class BankStatementParser {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "evidenceType",
            "fixtureId",
            "bankAccountRef",
            "currency",
            "coverageFrom",
            "coverageUntil",
            "complete",
            "lines");
    private static final Set<String> LINE_FIELDS =
            Set.of("lineId", "kind", "bookedAt", "principalMinor", "feeMinor", "payoutReferences");
    private static final int MAX_LINES = 1000;
    private static final int MAX_REFERENCES = 100;

    private BankStatementParser() {}

    public static BankStatement parse(JsonNode input) {
        exactFields(input, FIELDS);
        if (!input.path("schemaVersion").isInt()
                || input.path("schemaVersion").intValue() != 1
                || !"SIMULATED_BANK_EVIDENCE".equals(text(input, "evidenceType")))
            throw new IllegalArgumentException("Simulation schemaVersion 1 required");
        String fixtureId = identifier(input, "fixtureId");
        String bankAccountRef = identifier(input, "bankAccountRef");
        String currency = text(input, "currency");
        if (currency == null || !currency.matches("[A-Za-z]{3}")) throw invalid();
        currency = currency.toUpperCase(Locale.ROOT);
        Instant from = time(input, "coverageFrom"), until = time(input, "coverageUntil");
        if (!from.isBefore(until)
                || !input.path("complete").isBoolean()
                || !input.path("lines").isArray()
                || input.path("lines").size() > MAX_LINES) throw invalid();

        var canonicalLines = new TreeMap<String, JsonNode>();
        var lines = new TreeMap<String, BankStatementLine>();
        for (var raw : input.path("lines")) {
            BankStatementLine line = line(raw, from, until);
            var canonicalLine = (ObjectNode) canonical(raw);
            canonicalLine.put("bookedAt", line.bookedAt().toString());
            ArrayNode references = canonicalLine.putArray("payoutReferences");
            line.payoutReferences().forEach(references::add);
            if (canonicalLines.put(line.lineId(), canonicalLine) != null)
                throw new IllegalArgumentException("Duplicate line ID within fixture");
            lines.put(line.lineId(), line);
        }

        var statement = (ObjectNode) canonical(input);
        statement.put("currency", currency).put("coverageFrom", from.toString()).put("coverageUntil", until.toString());
        ArrayNode lineArray = statement.putArray("lines");
        canonicalLines.values().forEach(lineArray::add);
        String canonicalJson = canonical(statement).toString();
        return new BankStatement(
                fixtureId,
                bankAccountRef,
                currency,
                from,
                until,
                input.path("complete").booleanValue(),
                new ArrayList<>(lines.values()),
                canonicalJson,
                sha256(canonicalJson));
    }

    private static BankStatementLine line(JsonNode raw, Instant from, Instant until) {
        exactFields(raw, LINE_FIELDS);
        String id = identifier(raw, "lineId"), kind = text(raw, "kind");
        if (!Set.of("CREDIT", "RETURN").contains(Objects.toString(kind, ""))) throw invalid();
        Instant booked = time(raw, "bookedAt");
        if (booked.isBefore(from) || !booked.isBefore(until)) throw invalid();
        long principal = number(raw, "principalMinor"), fee = number(raw, "feeMinor");
        if (principal <= 0 || fee < 0 || fee > principal || kind.equals("RETURN") && fee != 0) throw invalid();
        JsonNode rawReferences = raw.path("payoutReferences");
        if (!rawReferences.isArray() || rawReferences.isEmpty() || rawReferences.size() > MAX_REFERENCES)
            throw invalid();
        var references = new TreeSet<String>();
        for (var ref : rawReferences) {
            if (!ref.isTextual() || !ref.textValue().matches("[A-Za-z0-9_]{1,255}") || !references.add(ref.textValue()))
                throw invalid();
        }
        return new BankStatementLine(id, BankLineKind.valueOf(kind), booked, principal, fee, List.copyOf(references));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var out = NODES.objectNode();
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) out.set(key, canonical(node.get(key)));
            return out;
        }
        if (node.isArray()) {
            var out = NODES.arrayNode();
            node.forEach(value -> out.add(canonical(value)));
            return out;
        }
        return node.deepCopy();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        var found = new HashSet<String>();
        node.fieldNames().forEachRemaining(found::add);
        if (!found.equals(expected)) throw invalid();
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).textValue() : null;
    }

    private static String identifier(JsonNode node, String key) {
        String value = text(node, key);
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,255}")) throw invalid();
        return value;
    }

    private static long number(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private static Instant time(JsonNode node, String key) {
        try {
            return Instant.parse(Objects.requireNonNull(text(node, key)));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid fixed simulated bank fixture schema");
    }
}
