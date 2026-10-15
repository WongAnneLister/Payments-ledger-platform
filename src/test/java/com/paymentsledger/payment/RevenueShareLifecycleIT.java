package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.refund.RefundFinancialEvidence;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RevenueShareLifecycleIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final TenantContext rc = new TenantContext(
            id(1), id(2), id(3), id(4), Set.of("USD"), false, Set.of("refunds:read", "refunds:write"));
    RevenueShareService shares;
    RefundRepository refunds;
    FinancialImportService importer;
    FinancialReplayService replay;
    UUID paymentId;
    String chargeId, chargeBt;

    @BeforeEach
    void setupShares() {
        var start = java.sql.Timestamp.from(fake.clock().instant().minusSeconds(86400));
        admin.update("UPDATE platform.price_fixture SET valid_from=?", start);
        admin.update("UPDATE platform.share_policy_fixture SET valid_from=?,share_basis_points=2000", start);
        var pay = service.create(context, "share-pay", command("share-pay")).resource();
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
        shares = new RevenueShareService(jdbc, tx, json, new LedgerPostingService(jdbc, json), true);
        repository = new PaymentRepository(jdbc, tx, json, fake.clock(), shares);
        refunds = new RefundRepository(jdbc, tx, json, fake.clock(), repository, shares);
        importer = new FinancialImportService(jdbc, tx, json, fake, fake.clock(), shares);
        replay = new FinancialReplayService(
                jdbc, tx, json, new LedgerPostingService(jdbc, json), new RefundFinancialEvidence(refunds), shares);
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
                        LEFT JOIN platform.balance_projection p ON p.ledger_account_id=a.id
                        LEFT JOIN (SELECT ledger_account_id,sum(CASE WHEN side='DEBIT' THEN amount_minor::numeric ELSE
                            -amount_minor::numeric END) n FROM platform.journal_entry GROUP BY ledger_account_id) e ON
                            e.ledger_account_id=a.id
                        WHERE coalesce(p.balance_minor,0)<>coalesce(e.n,0)""", Long.class)).isZero();
        assertThat(balance("PARTNER_SHARE_EXPENSE")).isEqualTo(effective());
        assertThat(balance("PARTNER_PAYABLE")).isEqualTo(-effective());
    }

    @Test
    void firstPostedCaptureThenReservationAndRepeatedFailureAreAtomic() {
        shares.reconcile(paymentId, "before-bt");
        assertThat(effective()).isZero();
        capture();
        assertThat(effective()).isEqualTo(2000);
        long psp = balance("PSP_FUNDS");
        var r = reserve("partial", 4000);
        assertThat(effective()).isEqualTo(1200);
        assertThat(balance("PSP_FUNDS")).isEqualTo(psp);
        refunds.accept(scope, fact("re_local", "failed", 4000), evidence(), r.refundId());
        assertThat(effective()).isEqualTo(2000);
        long journals = count("journal");
        refunds.accept(scope, fact("re_local", "failed", 4000), evidence(), r.refundId());
        shares.reconcile(paymentId, "repeat");
        assertThat(effective()).isEqualTo(2000);
        assertThat(count("journal")).isEqualTo(journals);
        coverage();
    }

    @Test
    void successMovesHoldToRefundWithoutSecondReversalAndPolicySnapshotStaysFrozen() {
        capture();
        admin.update("""
                UPDATE platform.share_policy_fixture
                SET share_basis_points=9000,policy_version=2""");
        var r = reserve("success", 4000);
        assertThat(effective()).isEqualTo(1200);
        long journals = count("journal");
        refunds.accept(scope, fact("re_success", "succeeded", 4000), evidence(), r.refundId());
        shares.reconcile(paymentId, "catch-up");
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(effective()).isEqualTo(1200);
        coverage();
    }

    @Test
    void unknownKeepsReducedShareAndDefinitiveRejectRestoresOnce() {
        capture();
        var r = reserve("unknown", 4000);
        RefundPort timeout = new RefundPort() {
            public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand c) {
                return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
            }

            public ProviderCallResult<RefundFact> queryRefund(ProviderScope s, String id) {
                throw new AssertionError();
            }
        };
        new RefundExecutor(repository, refunds, timeout, "FAKE").execute(r.operationId());
        assertThat(effective()).isEqualTo(1200);
        refunds.accept(scope, fact("re_cancel", "canceled", 4000), evidence(), r.refundId());
        assertThat(effective()).isEqualTo(2000);
        var rejected = reserve("reject", 4000);
        RefundPort reject = new RefundPort() {
            public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand c) {
                return new ProviderCallResult<>(ProviderCallClassification.DEFINITIVE_REJECT, null, evidence());
            }

            public ProviderCallResult<RefundFact> queryRefund(ProviderScope s, String id) {
                throw new AssertionError();
            }
        };
        new RefundExecutor(repository, refunds, reject, "FAKE").execute(rejected.operationId());
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void externalConflictWithdrawsEverythingAndAuditedUnfreezeRestores() {
        capture();
        UUID external = refunds.accept(scope, fact("re_external", "failed", 300), evidence(), null);
        assertThat(effective()).isZero();
        long version = jdbc.queryForObject(
                "SELECT version FROM platform.refund_control WHERE payment_id=?", Long.class, paymentId);
        UUID observation = jdbc.queryForObject(
                "SELECT id FROM platform.refund_source_observation WHERE refund_id=? LIMIT 1", UUID.class, external);
        refunds.resolveExternalConflict(scope, paymentId, version, observation, "trusted-resolution");
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void postedCaptureConflictWithdrawsShareWithoutMutatingOriginalFunds() {
        capture();
        long psp = balance("PSP_FUNDS");
        var old = fake.getBalanceTransaction(scope, chargeBt).payload();
        var changed = new BalanceTransactionFact(
                old.btId(),
                old.type(),
                old.amountMinor(),
                old.feeMinor() + 1,
                old.netMinor() - 1,
                old.currency(),
                old.balanceDomain(),
                old.sourceObjectId(),
                old.sourceObjectType(),
                old.status(),
                old.createdAt(),
                old.availableOn(),
                old.feeDetails(),
                "c".repeat(64));
        BalanceTransactionPort port = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                return fake.listBalanceTransactions(q);
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
                return new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        changed,
                        fake.getBalanceTransaction(s, id).evidence());
            }
        };
        new FinancialImportService(jdbc, tx, json, port, fake.clock(), shares).refreshPending(scope, chargeBt);
        assertThat(effective()).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(psp);
        coverage();
    }

    @Test
    void refundAndCatchUpCompeteThroughPostgresParentLock() throws Exception {
        capture();
        var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                reserve("race", 4000);
                return null;
            });
            var b = workers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                shares.reconcile(paymentId, "concurrent-catch-up");
                return null;
            });
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertThat(effective()).isEqualTo(1200);
        coverage();
    }

    @Test
    void allocationFailureRollsBackRefundHoldOperationAndJournal() {
        capture();
        long journals = count("journal"), operations = count("provider_operation");
        admin.execute("""
                CREATE FUNCTION platform.pr12_fail_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION
                    'injected allocation failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER pr12_inject BEFORE INSERT ON platform.share_adjustment FOR EACH ROW EXECUTE FUNCTION
                    platform.pr12_fail_adjustment()""");
        try {
            assertThatThrownBy(() -> reserve("rollback", 4000))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("injected allocation failure");
        } finally {
            admin.execute("DROP TRIGGER pr12_inject ON platform.share_adjustment");
            admin.execute("DROP FUNCTION platform.pr12_fail_adjustment()");
        }
        assertThat(effective()).isEqualTo(2000);
        assertThat(count("refund")).isZero();
        assertThat(count("provider_operation")).isEqualTo(operations);
        assertThat(count("journal")).isEqualTo(journals);
        coverage();
    }

    @Test
    void captureJournalAndFirstAllocationRollbackTogether() {
        admin.execute("""
                CREATE FUNCTION platform.pr12_fail_initial() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION
                    'injected first accrual failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER pr12_initial BEFORE INSERT ON platform.share_adjustment FOR EACH ROW EXECUTE FUNCTION
                    platform.pr12_fail_initial()""");
        try {
            assertThatThrownBy(this::capture)
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("injected first accrual failure");
        } finally {
            admin.execute("DROP TRIGGER pr12_initial ON platform.share_adjustment");
            admin.execute("DROP FUNCTION platform.pr12_fail_initial()");
        }
        assertThat(count("journal")).isZero();
        assertThat(balance("PSP_FUNDS")).isZero();
        assertThat(effective()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM platform.balance_transaction WHERE bt_id=?", String.class, chargeBt))
                .isEqualTo("PENDING_EVIDENCE");
        assertThat(replay.replay(scope, chargeBt)).isEqualTo("POSTED");
        assertThat(effective()).isEqualTo(2000);
        coverage();
    }

    @Test
    void conflictingPaymentObservationWithdrawsAccrualInTheSameTransaction() {
        capture();
        long psp = balance("PSP_FUNDS");
        UUID operation = repository.createOperation(paymentId);
        repository.mergeQuery(
                paymentId,
                operation,
                List.of(),
                new PaymentRepository.Facts(
                        null, null, List.of(), false, true, fake.clock().instant()));
        assertThat(effective()).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(psp);
        coverage();
    }

    @Test
    void quarantinedNewBtForKnownChargeWithdrawsShareBeforeCommit() {
        capture();
        long psp = balance("PSP_FUNDS");
        var at = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var bad = new BalanceTransactionFact(
                "txn_bad_charge",
                "charge",
                -10000,
                0,
                -10000,
                "usd",
                "PAYMENTS",
                chargeId,
                "charge",
                "pending",
                at,
                at.plusSeconds(86400),
                List.of(),
                "b".repeat(64));
        fake.controls().putBalanceTransaction(scope, bad, fake.clock().instant(), null);
        fake.clock().advance(Duration.ofSeconds(2));
        UUID window = importer.createWindow(
                scope,
                at.minusSeconds(1),
                fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "BACKFILL");
        importer.runWindow(window, 10);
        assertThat(replay.replay(scope, bad.btId())).isEqualTo("QUARANTINED");
        assertThat(effective()).isZero();
        assertThat(balance("PSP_FUNDS")).isEqualTo(psp);
        coverage();
    }

    @Test
    void concurrentRefundAssociationCannotLeaveShareAboveConflictCeiling() throws Exception {
        capture();
        var reserved = reserve("association-race", 4000);
        var at = fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var original = new BalanceTransactionFact(
                "txn_racing_refund",
                "refund",
                -4000,
                0,
                -4000,
                "usd",
                "PAYMENTS",
                "re_racing",
                "refund",
                "pending",
                at,
                at.plusSeconds(86400),
                List.of(),
                "d".repeat(64));
        fake.controls().putBalanceTransaction(scope, original, fake.clock().instant(), null);
        fake.clock().advance(Duration.ofSeconds(2));
        importer.runWindow(
                importer.createWindow(
                        scope,
                        at.minusSeconds(1),
                        fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                        "BACKFILL"),
                10);
        var changed = new BalanceTransactionFact(
                original.btId(),
                original.type(),
                -4000,
                1,
                -4001,
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

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope scope, String bt) {
                return new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        changed,
                        fake.getBalanceTransaction(scope, bt).evidence());
            }
        };
        var raced = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = Executors.newSingleThreadExecutor();
        var racingJdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                var found = super.queryForList(sql, type, args);
                // Match on normalized text: production SQL is laid out over several lines.
                if (sql.replaceAll("\\s+", " ").startsWith("SELECT payment_id FROM platform.refund")
                        && Arrays.asList(args).contains("re_racing")
                        && raced.compareAndSet(false, true)) {
                    assertThat(found).isEmpty();
                    try {
                        worker.submit(() -> refunds.accept(
                                        scope, fact("re_racing", "pending", 4000), evidence(), reserved.refundId()))
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return found;
            }
        };
        try {
            new FinancialImportService(racingJdbc, tx, json, port, fake.clock(), shares)
                    .refreshPending(scope, original.btId());
        } finally {
            worker.shutdownNow();
        }
        assertThat(raced.get()).isTrue();
        assertThat(count("bt_evidence_conflict")).isEqualTo(1);
        assertThat(effective()).isZero();
        coverage();
    }

    @Test
    void sourceAssociationLocksBtInTheSameOrderAsBatchImport() throws Exception {
        capture();
        // Deliberately reverse internal UUID order and provider BT ID order.
        for (int n = 0; n < 2; n++)
            admin.update(
                    """
   INSERT INTO platform.balance_transaction(id,organization_id,environment_id,provider_account_id,livemode,
    bt_id,amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,business_type,
    balance_domain,provider_created_at,available_on,provider_status,raw_digest,api_version,fetched_at,
    immutable_fact,fact,first_observation_id)
   SELECT ?,organization_id,environment_id,provider_account_id,livemode,?,amount_minor,fee_minor,net_minor,
    currency,'re_lock_order','refund',business_type,balance_domain,provider_created_at,available_on,
    provider_status,raw_digest,api_version,fetched_at,immutable_fact,fact,first_observation_id
   FROM platform.balance_transaction WHERE bt_id=?
   """,
                    UUID.fromString(
                            n == 0 ? "ffffffff-ffff-ffff-ffff-ffffffffffff" : "00000000-0000-0000-0000-000000000001"),
                    n == 0 ? "txn_A_first" : "txn_z_second",
                    chargeBt);
        var worker = Executors.newSingleThreadExecutor();
        var backend = new java.util.concurrent.atomic.AtomicInteger();
        try (var blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("""
                        SELECT id
                        FROM platform.balance_transaction
                        WHERE bt_id='txn_A_first'
                        FOR UPDATE""");
            }
            Future<?> pending = worker.submit(() -> new org.springframework.transaction.support.TransactionTemplate(tx)
                    .executeWithoutResult(status -> {
                        backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        shares.lock(paymentId);
                        FinancialSourceLocks.lock(jdbc, paymentId, List.of("re_lock_order"));
                    }));
            try {
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> backend.get() != 0
                                && Boolean.TRUE.equals(admin.queryForObject(
                                        "SELECT cardinality(pg_blocking_pids(?))>0", Boolean.class, backend.get())));
                // While waiting on the first BT, the association writer must not own the second.
                assertThatCode(() -> jdbc.queryForList("""
                                        SELECT id
                                        FROM platform.balance_transaction
                                        WHERE bt_id='txn_z_second'
                                        FOR UPDATE NOWAIT""")).doesNotThrowAnyException();
            } finally {
                blocker.rollback();
                pending.get(10, TimeUnit.SECONDS);
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
