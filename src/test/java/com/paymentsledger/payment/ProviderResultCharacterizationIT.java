package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionFact;
import com.paymentsledger.provider.ProviderModels.CheckoutSessionStatus;
import com.paymentsledger.provider.ProviderModels.CreateCheckoutSessionCommand;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins how provider results are written back before that code is split into domain rules and a repository: redacted
 * observations, every finishCreate branch, the remote-fact recheck before a create, ownership loss, cancellation of an
 * unsent create, cancel and query merges, session anchors, recovery queries with age escalation, and provider scope
 * checks.
 */
class ProviderResultCharacterizationIT extends PaymentCoreFixture {
    final ObjectMapper mapper = new ObjectMapper();

    Instant now() {
        return fake.clock().instant();
    }

    PaymentRepository.Claim claimed(String order) {
        var intent = repository.prepare(context, "result-" + order, command(order), "FAKE");
        return repository.claim(intent.operationId(), "FAKE");
    }

    Map<String, Object> payment(UUID id) {
        return admin.queryForMap("SELECT * FROM platform.payment WHERE id=?", id);
    }

    Map<String, Object> operation(UUID id) {
        return admin.queryForMap("SELECT * FROM platform.provider_operation WHERE id=?", id);
    }

    Map<String, Object> task(UUID operation) {
        return admin.queryForMap("SELECT * FROM platform.recovery_task WHERE operation_id=?", operation);
    }

    List<Map<String, Object>> observations(UUID operation) {
        return admin.queryForList("""
                SELECT call_type,classification,external_object_id,lease_token,payment_id,refund_id,
                    evidence::text AS evidence,fact::text AS fact
                FROM platform.provider_observation
                WHERE operation_id=?
                ORDER BY id""", operation);
    }

    static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> json(Object text) throws Exception {
        return mapper.readValue(text.toString(), Map.class);
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence("req_result", "key_result", now(), now(), 200, null, "a".repeat(64), "v1");
    }

    CheckoutSessionFact session(
            UUID payment, String id, CheckoutSessionStatus status, String hostedUrl, String paymentIntent) {
        return new CheckoutSessionFact(
                id,
                status,
                hostedUrl,
                paymentIntent,
                "unpaid",
                now().plus(Duration.ofHours(1)),
                Map.of("payment_id", payment.toString()));
    }

    ProviderCallResult<CheckoutSessionFact> success(CheckoutSessionFact fact) {
        return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, fact, evidence());
    }

    ProviderCallResult<CheckoutSessionFact> failure(ProviderCallClassification classification) {
        return new ProviderCallResult<>(classification, null, evidence());
    }

    PaymentRepository.Claim withToken(PaymentRepository.Claim claim, UUID token) {
        return new PaymentRepository.Claim(
                claim.operationId(),
                claim.paymentId(),
                token,
                claim.version(),
                claim.type(),
                claim.operation(),
                claim.payment());
    }

    void assertScheduled(UUID operation, String status, int attempts) {
        var task = task(operation);
        assertThat(task)
                .containsEntry("status", status)
                .containsEntry("lease_token", null)
                .containsEntry("lease_until", null);
        assertThat(at(task.get("available_at")))
                .isEqualTo(now().plus(repository.recoveryPolicy().delay(operation, attempts)));
    }

    @Test
    void createSuccessRecordsTheSessionAndAResolvedOperation() throws Exception {
        var claim = claimed("open");
        var fact = session(claim.paymentId(), "cs_open", CheckoutSessionStatus.OPEN, "https://pay.test/open", null);
        repository.finishCreate(claim, success(fact));

        assertThat(payment(claim.paymentId()))
                .containsEntry("payment_status", "AWAITING_CUSTOMER")
                .containsEntry("provider_status", "open")
                .containsEntry("resolution_status", "KNOWN")
                .containsEntry("checkout_session_id", "cs_open")
                .containsEntry("hosted_url", "https://pay.test/open")
                .containsEntry("unresolved_since", null);
        assertThat(at(payment(claim.paymentId()).get("last_provider_confirmed_at")))
                .isEqualTo(now());
        var op = operation(claim.operationId());
        assertThat(op)
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "KNOWN")
                .containsEntry("external_object_id", "cs_open")
                .containsEntry("last_provider_status", "open")
                .containsEntry("lease_until", null)
                .containsEntry("version", claim.version() + 1);
        assertScheduled(claim.operationId(), "WAITING", 1);

        var observed = observations(claim.operationId());
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst())
                .containsEntry("call_type", "CREATE_SESSION")
                .containsEntry("classification", "SUCCESS")
                .containsEntry("external_object_id", "cs_open")
                .containsEntry("lease_token", claim.token())
                .containsEntry("payment_id", claim.paymentId())
                .containsEntry("refund_id", null);
        assertThat(json(observed.getFirst().get("fact")))
                .as("only project-owned fields; never the hosted URL or metadata")
                .isEqualTo(Map.of(
                        "sessionId", "cs_open", "status", "OPEN", "paymentStatus", "unpaid", "paymentIntentId", ""));
        assertThat(json(observed.getFirst().get("evidence")))
                .containsEntry("providerRequestId", "req_result")
                .containsEntry("idempotencyKeyUsed", "key_result")
                .containsEntry("httpStatus", 200);

        var complete = claimed("complete");
        repository.finishCreate(
                complete,
                success(session(complete.paymentId(), "cs_complete", CheckoutSessionStatus.COMPLETE, null, "pi_c")));
        assertThat(payment(complete.paymentId()))
                .containsEntry("payment_status", "PROCESSING")
                .containsEntry("provider_status", "complete")
                .containsEntry("payment_intent_id", "pi_c");

        var settled = claimed("settled");
        admin.update("UPDATE platform.payment SET payment_status='SUCCEEDED' WHERE id=?", settled.paymentId());
        repository.finishCreate(
                settled,
                success(session(settled.paymentId(), "cs_settled", CheckoutSessionStatus.OPEN, "https://p", null)));
        assertThat(payment(settled.paymentId()))
                .as("a terminal status is never moved back by a create result")
                .containsEntry("payment_status", "SUCCEEDED");
    }

    @Test
    void realProviderSessionResolvesTheCreate() {
        var claim = claimed("fake");
        var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
        var result = fake.createSession(command);
        repository.finishCreate(claim, result);
        assertThat(payment(claim.paymentId()))
                .containsEntry("payment_status", "AWAITING_CUSTOMER")
                .containsEntry("checkout_session_id", result.payload().sessionId());
        assertThat(operation(claim.operationId())).containsEntry("status", "RESOLVED");
    }

    @Test
    void definitiveRejectCancelsOnlyWhenNothingRemoteExists() {
        var clean = claimed("reject-clean");
        repository.finishCreate(clean, failure(ProviderCallClassification.DEFINITIVE_REJECT));
        assertThat(payment(clean.paymentId()))
                .containsEntry("payment_status", "CANCELED")
                .containsEntry("resolution_status", "KNOWN");
        assertThat(operation(clean.operationId()))
                .containsEntry("status", "ACKNOWLEDGED")
                .containsEntry("resolution", "KNOWN");
        assertScheduled(clean.operationId(), "DONE", 1);
        assertThat(observations(clean.operationId()).getFirst())
                .containsEntry("classification", "DEFINITIVE_REJECT")
                .containsEntry("external_object_id", null)
                .containsEntry("fact", null);

        var traced = claimed("reject-traced");
        admin.update("UPDATE platform.payment SET checkout_session_id='cs_trace' WHERE id=?", traced.paymentId());
        repository.finishCreate(traced, failure(ProviderCallClassification.DEFINITIVE_REJECT));
        var p = payment(traced.paymentId());
        assertThat(p).containsEntry("payment_status", "PROCESSING").containsEntry("resolution_status", "CONFLICT");
        assertThat(at(p.get("unresolved_since"))).isEqualTo(now());
        assertThat(operation(traced.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "CONFLICT");
        assertScheduled(traced.operationId(), "WAITING", 1);
    }

    @Test
    void unusableResultsLeaveThePaymentUncertain() {
        var timeout = claimed("timeout");
        repository.finishCreate(timeout, failure(ProviderCallClassification.TIMEOUT_UNKNOWN));
        var p = payment(timeout.paymentId());
        assertThat(p).containsEntry("payment_status", "PROCESSING").containsEntry("resolution_status", "UNKNOWN");
        assertThat(at(p.get("unresolved_since"))).isEqualTo(now());
        assertThat(operation(timeout.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "UNKNOWN");

        var mismatch = claimed("mismatch");
        repository.finishCreate(mismatch, failure(ProviderCallClassification.CONTRACT_MISMATCH));
        assertThat(payment(mismatch.paymentId())).containsEntry("resolution_status", "CONFLICT");
        assertThat(operation(mismatch.operationId())).containsEntry("resolution", "CONFLICT");

        var foreign = claimed("foreign");
        repository.finishCreate(
                foreign,
                success(session(UUID.randomUUID(), "cs_foreign", CheckoutSessionStatus.OPEN, "https://p", null)));
        assertThat(payment(foreign.paymentId()))
                .as("a session for another payment is a conflict")
                .containsEntry("resolution_status", "CONFLICT")
                .containsEntry("checkout_session_id", null);
        assertThat(operation(foreign.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "CONFLICT")
                .containsEntry("external_object_id", "cs_foreign");

        var noUrl = claimed("no-url");
        repository.finishCreate(
                noUrl, success(session(noUrl.paymentId(), "cs_nourl", CheckoutSessionStatus.OPEN, " ", null)));
        assertThat(payment(noUrl.paymentId())).containsEntry("resolution_status", "CONFLICT");
    }

    @Test
    void anchorsAndExistingConflictsWinOverANewSession() {
        var anchored = claimed("anchored");
        admin.update("UPDATE platform.payment SET checkout_session_id='cs_first' WHERE id=?", anchored.paymentId());
        repository.finishCreate(
                anchored,
                success(session(anchored.paymentId(), "cs_second", CheckoutSessionStatus.OPEN, "https://p", null)));
        assertThat(payment(anchored.paymentId()))
                .containsEntry("checkout_session_id", "cs_first")
                .containsEntry("resolution_status", "CONFLICT");
        assertThat(operation(anchored.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "CONFLICT")
                .containsEntry("external_object_id", null);

        var intent = claimed("intent");
        admin.update("UPDATE platform.payment SET payment_intent_id='pi_first' WHERE id=?", intent.paymentId());
        repository.finishCreate(
                intent,
                success(session(intent.paymentId(), "cs_i", CheckoutSessionStatus.COMPLETE, null, "pi_second")));
        assertThat(payment(intent.paymentId())).containsEntry("resolution_status", "CONFLICT");

        var conflicted = claimed("conflicted");
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", conflicted.paymentId());
        long version = (Long) payment(conflicted.paymentId()).get("version");
        repository.finishCreate(
                conflicted,
                success(session(conflicted.paymentId(), "cs_c", CheckoutSessionStatus.OPEN, "https://p", null)));
        assertThat(payment(conflicted.paymentId()))
                .as("payment untouched")
                .containsEntry("version", version)
                .containsEntry("checkout_session_id", null);
        assertThat(operation(conflicted.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "CONFLICT")
                .containsEntry("external_object_id", "cs_c");
    }

    @Test
    void lostLeaseRecordsTheObservationButChangesNothing() {
        var claim = claimed("lost");
        fake.clock().advance(Duration.ofSeconds(30));
        long version = (Long) payment(claim.paymentId()).get("version");
        repository.finishCreate(
                claim, success(session(claim.paymentId(), "cs_lost", CheckoutSessionStatus.OPEN, "https://p", null)));
        assertThat(observations(claim.operationId())).hasSize(1);
        assertThat(payment(claim.paymentId())).containsEntry("version", version);
        assertThat(operation(claim.operationId())).containsEntry("status", "SENDING");

        var stale = withToken(claimed("stale"), UUID.randomUUID());
        assertThatThrownBy(() -> repository.transaction(() -> {
                    repository.completeOperation(stale, "RESOLVED", "KNOWN", null, null);
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lost operation ownership inside locked writeback transaction");
    }

    @Test
    void createIsRecheckedForRemoteFactsBeforeSending() {
        var clean = claimed("recheck-clean");
        assertThat(repository.deferCreateWithRemoteFact(clean)).isFalse();
        assertThat(operation(clean.operationId())).containsEntry("status", "SENDING");

        var remote = claimed("recheck-remote");
        admin.update(
                "UPDATE platform.payment SET payment_intent_id='pi_late',checkout_session_id='cs_late',provider_status='open' WHERE id=?",
                remote.paymentId());
        assertThat(repository.deferCreateWithRemoteFact(remote)).isTrue();
        assertThat(operation(remote.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", payment(remote.paymentId()).get("resolution_status"))
                .containsEntry("external_object_id", "cs_late")
                .containsEntry("last_provider_status", "open");
        assertScheduled(remote.operationId(), "WAITING", 1);

        var lost = withToken(claimed("recheck-lost"), UUID.randomUUID());
        assertThat(repository.deferCreateWithRemoteFact(lost))
                .as("a lost lease never sends")
                .isTrue();
        assertThat(operation(lost.operationId())).containsEntry("status", "SENDING");
    }

    @Test
    void cancelOfAnUnsentCreateResolvesBothOperations() {
        var intent = repository.prepare(context, "result-unsent", command("unsent"), "FAKE");
        var cancel = repository.prepareCancel(
                context, intent.paymentId(), "cancel-unsent", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        var claim = repository.claim(cancel.operationId(), "FAKE");
        assertThat(claim.type()).isEqualTo("CANCEL_PAYMENT");
        assertThat(repository.cancelUnsent(claim)).isTrue();
        assertThat(operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(task(intent.operationId())).containsEntry("status", "DONE");
        assertThat(payment(intent.paymentId()))
                .containsEntry("payment_status", "CANCELED")
                .containsEntry("resolution_status", "KNOWN");
        assertThat(operation(cancel.operationId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "KNOWN");
        assertScheduled(cancel.operationId(), "DONE", 1);

        var sent = repository.prepare(context, "result-sent", command("sent"), "FAKE");
        repository.claim(sent.operationId(), "FAKE");
        var sentCancel = repository.prepareCancel(
                context, sent.paymentId(), "cancel-sent", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        var sentClaim = repository.claim(sentCancel.operationId(), "FAKE");
        assertThat(repository.cancelUnsent(sentClaim))
                .as("a create already handed to a sender must be canceled remotely")
                .isFalse();
        assertThat(operation(sent.operationId())).containsEntry("status", "SENDING");
        assertThat(operation(sentCancel.operationId())).containsEntry("status", "SENDING");
    }

    @Test
    void finishCancelRecordsQueriesAndRefusesConflicts() {
        var intent = repository.prepare(context, "result-cancel", command("cancel"), "FAKE");
        repository.claim(intent.operationId(), "FAKE");
        var cancel = repository.prepareCancel(
                context, intent.paymentId(), "cancel-conflict", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        var claim = repository.claim(cancel.operationId(), "FAKE");
        var query = new PaymentRepository.Observation(
                "QUERY_SESSION", failure(ProviderCallClassification.TIMEOUT_UNKNOWN), null);
        repository.finishCancel(
                claim, List.of(query), new PaymentFacts(null, null, List.of(), false, true, now()), true);
        assertThat(payment(intent.paymentId()))
                .containsEntry("resolution_status", "CONFLICT")
                .doesNotContainEntry("payment_status", "CANCELED");
        var op = operation(cancel.operationId());
        assertThat(op).containsEntry("status", "UNKNOWN").containsEntry("resolution", "CONFLICT");
        assertThat(at(op.get("last_query_at"))).isEqualTo(now());
        assertThat(observations(cancel.operationId()))
                .singleElement()
                .satisfies(o -> assertThat(o)
                        .containsEntry("call_type", "QUERY_SESSION")
                        .containsEntry("lease_token", claim.token()));

        var incomplete = repository.prepare(context, "result-cancel-2", command("cancel-2"), "FAKE");
        repository.claim(incomplete.operationId(), "FAKE");
        var cancel2 = repository.prepareCancel(
                context, incomplete.paymentId(), "cancel-partial", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        var claim2 = repository.claim(cancel2.operationId(), "FAKE");
        repository.finishCancel(claim2, List.of(), new PaymentFacts(null, null, List.of(), false, false, now()), true);
        var op2 = operation(cancel2.operationId());
        assertThat(op2).containsEntry("status", "UNKNOWN").containsEntry("resolution", "UNKNOWN");
        assertThat(op2.get("last_query_at")).as("no query was made").isNull();
    }

    @Test
    void queryMergeStampsTheOperationWithoutTakingOwnership() {
        var claim = claimed("refresh");
        var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
        repository.finishCreate(claim, fake.createSession(command));
        long version = (Long) operation(claim.operationId()).get("version");
        fake.clock().advance(Duration.ofSeconds(5));
        executor.refresh(claim.paymentId());
        var op = operation(claim.operationId());
        assertThat(at(op.get("last_query_at"))).isEqualTo(now());
        assertThat(op).containsEntry("status", "RESOLVED").containsEntry("version", version);
        assertThat(observations(claim.operationId()))
                .filteredOn(o -> o.get("lease_token") == null)
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.get("call_type").toString()).isNotEqualTo("CREATE_SESSION"));

        var conflicted = claimed("refresh-conflict");
        repository.mergeQuery(
                conflicted.paymentId(),
                conflicted.operationId(),
                List.of(),
                new PaymentFacts(null, null, List.of(), true, true, now()));
        assertThat(operation(conflicted.operationId()))
                .as("a conflicting query never resolves the operation")
                .containsEntry("status", "SENDING");
        assertThat(payment(conflicted.paymentId())).containsEntry("resolution_status", "CONFLICT");
    }

    @Test
    void recoveryAnchorsComeFromThePaymentOperationAndObservations() {
        var claim = claimed("anchor");
        repository.finishCreate(
                claim, success(session(claim.paymentId(), "cs_anchor", CheckoutSessionStatus.OPEN, "https://p", null)));
        fake.clock().advance(repository.recoveryPolicy().delay(claim.operationId(), 1));
        var recovered = repository.claimRecovery(claim.operationId(), "FAKE", true);
        assertThat(repository.recoveryAnchor(recovered)).isEqualTo("cs_anchor");

        var split = claimed("split");
        repository.finishCreate(
                split, success(session(split.paymentId(), "cs_split", CheckoutSessionStatus.OPEN, "https://p", null)));
        admin.update("UPDATE platform.payment SET checkout_session_id='cs_other' WHERE id=?", split.paymentId());
        fake.clock().advance(Duration.ofMinutes(10));
        var splitClaim = repository.claimRecovery(split.operationId(), "FAKE", true);
        assertThat(repository.recoveryAnchor(splitClaim)).isEqualTo("CONFLICTING_SESSION_ANCHORS");
        repository.conflictingAnchor(splitClaim);
        assertThat(payment(split.paymentId())).containsEntry("resolution_status", "CONFLICT");

        var none = claimed("no-anchor");
        assertThat(repository.recoveryAnchor(none)).isNull();
    }

    @Test
    void recoveryQueryCompletesOrEscalatesByUnresolvedAge() {
        var young = claimed("young");
        repository.finishCreate(young, failure(ProviderCallClassification.TIMEOUT_UNKNOWN));
        fake.clock().advance(Duration.ofMinutes(5));
        var youngClaim = repository.claimRecovery(young.operationId(), "FAKE", true);
        var observation =
                new PaymentRepository.Observation("QUERY_SESSION", failure(ProviderCallClassification.NOT_FOUND), null);
        repository.finishRecoveryQuery(
                youngClaim,
                new ProviderQueryService.Queried(
                        new PaymentFacts(null, null, List.of(), false, false, now()), List.of(observation)));
        var op = operation(young.operationId());
        assertThat(op).containsEntry("status", "UNKNOWN").containsEntry("resolution", "UNKNOWN");
        assertThat(at(op.get("last_query_at"))).isEqualTo(now());
        assertScheduled(young.operationId(), "WAITING", 2);

        var old = claimed("old");
        repository.finishCreate(old, failure(ProviderCallClassification.TIMEOUT_UNKNOWN));
        fake.clock().advance(Duration.ofHours(1));
        var oldClaim = repository.claimRecovery(old.operationId(), "FAKE", true);
        repository.finishRecoveryQuery(
                oldClaim,
                new ProviderQueryService.Queried(
                        new PaymentFacts(null, null, List.of(), false, false, now()), List.of()));
        var escalated = operation(old.operationId());
        assertThat(escalated)
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT");
        assertThat(at(escalated.get("age_alert_at"))).isEqualTo(now());
        assertThat(task(old.operationId())).containsEntry("status", "ESCALATED");
    }

    @Test
    void providerScopeNeedsAnActiveApplicationAndBinding() {
        repository.checkProviderScope(scope, "FAKE");
        admin.update("UPDATE platform.application SET status='DISABLED'");
        assertThatThrownBy(() -> repository.checkProviderScope(scope, "FAKE"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
