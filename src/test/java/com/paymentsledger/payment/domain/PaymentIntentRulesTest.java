package com.paymentsledger.payment.domain;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class PaymentIntentRulesTest {
    static final TenantContext TENANT = new TenantContext(
            new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), Set.of("USD"), false, Set.of());
    static final StripeDeployment DEPLOYED = new StripeDeployment(
            "acct_1",
            TENANT.organizationId().toString(),
            TENANT.environmentId().toString(),
            TENANT.allowedProviderAccountId().toString());

    static void rejects(Executable action, ErrorCode code) {
        assertThatThrownBy(action::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo("Payment request cannot be completed: " + code.name());
        });
    }

    @Test
    void replayRequiresTheSameRequest() {
        var stored = new IdempotencyRecord("fp-1", new UUID(0, 7), new UUID(0, 8));
        PaymentIntentRules.requireSameRequest(stored, "fp-1");
        rejects(() -> PaymentIntentRules.requireSameRequest(stored, "fp-2"), ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void bindingMustBeActiveAndMatchTheProvider() {
        rejects(
                () -> PaymentIntentRules.requireBinding(Optional.empty(), "FAKE", TENANT, () -> DEPLOYED),
                ErrorCode.FORBIDDEN);
        var fake = Optional.of(new ProviderBinding("FAKE", "fixture"));
        PaymentIntentRules.requireBinding(fake, "FAKE", TENANT, () -> {
            throw new AssertionError("a non-Stripe binding does not read the Stripe deployment");
        });
        rejects(
                () -> PaymentIntentRules.requireBinding(fake, "STRIPE", TENANT, () -> DEPLOYED),
                ErrorCode.SERVICE_UNAVAILABLE);
        rejects(
                () -> PaymentIntentRules.requireBinding(fake, null, TENANT, () -> DEPLOYED),
                ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void stripeRequestsMustTargetTheConfiguredAccount() {
        var stripe = Optional.of(new ProviderBinding("STRIPE", "acct_1"));
        PaymentIntentRules.requireBinding(stripe, "STRIPE", TENANT, () -> DEPLOYED);
        rejects(
                () -> PaymentIntentRules.requireBinding(
                        Optional.of(new ProviderBinding("STRIPE", "acct_other")), "STRIPE", TENANT, () -> DEPLOYED),
                ErrorCode.SERVICE_UNAVAILABLE);
        for (var deployment : new StripeDeployment[] {
            new StripeDeployment("acct_1", "other", DEPLOYED.environmentId(), DEPLOYED.providerAccountId()),
            new StripeDeployment("acct_1", DEPLOYED.organizationId(), "other", DEPLOYED.providerAccountId()),
            new StripeDeployment("acct_1", DEPLOYED.organizationId(), DEPLOYED.environmentId(), "other"),
            new StripeDeployment(null, null, null, null)
        })
            rejects(
                    () -> PaymentIntentRules.requireBinding(stripe, "STRIPE", TENANT, () -> deployment),
                    ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void serverPricesTheOrder() {
        var quote = new PriceQuote(2500, "USD", "{}", "{}");
        assertThat(PaymentIntentRules.orderAmount(quote, 4, 10000, "USD")).isEqualTo(10000);
        rejects(() -> PaymentIntentRules.orderAmount(quote, 4, 9999, "USD"), ErrorCode.AMOUNT_MISMATCH);
        rejects(() -> PaymentIntentRules.orderAmount(quote, 4, 10000, "JPY"), ErrorCode.AMOUNT_MISMATCH);
        rejects(
                () -> PaymentIntentRules.orderAmount(new PriceQuote(Long.MAX_VALUE, "USD", "{}", "{}"), 2, 1, "USD"),
                ErrorCode.AMOUNT_MISMATCH);
    }

    @Test
    void checkoutExpiresInOneHourOnAWholeSecond() {
        assertThat(PaymentIntentRules.checkoutExpiry(Instant.parse("2026-10-15T10:00:00.987654Z")))
                .isEqualTo(Instant.parse("2026-10-15T11:00:00Z"));
    }

    @Test
    void platformIdentifiersOverrideMerchantMetadata() {
        UUID payment = new UUID(0, 9), order = new UUID(0, 10);
        var metadata = PaymentIntentRules.checkoutMetadata(
                Map.of("cart", "c-1", "payment_id", "spoofed"), TENANT, payment, order);
        assertThat(metadata)
                .containsEntry("cart", "c-1")
                .containsEntry("payment_id", payment.toString())
                .containsEntry("business_order_id", order.toString())
                .containsEntry(
                        "provider_account_id", TENANT.allowedProviderAccountId().toString());
        assertThat(java.util.List.copyOf(metadata.keySet())).isSorted();
    }

    @Test
    void cancellationStopsAtCaptureOrConflict() {
        PaymentIntentRules.requireCancelable(payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.KNOWN, 0));
        PaymentIntentRules.requireCancelable(payment(PaymentStatus.PROCESSING, ResolutionStatus.UNKNOWN, 0));
        rejects(
                () -> PaymentIntentRules.requireCancelable(
                        payment(PaymentStatus.AWAITING_CUSTOMER, ResolutionStatus.CONFLICT, 0)),
                ErrorCode.EVIDENCE_CONFLICT);
        rejects(
                () -> PaymentIntentRules.requireCancelable(payment(PaymentStatus.SUCCEEDED, ResolutionStatus.KNOWN, 0)),
                ErrorCode.PAYMENT_NOT_CANCELABLE);
        rejects(
                () -> PaymentIntentRules.requireCancelable(
                        payment(PaymentStatus.PROCESSING, ResolutionStatus.KNOWN, 1)),
                ErrorCode.PAYMENT_NOT_CANCELABLE);
    }

    static PaymentRecord payment(PaymentStatus status, ResolutionStatus resolution, long captured) {
        Instant t = Instant.parse("2026-10-15T10:00:00Z");
        return new PaymentRecord(
                new UUID(0, 9),
                new UUID(0, 10),
                "order",
                10000,
                "USD",
                status,
                "open",
                resolution,
                FinancialStatus.NOT_APPLICABLE,
                captured,
                "cs",
                "pi",
                null,
                "https://pay.test",
                t,
                t,
                t,
                null);
    }
}
