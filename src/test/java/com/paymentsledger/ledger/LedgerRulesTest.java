package com.paymentsledger.ledger;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerRulesTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @Test void acceptsBalancedPositiveMinorUnitsAndCopiesFacts() throws Exception {
        var facts = new ObjectMapper().readTree("{\"fee\":300}");
        var command = new PostingCommand("fixture:charge", "L-CHARGE", "PROVIDER", "fixture_charge",
                facts, List.of(new PostingLine(a, Side.DEBIT, 10000), new PostingLine(b, Side.CREDIT, 10000)), null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) facts).put("fee", 999);
        assertThat(command.facts().get("fee").asInt()).isEqualTo(300);
        assertThatThrownBy(() -> command.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsEmptySingleUnbalancedZeroNegativeAndOverflowingJournals() {
        for (var lines : List.of(
                List.<PostingLine>of(),
                List.of(new PostingLine(a, Side.DEBIT, 1)),
                List.of(new PostingLine(a, Side.DEBIT, 2), new PostingLine(b, Side.CREDIT, 1)),
                List.of(new PostingLine(a, Side.DEBIT, Long.MAX_VALUE), new PostingLine(a, Side.DEBIT, 1),
                        new PostingLine(b, Side.CREDIT, Long.MAX_VALUE), new PostingLine(b, Side.CREDIT, 1)))) {
            assertThatThrownBy(() -> new PostingCommand("fixture:key", "L-TEST", "INTERNAL", "fixture",
                    new ObjectMapper().createObjectNode(), lines, null)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> new PostingLine(a, Side.DEBIT, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new PostingLine(a, Side.CREDIT, -1)).isInstanceOf(ApiException.class);
    }

    @Test void scopeMustBeExplicitAndCurrencyWellFormed() {
        assertThatThrownBy(() -> new LedgerScope(null, a, "USD", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LedgerScope(a, b, "usd", null)).isInstanceOf(ApiException.class);
    }
}
