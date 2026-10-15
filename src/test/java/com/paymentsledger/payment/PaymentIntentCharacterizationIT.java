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
import org.junit.jupiter.api.function.Executable;

/**
 * Pins payment intent preparation (create and cancel) and the payment read model before they are split into domain
 * rules and repositories: idempotent replay, order and binding checks, server-side pricing, persisted intent rows, cancel
 * eligibility, the API resource's field order, its next action and its recovery summary.
 */
class PaymentIntentCharacterizationIT extends PaymentCoreFixture {

    static void rejects(Executable action, ErrorCode code) {
        assertThatThrownBy(action::execute)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    PaymentRequests.Create create(String order, String product, long amount) {
        return new PaymentRequests.Create(
                order,
                product,
                1,
                amount,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of("cart", "c-1"));
    }

    Map<String, Object> row(String sql, Object... args) {
        return admin.queryForMap(sql, args);
    }

    @Test
    void createIsIdempotentPerKeyAndOrderKeysAreUnique() {
        var first = repository.prepare(context, "key-1", create("order-1", "demo", 10000), "FAKE");
        assertThat(first.replay()).isFalse();
        var again = repository.prepare(context, "key-1", create("order-1", "demo", 10000), "FAKE");
        assertThat(again).isEqualTo(new PaymentRepository.Intent(first.paymentId(), first.operationId(), true));
        rejects(
                () -> repository.prepare(context, "key-1", create("order-2", "demo", 10000), "FAKE"),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        rejects(
                () -> repository.prepare(context, "key-2", create("order-1", "demo", 10000), "FAKE"),
                ErrorCode.ORDER_INTENT_CONFLICT);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment", Long.class))
                .isEqualTo(1);
    }

    @Test
    void priceAndBindingAreCheckedOnTheServer() {
        rejects(
                () -> repository.prepare(context, "amount", create("order-a", "demo", 9999), "FAKE"),
                ErrorCode.AMOUNT_MISMATCH);
        rejects(
                () -> repository.prepare(context, "product", create("order-p", "missing", 10000), "FAKE"),
                ErrorCode.NOT_FOUND);
        rejects(
                () -> repository.prepare(context, "provider", create("order-s", "demo", 10000), "STRIPE"),
                ErrorCode.SERVICE_UNAVAILABLE);
        admin.update("UPDATE platform.provider_account SET status='DISABLED' WHERE id=?", id(4));
        rejects(
                () -> repository.prepare(context, "disabled", create("order-d", "demo", 10000), "FAKE"),
                ErrorCode.FORBIDDEN);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.business_order", Long.class))
                .isZero();
    }

    @Test
    void createPersistsOrderPaymentOperationTaskAndIdempotencyRecord() {
        Instant now = fake.clock().instant();
        var intent = repository.prepare(context, "persist", create("order-persist", "demo", 10000), "FAKE");
        var payment = row("SELECT * FROM platform.payment WHERE id=?", intent.paymentId());
        assertThat(payment)
                .containsEntry("amount_minor", 10000L)
                .containsEntry("currency", "USD")
                .containsEntry("payment_status", "CREATED")
                .containsEntry("resolution_status", "KNOWN")
                .containsEntry("financial_status", "NOT_APPLICABLE");
        Instant expires = ((Timestamp) payment.get("expires_at")).toInstant();
        assertThat(expires).isEqualTo(now.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

        var order = row("SELECT * FROM platform.business_order WHERE id=?", payment.get("business_order_id"));
        assertThat(order)
                .containsEntry("order_key", "order-persist")
                .containsEntry("unit_amount_minor", 10000L)
                .containsEntry("amount_minor", 10000L)
                .containsEntry("quantity", 1);
        assertThat(order.get("price_snapshot")).isNotNull();
        assertThat(order.get("share_policy_snapshot")).isNotNull();

        var operation = row("SELECT * FROM platform.provider_operation WHERE id=?", intent.operationId());
        assertThat(operation)
                .containsEntry("operation_type", "CREATE_CHECKOUT")
                .containsEntry("operation_target", "PAYMENT")
                .containsEntry("operation_key", "pay_create_" + intent.operationId());
        String command = operation.get("command").toString();
        assertThat(command)
                .contains("\"cart\": \"c-1\"")
                .contains("\"payment_id\": \"" + intent.paymentId() + "\"")
                .contains("\"business_order_id\": \"" + payment.get("business_order_id") + "\"");

        var task = row("SELECT * FROM platform.recovery_task WHERE operation_id=?", intent.operationId());
        assertThat(task).containsEntry("status", "READY").containsEntry("payment_id", intent.paymentId());
        var api = row(
                "SELECT * FROM platform.api_idempotency_record WHERE idempotency_key=? AND request_type='CREATE_PAYMENT'",
                "persist");
        assertThat(api)
                .containsEntry("payment_id", intent.paymentId())
                .containsEntry("operation_id", intent.operationId());
    }

    @Test
    void cancelReusesOneOperationAndRejectsSettledOrConflictingPayments() {
        UUID paymentId = payment(
                service.create(context, "cancel-create", command("cancel")).resource());
        var cancel = new PaymentRequests.Cancel(null, null);
        var first = repository.prepareCancel(context, paymentId, "cancel-1", cancel, "FAKE");
        assertThat(first.replay()).isFalse();
        assertThat(row(
                        "SELECT operation_key,operation_type FROM platform.provider_operation WHERE id=?",
                        first.operationId()))
                .containsEntry("operation_type", "CANCEL_PAYMENT")
                .containsEntry("operation_key", "pay_cancel_" + first.operationId());
        assertThat(repository.prepareCancel(context, paymentId, "cancel-1", cancel, "FAKE"))
                .isEqualTo(new PaymentRepository.Intent(paymentId, first.operationId(), true));
        assertThat(repository.prepareCancel(context, paymentId, "cancel-2", cancel, "FAKE"))
                .as("a new key reuses the payment's single cancel operation")
                .isEqualTo(new PaymentRepository.Intent(paymentId, first.operationId(), true));

        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", paymentId);
        rejects(
                () -> repository.prepareCancel(context, paymentId, "cancel-3", cancel, "FAKE"),
                ErrorCode.EVIDENCE_CONFLICT);
        admin.update(
                "UPDATE platform.payment SET resolution_status='KNOWN',payment_status='SUCCEEDED' WHERE id=?",
                paymentId);
        rejects(
                () -> repository.prepareCancel(context, paymentId, "cancel-4", cancel, "FAKE"),
                ErrorCode.PAYMENT_NOT_CANCELABLE);
        rejects(
                () -> repository.prepareCancel(context, UUID.randomUUID(), "cancel-5", cancel, "FAKE"),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void resourceFieldOrderIsPartOfTheApi() {
        var resource = service.create(context, "shape", command("shape")).resource();
        assertThat(resource.keySet())
                .containsExactly(
                        "recovery",
                        "id",
                        "businessOrderId",
                        "orderKey",
                        "amountMinor",
                        "currency",
                        "paymentStatus",
                        "providerStatus",
                        "resolutionStatus",
                        "financialStatus",
                        "capturedAmountMinor",
                        "revenueShares",
                        "createdAt",
                        "updatedAt",
                        "lastProviderConfirmedAt",
                        "provider",
                        "nextAction");
        assertThat(((Map<?, ?>) resource.get("provider")).keySet())
                .containsExactly("checkoutSessionId", "paymentIntentId", "latestChargeId", "attempts");
        assertThat(((Map<?, ?>) resource.get("recovery")).keySet())
                .containsExactly("status", "nextAttemptAt", "attemptCount", "lastQueryAt", "reason");
        assertThat(resource.get("id").toString()).startsWith("pay_");
        assertThat(resource.get("businessOrderId").toString()).startsWith("bor_");
    }

    @Test
    void nextActionRedirectsOnlyWhileTheCheckoutCanStillBePaid() {
        UUID paymentId =
                payment(service.create(context, "action", command("action")).resource());
        var redirect = (Map<?, ?>) repository.read(context, paymentId).get("nextAction");
        assertThat(redirect.get("type")).isEqualTo("REDIRECT_TO_HOSTED_CHECKOUT");
        assertThat(redirect.get("hostedUrl")).isNotNull();
        Instant expires = admin.queryForObject(
                        "SELECT expires_at FROM platform.payment WHERE id=?", Timestamp.class, paymentId)
                .toInstant();
        assertThat(redirect.get("expiresAt")).isEqualTo(expires.toString());

        admin.update("UPDATE platform.payment SET resolution_status='UNKNOWN' WHERE id=?", paymentId);
        assertThat(nextAction(paymentId)).isEqualTo("WAIT_FOR_CONFIRMATION");
        admin.update(
                "UPDATE platform.payment SET resolution_status='KNOWN',payment_status='CANCELED' WHERE id=?",
                paymentId);
        assertThat(nextAction(paymentId)).isEqualTo("NONE");
        admin.update("UPDATE platform.payment SET payment_status='AWAITING_CUSTOMER' WHERE id=?", paymentId);
        assertThat(nextAction(paymentId)).isEqualTo("REDIRECT_TO_HOSTED_CHECKOUT");
        fake.clock().advance(Duration.ofHours(2));
        assertThat(nextAction(paymentId)).as("expired checkout").isEqualTo("WAIT_FOR_CONFIRMATION");
    }

    String nextAction(UUID paymentId) {
        return ((Map<?, ?>) repository.read(context, paymentId).get("nextAction"))
                .get("type")
                .toString();
    }

    @Test
    void recoverySummaryReportsTheMostUrgentTaskAndTotals() {
        UUID paymentId =
                payment(service.create(context, "summary", command("summary")).resource());
        var cancelOperation = repository
                .prepareCancel(context, paymentId, "summary-cancel", new PaymentRequests.Cancel(null, null), "FAKE")
                .operationId();
        UUID createOperation = repository.createOperation(paymentId);
        admin.update("UPDATE platform.recovery_task SET status='DONE' WHERE payment_id=?", paymentId);
        assertThat(summary(paymentId)).containsEntry("status", "IDLE").containsEntry("nextAttemptAt", null);
        assertThat(summary(paymentId).get("attemptCount"))
                .isEqualTo(admin.queryForObject(
                        "SELECT sum(attempt_count)::int FROM platform.recovery_task WHERE payment_id=?",
                        Integer.class,
                        paymentId));

        Instant at = fake.clock().instant().plusSeconds(300);
        admin.update(
                "UPDATE platform.recovery_task SET status='WAITING',available_at=? WHERE operation_id=?",
                Timestamp.from(at),
                cancelOperation);
        assertThat(summary(paymentId)).containsEntry("status", "WAITING").containsEntry("nextAttemptAt", at.toString());

        admin.update(
                "UPDATE platform.recovery_task SET status='RUNNING',lease_token=?,lease_until=? WHERE operation_id=?",
                UUID.randomUUID(),
                Timestamp.from(fake.clock().instant().plusSeconds(60)),
                cancelOperation);
        assertThat(summary(paymentId)).containsEntry("status", "RUNNING").containsEntry("nextAttemptAt", null);
        admin.update(
                "UPDATE platform.recovery_task SET lease_until=? WHERE operation_id=?",
                Timestamp.from(fake.clock().instant().minusSeconds(1)),
                cancelOperation);
        assertThat(summary(paymentId))
                .as("an expired lease is reported as waiting")
                .containsEntry("status", "WAITING")
                .containsEntry("nextAttemptAt", at.toString());

        admin.update(
                "UPDATE platform.recovery_task SET status='ESCALATED',reason='IDEMPOTENCY_WINDOW_EXPIRED',"
                        + "escalated_at=now(),lease_token=NULL,lease_until=NULL WHERE operation_id=?",
                createOperation);
        assertThat(summary(paymentId))
                .as("escalation outranks a waiting task")
                .containsEntry("status", "ESCALATED")
                .containsEntry("reason", "IDEMPOTENCY_WINDOW_EXPIRED");

        Instant queried = fake.clock().instant().minusSeconds(30);
        admin.update("UPDATE platform.provider_operation SET last_query_at=NULL WHERE id=?", createOperation);
        admin.update(
                "UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",
                Timestamp.from(queried),
                cancelOperation);
        assertThat(summary(paymentId)).containsEntry("lastQueryAt", queried.toString());
    }

    Map<?, ?> summary(UUID paymentId) {
        return (Map<?, ?>) repository.read(context, paymentId).get("recovery");
    }
}
