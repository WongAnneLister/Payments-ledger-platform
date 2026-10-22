package com.paymentsledger.payment;

import com.paymentsledger.common.error.*;
import com.paymentsledger.payment.domain.RecoveryWakeRules;
import com.paymentsledger.payment.domain.RecoveryWakeRules.Operation;
import com.paymentsledger.payment.domain.RecoveryWakeRules.Task;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.payment.repository.RecoveryTaskRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow manual wake owner: preserve existing operation identity, horizon and worker lock order.
 */
@Service
public class RecoveryTaskOperations {
    private final RecoveryTaskRepository tasks;
    private final Clock clock;

    public RecoveryTaskOperations(PaymentRepositories store, Clock clock) {
        this.tasks = store.recoveryTasks();
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> wake(UUID organization, UUID environment, UUID id) {
        Task initial = tasks.task(organization, environment, id, false).orElseThrow(RecoveryTaskOperations::notFound);
        if (!tasks.activeTestAccount(organization, environment, initial.providerAccountId(), initial.currency()))
            throw notFound();
        Operation op = null;
        Task task;
        if (initial.operationId() != null) {
            UUID parent = initial.paymentId();
            if (parent == null)
                parent = tasks.refundPayment(organization, environment, initial.refundId())
                        .orElseThrow(RecoveryTaskOperations::notFound);
            found(tasks.lockPayment(organization, environment, parent));
            if (initial.refundId() != null) found(tasks.lockRefund(organization, environment, initial.refundId()));
            op = tasks.lockOperation(organization, environment, initial.operationId())
                    .orElseThrow(RecoveryTaskOperations::notFound);
            task = tasks.task(organization, environment, id, true).orElseThrow(RecoveryTaskOperations::notFound);
        } else {
            // ReconciliationSourceRecovery.link locks task before source BT. Keep that order here.
            task = tasks.task(organization, environment, id, true).orElseThrow(RecoveryTaskOperations::notFound);
            found(tasks.lockBalanceTransaction(organization, environment, initial.sourceBtId()));
        }
        if (RecoveryWakeRules.taskRefused(task, clock.instant())) throw conflict();
        if (op != null && RecoveryWakeRules.operationRefused(op, clock.instant())) throw conflict();
        tasks.ready(id, clock.instant());
        return Map.of(
                "taskId",
                id.toString(),
                "status",
                "PENDING",
                "nextScheduledAt",
                clock.instant().toString(),
                "note",
                "Existing recovery obligation awakened; no provider request sent");
    }

    private static void found(boolean locked) {
        if (!locked) throw notFound();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Recovery task not found in caller scope");
    }

    private static ApiException conflict() {
        return new ApiException(
                ErrorCode.EVIDENCE_CONFLICT, "Task is leased, final or has an uncertain expired operation");
    }
}
