package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.adapter.stripe.StripeGateway.*;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.stripe.model.Refund;
import com.stripe.param.RefundCreateParams;
import java.util.*;

public final class StripeRefundAdapter implements RefundPort {
    private final StripeGateway gateway;

    public StripeRefundAdapter(StripeGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand command) {
        return gateway.call(command.scope(), command.operationIdempotencyKey(), c -> {
            var params = RefundCreateParams.builder().setAmount(command.amountMinor());
            if (command.chargeId() != null) params.setCharge(command.chargeId());
            else params.setPaymentIntent(command.paymentIntentId());
            if (command.reason() != null) {
                var reason = Arrays.stream(RefundCreateParams.Reason.values())
                        .filter(r -> r.getValue().equals(command.reason()))
                        .findFirst();
                if (reason.isEmpty()) throw reject("unsupported_refund_reason");
                params.setReason(reason.get());
            }
            var raw = c.fetch(() -> c.client().v1().refunds().create(params.build(), c.options()));
            if (raw.getAmount() == null
                    || raw.getAmount() != command.amountMinor()
                    || command.chargeId() != null && !command.chargeId().equals(raw.getCharge())
                    || command.paymentIntentId() != null
                            && !command.paymentIntentId().equals(raw.getPaymentIntent())) throw mismatch();
            return refund(raw);
        });
    }

    @Override
    public ProviderCallResult<RefundFact> queryRefund(ProviderScope scope, String id) {
        return gateway.call(scope, null, c -> {
            var raw = c.fetch(() -> c.client().v1().refunds().retrieve(required(id), c.options()));
            if (!id.equals(raw.getId())) throw mismatch();
            return refund(raw);
        });
    }

    private static RefundFact refund(Refund value) {
        // Refund has no livemode field in this API; mode follows the verified test credential/account.
        if (!"refund".equals(value.getObject())) throw mismatch();
        return new RefundFact(
                value.getId(),
                required(value.getCharge()),
                required(value.getStatus()),
                value.getAmount(),
                value.getCurrency(),
                value.getBalanceTransaction(),
                value.getFailureReason(),
                value.getFailureBalanceTransaction(),
                instant(value.getCreated()));
    }
}
