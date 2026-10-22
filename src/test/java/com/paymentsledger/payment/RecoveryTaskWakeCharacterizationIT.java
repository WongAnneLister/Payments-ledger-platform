package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.repository.PaymentRepositories;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the operator wake of an existing recovery task before it is split into rules and a repository: payment,
 * refund and reconciliation source tasks with their lock order, tasks outside the caller's scope or on a disabled
 * currency, and the task and operation states that refuse a wake.
 */
class RecoveryTaskWakeCharacterizationIT extends ReconciliationRecoveryFixture {
    RecoveryTaskOperations operations;

    @BeforeEach
    void operations() {
        operations = new RecoveryTaskOperations(jdbc, fake.clock());
    }

    Map<String, Object> wake(UUID environment, UUID task) {
        return new TransactionTemplate(tx).execute(s -> operations.wake(id(1), environment, task));
    }

    Map<String, Object> wake(UUID task) {
        return wake(id(2), task);
    }

    void refused(UUID task, ErrorCode code) {
        assertThatThrownBy(() -> wake(task))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    Map<String, Object> task(UUID id) {
        return jdbc.queryForMap("SELECT status,available_at,version FROM platform.recovery_task WHERE id=?", id);
    }

    UUID paymentTask(String key) {
        UUID payment = repository.prepare(context, key, command(key), "FAKE").paymentId();
        return jdbc.queryForObject("SELECT id FROM platform.recovery_task WHERE payment_id=?", UUID.class, payment);
    }

    UUID operationOf(UUID task) {
        return jdbc.queryForObject("SELECT operation_id FROM platform.recovery_task WHERE id=?", UUID.class, task);
    }

    void waiting(UUID task) {
        admin.update("""
                UPDATE platform.recovery_task
                SET status='WAITING',available_at=now()+interval '1 day',lease_until=NULL
                WHERE id=?""", task);
    }

    void assertAwake(UUID task, Map<String, Object> result) {
        assertThat(result)
                .containsEntry("taskId", task.toString())
                .containsEntry("status", "PENDING")
                .containsEntry("nextScheduledAt", fake.clock().instant().toString())
                .containsEntry("note", "Existing recovery obligation awakened; no provider request sent");
        var row = task(task);
        assertThat(row).containsEntry("status", "READY");
        assertThat(((Timestamp) row.get("available_at")).toInstant())
                .isEqualTo(fake.clock().instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void paymentOperationTaskIsMadeReadyWithoutTouchingTheOperation() {
        UUID task = paymentTask("wake-payment");
        waiting(task);
        String operation = jdbc.queryForObject(
                "SELECT status FROM platform.provider_operation WHERE id=?", String.class, operationOf(task));

        assertAwake(task, wake(task));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.provider_operation WHERE id=?", String.class, operationOf(task)))
                .isEqualTo(operation);
    }

    @Test
    void refundOperationTaskIsFoundThroughItsRefundsPayment() {
        UUID payment = repository
                .prepare(context, "wake-refund-payment", command("wake-refund-order"), "FAKE")
                .paymentId();
        admin.update("UPDATE platform.provider_operation SET status='RESOLVED' WHERE payment_id=?", payment);
        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE payment_id=?", payment);
        admin.update("""
                UPDATE platform.payment
                SET captured_amount_minor=1000,payment_status='SUCCEEDED',latest_charge_id='ch_wake'
                WHERE id=?""", payment);
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,'ch_wake','succeeded','pi_wake',true,1000,1000,now())""", id(1), id(2), id(3), id(4), payment);
        var refunds = new RefundRepository(
                new PaymentRepositories(jdbc),
                tx,
                new ObjectMapper().findAndRegisterModules(),
                fake.clock(),
                repository);
        var refundContext = new TenantContext(
                id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:write", "refunds:read"));
        UUID refund = refunds.prepare(
                        refundContext,
                        "wake-refund",
                        new RefundRequests.Create(payment, 400, "USD", null, null, null),
                        "FAKE")
                .refundId();
        UUID task = jdbc.queryForObject("SELECT id FROM platform.recovery_task WHERE refund_id=?", UUID.class, refund);
        waiting(task);

        assertAwake(task, wake(task));
    }

    @Test
    void reconciliationSourceTaskIsMadeReady() {
        UUID task = ((ReconciliationSourceRecovery) recovery).enqueue(scope, run, "txn_recovered");
        waiting(task);

        assertAwake(task, wake(task));
    }

    @Test
    void taskOutsideTheCallersScopeOrOnADisabledCurrencyIsNotFound() {
        UUID task = paymentTask("wake-scope");
        assertThatThrownBy(() -> wake(id(99), task))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        refused(UUID.randomUUID(), ErrorCode.NOT_FOUND);
        admin.update("UPDATE platform.org_currency_enabled SET status='DISABLED' WHERE currency='USD'");
        refused(task, ErrorCode.NOT_FOUND);
    }

    @Test
    void leasedOrFinalTasksAreNotWoken() {
        UUID task = paymentTask("wake-final");
        admin.update("UPDATE platform.recovery_task SET lease_until=now()+interval '1 hour' WHERE id=?", task);
        refused(task, ErrorCode.EVIDENCE_CONFLICT);
        for (String status : new String[] {"DONE", "ESCALATED"}) {
            admin.update("UPDATE platform.recovery_task SET status=?,lease_until=NULL WHERE id=?", status, task);
            refused(task, ErrorCode.EVIDENCE_CONFLICT);
        }
        assertThat(task(task)).containsEntry("status", "ESCALATED");
    }

    @Test
    void leasedResolvedOrExpiredUncertainOperationsAreNotWoken() {
        UUID task = paymentTask("wake-operation");
        UUID operation = operationOf(task);
        waiting(task);
        admin.update(
                "UPDATE platform.provider_operation SET lease_until=now()+interval '1 hour' WHERE id=?", operation);
        refused(task, ErrorCode.EVIDENCE_CONFLICT);
        admin.update("""
                UPDATE platform.provider_operation
                SET lease_until=NULL,status='UNKNOWN',first_send_at=now()-interval '2 days',
                    retry_until=now()-interval '1 day'
                WHERE id=?""", operation);
        refused(task, ErrorCode.EVIDENCE_CONFLICT);
        admin.update("UPDATE platform.provider_operation SET retry_until=NULL WHERE id=?", operation);
        refused(task, ErrorCode.EVIDENCE_CONFLICT);

        // An uncertain operation still inside its retry horizon may be woken.
        admin.update(
                "UPDATE platform.provider_operation SET retry_until=? WHERE id=?",
                Timestamp.from(fake.clock().instant().plusSeconds(3600)),
                operation);
        assertAwake(task, wake(task));

        admin.update("UPDATE platform.provider_operation SET status='RESOLVED' WHERE id=?", operation);
        refused(task, ErrorCode.EVIDENCE_CONFLICT);
    }
}
