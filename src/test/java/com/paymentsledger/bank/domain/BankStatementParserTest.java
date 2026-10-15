package com.paymentsledger.bank.domain;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankStatementParserTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    static final String INVALID = "Invalid fixed simulated bank fixture schema";

    static ObjectNode statement(String fixture, String lineId, String kind, long principal, long fee, String... refs) {
        var s = JSON.createObjectNode();
        s.put("schemaVersion", 1)
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("fixtureId", fixture)
                .put("bankAccountRef", "sim-bank-1")
                .put("currency", "USD")
                .put("coverageFrom", FROM.toString())
                .put("coverageUntil", FROM.plusSeconds(86400).toString())
                .put("complete", true);
        var line = s.putArray("lines").addObject();
        line.put("lineId", lineId)
                .put("kind", kind)
                .put("bookedAt", FROM.plusSeconds(200).toString())
                .put("principalMinor", principal)
                .put("feeMinor", fee);
        var references = line.putArray("payoutReferences");
        for (String ref : refs) references.add(ref);
        return s;
    }

    @Test
    void canonicalBytesAndDigestMatchTheStoredFormat() throws Exception {
        var parsed = BankStatementParser.parse(statement("pin", "line-pin", "CREDIT", 5700, 0, "po_b", "po_a"));
        String canonical = "{\"bankAccountRef\":\"sim-bank-1\",\"complete\":true,"
                + "\"coverageFrom\":\"2026-03-01T00:00:00Z\",\"coverageUntil\":\"2026-03-02T00:00:00Z\","
                + "\"currency\":\"USD\",\"evidenceType\":\"SIMULATED_BANK_EVIDENCE\",\"fixtureId\":\"pin\","
                + "\"lines\":[{\"bookedAt\":\"2026-03-01T00:03:20Z\",\"feeMinor\":0,\"kind\":\"CREDIT\","
                + "\"lineId\":\"line-pin\",\"payoutReferences\":[\"po_a\",\"po_b\"],\"principalMinor\":5700}],"
                + "\"schemaVersion\":1}";
        assertThat(parsed.canonicalJson()).isEqualTo(canonical);
        assertThat(parsed.digest())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        assertThat(parsed.lines())
                .containsExactly(new BankStatementLine(
                        "line-pin", BankLineKind.CREDIT, FROM.plusSeconds(200), 5700, 0, List.of("po_a", "po_b")));
    }

    @Test
    void keyLineReferenceOrderAndCurrencyCaseDoNotChangeTheDigest() {
        var original = statement("canon", "line-1", "CREDIT", 5700, 0, "po_b", "po_a");
        var second = original.withArray("lines").addObject();
        second.put("lineId", "line-0")
                .put("kind", "RETURN")
                .put("bookedAt", FROM.plusSeconds(300).toString())
                .put("principalMinor", 10)
                .put("feeMinor", 0);
        second.putArray("payoutReferences").add("po_c");

        var reordered = JSON.createObjectNode();
        reordered.put("lines", (String) null);
        reordered.setAll(original.deepCopy());
        reordered.put("currency", "usd");
        var lines = reordered.putArray("lines");
        lines.add(second.deepCopy());
        var first = ((ObjectNode) original.path("lines").get(0)).deepCopy();
        first.putArray("payoutReferences").add("po_a").add("po_b");
        lines.add(first);

        var a = BankStatementParser.parse(original);
        var b = BankStatementParser.parse(reordered);
        assertThat(b.digest()).isEqualTo(a.digest());
        assertThat(b.currency()).isEqualTo("USD");
        assertThat(a.lines()).extracting(BankStatementLine::lineId).containsExactly("line-0", "line-1");
    }

    @Test
    void rejectsInvalidStatementsWithStableMessages() {
        var version = statement("v", "l", "CREDIT", 1, 0, "po");
        version.put("schemaVersion", 2);
        assertRejected(version, "Simulation schemaVersion 1 required");
        var evidence = statement("v", "l", "CREDIT", 1, 0, "po");
        evidence.put("evidenceType", "REAL_BANK");
        assertRejected(evidence, "Simulation schemaVersion 1 required");

        var extra = statement("e", "l", "CREDIT", 1, 0, "po");
        extra.put("unexpected", true);
        assertRejected(extra, INVALID);
        var currency = statement("c", "l", "CREDIT", 1, 0, "po");
        currency.put("currency", "US");
        assertRejected(currency, INVALID);
        var fixture = statement("bad fixture", "l", "CREDIT", 1, 0, "po");
        assertRejected(fixture, INVALID);
        var coverage = statement("c", "l", "CREDIT", 1, 0, "po");
        coverage.put("coverageUntil", FROM.toString());
        assertRejected(coverage, INVALID);

        assertRejected(statement("f", "l", "CREDIT", 100, 101, "po"), INVALID);
        assertRejected(statement("f", "l", "CREDIT", 0, 0, "po"), INVALID);
        assertRejected(statement("r", "l", "RETURN", 100, 1, "po"), INVALID);
        assertRejected(statement("k", "l", "DEBIT", 100, 0, "po"), INVALID);
        assertRejected(statement("b", "l", "CREDIT", 100, 0, "po-with-dash"), INVALID);
        assertRejected(statement("b", "l", "CREDIT", 100, 0), INVALID);
        assertRejected(statement("b", "l", "CREDIT", 100, 0, "po", "po"), INVALID);
        var booked = statement("o", "l", "CREDIT", 1, 0, "po");
        ((ObjectNode) booked.path("lines").get(0))
                .put("bookedAt", FROM.plusSeconds(86400).toString());
        assertRejected(booked, INVALID);

        var duplicate = statement("d", "l", "CREDIT", 1, 0, "po");
        duplicate.withArray("lines").add(duplicate.path("lines").get(0).deepCopy());
        assertRejected(duplicate, "Duplicate line ID within fixture");
    }

    private static void assertRejected(ObjectNode statement, String message) {
        assertThatThrownBy(() -> BankStatementParser.parse(statement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
