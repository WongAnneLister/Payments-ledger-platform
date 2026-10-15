package com.paymentsledger.payment.domain;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.identity.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure rules for accepting payment create and cancel intents.
 *
 * <ul>
 *   <li>An idempotency key replays its original intent only for the same request; a changed request is a conflict.
 *   <li>The server prices the order from its active price list; a client amount that disagrees is rejected.
 *   <li>A request is charged only through the application's active test provider account, and a Stripe request only
 *       through the single Stripe account this deployment is configured for.
 *   <li>A payment can be canceled until money is captured or its evidence conflicts.
 * </ul>
 */
public final class PaymentIntentRules {
    /** How long a hosted checkout stays payable. */
    public static final long CHECKOUT_LIFETIME_SECONDS = 3600;

    private PaymentIntentRules() {}

    public static ApiException rejected(ErrorCode code) {
        return new ApiException(code, "Payment request cannot be completed: " + code.name());
    }

    public static void requireSameRequest(IdempotencyRecord stored, String requestFingerprint) {
        if (!requestFingerprint.equals(stored.requestFingerprint())) throw rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /** Charges only through the bound, active provider account; see the class comment for Stripe. */
    public static void requireBinding(
            Optional<ProviderBinding> binding,
            String provider,
            TenantContext tenant,
            Supplier<StripeDeployment> stripeDeployment) {
        if (binding.isEmpty()) throw rejected(ErrorCode.FORBIDDEN);
        if (provider == null || !provider.equals(binding.get().provider()))
            throw rejected(ErrorCode.SERVICE_UNAVAILABLE);
        if (provider.equals("STRIPE")) {
            StripeDeployment deployment = stripeDeployment.get();
            if (!Objects.equals(binding.get().providerAccountRef(), deployment.accountId())
                    || !tenant.organizationId().toString().equals(deployment.organizationId())
                    || !tenant.environmentId().toString().equals(deployment.environmentId())
                    || !tenant.allowedProviderAccountId().toString().equals(deployment.providerAccountId()))
                throw rejected(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** The order total from the server's unit price; the client's amount and currency must agree with it. */
    public static long orderAmount(
            PriceQuote quote, int quantity, long requestedAmountMinor, String requestedCurrency) {
        long amount;
        try {
            amount = Math.multiplyExact(quote.unitAmountMinor(), quantity);
        } catch (ArithmeticException overflow) {
            throw rejected(ErrorCode.AMOUNT_MISMATCH);
        }
        if (amount != requestedAmountMinor || !requestedCurrency.equals(quote.currency()))
            throw rejected(ErrorCode.AMOUNT_MISMATCH);
        return amount;
    }

    /** Whole seconds, so the provider and the database agree on the same expiry instant. */
    public static Instant checkoutExpiry(Instant now) {
        return now.plusSeconds(CHECKOUT_LIFETIME_SECONDS).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Metadata sent to the provider on both the session and the payment intent. Platform identifiers override any
     * merchant key of the same name, so provider objects can always be traced back to their payment.
     */
    public static Map<String, String> checkoutMetadata(
            Map<String, String> merchantMetadata, TenantContext tenant, UUID paymentId, UUID businessOrderId) {
        var metadata = new TreeMap<>(merchantMetadata);
        metadata.put("payment_id", paymentId.toString());
        metadata.put("business_order_id", businessOrderId.toString());
        metadata.put("organization_id", tenant.organizationId().toString());
        metadata.put("application_id", tenant.applicationId().toString());
        metadata.put("environment_id", tenant.environmentId().toString());
        metadata.put("provider_account_id", tenant.allowedProviderAccountId().toString());
        return metadata;
    }

    public static void requireCancelable(PaymentRecord payment) {
        if (payment.resolution() == ResolutionStatus.CONFLICT) throw rejected(ErrorCode.EVIDENCE_CONFLICT);
        if (payment.status() == PaymentStatus.SUCCEEDED || payment.capturedAmountMinor() > 0)
            throw rejected(ErrorCode.PAYMENT_NOT_CANCELABLE);
    }
}
