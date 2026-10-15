package com.paymentsledger.payment;

import static com.paymentsledger.payment.PaymentRepository.*;

import com.paymentsledger.common.error.*;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Commands execute only after committed leases; recovery chooses whether an uncertain command is safe. */
@Component
public class PaymentExecutor {
    private final PaymentRepository repository;
    private final CheckoutPort checkout;
    private final PaymentIntentPort intents;
    private final ChargePort charges;
    private final String providerName;

    @Autowired
    public PaymentExecutor(
            PaymentRepository repository,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<PaymentIntentPort> intents,
            ObjectProvider<ChargePort> charges,
            Environment environment) {
        this(
                repository,
                checkout.getIfAvailable(),
                intents.getIfAvailable(),
                charges.getIfAvailable(),
                environment.getProperty("platform.stripe.enabled", Boolean.class, false)
                        ? "STRIPE"
                        : environment.getProperty("platform.provider.fake.enabled", Boolean.class, false)
                                ? "FAKE"
                                : null);
    }

    public PaymentExecutor(
            PaymentRepository repository,
            CheckoutPort checkout,
            PaymentIntentPort intents,
            ChargePort charges,
            String providerName) {
        this.repository = repository;
        this.checkout = checkout;
        this.intents = intents;
        this.charges = charges;
        this.providerName = providerName;
    }

    String providerName() {
        return checkout == null ? null : providerName;
    }

    public void drainPrepared(int limit) {
        outsideTransaction();
        if (checkout == null) return;
        for (UUID id : repository.prepared(limit, providerName)) {
            try {
                execute(id);
            } catch (ApiException unavailable) {
                /* A disabled binding remains durable and unsent. */
            }
        }
    }

    public void execute(UUID operation) {
        outsideTransaction();
        if (checkout == null) return;
        var claim = repository.claim(operation, providerName);
        if (claim == null) return;
        executeClaim(claim);
    }

    void executeClaim(Claim claim) {
        outsideTransaction();
        if (claim.type().equals("CREATE_CHECKOUT")) {
            if (repository.deferCreateWithRemoteFact(claim)) return;
            var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
            repository.finishCreate(
                    claim, call(() -> checkout.createSession(command), command.operationIdempotencyKey()));
        } else cancel(claim);
    }

    private <T> ProviderCallResult<T> call(Supplier<ProviderCallResult<T>> action, String key) {
        outsideTransaction();
        Instant start = repository.now();
        try {
            return action.get();
        } catch (RuntimeException failed) {
            // Exception messages may contain secrets: retain only a fixed redacted classification.
            return new ProviderCallResult<>(
                    ProviderCallClassification.TIMEOUT_UNKNOWN,
                    null,
                    new ProviderEvidence(null, key, start, null, null, "port_exception", null, null));
        }
    }

    private ProviderScope scope(Map<String, Object> p) {
        return new ProviderScope(
                uuid(p, "organization_id"), uuid(p, "environment_id"), uuid(p, "provider_account_id"), false);
    }

    private static boolean success(ProviderCallResult<?> result) {
        return result.payload() != null
                && (result.classification() == ProviderCallClassification.SUCCESS
                        || result.classification() == ProviderCallClassification.AUTH_REQUIRED);
    }

    public void refresh(UUID payment) {
        outsideTransaction();
        var p = repository.payment(payment);
        if (p == null || p.get("checkout_session_id") == null && p.get("payment_intent_id") == null) return;
        // Recovered captures have immutable source provenance, not a CREATE_CHECKOUT operation.
        // L3 refreshes their provider evidence through its existing durable task.
        if ("RECONCILIATION".equals(p.get("origin"))) return;
        var query = query(p);
        repository.mergeQuery(payment, repository.createOperation(payment), query.observations(), query.facts());
    }

    ProviderQueryService.Queried query(Map<String, Object> payment) {
        return new ProviderQueryService(repository, checkout, intents, charges).query(payment);
    }

    private boolean unpaid(PaymentFacts facts) {
        return ProviderQueryService.unpaid(facts);
    }

    void cancel(Claim claim) {
        try (var budget = ProviderRequestBudget.openOrJoin(32)) {
            cancelWithinBudget(claim);
        }
    }

    private void cancelWithinBudget(Claim claim) {
        if (claim.payment().get("checkout_session_id") == null) {
            if (repository.cancelUnsent(claim)) return;
            repository.finishCancel(
                    claim, List.of(), new PaymentFacts(null, null, List.of(), false, false, repository.now()), false);
            return;
        }
        var before = new ProviderQueryService(repository, checkout, intents, charges)
                .budget(15)
                .query(claim.payment());
        var facts = before.facts();
        var observations = new ArrayList<>(before.observations());
        boolean canceled = false;
        if (unpaid(facts)) {
            if (facts.session().status() == CheckoutSessionStatus.OPEN) {
                String sessionId = facts.session().sessionId();
                var result = call(
                        () -> checkout.expireSession(scope(claim.payment()), sessionId),
                        str(claim.operation(), "operation_key"));
                observations.add(new Observation(
                        "EXPIRE_SESSION",
                        result,
                        result.payload() == null
                                ? facts.session().sessionId()
                                : result.payload().sessionId()));
                if (success(result)
                        && result.payload().sessionId().equals(facts.session().sessionId())
                        && matchesMetadata(result.payload().metadata(), claim.paymentId())
                        && result.payload().status() == CheckoutSessionStatus.EXPIRED) {
                    var after = new ProviderQueryService(repository, checkout, intents, charges)
                            .budget(15)
                            .query(claim.payment());
                    observations.addAll(after.observations());
                    facts = after.facts();
                    canceled = unpaid(facts) && facts.session().status() == CheckoutSessionStatus.EXPIRED;
                } else {
                    var after = new ProviderQueryService(repository, checkout, intents, charges)
                            .budget(15)
                            .query(claim.payment());
                    observations.addAll(after.observations());
                    facts = after.facts();
                }
            } else if (facts.session().status() == CheckoutSessionStatus.EXPIRED) canceled = true;
        }
        // Persist uncertain cancellation separately even if the query has known facts.
        if (!canceled && facts.charges().stream().noneMatch(ChargeFact::captured))
            facts = new PaymentFacts(
                    facts.session(),
                    facts.intent(),
                    facts.charges(),
                    false,
                    facts.conflict(),
                    facts.confirmedAt(),
                    facts.paymentVersion());
        repository.finishCancel(claim, observations, facts, canceled);
    }
}
