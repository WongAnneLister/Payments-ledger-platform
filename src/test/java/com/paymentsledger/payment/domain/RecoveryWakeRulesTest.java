package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.payment.domain.RecoveryWakeRules.Operation;
import com.paymentsledger.payment.domain.RecoveryWakeRules.Task;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryWakeRulesTest {
    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final Instant EARLIER = NOW.minusSeconds(1);
    static final Instant LATER = NOW.plusSeconds(1);

    static Task task(String status, Instant leaseUntil) {
        return new Task(
                UUID.randomUUID(), UUID.randomUUID(), "USD", null, null, null, UUID.randomUUID(), status, leaseUntil);
    }

    static boolean refused(String status, String type, Instant retryUntil, Instant leaseUntil) {
        return RecoveryWakeRules.operationRefused(new Operation(status, type, retryUntil, leaseUntil), NOW);
    }

    @Test
    void waitingOrReadyTasksWithoutALiveLeaseMayBeWoken() {
        assertThat(RecoveryWakeRules.taskRefused(task("WAITING", null), NOW)).isFalse();
        assertThat(RecoveryWakeRules.taskRefused(task("READY", NOW), NOW))
                .as("a lease expiring exactly now is expired")
                .isFalse();
        assertThat(RecoveryWakeRules.taskRefused(task("RUNNING", EARLIER), NOW)).isFalse();
    }

    @Test
    void leasedDoneAndEscalatedTasksAreRefused() {
        assertThat(RecoveryWakeRules.taskRefused(task("RUNNING", LATER), NOW)).isTrue();
        assertThat(RecoveryWakeRules.taskRefused(task("DONE", null), NOW)).isTrue();
        assertThat(RecoveryWakeRules.taskRefused(task("ESCALATED", null), NOW)).isTrue();
    }

    @Test
    void leasedResolvedAndEscalatedOperationsAreRefused() {
        assertThat(refused("PREPARED", "CREATE_CHECKOUT", null, null)).isFalse();
        assertThat(refused("PREPARED", "CREATE_CHECKOUT", null, LATER)).isTrue();
        assertThat(refused("RESOLVED", "QUERY_REFUND", LATER, null)).isTrue();
        assertThat(refused("ESCALATED", "QUERY_REFUND", LATER, null)).isTrue();
    }

    @Test
    void uncertainCreatesAreRefusedOnlyOnceTheirRetryHorizonHasPassed() {
        for (String status : new String[] {"UNKNOWN", "SENDING"}) {
            assertThat(refused(status, "CREATE_CHECKOUT", LATER, null)).isFalse();
            assertThat(refused(status, "CREATE_CHECKOUT", NOW, null)).isTrue();
            assertThat(refused(status, "CREATE_REFUND", EARLIER, null)).isTrue();
            assertThat(refused(status, "CREATE_REFUND", null, null))
                    .as("a create without a horizon cannot be retried safely")
                    .isTrue();
        }
        assertThat(refused("UNKNOWN", "QUERY_REFUND", EARLIER, null))
                .as("only creates depend on the provider's idempotency window")
                .isFalse();
    }
}
