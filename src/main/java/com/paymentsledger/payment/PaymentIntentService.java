package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantAccessGuard;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.payment.PaymentRepository.Intent;
import com.paymentsledger.payment.domain.IdempotencyRecord;
import com.paymentsledger.payment.domain.PaymentIntentRules;
import com.paymentsledger.payment.domain.PaymentRecord;
import com.paymentsledger.payment.domain.PriceQuote;
import com.paymentsledger.payment.domain.StripeDeployment;
import com.paymentsledger.payment.repository.PaymentIntentRepository;
import com.paymentsledger.payment.repository.PaymentIntentRepository.NewOperation;
import com.paymentsledger.payment.repository.PaymentIntentRepository.NewOrder;
import com.paymentsledger.payment.repository.PaymentRecordRepository;
import com.paymentsledger.provider.ProviderModels.CreateCheckoutSessionCommand;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts payment create and cancel intents: one short transaction records the order, payment, provider operation,
 * its recovery task and the idempotency record. No provider call happens here; executors send operations afterwards.
 */
final class PaymentIntentService {
    private final PaymentIntentRepository intents;
    private final PaymentRecordRepository records;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Supplier<StripeDeployment> stripeDeployment;

    PaymentIntentService(
            PaymentIntentRepository intents,
            PaymentRecordRepository records,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock,
            Supplier<StripeDeployment> stripeDeployment) {
        this.intents = intents;
        this.records = records;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.stripeDeployment = stripeDeployment;
    }

    Intent prepare(TenantContext tenant, String key, PaymentRequests.Create request, String provider) {
        TenantAccessGuard.requireScope(tenant, "payments:write");
        PaymentRequests.idempotencyKey(key);
        return tx.execute(status -> {
            intents.lock(scopeKey(tenant) + ":CREATE_PAYMENT:" + key);
            var prior = intents.findIdempotencyRecord(tenant, "CREATE_PAYMENT", key);
            if (prior.isPresent()) return replay(prior.get(), request.fingerprint());
            TenantAccessGuard.requireCurrency(tenant, request.currency());
            intents.lock(scopeKey(tenant) + ":ORDER:" + request.orderKey());
            if (intents.orderKeyExists(tenant, request.orderKey()))
                throw PaymentIntentRules.rejected(ErrorCode.ORDER_INTENT_CONFLICT);
            requireBinding(tenant, provider);
            PriceQuote price = intents.findPrice(tenant, request.productRef(), request.priceVersion(), clock.instant())
                    .orElseThrow(() -> PaymentIntentRules.rejected(ErrorCode.NOT_FOUND));
            RevenueShareService.validatePolicy(decode(price.policySnapshot()));
            long amount = PaymentIntentRules.orderAmount(
                    price, request.quantity(), request.amountMinor(), request.currency());

            UUID order = PaymentIds.newId(), payment = PaymentIds.newId(), operation = UUID.randomUUID();
            Instant expires = PaymentIntentRules.checkoutExpiry(clock.instant());
            intents.insertOrder(
                    tenant,
                    new NewOrder(
                            order,
                            request.currency(),
                            request.orderKey(),
                            request.fingerprint(),
                            request.productRef(),
                            request.priceVersion(),
                            request.quantity(),
                            price,
                            amount));
            intents.insertPayment(tenant, payment, request.currency(), order, amount, expires);
            var metadata = PaymentIntentRules.checkoutMetadata(request.metadata(), tenant, payment, order);
            var command = new CreateCheckoutSessionCommand(
                    new ProviderScope(
                            tenant.organizationId(), tenant.environmentId(), tenant.allowedProviderAccountId(), false),
                    "pay_create_" + operation,
                    amount,
                    request.currency(),
                    request.successUrl(),
                    request.cancelUrl(),
                    metadata,
                    metadata,
                    expires);
            intents.insertOperation(
                    tenant,
                    new NewOperation(
                            operation,
                            request.currency(),
                            payment,
                            "CREATE_CHECKOUT",
                            command.operationIdempotencyKey(),
                            request.fingerprint(),
                            encode(command),
                            expires),
                    clock.instant());
            intents.insertIdempotencyRecord(
                    tenant, request.currency(), "CREATE_PAYMENT", key, request.fingerprint(), payment, operation);
            return new Intent(payment, operation, false);
        });
    }

    /** Every cancel request for a payment shares its one CANCEL_PAYMENT operation. */
    Intent prepareCancel(
            TenantContext tenant, UUID paymentId, String key, PaymentRequests.Cancel request, String provider) {
        TenantAccessGuard.requireScope(tenant, "payments:write");
        PaymentRequests.idempotencyKey(key);
        return tx.execute(status -> {
            intents.lock(scopeKey(tenant) + ":CANCEL_PAYMENT:" + key);
            var prior = intents.findIdempotencyRecord(tenant, "CANCEL_PAYMENT", key);
            if (prior.isPresent()) return replay(prior.get(), request.fingerprint(paymentId));
            PaymentRecord payment = scoped(tenant, paymentId, true);
            requireBinding(tenant, provider);
            PaymentIntentRules.requireCancelable(payment);
            var existing = intents.findCancelOperation(paymentId);
            UUID operation = existing.orElseGet(UUID::randomUUID);
            if (existing.isEmpty())
                intents.insertOperation(
                        tenant,
                        new NewOperation(
                                operation,
                                payment.currency(),
                                paymentId,
                                "CANCEL_PAYMENT",
                                "pay_cancel_" + operation,
                                request.fingerprint(paymentId),
                                encode(request),
                                payment.expiresAt()),
                        clock.instant());
            intents.insertIdempotencyRecord(
                    tenant,
                    payment.currency(),
                    "CANCEL_PAYMENT",
                    key,
                    request.fingerprint(paymentId),
                    paymentId,
                    operation);
            return new Intent(paymentId, operation, existing.isPresent());
        });
    }

    /** Runs inside the caller's transaction. */
    void requireBinding(TenantContext tenant, String provider) {
        PaymentIntentRules.requireBinding(intents.findProviderBinding(tenant), provider, tenant, stripeDeployment);
    }

    /** The payment if it is in the tenant's scope and currencies; otherwise NOT_FOUND, never FORBIDDEN. */
    PaymentRecord scoped(TenantContext tenant, UUID paymentId, boolean lock) {
        TenantAccessGuard.require(tenant);
        return records.findScoped(tenant, paymentId, lock)
                .filter(payment -> tenant.allowedCurrencies().contains(payment.currency()))
                .orElseThrow(() -> PaymentIntentRules.rejected(ErrorCode.NOT_FOUND));
    }

    private static Intent replay(IdempotencyRecord prior, String requestFingerprint) {
        PaymentIntentRules.requireSameRequest(prior, requestFingerprint);
        return new Intent(prior.paymentId(), prior.operationId(), true);
    }

    private static String scopeKey(TenantContext tenant) {
        return tenant.organizationId() + ":" + tenant.environmentId() + ":" + tenant.applicationId();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode controlled payment command", e);
        }
    }

    private JsonNode decode(String value) {
        try {
            return json.readValue(value, JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted payment command", e);
        }
    }
}
