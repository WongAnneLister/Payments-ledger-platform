package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.*;
import com.paymentsledger.pr18.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class Pr18SequenceIT extends Pr18Fixture {
    final SequenceGenerator generator = new SequenceGenerator();
    long steps;
    final Map<String, Long> counts = new TreeMap<>();

    @Test
    void generatedCommandsMatchIndependentModelsAfterEveryCommit() {
        long started = System.nanoTime();
        Long replaySeed = Long.getLong("pr18.caseSeed");
        long base = Long.getLong("pr18.seed", 180018L);
        int cases = replaySeed == null ? Integer.getInteger("pr18.seeds", 100) : 1,
                max = Integer.getInteger("pr18.maxSteps", 100);
        assertThat(cases).isPositive();
        assertThat(max).isPositive();
        var actualSeeds = new ArrayList<Long>();
        var failures = new ArrayList<Long>();
        int completed = 0;
        String status = "FAIL";
        try {
            for (int index = 0; index < cases; index++) {
                long seed = replaySeed == null ? generator.caseSeed(base, index) : replaySeed;
                actualSeeds.add(seed);
                var original = generator.generate(seed, max);
                try {
                    run(seed, original, false, true);
                    completed++;
                } catch (Throwable failure) {
                    failures.add(seed);
                    var evidence = snapshot();
                    Pr18Artifacts.write(
                            "failure-" + seed + "-original.json",
                            Map.of(
                                    "generatorVersion",
                                    SequenceGenerator.VERSION,
                                    "classification",
                                    failure instanceof AssertionError ? "INVARIANT_MISMATCH" : "DRIVER_FAILURE",
                                    "baseSeed",
                                    base,
                                    "caseSeed",
                                    seed,
                                    "sequence",
                                    original,
                                    "failure",
                                    Pr18Artifacts.failure(failure),
                                    "after",
                                    evidence));
                    var fingerprint = fingerprint(failure);
                    var minimized = new SequenceShrinker().minimize(original, candidate -> {
                        try {
                            run(seed, candidate, false, false);
                            return false;
                        } catch (Throwable replayed) {
                            return fingerprint(replayed).equals(fingerprint);
                        }
                    });
                    Throwable minimizedFailure;
                    try {
                        run(seed, minimized, false, false);
                        throw new AssertionError("Minimized failure did not reproduce");
                    } catch (Throwable replayed) {
                        assertThat(fingerprint(replayed)).isEqualTo(fingerprint);
                        minimizedFailure = replayed;
                    }
                    Pr18Artifacts.write(
                            "failure-" + seed + "-minimized.json",
                            Map.of(
                                    "caseSeed",
                                    seed,
                                    "sequence",
                                    minimized,
                                    "originalSteps",
                                    original.size(),
                                    "minimizedSteps",
                                    minimized.size(),
                                    "failure",
                                    Pr18Artifacts.failure(minimizedFailure),
                                    "originalFailure",
                                    Pr18Artifacts.failure(failure),
                                    "failureIdentity",
                                    fingerprint(minimizedFailure),
                                    "generatorVersion",
                                    SequenceGenerator.VERSION,
                                    "classification",
                                    minimizedFailure instanceof AssertionError
                                            ? "INVARIANT_MISMATCH"
                                            : "DRIVER_FAILURE",
                                    "databaseProviderMessageEvidence",
                                    snapshot()));
                    throw new AssertionError(
                            "PR18 seed=" + seed + " base=" + base + " evidence=target/pr18/failure-" + seed
                                    + "-minimized.json",
                            failure);
                }
            }
            status = "PASS";
        } finally {
            var summary = new LinkedHashMap<String, Object>();
            summary.put("status", status);
            summary.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000L);
            summary.put("javaVersion", System.getProperty("java.version"));
            summary.put("postgresVersion", jdbc.queryForObject("SELECT version()", String.class));
            summary.put("runtimeRole", jdbc.queryForObject("SELECT current_user", String.class));
            summary.put("generatorVersion", SequenceGenerator.VERSION);
            summary.put("baseSeed", base);
            summary.put("requestedSeeds", cases);
            summary.put("completedSeeds", completed);
            summary.put("maxSteps", max);
            summary.put("executedSteps", steps);
            summary.put("operationCounts", counts);
            summary.put("actualCaseSeeds", actualSeeds);
            summary.put("failedSeeds", failures);
            summary.put("executionMode", "SERIAL_REAL_POSTGRESQL_SERVICES");
            summary.put("externalEvidence", "INDEPENDENT_GENERATED_PROVIDER_FIXTURE_AND_SIMULATED_BANK_EVIDENCE");
            Pr18Artifacts.write("sequence-summary.json", summary);
        }
    }

    @Test
    void deliberateMutantIsDetectedAndShrunkAgainstRealServices() {
        long seed = 180018;
        var original = generator.generate(seed, 45);
        assertThatThrownBy(() -> run(seed, original, true, false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MUTATION_ONLY");
        var originalEvidence = snapshot();
        var minimized = new SequenceShrinker().minimize(original, candidate -> {
            try {
                run(seed, candidate, true, false);
                return false;
            } catch (AssertionError mismatch) {
                return Objects.toString(mismatch.getMessage(), "").contains("MUTATION_ONLY");
            }
        });
        assertThat(minimized).hasSizeLessThan(original.size());
        assertThat(generator.legal(minimized)).isTrue();
        assertThatThrownBy(() -> run(seed, minimized, true, false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MUTATION_ONLY");
        Pr18Artifacts.write(
                "mutation-sensitivity.json",
                Map.of(
                        "status",
                        "PASS",
                        "classification",
                        "MUTATION_ONLY",
                        "mutation",
                        "test-only extra balanced share journal injected through real LedgerPostingService; oracle unchanged",
                        "caseSeed",
                        seed,
                        "original",
                        original,
                        "minimized",
                        minimized,
                        "originalEvidence",
                        originalEvidence,
                        "minimizedEvidence",
                        snapshot()));
    }

    private String fingerprint(Throwable t) {
        return Pr18FailureIdentity.of(t);
    }

    void run(long seed, List<SequenceCommand> sequence, boolean mutant, boolean record) {
        reset(seed);
        var oracle = new SequenceOracle();
        for (var c : sequence) {
            var before = lightSnapshot();
            if (record) {
                steps++;
                counts.merge(c.kind().name(), 1L, Long::sum);
            }
            try {
                execute(c, oracle);
                oracle.apply(c);
                if (mutant && c.kind() == SequenceCommand.Kind.RESERVE) {
                    UUID expense = jdbc.queryForObject("""
                            SELECT id
                            FROM platform.ledger_account
                            WHERE account_purpose='PARTNER_SHARE_EXPENSE'""", UUID.class);
                    UUID payable = jdbc.queryForObject(
                            "SELECT id FROM platform.ledger_account WHERE account_purpose='PARTNER_PAYABLE'",
                            UUID.class);
                    posting.post(
                            new LedgerScope(id(1), id(2), "USD", null),
                            new PostingCommand(
                                    "mutation-only:" + c.id(),
                                    "MUTATION_ONLY",
                                    "INTERNAL",
                                    "extra-share",
                                    json.createObjectNode().put("classification", "MUTATION_ONLY"),
                                    List.of(
                                            new PostingLine(expense, Side.DEBIT, 1),
                                            new PostingLine(payable, Side.CREDIT, 1)),
                                    null));
                    try {
                        verify(oracle, c);
                    } catch (AssertionError detected) {
                        throw new AssertionError(
                                "MUTATION_ONLY extra balanced share journal detected by unchanged oracle", detected);
                    }
                    throw new AssertionError("Mutant escaped the unchanged oracle");
                }
                verify(oracle, c);
            } catch (Throwable failure) {
                if (record)
                    Pr18Artifacts.write(
                            "failure-" + seed + "-step.json",
                            Map.of(
                                    "caseSeed",
                                    seed,
                                    "command",
                                    c,
                                    "before",
                                    before,
                                    "after",
                                    snapshot(),
                                    "failure",
                                    Pr18Artifacts.failure(failure)));
                throw failure;
            }
        }
        if (record)
            Pr18Artifacts.write(
                    "case-" + seed + ".json",
                    Map.of(
                            "seed",
                            seed,
                            "executedSteps",
                            sequence.size(),
                            "commands",
                            sequence,
                            "expected",
                            oracle.ledger.balances(),
                            "actual",
                            lightSnapshot(),
                            "reconciliationOutcomes",
                            List.copyOf(reconciliationOutcomes)));
    }

    void execute(SequenceCommand c, SequenceOracle oracle) {
        switch (c.kind()) {
            case CREATE -> createPayment();
            case PAYMENT_UNKNOWN ->
                repository.mergeQuery(
                        paymentId,
                        repository.createOperation(paymentId),
                        List.of(),
                        new PaymentRepository.Facts(
                                null,
                                null,
                                List.of(),
                                false,
                                false,
                                fake.clock().instant()));
            case PAYMENT_RECOVER -> service.get(context, paymentId);
            case PAYMENT_AUTH_REQUIRED -> {
                fake.controls()
                        .attemptPayment(
                                scope,
                                checkout,
                                com.paymentsledger.provider.fake.FakeControls.AttemptOutcome.REQUIRE_AUTH);
                service.get(context, paymentId);
            }
            case PAYMENT_DECLINED -> {
                fake.controls()
                        .attemptPayment(
                                scope, checkout, com.paymentsledger.provider.fake.FakeControls.AttemptOutcome.DECLINE);
                service.get(context, paymentId);
            }
            case CAPTURE -> capturePayment();
            case POST_CAPTURE -> post(bt("capture", "charge", 10000, 300, chargeId, "charge"));
            case RESERVE -> reserve(c.key(), c.amount());
            case REFUND_UNKNOWN -> {
                RefundPort timeout = new RefundPort() {
                    public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand ignored) {
                        return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
                    }

                    public ProviderCallResult<RefundFact> queryRefund(ProviderScope ignored, String key) {
                        throw new AssertionError("unknown refund without trusted ID must not query arbitrary ID");
                    }
                };
                UUID operation = intents.get(c.key()).operationId();
                var abandoned = repository.claim(operation, "FAKE");
                assertThat(abandoned).isNotNull();
                long held = oracle.refunds.held();
                assertThat(jdbc.queryForObject(
                                "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?",
                                Long.class,
                                paymentId))
                        .isEqualTo(held);
                fake.clock().advance(Duration.ofSeconds(31));
                var reclaimed = repository.claimRecovery(operation, "FAKE", true);
                assertThat(reclaimed).isNotNull();
                assertThat(reclaimed.token()).isNotEqualTo(abandoned.token());
                assertThat(reclaimed.operation().get("operation_key"))
                        .isEqualTo(abandoned.operation().get("operation_key"));
                new RefundExecutor(repository, refunds, timeout, "FAKE").executeClaim(reclaimed);
                var beforeStale = jdbc.queryForMap("""
                        SELECT status,resolution,version,lease_token
                        FROM platform.provider_operation
                        WHERE id=?""", operation);
                long observations = count("provider_observation");
                refunds.finish(
                        abandoned,
                        new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence()),
                        false);
                assertThat(jdbc.queryForMap("""
                                SELECT status,resolution,version,lease_token
                                FROM platform.provider_operation
                                WHERE id=?""", operation)).isEqualTo(beforeStale);
                assertThat(count("provider_observation")).isGreaterThan(observations);
                assertThat(jdbc.queryForObject(
                                "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?",
                                Long.class,
                                paymentId))
                        .isEqualTo(held);
            }
            case KEY_EXPIRED -> {
                fake.clock().advance(Duration.ofHours(2));
                long requests = fake.counters(scope).requestCount();
                var claim = repository.claimRecovery(intents.get(c.key()).operationId(), "FAKE", true);
                assertThat(claim).isNotNull();
                new RefundExecutor(repository, refunds, fake, "FAKE").recover(claim);
                assertThat(fake.counters(scope).requestCount()).isEqualTo(requests);
                assertThat(jdbc.queryForObject(
                                "SELECT reason FROM platform.recovery_task WHERE operation_id=?",
                                String.class,
                                intents.get(c.key()).operationId()))
                        .isEqualTo("IDEMPOTENCY_WINDOW_EXPIRED");
            }
            case REFUND_FAILED -> refundFact(c.key(), "failed", c.amount(), false);
            case REFUND_STALE -> refundFact(c.key(), "pending", c.amount(), true);
            case SAME_KEY -> {
                var previous = intents.get(c.key());
                var result = reserve(c.key(), c.amount());
                assertThat(result.refundId()).isEqualTo(previous.refundId());
                assertThat(result.operationId()).isEqualTo(previous.operationId());
                assertThat(result.replay()).isTrue();
            }
            case CHANGED_KEY ->
                assertThatThrownBy(() -> reserve(c.key(), c.amount())).isInstanceOf(ApiException.class);
            case REFUND_SUCCEEDED -> refundFact(c.key(), "succeeded", c.amount(), false);
            case EARLY_REFUND -> {
                ingest(bt("refund", "refund", -c.amount(), 0, "re_b", "refund"));
                assertThat(replay.replay(scope, "refund")).isEqualTo("PENDING_EVIDENCE");
            }
            case POST_REFUND -> post(bt("refund", "refund", -c.amount(), 0, "re_b", "refund"));
            case DUPLICATE_POST -> assertThat(replay.replay(scope, c.key())).isEqualTo("POSTED");
            case DISPUTE_WARNING -> observeDispute("warning", "warning_needs_response");
            case DISPUTE_CLOSED -> observeDispute("warning", "warning_closed");
            case DISPUTE_LOST -> observeDispute("loss", "lost", "dispute-debit", "dispute-return");
            case EARLY_DISPUTE_RETURN -> {
                ingest(bt("dispute-return", "adjustment", 3000, 0, "du_loss", "dispute"));
                assertThat(replay.replay(scope, "dispute-return")).isEqualTo("PENDING_EVIDENCE");
            }
            case DISPUTE_DEBIT -> post(bt("dispute-debit", "adjustment", -3000, 1500, "du_loss", "dispute"));
            case DISPUTE_WON -> observeDispute("loss", "won", "dispute-debit", "dispute-return");
            case DISPUTE_RETURN -> post(bt("dispute-return", "adjustment", 3000, 0, "du_loss", "dispute"));
            case ILLEGAL_REFUND ->
                assertThatThrownBy(() -> reserve("illegal-" + c.id(), c.amount()))
                        .isInstanceOf(ApiException.class);
            case SHARE -> shares.reconcile(paymentId, "generated:" + c.id());
            case PAYOUT_ACCEPT -> payoutId = payout("payout");
            case EARLY_PAYOUT_RETURN -> {
                ingest(bt("payout-return", "payout_failure", 1200, 0, "payout", "payout"));
                assertThat(replay.replay(scope, "payout-return")).isEqualTo("PENDING_EVIDENCE");
            }
            case PAYOUT_OUT -> post(bt("payout-out", "payout", -5700, 0, "payout", "payout"));
            case PAYOUT_RETURN -> post(bt("payout-return", "payout_failure", 1200, 0, "payout", "payout"));
            case BANK_RECEIPT -> {
                bankLine = bankLine("bank", 4500, 11, "payout");
                matcher.match(scope, payoutId, bankLine, 4500);
            }
            case DUPLICATE_BANK -> matcher.match(scope, payoutId, bankLine, 4500);
            case WRONG_SCOPE -> wrongScopes();
            case MISSING_EVIDENCE, WRONG_CUTOFF, FULL_RECONCILIATION -> {
                String kind = switch (c.kind()) {
                    case MISSING_EVIDENCE -> "missing";
                    case WRONG_CUTOFF -> "wrong-cutoff";
                    default -> "full";
                };
                var model = new ReconciliationModel(scope.toString(), until);
                var independent = kind.equals("missing")
                        ? null
                        : new ReconciliationModel.Evidence(
                                scope.toString(),
                                kind.equals("wrong-cutoff") ? until.minusSeconds(1) : until,
                                oracle.ledger.balance("PSP_FUNDS"),
                                true);
                assertThat(reconcile(oracle.ledger.balance("PSP_FUNDS"), kind))
                        .isEqualTo(model.compare(oracle.ledger.balance("PSP_FUNDS"), independent));
            }
        }
    }

    void wrongScopes() {
        for (var c : List.of(
                new TenantContext(id(90), id(2), id(3), id(4), Set.of("USD"), false, context.grantedScopes()),
                new TenantContext(id(1), id(91), id(3), id(4), Set.of("USD"), false, context.grantedScopes()),
                new TenantContext(id(1), id(2), id(3), id(92), Set.of("USD"), false, context.grantedScopes()),
                new TenantContext(id(1), id(2), id(3), id(4), Set.of("EUR"), false, context.grantedScopes())))
            assertThatThrownBy(() -> repository.read(c, paymentId)).isInstanceOf(ApiException.class);
    }

    void verify(SequenceOracle oracle, SequenceCommand c) {
        String label = "seed=" + currentSeed + " command=" + c.id();
        for (var account : jdbc.queryForList("""
                SELECT a.account_purpose,coalesce(b.balance_minor,0) amount
                FROM platform.ledger_account a
                LEFT JOIN platform.balance_projection b ON a.id=b.ledger_account_id"""))
            assertThat(((Number) account.get("amount")).longValue())
                    .as(label + " account=" + account.get("account_purpose"))
                    .isEqualTo(
                            oracle.ledger.balance(account.get("account_purpose").toString()));
        assertThat(count("journal")).as(label + " I02 exact journal coverage").isEqualTo(oracle.ledger.journals());
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM (SELECT financial_event_id FROM platform.journal GROUP BY financial_event_id HAVING
                            count(*)<>1) x""", Long.class)).as("I02 unique").isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.financial_event e
                        LEFT JOIN platform.journal j ON j.financial_event_id=e.id
                        WHERE j.id IS NULL""", Long.class))
                .as("I02 no unjournaled events")
                .isZero();
        assertThat(new HashSet<>(jdbc.queryForList(
                        "SELECT source_ref FROM platform.financial_event WHERE source_domain='PROVIDER'",
                        String.class)))
                .as(label + " I02 provider source exact set")
                .isEqualTo(oracle.providerJournals());
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM (SELECT journal_id FROM platform.journal_entry GROUP BY journal_id HAVING sum(CASE WHEN
                            side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END)<>0) x""", Long.class)).as("I01 per journal").isZero();
        if (paymentId != null)
            assertThat(jdbc.queryForObject(
                            "SELECT captured_amount_minor FROM platform.payment WHERE id=?", Long.class, paymentId))
                    .as(label + " C")
                    .isEqualTo(oracle.payment.captured());
        var control = jdbc.queryForList("""
                SELECT captured_minor,refunded_minor,reserved_minor
                FROM platform.refund_control
                WHERE payment_id=?""", paymentId);
        if (!control.isEmpty()) {
            assertThat(((Number) control.getFirst().get("captured_minor")).longValue())
                    .isEqualTo(oracle.payment.captured());
            assertThat(((Number) control.getFirst().get("refunded_minor")).longValue())
                    .as("I03 R")
                    .isEqualTo(oracle.refunds.refunded());
            assertThat(((Number) control.getFirst().get("reserved_minor")).longValue())
                    .as("I03 H")
                    .isEqualTo(oracle.refunds.held());
        }
        assertThat(balance("PARTNER_SHARE_EXPENSE")).as(label + " I04 cap").isEqualTo(oracle.effective());
        assertThat(oracle.refunds.refunded() + oracle.refunds.held()).isLessThanOrEqualTo(oracle.payment.captured());
        assertThat(new BalanceRebuildService(jdbc, fake.clock())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .as("I07 independent rebuild")
                .isTrue();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.journal_entry e
                        JOIN platform.journal j ON j.id=e.journal_id
                        WHERE (e.organization_id,e.environment_id,e.currency) IS DISTINCT FROM (j.organization_id,
                            j.environment_id,j.currency)""", Long.class)).as("I07 scope").isZero();
        if (payoutId != null) {
            var p = payouts.lookup(scope, "payout");
            assertThat(((Number) p.get("transit_minor")).longValue())
                    .as(label + " payout transit")
                    .isEqualTo(oracle.payout.transit());
            assertThat(((Number) p.get("bank_confirmed_minor")).longValue()).isEqualTo(oracle.payout.bank());
            assertThat(((Number) p.get("returned_minor")).longValue()).isEqualTo(oracle.payout.returned());
        }
    }

    Map<String, Object> lightSnapshot() {
        return Map.of(
                "balances",
                jdbc.queryForList("""
                        SELECT a.account_purpose,coalesce(b.balance_minor,0) balance_minor
                        FROM platform.ledger_account a
                        LEFT JOIN platform.balance_projection b ON a.id=b.ledger_account_id
                        ORDER BY a.account_purpose"""),
                "journals",
                count("journal"),
                "providerRequests",
                fake.counters(scope).requestCount(),
                "providerEffects",
                fake.controls().effects(scope),
                "refundControl",
                jdbc.queryForList("""
                        SELECT captured_minor,refunded_minor,reserved_minor,control_state
                        FROM platform.refund_control"""),
                "outboxEvents",
                count("outbox_event"));
    }
}
