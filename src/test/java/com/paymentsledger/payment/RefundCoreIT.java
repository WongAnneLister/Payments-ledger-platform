package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.provider.ProviderModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RefundCoreIT extends PaymentCoreFixture {
    TenantContext refundContext;
    UUID captured;

    @BeforeEach
    void capture() {
        refundContext = new TenantContext(
                context.organizationId(),
                context.environmentId(),
                context.applicationId(),
                context.allowedProviderAccountId(),
                Set.of("USD"),
                false,
                Set.of("refunds:read", "refunds:write"));
        var intent = repository.prepare(context, "payment-original", command("refund-order"), "FAKE");
        captured = intent.paymentId();
        admin.update("""
                UPDATE platform.payment
                SET captured_amount_minor=1000,payment_status='SUCCEEDED',latest_charge_id='ch_refund'
                WHERE id=?""", captured);
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,'ch_refund','succeeded','pi_refund',true,1000,1000,now())""", id(1), id(2), id(3), id(4), captured);
    }

    @Test
    void refundSchemaExistsAndNormalControlEnforcesIndividualNonnegativeAmounts() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('platform.refund') IS NOT NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('platform.refund_control') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    RefundRepository refunds() {
        return new RefundRepository(
                new PaymentRepositories(jdbc), tx, new ObjectMapper().findAndRegisterModules(), fake.clock());
    }

    RefundRequests.Create request(long amount, String key) {
        return new RefundRequests.Create(captured, amount, "USD", null, null, key);
    }

    @Test
    void competingSixHundredReservationsHaveOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> reserveAtBarrier(barrier, "refund-key-a"));
            var b = workers.submit(() -> reserveAtBarrier(barrier, "refund-key-b"));
            assertThat(a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?", Long.class, captured))
                .isEqualTo(600);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.provider_operation
                        WHERE operation_target='REFUND'""", Long.class)).isEqualTo(1);
    }

    int reserveAtBarrier(CyclicBarrier barrier, String key) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            refunds().prepare(refundContext, key, request(600, null), "FAKE");
            return 1;
        } catch (com.paymentsledger.common.error.ApiException e) {
            assertThat(e.errorCode()).isEqualTo(com.paymentsledger.common.error.ErrorCode.REFUND_LIMIT_EXCEEDED);
            return 0;
        }
    }

    @Test
    void idempotencyAndClientKeyNeverReserveTwiceAndRejectChangedPayload() {
        var r = refunds();
        var first = r.prepare(refundContext, "refund-key-a", request(600, "merchant-1"), "FAKE");
        assertThat(r.prepare(refundContext, "refund-key-a", request(600, "merchant-1"), "FAKE")
                        .replay())
                .isTrue();
        assertThat(r.prepare(refundContext, "refund-key-b", request(600, "merchant-1"), "FAKE")
                        .refundId())
                .isEqualTo(first.refundId());
        assertThatThrownBy(() -> r.prepare(refundContext, "refund-key-a", request(500, "merchant-1"), "FAKE"))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class);
        assertThat(count("refund")).isEqualTo(1);
    }

    @Test
    void pendingHoldsSuccessMovesOnceAndOlderPendingCannotRewind() {
        var r = refunds();
        var first = r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
        r.accept(scope, fact("re_local", "pending", 600), evidence(), first.refundId());
        assertThat(r.read(refundContext, first.refundId())).containsEntry("refundStatus", "PENDING");
        r.accept(scope, fact("re_local", "succeeded", 600), evidence(), first.refundId());
        r.accept(scope, fact("re_local", "succeeded", 600), evidence(), first.refundId());
        r.accept(scope, fact("re_local", "pending", 600), evidence(), first.refundId());
        assertThat(r.read(refundContext, first.refundId()))
                .containsEntry("refundStatus", "SUCCEEDED")
                .containsEntry("financialStatus", "PENDING_EVIDENCE");
        assertThat(jdbc.queryForMap("""
                        SELECT reserved_minor,refunded_minor
                        FROM platform.refund_control
                        WHERE payment_id=?""", captured))
                .containsEntry("reserved_minor", 0L)
                .containsEntry("refunded_minor", 600L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE refund_id=?", Long.class, first.refundId()))
                .isEqualTo(1);
    }

    @Test
    void externalOverlapIsRetainedAndFreezesNewReservations() {
        var r = refunds();
        r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
        UUID external = r.accept(scope, fact("re_dashboard", "succeeded", 600), evidence(), null);
        assertThat(external).isNotNull();
        assertThat(jdbc.queryForMap("""
                        SELECT control_state,reserved_minor,refunded_minor
                        FROM platform.refund_control
                        WHERE payment_id=?""", captured))
                .containsEntry("control_state", "EXTERNAL_CONFLICT")
                .containsEntry("reserved_minor", 600L)
                .containsEntry("refunded_minor", 600L);
        assertThatThrownBy(() -> r.prepare(refundContext, "refund-key-b", request(1, null), "FAKE"))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class);
    }

    @Test
    void rollbackNeverLeavesReservationOrOutbox() {
        var r = refunds();
        new org.springframework.transaction.support.TransactionTemplate(tx).execute(status -> {
            r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
            status.setRollbackOnly();
            return null;
        });
        assertThat(count("refund")).isZero();
        assertThat(count("refund_control")).isZero();
    }

    RefundFact fact(String id, String status, long amount) {
        return new RefundFact(
                id,
                "ch_refund",
                status,
                amount,
                "USD",
                null,
                null,
                null,
                fake.clock().instant());
    }

    com.paymentsledger.provider.ProviderEvidence evidence() {
        return new com.paymentsledger.provider.ProviderEvidence(
                "req", null, fake.clock().instant(), fake.clock().instant(), 200, null, null, null);
    }

    @Test
    void auditedUnfreezeRequiresResolvedControlAndIsIdempotent() {
        var r = refunds();
        r.accept(scope, fact("re_external", "succeeded", 300), evidence(), null);
        UUID observation = jdbc.queryForObject("SELECT id FROM platform.refund_source_observation LIMIT 1", UUID.class);
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, captured);
        r.resolveExternalConflict(scope, captured, version, observation, "audit-confirm-distinct");
        r.resolveExternalConflict(scope, captured, version, observation, "audit-confirm-distinct");
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?", String.class, captured))
                .isEqualTo("NORMAL");
        assertThat(count("refund_control_audit")).isEqualTo(1);
    }

    @Test
    void ambiguousOverlapCannotBeUnfrozen() {
        var r = refunds();
        r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
        r.accept(scope, fact("re_external", "succeeded", 300), evidence(), null);
        UUID observation = jdbc.queryForObject("SELECT id FROM platform.refund_source_observation LIMIT 1", UUID.class);
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, captured);
        assertThatThrownBy(() -> r.resolveExternalConflict(scope, captured, version, observation, "audit-invalid"))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?", Long.class, captured))
                .isEqualTo(600);
    }

    @Test
    void runtimeCannotForgeEvidenceOrScopeAndNormalAmountsAreSeparatelyNonnegative() {
        var r = refunds();
        var intent = r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE platform.refund_control SET reserved_minor=-1 WHERE payment_id=?", captured))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.refund SET amount_minor=1 WHERE id=?", intent.refundId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE platform.provider_operation SET payment_id=?,refund_id=null WHERE id=?",
                        captured,
                        intent.operationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        r.accept(scope, fact("re_local", "succeeded", 600), evidence(), intent.refundId());
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.refund_source_observation SET fact='{}'::jsonb"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE platform.outbox_event SET payload='{}' WHERE refund_id=?", intent.refundId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var other = new TenantContext(
                context.organizationId(),
                context.environmentId(),
                id(99),
                context.allowedProviderAccountId(),
                Set.of("USD"),
                false,
                Set.of("refunds:read"));
        assertThatThrownBy(() -> r.read(other, intent.refundId()))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class);
    }

    @Test
    void sourceObservationCannotClaimAnotherProviderScopePayment() {
        var r = refunds();
        r.prepare(refundContext, "refund-key-a", request(600, null), "FAKE");
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','different-scope',false)""", id(44), id(1), id(2));
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,
                            provider_refund_id,payment_id,fact,evidence)
                        VALUES (?,?,?,'re_forged',?,'{}','{}')""", id(1), id(2), id(44), captured))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void historicalUnmatchedObservationCanBeReconciledAfterTheExactChargeArrives() {
        var r = refunds();
        admin.update("DELETE FROM platform.payment_attempt WHERE payment_id=?", captured);
        assertThat(r.accept(scope, fact("re_delayed", "succeeded", 300), evidence(), null))
                .isNull();
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,'ch_refund','succeeded','pi_refund',true,1000,1000,now())""", id(1), id(2), id(3), id(4), captured);
        UUID refund = r.accept(scope, fact("re_delayed", "succeeded", 300), evidence(), null);
        UUID observation = jdbc.queryForObject(
                "SELECT id FROM platform.refund_source_observation WHERE refund_id=? LIMIT 1", UUID.class, refund);
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, captured);
        r.resolveExternalConflict(scope, captured, version, observation, "audit-charge-arrived");
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?", String.class, captured))
                .isEqualTo("NORMAL");
    }

    @Test
    void repeatedFailureAndCancellationReleaseOnceAndDoNotCollideNotifications() {
        var r = refunds();
        var failed = r.prepare(refundContext, "refund-fail-key", request(600, null), "FAKE");
        r.accept(scope, fact("re_failed", "failed", 600), evidence(), failed.refundId());
        r.accept(scope, fact("re_failed", "failed", 600), evidence(), failed.refundId());
        var canceled = r.prepare(refundContext, "refund-cancel-key", request(600, null), "FAKE");
        r.accept(scope, fact("re_canceled", "canceled", 600), evidence(), canceled.refundId());
        r.accept(scope, fact("re_canceled", "canceled", 600), evidence(), canceled.refundId());
        assertThat(jdbc.queryForMap("""
                        SELECT reserved_minor,refunded_minor
                        FROM platform.refund_control
                        WHERE payment_id=?""", captured))
                .containsEntry("reserved_minor", 0L)
                .containsEntry("refunded_minor", 0L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE refund_id IS NOT NULL", Long.class))
                .isEqualTo(2);
    }

    @Test
    void twoSuccessfulRefundsOnSamePaymentHaveSeparateTerminalNotificationIdentities() {
        var r = refunds();
        var a = r.prepare(refundContext, "refund-first-key", request(400, null), "FAKE");
        var b = r.prepare(refundContext, "refund-second-key", request(400, null), "FAKE");
        r.accept(scope, fact("re_first", "succeeded", 400), evidence(), a.refundId());
        r.accept(scope, fact("re_second", "succeeded", 400), evidence(), b.refundId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='refund.succeeded'", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                        SELECT count(DISTINCT business_key)
                        FROM platform.outbox_event
                        WHERE event_type='refund.succeeded'""", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        SELECT reserved_minor,refunded_minor
                        FROM platform.refund_control
                        WHERE payment_id=?""", captured))
                .containsEntry("reserved_minor", 0L)
                .containsEntry("refunded_minor", 800L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "succeeded,failed,,,,",
        "failed,canceled,,,,",
        "canceled,succeeded,,,,",
        "pending,failed,txn_old,txn_new,,",
        "pending,failed,,,txn_return_old,txn_return_new"
    })
    void contradictoryHistoricalTerminalOrBtEvidenceCannotBeUnfrozen(
            String oldStatus, String newStatus, String oldDebit, String newDebit, String oldReturn, String newReturn) {
        var r = refunds();
        UUID refund = acceptAfterDelayedCharge(r, oldStatus, newStatus, oldDebit, newDebit, oldReturn, newReturn);
        UUID observation = jdbc.queryForObject(
                "SELECT id FROM platform.refund_source_observation WHERE refund_id=? LIMIT 1", UUID.class, refund);
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, captured);
        assertThatThrownBy(
                        () -> r.resolveExternalConflict(scope, captured, version, observation, "audit-contradiction"))
                .isInstanceOf(com.paymentsledger.common.error.ApiException.class)
                .extracting(e -> ((com.paymentsledger.common.error.ApiException) e).errorCode())
                .isEqualTo(com.paymentsledger.common.error.ErrorCode.EVIDENCE_CONFLICT);
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?", String.class, captured))
                .isEqualTo("EXTERNAL_CONFLICT");
        assertThat(count("refund_control_audit")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pending", "requires_action"})
    void olderNonterminalObservationAllowsTerminalAndBtEnrichment(String oldStatus) {
        var r = refunds();
        UUID refund = acceptAfterDelayedCharge(r, oldStatus, "failed", null, "txn_debit", null, "txn_return");
        UUID observation = jdbc.queryForObject(
                "SELECT id FROM platform.refund_source_observation WHERE refund_id=? LIMIT 1", UUID.class, refund);
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, captured);
        r.resolveExternalConflict(scope, captured, version, observation, "audit-enrichment");
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?", String.class, captured))
                .isEqualTo("NORMAL");
    }

    UUID acceptAfterDelayedCharge(
            RefundRepository r,
            String oldStatus,
            String newStatus,
            String oldDebit,
            String newDebit,
            String oldReturn,
            String newReturn) {
        admin.update("DELETE FROM platform.payment_attempt WHERE payment_id=?", captured);
        assertThat(r.accept(
                        scope,
                        new RefundFact(
                                "re_late_dependency",
                                "ch_refund",
                                oldStatus,
                                300,
                                "USD",
                                oldDebit,
                                null,
                                oldReturn,
                                fake.clock().instant()),
                        evidence(),
                        null))
                .isNull();
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,'ch_refund','succeeded','pi_refund',true,1000,1000,now())""", id(1), id(2), id(3), id(4), captured);
        return r.accept(
                scope,
                new RefundFact(
                        "re_late_dependency",
                        "ch_refund",
                        newStatus,
                        300,
                        "USD",
                        newDebit,
                        null,
                        newReturn,
                        fake.clock().instant()),
                evidence(),
                null);
    }
}
