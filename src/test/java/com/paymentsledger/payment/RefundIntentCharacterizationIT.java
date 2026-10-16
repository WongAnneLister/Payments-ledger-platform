package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.repository.PaymentRepositories;
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
 * Pins refund reservation and the refund read model before they are split into domain rules and a repository: the
 * rows a reservation writes, idempotency and client-key replays, every refusal, the read model's fields and
 * reservation figures, and the guards of the audited unfreeze.
 */
class RefundIntentCharacterizationIT extends PaymentCoreFixture {
    final ObjectMapper mapper = new ObjectMapper();
    TenantContext refundContext;
    UUID captured;

    @BeforeEach
    void capture() {
        refundContext = new TenantContext(
                context.organizationId(),
                context.environmentId(),
                context.applicationId(),
                context.allowedProviderAccountId(),
                Set.of("USD", "EUR"),
                false,
                Set.of("refunds:read", "refunds:write"));
        captured = capturedPayment("intent-order", "ch_intent", 1000);
    }

    UUID capturedPayment(String order, String charge, long amount) {
        var intent = repository.prepare(context, "payment-" + order, command(order), "FAKE");
        admin.update("""
                UPDATE platform.payment
                SET captured_amount_minor=?,payment_status='SUCCEEDED',latest_charge_id=?
                WHERE id=?""", amount, charge, intent.paymentId());
        admin.update("""
                INSERT INTO platform.payment_attempt(organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,fetched_at)
                VALUES (?,?,?,?,'USD',?,?,'succeeded',?,true,?,?,now())""", id(1), id(2), id(3), id(4), intent.paymentId(), charge, "pi_" + charge, amount, amount);
        return intent.paymentId();
    }

    RefundRepository refunds() {
        return new RefundRepository(
                new PaymentRepositories(jdbc), tx, new ObjectMapper().findAndRegisterModules(), fake.clock());
    }

    RefundRequests.Create request(UUID payment, long amount, String clientKey) {
        return new RefundRequests.Create(payment, amount, "USD", "REQUESTED_BY_CUSTOMER", "note", clientKey);
    }

    Instant now() {
        return fake.clock().instant();
    }

    static Instant at(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    static void rejects(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    Map<String, Object> control(UUID payment) {
        return admin.queryForMap("SELECT * FROM platform.refund_control WHERE payment_id=?", payment);
    }

    @Test
    void reservationWritesRefundOperationTaskAndIdempotencyRecord() throws Exception {
        var request = request(captured, 400, "client-1");
        var intent = refunds().prepare(refundContext, "refund-write", request, "FAKE");
        assertThat(intent.replay()).isFalse();

        var refund = admin.queryForMap("SELECT * FROM platform.refund WHERE id=?", intent.refundId());
        assertThat(refund)
                .containsEntry("payment_id", captured)
                .containsEntry("amount_minor", 400L)
                .containsEntry("currency", "USD")
                .containsEntry("source", "LOCAL")
                .containsEntry("refund_status", "RESERVED")
                .containsEntry("resolution_status", "KNOWN")
                .containsEntry("reason", "REQUESTED_BY_CUSTOMER")
                .containsEntry("note", "note")
                .containsEntry("client_refund_key", "client-1")
                .containsEntry("request_fingerprint", request.fingerprint())
                .containsEntry("provider_charge_id", "ch_intent")
                .containsEntry("provider_refund_id", null);
        assertThat(control(captured))
                .containsEntry("captured_minor", 1000L)
                .containsEntry("reserved_minor", 400L)
                .containsEntry("refunded_minor", 0L)
                .containsEntry("control_state", "NORMAL")
                .containsEntry("version", 1L);

        var operation = admin.queryForMap("SELECT * FROM platform.provider_operation WHERE id=?", intent.operationId());
        assertThat(operation)
                .containsEntry("refund_id", intent.refundId())
                .containsEntry("payment_id", null)
                .containsEntry("operation_target", "REFUND")
                .containsEntry("operation_type", "CREATE_REFUND")
                .containsEntry("status", "PREPARED")
                .containsEntry("operation_key", "refund_create_" + intent.operationId())
                .containsEntry("request_fingerprint", request.fingerprint());
        assertThat(at(operation.get("expires_at"))).isEqualTo(now().plus(Duration.ofDays(1)));
        var command = mapper.readTree(operation.get("command").toString());
        assertThat(command.path("operationIdempotencyKey").asText()).isEqualTo("refund_create_" + intent.operationId());
        assertThat(command.path("chargeId").asText()).isEqualTo("ch_intent");
        assertThat(command.path("paymentIntentId").isNull()).isTrue();
        assertThat(command.path("amountMinor").asLong()).isEqualTo(400);
        assertThat(command.path("reason").asText()).isEqualTo("requested_by_customer");
        assertThat(command.path("scope").path("providerAccountId").asText()).isEqualTo(id(4).toString());

        var task = admin.queryForMap("SELECT * FROM platform.recovery_task WHERE operation_id=?", intent.operationId());
        assertThat(task).containsEntry("refund_id", intent.refundId()).containsEntry("status", "READY");
        assertThat(at(task.get("available_at"))).isEqualTo(now());

        assertThat(admin.queryForMap("""
                        SELECT request_type,request_fingerprint,refund_id,operation_id,currency
                        FROM platform.api_idempotency_record
                        WHERE idempotency_key='refund-write'"""))
                .isEqualTo(Map.of(
                        "request_type", "CREATE_REFUND",
                        "request_fingerprint", request.fingerprint(),
                        "refund_id", intent.refundId(),
                        "operation_id", intent.operationId(),
                        "currency", "USD"));
    }

    @Test
    void providerReasonsMapOnlyKnownMerchantReasons() throws Exception {
        for (var reason : List.of("DUPLICATE", "FRAUDULENT", "MERCHANT_DECISION", "OTHER")) {
            var intent = refunds()
                    .prepare(
                            refundContext,
                            "refund-reason-" + reason,
                            new RefundRequests.Create(captured, 10, "USD", reason, null, null),
                            "FAKE");
            var command = mapper.readTree(admin.queryForObject(
                    "SELECT command::text FROM platform.provider_operation WHERE id=?",
                    String.class,
                    intent.operationId()));
            String expected = switch (reason) {
                case "DUPLICATE" -> "duplicate";
                case "FRAUDULENT" -> "fraudulent";
                default -> null;
            };
            if (expected == null)
                assertThat(command.path("reason").isNull()).as(reason).isTrue();
            else assertThat(command.path("reason").asText()).isEqualTo(expected);
        }
    }

    @Test
    void replaysReturnTheOriginalRefundAndRejectChangedRequests() {
        var r = refunds();
        var first = r.prepare(refundContext, "refund-replay-a", request(captured, 300, "client-r"), "FAKE");
        var again = r.prepare(refundContext, "refund-replay-a", request(captured, 300, "client-r"), "FAKE");
        assertThat(again).isEqualTo(new RefundRepository.Intent(first.refundId(), first.operationId(), true));
        rejects(
                () -> r.prepare(refundContext, "refund-replay-a", request(captured, 301, "client-r"), "FAKE"),
                ErrorCode.IDEMPOTENCY_CONFLICT);

        var byClientKey = r.prepare(refundContext, "refund-replay-b", request(captured, 300, "client-r"), "FAKE");
        assertThat(byClientKey).isEqualTo(new RefundRepository.Intent(first.refundId(), first.operationId(), true));
        assertThat(admin.queryForObject(
                        "SELECT refund_id FROM platform.api_idempotency_record WHERE idempotency_key='refund-replay-b'",
                        UUID.class))
                .as("the new key is recorded against the original refund")
                .isEqualTo(first.refundId());
        rejects(
                () -> r.prepare(refundContext, "refund-replay-c", request(captured, 299, "client-r"), "FAKE"),
                ErrorCode.IDEMPOTENCY_CONFLICT);

        assertThat(count("refund")).isEqualTo(1);
        assertThat(control(captured)).containsEntry("reserved_minor", 300L);
    }

    @Test
    void reservationsAreRefusedOutsideScopeAndBeyondTheAvailableAmount() {
        var r = refunds();
        var readOnly = new TenantContext(id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:read"));
        rejects(() -> r.prepare(readOnly, "refund-no-scope", request(captured, 10, null), "FAKE"), ErrorCode.FORBIDDEN);
        rejects(
                () -> r.prepare(refundContext, "refund-missing", request(UUID.randomUUID(), 10, null), "FAKE"),
                ErrorCode.NOT_FOUND);
        var euro = new RefundRequests.Create(captured, 10, "EUR", null, null, null);
        rejects(() -> r.prepare(refundContext, "refund-euro", euro, "FAKE"), ErrorCode.CURRENCY_NOT_ENABLED);
        var dollarsOnly = new TenantContext(id(1), id(2), id(3), id(4), Set.of("EUR"), false, Set.of("refunds:write"));
        rejects(
                () -> r.prepare(dollarsOnly, "refund-hidden", request(captured, 10, null), "FAKE"),
                ErrorCode.CURRENCY_NOT_ENABLED);

        var uncaptured = repository.prepare(context, "payment-uncaptured", command("uncaptured"), "FAKE");
        rejects(
                () -> r.prepare(refundContext, "refund-uncaptured", request(uncaptured.paymentId(), 10, null), "FAKE"),
                ErrorCode.REFUND_BLOCKED);
        var noCharge = capturedPayment("no-charge", "ch_none", 500);
        admin.update("UPDATE platform.payment SET latest_charge_id=NULL WHERE id=?", noCharge);
        rejects(
                () -> r.prepare(refundContext, "refund-no-charge", request(noCharge, 10, null), "FAKE"),
                ErrorCode.REFUND_BLOCKED);

        var conflicted = capturedPayment("conflicted", "ch_conflicted", 500);
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", conflicted);
        rejects(
                () -> r.prepare(refundContext, "refund-conflicted", request(conflicted, 10, null), "FAKE"),
                ErrorCode.EVIDENCE_CONFLICT);

        rejects(
                () -> r.prepare(refundContext, "refund-too-much", request(captured, 1001, null), "FAKE"),
                ErrorCode.REFUND_LIMIT_EXCEEDED);
        r.prepare(refundContext, "refund-six", request(captured, 600, null), "FAKE");
        rejects(
                () -> r.prepare(refundContext, "refund-401", request(captured, 401, null), "FAKE"),
                ErrorCode.REFUND_LIMIT_EXCEEDED);
        r.prepare(refundContext, "refund-400", request(captured, 400, null), "FAKE");
        assertThat(control(captured)).containsEntry("reserved_minor", 1000L);

        var frozen = capturedPayment("frozen", "ch_frozen", 500);
        r.prepare(refundContext, "refund-before-freeze", request(frozen, 10, null), "FAKE");
        admin.update("UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT' WHERE payment_id=?", frozen);
        rejects(
                () -> r.prepare(refundContext, "refund-frozen", request(frozen, 10, null), "FAKE"),
                ErrorCode.EVIDENCE_CONFLICT);
        admin.update("UPDATE platform.refund_control SET control_state='DISPUTE_BLOCKED' WHERE payment_id=?", frozen);
        rejects(
                () -> r.prepare(refundContext, "refund-disputed", request(frozen, 10, null), "FAKE"),
                ErrorCode.REFUND_BLOCKED);

        assertThat(count("refund")).isEqualTo(3);
    }

    @Test
    void controlTracksTheLatestCapturedAmount() {
        var r = refunds();
        r.prepare(refundContext, "refund-capture-1", request(captured, 100, null), "FAKE");
        admin.update("UPDATE platform.payment SET captured_amount_minor=2000 WHERE id=?", captured);
        r.prepare(refundContext, "refund-capture-2", request(captured, 1500, null), "FAKE");
        assertThat(control(captured)).containsEntry("captured_minor", 2000L).containsEntry("reserved_minor", 1600L);
    }

    @Test
    void readModelShowsTheRefundItsReservationAndEvidence() {
        var r = refunds();
        var intent = r.prepare(refundContext, "refund-read", request(captured, 400, null), "FAKE");
        var resource = r.read(refundContext, intent.refundId());
        assertThat(resource)
                .containsEntry("id", PaymentIds.format("ref", intent.refundId()))
                .containsEntry("paymentId", PaymentIds.format("pay", captured))
                .containsEntry("amountMinor", 400L)
                .containsEntry("currency", "USD")
                .containsEntry("refundStatus", "RESERVED")
                .containsEntry("resolutionStatus", "KNOWN")
                .containsEntry("providerStatus", null)
                .containsEntry("providerRefundId", null)
                .containsEntry("reason", "REQUESTED_BY_CUSTOMER")
                .containsEntry("controlState", "NORMAL")
                .containsEntry("financialStatus", "NOT_APPLICABLE")
                .containsEntry("lastProviderConfirmedAt", null)
                .containsEntry(
                        "reservation",
                        Map.of(
                                "capturedMinor",
                                1000L,
                                "refundedMinor",
                                0L,
                                "reservedMinor",
                                400L,
                                "availableMinor",
                                600L));
        @SuppressWarnings("unchecked")
        var evidence = (Map<String, Object>) resource.get("evidence");
        assertThat(evidence)
                .containsEntry("refundBalanceTransactionId", null)
                .containsEntry("failureBalanceTransactionId", null)
                .containsEntry("actualFeeRefundMinor", 0L)
                .containsEntry("postedJournalIds", List.of());
        assertThat(resource.get("createdAt")).isInstanceOf(String.class);
        assertThat(List.copyOf(resource.keySet()))
                .containsExactly(
                        "id",
                        "paymentId",
                        "amountMinor",
                        "currency",
                        "refundStatus",
                        "resolutionStatus",
                        "providerStatus",
                        "providerRefundId",
                        "reason",
                        "controlState",
                        "reservation",
                        "financialStatus",
                        "evidence",
                        "createdAt",
                        "updatedAt",
                        "lastProviderConfirmedAt");

        admin.update("UPDATE platform.refund SET refund_status='SUBMITTED' WHERE id=?", intent.refundId());
        assertThat(r.read(refundContext, intent.refundId()))
                .as("a submitted refund awaits evidence")
                .containsEntry("financialStatus", "PENDING_EVIDENCE");
        admin.update("UPDATE platform.refund SET resolution_status='CONFLICT' WHERE id=?", intent.refundId());
        assertThat(r.read(refundContext, intent.refundId())).containsEntry("financialStatus", "CONFLICT");

        var writeOnly = new TenantContext(id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:write"));
        rejects(() -> r.read(writeOnly, intent.refundId()), ErrorCode.FORBIDDEN);
        var euroOnly = new TenantContext(id(1), id(2), id(3), id(4), Set.of("EUR"), false, Set.of("refunds:read"));
        rejects(() -> r.read(euroOnly, intent.refundId()), ErrorCode.NOT_FOUND);
        rejects(() -> r.read(refundContext, UUID.randomUUID()), ErrorCode.NOT_FOUND);
    }

    @Test
    void unfreezeValidatesItsInputsBeforeTouchingState() {
        var r = refunds();
        var scope = new ProviderScope(id(1), id(2), id(4), false);
        rejects(
                () -> r.resolveExternalConflict(
                        new ProviderScope(id(1), id(2), id(4), true), captured, 0, UUID.randomUUID(), "audit"),
                ErrorCode.VALIDATION_ERROR);
        rejects(
                () -> r.resolveExternalConflict(scope, captured, 0, UUID.randomUUID(), null),
                ErrorCode.VALIDATION_ERROR);
        rejects(
                () -> r.resolveExternalConflict(scope, captured, 0, UUID.randomUUID(), " "),
                ErrorCode.VALIDATION_ERROR);
        rejects(
                () -> r.resolveExternalConflict(scope, captured, 0, UUID.randomUUID(), "a".repeat(256)),
                ErrorCode.VALIDATION_ERROR);
        rejects(
                () -> r.resolveExternalConflict(scope, UUID.randomUUID(), 0, UUID.randomUUID(), "audit-missing"),
                ErrorCode.NOT_FOUND);
        rejects(
                () -> r.resolveExternalConflict(scope, captured, 0, UUID.randomUUID(), "audit-normal"),
                ErrorCode.EVIDENCE_CONFLICT);
        assertThat(admin.queryForObject(
                        "SELECT count(*) FROM platform.refund_control WHERE payment_id=?", Long.class, captured))
                .as("the refused attempt rolls back the control row it created")
                .isZero();
    }
}
