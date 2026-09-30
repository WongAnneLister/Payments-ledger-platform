package com.paymentsledger.webhook;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

class WebhookNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void keepsCorrelationButDropsCustomerDetailsAndExpandedObjectContents() throws Exception {
        var safe = WebhookNormalizer.sanitize(mapper.readTree("""
          {"id":"ch_test","object":"charge","amount":100,"captured":true,
           "payment_intent":{"id":"pi_test","client_secret":"secret_nested","customer":{"email":"nested@example.com"}},
           "billing_details":{"email":"customer@example.com"},"client_secret":"secret_top",
           "metadata":{"payment_id":"00000000-0000-0000-0000-000000000008","email":"metadata@example.com"}}
          """), mapper);
        assertThat(safe.path("payment_intent").asText()).isEqualTo("pi_test");
        assertThat(safe.path("metadata").size()).isEqualTo(1);
        assertThat(safe.path("metadata").path("payment_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000008");
        assertThat(safe.toString()).doesNotContain("secret", "@", "billing_details", "customer");
        var publicId = WebhookNormalizer.sanitize(mapper.readTree("{\"metadata\":{\"payment_id\":\"pay_01ARZ3NDEKTSV4RRFFQ69G5FAV\"}}"), mapper);
        assertThat(publicId.path("metadata").path("payment_id").asText()).isEqualTo("pay_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    }

    @Test void malformedTypesCannotBecomeFinancialFacts() throws Exception {
        var safe = WebhookNormalizer.sanitize(mapper.readTree("""
          {"amount":"100","amount_captured":1.5,"amount_received":9223372036854775808,
           "captured":"true","paid":1,"charge":{"id":"ch_valid","amount":999},
           "payment_intent":"pi_invalid@example.com","metadata":{"payment_id":"customer@example.com"}}
          """), mapper);
        assertThat(safe.size()).isEqualTo(1);
        assertThat(safe.path("charge").asText()).isEqualTo("ch_valid");
        assertThatThrownBy(() -> WebhookNormalizer.sanitize(mapper.createArrayNode(), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WebhookNormalizer.sanitize(mapper.createObjectNode().put("status", "x".repeat(257)), mapper).size()).isZero();
    }

    @Test void semanticIdentityIgnoresSerializationOrderButRetainsChangedFacts() throws Exception {
        var first = mapper.readTree("{\"object\":\"charge\",\"id\":\"ch_same\",\"captured\":false,\"amount\":100}");
        var reordered = mapper.readTree("{\"amount\":100,\"captured\":false,\"id\":\"ch_same\",\"object\":\"charge\"}");
        assertThat(WebhookNormalizer.semanticKey(first)).isEqualTo(WebhookNormalizer.semanticKey(reordered));
        var changed = first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("captured", true);
        assertThat(WebhookNormalizer.semanticKey(first)).isNotEqualTo(WebhookNormalizer.semanticKey(changed));
        assertThat(WebhookNormalizer.digest(first.toString().getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(WebhookNormalizer.digest(reordered.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void disputeProjectionKeepsDeadlineAndOnlyBtIdentities() throws Exception {
        var safe=WebhookNormalizer.sanitize(mapper.readTree("""
         {"id":"du_test","object":"dispute","charge":{"id":"ch_test"},"status":"needs_response",
          "evidence_details":{"due_by":1800000000,"submission_count":1},
          "evidence":{"customer_email_address":"private@example.com"},
          "balance_transactions":[{"id":"txn_one","amount":-3000,"secret":"hidden"},"txn_two"]}
         """),mapper);
        assertThat(safe.path("evidence_details").path("due_by").asLong()).isEqualTo(1800000000);
        assertThat(safe.path("balance_transactions").toString()).isEqualTo("[\"txn_one\",\"txn_two\"]");
        assertThat(safe.toString()).doesNotContain("private@","hidden","submission_count","-3000");
    }

}
