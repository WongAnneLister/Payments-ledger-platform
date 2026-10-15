package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRequestsTest {
    final ObjectMapper json = new ObjectMapper();

    ObjectNode valid() {
        return json.createObjectNode()
                .put("orderKey", "order-1")
                .put("productRef", "demo")
                .put("priceVersion", 1)
                .put("amountMinor", 100)
                .put("currency", "USD")
                .put("successUrl", "https://merchant.example/success")
                .put("cancelUrl", "https://merchant.example/cancel");
    }

    @Test
    void canonicalDefaultsAndMetadataOrderHaveSameFingerprint() {
        var a = valid();
        a.putObject("metadata").put("b", "2").put("a", "1");
        var b = valid().put("quantity", 1).putNull("customerRef");
        b.putObject("metadata").put("a", "1").put("b", "2");
        assertThat(PaymentRequests.create(a).fingerprint())
                .isEqualTo(PaymentRequests.create(b).fingerprint())
                .matches("[a-f0-9]{64}");
    }

    @Test
    void everyMeaningfulFieldChangesFingerprint() {
        var a = valid();
        String fp = PaymentRequests.create(a).fingerprint();
        for (var b : new ObjectNode[] {
            valid().put("amountMinor", 101),
            valid().put("quantity", 2),
            valid().put("priceVersion", 2),
            valid().put("customerRef", "another"),
            valid().put("successUrl", "https://merchant.example/other"),
            valid().put("orderKey", "other")
        }) assertThat(PaymentRequests.create(b).fingerprint()).isNotEqualTo(fp);
    }

    @Test
    void rejectsUnknownFieldsIncludingAuthorityAndRawCardFields() {
        for (String key : new String[] {"organizationId", "providerAccountId", "livemode", "cardNumber", "unexpected"})
            assertThatThrownBy(() -> PaymentRequests.create(valid().put(key, "anything")))
                    .isInstanceOf(ApiException.class);
    }

    @Test
    void exactIntegersRejectFloatStringsOverflowAndNegativeAmounts() {
        for (var value : new com.fasterxml.jackson.databind.JsonNode[] {
            json.getNodeFactory().numberNode(100.0),
            json.getNodeFactory().textNode("100"),
            json.getNodeFactory().numberNode(new java.math.BigInteger("9223372036854775808")),
            json.getNodeFactory().numberNode(-1)
        }) {
            var request = valid();
            request.set("amountMinor", value);
            assertThatThrownBy(() -> PaymentRequests.create(request)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void validatesQuantityVersionCurrencyAndRequiredFields() {
        for (var bad : new ObjectNode[] {
            valid().put("quantity", 0),
            valid().put("quantity", 10001),
            valid().put("priceVersion", 0),
            valid().put("currency", "usd"),
            valid().put("orderKey", " ")
        }) assertThatThrownBy(() -> PaymentRequests.create(bad)).isInstanceOf(ApiException.class);
        var missing = valid();
        missing.remove("productRef");
        assertThatThrownBy(() -> PaymentRequests.create(missing)).isInstanceOf(ApiException.class);
    }

    @Test
    void returnUrlsRequireHttpsAndNoCredentials() {
        for (String url : new String[] {
            "javascript:alert(1)",
            "http://example.com/",
            "https://user:password@example.com/",
            "https:///relative",
            "/relative"
        })
            assertThatThrownBy(() -> PaymentRequests.create(valid().put("successUrl", url)))
                    .isInstanceOf(ApiException.class);
    }

    @Test
    void reservesInternalMetadataAndRejectsNestedOrSensitiveValues() {
        for (String key : new String[] {
            "payment_id", "business_order_id", "organization_id", "client_secret", "card_number", "pr06_internal"
        }) {
            var request = valid();
            request.putObject("metadata").put(key, "spoofed");
            assertThatThrownBy(() -> PaymentRequests.create(request)).isInstanceOf(ApiException.class);
        }
        var nested = valid();
        nested.putObject("metadata").putObject("anything").put("x", "nested");
        assertThatThrownBy(() -> PaymentRequests.create(nested)).isInstanceOf(ApiException.class);
    }

    @Test
    void cancellationDefaultsAreCanonicalAndTargetIsPartOfFingerprint() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThatThrownBy(() -> PaymentRequests.cancel(json.nullNode())).isInstanceOf(ApiException.class);
        var omitted = PaymentRequests.cancel(null);
        var explicit = PaymentRequests.cancel(json.createObjectNode().put("reason", "OTHER"));
        assertThat(omitted.fingerprint(a)).isEqualTo(explicit.fingerprint(a)).isNotEqualTo(omitted.fingerprint(b));
        assertThatThrownBy(() -> PaymentRequests.cancel(json.createObjectNode().put("reason", "bad")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void idempotencyKeyIsBoundedAndCannotContainControls() {
        assertThat(PaymentRequests.idempotencyKey("abcdefgh")).isEqualTo("abcdefgh");
        for (String key : new String[] {null, "", "short", "a".repeat(256), "abc\ndefgh"})
            assertThatThrownBy(() -> PaymentRequests.idempotencyKey(key)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PaymentRequests.idempotencyKey(null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
    }
}
