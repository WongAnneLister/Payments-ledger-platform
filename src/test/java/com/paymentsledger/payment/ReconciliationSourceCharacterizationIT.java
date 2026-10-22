package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the source recovery branches that {@link ReconciliationRecoveryIT} leaves open before the class is split into
 * domain rules and a repository: linking to a payment that already exists for the order, an existing payment bound
 * to another intent, bindings that are expired or disagree on the amount, provider reads that are not configured,
 * throw or return a charge without an intent, and the bounds checked before any task is written.
 */
class ReconciliationSourceCharacterizationIT extends ReconciliationRecoveryFixture {
    ReconciliationSourceRecovery sources() {
        return (ReconciliationSourceRecovery) recovery;
    }

    String recoverOnce(ReconciliationSourceRecovery sources) {
        sources.enqueue(scope, run, "txn_recovered");
        var claims = sources.claim(scope, run, 1);
        assertThat(claims).hasSize(1);
        return sources.recover(claims.getFirst());
    }

    Map<String, Object> sourceTask() {
        return jdbc.queryForMap("""
                SELECT status,reason,attempt_count,lease_token,escalated_at IS NOT NULL AS escalated
                FROM platform.recovery_task
                WHERE task_type='RECONCILIATION_SOURCE'""");
    }

    String observedEvidence() {
        return jdbc.queryForObject(
                "SELECT evidence->>'classification' FROM platform.reconciliation_source_observation", String.class);
    }

    void binding(UUID order, long amount, Instant eligibleUntil) {
        admin.update(
                """
                INSERT INTO platform.order_provider_binding(organization_id,environment_id,application_id,
                    provider_account_id,livemode,currency,order_id,payment_intent_id,amount_minor,proof_kind,proof_ref,
                    proof_digest,eligible_until)
                VALUES (?,?,?,?,false,'USD',?,'pi_recovered',?,'SERVER_ORDER_FIXTURE','independent-server-order-v1',
                    ?,?)""",
                id(1),
                id(2),
                id(3),
                id(4),
                order,
                amount,
                "c".repeat(64),
                eligibleUntil == null ? null : Timestamp.from(eligibleUntil));
    }

    UUID nativePayment(String key) {
        return repository.prepare(context, key, command(key), "FAKE").paymentId();
    }

    UUID orderOf(UUID payment) {
        return jdbc.queryForObject("SELECT business_order_id FROM platform.payment WHERE id=?", UUID.class, payment);
    }

    @Test
    void existingNativePaymentForTheOrderIsLinkedWithoutInsertingAnother() {
        UUID payment = nativePayment("existing-native");
        binding(orderOf(payment));

        assertThat(recoverOnce(sources())).isEqualTo("LINKED");

        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("reconciliation_payment_provenance")).isZero();
        assertThat(jdbc.queryForObject("SELECT origin FROM platform.payment WHERE id=?", String.class, payment))
                .isEqualTo("NATIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT payment_intent_id FROM platform.payment WHERE id=?", String.class, payment))
                .isEqualTo("pi_recovered");
        assertThat(sourceTask()).containsEntry("status", "DONE").containsEntry("reason", "LINKED");
        assertThat(psp()).isEqualTo(9700);
    }

    @Test
    void existingPaymentBoundToAnotherIntentIsEscalatedAsAConflict() {
        UUID payment = nativePayment("existing-other-intent");
        binding(orderOf(payment));
        admin.update("UPDATE platform.payment SET payment_intent_id='pi_other' WHERE id=?", payment);

        assertThat(recoverOnce(sources())).isEqualTo("UNALLOCATED");

        assertThat(sourceTask())
                .containsEntry("status", "ESCALATED")
                .containsEntry("reason", "SOURCE_EVIDENCE_CONFLICT")
                .containsEntry("escalated", true);
        assertThat(count("reconciliation_payment_provenance")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT reason
                        FROM platform.reconciliation_exception
                        WHERE source_ref='txn_recovered'""", String.class)).isEqualTo("SOURCE_EVIDENCE_CONFLICT");
        assertThat(psp()).isZero();
    }

    @Test
    void expiredBindingIsNotEligibleForAHistoricalLink() {
        binding(order("expired-binding"), 10000, clock.instant().minusSeconds(1));

        assertThat(recoverOnce(sources())).isEqualTo("UNALLOCATED");

        assertThat(sourceTask()).containsEntry("status", "ESCALATED").containsEntry("reason", "ORDER_NOT_ELIGIBLE");
        assertThat(count("payment")).isZero();
    }

    @Test
    void bindingThatIsStillEligibleLinks() {
        binding(order("future-binding"), 10000, clock.instant().plusSeconds(3600));
        assertThat(recoverOnce(sources())).isEqualTo("LINKED");
        assertThat(count("payment")).isEqualTo(1);
    }

    @Test
    void bindingForAnotherAmountIsNotEligible() {
        binding(order("short-binding"), 9000, null);

        assertThat(recoverOnce(sources())).isEqualTo("UNALLOCATED");

        assertThat(sourceTask()).containsEntry("status", "ESCALATED").containsEntry("reason", "ORDER_NOT_ELIGIBLE");
        assertThat(count("payment")).isZero();
    }

    @Test
    void missingProviderReadsWaitForEvidence() {
        binding(order("no-ports"));

        assertThat(recoverOnce(sources(null, null))).isEqualTo("WAITING_EVIDENCE");

        assertThat(sourceTask())
                .containsEntry("status", "WAITING")
                .containsEntry("reason", "SOURCE_READ_UNAVAILABLE")
                .containsEntry("lease_token", null)
                .containsEntry("escalated", false);
        assertThat(observedEvidence()).isEqualTo("UNAVAILABLE");
        assertThat(count("reconciliation_exception")).isPositive();
        assertThat(count("payment")).isZero();
    }

    @Test
    void thrownProviderReadWaitsForEvidence() {
        binding(order("thrown-read"));
        reads.chargeFailure = new IllegalStateException("provider read failed");

        assertThat(recoverOnce(sources())).isEqualTo("WAITING_EVIDENCE");

        assertThat(sourceTask()).containsEntry("status", "WAITING").containsEntry("reason", "SOURCE_READ_UNAVAILABLE");
        assertThat(observedEvidence()).isEqualTo("READ_THROWN");
    }

    @Test
    void chargeWithoutPaymentIntentIsAConflict() {
        binding(order("charge-without-intent"));
        reads.chargeIntent = null;

        assertThat(recoverOnce(sources())).isEqualTo("UNALLOCATED");

        assertThat(sourceTask())
                .containsEntry("status", "ESCALATED")
                .containsEntry("reason", "SOURCE_EVIDENCE_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT fact->>'chargeId' FROM platform.reconciliation_source_observation", String.class))
                .isEqualTo("ch_recovered");
    }

    @Test
    void secondClaimFindsNothingWhileTheFirstLeaseIsLive() {
        sources().enqueue(scope, run, "txn_recovered");
        assertThat(sources().enqueue(scope, run, "txn_recovered"))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT id FROM platform.recovery_task WHERE task_type='RECONCILIATION_SOURCE'", UUID.class));
        var first = sources().claim(scope, run, 5);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().version()).isEqualTo(1);
        assertThat(sources().claim(scope, run, 5)).isEmpty();
        assertThat(sourceTask()).containsEntry("status", "RUNNING").containsEntry("attempt_count", 1);
    }

    @Test
    void boundsAndUnknownBalanceTransactionsAreRejectedBeforeAnyTaskIsWritten() {
        assertThatThrownBy(() -> sources().enqueue(scope, run, "txn_missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scoped capture BT required");
        assertThatThrownBy(() -> sources().claim(scope, run, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bounded claim limit required");
        assertThatThrownBy(() -> sources().claim(scope, run, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sources().drain(scope, run, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bounded drain limit required");
        assertThatThrownBy(() -> sources().claim(scope, UUID.randomUUID(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown scoped reconciliation run");
        assertThat(count("recovery_task")).isZero();
    }
}
