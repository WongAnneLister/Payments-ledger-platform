package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.notification.fixture.SimulatedMerchantServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

class RefundMerchantDeliveryIT extends NotificationFixture {
    @Container
    static final PostgreSQLContainer<?> MERCHANT_DB = new PostgreSQLContainer<>("postgres:16");

    private static final String SECRET = "refund-fixture-only-signing-secret-32bytes";

    @Test
    void refundEffectsSurviveLostResponsesAndBusinessDuplicatesWithoutFulfillingOrders() throws Exception {
        var ds = new DriverManagerDataSource(
                MERCHANT_DB.getJdbcUrl(), MERCHANT_DB.getUsername(), MERCHANT_DB.getPassword());
        try (var merchant =
                new SimulatedMerchantServer(new InetSocketAddress("127.0.0.1", 0), ds, SECRET, fake.clock())) {
            var base = success();
            var event = (ObjectNode) new ObjectMapper().readTree(base.payload());
            UUID refund = UUID.randomUUID(), eventId = UUID.randomUUID();
            String key = base.organizationId() + ":" + base.environmentId() + ":" + base.applicationId() + ":" + refund
                    + ":refund.succeeded";
            event.put("eventId", eventId.toString());
            event.put("eventType", "refund.succeeded");
            event.put("refundId", refund.toString());
            event.put("refundStatus", "SUCCEEDED");
            event.put("amountMinor", 4000);
            event.put("businessKey", key);
            byte[] body = event.toString().getBytes(StandardCharsets.UTF_8);
            var target = new MerchantTransport.Target(merchant.uri(), "fixture");
            var transport = new HttpMerchantTransport(
                    NotificationTargetPolicy.localFixture(merchant.uri()), ref -> SECRET, fake.clock());
            merchant.dropNextResponse();
            assertThat(transport.send(target, eventId, key, body).outcome())
                    .isEqualTo(MerchantTransport.Outcome.RETRYABLE);
            assertThat(transport.send(target, eventId, key, body).status()).isEqualTo(200);
            UUID duplicate = UUID.randomUUID();
            event.put("eventId", duplicate.toString());
            assertThat(transport
                            .send(target, duplicate, key, event.toString().getBytes(StandardCharsets.UTF_8))
                            .status())
                    .isEqualTo(200);
            assertThat(merchant.receipts()).isEqualTo(2);
            assertThat(merchant.effects()).isZero();
            assertThat(new JdbcTemplate(ds)
                            .queryForObject("SELECT count(*) FROM merchant_fixture.refund_effect", Long.class))
                    .isEqualTo(1);
        }
    }
}
