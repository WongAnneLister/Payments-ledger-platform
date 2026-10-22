package com.paymentsledger.payment;

import static com.paymentsledger.payment.domain.ReconciliationSourceRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.FinancialReplayService;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.ReconciliationSourceRules;
import com.paymentsledger.payment.repository.PaymentRepositories;
import com.paymentsledger.payment.repository.ReconciliationSourceRepository;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L3 only links existing server-proven orders. Provider I/O never runs inside local transactions. */
public final class ReconciliationSourceRecovery {
    public record Claim(UUID taskId, UUID runId, UUID btId, ProviderScope scope, UUID token, long version) {}

    private record Query(ChargeFact charge, PaymentIntentFact intent, Map<String, Object> evidence, String problem) {}

    private record Linked(String outcome, UUID paymentId) {}

    private final ReconciliationSourceRepository sources;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ChargePort charges;
    private final PaymentIntentPort intents;
    private final PaymentRepository payments;
    private final FinancialReplayService replay;
    private final Clock clock;

    public ReconciliationSourceRecovery(
            PaymentRepositories store,
            PlatformTransactionManager manager,
            ObjectMapper json,
            ChargePort charges,
            PaymentIntentPort intents,
            PaymentRepository payments,
            FinancialReplayService replay,
            Clock clock) {
        this.sources = store.reconciliationSources();
        this.tx = new TransactionTemplate(manager);
        this.json = json.copy().findAndRegisterModules();
        this.charges = charges;
        this.intents = intents;
        this.payments = payments;
        this.replay = replay;
        this.clock = clock;
    }

    public UUID enqueue(ProviderScope scope, UUID runId, String btId) {
        return transaction(() -> {
            active(scope);
            var run = run(scope, runId);
            var bt = sources.balanceTransaction(scope, btId).orElse(null);
            if (!recoverableCapture(bt, run.currency()))
                throw new IllegalArgumentException("Scoped capture BT required");
            if (!sources.enumerated(run.importWindowId(), btId))
                throw new IllegalArgumentException("BT is not enumerated in this reconciliation run");
            return sources.insertTask(scope, run, bt.id(), clock.instant());
        });
    }

    public List<Claim> claim(ProviderScope scope, UUID runId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Bounded claim limit required");
        return transaction(() -> {
            active(scope);
            run(scope, runId);
            var result = new ArrayList<Claim>();
            for (var task : sources.lockClaimable(scope, runId, clock.instant(), limit)) {
                UUID token = UUID.randomUUID();
                long version = task.version() + 1;
                sources.lease(task.id(), token, clock.instant().plusSeconds(60), version, clock.instant());
                result.add(new Claim(task.id(), runId, task.sourceBtId(), scope, token, version));
            }
            return List.copyOf(result);
        });
    }

    public void drain(ProviderScope scope, UUID runId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Bounded drain limit required");
        PaymentRepository.outsideTransaction();
        active(scope);
        var run = run(scope, runId);
        for (String bt : sources.unqueued(scope, run, limit)) enqueue(scope, runId, bt);
        for (Claim claim : claim(scope, runId, limit)) recover(claim);
    }

    public String recover(Claim claim) {
        PaymentRepository.outsideTransaction();
        active(claim.scope());
        run(claim.scope(), claim.runId());
        var bt = sources.balanceTransaction(claim.scope(), claim.btId())
                .orElseThrow(() -> new IllegalArgumentException("Missing scoped source BT"));
        Query read = query(claim.scope(), bt);
        Linked linked = transaction(() -> link(claim, bt, read));
        if (linked.paymentId() != null) replay.replay(claim.scope(), bt.btId());
        return linked.outcome();
    }

    private Query query(ProviderScope scope, SourceBalanceTransaction bt) {
        var evidence = new LinkedHashMap<String, Object>();
        if (charges == null || intents == null)
            return new Query(null, null, Map.of("classification", "UNAVAILABLE"), READ_UNAVAILABLE);
        try {
            var chargeRead = charges.queryCharge(scope, Objects.requireNonNull(bt.sourceObjectId()));
            evidence.put("charge", readEvidence(chargeRead));
            if (!trusted(chargeRead)) return new Query(null, null, evidence, READ_UNAVAILABLE);
            ChargeFact charge = chargeRead.payload();
            if (charge.paymentIntentId() == null) return new Query(charge, null, evidence, EVIDENCE_CONFLICT);
            var intentRead = intents.queryPaymentIntent(scope, charge.paymentIntentId());
            evidence.put("paymentIntent", readEvidence(intentRead));
            if (!trusted(intentRead)) return new Query(charge, null, evidence, READ_UNAVAILABLE);
            boolean valid = provesCapture(bt, chargeRead, intentRead);
            return new Query(charge, intentRead.payload(), evidence, valid ? null : EVIDENCE_CONFLICT);
        } catch (RuntimeException unavailable) {
            return new Query(null, null, Map.of("classification", "READ_THROWN"), READ_UNAVAILABLE);
        }
    }

    private Linked link(Claim claim, SourceBalanceTransaction bt, Query read) {
        active(claim.scope());
        // Discover the immutable order first. No task lock or BT lock is held while acquiring rank10/20.
        OrderBinding binding = read.intent() == null
                ? null
                : sources.binding(claim.scope(), read.intent().paymentIntentId())
                        .orElse(null);
        RecoverableOrder order = null;
        if (binding != null && sources.lockOrder(binding.orderId(), claim.scope()))
            order = sources.order(binding.orderId()).orElse(null);
        ExistingPayment payment =
                order == null ? null : sources.lockPaymentForOrder(order.id()).orElse(null);
        var task = sources.lockTask(claim.taskId()).orElse(null);
        if (!owns(task, claim)) return new Linked("STALE_CLAIM", null);
        // Import conflict writers lock this BT too. Recheck under the same lock before creating a business link.
        var currentBt =
                sources.lockBalanceTransaction(claim.scope(), claim.btId()).orElse(null);
        String problem = read.problem();
        if (withdrawn(currentBt) || sources.hasEvidenceConflict(claim.btId())) problem = EVIDENCE_CONFLICT;
        if (problem == null && binding == null) problem = NO_TRUSTED_ORDER;
        if (problem == null
                && (orderMismatch(binding, order, read.charge(), bt.currency()) || !orderEligible(order, binding)))
            problem = ORDER_NOT_ELIGIBLE;
        if (problem == null && payment != null && paymentContradicts(payment, read.charge(), read.intent()))
            problem = EVIDENCE_CONFLICT;
        if (problem != null) {
            UUID observation = observe(claim, read, problem, problem);
            var disposition = disposition(problem);
            complete(claim, disposition.taskStatus(), problem);
            exception(claim, bt, problem, observation);
            return new Linked(disposition.outcome(), null);
        }
        UUID observation = observe(claim, read, "LINKED", null);
        String digest = sources.observationDigest(observation);
        UUID paymentId = payment == null ? UUID.randomUUID() : payment.id();
        if (payment == null) {
            sources.insertPayment(
                    paymentId, order, clock.instant().plusSeconds(1), claim.runId(), claim.btId(), digest);
            sources.insertProvenance(
                    paymentId,
                    claim.scope(),
                    order,
                    claim.runId(),
                    claim.btId(),
                    binding.id(),
                    observation,
                    read.charge().chargeId(),
                    read.intent().paymentIntentId(),
                    digest);
        }
        // Existing repository owns normalized capture/attempt/control/share and canonical notification updates.
        if (!payments.mergeReconciliationQuery(
                paymentId, new PaymentFacts(null, read.intent(), List.of(read.charge()), true, false, clock.instant())))
            throw new IllegalStateException("Verified source merge did not apply");
        complete(claim, "DONE", "LINKED");
        return new Linked("LINKED", paymentId);
    }

    private boolean orderEligible(RecoverableOrder order, OrderBinding binding) {
        return !bindingExpired(binding, clock.instant()) && sources.fulfillmentEligible(order);
    }

    private UUID observe(Claim claim, Query read, String outcome, String reason) {
        String encoded = encode(observedFact(read.charge(), read.intent()));
        String ev = encode(read.evidence());
        String digest = sha(encoded + ev);
        UUID id = UUID.randomUUID();
        sources.insertObservation(id, claim.scope(), claim.runId(), claim.btId(), outcome, reason, encoded, ev, digest);
        return id;
    }

    private void exception(Claim claim, SourceBalanceTransaction bt, String reason, UUID observation) {
        // Serialize with finish: operational observations remain append-only while frozen reports never change.
        if (!sources.lockOpenRun(claim.runId())) return;
        sources.insertException(
                claim.runId(),
                claim.scope(),
                bt.currency(),
                reason,
                bt.btId(),
                encode(Map.of("sourceObservationId", observation)));
    }

    private void complete(Claim claim, String status, String reason) {
        int changed = sources.complete(
                claim.taskId(),
                claim.token(),
                claim.version(),
                status,
                reason,
                clock.instant().plusSeconds(300),
                clock.instant());
        if (changed != 1) throw new IllegalStateException("L3 completion ownership changed");
    }

    private boolean owns(SourceTask task, Claim claim) {
        return ReconciliationSourceRules.owns(
                task, claim.scope(), claim.runId(), claim.btId(), claim.token(), claim.version(), clock.instant());
    }

    private SourceRun run(ProviderScope scope, UUID id) {
        var run = sources.run(scope, id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scoped reconciliation run"));
        if (!sources.currencyEnabled(scope, run.currency()))
            throw new IllegalArgumentException("Active scoped currency required");
        return run;
    }

    private void active(ProviderScope scope) {
        if (scope == null || scope.livemode()) throw new IllegalArgumentException("Test scope required");
        if (!sources.activeTestScope(scope))
            throw new IllegalArgumentException("Active trusted provider scope required");
    }

    private <T> T transaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(s -> work.get());
            } catch (RuntimeException failure) {
                boolean retry = false;
                for (Throwable t = failure; t != null; t = t.getCause())
                    if (t instanceof java.sql.SQLException sql
                            && Set.of("40P01", "40001").contains(sql.getSQLState())) retry = true;
                if (!retry || attempt >= 2) throw failure;
            }
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid controlled recovery evidence", e);
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
