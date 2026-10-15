package com.paymentsledger.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.payment.PaymentRepository;
import com.paymentsledger.payment.RefundRepository;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.RefundFact;
import com.paymentsledger.provider.ProviderScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Verified refund snapshots join the canonical refund transaction; no provider I/O. */
@Component
public final class RefundWebhookHandler {
    private final WebhookInboxRepository inbox;
    private final PaymentRepository payments;
    private final RefundRepository refunds;

    public RefundWebhookHandler(WebhookInboxRepository inbox, PaymentRepository payments, RefundRepository refunds) {
        this.inbox = inbox;
        this.payments = payments;
        this.refunds = refunds;
    }

    public void process(WebhookInboxRepository.Claim claim, JsonNode object) {
        var row = claim.row();
        var scope = new ProviderScope(
                (UUID) row.get("organization_id"),
                (UUID) row.get("environment_id"),
                (UUID) row.get("provider_account_id"),
                false);
        String objectId = text(object, "id"), chargeId = text(object, "charge");
        var existing = refunds.lookup(scope, objectId);
        UUID paymentId =
                existing == null ? refunds.paymentForCharge(scope, chargeId) : (UUID) existing.get("payment_id");
        inbox.transaction(() -> {
            if (paymentId != null) payments.lockWebhookPayment(paymentId);
            if (!inbox.owns(claim)) return null;
            if (!Set.of("refund.created", "refund.updated", "refund.failed", "charge.refund.updated")
                    .contains(row.get("event_type").toString())) {
                inbox.waitFor(claim, "EVIDENCE", objectId, "UNSUPPORTED_REFUND_EVENT");
                return null;
            }
            var amount = object.path("amount");
            String status = text(object, "status"), currency = text(object, "currency");
            if (objectId == null
                    || chargeId == null
                    || !amount.isIntegralNumber()
                    || !amount.canConvertToLong()
                    || amount.longValue() <= 0
                    || currency == null
                    || !currency.matches("[a-zA-Z]{3}")
                    || status == null
                    || !Set.of("pending", "requires_action", "succeeded", "failed", "canceled")
                            .contains(status)) {
                inbox.waitFor(claim, "EVIDENCE", objectId, "INCOMPLETE_REFUND_FACT");
                return null;
            }
            Instant confirmed = ((Timestamp) row.get("event_created_at")).toInstant();
            Instant created = object.path("created").isIntegralNumber()
                            && object.path("created").canConvertToLong()
                    ? Instant.ofEpochSecond(object.path("created").longValue())
                    : null;
            var fact = new RefundFact(
                    objectId,
                    chargeId,
                    status,
                    amount.longValue(),
                    currency,
                    text(object, "balance_transaction"),
                    text(object, "failure_reason"),
                    text(object, "failure_balance_transaction"),
                    created);
            var evidence = new ProviderEvidence(
                    null, null, confirmed, confirmed, 200, null, (String) row.get("raw_digest"), (String)
                            row.get("api_version"));
            UUID accepted = refunds.accept(scope, fact, evidence, existing == null ? null : (UUID) existing.get("id"));
            if (accepted == null) {
                inbox.waitFor(claim, "CHARGE", chargeId, "MISSING_REFUND_PAYMENT_DEPENDENCY");
                return null;
            }
            inbox.done(claim);
            return null;
        });
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).textValue() : null;
    }
}
