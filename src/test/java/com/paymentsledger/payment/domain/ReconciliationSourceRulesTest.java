package com.paymentsledger.payment.domain;

import static com.paymentsledger.payment.domain.ReconciliationSourceRules.*;
import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.ChargeFact;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import com.paymentsledger.provider.ProviderScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationSourceRulesTest {
    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final String VERSION = "2026-01-01";
    static final ProviderScope SCOPE = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 4), false);

    static SourceBalanceTransaction bt(String domain, long amount, String currency, String state) {
        return new SourceBalanceTransaction(
                UUID.randomUUID(), "txn_1", domain, "charge", "charge", "ch_1", amount, currency, VERSION, state);
    }

    static SourceBalanceTransaction capture() {
        return bt("PAYMENTS", 10000, "USD", "AVAILABLE");
    }

    static ProviderEvidence evidence(int status, String version, String digest) {
        return new ProviderEvidence(null, null, NOW, NOW.plusMillis(5), status, null, digest, version);
    }

    static <T> ProviderCallResult<T> ok(T payload) {
        return new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS, payload, evidence(200, VERSION, "b".repeat(64)));
    }

    static ChargeFact charge(long amount, Long captured, String intent) {
        return new ChargeFact("ch_1", intent, "succeeded", true, amount, captured, "usd", "txn_1", null, null, NOW);
    }

    static PaymentIntentFact intent(String status, long received, List<String> charges) {
        return new PaymentIntentFact("pi_1", status, 10000, received, "usd", charges, "ch_1", null, Map.of(), NOW);
    }

    @Test
    void onlyPositiveCaptureBalanceTransactionsInTheRunCurrencyAreRecoverable() {
        assertThat(recoverableCapture(capture(), "USD")).isTrue();
        assertThat(recoverableCapture(null, "USD")).isFalse();
        assertThat(recoverableCapture(capture(), "EUR")).isFalse();
        assertThat(recoverableCapture(bt("PAYMENTS", 0, "USD", "AVAILABLE"), "USD"))
                .isFalse();
        assertThat(recoverableCapture(bt("ISSUING", 10000, "USD", "AVAILABLE"), "USD"))
                .isFalse();
    }

    @Test
    void onlySuccessfulVersionedAndDigestedReadsAreTrusted() {
        assertThat(trusted(ok("payload"))).isTrue();
        assertThat(trusted(null)).isFalse();
        assertThat(trusted(new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS, null, evidence(200, VERSION, "b".repeat(64)))))
                .isFalse();
        assertThat(trusted(new ProviderCallResult<>(
                        ProviderCallClassification.AUTH_REQUIRED, "payload", evidence(200, VERSION, "b".repeat(64)))))
                .isFalse();
        assertThat(trusted(new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS, "payload", evidence(202, VERSION, "b".repeat(64)))))
                .isFalse();
        assertThat(trusted(new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS, "payload", evidence(200, " ", "b".repeat(64)))))
                .isFalse();
        assertThat(trusted(new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS, "payload", evidence(200, VERSION, "B".repeat(64)))))
                .isFalse();
    }

    @Test
    void captureIsProvenOnlyWhenChargeIntentAndBalanceTransactionAgree() {
        var fullyCaptured = ok(charge(10000, 10000L, "pi_1"));
        var succeeded = ok(intent("succeeded", 10000, List.of("ch_1")));
        assertThat(provesCapture(capture(), fullyCaptured, succeeded)).isTrue();

        assertThat(provesCapture(capture(), ok(charge(10000, 9000L, "pi_1")), succeeded))
                .as("partially captured")
                .isFalse();
        assertThat(provesCapture(capture(), ok(charge(10000, 10000L, "pi_other")), succeeded))
                .as("charge of another intent")
                .isFalse();
        assertThat(provesCapture(capture(), fullyCaptured, ok(intent("processing", 10000, List.of("ch_1")))))
                .isFalse();
        assertThat(provesCapture(capture(), fullyCaptured, ok(intent("succeeded", 10000, List.of("ch_0")))))
                .isFalse();
        assertThat(provesCapture(bt("PAYMENTS", 9999, "USD", "AVAILABLE"), fullyCaptured, succeeded))
                .isFalse();
        assertThat(provesCapture(bt("PAYMENTS", 10000, "USD", "QUARANTINED"), fullyCaptured, succeeded))
                .isFalse();
        var otherVersion = new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                charge(10000, 10000L, "pi_1"),
                evidence(200, "2025-01-01", "b".repeat(64)));
        assertThat(provesCapture(capture(), otherVersion, succeeded))
                .as("a read under another API version cannot prove the pinned BT page")
                .isFalse();
    }

    @Test
    void bindingAndOrderMustMatchTheCaptureAndStillBeEligible() {
        var order = new RecoverableOrder(UUID.randomUUID(), null, null, null, null, "USD", 10000);
        var binding = new OrderBinding(UUID.randomUUID(), order.id(), "USD", 10000, null);
        var charge = charge(10000, 10000L, "pi_1");
        assertThat(orderMismatch(binding, order, charge, "USD")).isFalse();
        assertThat(orderMismatch(binding, order, charge, "EUR")).isTrue();
        assertThat(orderMismatch(new OrderBinding(binding.id(), order.id(), "USD", 9000, null), order, charge, "USD"))
                .isTrue();
        assertThat(orderMismatch(
                        binding, new RecoverableOrder(order.id(), null, null, null, null, "USD", 9000), charge, "USD"))
                .isTrue();

        assertThat(bindingExpired(binding, NOW)).isFalse();
        assertThat(bindingExpired(new OrderBinding(binding.id(), order.id(), "USD", 10000, NOW.plusSeconds(1)), NOW))
                .isFalse();
        assertThat(bindingExpired(new OrderBinding(binding.id(), order.id(), "USD", 10000, NOW), NOW))
                .isTrue();
    }

    @Test
    void existingPaymentMustNotContradictTheCapture() {
        var charge = charge(10000, 10000L, "pi_1");
        var intent = intent("succeeded", 10000, List.of("ch_1"));
        UUID id = UUID.randomUUID();
        assertThat(paymentContradicts(new ExistingPayment(id, 10000, null, "PENDING", "KNOWN"), charge, intent))
                .isFalse();
        assertThat(paymentContradicts(new ExistingPayment(id, 10000, "pi_1", "SUCCEEDED", "KNOWN"), charge, intent))
                .isFalse();
        assertThat(paymentContradicts(new ExistingPayment(id, 9000, null, "PENDING", "KNOWN"), charge, intent))
                .isTrue();
        assertThat(paymentContradicts(new ExistingPayment(id, 10000, "pi_2", "PENDING", "KNOWN"), charge, intent))
                .isTrue();
        assertThat(paymentContradicts(new ExistingPayment(id, 10000, null, "CANCELED", "KNOWN"), charge, intent))
                .isTrue();
        assertThat(paymentContradicts(new ExistingPayment(id, 10000, null, "PENDING", "CONFLICT"), charge, intent))
                .isTrue();
    }

    @Test
    void unreadableEvidenceWaitsAndEveryOtherProblemEscalates() {
        assertThat(disposition(READ_UNAVAILABLE)).isEqualTo(new Disposition("WAITING", "WAITING_EVIDENCE"));
        for (String problem : List.of(EVIDENCE_CONFLICT, NO_TRUSTED_ORDER, ORDER_NOT_ELIGIBLE))
            assertThat(disposition(problem)).isEqualTo(new Disposition("ESCALATED", "UNALLOCATED"));
        assertThat(withdrawn(null)).isTrue();
        assertThat(withdrawn(bt("PAYMENTS", 10000, "USD", "EXCLUDED"))).isTrue();
        assertThat(withdrawn(capture())).isFalse();
    }

    @Test
    void aClaimOwnsItsTaskOnlyWhileItsLeaseIsLiveAndUnchanged() {
        UUID token = UUID.randomUUID(), run = UUID.randomUUID(), bt = UUID.randomUUID();
        var task = new SourceTask(
                UUID.randomUUID(),
                "RUNNING",
                token,
                3,
                run,
                bt,
                SCOPE.organizationId(),
                SCOPE.environmentId(),
                SCOPE.providerAccountId(),
                false,
                NOW.plusSeconds(60));
        assertThat(owns(task, SCOPE, run, bt, token, 3, NOW)).isTrue();
        assertThat(owns(null, SCOPE, run, bt, token, 3, NOW)).isFalse();
        assertThat(owns(task, SCOPE, run, bt, UUID.randomUUID(), 3, NOW))
                .as("newer claimer")
                .isFalse();
        assertThat(owns(task, SCOPE, run, bt, token, 2, NOW)).isFalse();
        assertThat(owns(task, SCOPE, UUID.randomUUID(), bt, token, 3, NOW)).isFalse();
        assertThat(owns(
                        task,
                        new ProviderScope(SCOPE.organizationId(), SCOPE.environmentId(), UUID.randomUUID(), false),
                        run,
                        bt,
                        token,
                        3,
                        NOW))
                .isFalse();
        assertThat(owns(task, SCOPE, run, bt, token, 3, NOW.plusSeconds(60)))
                .as("expired lease")
                .isFalse();
    }

    @Test
    void observationsRecordNormalizedFactsAndReadClassificationsOnly() {
        var fact = observedFact(charge(10000, 10000L, "pi_1"), intent("succeeded", 10000, List.of("ch_1")));
        assertThat(fact.keySet())
                .containsExactly(
                        "chargeId",
                        "paymentIntentId",
                        "btId",
                        "amountMinor",
                        "capturedAmountMinor",
                        "currency",
                        "status",
                        "captured",
                        "intentStatus",
                        "intentReceivedMinor");
        assertThat(fact).containsEntry("currency", "USD");
        assertThat(observedFact(null, null)).isEmpty();

        var read = ok("secret payload");
        assertThat(readEvidence(read))
                .containsOnlyKeys("classification", "evidence")
                .containsEntry("classification", ProviderCallClassification.SUCCESS);
        assertThat(readEvidence(null)).containsExactly(Map.entry("classification", "MISSING_RESULT"));
    }
}
