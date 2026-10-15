package com.paymentsledger.financialingestion;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class FinancialIngestionIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Instant from = Instant.parse("2026-03-01T00:00:00Z"), until = Instant.parse("2026-03-02T00:00:00Z");
    final Clock clock = Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC);
    JdbcTemplate admin, jdbc;
    DataSourceTransactionManager tx;
    FinancialImportService importer;
    FinancialReplayService replay;
    TestProvider provider;

    static UUID id(int n) {
        return new UUID(0, n);
    }

    @BeforeEach
    void setup() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'financial')", id(1));
        admin.update(
                "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",
                id(2),
                id(1));
        admin.update(
                "INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE','financial',false)",
                id(4),
                id(1),
                id(2));
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2),('JPY',0)");
        admin.update(
                "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD'),(?,?,'JPY')",
                id(1),
                id(2),
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode) VALUES (?,?,?,false,'ZERO')",
                id(1),
                id(2),
                id(4));
        int n = 20;
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SALES_REVENUE", "SALES_RETURNS", "SUSPENSE")) {
            boolean internal = purpose.startsWith("SALES");
            admin.update(
                    "INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain) VALUES (?,?,?,'USD',?,?,?,?)",
                    id(n++),
                    id(1),
                    id(2),
                    purpose,
                    internal ? "INTERNAL" : "PROVIDER",
                    internal ? null : id(4),
                    internal ? null : "PAYMENTS");
        }
        provider = new TestProvider();
        importer = new FinancialImportService(jdbc, tx, mapper, provider, clock);
        replay = new FinancialReplayService(
                jdbc, tx, mapper, new LedgerPostingService(jdbc, mapper), RefundEvidencePort.unavailable());
    }

    BalanceTransactionFact fact(String id, long amount, String currency, String type, String domain) {
        return new BalanceTransactionFact(
                id,
                type,
                amount,
                0,
                amount,
                currency,
                domain,
                "src_" + id,
                type,
                "pending",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
    }

    ProviderCallResult<BalanceTransactionPage> page(
            List<BalanceTransactionFact> facts, boolean more, String cursor, String version) {
        return new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(facts, more, cursor),
                new ProviderEvidence("req", null, from, until, 200, null, "a".repeat(64), version));
    }

    UUID window() {
        return importer.createWindow(scope, from, until, "BACKFILL");
    }

    long count(String table) {
        return admin.queryForObject("SELECT count(*) FROM platform." + table, Long.class);
    }

    void ingest(BalanceTransactionFact fact) {
        provider.pages.add(page(List.of(fact), false, null, "v1"));
        assertThat(importer.importPage(window())).isEqualTo("COMPLETE");
    }

    class TestProvider implements BalanceTransactionPort {
        final Queue<ProviderCallResult<BalanceTransactionPage>> pages = new ConcurrentLinkedQueue<>();
        BalanceTransactionFact refreshed;

        public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return Objects.requireNonNull(pages.poll(), "interrupted provider");
        }

        public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new ProviderCallResult<>(
                    ProviderCallClassification.SUCCESS,
                    refreshed,
                    new ProviderEvidence("refresh", null, from, until, 200, null, "b".repeat(64), "v1"));
        }
    }

    @Test
    void interruptedPaginationResumesDurableCursorAndCompletesOnlyOnTerminalPage() {
        UUID w = window();
        provider.pages.add(page(List.of(fact("a", 100, "USD", "adjustment", "PAYMENTS")), true, "a", "v1"));
        assertThat(importer.importPage(w)).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> importer.importPage(w)).hasMessage("interrupted provider");
        assertThat(admin.queryForObject("SELECT state FROM platform.import_window WHERE id=?", String.class, w))
                .isEqualTo("INCOMPLETE");
        assertThat(admin.queryForObject(
                        "SELECT next_cursor FROM platform.import_cursor WHERE window_id=?", String.class, w))
                .isEqualTo("a");
        provider.pages.add(page(List.of(fact("b", -40, "USD", "adjustment", "PAYMENTS")), false, null, "v1"));
        assertThat(new FinancialImportService(jdbc, tx, mapper, provider, clock).importPage(w))
                .isEqualTo("COMPLETE");
        assertThat(count("balance_transaction")).isEqualTo(2);
        assertThat(count("import_page")).isEqualTo(2);
    }

    @Test
    void cursorCycleDuplicateIdsOutOfWindowAndVersionDriftNeverComplete() {
        for (String scenario : List.of("cycle", "duplicate", "outside", "version", "cursor")) {
            UUID w = window();
            provider.pages.add(page(
                    List.of(fact(scenario + "a", 100, "USD", "adjustment", "PAYMENTS")), true, scenario + "a", "v1"));
            assertThat(importer.importPage(w)).isEqualTo("ACCEPTED");
            var b = fact(scenario + "b", 100, "USD", "adjustment", "PAYMENTS");
            if (scenario.equals("outside"))
                b = new BalanceTransactionFact(
                        b.btId(),
                        b.type(),
                        100,
                        0,
                        100,
                        "USD",
                        "PAYMENTS",
                        b.sourceObjectId(),
                        b.sourceObjectType(),
                        "pending",
                        until,
                        until,
                        List.of(),
                        null);
            provider.pages.add(
                    switch (scenario) {
                        case "cycle" -> page(List.of(b), true, scenario + "a", "v1");
                        case "duplicate" ->
                            page(
                                    List.of(fact(scenario + "a", 100, "USD", "adjustment", "PAYMENTS")),
                                    false,
                                    null,
                                    "v1");
                        case "version" -> page(List.of(b), false, null, "v2");
                        case "cursor" -> page(List.of(b), true, "wrong", "v1");
                        default -> page(List.of(b), false, null, "v1");
                    });
            assertThat(importer.importPage(w)).as(scenario).isEqualTo("REJECTED");
            assertThat(admin.queryForObject("SELECT state FROM platform.import_window WHERE id=?", String.class, w))
                    .isEqualTo("INCOMPLETE");
        }
    }

    @Test
    void sameBtAmountOrCurrencyConflictRetainsOriginalAndJournal() {
        var first = fact("same", 100, "USD", "adjustment", "PAYMENTS");
        ingest(first);
        assertThat(replay.replay(scope, "same")).isEqualTo("POSTED");
        ingest(fact("same", 101, "USD", "adjustment", "PAYMENTS"));
        ingest(fact("same", 100, "JPY", "adjustment", "PAYMENTS"));
        assertThat(count("balance_transaction")).isEqualTo(1);
        assertThat(count("bt_evidence_conflict")).isEqualTo(2);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT amount_minor FROM platform.balance_transaction", Long.class))
                .isEqualTo(100);
    }

    @Test
    void concurrentDuplicateImportsAndReplayHaveOneBtOneJournalAndRebuildMatches() throws Exception {
        for (int i = 0; i < 4; i++)
            provider.pages.add(page(List.of(fact("same", 100, "USD", "adjustment", "PAYMENTS")), false, null, "v1"));
        List<UUID> windows = new ArrayList<>();
        for (int i = 0; i < 4; i++) windows.add(window());
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (UUID w : windows)
                jobs.add(pool.submit(() -> {
                    importer.importPage(w);
                    replay.replay(scope, "same");
                }));
            for (var j : jobs) j.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("balance_transaction")).isEqualTo(1);
        assertThat(count("journal")).isEqualTo(1);
        assertThat(new BalanceRebuildService(jdbc, clock)
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .isTrue();
        assertThat(admin.queryForObject(
                        "SELECT count(*) FROM platform.financial_event e JOIN platform.journal j ON j.financial_event_id=e.id WHERE event_key LIKE 'bt:%'",
                        Long.class))
                .isEqualTo(1);
        assertThat(admin.queryForObject(
                        "SELECT sum(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM platform.journal_entry",
                        Long.class))
                .isZero();
    }

    @Test
    void signedSuspenseAndUnavailableDependenciesPreserveEvidence() {
        ingest(fact("negative", -70, "USD", "adjustment", "PAYMENTS"));
        assertThat(replay.replay(scope, "negative")).isEqualTo("POSTED");
        ingest(fact("refund", -100, "USD", "refund", "PAYMENTS"));
        assertThat(replay.replay(scope, "refund")).isEqualTo("PENDING_EVIDENCE");
        ingest(fact("payout", -100, "USD", "payout", "PAYMENTS"));
        assertThat(replay.replay(scope, "payout")).isEqualTo("PENDING_EVIDENCE");
        ingest(fact("issuing", -100, "USD", "adjustment", "ISSUING"));
        assertThat(replay.replay(scope, "issuing")).isEqualTo("QUARANTINED");
        ingest(fact("fx", 100, "USD", "currency_conversion", "PAYMENTS"));
        assertThat(replay.replay(scope, "fx")).isEqualTo("QUARANTINED");
        ingest(fact("zero", 0, "USD", "adjustment", "PAYMENTS"));
        assertThat(replay.replay(scope, "zero")).isEqualTo("ZERO");
        assertThat(count("journal")).isEqualTo(1);
        assertThat(admin.queryForObject(
                        "SELECT reason FROM platform.balance_transaction WHERE bt_id='negative'", String.class))
                .isEqualTo("UNKNOWN_SOURCE");
    }

    @Test
    void availabilityRefreshIsRecordedWithoutSecondJournal() {
        var f = fact("available", 100, "USD", "adjustment", "PAYMENTS");
        ingest(f);
        replay.replay(scope, f.btId());
        provider.refreshed = new BalanceTransactionFact(
                f.btId(),
                f.type(),
                100,
                0,
                100,
                "USD",
                "PAYMENTS",
                f.sourceObjectId(),
                f.sourceObjectType(),
                "available",
                f.createdAt(),
                until.plusSeconds(10),
                List.of(),
                null);
        assertThat(importer.refreshPending(scope, f.btId())).isEqualTo("ACCEPTED");
        assertThat(replay.replay(scope, f.btId())).isEqualTo("POSTED");
        assertThat(count("journal")).isEqualTo(1);
        assertThat(count("provider_observation")).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT provider_status FROM platform.balance_transaction", String.class))
                .isEqualTo("available");
    }

    @Test
    void cutoffExcludesHistoryAndPolicyFreezesAfterAcceptance() {
        admin.update(
                "UPDATE platform.ingestion_policy SET opening_mode='VERIFIED_CUTOFF',cutoff_at=?,evidence_ref='verified',evidence_digest=?",
                java.sql.Timestamp.from(until),
                "a".repeat(64));
        ingest(fact("opening", 100, "USD", "adjustment", "PAYMENTS"));
        assertThat(replay.replay(scope, "opening")).isEqualTo("EXCLUDED");
        assertThat(count("journal")).isZero();
        assertThatThrownBy(() ->
                        admin.update("UPDATE platform.ingestion_policy SET cutoff_at=?", java.sql.Timestamp.from(from)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void runtimeCannotChangeImmutableFactOrCrossScopeBinding() {
        ingest(fact("immutable", 100, "USD", "adjustment", "PAYMENTS"));
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.balance_transaction SET amount_minor=101,net_minor=101"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.balance_transaction SET fact='{}'::jsonb"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.balance_transaction SET livemode=true"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void trustedRefundReplayUsesRealScopedDependencyAndActualNegativeFee() {
        UUID app = id(80), order = id(81), payment = id(82);
        admin.update(
                "INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,'refund-app')",
                app,
                id(1),
                id(2));
        admin.update(
                "INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot) VALUES (?,?,?,?,?,'USD','refund-order','fingerprint','fixture',1,1,1000,1000,'{}','{}')",
                order,
                id(1),
                id(2),
                app,
                id(4));
        admin.update(
                "INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,expires_at,payment_status,captured_amount_minor) VALUES (?,?,?,?,?,'USD',?,1000,now(),'SUCCEEDED',1000)",
                payment,
                id(1),
                id(2),
                app,
                id(4),
                order);
        var f = new BalanceTransactionFact(
                "refund_ready",
                "refund",
                -100,
                -5,
                -95,
                "USD",
                "PAYMENTS",
                "re_real",
                "refund",
                "pending",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
        ingest(f);
        assertThat(replay.replay(scope, f.btId())).isEqualTo("PENDING_EVIDENCE");
        RefundEvidencePort trusted = (sc, ref) -> sc.equals(scope) && ref.equals("re_real")
                ? Optional.of(new RefundEvidencePort.Evidence(payment, "re_real", f.btId(), 100, "USD", true))
                : Optional.empty();
        var enabled = new FinancialReplayService(jdbc, tx, mapper, new LedgerPostingService(jdbc, mapper), trusted);
        assertThat(enabled.replay(scope, f.btId())).isEqualTo("POSTED");
        assertThat(enabled.replay(scope, f.btId())).isEqualTo("POSTED");
        assertThat(admin.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(20)))
                .isEqualTo(-95);
        assertThat(admin.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(21)))
                .isEqualTo(-5);
        assertThat(admin.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(23)))
                .isEqualTo(100);
    }

    @Test
    void pageCursorFailureRollsBackEvidenceAndFactsAndCanResume() {
        UUID w = window();
        provider.pages.add(page(List.of(fact("atomic", 100, "USD", "adjustment", "PAYMENTS")), false, null, "v1"));
        admin.execute(
                "CREATE FUNCTION platform.pr10_test_cursor_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected cursor failure'; END $$");
        admin.execute(
                "CREATE TRIGGER pr10_test_cursor_failure BEFORE UPDATE ON platform.import_cursor FOR EACH ROW EXECUTE FUNCTION platform.pr10_test_cursor_failure()");
        try {
            assertThatThrownBy(() -> importer.importPage(w)).hasMessageContaining("injected cursor failure");
            assertThat(count("balance_transaction")).isZero();
            assertThat(count("provider_observation")).isZero();
            assertThat(count("import_page")).isZero();
        } finally {
            admin.execute("DROP TRIGGER pr10_test_cursor_failure ON platform.import_cursor");
            admin.execute("DROP FUNCTION platform.pr10_test_cursor_failure()");
        }
        provider.pages.add(page(List.of(fact("atomic", 100, "USD", "adjustment", "PAYMENTS")), false, null, "v1"));
        assertThat(importer.importPage(w)).isEqualTo("COMPLETE");
    }

    @Test
    void staleConcurrentSameWindowPageIsRecordedWithoutSecondAcceptance() throws Exception {
        UUID w = window();
        var barrier = new CyclicBarrier(2);
        BalanceTransactionPort concurrent = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return page(List.of(fact("fenced", 100, "USD", "adjustment", "PAYMENTS")), false, null, "v1");
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
                throw new UnsupportedOperationException();
            }
        };
        var service = new FinancialImportService(jdbc, tx, mapper, concurrent, clock);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.importPage(w));
            var b = pool.submit(() -> service.importPage(w));
            assertThat(Set.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETE", "STALE");
        }
        assertThat(count("balance_transaction")).isEqualTo(1);
        assertThat(count("provider_observation")).isEqualTo(2);
        assertThat(count("import_page")).isEqualTo(1);
    }

    @Test
    void missingProvisionedAccountStaysPendingAndFeeOrderDoesNotConflict() {
        admin.update("DELETE FROM platform.ledger_account WHERE id=?", id(20));
        var f = fact("provision", 100, "USD", "adjustment", "PAYMENTS");
        ingest(f);
        assertThat(replay.replay(scope, f.btId())).isEqualTo("PENDING_EVIDENCE");
        assertThat(count("journal")).isZero();
        admin.update(
                "INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain) VALUES (?,?,?,'USD','PSP_FUNDS','PROVIDER',?,'PAYMENTS')",
                id(20),
                id(1),
                id(2),
                id(4));
        assertThat(replay.replay(scope, f.btId())).isEqualTo("POSTED");
        var fees = List.of(new FeeDetail(1, "usd", "a", null), new FeeDetail(2, "USD", "b", null));
        for (var list : List.of(fees, List.of(fees.getLast(), fees.getFirst())))
            ingest(new BalanceTransactionFact(
                    "fee_order",
                    "adjustment",
                    100,
                    3,
                    97,
                    "USD",
                    "PAYMENTS",
                    "src",
                    "unknown",
                    "pending",
                    from.plusSeconds(1),
                    until,
                    list,
                    null));
        assertThat(count("bt_evidence_conflict")).isZero();
    }

    @Test
    void incompleteProviderAndEmptyContinuationAreRecordedWithoutCompletion() {
        for (boolean incomplete : new boolean[] {true, false}) {
            UUID w = window();
            provider.pages.add(
                    incomplete
                            ? new ProviderCallResult<>(
                                    ProviderCallClassification.RETRYABLE_TRANSIENT,
                                    null,
                                    new ProviderEvidence(null, null, from, until, 503, "unavailable", null, "v1"))
                            : page(List.of(), true, "missing", "v1"));
            assertThat(importer.importPage(w)).isEqualTo(incomplete ? "RETRY" : "REJECTED");
            assertThat(admin.queryForObject("SELECT state FROM platform.import_window WHERE id=?", String.class, w))
                    .isEqualTo("INCOMPLETE");
        }
        assertThat(count("provider_observation")).isEqualTo(2);
        assertThat(count("balance_transaction")).isZero();
        assertThat(count("import_page")).isZero();
    }

    @Test
    void retriesWholeLocalTransactionOnSerializationFailureWithoutRefetching() {
        UUID w = window();
        provider.pages.add(page(List.of(fact("retry", 100, "USD", "adjustment", "PAYMENTS")), false, null, "v1"));
        admin.execute("CREATE SEQUENCE platform.pr10_test_retry_sequence");
        admin.execute(
                "CREATE FUNCTION platform.pr10_test_retry() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,platform AS $$ BEGIN IF nextval('platform.pr10_test_retry_sequence')<3 THEN RAISE EXCEPTION 'injected serialization' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        admin.execute(
                "CREATE TRIGGER pr10_test_retry BEFORE UPDATE ON platform.import_cursor FOR EACH ROW EXECUTE FUNCTION platform.pr10_test_retry()");
        try {
            assertThat(importer.importPage(w)).isEqualTo("COMPLETE");
            assertThat(count("provider_observation")).isEqualTo(1);
            assertThat(count("import_page")).isEqualTo(1);
            assertThat(admin.queryForObject("SELECT last_value FROM platform.pr10_test_retry_sequence", Long.class))
                    .isEqualTo(3);
        } finally {
            admin.execute("DROP TRIGGER pr10_test_retry ON platform.import_cursor");
            admin.execute("DROP FUNCTION platform.pr10_test_retry()");
            admin.execute("DROP SEQUENCE platform.pr10_test_retry_sequence");
        }
    }

    @Test
    void missingStatusRemainsDurableRejectedObservation() {
        var f = new BalanceTransactionFact(
                "missing_status",
                "adjustment",
                1,
                0,
                1,
                "USD",
                "PAYMENTS",
                "src",
                "unknown",
                null,
                from.plusSeconds(1),
                until,
                List.of(),
                null);
        provider.pages.add(page(List.of(f), false, null, "v1"));
        assertThat(importer.importPage(window())).isEqualTo("REJECTED");
        assertThat(count("provider_observation")).isEqualTo(1);
        assertThat(count("balance_transaction")).isZero();
    }

    @Test
    void availabilityProjectionMovesBucketsWithoutChangingLedgerTotal() {
        var f = fact("buckets", 100, "USD", "adjustment", "PAYMENTS");
        ingest(f);
        replay.replay(scope, f.btId());
        var before = jdbc.queryForMap("SELECT * FROM platform.psp_availability_projection");
        assertThat(((Number) before.get("pending_net_minor")).longValue()).isEqualTo(100);
        assertThat(((Number) before.get("available_net_minor")).longValue()).isZero();
        provider.refreshed = new BalanceTransactionFact(
                f.btId(),
                f.type(),
                100,
                0,
                100,
                "USD",
                "PAYMENTS",
                f.sourceObjectId(),
                f.sourceObjectType(),
                "available",
                f.createdAt(),
                until,
                List.of(),
                null);
        importer.refreshPending(scope, f.btId());
        var after = jdbc.queryForMap("SELECT * FROM platform.psp_availability_projection");
        assertThat(((Number) after.get("pending_net_minor")).longValue()).isZero();
        assertThat(((Number) after.get("available_net_minor")).longValue()).isEqualTo(100);
        assertThat(after.get("known_bt_net_minor")).isEqualTo(before.get("known_bt_net_minor"));
        assertThat(((Number) after.get("unclassified_or_opening_minor")).longValue())
                .isZero();
        assertThat(count("journal")).isEqualTo(1);
    }

    @Test
    void cutoffExcludesExactInclusiveSnapshotBoundary() {
        admin.update(
                "UPDATE platform.ingestion_policy SET opening_mode='VERIFIED_CUTOFF',cutoff_at=?,evidence_ref='inclusive snapshot',evidence_digest=?",
                java.sql.Timestamp.from(from.plusSeconds(1)),
                "a".repeat(64));
        ingest(fact("exact_cutoff", 100, "USD", "adjustment", "PAYMENTS"));
        assertThat(replay.replay(scope, "exact_cutoff")).isEqualTo("EXCLUDED");
        assertThat(count("journal")).isZero();
    }

    @Test
    void invalidRefreshStatusPreservesPendingAndRecordsEvidence() {
        var f = fact("refresh_invalid", 100, "USD", "adjustment", "PAYMENTS");
        ingest(f);
        replay.replay(scope, f.btId());
        for (String status : new String[] {"unexpected", null}) {
            provider.refreshed = new BalanceTransactionFact(
                    f.btId(),
                    f.type(),
                    100,
                    0,
                    100,
                    "USD",
                    "PAYMENTS",
                    f.sourceObjectId(),
                    f.sourceObjectType(),
                    status,
                    f.createdAt(),
                    until,
                    List.of(),
                    null);
            assertThat(importer.refreshPending(scope, f.btId())).isEqualTo("REJECTED");
        }
        assertThat(admin.queryForObject("SELECT provider_status FROM platform.balance_transaction", String.class))
                .isEqualTo("pending");
        assertThat(count("provider_observation")).isEqualTo(3);
        assertThat(count("journal")).isEqualTo(1);
    }

    @Test
    void runtimeCannotAttachAnotherWindowsObservationOrReassignCursor() {
        ingest(fact("scope_page", 100, "USD", "adjustment", "PAYMENTS"));
        UUID w = window();
        UUID observation = jdbc.queryForObject("SELECT id FROM platform.provider_observation", UUID.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO platform.import_page(window_id,request_cursor,terminal,observation_id,bt_ids) VALUES (?,'',true,?,'{}')",
                        w,
                        observation))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID another = window();
        admin.update("DELETE FROM platform.import_cursor WHERE window_id=?", another);
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE platform.import_cursor SET window_id=? WHERE window_id=?", another, w))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void joinedTransactionDoesNotRetryInsideAnAbortedOuterTransaction() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var outer = new org.springframework.transaction.support.TransactionTemplate(tx);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> new FinancialTransactions(tx).run(() -> {
                    attempts.incrementAndGet();
                    throw new org.springframework.dao.CannotSerializeTransactionException(
                            "injected", new java.sql.SQLException("retry outer owner", "40001"));
                })))
                .isInstanceOf(org.springframework.dao.CannotSerializeTransactionException.class);
        assertThat(attempts).hasValue(1);
    }
}
