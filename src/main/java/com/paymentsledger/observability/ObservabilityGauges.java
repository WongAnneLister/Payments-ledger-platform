package com.paymentsledger.observability;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;
/** Durable global active test-scope sampling. Missing observations and persistence errors are NaN. */
public final class ObservabilityGauges {
    public static final List<String> NAMES=List.of("unknown_payment_count","unknown_payment_oldest_age","refund_reserved_age","webhook_lag","outbox_oldest_age","ledger_pending_evidence","unclassified_bt_count","trial_balance_failures","reconciliation_residual","payout_unmatched_age","share_cap_violations");
    private final Supplier<JsonNode> persistentSnapshot;
    public ObservabilityGauges(MeterRegistry registry,Supplier<JsonNode> persistentSnapshot) {
        this.persistentSnapshot=persistentSnapshot;
        for(String name:NAMES) Gauge.builder("payments.observability."+name,this,g->g.value(name)).strongReference(true).register(registry);
    }
    private double value(String name) {
        try {
            for(var metric:persistentSnapshot.get().path("metrics")) if(metric.path("name").asText().equals(name) && metric.path("status").asText().equals("AVAILABLE") && metric.path("value").isTextual()) {
                double value=Double.parseDouble(metric.path("value").asText());
                return Double.isFinite(value)?value:Double.NaN;
            }
        } catch(RuntimeException unavailable) { /* Finance telemetry never masks or logs database failures. */ }
        return Double.NaN;
    }
}
