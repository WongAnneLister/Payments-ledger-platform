package com.paymentsledger.reconciliation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.*;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ReconciliationCoreIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);
    final Instant from = Instant.parse("2026-03-01T00:00:00Z"), until = Instant.parse("2026-03-02T00:00:00Z");
    final Clock clock = Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC);
    JdbcTemplate admin, jdbc;
    DataSourceTransactionManager tx;
    FinancialImportService importer;
    FinancialReplayService replay;
    Object service;
    final Queue<ProviderCallResult<BalanceTransactionPage>> pages = new ArrayDeque<>();

    static UUID id(int n) {
        return new UUID(0, n);
    }

    @BeforeEach
    void setup() throws Exception {
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
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'reconcile')", id(1));
        admin.update("""
                INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                VALUES (?,?,'test',false)""", id(2), id(1));
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','reconcile',false)""", id(4), id(1), id(2));
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
        admin.update("""
                INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                VALUES (?,?,'USD')""", id(1), id(2));
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode)
                VALUES (?,?,?,false,'ZERO')""", id(1), id(2), id(4));
        int n = 20;
        for (String purpose : List.of("PSP_FUNDS", "PSP_FEE_EXPENSE", "SUSPENSE"))
            admin.update("""
                    INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                        source_domain,provider_account_id,balance_domain)
                    VALUES (?,?,?,'USD',?,'PROVIDER',?,'PAYMENTS')""", id(n++), id(1), id(2), purpose, id(4));
        var mapper = new ObjectMapper().findAndRegisterModules();
        BalanceTransactionPort provider = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                return Objects.requireNonNull(pages.poll());
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
                throw new UnsupportedOperationException();
            }
        };
        importer = new FinancialImportService(jdbc, tx, mapper, provider, clock);
        replay = new FinancialReplayService(
                jdbc, tx, mapper, new LedgerPostingService(jdbc, mapper), RefundEvidencePort.unavailable());
        service = ReconciliationWindowsTest.require("com.paymentsledger.reconciliation.ReconciliationService")
                .getConstructors()[0]
                .newInstance(jdbc, tx, mapper, importer, new BalanceRebuildService(jdbc, clock), null, clock);
    }

    UUID run() throws Exception {
        return (UUID) service.getClass()
                .getMethod(
                        "createBackfill", ProviderScope.class, String.class, Instant.class, Instant.class, ZoneId.class)
                .invoke(service, scope, "USD", from, until, ZoneOffset.UTC);
    }

    void page(boolean more, String id, long amount) {
        var facts = id == null
                ? List.<BalanceTransactionFact>of()
                : List.of(new BalanceTransactionFact(
                        id,
                        "adjustment",
                        amount,
                        0,
                        amount,
                        "USD",
                        "PAYMENTS",
                        "src",
                        "unknown",
                        "pending",
                        from.plusSeconds(1),
                        until,
                        List.of(),
                        "a".repeat(64)));
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(facts, more, more ? id : null),
                new ProviderEvidence("fixture", null, from, until, 200, null, "b".repeat(64), "v1")));
    }

    void advance(UUID run, int budget) throws Exception {
        service.getClass().getMethod("advance", UUID.class, int.class).invoke(service, run, budget);
    }

    Object evidence(long closing, ProviderScope scope, Instant cutoff) throws Exception {
        Class<?> kind = Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence$Kind");
        Object fixture = Enum.valueOf((Class) kind, "INDEPENDENT_FIXTURE");
        return Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence")
                .getConstructors()[0]
                .newInstance(
                        scope,
                        "USD",
                        "PAYMENTS",
                        from,
                        cutoff,
                        0L,
                        closing,
                        "independent-provider-fixture",
                        "fixture-v1",
                        "c".repeat(64),
                        "v1",
                        fixture,
                        false);
    }

    void finish(UUID run, Object evidence) throws Exception {
        service.getClass()
                .getMethod(
                        "finish",
                        UUID.class,
                        Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence"))
                .invoke(service, run, evidence);
    }

    String check(UUID run, int number) {
        return jdbc.queryForObject("""
                SELECT outcome
                FROM platform.reconciliation_check
                WHERE run_id=? AND check_number=?""", String.class, run, number);
    }

    @Test
    void independentClosingDetectsOmittedExternalRefundAndPersistsSevenImmutableOutcomes() throws Exception {
        UUID run = run();
        page(false, "sale", 1000);
        advance(run, 1);
        replay.replay(scope, "sale");
        // External constant 900 includes a refund absent from imported facts; production math is not the oracle.
        finish(run, evidence(900, scope, until));
        assertThat(check(run, 5)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.reconciliation_check WHERE run_id=?", Long.class, run))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                        SELECT residual
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=5""", Long.class, run)).isEqualTo(100);
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE platform.reconciliation_check SET outcome='PASS' WHERE run_id=?", run))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void absentIndependentClosingAndNonterminalPaginationRemainIncomplete() throws Exception {
        UUID run = run();
        page(true, "first", 100);
        advance(run, 1);
        replay.replay(scope, "first");
        finish(run, null);
        assertThat(check(run, 4)).isEqualTo("INCOMPLETE");
        assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT state FROM platform.reconciliation_run WHERE id=?", String.class, run))
                .isEqualTo("INCOMPLETE");
    }

    @Test
    void wrongAccountOrCurrentCutoffCannotServeAsYesterdayClosing() throws Exception {
        for (boolean wrongScope : new boolean[] {true, false}) {
            UUID run = run();
            page(false, null, 0);
            advance(run, 1);
            finish(
                    run,
                    evidence(
                            0,
                            wrongScope ? new ProviderScope(id(1), id(2), id(99), false) : scope,
                            wrongScope ? until : clock.instant()));
            assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
        }
    }

    @Test
    void projectionTamperingIsDetectedIndependently() throws Exception {
        UUID run = run();
        page(false, "projection", 100);
        advance(run, 1);
        replay.replay(scope, "projection");
        admin.execute("ALTER TABLE platform.balance_projection DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.balance_projection
                    SET balance_minor=balance_minor+1
                    WHERE ledger_account_id=?""", id(20));
        } finally {
            admin.execute("ALTER TABLE platform.balance_projection ENABLE TRIGGER USER");
        }
        finish(run, evidence(100, scope, until));
        assertThat(check(run, 7)).isEqualTo("FAIL");
    }

    @Test
    void windowCompleteFlagCannotHideMissingAcceptedPage() throws Exception {
        UUID run = run();
        page(true, "page_a", 100);
        page(false, "page_b", 100);
        advance(run, 2);
        replay.replay(scope, "page_a");
        replay.replay(scope, "page_b");
        admin.execute("ALTER TABLE platform.import_page DISABLE TRIGGER USER");
        try {
            admin.update("""
                    DELETE FROM platform.import_page
                    WHERE request_cursor=''
                        AND window_id=(SELECT import_window_id FROM platform.reconciliation_run WHERE id=?)""", run);
        } finally {
            admin.execute("ALTER TABLE platform.import_page ENABLE TRIGGER USER");
        }
        finish(run, evidence(200, scope, until));
        assertThat(check(run, 4)).isEqualTo("INCOMPLETE");
        assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
    }

    @Test
    void missingFeeModelIsExplicitAndCannotChangeActualMoney() throws Exception {
        UUID run = run();
        var fact = new BalanceTransactionFact(
                "fee",
                "adjustment",
                1000,
                37,
                963,
                "USD",
                "PAYMENTS",
                "src",
                "unknown",
                "pending",
                from.plusSeconds(1),
                until,
                List.of(),
                "a".repeat(64));
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(fact), false, null),
                new ProviderEvidence("fixture", null, from, until, 200, null, "b".repeat(64), "v1")));
        advance(run, 1);
        replay.replay(scope, "fee");
        finish(run, evidence(963, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='UNMODELED_FEE'""", Long.class, run)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(20)))
                .isEqualTo(963);
        assertThat(jdbc.queryForObject(
                        "SELECT fee_minor FROM platform.balance_transaction WHERE bt_id='fee'", Long.class))
                .isEqualTo(37);
    }

    @Test
    void feeModelMismatchPreservesActualFeeAndVersionedResidual() throws Exception {
        admin.update("""
                INSERT INTO platform.fee_model(organization_id,environment_id,provider_account_id,livemode,currency,
                    balance_domain,business_type,model_version,effective_from,effective_until,basis_points,
                    flat_minor,rounding)
                VALUES (?,?,?,false,'USD','PAYMENTS','adjustment','fee-v1',?,?,0,30,'HALF_UP')""", id(1), id(2), id(4), java.sql.Timestamp.from(from), java.sql.Timestamp.from(until));
        UUID run = run();
        var fact = new BalanceTransactionFact(
                "fee_mismatch",
                "adjustment",
                1000,
                37,
                963,
                "USD",
                "PAYMENTS",
                "src",
                "unknown",
                "pending",
                from.plusSeconds(1),
                until,
                List.of(),
                "a".repeat(64));
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(fact), false, null),
                new ProviderEvidence("fixture", null, from, until, 200, null, "b".repeat(64), "v1")));
        advance(run, 1);
        replay.replay(scope, "fee_mismatch");
        finish(run, evidence(963, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='FEE_MODEL_MISMATCH'""", Long.class, run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT residual
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='FEE_MODEL_MISMATCH'""", Long.class, run)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->>'feeModel'
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='FEE_MODEL_MISMATCH'""", String.class, run)).isEqualTo("fee-v1");
        assertThat(jdbc.queryForObject(
                        "SELECT fee_minor FROM platform.balance_transaction WHERE bt_id='fee_mismatch'", Long.class))
                .isEqualTo(37);
    }

    @Test
    void acceptedEventWithoutJournalFailsFirstCheck() throws Exception {
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        admin.execute("ALTER TABLE platform.financial_event DISABLE TRIGGER USER");
        try {
            admin.update("""
                    INSERT INTO platform.financial_event(organization_id,environment_id,currency,provider_account_id,
                        event_key,event_type,source_domain,source_ref,facts)
                    VALUES (?,?,'USD',?,'orphan-event','fixture','PROVIDER','orphan','{}')""", id(1), id(2), id(4));
        } finally {
            admin.execute("ALTER TABLE platform.financial_event ENABLE TRIGGER USER");
        }
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 1)).isEqualTo("FAIL");
    }

    @Test
    void journalWithoutAcceptedEventFailsReverseCoverage() throws Exception {
        UUID run = run();
        page(false, "reverse", 100);
        advance(run, 1);
        replay.replay(scope, "reverse");
        admin.execute("ALTER TABLE platform.financial_event DISABLE TRIGGER ALL");
        try {
            admin.update("DELETE FROM platform.financial_event WHERE source_ref='reverse'");
        } finally {
            admin.execute("ALTER TABLE platform.financial_event ENABLE TRIGGER ALL");
        }
        finish(run, evidence(100, scope, until));
        assertThat(check(run, 1)).isEqualTo("FAIL");
    }

    @Test
    void openingJournalAndHistoricalBtAreNotCountedTwice() throws Exception {
        Instant cutoff = from.plusSeconds(10);
        admin.update("""
                UPDATE platform.ingestion_policy
                SET opening_mode='VERIFIED_CUTOFF',cutoff_at=?,evidence_ref='opening-fixture',evidence_digest=?""", java.sql.Timestamp.from(cutoff), "a".repeat(64));
        admin.update("""
                INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                    source_domain)
                VALUES (?,?,?,'USD','OPENING_EQUITY','INTERNAL')""", id(30), id(1), id(2));
        var mapper = new ObjectMapper();
        new LedgerPostingService(jdbc, mapper)
                .post(
                        new LedgerScope(id(1), id(2), "USD", id(4)),
                        new PostingCommand(
                                "opening-fixture",
                                "L-OPENING",
                                "INTERNAL",
                                "opening-fixture",
                                mapper.createObjectNode(),
                                List.of(
                                        new PostingLine(id(20), Side.DEBIT, 100),
                                        new PostingLine(id(30), Side.CREDIT, 100)),
                                null));
        UUID run = run();
        var old = new BalanceTransactionFact(
                "historic",
                "adjustment",
                100,
                0,
                100,
                "USD",
                "PAYMENTS",
                "src-old",
                "unknown",
                "pending",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
        var recent = new BalanceTransactionFact(
                "recent",
                "adjustment",
                50,
                0,
                50,
                "USD",
                "PAYMENTS",
                "src-new",
                "unknown",
                "pending",
                from.plusSeconds(20),
                until,
                List.of(),
                null);
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(old, recent), false, null),
                new ProviderEvidence("fixture", null, from, until, 200, null, "b".repeat(64), "v1")));
        advance(run, 1);
        assertThat(replay.replay(scope, "historic")).isEqualTo("EXCLUDED");
        replay.replay(scope, "recent");
        Class<?> kind = Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence$Kind");
        Object independent = Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence")
                .getConstructors()[0]
                .newInstance(
                        scope,
                        "USD",
                        "PAYMENTS",
                        cutoff,
                        until,
                        100L,
                        150L,
                        "independent-provider-fixture",
                        "fixture-v1",
                        "c".repeat(64),
                        "v1",
                        Enum.valueOf((Class) kind, "INDEPENDENT_FIXTURE"),
                        false);
        finish(run, independent);
        assertThat(check(run, 5)).isEqualTo("PASS");
        assertThat(jdbc.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(20)))
                .isEqualTo(150);
    }

    @Test
    void arbitraryOpeningCannotMakeThreeSidesAgree() throws Exception {
        UUID run = run();
        page(false, "opening-mismatch", 100);
        advance(run, 1);
        replay.replay(scope, "opening-mismatch");
        Class<?> kind = Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence$Kind");
        Object independent = Class.forName("com.paymentsledger.reconciliation.IndependentBalanceEvidence")
                .getConstructors()[0]
                .newInstance(
                        scope,
                        "USD",
                        "PAYMENTS",
                        from,
                        until,
                        50L,
                        150L,
                        "independent-provider-fixture",
                        "fixture-v1",
                        "c".repeat(64),
                        "v1",
                        Enum.valueOf((Class) kind, "INDEPENDENT_FIXTURE"),
                        false);
        finish(run, independent);
        assertThat(check(run, 5)).isEqualTo("FAIL");
    }

    @Test
    void nonBtPspMovementWithoutBusinessCutoffIsIncomplete() throws Exception {
        UUID run = run();
        page(false, "normal", 100);
        advance(run, 1);
        replay.replay(scope, "normal");
        var mapper = new ObjectMapper();
        new LedgerPostingService(jdbc, mapper)
                .post(
                        new LedgerScope(id(1), id(2), "USD", id(4)),
                        new PostingCommand(
                                "manual-fixture",
                                "manual",
                                "INTERNAL",
                                "unverified-date",
                                mapper.createObjectNode(),
                                List.of(
                                        new PostingLine(id(20), Side.DEBIT, 7),
                                        new PostingLine(id(22), Side.CREDIT, 7)),
                                null));
        finish(run, evidence(107, scope, until));
        assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
    }

    void paymentFixture() {
        admin.update("""
                INSERT INTO platform.application(id,organization_id,environment_id,code)
                VALUES (?,?,?,'fixture')""", id(80), id(1), id(2));
        admin.update("""
                INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,
                    unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot)
                VALUES (?,?,?,?,?,'USD','fixture','fixture','fixture',1,1,1000,1000,'{}',
                    '{\"partner_ref\":\"partner\",\"share_basis_points\":1000,\"policy_version\":\"fixture\"}')""", id(81), id(1), id(2), id(80), id(4));
        admin.update("""
                INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,business_order_id,amount_minor,expires_at,payment_status,captured_amount_minor)
                VALUES (?,?,?,?,?,'USD',?,1000,now(),'SUCCEEDED',1000)""", id(82), id(1), id(2), id(80), id(4), id(81));
    }

    @Test
    void reservationControlWithoutBackingRefundFailsReconstruction() throws Exception {
        paymentFixture();
        admin.update("""
                INSERT INTO platform.refund_control(payment_id,organization_id,environment_id,application_id,
                    provider_account_id,currency,captured_minor,reserved_minor)
                VALUES (?,?,?,?,?,'USD',1000,100)""", id(82), id(1), id(2), id(80), id(4));
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 2)).isEqualTo("FAIL");
    }

    @Test
    void effectiveShareWithoutJournalHistoryFailsCoverageAndCap() throws Exception {
        paymentFixture();
        admin.update("""
                INSERT INTO platform.share_allocation(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,partner_ref,policy_version,rate_bps)
                VALUES (?,?,?,?,?,'USD',?,'partner','fixture',1000)""", id(90), id(1), id(2), id(80), id(4), id(82));
        admin.execute("ALTER TABLE platform.share_allocation DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.share_allocation
                    SET effective_minor=150,cap_minor=150,generation=1
                    WHERE id=?""", id(90));
        } finally {
            admin.execute("ALTER TABLE platform.share_allocation ENABLE TRIGGER USER");
        }
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 3)).isEqualTo("FAIL");
    }

    @Test
    void payoutCountersWithoutAuthoritativeFinancialHistoryFail() throws Exception {
        admin.update("""
                INSERT INTO platform.payout(id,organization_id,environment_id,provider_account_id,livemode,
                    provider_payout_id,currency,amount_minor,automatic,last_observed_at)
                VALUES (?,?,?,?,false,'po_fixture','USD',1000,true,now())""", id(95), id(1), id(2), id(4));
        admin.execute("ALTER TABLE platform.payout DISABLE TRIGGER USER");
        try {
            admin.update("UPDATE platform.payout SET outgoing_minor=1000,transit_minor=1000 WHERE id=?", id(95));
        } finally {
            admin.execute("ALTER TABLE platform.payout ENABLE TRIGGER USER");
        }
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 6)).isEqualTo("FAIL");
    }

    @Test
    void canonicalSourceAmountMismatchFailsTransactionComparison() throws Exception {
        paymentFixture();
        UUID run = run();
        page(false, "source_bt", 1000);
        advance(run, 1);
        replay.replay(scope, "source_bt");
        admin.update("""
                INSERT INTO platform.payment_attempt(id,organization_id,environment_id,application_id,
                    provider_account_id,currency,payment_id,provider_object_id,provider_status,payment_intent_id,
                    captured,amount_minor,amount_captured_minor,balance_transaction_id,fetched_at)
                VALUES (?,?,?,?,?,'USD',?,'ch_source','succeeded','pi_source',true,1000,999,'source_bt',now())""", id(98), id(1), id(2), id(80), id(4), id(82));
        admin.execute("ALTER TABLE platform.balance_transaction DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.balance_transaction
                    SET
                        business_type='charge',
                        source_object_type='charge',
                        source_object_id='ch_source',
                        payment_id=?,
                        reason=null
                    WHERE bt_id='source_bt'""", id(82));
        } finally {
            admin.execute("ALTER TABLE platform.balance_transaction ENABLE TRIGGER USER");
        }
        finish(run, evidence(1000, scope, until));
        assertThat(check(run, 4)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='SOURCE_OBJECT_MISMATCH'""", Long.class, run)).isEqualTo(1);
    }

    PayoutService configurePayoutChecks() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var ledger = new LedgerPostingService(jdbc, mapper);
        var payouts = new PayoutService(jdbc, tx, mapper, ledger);
        var bank = new BankMatcher(jdbc, tx, mapper, ledger, payouts);
        replay = new FinancialReplayService(
                jdbc,
                tx,
                mapper,
                ledger,
                RefundEvidencePort.unavailable(),
                RevenueShareService.disabled(),
                null,
                payouts);
        service = new ReconciliationService(
                jdbc, tx, mapper, importer, new BalanceRebuildService(jdbc, clock), bank, clock);
        return payouts;
    }

    BalanceTransactionFact payoutBt(String id, String type, long amount, long fee) {
        return new BalanceTransactionFact(
                id,
                type,
                amount,
                fee,
                amount - fee,
                "USD",
                "PAYMENTS",
                "po_test",
                "payout",
                "available",
                from.plusSeconds(1),
                until,
                List.of(),
                "a".repeat(64));
    }

    void payoutPage(List<BalanceTransactionFact> facts) {
        pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(facts, false, null),
                new ProviderEvidence("fixture", null, from, until, 200, null, "b".repeat(64), "v1")));
    }

    UUID partialPayout(boolean complete) throws Exception {
        var payouts = configurePayoutChecks();
        UUID payout = payouts.accept(
                scope,
                new PayoutFact("po_test", "pending", 5700, "USD", "txn_out", null, true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(List.of(payoutBt("txn_out", "payout", -5700, 12)));
        advance(run, 1);
        assertThat(replay.replay(scope, "txn_out")).isEqualTo("POSTED");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fixture = mapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("fixtureId", "partial")
                .put("bankAccountRef", "bank")
                .put("currency", "USD")
                .put("coverageFrom", from.toString())
                .put("coverageUntil", until.plusSeconds(86400).toString())
                .put("complete", complete);
        var line = fixture.putArray("lines")
                .addObject()
                .put("lineId", "partial_line")
                .put("kind", "CREDIT")
                .put("bookedAt", until.toString())
                .put("principalMinor", 2900)
                .put("feeMinor", 10);
        line.putArray("payoutReferences").add("po_test");
        new BankStatementImporter(jdbc, tx, mapper).importStatement(scope, fixture);
        UUID lineId = jdbc.queryForObject("""
                SELECT id
                FROM platform.bank_statement_line
                WHERE external_line_id='partial_line'""", UUID.class);
        new BankMatcher(jdbc, tx, mapper, new LedgerPostingService(jdbc, mapper), payouts)
                .match(scope, payout, lineId, 2900);
        finish(run, evidence(-5712, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->'bankStatements'->0->>'fixture_id'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=6""", String.class, run)).isEqualTo("partial");
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->'bankStatements'->0->>'digest'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=6""", String.class, run))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT digest FROM platform.bank_statement WHERE fixture_id='partial'", String.class));
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->'bankLines'->0->>'id'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=6""", String.class, run)).isEqualTo(lineId.toString());
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->'bankLines'->0->>'allocated_minor'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=6""", Long.class, run)).isEqualTo(2900);
        return run;
    }

    @Test
    void partialBankWithoutCompletenessRemainsIncomplete() throws Exception {
        UUID run = partialPayout(false);
        assertThat(check(run, 6)).isEqualTo("INCOMPLETE");
    }

    @Test
    void completeBankShortReceiptFailsAgainOnFreshNightlyVersion() throws Exception {
        UUID first = partialPayout(true);
        assertThat(check(first, 6)).isEqualTo("FAIL");
        UUID second = run();
        payoutPage(List.of(payoutBt("txn_out", "payout", -5700, 12)));
        advance(second, 1);
        finish(second, evidence(-5712, scope, until));
        assertThat(check(second, 6)).isEqualTo("FAIL");
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_check
                        WHERE check_number=6 AND outcome='FAIL'""", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_fact", Long.class))
                .isEqualTo(1);
    }

    @Test
    void confirmedPspReturnPassesPayoutWithoutFabricatedBankReceipt() throws Exception {
        var payouts = configurePayoutChecks();
        payouts.accept(
                scope,
                new PayoutFact("po_test", "failed", 5700, "USD", "txn_out", "txn_return", true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(
                List.of(payoutBt("txn_out", "payout", -5700, 12), payoutBt("txn_return", "payout_failure", 5700, -12)));
        advance(run, 1);
        assertThat(replay.replay(scope, "txn_out")).isEqualTo("POSTED");
        assertThat(replay.replay(scope, "txn_return")).isEqualTo("POSTED");
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 6)).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_bank_match", Long.class))
                .isZero();
    }

    @Test
    void resumeDuplicateAndLateOverlappingRunKeepOneJournalPerBt() throws Exception {
        UUID first = run();
        page(true, "first", 100);
        advance(first, 1);
        assertThat(jdbc.queryForObject(
                        "SELECT next_cursor FROM platform.reconciliation_cursor WHERE run_id=?", String.class, first))
                .isEqualTo("first");
        page(false, "second", -40);
        advance(first, 1);
        replay.replay(scope, "first");
        replay.replay(scope, "second");
        finish(first, evidence(60, scope, until));
        assertThat(check(first, 5)).isEqualTo("PASS");
        UUID later = run();
        var facts = List.of(
                new BalanceTransactionFact(
                        "first",
                        "adjustment",
                        100,
                        0,
                        100,
                        "USD",
                        "PAYMENTS",
                        "src",
                        "unknown",
                        "pending",
                        from.plusSeconds(1),
                        until,
                        List.of(),
                        null),
                new BalanceTransactionFact(
                        "second",
                        "adjustment",
                        -40,
                        0,
                        -40,
                        "USD",
                        "PAYMENTS",
                        "src",
                        "unknown",
                        "pending",
                        from.plusSeconds(1),
                        until,
                        List.of(),
                        null),
                new BalanceTransactionFact(
                        "late",
                        "adjustment",
                        -10,
                        0,
                        -10,
                        "USD",
                        "PAYMENTS",
                        "src",
                        "unknown",
                        "pending",
                        from.plusSeconds(1),
                        until,
                        List.of(),
                        null));
        payoutPage(facts);
        advance(later, 1);
        for (var bt : List.of("first", "second", "late")) replay.replay(scope, bt);
        finish(later, evidence(50, scope, until));
        assertThat(check(later, 5)).isEqualTo("PASS");
        assertThat(check(first, 5)).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(3);
    }

    @Test
    void pendingAvailabilityRefreshDoesNotAlterFrozenInputOrMoney() throws Exception {
        UUID first = run();
        page(false, "available", 100);
        advance(first, 1);
        replay.replay(scope, "available");
        finish(first, evidence(100, scope, until));
        var refreshed = new BalanceTransactionFact(
                "available",
                "adjustment",
                100,
                0,
                100,
                "USD",
                "PAYMENTS",
                "src",
                "unknown",
                "available",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
        BalanceTransactionPort port = new BalanceTransactionPort() {
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
                throw new UnsupportedOperationException();
            }

            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
                return new ProviderCallResult<>(
                        ProviderCallClassification.SUCCESS,
                        refreshed,
                        new ProviderEvidence("refresh", null, until, until, 200, null, "b".repeat(64), "v1"));
            }
        };
        new FinancialImportService(jdbc, tx, new ObjectMapper().findAndRegisterModules(), port, clock)
                .refreshPending(scope, "available");
        assertThat(jdbc.queryForObject("""
                        SELECT input_versions->'balanceTransactions'->0->>'provider_status'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=5""", String.class, first)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("""
                        SELECT provider_status
                        FROM platform.balance_transaction
                        WHERE bt_id='available'""", String.class)).isEqualTo("available");
        assertThat(jdbc.queryForObject(
                        "SELECT balance_minor FROM platform.balance_projection WHERE ledger_account_id=?",
                        Long.class,
                        id(20)))
                .isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal", Long.class))
                .isEqualTo(1);
    }

    @Test
    void historicalRefundDebitWithLaterFailedStatusIsIncompleteRatherThanFalseMismatch() throws Exception {
        paymentFixture();
        admin.update("""
                INSERT INTO platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,
                    source_domain)
                VALUES (?,?,?,'USD','SALES_RETURNS','INTERNAL')""", id(23), id(1), id(2));
        admin.update("""
                INSERT INTO platform.refund(id,organization_id,environment_id,application_id,provider_account_id,
                    currency,payment_id,amount_minor,request_fingerprint,source,provider_charge_id,
                    provider_refund_id,balance_transaction_id,failure_balance_transaction_id,refund_status,
                    last_provider_confirmed_at)
                VALUES (?,?,?,?,?,'USD',?,100,'fixture','EXTERNAL','ch_refund','re_cutoff','refund_debit',
                    'refund_return','FAILED',?)""", id(99), id(1), id(2), id(80), id(4), id(82), java.sql.Timestamp.from(until.plusSeconds(60)));
        admin.update("""
                INSERT INTO platform.fee_model(organization_id,environment_id,provider_account_id,livemode,currency,
                    balance_domain,business_type,model_version,effective_from,effective_until,basis_points,
                    flat_minor,rounding)
                VALUES (?,?,?,false,'USD','PAYMENTS','refund','fee-zero',?,?,0,0,'DOWN')""", id(1), id(2), id(4), java.sql.Timestamp.from(from), java.sql.Timestamp.from(until));
        UUID run = run();
        payoutPage(List.of(new BalanceTransactionFact(
                "refund_debit",
                "refund",
                -100,
                0,
                -100,
                "USD",
                "PAYMENTS",
                "re_cutoff",
                "refund",
                "available",
                from.plusSeconds(1),
                until,
                List.of(),
                null)));
        advance(run, 1);
        RefundEvidencePort trusted = (sc, ref) -> Optional.of(new RefundEvidencePort.Evidence(
                id(82), "re_cutoff", "refund_debit", 100, "USD", true, "refund_return", id(99)));
        var mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(new FinancialReplayService(jdbc, tx, mapper, new LedgerPostingService(jdbc, mapper), trusted)
                        .replay(scope, "refund_debit"))
                .isEqualTo("POSTED");
        finish(run, evidence(-100, scope, until));
        assertThat(check(run, 4)).isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='SOURCE_OBJECT_MISMATCH'""", Long.class, run)).isZero();
    }

    @Test
    void balancedPayoutFeeEntryTamperFailsIndependentPayoutCheck() throws Exception {
        var payouts = configurePayoutChecks();
        payouts.accept(
                scope,
                new PayoutFact("po_test", "failed", 5700, "USD", "txn_out", "txn_return", true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(
                List.of(payoutBt("txn_out", "payout", -5700, 12), payoutBt("txn_return", "payout_failure", 5700, -12)));
        advance(run, 1);
        replay.replay(scope, "txn_out");
        replay.replay(scope, "txn_return");
        admin.execute("ALTER TABLE platform.journal_entry DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.journal_entry e
                    SET amount_minor=amount_minor+1
                    FROM platform.ledger_account a
                    WHERE a.id=e.ledger_account_id
                        AND a.account_purpose IN('PSP_FUNDS','PSP_FEE_EXPENSE')
                        AND e.journal_id=(SELECT journal_id FROM platform.balance_transaction WHERE bt_id='txn_out')""");
        } finally {
            admin.execute("ALTER TABLE platform.journal_entry ENABLE TRIGGER USER");
        }
        finish(run, evidence(0, scope, until));
        assertThat(check(run, 1)).isEqualTo("PASS");
        assertThat(check(run, 6)).isEqualTo("FAIL");
    }

    @Test
    void providerSourceCannotBelongToTwoBalanceDomains() throws Exception {
        UUID run = run();
        page(false, "domain_source", 100);
        advance(run, 1);
        replay.replay(scope, "domain_source");
        var mapper = new ObjectMapper();
        new LedgerPostingService(jdbc, mapper)
                .post(
                        new LedgerScope(id(1), id(2), "USD", id(4)),
                        new PostingCommand(
                                "cross-domain-fixture",
                                "fixture",
                                "PROVIDER",
                                "domain_source",
                                mapper.createObjectNode().put("balanceDomain", "ISSUING"),
                                List.of(
                                        new PostingLine(id(20), Side.DEBIT, 1),
                                        new PostingLine(id(22), Side.CREDIT, 1)),
                                null));
        finish(run, evidence(100, scope, until));
        assertThat(check(run, 7)).isEqualTo("FAIL");
    }

    @Test
    void missingProvisionedLedgerScopeStillPersistsSevenIncompleteResults() throws Exception {
        admin.update("DELETE FROM platform.ledger_account WHERE organization_id=?", id(1));
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        assertThatCode(() -> finish(run, evidence(0, scope, until))).doesNotThrowAnyException();
        assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
        assertThat(check(run, 7)).isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.reconciliation_check WHERE run_id=?", Long.class, run))
                .isEqualTo(7);
    }

    @Test
    void scopedQueryAndImmutableChildrenRejectCrossScopeAndAdminMutation() throws Exception {
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        finish(run, evidence(0, scope, until));
        assertThatThrownBy(() -> service.getClass()
                        .getMethod("query", ProviderScope.class, UUID.class)
                        .invoke(service, new ProviderScope(id(1), id(2), id(77), false), run))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.update("""
                        UPDATE platform.reconciliation_evidence
                        SET evidence_version='forged'
                        WHERE run_id=?""", run))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> admin.update("DELETE FROM platform.reconciliation_check WHERE run_id=?", run))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO platform.reconciliation_cursor(run_id,organization_id,environment_id,
                            provider_account_id,livemode,currency)
                        VALUES (?,?,?,?,false,'USD')""", UUID.randomUUID(), id(1), id(2), id(77)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        for (String function : List.of("pr15_run_guard()", "pr15_cursor_guard()", "pr15_child_guard()"))
            assertThat(admin.queryForObject(
                            "SELECT has_function_privilege('payments_app',?,'EXECUTE')",
                            Boolean.class,
                            "platform." + function))
                    .isFalse();
    }

    @Test
    void missingIndependentClosingCannotHideDefiniteLocalBtMappingViolation() throws Exception {
        UUID run = run();
        page(false, "mapping", 100);
        advance(run, 1);
        replay.replay(scope, "mapping");
        admin.execute("ALTER TABLE platform.journal_entry DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.journal_entry
                    SET amount_minor=101
                    WHERE journal_id=(SELECT journal_id FROM platform.balance_transaction WHERE bt_id='mapping')""");
        } finally {
            admin.execute("ALTER TABLE platform.journal_entry ENABLE TRIGGER USER");
        }
        admin.execute("ALTER TABLE platform.balance_projection DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.balance_projection
                    SET balance_minor=CASE WHEN ledger_account_id=? THEN 101 ELSE -101 END WHERE ledger_account_id IN
                        (?,?)""", id(20), id(20), id(22));
        } finally {
            admin.execute("ALTER TABLE platform.balance_projection ENABLE TRIGGER USER");
        }
        finish(run, null);
        assertThat(check(run, 1)).isEqualTo("PASS");
        assertThat(check(run, 7)).isEqualTo("PASS");
        assertThat(check(run, 4)).isEqualTo("FAIL");
        assertThat(check(run, 5)).isEqualTo("INCOMPLETE");
    }

    @Test
    void exactBusinessWindowStartIsDeltaUnderZeroOpeningPolicy() throws Exception {
        UUID run = run();
        payoutPage(List.of(new BalanceTransactionFact(
                "start",
                "adjustment",
                100,
                0,
                100,
                "USD",
                "PAYMENTS",
                "src",
                "unknown",
                "pending",
                from,
                until,
                List.of(),
                null)));
        advance(run, 1);
        replay.replay(scope, "start");
        finish(run, evidence(100, scope, until));
        assertThat(check(run, 5)).isEqualTo("PASS");
    }

    boolean awaitBlocked(String lockType) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            if (admin.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype=?", Long.class, lockType)
                    > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void racingEvidenceInsertCannotAppendAfterFrozenSnapshot() throws Exception {
        UUID run = run();
        page(false, null, 0);
        advance(run, 1);
        admin.execute("""
                CREATE FUNCTION platform.pr15_test_pause() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET
                    search_path=pg_catalog,platform AS $$ BEGIN IF NEW.check_number=1 THEN PERFORM
                    pg_advisory_xact_lock(15154); END IF; RETURN NEW; END $$""");
        admin.execute("""
                CREATE TRIGGER zz_pr15_test_pause BEFORE INSERT ON platform.reconciliation_check FOR EACH ROW EXECUTE FUNCTION
                    platform.pr15_test_pause()""");
        try (var gate = admin.getDataSource().getConnection();
                var statement = gate.createStatement();
                var pool = Executors.newFixedThreadPool(2)) {
            statement.execute("SELECT pg_advisory_lock(15154)");
            try {
                var closing = pool.submit(() -> {
                    finish(run, evidence(0, scope, until));
                    return null;
                });
                assertThat(awaitBlocked("advisory")).isTrue();
                var inserting = pool.submit(() -> jdbc.update("""
                        INSERT INTO platform.reconciliation_evidence(run_id,organization_id,environment_id,
                            provider_account_id,livemode,currency,evidence_kind,source,evidence_version,digest,payload)
                        VALUES (?,?,?,?,false,'USD','INDEPENDENT_FIXTURE','racing-fixture','race-v1',?,'{}')""", run, id(1), id(2), id(4), "d".repeat(64)));
                assertThat(awaitBlocked("transactionid")).isTrue();
                statement.execute("SELECT pg_advisory_unlock(15154)");
                closing.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> inserting.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(jdbc.queryForObject("""
                                SELECT count(*)
                                FROM platform.reconciliation_evidence
                                WHERE run_id=? AND source='racing-fixture'""", Long.class, run)).isZero();
            } finally {
                statement.execute("SELECT pg_advisory_unlock(15154)");
            }
        } finally {
            admin.execute("DROP TRIGGER zz_pr15_test_pause ON platform.reconciliation_check");
            admin.execute("DROP FUNCTION platform.pr15_test_pause()");
        }
    }

    void feeModel(String type, String direction) {
        assertThat(admin.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema='platform' AND table_name='fee_model' AND column_name='fee_direction'""", Long.class)).isEqualTo(1);
        admin.update(
                """
                INSERT INTO platform.fee_model(organization_id,environment_id,provider_account_id,livemode,currency,
                    balance_domain,business_type,model_version,effective_from,effective_until,basis_points,
                    flat_minor,rounding,fee_direction)
                VALUES (?,?,?,false,'USD','PAYMENTS',?,?,?, ?,0,12,'DOWN',?)""",
                id(1),
                id(2),
                id(4),
                type,
                type + "-v1",
                java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(until),
                direction);
    }

    @Test
    void payoutExpenseModelMatchesPositiveFeeOnNegativePrincipal() throws Exception {
        feeModel("payout", "EXPENSE");
        var payouts = configurePayoutChecks();
        payouts.accept(
                scope,
                new PayoutFact("po_test", "pending", 5700, "USD", "txn_out", null, true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(List.of(payoutBt("txn_out", "payout", -5700, 12)));
        advance(run, 1);
        replay.replay(scope, "txn_out");
        finish(run, evidence(-5712, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='FEE_MODEL_MISMATCH'""", Long.class, run)).isZero();
        assertThat(check(run, 4)).isEqualTo("PASS");
    }

    @Test
    void payoutReturnRebateModelMatchesNegativeFeeOnPositivePrincipal() throws Exception {
        feeModel("payout", "EXPENSE");
        feeModel("payout_failure", "REBATE");
        var payouts = configurePayoutChecks();
        payouts.accept(
                scope,
                new PayoutFact("po_test", "failed", 5700, "USD", "txn_out", "txn_return", true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(
                List.of(payoutBt("txn_out", "payout", -5700, 12), payoutBt("txn_return", "payout_failure", 5700, -12)));
        advance(run, 1);
        replay.replay(scope, "txn_out");
        replay.replay(scope, "txn_return");
        finish(run, evidence(0, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_exception
                        WHERE run_id=? AND reason='FEE_MODEL_MISMATCH'""", Long.class, run)).isZero();
        assertThat(check(run, 4)).isEqualTo("PASS");
    }

    @Test
    void definiteOpeningMismatchFailsEvenWithUnrelatedUnresolvedRefund() throws Exception {
        UUID run = run();
        payoutPage(List.of(
                new BalanceTransactionFact(
                        "known",
                        "adjustment",
                        100,
                        0,
                        100,
                        "USD",
                        "PAYMENTS",
                        "src",
                        "unknown",
                        "pending",
                        from.plusSeconds(1),
                        until,
                        List.of(),
                        null),
                new BalanceTransactionFact(
                        "unresolved",
                        "refund",
                        -10,
                        0,
                        -10,
                        "USD",
                        "PAYMENTS",
                        "re_missing",
                        "refund",
                        "pending",
                        from.plusSeconds(2),
                        until,
                        List.of(),
                        null)));
        advance(run, 1);
        replay.replay(scope, "known");
        assertThat(replay.replay(scope, "unresolved")).isEqualTo("PENDING_EVIDENCE");
        var independent = new IndependentBalanceEvidence(
                scope,
                "USD",
                "PAYMENTS",
                from,
                until,
                50,
                140,
                "independent-provider-fixture",
                "fixture-v1",
                "c".repeat(64),
                "v1",
                IndependentBalanceEvidence.Kind.INDEPENDENT_FIXTURE,
                false);
        finish(run, independent);
        assertThat(check(run, 5)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("SELECT state FROM platform.reconciliation_run WHERE id=?", String.class, run))
                .isEqualTo("INCOMPLETE");
    }

    @Test
    void definiteFailuresCannotHideUnclassifiedInputsInOverallCompleteness() throws Exception {
        admin.update("""
                INSERT INTO platform.fee_model(organization_id,environment_id,provider_account_id,livemode,currency,
                    balance_domain,business_type,model_version,effective_from,effective_until,basis_points,
                    flat_minor,rounding)
                VALUES (?,?,?,false,'USD','PAYMENTS','adjustment','zero-v1',?,?,0,0,'DOWN')""", id(1), id(2), id(4), java.sql.Timestamp.from(from), java.sql.Timestamp.from(until));
        UUID run = run();
        page(false, "unclassified_mapping", 100);
        advance(run, 1);
        replay.replay(scope, "unclassified_mapping");
        admin.execute("ALTER TABLE platform.journal_entry DISABLE TRIGGER USER");
        try {
            admin.update("""
                    UPDATE platform.journal_entry
                    SET amount_minor=101
                    WHERE journal_id=(SELECT journal_id FROM platform.balance_transaction WHERE
                        bt_id='unclassified_mapping')""");
        } finally {
            admin.execute("ALTER TABLE platform.journal_entry ENABLE TRIGGER USER");
        }
        finish(run, evidence(100, scope, until));
        assertThat(check(run, 4)).isEqualTo("FAIL");
        assertThat(check(run, 5)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("""
                        SELECT completeness->>'unclassified'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=4""", Long.class, run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND outcome='INCOMPLETE'""", Long.class, run)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM platform.reconciliation_run WHERE id=?", String.class, run))
                .isEqualTo("INCOMPLETE");
    }

    @Test
    void accountWidePagesRetainCoverageWithoutLeakingOtherMoneyScopes() throws Exception {
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('EUR',2)");
        admin.update("""
                INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                VALUES (?,?,'EUR')""", id(1), id(2));
        feeModel("payout", "EXPENSE");
        var payouts = configurePayoutChecks();
        payouts.accept(
                scope,
                new PayoutFact("po_test", "pending", 5700, "USD", "txn_out", null, true, until, null),
                new ProviderEvidence("req", null, from, from, 200, null, "a".repeat(64), "v1"));
        UUID run = run();
        payoutPage(List.of(
                payoutBt("txn_out", "payout", -5700, 12),
                new BalanceTransactionFact(
                        "eur_other",
                        "adjustment",
                        200,
                        0,
                        200,
                        "EUR",
                        "PAYMENTS",
                        "other",
                        "unknown",
                        "available",
                        from.plusSeconds(2),
                        until,
                        List.of(),
                        null),
                new BalanceTransactionFact(
                        "issuing_other",
                        "adjustment",
                        300,
                        0,
                        300,
                        "USD",
                        "ISSUING",
                        "other",
                        "unknown",
                        "available",
                        from.plusSeconds(3),
                        until,
                        List.of(),
                        null)));
        advance(run, 1);
        assertThat(replay.replay(scope, "txn_out")).isEqualTo("POSTED");
        finish(run, evidence(-5712, scope, until));
        assertThat(jdbc.queryForObject("""
                        SELECT jsonb_array_length(input_versions->'pages'->0->'btIds')
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=4""", Long.class, run)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                        SELECT completeness->>'pagesComplete'
                        FROM platform.reconciliation_check
                        WHERE run_id=? AND check_number=4""", Boolean.class, run)).isTrue();
        assertThat(check(run, 5)).isEqualTo("PASS");
        assertThat(check(run, 4)).isEqualTo("PASS");
    }
}
