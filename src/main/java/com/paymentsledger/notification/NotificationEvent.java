package com.paymentsledger.notification;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Immutable authoritative event; its exact stored bytes are reused for every attempt. */
public record NotificationEvent(
        UUID id,
        UUID organizationId,
        UUID environmentId,
        UUID applicationId,
        UUID providerAccountId,
        UUID paymentId,
        UUID orderId,
        String currency,
        String eventType,
        long resourceVersion,
        String businessKey,
        String origin,
        Instant occurredAt,
        String payload,
        String digest) {
    public byte[] bytes() {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    static NotificationEvent read(ResultSet r, int row) throws SQLException {
        return new NotificationEvent(
                r.getObject("id", UUID.class),
                r.getObject("organization_id", UUID.class),
                r.getObject("environment_id", UUID.class),
                r.getObject("application_id", UUID.class),
                r.getObject("provider_account_id", UUID.class),
                r.getObject("payment_id", UUID.class),
                r.getObject("order_id", UUID.class),
                r.getString("currency"),
                r.getString("event_type"),
                r.getLong("resource_version"),
                r.getString("business_key"),
                r.getString("origin"),
                r.getTimestamp("occurred_at").toInstant(),
                r.getString("payload"),
                r.getString("payload_digest"));
    }
}
