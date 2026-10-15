package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.bank.*;
import com.paymentsledger.dispute.*;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.*;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.pr18.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.reconciliation.*;
import com.paymentsledger.refund.RefundFinancialEvidence;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Real repositories/services; provider and bank data are explicitly simulated independent inputs. */
abstract class Pr18Fixture extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final TenantContext refundContext = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:read", "refunds:write"));
    RevenueShareService shares;
    DisputeService disputes;
    RefundRepository refunds;
    PayoutService payouts;
    BankStatementImporter banks;
    BankMatcher matcher;
    FinancialImportService importer;
    FinancialReplayService replay;
    LedgerPostingService posting;
    ReconciliationService reconciliation;
    final Queue<ProviderCallResult<BalanceTransactionPage>> pages = new ConcurrentLinkedQueue<>();
    final Map<String, BalanceTransactionFact> providerFacts = new LinkedHashMap<>();
    final Map<String, RefundRepository.Intent> intents = new HashMap<>();
    final List<Map<String, Object>> reconciliationOutcomes = new ArrayList<>();
    Instant from, until;
    UUID paymentId, payoutId, bankLine;
    String chargeId, checkout;
    Path caseRoot;
    long currentSeed;
    int resetNumber;

    void reset(long seed) {
        if (caseRoot == null) caseRoot = directory;
        if (pool != null) pool.close();
        directory = caseRoot.resolve("case-" + seed + "-" + (resetNumber++));
        super.setup();
        fake = new com.paymentsledger.provider.fake.FakeStripeProvider(
                directory.resolve("provider"), seed, Instant.parse("2026-10-07T12:00:00Z"), Duration.ofDays(1));
        currentSeed = seed;
        intents.clear();
        reconciliationOutcomes.clear();
        pages.clear();
        providerFacts.clear();
        paymentId = null;
        payoutId = null;
        bankLine = null;
        chargeId = null;
        checkout = null;
        from = fake.clock()
                .instant()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .minus(Duration.ofDays(3));
        until = from.plus(Duration.ofDays(2));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", java.sql.Timestamp.from(from));
        admin.update(
                "UPDATE platform.share_policy_fixture SET valid_from=?,share_basis_points=2000",
                java.sql.Timestamp.from(from));
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode,business_zone)
                VALUES (?,?,?,false,'ZERO','UTC')""", id(1), id(2), id(4));
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SALES_RETURNS", "SUSPENSE")) {
            boolean external = !purpose.startsWith("SALES_");
            admin.update(
                    """
                    INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,
                        source_domain,provider_account_id,balance_domain)
                    VALUES (?,?,'USD',?,?,?,?)""",
                    id(1),
                    id(2),
                    purpose,
                    external ? "PROVIDER" : "INTERNAL",
                    external ? id(4) : null,
                    external ? "PAYMENTS" : null);
        }
        wire();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("payments_app");
    }

    void wire() {
        posting = new LedgerPostingService(jdbc, json);
        shares = new RevenueShareService(jdbc, tx, json, posting, true, p -> disputes == null || !disputes.blocks(p));
        disputes = new DisputeService(jdbc, tx, json, posting, shares);
        repository = new PaymentRepository(jdbc, tx, json, fake.clock(), shares);
        repository.recoveryPolicy(new RecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofDays(1), 0, Duration.ofDays(7), Duration.ZERO));
        executor = new PaymentExecutor(repository, fake, fake, fake, "FAKE");
        service = new PaymentService(repository, executor);
        refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares, disputes);
        payouts = new PayoutService(jdbc, tx, json, posting);
        banks = new BankStatementImporter(jdbc, tx, json);
        matcher = new BankMatcher(jdbc, tx, json, posting, payouts);
        BalanceTransactionPort reads = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive())
                        .isFalse();
                return pages.remove();
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String bt) {
                return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, providerFacts.get(bt), evidence());
            }
        };
        importer = new FinancialImportService(jdbc, tx, json, reads, fake.clock(), shares);
        replay = new FinancialReplayService(
                jdbc, tx, json, posting, new RefundFinancialEvidence(refunds), shares, disputes, payouts);
        reconciliation = new ReconciliationService(
                jdbc, tx, json, importer, new BalanceRebuildService(jdbc, fake.clock()), matcher, fake.clock());
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence(
                "pr18-fixture",
                null,
                fake.clock().instant(),
                fake.clock().instant(),
                200,
                null,
                "a".repeat(64),
                "fixture-v1");
    }

    void createPayment() {
        var result =
                service.create(context, "pr18-payment", command("pr18-payment")).resource();
        paymentId = payment(result);
        checkout = session(result);
    }

    void capturePayment() {
        chargeId = fake.controls()
                .attemptPayment(scope, checkout, FakeControls.AttemptOutcome.SUCCEED)
                .latestChargeId();
        service.get(context, paymentId);
        fake.controls().linkChargeBalanceTransaction(scope, chargeId, "capture");
        service.get(context, paymentId);
    }

    RefundRequests.Create refundRequest(String key, long amount) {
        return new RefundRequests.Create(paymentId, amount, "USD", "REQUESTED_BY_CUSTOMER", null, key);
    }

    RefundRepository.Intent reserve(String key, long amount) {
        var intent = refunds.prepare(refundContext, "pr18-api-" + key, refundRequest(key, amount), "FAKE");
        intents.putIfAbsent(key, intent);
        return intent;
    }

    RefundFact refundFact(String key, String status, long amount) {
        return new RefundFact(
                "re_" + key,
                chargeId,
                status,
                amount,
                "usd",
                key.equals("b") ? "refund" : null,
                null,
                null,
                fake.clock().instant());
    }

    void refundFact(String key, String status, long amount, boolean stale) {
        refunds.accept(
                scope,
                refundFact(key, status, amount),
                stale
                        ? new ProviderEvidence("stale", null, from, from, 200, null, "b".repeat(64), "fixture-v1")
                        : evidence(),
                intents.get(key).refundId());
    }

    DisputeFact disputeFact(String key, String status, String... links) {
        return new DisputeFact("du_" + key, chargeId, status, "fraudulent", 3000, "usd", until, List.of(links));
    }

    void observeDispute(String key, String status, String... links) {
        fake.clock().advance(Duration.ofSeconds(1));
        disputes.accept(scope, disputeFact(key, status, links), evidence());
    }

    BalanceTransactionFact bt(String key, String type, long amount, long fee, String source, String sourceType) {
        return new BalanceTransactionFact(
                key,
                type,
                amount,
                fee,
                amount - fee,
                "USD",
                "PAYMENTS",
                source,
                sourceType,
                "available",
                from.plusSeconds(1),
                from.plusSeconds(100),
                List.of(),
                "a".repeat(64));
    }

    void ingest(BalanceTransactionFact fact) {
        providerFacts.put(fact.btId(), fact);
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(fact), false, null),
                evidence()));
        assertThat(importer.importPage(importer.createWindow(scope, from, until, "BACKFILL")))
                .isEqualTo("COMPLETE");
    }

    void post(BalanceTransactionFact fact) {
        ingest(fact);
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
    }

    PayoutFact payoutFact(String key, String out, String returned) {
        return new PayoutFact(key, "pending", 5700, "USD", out, returned, false, from.plusSeconds(100), null);
    }

    UUID payout(String name) {
        return payouts.accept(scope, payoutFact(name, name + "-out", name + "-return"), evidence());
    }

    UUID bankLine(String name, long amount, long fee, String... refs) {
        return bankLine(name, amount, fee, true, refs);
    }

    UUID bankLine(String name, long amount, long fee, boolean complete, String... refs) {
        var statement = json.createObjectNode()
                .put("schemaVersion", 1)
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("fixtureId", name)
                .put("bankAccountRef", "sim-bank-1")
                .put("currency", "USD")
                .put("coverageFrom", from.toString())
                .put("coverageUntil", until.toString())
                .put("complete", complete);
        var line = statement
                .putArray("lines")
                .addObject()
                .put("lineId", name)
                .put("kind", "CREDIT")
                .put("bookedAt", from.plusSeconds(200).toString())
                .put("principalMinor", amount)
                .put("feeMinor", fee);
        var references = line.putArray("payoutReferences");
        for (var ref : refs) references.add(ref);
        UUID statementId = banks.importStatement(scope, statement);
        return jdbc.queryForObject(
                "SELECT id FROM platform.bank_statement_line WHERE statement_id=?", UUID.class, statementId);
    }

    String reconcile(long closing, String evidenceKind) {
        UUID run = reconciliation.createBackfill(scope, "USD", from, until, ZoneOffset.UTC);
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.copyOf(providerFacts.values()), false, null),
                evidence()));
        reconciliation.advance(run, 1);
        IndependentBalanceEvidence independent = evidenceKind.equals("missing")
                ? null
                : new IndependentBalanceEvidence(
                        scope,
                        "USD",
                        "PAYMENTS",
                        from,
                        evidenceKind.equals("wrong-cutoff") ? until.minusSeconds(1) : until,
                        0,
                        closing,
                        "independent-pr18-generated-provider",
                        "case-" + currentSeed,
                        "c".repeat(64),
                        "fixture-v1",
                        IndependentBalanceEvidence.Kind.INDEPENDENT_FIXTURE,
                        false);
        reconciliation.finish(run, independent);
        String result = jdbc.queryForObject("""
                SELECT outcome
                FROM platform.reconciliation_check
                WHERE run_id=? AND check_number=5""", String.class, run);
        reconciliationOutcomes.add(Map.of(
                "runId",
                run.toString(),
                "kind",
                evidenceKind,
                "closingMinor",
                closing,
                "I05",
                result,
                "I06",
                jdbc.queryForObject("""
                        SELECT outcome
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=6""", String.class, run),
                "cutoff",
                until.toString()));
        if (evidenceKind.equals("full"))
            assertThat(jdbc.queryForObject("""
                            SELECT outcome
                            FROM platform.reconciliation_check
                            WHERE run_id=? AND check_number=6""", String.class, run))
                    .as("I06 payout and bank independent evidence")
                    .isEqualTo("PASS");
        return result;
    }

    long balance(String purpose) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(b.balance_minor),0)
                FROM platform.balance_projection b
                JOIN platform.ledger_account a ON a.id=b.ledger_account_id
                WHERE a.account_purpose=?""", Long.class, purpose);
    }

    Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of(
                "payment",
                "payment_attempt",
                "refund_control",
                "refund",
                "provider_operation",
                "recovery_task",
                "provider_observation",
                "refund_source_observation",
                "dispute",
                "dispute_financial_fact",
                "share_allocation",
                "share_adjustment",
                "balance_transaction",
                "financial_event",
                "journal",
                "journal_entry",
                "balance_projection",
                "payout",
                "payout_fact",
                "bank_statement",
                "bank_statement_line",
                "payout_bank_match",
                "reconciliation_check",
                "outbox_event",
                "consumer_inbox",
                "delivery_task")) {
            try {
                result.put(
                        table,
                        json.readTree(jdbc.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]'::jsonb)::text FROM platform." + table
                                        + " t",
                                String.class)));
            } catch (Exception ex) {
                throw new IllegalStateException("Required evidence table unavailable: " + table, ex);
            }
        }
        result.put("providerCalls", fake.controls().calls(scope));
        result.put("providerEffects", fake.controls().effects(scope));
        result.put("providerFacts", List.copyOf(providerFacts.values()));
        result.put("reconciliationOutcomes", List.copyOf(reconciliationOutcomes));
        result.put("runtimeRole", jdbc.queryForObject("SELECT current_user", String.class));
        return result;
    }
}
