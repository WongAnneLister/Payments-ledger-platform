package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OperationLeaseRulesTest {
    static final Instant NOW = Instant.parse("2026-10-16T02:00:00Z");
    static final Instant EARLIER = NOW.minusSeconds(1);
    static final Instant LATER = NOW.plusSeconds(1);

    @Test
    void firstSendTakesOnlyPreparedOperations() {
        assertThat(OperationLeaseRules.claimable(false, "PREPARED", null, null, null, NOW))
                .isTrue();
        assertThat(OperationLeaseRules.claimable(false, "SENDING", null, "READY", EARLIER, NOW))
                .isFalse();
        assertThat(OperationLeaseRules.claimable(false, "UNKNOWN", null, "WAITING", EARLIER, NOW))
                .isFalse();
    }

    @Test
    void recoveryTakesDueTasksWhoseLeaseHasExpired() {
        assertThat(OperationLeaseRules.claimable(true, "UNKNOWN", null, "WAITING", NOW, NOW))
                .isTrue();
        assertThat(OperationLeaseRules.claimable(true, "SENDING", NOW, "RUNNING", EARLIER, NOW))
                .as("a lease expiring exactly now is expired")
                .isTrue();
        assertThat(OperationLeaseRules.claimable(true, "SENDING", LATER, "RUNNING", EARLIER, NOW))
                .isFalse();
        assertThat(OperationLeaseRules.claimable(true, "UNKNOWN", null, "WAITING", LATER, NOW))
                .isFalse();
        assertThat(OperationLeaseRules.claimable(true, "ACKNOWLEDGED", null, "DONE", EARLIER, NOW))
                .isFalse();
    }

    @Test
    void escalatedOperationsAreNeverClaimed() {
        assertThat(OperationLeaseRules.claimable(false, "ESCALATED", null, null, null, NOW))
                .isFalse();
        assertThat(OperationLeaseRules.claimable(true, "ESCALATED", null, "WAITING", EARLIER, NOW))
                .isFalse();
    }

    @Test
    void remotePaymentIsKnownOnceItHasAnIntentOrACapture() {
        assertThat(OperationLeaseRules.remotePaymentKnown(null, 0)).isFalse();
        assertThat(OperationLeaseRules.remotePaymentKnown("pi_1", 0)).isTrue();
        assertThat(OperationLeaseRules.remotePaymentKnown(null, 1)).isTrue();
    }

    @Test
    void firstGrantFixesTheSendTimeAndTheRetryHorizon() {
        var grant = OperationLeaseRules.grant(null, null, NOW, Duration.ofHours(24), Duration.ofSeconds(30));
        assertThat(grant.firstSendAt()).isEqualTo(NOW);
        assertThat(grant.retryUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(grant.leaseUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void laterGrantsKeepTheSendTimeAndHorizonAndRenewOnlyTheLease() {
        Instant first = NOW.minusSeconds(600), horizon = first.plus(Duration.ofHours(24));
        var grant = OperationLeaseRules.grant(first, horizon, NOW, Duration.ofHours(48), Duration.ofSeconds(30));
        assertThat(grant.firstSendAt()).isEqualTo(first);
        assertThat(grant.retryUntil()).isEqualTo(horizon);
        assertThat(grant.leaseUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void withoutAVerifiedWindowThereIsNoRetryHorizon() {
        var grant = OperationLeaseRules.grant(null, null, NOW, Duration.ZERO, Duration.ofSeconds(30));
        assertThat(grant.firstSendAt()).isEqualTo(NOW);
        assertThat(grant.retryUntil()).isNull();
    }

    @Test
    void accountBatchOpensAtItsScheduledTime() {
        assertThat(OperationLeaseRules.batchAvailable(EARLIER, NOW)).isTrue();
        assertThat(OperationLeaseRules.batchAvailable(NOW, NOW)).isTrue();
        assertThat(OperationLeaseRules.batchAvailable(LATER, NOW)).isFalse();
    }

    @Test
    void paymentTaskIsDoneWhenAcknowledgedCanceledWithCertaintyOrCancelResolved() {
        assertThat(OperationLeaseRules.paymentTaskDone("ACKNOWLEDGED", "CREATE_CHECKOUT", "PROCESSING", "UNKNOWN"))
                .isTrue();
        assertThat(OperationLeaseRules.paymentTaskDone("UNKNOWN", "CREATE_CHECKOUT", "CANCELED", "KNOWN"))
                .isTrue();
        assertThat(OperationLeaseRules.paymentTaskDone("UNKNOWN", "CREATE_CHECKOUT", "CANCELED", "UNKNOWN"))
                .isFalse();
        assertThat(OperationLeaseRules.paymentTaskDone("RESOLVED", "CANCEL_PAYMENT", "SUCCEEDED", "KNOWN"))
                .isTrue();
        assertThat(OperationLeaseRules.paymentTaskDone("RESOLVED", "CREATE_CHECKOUT", "SUCCEEDED", "KNOWN"))
                .isFalse();
    }

    @Test
    void refundTaskIsDoneOnlyWhenTerminalAndCertain() {
        assertThat(OperationLeaseRules.refundTaskDone(true, "KNOWN")).isTrue();
        assertThat(OperationLeaseRules.refundTaskDone(true, "UNKNOWN")).isFalse();
        assertThat(OperationLeaseRules.refundTaskDone(false, "KNOWN")).isFalse();
    }

    @Test
    void l2WakesOnlyUnescalatedUnleasedOperations() {
        assertThat(OperationLeaseRules.wakeable("UNKNOWN", null, NOW)).isTrue();
        assertThat(OperationLeaseRules.wakeable("SENDING", NOW, NOW)).isTrue();
        assertThat(OperationLeaseRules.wakeable("SENDING", LATER, NOW)).isFalse();
        assertThat(OperationLeaseRules.wakeable("ESCALATED", null, NOW)).isFalse();
    }
}
