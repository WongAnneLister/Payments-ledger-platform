package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import com.paymentsledger.provider.ProviderScope;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins how provider refund facts are written back before that code is split into domain rules and a repository: local
 * facts settling or releasing a reservation, terminal facts that never regress, contradictions that freeze the
 * control, external refunds with their query operation, facts that can only be observed, source queries without a
 * fact, and the leased create/query writeback (submitted, finish, anchor conflicts, age escalation).
 */
class RefundFactCharacterizationIT extends PaymentCoreFixture {
    final ObjectMapper mapper = new ObjectMapper();
    TenantContext refundContext;
    ProviderScope providerScope;
    UUID paymentId;
    RefundRepository refunds;
    int keys;

    @BeforeEach
    void captured() {
        refundContext = new TenantContext(
                id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:write", "refunds:read"));
        providerScope = new ProviderScope(id(1), id(2), id(4), false);
        paymentId = repository
                .prepare(context, "payment-fact", command("fact-order"), "FAKE")
                .paymentId();
        admin.update("UPDATE platform.provider_operation SET status='RESOLVED' WHERE payment_id=?", paymentId);
        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE payment_id=?", paymentId);
        admin.update("""
                UPDATE platform.payment
                SET captured_amount_minor=1000,payment_status='SUCCEEDED',latest_charge_id='ch_fact'
                WHERE id=?""", paymentId);
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,'ch_fact','succeeded','pi_fact',true,1000,1000,now())""", id(1), id(2), id(3), id(4), paymentId);
        refunds = new RefundRepository(jdbc, tx, new ObjectMapper().findAndRegisterModules(), fake.clock(), repository);
    }

    Instant now() {
        return fake.clock().instant();
    }

    static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    RefundRepository.Intent reserve(long amount) {
        return refunds.prepare(
                refundContext,
                "refund-fact-" + ++keys,
                new RefundRequests.Create(paymentId, amount, "USD", null, null, null),
                "FAKE");
    }

    RefundFact fact(String refundId, String status, long amount) {
        return fact(refundId, "ch_fact", status, amount, "usd", null, null, null);
    }

    RefundFact fact(
            String refundId,
            String charge,
            String status,
            long amount,
            String currency,
            String bt,
            String failureReason,
            String failureBt) {
        return new RefundFact(refundId, charge, status, amount, currency, bt, failureReason, failureBt, now());
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence("req_fact", null, now(), now(), 200, null, null, null);
    }

    ProviderCallResult<RefundFact> success(RefundFact fact) {
        return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, fact, evidence());
    }

    ProviderCallResult<RefundFact> failure(ProviderCallClassification classification) {
        return new ProviderCallResult<>(classification, null, evidence());
    }

    Map<String, Object> refund(UUID id) {
        return admin.queryForMap("SELECT * FROM platform.refund WHERE id=?", id);
    }

    Map<String, Object> control() {
        return admin.queryForMap("SELECT * FROM platform.refund_control WHERE payment_id=?", paymentId);
    }

    Map<String, Object> operation(UUID id) {
        return admin.queryForMap("SELECT * FROM platform.provider_operation WHERE id=?", id);
    }

    Map<String, Object> task(UUID operation) {
        return admin.queryForMap("SELECT * FROM platform.recovery_task WHERE operation_id=?", operation);
    }

    List<Map<String, Object>> sourceObservations() {
        return admin.queryForList("""
                SELECT provider_refund_id,refund_id,payment_id,fact::text AS fact,evidence::text AS evidence
                FROM platform.refund_source_observation
                ORDER BY created_at""");
    }

    Map<String, Object> json(Object text) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(text.toString(), Map.class);
        return map;
    }

    static void rejects(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    PaymentRepository.Claim stale(PaymentRepository.Claim claim) {
        return new PaymentRepository.Claim(
                claim.operationId(),
                claim.paymentId(),
                UUID.randomUUID(),
                claim.version(),
                claim.type(),
                claim.operation(),
                claim.payment());
    }

    @Test
    void localFactsSettleTheReservationOnceAndNeverRegress() throws Exception {
        var intent = reserve(600);
        assertThat(refunds.accept(providerScope, fact("re_local", "pending", 600), evidence(), intent.refundId()))
                .isEqualTo(intent.refundId());
        var pending = refund(intent.refundId());
        assertThat(pending)
                .containsEntry("refund_status", "PENDING")
                .containsEntry("resolution_status", "KNOWN")
                .containsEntry("provider_status", "pending")
                .containsEntry("provider_refund_id", "re_local")
                .containsEntry("unresolved_since", null);
        assertThat(at(pending.get("last_provider_confirmed_at"))).isEqualTo(now());
        assertThat(control()).containsEntry("reserved_minor", 600L).containsEntry("refunded_minor", 0L);
        var task = task(intent.operationId());
        assertThat(task).containsEntry("status", "WAITING");
        assertThat(at(task.get("available_at"))).isEqualTo(now());

        var observed = sourceObservations();
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst())
                .containsEntry("provider_refund_id", "re_local")
                .containsEntry("refund_id", intent.refundId())
                .containsEntry("payment_id", paymentId);
        assertThat(json(observed.getFirst().get("fact")))
                .containsEntry("refundId", "re_local")
                .containsEntry("chargeId", "ch_fact")
                .containsEntry("status", "pending")
                .containsEntry("amountMinor", 600)
                .containsEntry("currency", "usd");
        assertThat(json(observed.getFirst().get("evidence"))).containsEntry("providerRequestId", "req_fact");

        long version = (Long) control().get("version");
        refunds.accept(
                providerScope,
                fact("re_local", "ch_fact", "succeeded", 600, "USD", "txn_re", null, null),
                evidence(),
                intent.refundId());
        assertThat(refund(intent.refundId()))
                .containsEntry("refund_status", "SUCCEEDED")
                .containsEntry("provider_status", "succeeded")
                .containsEntry("balance_transaction_id", "txn_re");
        assertThat(control())
                .containsEntry("reserved_minor", 0L)
                .containsEntry("refunded_minor", 600L)
                .containsEntry("version", version + 1);

        refunds.accept(providerScope, fact("re_local", "succeeded", 600), evidence(), intent.refundId());
        refunds.accept(providerScope, fact("re_local", "pending", 600), evidence(), intent.refundId());
        assertThat(refund(intent.refundId()))
                .as("repeated and older facts change nothing")
                .containsEntry("refund_status", "SUCCEEDED")
                .containsEntry("provider_status", "succeeded")
                .containsEntry("resolution_status", "KNOWN");
        assertThat(control()).containsEntry("refunded_minor", 600L).containsEntry("version", version + 1);
        assertThat(sourceObservations()).hasSize(4);

        refunds.accept(providerScope, fact("re_local", "failed", 600), evidence(), intent.refundId());
        assertThat(refund(intent.refundId()))
                .as("a different terminal fact is a contradiction")
                .containsEntry("refund_status", "SUCCEEDED")
                .containsEntry("resolution_status", "CONFLICT");
        assertThat(control()).containsEntry("control_state", "EXTERNAL_CONFLICT");
    }

    @Test
    void failedRefundReleasesTheHoldWithoutRefunding() {
        var intent = reserve(400);
        refunds.accept(
                providerScope,
                fact("re_failed", "ch_fact", "failed", 400, "USD", null, "lost_or_stolen_card", "txn_fail"),
                evidence(),
                intent.refundId());
        assertThat(refund(intent.refundId()))
                .containsEntry("refund_status", "FAILED")
                .containsEntry("failure_reason", "lost_or_stolen_card")
                .containsEntry("failure_balance_transaction_id", "txn_fail");
        assertThat(control()).containsEntry("reserved_minor", 0L).containsEntry("refunded_minor", 0L);

        var canceled = reserve(300);
        refunds.accept(providerScope, fact("re_canceled", "canceled", 300), evidence(), canceled.refundId());
        assertThat(refund(canceled.refundId())).containsEntry("refund_status", "CANCELED");
        assertThat(control()).containsEntry("reserved_minor", 0L);
    }

    @Test
    void unrecognizedStatusLeavesTheRefundUncertain() {
        var intent = reserve(200);
        refunds.accept(providerScope, fact("re_odd", "mystery", 200), evidence(), intent.refundId());
        var odd = refund(intent.refundId());
        assertThat(odd)
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("resolution_status", "UNKNOWN")
                .containsEntry("provider_status", "mystery");
        assertThat(at(odd.get("unresolved_since"))).isEqualTo(now());

        fake.clock().advance(Duration.ofSeconds(5));
        refunds.accept(providerScope, fact("re_odd", "requires_action", 200), evidence(), intent.refundId());
        var known = refund(intent.refundId());
        assertThat(known)
                .containsEntry("refund_status", "REQUIRES_ACTION")
                .containsEntry("resolution_status", "KNOWN")
                .containsEntry("unresolved_since", null);
    }

    @Test
    void contradictoryFactsFreezeTheControlAndKeepTheRefund() {
        var amount = reserve(600);
        refunds.accept(providerScope, fact("re_amount", "succeeded", 500), evidence(), amount.refundId());
        assertThat(refund(amount.refundId()))
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("resolution_status", "CONFLICT")
                .containsEntry("provider_refund_id", null);
        assertThat(at(refund(amount.refundId()).get("unresolved_since"))).isEqualTo(now());
        assertThat(control())
                .containsEntry("control_state", "EXTERNAL_CONFLICT")
                .containsEntry("reserved_minor", 600L);

        refunds.accept(providerScope, fact("re_amount", "succeeded", 600), evidence(), amount.refundId());
        assertThat(refund(amount.refundId()))
                .as("a refund in conflict stays in conflict")
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("resolution_status", "CONFLICT");
    }

    @Test
    void anotherChargeOrBalanceTransactionIsAContradiction() {
        var charge = reserve(100);
        refunds.accept(
                providerScope,
                fact("re_charge", "ch_other", "pending", 100, "USD", null, null, null),
                evidence(),
                charge.refundId());
        assertThat(refund(charge.refundId())).containsEntry("resolution_status", "CONFLICT");

        admin.update("UPDATE platform.refund_control SET control_state='NORMAL' WHERE payment_id=?", paymentId);
        var bt = reserve(100);
        refunds.accept(
                providerScope,
                fact("re_bt", "ch_fact", "pending", 100, "USD", "txn_a", null, null),
                evidence(),
                bt.refundId());
        refunds.accept(
                providerScope,
                fact("re_bt", "ch_fact", "succeeded", 100, "USD", "txn_b", null, null),
                evidence(),
                bt.refundId());
        assertThat(refund(bt.refundId()))
                .containsEntry("refund_status", "PENDING")
                .containsEntry("balance_transaction_id", "txn_a")
                .containsEntry("resolution_status", "CONFLICT");
    }

    @Test
    void aProviderRefundBoundToAnotherLocalRefundIsAConflict() {
        var first = reserve(100);
        var second = reserve(100);
        refunds.accept(providerScope, fact("re_shared", "pending", 100), evidence(), first.refundId());
        assertThat(refunds.accept(providerScope, fact("re_shared", "pending", 100), evidence(), second.refundId()))
                .isEqualTo(second.refundId());
        assertThat(refund(second.refundId()))
                .containsEntry("resolution_status", "CONFLICT")
                .containsEntry("provider_refund_id", null);
        assertThat(refund(first.refundId())).containsEntry("resolution_status", "KNOWN");
        assertThat(control()).containsEntry("control_state", "EXTERNAL_CONFLICT");
        assertThat(sourceObservations().getLast()).containsEntry("refund_id", second.refundId());

        assertThat(refunds.accept(providerScope, fact("re_shared", "succeeded", 100), evidence(), null))
                .as("without a local id the bound refund is used")
                .isEqualTo(first.refundId());
        assertThat(refund(first.refundId())).containsEntry("refund_status", "SUCCEEDED");
    }

    @Test
    void externalRefundsAreRecordedWithAQueryOperation() throws Exception {
        UUID external = refunds.accept(providerScope, fact("re_ext", "pending", 250), evidence(), null);
        var row = refund(external);
        assertThat(row)
                .containsEntry("payment_id", paymentId)
                .containsEntry("source", "EXTERNAL")
                .containsEntry("amount_minor", 250L)
                .containsEntry("currency", "USD")
                .containsEntry("request_fingerprint", "external:re_ext")
                .containsEntry("provider_charge_id", "ch_fact")
                .containsEntry("provider_refund_id", "re_ext")
                .containsEntry("refund_status", "PENDING")
                .containsEntry("resolution_status", "KNOWN");
        assertThat(control())
                .containsEntry("control_state", "EXTERNAL_CONFLICT")
                .containsEntry("reserved_minor", 0L)
                .containsEntry("version", 1L);

        var query = admin.queryForMap("SELECT * FROM platform.provider_operation WHERE refund_id=?", external);
        assertThat(query)
                .containsEntry("operation_target", "REFUND")
                .containsEntry("operation_type", "QUERY_REFUND")
                .containsEntry("status", "PREPARED")
                .containsEntry("request_fingerprint", "external-query:re_ext")
                .containsEntry("external_object_id", "re_ext")
                .containsEntry("operation_key", "refund_query_" + query.get("id"));
        assertThat(at(query.get("expires_at"))).isEqualTo(now().plus(Duration.ofDays(1)));
        var command = json(query.get("command"));
        assertThat(command).containsEntry("refundId", "re_ext");
        assertThat(json(mapper.writeValueAsString(command.get("scope"))))
                .containsEntry("providerAccountId", id(4).toString())
                .containsEntry("livemode", false);
        var task = task((UUID) query.get("id"));
        assertThat(task).containsEntry("status", "WAITING").containsEntry("refund_id", external);
        assertThat(at(task.get("available_at"))).isEqualTo(now());

        fake.clock().advance(Duration.ofSeconds(5));
        assertThat(refunds.accept(providerScope, fact("re_ext", "succeeded", 250), evidence(), null))
                .isEqualTo(external);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(refund(external)).containsEntry("refund_status", "SUCCEEDED");
        assertThat(control())
                .as("an external refund held nothing")
                .containsEntry("reserved_minor", 0L)
                .containsEntry("refunded_minor", 250L);
    }

    @Test
    void factsWithoutAUsablePaymentAreOnlyObserved() {
        assertThat(refunds.accept(
                        providerScope,
                        fact("re_orphan", "ch_unknown", "pending", 100, "USD", null, null, null),
                        evidence(),
                        null))
                .isNull();
        assertThat(sourceObservations().getLast())
                .containsEntry("provider_refund_id", "re_orphan")
                .containsEntry("refund_id", null)
                .containsEntry("payment_id", null);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.refund_control", Long.class))
                .isZero();

        assertThat(refunds.accept(
                        providerScope,
                        fact("re_euro", "ch_fact", "pending", 100, "EUR", null, null, null),
                        evidence(),
                        null))
                .isNull();
        assertThat(count("refund")).isZero();
        assertThat(sourceObservations().getLast())
                .containsEntry("provider_refund_id", "re_euro")
                .containsEntry("payment_id", null);
        assertThat(control())
                .as("the control is frozen before the currency is checked")
                .containsEntry("control_state", "EXTERNAL_CONFLICT");

        rejects(
                () -> refunds.accept(
                        new ProviderScope(id(1), id(2), id(4), true), fact("re_live", "pending", 1), evidence(), null),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void sourceQueriesAndLookups() throws Exception {
        refunds.recordSourceQuery(providerScope, "re_query", failure(ProviderCallClassification.NOT_FOUND));
        var observed = sourceObservations().getLast();
        assertThat(observed)
                .containsEntry("provider_refund_id", "re_query")
                .containsEntry("refund_id", null)
                .containsEntry("payment_id", null);
        assertThat(json(observed.get("fact"))).isEqualTo(Map.of("refundId", "re_query", "classification", "NOT_FOUND"));

        refunds.recordSourceQuery(providerScope, "re_found", success(fact("re_found", "pending", 100)));
        assertThat(refunds.lookup(providerScope, "re_found"))
                .as("a query with a fact is accepted like any other fact")
                .containsEntry("source", "EXTERNAL");
        assertThat(refunds.lookup(providerScope, "re_missing")).isNull();
        assertThat(refunds.paymentForCharge(providerScope, "ch_fact")).isEqualTo(paymentId);
        assertThat(refunds.paymentForCharge(providerScope, "ch_unknown")).isNull();
        var live = new ProviderScope(id(1), id(2), id(4), true);
        rejects(() -> refunds.lookup(live, "re_found"), ErrorCode.FORBIDDEN);
        rejects(() -> refunds.paymentForCharge(live, "ch_fact"), ErrorCode.FORBIDDEN);
    }

    @Test
    void submittedMovesAReservationToTheProviderOnlyWhileItIsSendable() {
        var intent = reserve(100);
        var claim = repository.claim(intent.operationId(), "FAKE");
        assertThat(refunds.submitted(claim)).isTrue();
        assertThat(refund(intent.refundId())).containsEntry("refund_status", "SUBMITTED");
        assertThat(refunds.submitted(claim)).as("a retry of the same claim").isTrue();
        assertThat(refunds.submitted(stale(claim)))
                .as("a lost lease never sends")
                .isFalse();

        var frozen = reserve(100);
        var frozenClaim = repository.claim(frozen.operationId(), "FAKE");
        admin.update(
                "UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT' WHERE payment_id=?", paymentId);
        assertThat(refunds.submitted(frozenClaim)).isFalse();
        assertThat(refund(frozen.refundId())).containsEntry("refund_status", "RESERVED");
        assertThat(operation(frozen.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "KNOWN")
                .containsEntry("lease_until", null);
    }

    @Test
    void finishWritesBackCreateResults() {
        var accepted = reserve(100);
        var acceptedClaim = repository.claim(accepted.operationId(), "FAKE");
        refunds.submitted(acceptedClaim);
        refunds.finish(acceptedClaim, success(fact("re_created", "pending", 100)), false);
        assertThat(refund(accepted.refundId()))
                .containsEntry("refund_status", "PENDING")
                .containsEntry("provider_refund_id", "re_created");
        assertThat(operation(accepted.operationId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "KNOWN")
                .containsEntry("external_object_id", "re_created")
                .containsEntry("last_provider_status", "pending");
        assertThat(task(accepted.operationId())).containsEntry("status", "WAITING");
        assertThat(admin.queryForMap(
                        "SELECT call_type,classification,refund_id,external_object_id FROM platform.provider_observation WHERE operation_id=?",
                        accepted.operationId()))
                .containsEntry("call_type", "CREATE_REFUND")
                .containsEntry("classification", "SUCCESS")
                .containsEntry("refund_id", accepted.refundId())
                .containsEntry("external_object_id", "re_created");

        var rejected = reserve(200);
        var rejectedClaim = repository.claim(rejected.operationId(), "FAKE");
        refunds.finish(rejectedClaim, failure(ProviderCallClassification.DEFINITIVE_REJECT), false);
        assertThat(refund(rejected.refundId()))
                .containsEntry("refund_status", "FAILED")
                .containsEntry("resolution_status", "KNOWN");
        assertThat(control()).containsEntry("reserved_minor", 100L);
        assertThat(operation(rejected.operationId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "KNOWN");
        assertThat(task(rejected.operationId())).containsEntry("status", "DONE");

        var timeout = reserve(300);
        var timeoutClaim = repository.claim(timeout.operationId(), "FAKE");
        refunds.finish(timeoutClaim, failure(ProviderCallClassification.TIMEOUT_UNKNOWN), false);
        var uncertain = refund(timeout.refundId());
        assertThat(uncertain).containsEntry("refund_status", "RESERVED").containsEntry("resolution_status", "UNKNOWN");
        assertThat(at(uncertain.get("unresolved_since"))).isEqualTo(now());
        assertThat(control()).containsEntry("reserved_minor", 400L);
        assertThat(operation(timeout.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "UNKNOWN");
    }

    @Test
    void finishWritesBackQueriesAndRespectsTheLease() {
        var queried = reserve(100);
        var claim = repository.claim(queried.operationId(), "FAKE");
        refunds.finish(claim, failure(ProviderCallClassification.DEFINITIVE_REJECT), true);
        assertThat(refund(queried.refundId()))
                .as("a rejected query is not a rejected refund")
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("resolution_status", "UNKNOWN");
        var op = operation(queried.operationId());
        assertThat(op).containsEntry("status", "UNKNOWN");
        assertThat(at(op.get("last_query_at"))).isEqualTo(now());
        assertThat(admin.queryForObject(
                        "SELECT call_type FROM platform.provider_observation WHERE operation_id=?",
                        String.class,
                        queried.operationId()))
                .isEqualTo("QUERY_REFUND");

        var lost = reserve(100);
        var lostClaim = repository.claim(lost.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(30));
        refunds.finish(lostClaim, success(fact("re_lost", "succeeded", 100)), false);
        assertThat(refund(lost.refundId()))
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("provider_refund_id", null);
        assertThat(operation(lost.operationId())).containsEntry("status", "SENDING");
        assertThat(admin.queryForObject(
                        "SELECT count(*) FROM platform.provider_observation WHERE operation_id=?",
                        Long.class,
                        lost.operationId()))
                .as("the provider's answer is still recorded")
                .isEqualTo(1);
    }

    @Test
    void longUncertainRefundsEscalateInsteadOfCompleting() {
        var intent = reserve(100);
        var claim = repository.claim(intent.operationId(), "FAKE");
        admin.update(
                "UPDATE platform.refund SET resolution_status='UNKNOWN',unresolved_since=? WHERE id=?",
                Timestamp.from(now().minus(Duration.ofHours(1))),
                intent.refundId());
        refunds.finish(claim, failure(ProviderCallClassification.TIMEOUT_UNKNOWN), false);
        var op = operation(intent.operationId());
        assertThat(op).containsEntry("status", "ESCALATED").containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT");
        assertThat(at(op.get("age_alert_at"))).isEqualTo(now());
        assertThat(task(intent.operationId())).containsEntry("status", "ESCALATED");
    }

    @Test
    void conflictingAnchorsFreezeTheControlAndEscalateUnderTheLease() {
        var intent = reserve(100);
        var claim = repository.claim(intent.operationId(), "FAKE");
        refunds.conflictingAnchors(stale(claim));
        assertThat(control()).containsEntry("control_state", "NORMAL");
        assertThat(operation(intent.operationId())).containsEntry("status", "SENDING");

        refunds.conflictingAnchors(claim);
        assertThat(control()).containsEntry("control_state", "EXTERNAL_CONFLICT");
        var conflicted = refund(intent.refundId());
        assertThat(conflicted).containsEntry("resolution_status", "CONFLICT");
        assertThat(at(conflicted.get("unresolved_since"))).isEqualTo(now());
        assertThat(operation(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "CONFLICTING_REFUND_ANCHORS")
                .containsEntry("resolution", "CONFLICT");
        assertThat(task(intent.operationId())).containsEntry("status", "ESCALATED");
        assertThat(control()).as("the hold is retained").containsEntry("reserved_minor", 100L);
    }
}
