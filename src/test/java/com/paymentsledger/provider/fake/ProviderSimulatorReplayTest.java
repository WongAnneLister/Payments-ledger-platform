package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;
import static com.paymentsledger.provider.ProviderCallClassification.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Produces a small shareable evidence trace from actual simulator calls, not handwritten numbers. */
class ProviderSimulatorReplayTest {
    @TempDir Path directory;
    @Test void recordsLostResponseRecoveryAndExpiredRefundKeyRisk() throws Exception {
        var start=Instant.parse("2026-01-01T00:00:00Z");
        var scope=new ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
        var fake=new FakeStripeProvider(directory,42,start,Duration.ofMinutes(10));
        var checkout=new CreateCheckoutSessionCommand(scope,"checkout:1",10000,"USD","https://example.test/success","https://example.test/cancel",Map.of(),Map.of("order","1"),Duration.ofMinutes(30));
        fake.controls().enqueue(scope,FailureScript.Operation.CREATE_SESSION,"checkout:1",FailureScript.Outcome.RESPONSE_LOST);
        assertThat(fake.createSession(checkout).classification()).isEqualTo(TIMEOUT_UNKNOWN);
        fake=new FakeStripeProvider(directory,42,start,Duration.ofMinutes(10));
        var session=fake.createSession(checkout).payload();
        var pi=fake.controls().attemptPayment(scope,session.sessionId(),FakeControls.AttemptOutcome.SUCCEED);
        var refund=new CreateRefundCommand(scope,"refund:1",pi.latestChargeId(),null,1000,"requested_by_customer");
        fake.controls().enqueue(scope,FailureScript.Operation.CREATE_REFUND,"refund:1",FailureScript.Outcome.RESPONSE_LOST);
        assertThat(fake.createRefund(refund).classification()).isEqualTo(TIMEOUT_UNKNOWN);
        var recovered=fake.createRefund(refund).payload();
        assertThat(fake.createRefund(refund).payload()).isEqualTo(recovered);
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
        fake.clock().advance(Duration.ofMinutes(10));
        var unsafe=fake.createRefund(refund).payload();
        assertThat(unsafe.refundId()).isNotEqualTo(recovered.refundId());
        var counters=fake.counters(scope);
        assertThat(counters).isEqualTo(new ProviderCounters(6,3,1,1,2));
        var report=FakeStateStore.JSON.createObjectNode();
        report.put("sourceMode","PROVIDER_SIMULATOR").put("ruleVersion","PR04-1").put("seed",42)
                .put("executionStatus","PASS").put("dataCompleteness","NOT_APPLICABLE")
                .put("initialTime",start.toString()).put("finalTime",fake.clock().instant().toString())
                .put("retention","PT10M").put("scopeExplanation","Local fixture, no real Stripe account")
                .put("assertion","Within-window replay refunds once; expired key can produce a second independent refund effect");
        report.set("scope",FakeStateStore.JSON.valueToTree(scope));
        report.set("counters",FakeStateStore.JSON.valueToTree(counters));
        report.set("effects",FakeStateStore.JSON.valueToTree(fake.controls().effects(scope)));
        report.set("calls",FakeStateStore.JSON.valueToTree(fake.controls().calls(scope)));
        Files.createDirectories(Path.of("target","pr04-evidence"));
        FakeStateStore.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","pr04-evidence","replay.json").toFile(),report);
    }
}
