package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.notification.*;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.lang.reflect.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReconciliationRecoveryIT extends PaymentCoreFixture {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Instant until =
            LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    final Instant from = until.minusSeconds(86400), at = from.plusSeconds(3600);
    final Clock clock = Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC);
    Reads reads;
    FinancialImportService importer;
    FinancialReplayService replay;
    Object recovery, engine;
    UUID run;

    @BeforeEach
    void setupReconciliation() throws Exception {
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode)
                VALUES (?,?,?,false,'ZERO')""", id(1), id(2), id(4));
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SUSPENSE")) {
            boolean external = !purpose.equals("SALES_REVENUE");
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
        reads = new Reads();
        importer = new FinancialImportService(jdbc, tx, json, reads, clock);
        var ledger = new LedgerPostingService(jdbc, json);
        replay = new FinancialReplayService(jdbc, tx, json, ledger, RefundEvidencePort.unavailable());
        var payouts = new PayoutService(jdbc, tx, json, ledger);
        var bank = new BankMatcher(jdbc, tx, json, ledger, payouts);
        Class<?> core = require("com.paymentsledger.reconciliation.ReconciliationService");
        engine = core.getConstructor(
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        org.springframework.transaction.PlatformTransactionManager.class,
                        ObjectMapper.class,
                        FinancialImportService.class,
                        BalanceRebuildService.class,
                        BankMatcher.class,
                        Clock.class)
                .newInstance(jdbc, tx, json, importer, new BalanceRebuildService(jdbc, clock), bank, clock);
        run = (UUID)
                invoke(engine, "createDay", scope, "USD", LocalDate.ofInstant(from, ZoneOffset.UTC), ZoneOffset.UTC);
        invoke(engine, "advance", run, 10);
        Class<?> type = require("com.paymentsledger.payment.ReconciliationSourceRecovery");
        recovery = type.getConstructor(
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        org.springframework.transaction.PlatformTransactionManager.class,
                        ObjectMapper.class,
                        ChargePort.class,
                        PaymentIntentPort.class,
                        PaymentRepository.class,
                        FinancialReplayService.class,
                        Clock.class)
                .newInstance(jdbc, tx, json, reads, reads, repository, replay, clock);
    }

    static Class<?> require(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing L3 recovery implementation: " + name, e);
        }
    }

    static Object invoke(Object target, String method, Object... args) throws Exception {
        Method m = Arrays.stream(target.getClass().getMethods())
                .filter(x -> x.getName().equals(method) && x.getParameterCount() == args.length)
                .findFirst()
                .orElseThrow();
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error a) throw a;
            throw e;
        }
    }

    UUID order(String key) {
        UUID order = UUID.randomUUID();
        admin.update("""
  INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot)
  VALUES (?,?,?,?,?,'USD',?,?,'demo',1,1,10000,10000,'{}','{}')
  """, order, id(1), id(2), id(3), id(4), key, "a".repeat(64));
        return order;
    }

    void binding(UUID order) {
        admin.update("""
  INSERT INTO platform.order_provider_binding(organization_id,environment_id,application_id,provider_account_id,livemode,currency,order_id,payment_intent_id,amount_minor,proof_kind,proof_ref,proof_digest)
  VALUES (?,?,?,?,false,'USD',?,'pi_recovered',10000,'SERVER_ORDER_FIXTURE','independent-server-order-v1',?)
  """, id(1), id(2), id(3), id(4), order, "c".repeat(64));
    }

    void drain() throws Exception {
        invoke(recovery, "drain", scope, run, 25);
    }

    long psp() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(p.balance_minor),0)
                FROM platform.balance_projection p
                JOIN platform.ledger_account a ON a.id=p.ledger_account_id
                WHERE a.account_purpose='PSP_FUNDS'""", Long.class);
    }

    NotificationEvent event() {
        var e = jdbc.queryForMap("SELECT * FROM platform.outbox_event WHERE event_type='payment.succeeded'");
        return new NotificationEvent(
                (UUID) e.get("id"),
                id(1),
                id(2),
                id(3),
                id(4),
                (UUID) e.get("payment_id"),
                (UUID) e.get("order_id"),
                "USD",
                "payment.succeeded",
                ((Number) e.get("resource_version")).longValue(),
                e.get("business_key").toString(),
                e.get("origin").toString(),
                ((Timestamp) e.get("occurred_at")).toInstant(),
                e.get("payload").toString(),
                e.get("payload_digest").toString());
    }

    @Test
    void trustedExistingOrderRestoresMissingPaymentAndPostsOnceWithReconciliationOrigin() throws Exception {
        UUID order = order("valid");
        binding(order);
        drain();
        assertThat(count("payment")).isEqualTo(1);
        assertThat(psp()).isEqualTo(9700);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT business_order_id FROM platform.payment", UUID.class))
                .isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT origin FROM platform.payment", String.class))
                .isEqualTo("RECONCILIATION");
        assertThat(count("reconciliation_payment_provenance")).isEqualTo(1);
        assertThat(event().origin()).isEqualTo("RECONCILIATION");
        assertThat(new FulfillmentEligibility.Database(jdbc).evaluate(event()).eligible())
                .isTrue();
        drain();
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(count("reconciliation_payment_provenance")).isEqualTo(1);
    }

    @Test
    void noOrderPreservesUnallocatedEvidenceWithoutInventingBusinessOrFulfillment() throws Exception {
        drain();
        assertThat(count("business_order")).isZero();
        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("journal")).isZero();
        assertThat(count("reconciliation_source_observation")).isPositive();
        assertThat(jdbc.queryForObject("""
                        SELECT reason
                        FROM platform.recovery_task
                        WHERE task_type='RECONCILIATION_SOURCE'""", String.class)).isEqualTo("NO_TRUSTED_ORDER");
    }

    @Test
    void metadataOnlyEvenMatchingUniqueAmountIsInsufficient() throws Exception {
        UUID order = order("metadata");
        reads.metadata = Map.of("business_order_id", order.toString(), "organization_id", id(1).toString());
        drain();
        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(psp()).isZero();
    }

    @Test
    void conflictingChargeAmountRefusesLinkAndRetainsException() throws Exception {
        binding(order("mismatch"));
        reads.captureAmount = 9999;
        drain();
        assertThat(count("payment")).isZero();
        assertThat(count("journal")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT reason
                        FROM platform.recovery_task
                        WHERE task_type='RECONCILIATION_SOURCE'""", String.class)).isEqualTo("SOURCE_EVIDENCE_CONFLICT");
    }

    @Test
    void expiredOrDisabledOrderEligibilityNeverTriggersHistoricalFulfillment() throws Exception {
        UUID order = order("disabled");
        binding(order);
        admin.update("""
                UPDATE platform.order_fulfillment_eligibility
                SET eligible=false
                WHERE order_id=?""", order);
        drain();
        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void runtimeCannotFabricateTrustedOrderBinding() throws Exception {
        UUID order = order("forged");
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO platform.order_provider_binding(organization_id,environment_id,application_id,
                            provider_account_id,livemode,currency,order_id,payment_intent_id,amount_minor,proof_kind,
                            proof_ref,proof_digest)
                        VALUES (?,?,?,?,false,'USD',?,'pi_forged',10000,'SERVER_ORDER_FIXTURE','forged',?)""", id(1), id(2), id(3), id(4), order, "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void staleLeaseCannotLinkButExpiryAllowsAnotherOwner() throws Exception {
        binding(order("lease"));
        invoke(recovery, "enqueue", scope, run, "txn_recovered");
        var claims = (List<?>) invoke(recovery, "claim", scope, run, 1);
        assertThat(claims).hasSize(1);
        Object first = claims.getFirst();
        admin.update("""
                UPDATE platform.recovery_task
                SET lease_until=?
                WHERE task_type='RECONCILIATION_SOURCE'""", Timestamp.from(clock.instant().minusSeconds(1)));
        var newer = (List<?>) invoke(recovery, "claim", scope, run, 1);
        assertThat(newer).hasSize(1);
        assertThat(invoke(recovery, "recover", first)).isEqualTo("STALE_CLAIM");
        assertThat(count("payment")).isZero();
        assertThat(invoke(recovery, "recover", newer.getFirst())).isEqualTo("LINKED");
        assertThat(psp()).isEqualTo(9700);
    }

    @Test
    void injectedProvenanceFailureRollsBackPaymentAndNotification() throws Exception {
        binding(order("rollback"));
        admin.execute("""
                CREATE FUNCTION platform.pr15_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE
                    EXCEPTION 'injected'; END $$""");
        admin.execute("""
                CREATE TRIGGER pr15_test_fail BEFORE INSERT ON platform.reconciliation_payment_provenance FOR EACH
                    ROW EXECUTE FUNCTION platform.pr15_test_fail()""");
        try {
            assertThatThrownBy(() -> drain()).isInstanceOf(Exception.class);
            assertThat(count("payment")).isZero();
            assertThat(count("outbox_event")).isZero();
            assertThat(count("journal")).isZero();
        } finally {
            admin.execute("DROP TRIGGER pr15_test_fail ON platform.reconciliation_payment_provenance");
            admin.execute("DROP FUNCTION platform.pr15_test_fail()");
        }
    }

    @Test
    void serverNativeCheckoutObservationCreatesDurableBinding() throws Exception {
        var pay = service.create(context, "native-binding", command("native-binding"))
                .resource();
        UUID payment = payment(pay);
        fake.controls()
                .attemptPayment(
                        scope, session(pay), com.paymentsledger.provider.fake.FakeControls.AttemptOutcome.SUCCEED);
        service.get(context, payment);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.order_provider_binding
                        WHERE payment_intent_id=(SELECT payment_intent_id FROM platform.payment WHERE id=?)""", Long.class, payment)).isEqualTo(1);
    }

    @Test
    void unavailableReadWaitsThenLeaseRetryUsesTheSameTask() throws Exception {
        binding(order("unavailable"));
        reads.unavailable = true;
        drain();
        assertThat(count("payment")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT status
                        FROM platform.recovery_task
                        WHERE task_type='RECONCILIATION_SOURCE'""", String.class)).isEqualTo("WAITING");
        admin.update("""
                UPDATE platform.recovery_task
                SET available_at=?
                WHERE task_type='RECONCILIATION_SOURCE'""", Timestamp.from(clock.instant().minusSeconds(1)));
        reads.unavailable = false;
        drain();
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("recovery_task")).isEqualTo(1);
        assertThat(count("reconciliation_source_observation")).isEqualTo(2);
    }

    @Test
    void sourceVersionMismatchCannotMixQueryProofWithPinnedBtPage() throws Exception {
        binding(order("version"));
        reads.readVersion = "different-provider-api";
        drain();
        assertThat(count("payment")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT reason
                        FROM platform.recovery_task
                        WHERE task_type='RECONCILIATION_SOURCE'""", String.class)).isEqualTo("SOURCE_EVIDENCE_CONFLICT");
    }

    @Test
    void disabledCurrencyPreventsAssociationEvenWithValidServerBinding() throws Exception {
        binding(order("currency-disabled"));
        admin.update("UPDATE platform.org_currency_enabled SET status='DISABLED' WHERE currency='USD'");
        assertThatThrownBy(() -> drain()).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment")).isZero();
    }

    @Test
    void scopedRunCannotBeEnqueuedByAnotherProviderAccount() throws Exception {
        assertThatThrownBy(() -> invoke(
                        recovery, "enqueue", new ProviderScope(id(1), id(2), id(999), false), run, "txn_recovered"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("recovery_task")).isZero();
    }

    @Test
    void workerBuildsSevenBusinessWindowsAndKeepsMissingReportIncomplete() throws Exception {
        Class<?> type = require("com.paymentsledger.reconciliation.ReconciliationWorker");
        Object worker = type.getConstructor(
                        require("com.paymentsledger.reconciliation.ReconciliationService"),
                        require("com.paymentsledger.payment.ReconciliationSourceRecovery"),
                        FinancialReplayService.class,
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        ReportPort.class,
                        Clock.class,
                        ProviderScope.class,
                        String.class,
                        ZoneId.class)
                .newInstance(engine, recovery, replay, jdbc, null, clock, scope, "USD", ZoneOffset.UTC);
        invoke(worker, "nightly");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.reconciliation_run", Long.class))
                .isEqualTo(8);
        invoke(worker, "drain");
        invoke(worker, "drain");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_check
                        WHERE check_number=5 AND outcome='INCOMPLETE'""", Long.class)).isEqualTo(8);
        assertThat(count("business_order")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void sourceLeaseCanContinueAfterFrozenIncompleteReportWithoutRewritingItsExceptions() throws Exception {
        binding(order("frozen-run"));
        reads.unavailable = true;
        drain();
        invoke(engine, "finish", run, null);
        long exceptions = count("reconciliation_exception");
        admin.update("""
                UPDATE platform.recovery_task
                SET available_at=?
                WHERE task_type='RECONCILIATION_SOURCE'""", Timestamp.from(clock.instant().minusSeconds(1)));
        assertThatCode(() -> drain()).doesNotThrowAnyException();
        assertThat(count("reconciliation_exception")).isEqualTo(exceptions);
        assertThat(count("reconciliation_source_observation")).isEqualTo(2);
        reads.unavailable = false;
        admin.update("""
                UPDATE platform.recovery_task
                SET available_at=?
                WHERE task_type='RECONCILIATION_SOURCE'""", Timestamp.from(clock.instant().minusSeconds(1)));
        drain();
        assertThat(count("payment")).isEqualTo(1);
        assertThat(psp()).isEqualTo(9700);
        assertThat(count("reconciliation_check")).isEqualTo(7);
    }

    @Test
    void recoveredPaymentCanBeReadWithoutInventingNativeCreateOperation() throws Exception {
        binding(order("read-recovered"));
        drain();
        UUID payment = jdbc.queryForObject("SELECT id FROM platform.payment", UUID.class);
        assertThatCode(() -> service.get(context, payment)).doesNotThrowAnyException();
        assertThat(count("provider_operation")).isZero();
        assertThat(psp()).isEqualTo(9700);
    }

    @Test
    void forgedRecoveryRunCannotCrossProviderScopeOnPaymentInsert() throws Exception {
        UUID order = order("wrong-run");
        binding(order);
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','other-provider',false)""", id(77), id(1), id(2));
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode)
                VALUES (?,?,?,false,'ZERO')""", id(1), id(2), id(77));
        UUID wrongRun = (UUID) invoke(
                engine,
                "createDay",
                new ProviderScope(id(1), id(2), id(77), false),
                "USD",
                LocalDate.ofInstant(from, ZoneOffset.UTC),
                ZoneOffset.UTC);
        UUID bt = jdbc.queryForObject(
                "SELECT id FROM platform.balance_transaction WHERE bt_id='txn_recovered'", UUID.class);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
                            currency,business_order_id,amount_minor,expires_at,origin,reconciliation_run_id,
                            reconciliation_source_bt_id,reconciliation_evidence_digest)
                        VALUES (?,?,?,?,?,'USD',?,10000,now(),'RECONCILIATION',?,?,?)""", UUID.randomUUID(), id(1), id(2), id(3), id(4), order, wrongRun, bt, "b".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void independentPeriodReportIsReadOutsideTransactionAndComparedAtExactCutoffs() throws Exception {
        binding(order("period-report"));
        ReportPort report = new ReportPort() {
            public ProviderCallResult<ReportFact> requestReport(ReportRequest q) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                return new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        new ReportFact(
                                "report_fixture",
                                "available",
                                q.createdGte(),
                                q.createdLt(),
                                List.of(
                                        new BalanceSnapshot("USD", "PAYMENTS", 0, 0, from),
                                        new BalanceSnapshot("USD", "PAYMENTS", 9700, 0, until)),
                                "d".repeat(64)),
                        reads.evidence());
            }

            public ProviderCallResult<ReportFact> fetchReport(ProviderScope s, String id) {
                throw new AssertionError("Already complete period fixture");
            }
        };
        Class<?> type = require("com.paymentsledger.reconciliation.ReconciliationWorker");
        Object worker = type.getConstructor(
                        require("com.paymentsledger.reconciliation.ReconciliationService"),
                        require("com.paymentsledger.payment.ReconciliationSourceRecovery"),
                        FinancialReplayService.class,
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        ReportPort.class,
                        Clock.class,
                        ProviderScope.class,
                        String.class,
                        ZoneId.class)
                .newInstance(engine, recovery, replay, jdbc, report, clock, scope, "USD", ZoneOffset.UTC);
        invoke(worker, "drain");
        assertThat(jdbc.queryForObject("""
                        SELECT outcome
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=5""", String.class, run)).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_evidence
                        WHERE evidence_kind='PROVIDER_REPORT_READ'""", Long.class)).isEqualTo(1);
    }

    @Test
    void pendingPeriodReportResumesWithFetchWithoutRequestingAnotherReport() throws Exception {
        binding(order("pending-report"));
        int[] calls = {0, 0};
        ReportPort report = new ReportPort() {
            public ProviderCallResult<ReportFact> requestReport(ReportRequest q) {
                calls[0]++;
                return reads.ok(new ReportFact("report_pending", "pending", from, until, List.of(), "d".repeat(64)));
            }

            public ProviderCallResult<ReportFact> fetchReport(ProviderScope s, String id) {
                assertThat(s).isEqualTo(scope);
                assertThat(id).isEqualTo("report_pending");
                calls[1]++;
                return reads.ok(new ReportFact(
                        id,
                        "available",
                        from,
                        until,
                        List.of(
                                new BalanceSnapshot("USD", "PAYMENTS", 0, 0, from),
                                new BalanceSnapshot("USD", "PAYMENTS", 9700, 0, until)),
                        "e".repeat(64)));
            }
        };
        var worker = new com.paymentsledger.reconciliation.ReconciliationWorker(
                (com.paymentsledger.reconciliation.ReconciliationService) engine,
                (ReconciliationSourceRecovery) recovery,
                replay,
                jdbc,
                report,
                clock,
                scope,
                "USD",
                ZoneOffset.UTC);
        worker.drain();
        assertThat(jdbc.queryForObject(
                        "SELECT finished_at FROM platform.reconciliation_run WHERE id=?", Timestamp.class, run))
                .isNull();
        worker.drain();
        assertThat(calls).containsExactly(1, 1);
        assertThat(jdbc.queryForObject("""
                        SELECT outcome
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=5""", String.class, run)).isEqualTo("PASS");
    }

    @Test
    void incompleteOldWindowsDoNotStarveLaterWindows() throws Exception {
        reads.pagesUnavailable = true;
        var worker = new com.paymentsledger.reconciliation.ReconciliationWorker(
                (com.paymentsledger.reconciliation.ReconciliationService) engine,
                (ReconciliationSourceRecovery) recovery,
                replay,
                jdbc,
                null,
                clock,
                scope,
                "USD",
                ZoneOffset.UTC);
        worker.nightly();
        worker.drain();
        worker.drain();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.import_window w
                        JOIN platform.reconciliation_run r ON r.import_window_id=w.id
                        WHERE r.id<>? AND w.version>0""", Long.class, run)).isEqualTo(7);
    }

    @Test
    void concurrentClaimersShareOneDurableSourceTask() throws Exception {
        binding(order("concurrent-claim"));
        invoke(recovery, "enqueue", scope, run, "txn_recovered");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<List<?>> claim = () -> {
                start.await();
                return (List<?>) invoke(recovery, "claim", scope, run, 1);
            };
            var a = pool.submit(claim);
            var b = pool.submit(claim);
            start.countDown();
            var all = new ArrayList<Object>();
            all.addAll(a.get(10, java.util.concurrent.TimeUnit.SECONDS));
            all.addAll(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(all).hasSize(1);
            assertThat(invoke(recovery, "recover", all.getFirst())).isEqualTo("LINKED");
            assertThat(psp()).isEqualTo(9700);
            assertThat(count("journal")).isEqualTo(1);
        }
    }

    void conflictingBt() {
        jdbc.update("""
                INSERT INTO platform.bt_evidence_conflict(organization_id,environment_id,provider_account_id,
                    livemode,balance_transaction_id,observation_id,existing_fact,incoming_fact)
                SELECT
                    organization_id,
                    environment_id,
                    provider_account_id,
                    livemode,
                    id,
                    first_observation_id,
                    immutable_fact,
                    '{\"amountMinor\":9999}'::jsonb
                FROM platform.balance_transaction
                WHERE bt_id='txn_recovered'""");
    }

    @Test
    void conflictingBtCannotCreateRecoveredPaymentOrHistoricalNotification() throws Exception {
        binding(order("conflict-before-link"));
        conflictingBt();
        drain();
        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("journal")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT reason
                        FROM platform.recovery_task
                        WHERE task_type='RECONCILIATION_SOURCE'""", String.class)).isEqualTo("SOURCE_EVIDENCE_CONFLICT");
    }

    @Test
    void conflictDiscoveredAfterLinkPreventsHistoricalFulfillment() throws Exception {
        binding(order("conflict-after-link"));
        drain();
        conflictingBt();
        assertThat(new FulfillmentEligibility.Database(jdbc).evaluate(event()).eligible())
                .isFalse();
        assertThat(count("journal")).isEqualTo(1);
    }

    @Test
    void moreThanOneReplayBatchRemainsOpenUntilAllFactsWereAttempted() throws Exception {
        invoke(engine, "finish", run, null);
        reads.extra = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            reads.extra.add(new BalanceTransactionFact(
                    "adj_" + String.format("%02d", i),
                    "adjustment",
                    100,
                    0,
                    100,
                    "USD",
                    "PAYMENTS",
                    "unknown_" + i,
                    "unknown",
                    "available",
                    at,
                    at,
                    List.of(),
                    "a".repeat(64)));
        UUID batch = (UUID)
                invoke(engine, "createDay", scope, "USD", LocalDate.ofInstant(from, ZoneOffset.UTC), ZoneOffset.UTC);
        var worker = new com.paymentsledger.reconciliation.ReconciliationWorker(
                (com.paymentsledger.reconciliation.ReconciliationService) engine,
                (ReconciliationSourceRecovery) recovery,
                replay,
                jdbc,
                null,
                clock,
                scope,
                "USD",
                ZoneOffset.UTC);
        worker.drain();
        assertThat(jdbc.queryForObject(
                        "SELECT finished_at FROM platform.reconciliation_run WHERE id=?", Timestamp.class, batch))
                .isNull();
        worker.drain();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.balance_transaction
                        WHERE bt_id LIKE 'adj_%' AND state='POSTED'""", Long.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject(
                        "SELECT finished_at FROM platform.reconciliation_run WHERE id=?", Timestamp.class, batch))
                .isNotNull();
    }

    @Test
    void invalidDrainBudgetIsRejectedBeforeAnyTaskIsCreated() throws Exception {
        assertThatThrownBy(() -> invoke(recovery, "drain", scope, run, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("recovery_task")).isZero();
    }

    @Test
    void transientReportFetchKeepsPendingIdUntilAvailable() throws Exception {
        pendingReportRecovers(false);
    }

    @Test
    void thrownReportFetchKeepsPendingIdUntilAvailable() throws Exception {
        pendingReportRecovers(true);
    }

    void pendingReportRecovers(boolean thrown) throws Exception {
        binding(order("report-retry"));
        int[] calls = {0, 0};
        ReportPort report = new ReportPort() {
            public ProviderCallResult<ReportFact> requestReport(ReportRequest q) {
                calls[0]++;
                return reads.ok(new ReportFact("report_retry", "pending", from, until, List.of(), "d".repeat(64)));
            }

            public ProviderCallResult<ReportFact> fetchReport(ProviderScope s, String id) {
                assertThat(id).isEqualTo("report_retry");
                assertThat(s).isEqualTo(scope);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                calls[1]++;
                if (calls[1] == 1) {
                    if (thrown) throw new IllegalStateException("temporary read failure");
                    return new ProviderCallResult<>(
                            ProviderCallClassification.RETRYABLE_TRANSIENT, null, reads.evidence());
                }
                return reads.ok(new ReportFact(
                        id,
                        "available",
                        from,
                        until,
                        List.of(
                                new BalanceSnapshot("USD", "PAYMENTS", 0, 0, from),
                                new BalanceSnapshot("USD", "PAYMENTS", 9700, 0, until)),
                        "e".repeat(64)));
            }
        };
        var worker = new com.paymentsledger.reconciliation.ReconciliationWorker(
                (com.paymentsledger.reconciliation.ReconciliationService) engine,
                (ReconciliationSourceRecovery) recovery,
                replay,
                jdbc,
                report,
                clock,
                scope,
                "USD",
                ZoneOffset.UTC);
        worker.drain();
        worker.drain();
        assertThat(jdbc.queryForObject(
                        "SELECT finished_at FROM platform.reconciliation_run WHERE id=?", Timestamp.class, run))
                .isNull();
        worker.drain();
        assertThat(calls).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("""
                        SELECT outcome
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=5""", String.class, run)).isEqualTo("PASS");
        assertThat(count("journal")).isEqualTo(1);
    }

    class Reads implements BalanceTransactionPort, ChargePort, PaymentIntentPort {
        List<BalanceTransactionFact> extra = List.of();
        Map<String, String> metadata = Map.of();
        long captureAmount = 10000;
        boolean unavailable, pagesUnavailable;
        String readVersion = "independent-fixture-v1";

        ProviderEvidence evidence() {
            return new ProviderEvidence(
                    null, null, clock.instant(), clock.instant(), 200, null, "b".repeat(64), readVersion);
        }

        <T> ProviderCallResult<T> ok(T value) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, value, evidence());
        }

        BalanceTransactionFact bt() {
            return new BalanceTransactionFact(
                    "txn_recovered",
                    "charge",
                    10000,
                    300,
                    9700,
                    "usd",
                    "PAYMENTS",
                    "ch_recovered",
                    "charge",
                    "available",
                    at,
                    at,
                    List.of(),
                    "b".repeat(64));
        }

        public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
            if (pagesUnavailable)
                return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
            return ok(new BalanceTransactionPage(
                    at.isBefore(q.createdGte()) || !at.isBefore(q.createdLt())
                            ? List.of()
                            : extra.isEmpty() ? List.of(bt()) : extra,
                    false,
                    null));
        }

        public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
            return ok(bt());
        }

        public ProviderCallResult<ChargeFact> queryCharge(ProviderScope s, String id) {
            if (unavailable)
                return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
            return ok(new ChargeFact(
                    "ch_recovered",
                    "pi_recovered",
                    "succeeded",
                    true,
                    captureAmount,
                    captureAmount,
                    "usd",
                    "txn_recovered",
                    null,
                    null,
                    at));
        }

        public ProviderCallResult<List<ChargeFact>> listChargeAttempts(ProviderScope s, String id) {
            return ok(List.of(queryCharge(s, "ch_recovered").payload()));
        }

        public ProviderCallResult<PaymentIntentFact> queryPaymentIntent(ProviderScope s, String id) {
            return ok(new PaymentIntentFact(
                    "pi_recovered",
                    "succeeded",
                    10000,
                    10000L,
                    "usd",
                    List.of("ch_recovered"),
                    "ch_recovered",
                    null,
                    metadata,
                    at));
        }
    }
}
