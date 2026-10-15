package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.adapter.stripe.StripeGateway.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.param.ChargeListParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.*;

/** Checkout creates a hosted session; successful session creation is not a payment effect. */
public final class StripeCheckoutAdapter implements CheckoutPort, PaymentIntentPort, ChargePort {
    private final StripeGateway gateway;

    public StripeCheckoutAdapter(StripeGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand command) {
        return gateway.call(command.scope(), command.operationIdempotencyKey(), c -> {
            var params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                    .setSuccessUrl(command.successUrl())
                    .setCancelUrl(command.cancelUrl())
                    .setExpiresAt(command.expiresAt().getEpochSecond())
                    .putAllMetadata(command.sessionMetadata())
                    .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                            .setCaptureMethod(SessionCreateParams.PaymentIntentData.CaptureMethod.AUTOMATIC)
                            .putAllMetadata(command.paymentIntentMetadata())
                            .build())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(command.currency().toLowerCase(Locale.ROOT))
                                    .setUnitAmount(command.amountMinor())
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("Payment")
                                            .build())
                                    .build())
                            .build())
                    .build();
            return session(c.fetch(() -> c.client().v1().checkout().sessions().create(params, c.options())));
        });
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var raw = c.fetch(() -> c.client().v1().checkout().sessions().retrieve(required(id), c.options()));
            if (!id.equals(raw.getId())) throw mismatch();
            return session(raw);
        });
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope scope, String id) {
        // Stripe rejects non-open sessions. Never translate this into a PaymentIntent cancellation.
        return gateway.call(scope, "checkout-expire:" + digest(List.of(scope, required(id))), c -> {
            var raw = c.fetch(() -> c.client().v1().checkout().sessions().expire(required(id), c.options()));
            if (!id.equals(raw.getId())) throw mismatch();
            return session(raw);
        });
    }

    @Override
    public ProviderCallResult<PaymentIntentFact> queryPaymentIntent(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var pi = c.fetch(() -> c.client().v1().paymentIntents().retrieve(required(id), c.options()));
            testMode(pi.getLivemode());
            if (!"payment_intent".equals(pi.getObject()) || !id.equals(pi.getId())) throw mismatch();
            var charges = attempts(c, id);
            if (pi.getLatestCharge() != null
                    && charges.stream().noneMatch(ch -> pi.getLatestCharge().equals(ch.chargeId()))) {
                if (charges.isEmpty()) throw empty();
                throw mismatch();
            }
            return new PaymentIntentFact(
                    pi.getId(),
                    required(pi.getStatus()),
                    pi.getAmount(),
                    pi.getAmountReceived(),
                    pi.getCurrency(),
                    charges.stream().map(ChargeFact::chargeId).toList(),
                    pi.getLatestCharge(),
                    pi.getNextAction() == null
                            ? null
                            : required(pi.getNextAction().getType()),
                    metadata(pi.getMetadata()),
                    instant(pi.getCreated()));
        });
    }

    @Override
    public ProviderCallResult<ChargeFact> queryCharge(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var value = c.fetch(() -> c.client().v1().charges().retrieve(required(id), c.options()));
            if (!id.equals(value.getId())) throw mismatch();
            return charge(value);
        });
    }

    @Override
    public ProviderCallResult<List<ChargeFact>> listChargeAttempts(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var attempts = attempts(c, required(id));
            if (attempts.isEmpty()) throw empty();
            return attempts;
        });
    }

    private static List<ChargeFact> attempts(StripeGateway.Call c, String id) throws StripeException {
        List<ChargeFact> facts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int pages = 0; pages < 1000; pages++) {
            var params = ChargeListParams.builder()
                    .setPaymentIntent(id)
                    .setLimit(100L)
                    .setStartingAfter(cursor)
                    .build();
            var page = c.fetch(() -> c.client().v1().charges().list(params, c.options()));
            if (page.getData() == null || page.getHasMore() == null) throw mismatch();
            for (Charge raw : page.getData()) {
                var fact = charge(raw);
                if (!id.equals(fact.paymentIntentId()) || !seen.add(fact.chargeId())) throw mismatch();
                facts.add(fact);
            }
            if (!page.getHasMore()) return List.copyOf(facts);
            if (page.getData().isEmpty()) throw mismatch();
            cursor = page.getData().getLast().getId();
        }
        // Never report a truncated attempt history as complete.
        throw mismatch();
    }

    static CheckoutSessionFact session(Session value) {
        testMode(value.getLivemode());
        if (!"checkout.session".equals(value.getObject())) throw mismatch();
        var status = CheckoutSessionStatus.valueOf(required(value.getStatus()).toUpperCase(Locale.ROOT));
        if (status == CheckoutSessionStatus.OPEN) {
            String url = required(value.getUrl());
            var uri = java.net.URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw mismatch();
        }
        return new CheckoutSessionFact(
                value.getId(),
                status,
                value.getUrl(),
                value.getPaymentIntent(),
                required(value.getPaymentStatus()),
                instant(value.getExpiresAt()),
                metadata(value.getMetadata()));
    }

    static ChargeFact charge(Charge value) {
        testMode(value.getLivemode());
        if (!"charge".equals(value.getObject()) || value.getCaptured() == null) throw mismatch();
        return new ChargeFact(
                value.getId(),
                value.getPaymentIntent(),
                required(value.getStatus()),
                value.getCaptured(),
                value.getAmount(),
                value.getAmountCaptured(),
                value.getCurrency(),
                value.getBalanceTransaction(),
                value.getFailureCode(),
                value.getOutcome() == null ? null : value.getOutcome().getType(),
                instant(value.getCreated()));
    }
}
