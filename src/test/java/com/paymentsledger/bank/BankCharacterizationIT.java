package com.paymentsledger.bank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.provider.ProviderModels.PayoutFact;
import com.paymentsledger.provider.ProviderScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the observable behavior of the bank module before it is split into domain, repository and service layers:
 * every rejection branch with its exact message and no financial side effects, the return-overlap conflict branch,
 * and the canonical statement form whose digest is already stored for idempotent reimport.
 */
class BankCharacterizationIT extends PayoutBankFixture {

    private void rejects(Runnable action, String message) {
        long journals = count("journal"), matches = count("payout_bank_match");
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(count("payout_bank_match")).isEqualTo(matches);
    }

    private ObjectNode statementSpanning(String fixture, String line, long bookedAfterSeconds, String ref) {
        var s = (ObjectNode) statement(fixture, line, "CREDIT", 5700, 0, ref);
        s.put("coverageUntil", from.plusSeconds(30L * 86400).toString());
        ((ObjectNode) s.path("lines").get(0))
                .put("bookedAt", from.plusSeconds(bookedAfterSeconds).toString());
        return s;
    }

    private UUID outgoingWithoutArrival(String po) {
        UUID local = payouts.accept(
                scope, new PayoutFact(po, "paid", 5700, "usd", "bt_" + po, null, false, null, null), evidence());
        ingest(bt("bt_" + po, po, -5700, 0));
        assertThat(replay.replay(scope, "bt_" + po)).isEqualTo("POSTED");
        return local;
    }

    @Test
    void matchRejectsInvalidRequestsBeforeTouchingMoney() {
        UUID po = outgoing("po_reject");
        UUID line = line(statement("a", "line-a", "CREDIT", 5700, 0, "po_reject"));
        rejects(() -> matcher.match(scope, po, line, 0), "Positive principal allocation required");
        rejects(
                () -> matcher.match(new ProviderScope(id(1), id(2), id(4), true), po, line, 5700),
                "Test provider scope required");
        rejects(() -> matcher.match(scope, UUID.randomUUID(), line, 5700), "Payout outside scope");
        rejects(() -> matcher.match(scope, po, UUID.randomUUID(), 5700), "Bank line scope or currency mismatch");
    }

    @Test
    void matchRejectsLinesThatDoNotBelongToThePayout() {
        UUID po = outgoing("po_lines");
        var yen = statement("yen", "line-yen", "CREDIT", 5700, 0, "po_lines");
        ((ObjectNode) yen).put("currency", "JPY");
        UUID yenLine = line(yen);
        rejects(() -> matcher.match(scope, po, yenLine, 5700), "Bank line scope or currency mismatch");
        UUID other = line(statement("other", "line-other", "CREDIT", 5700, 0, "po_someone_else"));
        rejects(() -> matcher.match(scope, po, other, 5700), "Payout reference mismatch");
        UUID late = line(statementSpanning("late", "line-late", 9L * 86400, "po_lines"));
        rejects(() -> matcher.match(scope, po, late, 5700), "Bank booking outside +/-7 UTC dates");
    }

    @Test
    void matchRequiresArrivalEvidence() {
        UUID po = outgoingWithoutArrival("po_no_arrival");
        assertThat(matcher.evidenceState(scope, po)).isEqualTo("WAITING_EVIDENCE");
        UUID line = line(statement("a", "line-a", "CREDIT", 5700, 0, "po_no_arrival"));
        rejects(() -> matcher.match(scope, po, line, 5700), "Arrival date evidence required");
    }

    @Test
    void matchEnforcesStableIdentityAndCapacity() {
        UUID a = outgoing("po_cap_a"), b = outgoing("po_cap_b");
        UUID shared = line(statement("shared", "line-shared", "CREDIT", 5700, 0, "po_cap_a", "po_cap_b"));
        var first = matcher.match(scope, a, shared, 5700);
        assertThat(first.state()).isEqualTo("MATCHED");
        assertThat(matcher.match(scope, a, shared, 5700)).isEqualTo(first);
        rejects(() -> matcher.match(scope, a, shared, 5699), "Stable match identity conflicts with retry amount");
        rejects(() -> matcher.match(scope, b, shared, 100), "Bank line capacity exceeded");

        UUID c = outgoing("po_cap_c");
        UUID wide = line(statement("wide", "line-wide", "CREDIT", 6000, 0, "po_cap_c"));
        rejects(() -> matcher.match(scope, c, wide, 5800), "Payout unresolved or principal capacity exceeded");
    }

    @Test
    void clawbackRequiresVerifiedOverlap() {
        UUID po = outgoing("po_clawback");
        UUID returned = line(statement("ret", "line-ret", "RETURN", 5700, 0, "po_clawback"));
        rejects(
                () -> matcher.match(scope, po, returned, 5700),
                "Verified overlap and prior net bank principal required for clawback");
    }

    @Test
    void bankReceiptAfterPspReturnRecordsConflictWithoutPosting() {
        UUID po = outgoing("po_overlap_first");
        accept("po_overlap_first", "failed", "bt_po_overlap_first", "bt_return_first");
        ingest(bt("bt_return_first", "po_overlap_first", 5700, 0));
        assertThat(replay.replay(scope, "bt_return_first")).isEqualTo("POSTED");
        long journals = count("journal");
        UUID credit = line(statement("late-credit", "line-late-credit", "CREDIT", 5700, 0, "po_overlap_first"));

        var conflict = matcher.match(scope, po, credit, 5700);
        assertThat(conflict).isEqualTo(new BankMatcher.MatchResult("CONFLICT", null));
        assertThat(matcher.match(scope, po, credit, 5700)).isEqualTo(conflict);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.payout_case WHERE payout_id=? AND"
                                + " reason='BANK_RECEIPT_RETURN_OVERLAP'",
                        Long.class,
                        po))
                .isEqualTo(1);
        assertThat(payouts.lookup(scope, "po_overlap_first").get("bank_match_state"))
                .isEqualTo("CONFLICT");
        assertThat(matcher.evidenceState(scope, po)).isEqualTo("CONFLICT");
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(count("payout_bank_match")).isZero();
    }

    @Test
    void importRejectsInvalidFixturesWithStableMessages() {
        var version = (ObjectNode) statement("v", "line-v", "CREDIT", 5700, 0, "po_x");
        version.put("schemaVersion", 2);
        rejects(() -> banks.importStatement(scope, version), "Simulation schemaVersion 1 required");

        String invalid = "Invalid fixed simulated bank fixture schema";
        var extra = (ObjectNode) statement("e", "line-e", "CREDIT", 5700, 0, "po_x");
        extra.put("unexpected", true);
        rejects(() -> banks.importStatement(scope, extra), invalid);
        var fee = statement("f", "line-f", "CREDIT", 100, 101, "po_x");
        rejects(() -> banks.importStatement(scope, fee), invalid);
        var returnFee = statement("r", "line-r", "RETURN", 100, 1, "po_x");
        rejects(() -> banks.importStatement(scope, returnFee), invalid);
        var outside = statementSpanning("o", "line-o", 1, "po_x");
        outside.put("coverageUntil", from.plusSeconds(1).toString());
        rejects(() -> banks.importStatement(scope, outside), invalid);
        var badRef = statement("b", "line-b", "CREDIT", 100, 0, "po-with-dash");
        rejects(() -> banks.importStatement(scope, badRef), invalid);

        var duplicate = (ObjectNode) statement("d", "line-d", "CREDIT", 100, 0, "po_x");
        duplicate.withArray("lines").add(duplicate.path("lines").get(0).deepCopy());
        rejects(() -> banks.importStatement(scope, duplicate), "Duplicate line ID within fixture");

        var euro = (ObjectNode) statement("eur", "line-eur", "CREDIT", 100, 0, "po_x");
        euro.put("currency", "EUR");
        rejects(() -> banks.importStatement(scope, euro), "Trusted enabled test scope required");
        assertThat(count("bank_statement")).isZero();
    }

    @Test
    void importIsIdempotentOnCanonicalContentAndRejectsChangedOrStolenIdentities() {
        var original = (ObjectNode) statement("canon", "line-1", "CREDIT", 5700, 0, "po_b", "po_a");
        var second = original.withArray("lines").addObject();
        second.put("lineId", "line-0")
                .put("kind", "RETURN")
                .put("bookedAt", from.plusSeconds(300).toString())
                .put("principalMinor", 10)
                .put("feeMinor", 0);
        second.putArray("payoutReferences").add("po_c");
        UUID id = banks.importStatement(scope, original);

        // Same evidence, different key order, line order, reference order and currency case.
        var reordered = json.createObjectNode();
        reordered.put("lines", (String) null);
        reordered.setAll((ObjectNode) original.deepCopy());
        reordered.put("currency", "usd");
        var lines = reordered.putArray("lines");
        lines.add(second.deepCopy());
        var first = ((ObjectNode) original.path("lines").get(0)).deepCopy();
        first.putArray("payoutReferences").add("po_a").add("po_b");
        lines.add(first);
        assertThat(banks.importStatement(scope, reordered)).isEqualTo(id);

        var changed = original.deepCopy();
        ((ObjectNode) changed.path("lines").get(0)).put("principalMinor", 5701);
        rejects(() -> banks.importStatement(scope, changed), "Fixture identity has different immutable evidence");

        var stolen = (ObjectNode) statement("thief", "line-1", "CREDIT", 5700, 0, "po_a");
        rejects(() -> banks.importStatement(scope, stolen), "Bank source line already exists in another fixture");
        assertThat(count("bank_statement")).isEqualTo(1);
        assertThat(count("bank_statement_line")).isEqualTo(2);
    }

    @Test
    void canonicalStatementBytesAndDigestArePinned() throws Exception {
        UUID id = banks.importStatement(scope, statement("pin", "line-pin", "CREDIT", 5700, 0, "po_b", "po_a"));
        String canonical = "{\"bankAccountRef\":\"sim-bank-1\",\"complete\":true,"
                + "\"coverageFrom\":\"2026-03-01T00:00:00Z\",\"coverageUntil\":\"2026-03-02T00:00:00Z\","
                + "\"currency\":\"USD\",\"evidenceType\":\"SIMULATED_BANK_EVIDENCE\",\"fixtureId\":\"pin\","
                + "\"lines\":[{\"bookedAt\":\"2026-03-01T00:03:20Z\",\"feeMinor\":0,\"kind\":\"CREDIT\","
                + "\"lineId\":\"line-pin\",\"payoutReferences\":[\"po_a\",\"po_b\"],\"principalMinor\":5700}],"
                + "\"schemaVersion\":1}";
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT digest FROM platform.bank_statement WHERE id=?", String.class, id))
                .isEqualTo(expected);
        JsonNode stored = json.readTree(
                jdbc.queryForObject("SELECT payload::text FROM platform.bank_statement WHERE id=?", String.class, id));
        assertThat(stored).isEqualTo(json.readTree(canonical));
        assertThat(jdbc.queryForObject(
                        "SELECT array_to_string(payout_references,',') FROM platform.bank_statement_line"
                                + " WHERE statement_id=?",
                        String.class,
                        id))
                .isEqualTo("po_a,po_b");
    }
}
