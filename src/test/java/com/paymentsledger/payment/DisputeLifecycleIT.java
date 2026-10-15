package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.dispute.*;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.refund.RefundFinancialEvidence;
import com.paymentsledger.revenueshare.RevenueShareService;
import com.paymentsledger.webhook.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class DisputeLifecycleIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final TenantContext rc = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:read", "refunds:write"));
    RevenueShareService shares;
    RefundRepository refunds;
    FinancialImportService importer;
    FinancialReplayService replay;
    DisputeService disputes;
    UUID paymentId;
    String chargeId, chargeBt;

    @BeforeEach
    void setupShares() {
        var start = java.sql.Timestamp.from(fake.clock().instant().minusSeconds(86400));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", start);
        admin.update("UPDATE platform.share_policy_fixture SET valid_from=?,share_basis_points=2000", start);
        var intent = repository.prepare(context, "share-pay", command("share-pay"), "FAKE");
        executor.execute(intent.operationId());
        var pay = repository.read(context, intent.paymentId());
        paymentId = payment(pay);
        chargeId = fake.controls()
                .attemptPayment(scope, session(pay), FakeControls.AttemptOutcome.SUCCEED)
                .latestChargeId();
        service.get(context, paymentId);
        chargeBt = "txn_share_capture";
        fake.controls().linkChargeBalanceTransaction(scope, chargeId, chargeBt);
        service.get(context, paymentId);
        var at = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        fake.controls()
                .putBalanceTransaction(
                        scope,
                        new BalanceTransactionFact(
                                chargeBt,
                                "charge",
                                10000,
                                300,
                                9700,
                                "usd",
                                "PAYMENTS",
                                chargeId,
                                "charge",
                                "pending",
                                at,
                                at.plusSeconds(86400),
                                List.of(),
                                "a".repeat(64)),
                        fake.clock().instant(),
                        null);
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode,business_zone)
                VALUES (?,?,?,false,'ZERO','UTC')""", id(1), id(2), id(4));
        for (String p : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SALES_RETURNS", "SUSPENSE")) {
            boolean external = !p.startsWith("SALES_");
            admin.update(
                    """
                    INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,
                        source_domain,provider_account_id,balance_domain)
                    VALUES (?,?,'USD',?,?,?,?)""",
                    id(1),
                    id(2),
                    p,
                    external ? "PROVIDER" : "INTERNAL",
                    external ? id(4) : null,
                    external ? "PAYMENTS" : null);
        }
        shares = new RevenueShareService(
                jdbc,
                tx,
                json,
                new LedgerPostingService(jdbc, json),
                true,
                id -> disputes == null || !disputes.blocks(id));
        disputes = new DisputeService(jdbc, tx, json, new LedgerPostingService(jdbc, json), shares);
        repository = new PaymentRepository(jdbc, tx, json, fake.clock(), shares);
        refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares, disputes);
        importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock(), shares);
        replay = new FinancialReplayService(
                jdbc,
                tx,
                json,
                new LedgerPostingService(jdbc, json),
                new RefundFinancialEvidence(refunds),
                shares,
                disputes);
    }

    void capture() {
        fake.clock().advance(Duration.ofSeconds(2));
        UUID window = importer.createWindow(
                scope,
                fake.clock().instant().minusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "BACKFILL");
        importer.runWindow(window, 10);
        assertThat(replay.replay(scope, chargeBt)).isEqualTo("POSTED");
    }

    RefundRepository.Intent reserve(String key, long amount) {
        return refunds.prepare(
                rc,
                "share-refund-" + key,
                new RefundRequests.Create(paymentId, amount, "USD", null, null, key),
                "FAKE");
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence(
                null, null, fake.clock().instant(), fake.clock().instant(), 200, null, "a".repeat(64), "fixture");
    }

    RefundFact fact(String key, String status, long amount) {
        return new RefundFact(
                key,
                chargeId,
                status,
                amount,
                "usd",
                null,
                null,
                null,
                fake.clock().instant());
    }

    long effective() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(effective_minor),0)
                FROM platform.share_allocation
                WHERE payment_id=?""", Long.class, paymentId);
    }

    long balance(String purpose) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(b.balance_minor),0)
                FROM platform.balance_projection b
                JOIN platform.ledger_account a ON a.id=b.ledger_account_id
                WHERE a.account_purpose=?""", Long.class, purpose);
    }

    void coverage() {
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.ledger_account a
                        LEFT
                        JOIN platform.balance_projection p ON p.ledger_account_id=a.id
                        LEFT
                        JOIN (SELECT ledger_account_id,sum(CASE WHEN side='DEBIT' THEN amount_minor::numeric ELSE
                            -amount_minor::numeric END) n FROM platform.journal_entry GROUP BY ledger_account_id) e ON
                            e.ledger_account_id=a.id
                        WHERE coalesce(p.balance_minor,0)<>coalesce(e.n,0)""", Long.class)).isZero();
        assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(effective());
        assertThat(balance("PARTNER_PAYABLE")).isEqualTo(-effective());
    }

    WebhookInboxRepository inbox;
    WebhookProcessor processor;

    void webhook(String eventId, String disputeId, String status, List<String> bts) throws Exception {
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_test'
                WHERE id=?""", id(4));
        if (inbox == null) {
            inbox = new WebhookInboxRepository(jdbc, tx, json, fake.clock());
            processor = new WebhookProcessor(
                    inbox,
                    repository,
                    json,
                    fake.clock(),
                    new RefundWebhookHandler(inbox, repository, refunds),
                    new DisputeWebhookHandler(inbox, repository, disputes));
        }
        var root = json.createObjectNode()
                .put("id", eventId)
                .put("type", "charge.dispute.updated")
                .put("created", fake.clock().instant().getEpochSecond())
                .put("livemode", false)
                .put("account", "acct_test")
                .put("api_version", "2026-07-29.dahlia");
        var object = root.putObject("data")
                .putObject("object")
                .put("id", disputeId)
                .put("object", "dispute")
                .put("charge", chargeId)
                .put("status", status)
                .put("amount", 3000)
                .put("currency", "usd")
                .put("reason", "fraudulent");
        object.putObject("evidence_details")
                .put("due_by", fake.clock().instant().plusSeconds(86400).getEpochSecond());
        var links = object.putArray("balance_transactions");
        bts.forEach(links::add);
        var verified = new VerifiedWebhook(
                eventId,
                "charge.dispute.updated",
                disputeId,
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                false);
        inbox.receive(
                scope,
                "acct_test",
                root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        verified,
                        new ProviderEvidence(
                                null,
                                null,
                                fake.clock().instant(),
                                fake.clock().instant(),
                                200,
                                null,
                                null,
                                "2026-07-29.dahlia")));
    }

    void process() {
        for (var claim : inbox.claimDue(100)) processor.process(claim);
    }

    @Test
    void warningWebhookWithdrawsShareWithoutMovingProviderFunds() throws Exception {
        capture();
        assertThat(effective()).isEqualTo(2000);
        long psp = balance("PSP_FUNDS");
        webhook("evt_dispute_open", "du_lifecycle", "warning_needs_response", List.of());
        process();
        assertThat(effective()).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(psp);
        admin.update("""
                UPDATE platform.provider_account
                SET provider='FAKE',provider_account_ref='fixture'
                WHERE id=?""", id(4));
        assertThatThrownBy(() -> reserve("blocked-warning", 100))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFUND_BLOCKED));
        assertThat(disputes.lookup(scope, "du_lifecycle").get("evidence_due_by"))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("DONE");
        coverage();
    }

    DisputeFact dispute(String id, String status, List<String> links) {
        return new DisputeFact(
                id,
                chargeId,
                status,
                "fraudulent",
                3000,
                "usd",
                fake.clock().instant().plusSeconds(86400),
                links);
    }

    void observe(String id, String status, String... links) {
        fake.clock().advance(Duration.ofSeconds(1));
        disputes.accept(scope, dispute(id, status, List.of(links)), evidence());
    }

    BalanceTransactionFact movement(String id, String source, long amount, long fee) {
        var at = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new BalanceTransactionFact(
                id,
                "adjustment",
                amount,
                fee,
                amount - fee,
                "usd",
                "PAYMENTS",
                source,
                "dispute",
                "pending",
                at,
                at.plusSeconds(86400),
                List.of(),
                "d".repeat(64));
    }

    void ingest(BalanceTransactionFact fact) {
        fake.controls().putBalanceTransaction(scope, fact, fake.clock().instant(), null);
        fake.clock().advance(Duration.ofSeconds(2));
        UUID window = importer.createWindow(
                scope,
                fact.createdAt().minusSeconds(1),
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "BACKFILL");
        importer.runWindow(window, 10);
    }

    void post(String bt, String source, long amount, long fee) {
        ingest(movement(bt, source, amount, fee));
        assertThat(replay.replay(scope, bt)).isEqualTo("POSTED");
    }

    long money(String dispute, String column) {
        return ((Number) disputes.lookup(scope, dispute).get(column)).longValue();
    }

    @Test
    void wonNeedsActualReturnAndDoesNotInventFeeRefund() {
        capture();
        observe("du_won", "needs_response", "txn_debit");
        assertThat(effective()).isZero();
        post("txn_debit", "du_won", -3000, 1500);
        assertThat(balance("PSP_FUNDS")).isEqualTo(5200);
        assertThat(balance("DISPUTE_RECEIVABLE")).isEqualTo(3000);
        assertThat(balance("DISPUTE_FEE_EXPENSE")).isEqualTo(1500);
        observe("du_won", "won", "txn_debit", "txn_return");
        assertThat(effective()).isZero();
        assertThat(disputes.blocks(paymentId)).isTrue();
        post("txn_return", "du_won", 3000, 0);
        assertThat(balance("PSP_FUNDS")).isEqualTo(8200);
        assertThat(balance("DISPUTE_RECEIVABLE")).isZero();
        assertThat(balance("DISPUTE_FEE_EXPENSE")).isEqualTo(1500);
        assertThat(effective()).isEqualTo(2000);
        long journals = count("journal");
        assertThat(replay.replay(scope, "txn_return")).isEqualTo("POSTED");
        observe("du_won", "won", "txn_debit", "txn_return");
        assertThat(count("journal")).isEqualTo(journals);
        coverage();
    }

    @Test
    void lossReclassifiesOnceAndLateWinReturnsAgainstThatLoss() {
        capture();
        observe("du_lost", "lost", "txn_loss_debit");
        post("txn_loss_debit", "du_lost", -3000, 1500);
        assertThat(balance("PSP_FUNDS")).isEqualTo(5200);
        assertThat(balance("DISPUTE_RECEIVABLE")).isZero();
        assertThat(balance("DISPUTE_LOSS")).isEqualTo(3000);
        assertThat(effective()).isZero();
        long journals = count("journal");
        observe("du_lost", "lost", "txn_loss_debit");
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(balance("PSP_FUNDS")).isEqualTo(5200);
        observe("du_lost", "won", "txn_loss_debit", "txn_late_win");
        assertThat(effective()).isZero();
        post("txn_late_win", "du_lost", 3000, 0);
        assertThat(balance("PSP_FUNDS")).isEqualTo(8200);
        assertThat(balance("DISPUTE_LOSS")).isZero();
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void btBeforeDisputeUsesQueryRecoveryAndReturnBeforeDebitWaits() {
        capture();
        var back = movement("txn_early_return", "du_early", 3000, 0);
        ingest(back);
        assertThat(replay.replay(scope, back.btId())).isEqualTo("PENDING_EVIDENCE");
        assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
        fake.controls()
                .putDispute(
                        scope,
                        dispute("du_early", "won", List.of("txn_early_debit", "txn_early_return")),
                        fake.clock().instant(),
                        fake.clock().instant());
        var source = new DisputeSourceRecovery(
                new DisputePort() {
                    public ProviderCallResult<Page<DisputeFact>> listDisputes(PageQuery q) {
                        throw new AssertionError();
                    }

                    public ProviderCallResult<DisputeFact> getDispute(ProviderScope s, String id) {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                        .isActualTransactionActive())
                                .isFalse();
                        return fake.getDispute(s, id);
                    }
                },
                disputes);
        new FinancialIngestionWorker(
                        jdbc, importer, replay, scope, fake.clock(), RefundSourceResolver.unavailable(), source)
                .tick();
        assertThat(effective()).isZero();
        assertThat(replay.replay(scope, back.btId())).isEqualTo("PENDING_EVIDENCE");
        post("txn_early_debit", "du_early", -3000, 1500);
        assertThat(effective()).isZero();
        assertThat(replay.replay(scope, back.btId())).isEqualTo("POSTED");
        assertThat(balance("PSP_FUNDS")).isEqualTo(8200);
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void multipleWarningsRestoreOnlyAfterTheLastBlockerClears() {
        capture();
        observe("du_first", "warning_needs_response");
        observe("du_second", "warning_under_review");
        assertThat(count("dispute")).isEqualTo(2);
        assertThat(effective()).isZero();
        observe("du_first", "warning_closed");
        assertThat(effective()).isZero();
        observe("du_second", "warning_closed");
        assertThat(effective()).isEqualTo(2000);
        long journals = count("journal");
        observe("du_second", "warning_closed");
        assertThat(count("journal")).isEqualTo(journals);
        coverage();
    }

    @Test
    void excessReturnStaysInSuspenseAndCannotRestoreShare() {
        capture();
        observe("du_excess", "lost", "txn_excess_debit");
        post("txn_excess_debit", "du_excess", -3000, 1500);
        observe("du_excess", "won", "txn_excess_debit", "txn_excess_return");
        post("txn_excess_return", "du_excess", 3500, 0);
        assertThat(balance("DISPUTE_LOSS")).isZero();
        assertThat(balance("SUSPENSE")).isEqualTo(-500);
        assertThat(balance("PSP_FUNDS")).isEqualTo(8700);
        assertThat(effective()).isZero();
        assertThat(count("dispute_case")).isPositive();
        coverage();
    }

    @Test
    void feeOnlyReturnUsesItsActualSignedFee() {
        capture();
        observe("du_fees", "won", "txn_fee_debit", "txn_fee_principal", "txn_fee_refund");
        post("txn_fee_debit", "du_fees", -3000, 1500);
        post("txn_fee_principal", "du_fees", 3000, 0);
        assertThat(effective()).isZero();
        post("txn_fee_refund", "du_fees", 0, -500);
        assertThat(balance("PSP_FUNDS")).isEqualTo(8700);
        assertThat(balance("DISPUTE_FEE_EXPENSE")).isEqualTo(1000);
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void externalRefundAndDisputeFactsAreBothRetainedWithoutAddingDisputeToR() {
        capture();
        observe("du_overlap", "needs_response");
        refunds.accept(
                scope,
                new RefundFact(
                        "re_overlap",
                        chargeId,
                        "succeeded",
                        1000,
                        "usd",
                        null,
                        null,
                        null,
                        fake.clock().instant()),
                evidence(),
                null);
        assertThat(count("refund")).isEqualTo(1);
        assertThat(count("dispute")).isEqualTo(1);
        assertThat(count("dispute_case")).isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT control_state FROM platform.refund_control WHERE payment_id=?",
                        String.class,
                        paymentId))
                .isEqualTo("EXTERNAL_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT refunded_minor FROM platform.refund_control WHERE payment_id=?", Long.class, paymentId))
                .isEqualTo(1000);
        assertThat(effective()).isZero();
        coverage();
    }

    @Test
    void concurrentRefundAndDisputeSerializeOnThePayment() throws Exception {
        capture();
        var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var refund = workers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    reserve("dispute-race", 4000);
                    return true;
                } catch (ApiException blocked) {
                    assertThat(blocked.errorCode()).isIn(ErrorCode.REFUND_BLOCKED, ErrorCode.EVIDENCE_CONFLICT);
                    return false;
                }
            });
            var opened = workers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                disputes.accept(scope, dispute("du_race", "needs_response", List.of()), evidence());
                return true;
            });
            boolean reserved = refund.get(20, TimeUnit.SECONDS);
            assertThat(opened.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?",
                            Long.class,
                            paymentId))
                    .isEqualTo(reserved ? 4000 : 0);
            if (reserved)
                assertThat(jdbc.queryForObject(
                                "SELECT control_state FROM platform.refund_control WHERE payment_id=?",
                                String.class,
                                paymentId))
                        .isEqualTo("EXTERNAL_CONFLICT");
        } finally {
            workers.shutdownNow();
        }
        assertThat(effective()).isZero();
        assertThat(disputes.blocks(paymentId)).isTrue();
        coverage();
    }

    @Test
    void shareJournalFailureRollsBackDisputeAndEvidence() {
        capture();
        long journals = count("journal");
        admin.execute("""
                CREATE FUNCTION platform.pr13_fail_share() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE
                    EXCEPTION 'injected dispute share failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER pr13_fail_share BEFORE INSERT ON platform.share_adjustment FOR EACH ROW EXECUTE
                    FUNCTION platform.pr13_fail_share()""");
        try {
            assertThatThrownBy(() -> observe("du_rollback", "needs_response"))
                    .hasStackTraceContaining("injected dispute share failure");
        } finally {
            admin.execute("DROP TRIGGER pr13_fail_share ON platform.share_adjustment");
            admin.execute("DROP FUNCTION platform.pr13_fail_share()");
        }
        assertThat(count("dispute")).isZero();
        assertThat(count("dispute_observation")).isZero();
        assertThat(effective()).isEqualTo(2000);
        assertThat(count("journal")).isEqualTo(journals);
        coverage();
    }

    @Test
    void staleWebhookLeaseCannotApplyDisputeAndAckFailureRollsBack() throws Exception {
        capture();
        webhook("evt_dispute_atomic", "du_ack", "needs_response", List.of());
        var old = inbox.claimDue(1).getFirst();
        fake.clock().advance(Duration.ofSeconds(31));
        var current = inbox.claimDue(1).getFirst();
        processor.process(old);
        assertThat(count("dispute")).isZero();
        admin.execute("""
                CREATE FUNCTION platform.pr13_fail_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF
                    NEW.status='DONE' THEN RAISE EXCEPTION 'injected dispute ack failure'; END IF; RETURN NEW; END
                    $$""");
        admin.execute("""
                CREATE TRIGGER pr13_fail_ack BEFORE
                UPDATE ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION platform.pr13_fail_ack()""");
        try {
            assertThatThrownBy(() -> processor.process(current))
                    .hasStackTraceContaining("injected dispute ack failure");
        } finally {
            admin.execute("DROP TRIGGER pr13_fail_ack ON platform.webhook_inbox");
            admin.execute("DROP FUNCTION platform.pr13_fail_ack()");
        }
        assertThat(count("dispute")).isZero();
        assertThat(effective()).isEqualTo(2000);
        processor.process(current);
        assertThat(effective()).isZero();
        coverage();
    }

    @Test
    void returnedMoneyBeforeWonWaitsForKnownDisputeWorkerQuery() {
        capture();
        observe("du_worker", "needs_response", "txn_worker_debit", "txn_worker_return");
        post("txn_worker_debit", "du_worker", -3000, 1500);
        post("txn_worker_return", "du_worker", 3000, 0);
        assertThat(effective()).isZero();
        fake.clock().advance(Duration.ofSeconds(2));
        fake.controls()
                .putDispute(
                        scope,
                        dispute("du_worker", "won", List.of("txn_worker_debit", "txn_worker_return")),
                        fake.clock().instant(),
                        fake.clock().instant());
        new DisputeRecoveryWorker(
                        jdbc,
                        new DisputeSourceRecovery(fake, disputes),
                        id(1).toString(),
                        id(2).toString(),
                        id(4).toString())
                .tick();
        assertThat(effective()).isEqualTo(2000);
        assertThat(disputes.lookup(scope, "du_worker").get("last_query_attempt_at"))
                .isNotNull();
        coverage();
    }

    @Test
    void failingKnownSourceDoesNotStarveAnotherDispute() {
        capture();
        observe("du_unavailable", "warning_needs_response");
        observe("du_recoverable", "warning_needs_response");
        DisputeSourceResolver resolver = (s, id) -> {
            if (id.equals("du_unavailable")) throw new IllegalStateException("fixture failure");
            observe(id, "warning_closed");
        };
        new DisputeRecoveryWorker(jdbc, resolver, id(1).toString(), id(2).toString(), id(4).toString()).tick();
        assertThat(disputes.lookup(scope, "du_recoverable")).containsEntry("blocking", false);
        assertThat(effective()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.dispute WHERE last_query_attempt_at IS NOT NULL", Long.class))
                .isEqualTo(2);
    }

    @Test
    void lateConflictingDisputeBtWithdrawsRestoredShareInTheImportTransaction() {
        capture();
        observe("du_conflict", "won", "txn_conflict_debit", "txn_conflict_return");
        post("txn_conflict_debit", "du_conflict", -3000, 1500);
        post("txn_conflict_return", "du_conflict", 3000, 0);
        assertThat(effective()).isEqualTo(2000);
        var original = fake.getBalanceTransaction(scope, "txn_conflict_return").payload();
        var changed = new BalanceTransactionFact(
                original.btId(),
                original.type(),
                original.amountMinor(),
                1,
                original.netMinor() - 1,
                original.currency(),
                original.balanceDomain(),
                original.sourceObjectId(),
                original.sourceObjectType(),
                original.status(),
                original.createdAt(),
                original.availableOn(),
                List.of(),
                "e".repeat(64));
        BalanceTransactionPort port = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                throw new AssertionError();
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
                return new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        changed,
                        fake.getBalanceTransaction(s, id).evidence());
            }
        };
        new FinancialImportService(jdbc, tx, json, port, fake.clock(), shares).refreshPending(scope, original.btId());
        assertThat(effective()).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(8200);
        assertThat(disputes.blocks(paymentId)).isTrue();
        coverage();
    }

    @Test
    void disputeFinancialHistoryFailureRollsBackBtJournalAndCounters() {
        capture();
        observe("du_money_rollback", "needs_response", "txn_money_rollback");
        ingest(movement("txn_money_rollback", "du_money_rollback", -3000, 1500));
        long journals = count("journal");
        admin.execute("""
                CREATE FUNCTION platform.pr13_fail_money() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE
                    EXCEPTION 'injected dispute money failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER pr13_fail_money BEFORE INSERT ON platform.dispute_financial_fact FOR EACH ROW EXECUTE
                    FUNCTION platform.pr13_fail_money()""");
        try {
            assertThatThrownBy(() -> replay.replay(scope, "txn_money_rollback"))
                    .hasStackTraceContaining("injected dispute money failure");
        } finally {
            admin.execute("DROP TRIGGER pr13_fail_money ON platform.dispute_financial_fact");
            admin.execute("DROP FUNCTION platform.pr13_fail_money()");
        }
        assertThat(balance("PSP_FUNDS")).isEqualTo(9700);
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(money("du_money_rollback", "debited_principal_minor")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM platform.balance_transaction WHERE bt_id='txn_money_rollback'",
                        String.class))
                .isEqualTo("PENDING_EVIDENCE");
        assertThat(replay.replay(scope, "txn_money_rollback")).isEqualTo("POSTED");
        coverage();
    }

    @Test
    void lateReturnAllocatesReceivableThenExistingLossThenSuspense() {
        capture();
        observe("du_split", "lost", "txn_split_loss");
        post("txn_split_loss", "du_split", -2000, 1500);
        assertThat(balance("DISPUTE_LOSS")).isEqualTo(2000);
        observe("du_split", "won", "txn_split_loss", "txn_split_ar", "txn_split_return");
        post("txn_split_ar", "du_split", -1000, 0);
        assertThat(balance("DISPUTE_RECEIVABLE")).isEqualTo(1000);
        assertThat(balance("DISPUTE_LOSS")).isEqualTo(2000);
        post("txn_split_return", "du_split", 3500, 0);
        var allocation = jdbc.queryForMap("""
                SELECT f.receivable_delta,f.loss_delta,f.suspense_minor
                FROM platform.dispute_financial_fact f
                JOIN platform.balance_transaction b ON b.id=f.balance_transaction_id
                WHERE b.bt_id='txn_split_return'""");
        assertThat(allocation)
                .containsEntry("receivable_delta", -1000L)
                .containsEntry("loss_delta", -2000L)
                .containsEntry("suspense_minor", 500L);
        assertThat(balance("PSP_FUNDS")).isEqualTo(8700);
        assertThat(balance("DISPUTE_RECEIVABLE")).isZero();
        assertThat(balance("DISPUTE_LOSS")).isZero();
        assertThat(balance("SUSPENSE")).isEqualTo(-500);
        assertThat(effective()).isZero();
        coverage();
    }

    @Test
    void disputeQueriesRespectApplicationAndCurrencyScope() {
        capture();
        observe("du_scoped", "needs_response", "txn_scoped_dispute");
        post("txn_scoped_dispute", "du_scoped", -3000, 1500);
        assertThat(disputes.disputes(rc, paymentId)).hasSize(1);
        assertThat(disputes.financialFacts(rc, paymentId)).hasSize(1);
        assertThat(disputes.observations(rc, paymentId)).hasSize(1);
        var other = new TenantContext(id(1), id(2), id(90), id(4), Set.of("USD"), false, Set.of());
        assertThat(disputes.disputes(other, paymentId)).isEmpty();
        assertThat(disputes.financialFacts(other, paymentId)).isEmpty();
        var wrongCurrency = new TenantContext(id(1), id(2), id(3), id(4), Set.of("EUR"), false, Set.of());
        assertThatThrownBy(() -> disputes.disputes(wrongCurrency, paymentId)).isInstanceOf(ApiException.class);
    }

    @Test
    void capturePrelocksShareAndProviderAccountsInOneGlobalOrder() throws Exception {
        int ordinal = 0;
        for (String purpose : List.of("PARTNER_SHARE_EXPENSE", "PARTNER_PAYABLE"))
            admin.update("""
                    INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                        source_domain)
                    VALUES (?, ?,?,'USD',?,'INTERNAL')""", new UUID(0, 200 + ordinal++), id(1), id(2), purpose);
        shares.reconcile(paymentId, "precreate");
        fake.clock().advance(Duration.ofSeconds(2));
        importer.runWindow(
                importer.createWindow(
                        scope,
                        fake.clock().instant().minusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                        fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                        "BACKFILL"),
                10);
        var worker = Executors.newSingleThreadExecutor();
        var backend = new java.util.concurrent.atomic.AtomicInteger();
        try (var blocker = admin.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("""
                        SELECT id
                        FROM platform.ledger_account
                        WHERE account_purpose='PARTNER_SHARE_EXPENSE'
                        FOR
                        UPDATE""");
            }
            var pending = worker.submit(() -> new org.springframework.transaction.support.TransactionTemplate(tx)
                    .execute(status -> {
                        backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        return replay.replay(scope, chargeBt);
                    }));
            try {
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> backend.get() != 0
                                && Boolean.TRUE.equals(admin.queryForObject(
                                        "SELECT cardinality(pg_blocking_pids(?))>0", Boolean.class, backend.get())));
                assertThatCode(() -> admin.queryForList("""
                                        SELECT id
                                        FROM platform.ledger_account
                                        WHERE account_purpose='PSP_FUNDS'
                                        FOR
                                        UPDATE NOWAIT""")).doesNotThrowAnyException();
            } finally {
                blocker.rollback();
                assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo("POSTED");
            }
        } finally {
            worker.shutdownNow();
        }
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void postedDisputeIdentityIsImmutableAndLaterDebitReblocksSharing() {
        capture();
        observe("du_reopen_money", "won", "txn_reopen_debit", "txn_reopen_return");
        post("txn_reopen_debit", "du_reopen_money", -1000, 0);
        post("txn_reopen_return", "du_reopen_money", 1000, 0);
        assertThat(effective()).isEqualTo(2000);
        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE platform.balance_transaction
                        SET dispute_id=NULL
                        WHERE bt_id='txn_reopen_debit'""")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        post("txn_new_debit", "du_reopen_money", -2000, 0);
        assertThat(effective()).isZero();
        assertThat(money("du_reopen_money", "outstanding_receivable_minor")).isEqualTo(2000);
        coverage();
    }

    @Test
    void newerContradictoryTerminalObservationBlocksPreviouslyRestoredShare() {
        capture();
        observe("du_status_conflict", "won", "txn_status_debit", "txn_status_return");
        post("txn_status_debit", "du_status_conflict", -3000, 0);
        post("txn_status_return", "du_status_conflict", 3000, 0);
        assertThat(effective()).isEqualTo(2000);
        observe("du_status_conflict", "future_unrecognized_status", "txn_status_debit", "txn_status_return");
        assertThat(effective()).isZero();
        assertThat(disputes.blocks(paymentId)).isTrue();
        assertThat(count("dispute_case")).isPositive();
        coverage();
    }
}
