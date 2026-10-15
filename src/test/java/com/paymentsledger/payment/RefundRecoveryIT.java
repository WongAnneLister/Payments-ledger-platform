package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class RefundRecoveryIT extends PaymentCoreFixture {
    RefundRepository refunds;
    RefundExecutor refundExecutor;
    OperationRecoveryCoordinator coordinator;
    UUID paymentId;
    TenantContext refundContext;
    int creates, queries;
    ProviderCallResult<RefundFact> response;

    @BeforeEach
    void refundSetup() {
        refundContext = new TenantContext(
                id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:write", "refunds:read"));
        paymentId = repository
                .prepare(context, "payment-original", command("refund-order"), "FAKE")
                .paymentId();
        admin.update("UPDATE platform.provider_operation SET status='RESOLVED' WHERE payment_id=?", paymentId);
        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE payment_id=?", paymentId);
        admin.update(
                "UPDATE platform.payment SET captured_amount_minor=1000,payment_status='SUCCEEDED',latest_charge_id='ch_refund' WHERE id=?",
                paymentId);
        admin.update(
                "INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,captured,amount_minor,amount_captured_minor,fetched_at) VALUES (?,?,?,?,'USD',?,'ch_refund','succeeded','pi_refund',true,1000,1000,now())",
                id(1),
                id(2),
                id(3),
                id(4),
                paymentId);
        refunds = new RefundRepository(jdbc, tx, new ObjectMapper().findAndRegisterModules(), fake.clock(), repository);
        RefundPort port = new RefundPort() {
            public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand c) {
                PaymentRepository.outsideTransaction();
                creates++;
                return response;
            }

            public ProviderCallResult<RefundFact> queryRefund(ProviderScope s, String id) {
                PaymentRepository.outsideTransaction();
                queries++;
                return response;
            }
        };
        refundExecutor = new RefundExecutor(repository, refunds, port, "FAKE");
        coordinator = new OperationRecoveryCoordinator(
                repository,
                executor,
                refundExecutor,
                new RecoveryPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofDays(1),
                        Duration.ofDays(1),
                        0,
                        Duration.ofHours(1),
                        Duration.ZERO));
    }

    RefundRepository.Intent reserve() {
        return refunds.prepare(
                refundContext,
                "refund-request",
                new RefundRequests.Create(paymentId, 600, "USD", null, null, null),
                "FAKE");
    }

    ProviderCallResult<RefundFact> result(String state) {
        return new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new RefundFact(
                        "re_confirmed",
                        "ch_refund",
                        state,
                        600,
                        "usd",
                        null,
                        null,
                        null,
                        fake.clock().instant()),
                evidence());
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence(
                "req", null, fake.clock().instant(), fake.clock().instant(), 200, null, null, null);
    }

    long held() {
        return jdbc.queryForObject(
                "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?", Long.class, paymentId);
    }

    @Test
    void timeoutHoldsAndRetryUsesSameOperationThenKnownIdQueries() {
        var intent = reserve();
        response = new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
        refundExecutor.execute(intent.operationId());
        assertThat(held()).isEqualTo(600);
        assertThat(refunds.read(refundContext, intent.refundId())).containsEntry("resolutionStatus", "UNKNOWN");
        var key = repository.operation(intent.operationId()).get("operation_key");
        fake.clock().advance(Duration.ofSeconds(6));
        response = result("pending");
        coordinator.recoverDue(10);
        assertThat(creates).isEqualTo(2);
        assertThat(held()).isEqualTo(600);
        assertThat(repository.operation(intent.operationId()).get("operation_key"))
                .isEqualTo(key);
        fake.clock().advance(Duration.ofSeconds(16));
        response = result("succeeded");
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(1);
        assertThat(creates).isEqualTo(2);
        assertThat(held()).isZero();
    }

    @Test
    void staleWorkerOnlyAppendsObservationAndRecoveryQueriesItsAnchor() {
        var intent = reserve();
        var stale = repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        refunds.finish(stale, result("succeeded"), false);
        assertThat(held()).isEqualTo(600);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "SENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.provider_observation WHERE refund_id=?",
                        Long.class,
                        intent.refundId()))
                .isEqualTo(1);
        response = result("succeeded");
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(1);
        assertThat(creates).isZero();
        assertThat(held()).isZero();
    }

    @Test
    void expiredKeyEscalatesWithoutSendingAndKeepsHold() {
        var intent = reserve();
        repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofDays(2));
        coordinator.recoverDue(10);
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "IDEMPOTENCY_WINDOW_EXPIRED");
        assertThat(creates).isZero();
        assertThat(held()).isEqualTo(600);
    }

    @Test
    void leaseLostBeforeDispatchCannotIssueProviderCall() {
        var intent = reserve();
        var stale = repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        response = result("succeeded");
        refundExecutor.executeClaim(stale);
        assertThat(creates).isZero();
        assertThat(held()).isEqualTo(600);
    }

    @Test
    void webhookBetweenClaimAndDispatchPreventsCreate() {
        var intent = reserve();
        var claim = repository.claim(intent.operationId(), "FAKE");
        refunds.accept(scope, result("pending").payload(), evidence(), intent.refundId());
        response = result("succeeded");
        refundExecutor.executeClaim(claim);
        assertThat(creates).isZero();
        fake.clock().advance(Duration.ofSeconds(6));
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(1);
        assertThat(held()).isZero();
    }

    @Test
    void externalConflictStopsAnAlreadyPreparedRequest() {
        var intent = reserve();
        refunds.accept(
                scope,
                new RefundFact(
                        "re_external",
                        "ch_refund",
                        "succeeded",
                        500,
                        "usd",
                        null,
                        null,
                        null,
                        fake.clock().instant()),
                evidence(),
                null);
        response = result("succeeded");
        refundExecutor.execute(intent.operationId());
        assertThat(creates).isZero();
        assertThat(held()).isEqualTo(600);
    }

    @Test
    void anchoredUnknownEscalatesAfterAgeButStillQueriesBeforeEscalating() {
        var intent = reserve();
        response = result("pending");
        refundExecutor.execute(intent.operationId());
        response = new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
        fake.clock().advance(Duration.ofSeconds(6));
        coordinator.recoverDue(10);
        fake.clock().advance(Duration.ofHours(2));
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(2);
        assertThat(held()).isEqualTo(600);
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT");
    }

    @Test
    void l2ReopensTerminalRefundWhoseFinancialEvidenceIsMissing() {
        var intent = reserve();
        response = result("succeeded");
        refundExecutor.execute(intent.operationId());
        assertThat(held()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.recovery_task WHERE operation_id=?",
                        String.class,
                        intent.operationId()))
                .isEqualTo("DONE");
        coordinator.scanL2(10);
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(1);
    }

    @Test
    void externalPendingUsesSharedQueryOnlyRecoveryEvenWithoutAnotherWebhook() {
        UUID external = refunds.accept(scope, result("pending").payload(), evidence(), null);
        response = result("succeeded");
        coordinator.scanL2(10);
        coordinator.recoverDue(10);
        assertThat(queries).isEqualTo(1);
        assertThat(creates).isZero();
        assertThat(refunds.read(refundContext, external)).containsEntry("refundStatus", "SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.provider_operation WHERE refund_id=? AND operation_type='QUERY_REFUND'",
                        Long.class,
                        external))
                .isEqualTo(1);
    }

    @Test
    void conflictingStaleSuccessAnchorsFreezeControlAndRetainTheEntireHold() {
        var intent = reserve();
        var stale = repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        appendConflictingSuccesses(stale);
        assertThat(held()).isEqualTo(600);
        coordinator.recoverDue(10);
        assertThat(refunds.read(refundContext, intent.refundId()))
                .containsEntry("resolutionStatus", "CONFLICT")
                .containsEntry("controlState", "EXTERNAL_CONFLICT");
        assertThat(held()).isEqualTo(600);
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("resolution", "CONFLICT")
                .containsEntry("escalation_reason", "CONFLICTING_REFUND_ANCHORS");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.recovery_task WHERE operation_id=?",
                        String.class,
                        intent.operationId()))
                .isEqualTo("ESCALATED");
        assertThatThrownBy(() -> refunds.prepare(
                        refundContext,
                        "refund-new-request",
                        new RefundRequests.Create(paymentId, 400, "USD", null, null, null),
                        "FAKE"))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class)
                .extracting(e -> ((com.paymentsledger.common.error.ApiException) e).errorCode())
                .isEqualTo(com.paymentsledger.common.error.ErrorCode.EVIDENCE_CONFLICT);
        assertThat(creates).isZero();
        assertThat(queries).isZero();
    }

    @Test
    void staleLeaseCannotFreezeControlWhenConflictingAnchorsAreObserved() {
        var intent = reserve();
        var stale = repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        appendConflictingSuccesses(stale);
        var current = repository.claimRecovery(intent.operationId(), "FAKE", true);
        assertThat(current).isNotNull();
        var controlBefore = jdbc.queryForMap("SELECT * FROM platform.refund_control WHERE payment_id=?", paymentId);
        refundExecutor.recover(stale);
        assertThat(jdbc.queryForMap("SELECT * FROM platform.refund_control WHERE payment_id=?", paymentId))
                .isEqualTo(controlBefore);
        assertThat(refunds.read(refundContext, intent.refundId()))
                .containsEntry("resolutionStatus", "KNOWN")
                .containsEntry("controlState", "NORMAL");
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "SENDING")
                .containsEntry("lease_token", current.token());
        assertThat(held()).isEqualTo(600);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.provider_observation WHERE refund_id=?",
                        Long.class,
                        intent.refundId()))
                .isEqualTo(2);
        assertThat(creates).isZero();
        assertThat(queries).isZero();
    }

    void appendConflictingSuccesses(PaymentRepository.Claim stale) {
        for (String providerRefund : List.of("re_conflicting_a", "re_conflicting_b"))
            refunds.finish(
                    stale,
                    new ProviderCallResult<>(
                            ProviderCallClassification.SUCCESS,
                            new RefundFact(
                                    providerRefund,
                                    "ch_refund",
                                    "succeeded",
                                    600,
                                    "usd",
                                    null,
                                    null,
                                    null,
                                    fake.clock().instant()),
                            evidence()),
                    false);
    }

    @Test
    void anchorConflictFreezeAndEscalationRollbackTogether() {
        var intent = reserve();
        var stale = repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        appendConflictingSuccesses(stale);
        var current = repository.claimRecovery(intent.operationId(), "FAKE", true);
        new org.springframework.transaction.support.TransactionTemplate(tx).execute(status -> {
            refunds.conflictingAnchors(current);
            status.setRollbackOnly();
            return null;
        });
        assertThat(refunds.read(refundContext, intent.refundId()))
                .containsEntry("resolutionStatus", "KNOWN")
                .containsEntry("controlState", "NORMAL");
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "SENDING")
                .containsEntry("lease_token", current.token());
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.recovery_task WHERE operation_id=?",
                        String.class,
                        intent.operationId()))
                .isEqualTo("RUNNING");
        assertThat(held()).isEqualTo(600);
    }
}
