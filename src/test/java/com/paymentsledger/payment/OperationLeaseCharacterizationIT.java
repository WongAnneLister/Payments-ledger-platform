package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins provider-operation leasing before it is split into domain rules and a repository: who may claim an operation
 * (first send versus recovery), the lease and fencing fields a claim writes, owns() fencing, per-account recovery
 * batch metering, deferral when a remote fact already exists, escalation and age alerts, and the three scheduler
 * queries (prepared, due, L2 wake-up).
 */
class OperationLeaseCharacterizationIT extends PaymentCoreFixture {
    Instant now() {
        return fake.clock().instant();
    }

    PaymentRepository.Intent prepared(String order) {
        return repository.prepare(context, "lease-" + order, command(order), "FAKE");
    }

    Map<String, Object> operation(UUID id) {
        return admin.queryForMap("SELECT * FROM platform.provider_operation WHERE id=?", id);
    }

    Map<String, Object> task(UUID operation) {
        return admin.queryForMap("SELECT * FROM platform.recovery_task WHERE operation_id=?", operation);
    }

    static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    static void rejects(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void firstClaimLeasesAPreparedOperationAndItsTask() {
        var intent = prepared("first");
        long version = (Long) operation(intent.operationId()).get("version");
        var claim = repository.claim(intent.operationId(), "FAKE");
        assertThat(claim.operationId()).isEqualTo(intent.operationId());
        assertThat(claim.paymentId()).isEqualTo(intent.paymentId());
        assertThat(claim.version()).isEqualTo(version + 1);
        assertThat(claim.type()).isEqualTo("CREATE_CHECKOUT");

        var op = operation(intent.operationId());
        assertThat(op)
                .containsEntry("status", "SENDING")
                .containsEntry("lease_token", claim.token())
                .containsEntry("lease_owner", "pr07")
                .containsEntry("attempt_count", 1)
                .containsEntry("version", claim.version());
        assertThat(at(op.get("lease_until"))).isEqualTo(now().plusSeconds(30));
        assertThat(at(op.get("first_send_at"))).isEqualTo(now());
        assertThat(op.get("retry_until"))
                .as("no verified retry window by default")
                .isNull();
        var task = task(intent.operationId());
        assertThat(task)
                .containsEntry("status", "RUNNING")
                .containsEntry("lease_token", claim.token())
                .containsEntry("attempt_count", 1);
        assertThat(at(task.get("lease_until"))).isEqualTo(now().plusSeconds(30));

        assertThat(repository.claim(intent.operationId(), "FAKE"))
                .as("only PREPARED may be first-sent")
                .isNull();
        assertThat(repository.claim(UUID.randomUUID(), "FAKE")).isNull();
    }

    @Test
    void verifiedWindowSetsTheRetryHorizonOnFirstSendOnly() {
        repository.recoveryPolicy(new RecoveryPolicy(
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofDays(1),
                0,
                Duration.ofHours(1),
                Duration.ofSeconds(1)));
        var intent = prepared("window");
        Instant first = now();
        repository.claim(intent.operationId(), "FAKE");
        assertThat(at(operation(intent.operationId()).get("retry_until")))
                .isEqualTo(first.plus(Duration.ofMinutes(10)));

        fake.clock().advance(Duration.ofSeconds(31));
        var reclaimed = repository.claimRecovery(intent.operationId(), "FAKE", true);
        assertThat(reclaimed).isNotNull();
        var op = operation(intent.operationId());
        assertThat(at(op.get("first_send_at"))).as("first send is never moved").isEqualTo(first);
        assertThat(at(op.get("retry_until"))).isEqualTo(first.plus(Duration.ofMinutes(10)));
        assertThat(op).containsEntry("attempt_count", 2);
        assertThat(reclaimed.operation().get("first_send_at")).isEqualTo(Timestamp.from(first));
    }

    @Test
    void ownsFencesTokenVersionLeaseAndTask() {
        var intent = prepared("fence");
        var claim = repository.claim(intent.operationId(), "FAKE");
        assertThat(repository.transaction(() -> repository.owns(claim))).isTrue();
        var otherToken = new PaymentRepository.Claim(
                claim.operationId(),
                claim.paymentId(),
                UUID.randomUUID(),
                claim.version(),
                claim.type(),
                claim.operation(),
                claim.payment());
        var otherVersion = new PaymentRepository.Claim(
                claim.operationId(),
                claim.paymentId(),
                claim.token(),
                claim.version() + 1,
                claim.type(),
                claim.operation(),
                claim.payment());
        assertThat(repository.transaction(() -> repository.owns(otherToken))).isFalse();
        assertThat(repository.transaction(() -> repository.owns(otherVersion))).isFalse();
        admin.update("UPDATE platform.recovery_task SET status='WAITING' WHERE operation_id=?", intent.operationId());
        assertThat(repository.transaction(() -> repository.owns(claim)))
                .as("task no longer running")
                .isFalse();
        admin.update("UPDATE platform.recovery_task SET status='RUNNING' WHERE operation_id=?", intent.operationId());
        assertThat(repository.transaction(() -> repository.owns(claim))).isTrue();
        fake.clock().advance(Duration.ofSeconds(30));
        assertThat(repository.transaction(() -> repository.owns(claim)))
                .as("lease expired")
                .isFalse();
    }

    @Test
    void recoveryClaimsOnlyDueUnleasedTasksAndMetersBatchesPerAccount() {
        var intent = prepared("recover");
        repository.claim(intent.operationId(), "FAKE");
        assertThat(repository.claimRecovery(intent.operationId(), "FAKE", true))
                .as("live lease")
                .isNull();
        fake.clock().advance(Duration.ofSeconds(31));
        assertThat(repository.claimRecovery(intent.operationId(), "FAKE", true)).isNotNull();
        assertThat(at(admin.queryForObject(
                        "SELECT next_batch_at FROM platform.recovery_account_limit WHERE provider_account_id=?",
                        Timestamp.class,
                        id(4))))
                .isEqualTo(now().plusSeconds(1));

        var second = prepared("metered");
        assertThat(repository.claimRecovery(second.operationId(), "FAKE", true))
                .as("another batch for the same account must wait")
                .isNull();
        assertThat(at(task(second.operationId()).get("available_at"))).isEqualTo(now().plusSeconds(1));
        assertThat(operation(second.operationId())).containsEntry("status", "PREPARED");

        fake.clock().advance(Duration.ofSeconds(1));
        assertThat(repository.claimRecovery(second.operationId(), "FAKE", true)).isNotNull();

        var done = prepared("finished");
        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE operation_id=?", done.operationId());
        fake.clock().advance(Duration.ofSeconds(5));
        assertThat(repository.claimRecovery(done.operationId(), "FAKE", true)).isNull();
        var later = prepared("later");
        admin.update(
                "UPDATE platform.recovery_task SET available_at=? WHERE operation_id=?",
                Timestamp.from(now().plusSeconds(60)),
                later.operationId());
        assertThat(repository.claimRecovery(later.operationId(), "FAKE", true))
                .as("not due")
                .isNull();
    }

    @Test
    void knownRemotePaymentDefersAFirstSendToAQuery() {
        var intent = prepared("remote");
        admin.update("UPDATE platform.payment SET payment_intent_id='pi_remote' WHERE id=?", intent.paymentId());
        assertThat(repository.claim(intent.operationId(), "FAKE")).isNull();
        assertThat(operation(intent.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "UNKNOWN")
                .containsEntry("attempt_count", 0);
        var task = task(intent.operationId());
        assertThat(task).containsEntry("status", "WAITING").containsEntry("reason", "REMOTE_FACT_REQUIRES_QUERY");
        assertThat(at(task.get("available_at"))).isEqualTo(now());
    }

    @Test
    void firstSendRefusesConflictsAndDisabledCurrencies() {
        var conflict = prepared("conflict");
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", conflict.paymentId());
        rejects(() -> repository.claim(conflict.operationId(), "FAKE"), ErrorCode.EVIDENCE_CONFLICT);
        assertThat(operation(conflict.operationId())).containsEntry("status", "PREPARED");

        var currency = prepared("currency");
        admin.update("UPDATE platform.org_currency_enabled SET status='DISABLED' WHERE currency='USD'");
        rejects(() -> repository.claim(currency.operationId(), "FAKE"), ErrorCode.CURRENCY_NOT_ENABLED);
        assertThat(operation(currency.operationId())).containsEntry("status", "PREPARED");
    }

    @Test
    void escalationAndAgeAlertsRequireTheLease() {
        var intent = prepared("escalate");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var stale = new PaymentRepository.Claim(
                claim.operationId(),
                claim.paymentId(),
                UUID.randomUUID(),
                claim.version(),
                claim.type(),
                claim.operation(),
                claim.payment());
        repository.alertAge(stale);
        repository.escalate(stale, "IGNORED");
        assertThat(operation(intent.operationId()))
                .containsEntry("status", "SENDING")
                .containsEntry("age_alert_at", null);

        repository.alertAge(claim);
        assertThat(at(operation(intent.operationId()).get("age_alert_at"))).isEqualTo(now());
        assertThat(at(task(intent.operationId()).get("age_alert_at"))).isEqualTo(now());
        repository.escalate(claim, "UNRESOLVED_AGE_LIMIT");
        var op = operation(intent.operationId());
        assertThat(op)
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT")
                .containsEntry("lease_until", null)
                .containsEntry("resolution", "UNKNOWN");
        assertThat(at(op.get("escalated_at"))).isEqualTo(now());
        assertThat(task(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("reason", "UNRESOLVED_AGE_LIMIT")
                .containsEntry("lease_token", null)
                .containsEntry("lease_until", null);
        assertThat(admin.queryForMap("SELECT * FROM platform.payment WHERE id=?", intent.paymentId()))
                .containsEntry("resolution_status", "UNKNOWN")
                .containsEntry("payment_status", "PROCESSING");
        assertThat(repository.claimRecovery(intent.operationId(), "FAKE", true)).isNull();
    }

    @Test
    void schedulerQueriesSelectSendableDueAndStrandedOperations() {
        var a = prepared("sched-a");
        var b = prepared("sched-b");
        var conflicted = prepared("sched-c");
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", conflicted.paymentId());
        assertThat(repository.prepared(10, "FAKE")).containsExactly(a.operationId(), b.operationId());
        assertThat(repository.prepared(0, "FAKE"))
                .as("limit is clamped to at least one")
                .hasSize(1);
        assertThat(repository.prepared(10, "STRIPE")).isEmpty();
        assertThat(repository.prepared(10, null)).isEmpty();

        assertThat(repository.due(10, "FAKE")).contains(a.operationId(), b.operationId());
        admin.update(
                "UPDATE platform.recovery_task SET available_at=? WHERE operation_id=?",
                Timestamp.from(now().plusSeconds(60)),
                b.operationId());
        repository.claim(a.operationId(), "FAKE");
        assertThat(repository.due(10, "FAKE"))
                .as("leased and future tasks are not due")
                .doesNotContain(a.operationId(), b.operationId());

        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE operation_id=?", b.operationId());
        assertThat(repository.wakeL2(10, "FAKE")).isGreaterThanOrEqualTo(1);
        var woken = task(b.operationId());
        assertThat(woken).containsEntry("status", "WAITING");
        assertThat(at(woken.get("available_at"))).isEqualTo(now());
        assertThat(task(a.operationId())).as("a live lease is not woken").containsEntry("status", "RUNNING");
        assertThat(repository.wakeL2(10, null)).isZero();
    }
}
