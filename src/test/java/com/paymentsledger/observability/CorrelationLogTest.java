package com.paymentsledger.observability;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationLogTest {
    @Test
    void onlyKnownAllowlistedAssociationsBecomeStructuredJson() throws Exception {
        MDC.put("requestId", "req-fixture");
        try {
            var known = new LinkedHashMap<String, Object>();
            for (String k : List.of(
                    "businessOrderId",
                    "paymentId",
                    "operationId",
                    "providerRequestId",
                    "balanceTransactionId",
                    "financialEventId",
                    "journalId",
                    "runId")) known.put(k, k + "_fixture");
            known.put("reason", "sensitive fixture");
            known.put("rawException", "sensitive exception");
            known.put("metadata", Map.of("token", "hidden"));
            var tree = new ObjectMapper().readTree(CorrelationLog.encode("ops.read", known));
            assertThat(tree.path("event").asText()).isEqualTo("ops.read");
            assertThat(tree.path("requestId").asText()).isEqualTo("req-fixture");
            for (String k : known.keySet())
                if (!Set.of("reason", "rawException", "metadata").contains(k))
                    assertThat(tree.path(k).asText()).isEqualTo(k + "_fixture");
            assertThat(tree.has("reason") || tree.has("rawException") || tree.has("metadata"))
                    .isFalse();
        } finally {
            MDC.clear();
        }
    }

    @Test
    void suppressesSecretLikeCardAndUnboundedAssociationValues() throws Exception {
        for (String value : List.of(
                "sk_test_" + "synthetic",
                "pi_fixture_secret_synthetic",
                "whsec_synthetic",
                "4242".repeat(4),
                "bank_token_fixture",
                "x".repeat(129),
                "newline\nfixture")) {
            var tree = new ObjectMapper().readTree(CorrelationLog.encode("ops.read", Map.of("paymentId", value)));
            assertThat(tree.has("paymentId")).isFalse();
        }
        assertThat(new ObjectMapper()
                        .readTree(CorrelationLog.encode("ops.read", Map.of()))
                        .has("paymentId"))
                .isFalse();
    }

    @Test
    void embeddedCredentialPrefixesAreSuppressedInProviderAssociationsAndRequestMdc() throws Exception {
        for (String key : List.of("sk_test_", "sk_live_", "pk_test_", "pk_live_", "rk_test_", "rk_live_")) {
            String value = "req" + key + "synthetic";
            MDC.put("requestId", value);
            try {
                var tree = new ObjectMapper()
                        .readTree(CorrelationLog.encode("ops.read", Map.of("providerRequestId", value)));
                assertThat(tree.has("requestId")).isFalse();
                assertThat(tree.has("providerRequestId")).isFalse();
            } finally {
                MDC.clear();
            }
        }
    }

    @Test
    void validCanonicalUuidAndKnownLocalTypedIdsAreRetainedDespiteZeroHeavyEncoding() throws Exception {
        UUID uuid = new UUID(16, 2701);
        String payment = com.paymentsledger.payment.PaymentIds.format("pay", uuid),
                order = com.paymentsledger.payment.PaymentIds.format("bor", uuid);
        var tree = new ObjectMapper()
                .readTree(CorrelationLog.encode(
                        "ops.read", Map.of("paymentId", payment, "businessOrderId", order, "operationId", uuid)));
        assertThat(tree.path("paymentId").asText()).isEqualTo(payment);
        assertThat(tree.path("businessOrderId").asText()).isEqualTo(order);
        assertThat(tree.path("operationId").asText()).isEqualTo(uuid.toString());
        assertThat(new ObjectMapper()
                        .readTree(CorrelationLog.encode("ops.read", Map.of("paymentId", "4242".repeat(4))))
                        .has("paymentId"))
                .isFalse();
    }

    @Test
    void unsafeInheritedMdcAndMessageAssociationsAreSuppressedAtEmission() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CorrelationLog.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        String sensitive = "reqsk_test_" + "synthetic";
        MDC.put("requestId", sensitive);
        MDC.put("paymentId", sensitive);
        MDC.put("rawBody", sensitive);
        try {
            CorrelationLog.read(
                    "ops.read", new ObjectMapper().createObjectNode().put("paymentId", sensitive));
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getMDCPropertyMap().containsValue(sensitive))
                    .isFalse();
            assertThat(events.list.getFirst().getFormattedMessage().contains(sensitive))
                    .isFalse();
            assertThat(events.list.getFirst().getMDCPropertyMap().containsKey("rawBody"))
                    .isFalse();
            assertThat(MDC.get("rawBody").equals(sensitive)).isTrue();
            assertThat(MDC.get("requestId").equals(sensitive)).isTrue();
        } finally {
            logger.detachAppender(events);
            events.stop();
            MDC.clear();
        }
    }
}
