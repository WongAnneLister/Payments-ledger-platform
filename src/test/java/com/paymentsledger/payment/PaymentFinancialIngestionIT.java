package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.webhook.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real application payment/query/webhook paths feeding the same real PostgreSQL posting boundary. */
class PaymentFinancialIngestionIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    FinancialImportService importer;
    FinancialReplayService replay;

    @BeforeEach
    void ingestionSetup() {
        // Make fixture validity deterministic instead of comparing Docker's now() with the host's Fake clock.
        var starts = java.sql.Timestamp.from(fake.clock().instant().minus(Duration.ofDays(1)));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", starts);
        admin.update("UPDATE platform.share_policy_fixture SET valid_from=?", starts);
        admin.update("""
                INSERT INTO platform.ingestion_policy
                (organization_id,environment_id,provider_account_id,livemode,opening_mode,business_zone)
                VALUES (?,?,?,false,'ZERO','UTC')
                """, scope.organizationId(), scope.environmentId(), scope.providerAccountId());
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SALES_RETURNS", "SUSPENSE")) {
            boolean provider = !purpose.startsWith("SALES_");
            admin.update(
                    """
                    INSERT INTO platform.ledger_account
                    (organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain)
                    VALUES (?,?,'USD',?,?,?,?)
                    """,
                    scope.organizationId(),
                    scope.environmentId(),
                    purpose,
                    provider ? "PROVIDER" : "INTERNAL",
                    provider ? scope.providerAccountId() : null,
                    provider ? "PAYMENTS" : null);
        }
        importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock());
        replay = new FinancialReplayService(
                jdbc, tx, json, new LedgerPostingService(jdbc, json), RefundEvidencePort.unavailable());
    }

    BalanceTransactionFact bt(String btId, String chargeId) {
        Instant created = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new BalanceTransactionFact(
                btId,
                "charge",
                10000,
                300,
                9700,
                "usd",
                "PAYMENTS",
                chargeId,
                "charge",
                "pending",
                created,
                created.plus(Duration.ofDays(2)),
                List.of(new FeeDetail(300, "usd", "stripe_fee", "controlled fixture")),
                "a".repeat(64));
    }

    void expose(BalanceTransactionFact fact) {
        fake.controls().putBalanceTransaction(scope, fact, fake.clock().instant(), null);
        fake.controls().linkChargeBalanceTransaction(scope, fact.sourceObjectId(), fact.btId());
    }

    void importFact(BalanceTransactionFact fact) {
        // Pin the high water at a time that has already passed.
        fake.clock().advance(Duration.ofSeconds(2));
        UUID window = importer.createWindow(
                scope,
                fact.createdAt().minusSeconds(1),
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "BACKFILL");
        importer.runWindow(window, 10);
    }

    String financial(UUID paymentId) {
        return jdbc.queryForObject("SELECT financial_status FROM platform.payment WHERE id=?", String.class, paymentId);
    }

    void assertOnePosting() {
        assertThat(count("journal")).isEqualTo(1);
        assertThat(count("financial_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT p.balance_minor FROM platform.balance_projection p JOIN platform.ledger_account a
                ON a.id=p.ledger_account_id WHERE a.account_purpose='PSP_FUNDS'
                """, Long.class)).isEqualTo(9700);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type='payment.succeeded'", Long.class))
                .isEqualTo(1);
        // Independent Entry scan, without calling the production mapper or posting function.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.ledger_account a
                LEFT JOIN platform.balance_projection p ON p.ledger_account_id=a.id
                LEFT JOIN (SELECT ledger_account_id,
                    sum(CASE WHEN side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END) AS rebuilt
                    FROM platform.journal_entry GROUP BY ledger_account_id) e ON e.ledger_account_id=a.id
                WHERE coalesce(p.balance_minor,0)<>coalesce(e.rebuilt,0)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.balance_transaction b
                JOIN platform.journal j ON j.id=b.journal_id
                JOIN platform.financial_event e ON e.id=j.financial_event_id
                WHERE b.state='POSTED' AND e.event_key=('bt:'||b.provider_account_id||':'||b.livemode||':'||b.bt_id)
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void delayedBtPostsAndRepeatedAuthoritativeQueriesPreserveFinancialState() {
        var resource = service.create(context, "financial-query", command("financial-query"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        UUID paymentId = payment(resource);
        service.get(context, paymentId);
        assertThat(financial(paymentId)).isEqualTo("PENDING_EVIDENCE");
        assertThat(count("journal")).isZero();
        var fact = bt("txn_delayed", intent.latestChargeId());
        expose(fact);
        service.get(context, paymentId);
        importFact(fact);
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
        service.get(context, paymentId);
        service.get(context, paymentId);
        assertThat(financial(paymentId)).isEqualTo("POSTED");
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
        assertOnePosting();
    }

    @Test
    void btBeforeLocalCaptureWaitsAndConvergesAfterAuthoritativeQuery() {
        var resource = service.create(context, "financial-first", command("financial-first"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var fact = bt("txn_before_capture", intent.latestChargeId());
        expose(fact);
        importFact(fact);
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("PENDING_EVIDENCE");
        assertThat(count("journal")).isZero();
        service.get(context, payment(resource));
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
        assertThat(financial(payment(resource))).isEqualTo("POSTED");
        assertOnePosting();
    }

    @Test
    void paymentFinancialUpdateFailureRollsBackJournalAndProjectionTogether() {
        var resource = service.create(context, "financial-rollback", command("financial-rollback"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var fact = bt("txn_rollback", intent.latestChargeId());
        expose(fact);
        service.get(context, payment(resource));
        importFact(fact);
        admin.execute("""
                CREATE FUNCTION platform.pr10_test_fail_financial() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.financial_status='POSTED' THEN RAISE EXCEPTION 'injected financial status failure'; END IF;
                RETURN NEW; END $$
                """);
        admin.execute("""
                CREATE TRIGGER pr10_test_fail_financial BEFORE UPDATE ON platform.payment FOR EACH ROW EXECUTE FUNCTION
                    platform.pr10_test_fail_financial()""");
        try {
            assertThatThrownBy(() -> replay.replay(scope, fact.btId())).isInstanceOf(RuntimeException.class);
            assertThat(count("journal")).isZero();
            assertThat(count("financial_event")).isZero();
            assertThat(count("balance_projection")).isZero();
            assertThat(financial(payment(resource))).isEqualTo("PENDING_EVIDENCE");
        } finally {
            admin.execute("DROP TRIGGER pr10_test_fail_financial ON platform.payment");
            admin.execute("DROP FUNCTION platform.pr10_test_fail_financial()");
        }
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
        assertOnePosting();
    }

    @Test
    void concurrentQueryWebhookAndBtReplayKeepOneCaptureOneFinancialJournal() throws Exception {
        var resource = service.create(context, "financial-race", command("financial-race"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var fact = bt("txn_race", intent.latestChargeId());
        expose(fact);
        service.get(context, payment(resource));
        importFact(fact);
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_pr10_fixture'
                WHERE id=?""", scope.providerAccountId());
        var inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
        var processor = new WebhookProcessor(inbox, repository, json, fake.clock());
        var object = json.createObjectNode()
                .put("object", "charge")
                .put("id", fact.sourceObjectId())
                .put("payment_intent", intent.paymentIntentId())
                .put("status", "succeeded")
                .put("captured", true)
                .put("amount", 10000)
                .put("amount_captured", 10000)
                .put("currency", "usd")
                .put("balance_transaction", fact.btId());
        var event = json.createObjectNode()
                .put("id", "evt_pr10_race")
                .put("type", "charge.succeeded")
                .put("created", fake.clock().instant().getEpochSecond())
                .put("livemode", false)
                .put("account", "acct_pr10_fixture")
                .put("api_version", "2026-07-29.dahlia");
        event.putObject("data").set("object", object);
        // Trusted verified boundary fixture; HTTP signature correctness is tested in StripeWebhookHttpIT.
        inbox.receive(
                scope,
                "acct_pr10_fixture",
                event.toString().getBytes(StandardCharsets.UTF_8),
                new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        new VerifiedWebhook(
                                "evt_pr10_race",
                                "charge.succeeded",
                                fact.sourceObjectId(),
                                fake.clock().instant(),
                                false),
                        new ProviderEvidence(
                                null,
                                null,
                                fake.clock().instant(),
                                fake.clock().instant(),
                                200,
                                null,
                                null,
                                "2026-07-29.dahlia")));
        try (var threads = Executors.newFixedThreadPool(3)) {
            var barrier = new CyclicBarrier(3);
            var posting = threads.submit(() -> {
                barrier.await();
                return replay.replay(scope, fact.btId());
            });
            var querying = threads.submit(() -> {
                barrier.await();
                return service.get(context, payment(resource));
            });
            var webhook = threads.submit(() -> {
                barrier.await();
                return processor.processDue(10);
            });
            assertThat(posting.get(30, TimeUnit.SECONDS)).isEqualTo("POSTED");
            querying.get(30, TimeUnit.SECONDS);
            webhook.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(financial(payment(resource))).isEqualTo("POSTED");
        assertOnePosting();
    }

    @Test
    void configuredWorkerReplaysDurableBtAndDoesNotRepeatCompletedDailyEnumeration() {
        var resource = service.create(context, "financial-worker", command("financial-worker"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var fact = bt("txn_worker", intent.latestChargeId());
        expose(fact);
        service.get(context, payment(resource));
        importFact(fact);
        var worker = new FinancialIngestionWorker(jdbc, importer, replay, scope, fake.clock());
        worker.tick();
        assertThat(financial(payment(resource))).isEqualTo("POSTED");
        long windows = count("import_window");
        worker.tick();
        assertThat(count("import_window")).isEqualTo(windows);
        assertOnePosting();
    }

    @Test
    void recognizedChargeTypeCaseDoesNotLosePostedCoverage() {
        var resource = service.create(context, "financial-case", command("financial-case"))
                .resource();
        var intent = fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var base = bt("txn_case", intent.latestChargeId());
        var fact = new BalanceTransactionFact(
                base.btId(),
                "CHARGE",
                base.amountMinor(),
                base.feeMinor(),
                base.netMinor(),
                base.currency(),
                base.balanceDomain(),
                base.sourceObjectId(),
                base.sourceObjectType(),
                base.status(),
                base.createdAt(),
                base.availableOn(),
                base.feeDetails(),
                base.rawDigest());
        expose(fact);
        service.get(context, payment(resource));
        importFact(fact);
        assertThat(replay.replay(scope, fact.btId())).isEqualTo("POSTED");
        assertThat(financial(payment(resource))).isEqualTo("POSTED");
        service.get(context, payment(resource));
        assertThat(financial(payment(resource))).isEqualTo("POSTED");
        assertOnePosting();
    }

    @Test
    void rejectedHistoricalWindowDoesNotBlockNewBackfillOrDailyOverlap() {
        Instant boundary = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        UUID oldWindow = importer.createWindow(
                scope, boundary.minus(Duration.ofDays(10)), boundary.minus(Duration.ofDays(9)), "BACKFILL");
        admin.update("""
                UPDATE platform.import_cursor
                SET api_version='obsolete-contract'
                WHERE window_id=?""", oldWindow);
        UUID newerWindow = importer.createWindow(
                scope, boundary.minus(Duration.ofDays(2)), boundary.minus(Duration.ofDays(1)), "BACKFILL");
        var worker = new FinancialIngestionWorker(jdbc, importer, replay, scope, fake.clock());
        worker.tick();
        assertThat(jdbc.queryForObject("SELECT state FROM platform.import_window WHERE id=?", String.class, oldWindow))
                .isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM platform.import_window WHERE id=?", String.class, oldWindow))
                .isEqualTo("API_VERSION_DRIFT");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM platform.import_window WHERE id=?", String.class, newerWindow))
                .isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.import_window
                        WHERE purpose='OVERLAP' AND state='COMPLETE'""", Long.class)).isEqualTo(1);
    }
}
