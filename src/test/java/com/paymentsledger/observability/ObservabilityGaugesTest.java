package com.paymentsledger.observability;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;

class ObservabilityGaugesTest {
    static final List<String> NAMES = List.of(
            "unknown_payment_count",
            "unknown_payment_oldest_age",
            "refund_reserved_age",
            "webhook_lag",
            "outbox_oldest_age",
            "ledger_pending_evidence",
            "unclassified_bt_count",
            "trial_balance_failures",
            "reconciliation_residual",
            "payout_unmatched_age",
            "share_cap_violations");

    @Test
    void persistentBoundedFinanceGaugesHaveNoIdentifiersAndMissingIsNan() {
        var registry = new SimpleMeterRegistry();
        var source = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>(
                new ObjectMapper().createObjectNode());
        var gauges = new ObservabilityGauges(registry, source::get);
        assertThat(registry.getMeters()).hasSize(11);
        for (String name : NAMES) {
            var gauge = registry.find("payments.observability." + name).gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isNaN();
            assertThat(gauge.getId().getTags()).isEmpty();
        }
        var json = new ObjectMapper();
        var snapshot = json.createObjectNode();
        snapshot.putArray("metrics")
                .addObject()
                .put("name", "unknown_payment_count")
                .put("status", "AVAILABLE")
                .put("value", "2");
        source.set(snapshot.deepCopy());
        assertThat(registry.find("payments.observability.unknown_payment_count")
                        .gauge()
                        .value())
                .isEqualTo(2);
        snapshot.withArray("metrics").get(0).deepCopy();
        snapshot.withArray("metrics")
                .removeAll()
                .addObject()
                .put("name", "unknown_payment_count")
                .put("status", "NO_DATA")
                .putNull("value");
        source.set(snapshot.deepCopy());
        assertThat(registry.find("payments.observability.unknown_payment_count")
                        .gauge()
                        .value())
                .isNaN();
        assertThat(registry.getMeters()).hasSize(11);
    }
}
