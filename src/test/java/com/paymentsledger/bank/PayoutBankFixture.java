package com.paymentsledger.bank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.payout.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.testsupport.SqlTrace;
import java.nio.file.Path;
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

/** Shared PostgreSQL fixture for payout and simulated-bank tests; every test also writes a SQL trace. */
@Testcontainers
abstract class PayoutBankFixture {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static UUID id(int n) {
        return new UUID(0, n);
    }

    final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);
    final Instant from = Instant.parse("2026-03-01T00:00:00Z"), until = from.plusSeconds(86400);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    /** Statements issued through the application role, written per test to compare runs across refactors. */
    final SqlTrace trace = new SqlTrace();

    JdbcTemplate jdbc, admin;
    DataSourceTransactionManager tx;
    PayoutService payouts;
    BankStatementImporter banks;
    BankMatcher matcher;
    FinancialImportService importer;
    FinancialReplayService replay;
    Reads reads;
    LedgerPostingService ledger;

    @BeforeEach
    void setup() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas("platform")
                .load()
                .migrate();
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        var ds = trace.wrap(new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only"));
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'payout')", id(1));
        admin.update("""
                INSERT INTO platform.environment_config(id,organization_id,code,livemode)
                VALUES (?,?,'test',false)""", id(2), id(1));
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','payout',false)""", id(4), id(1), id(2));
        admin.update("""
                INSERT INTO platform.currency_config(code,minor_unit_exponent)
                VALUES ('USD',2),('JPY',0)""");
        admin.update("""
                INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)
                VALUES (?,?,'USD'),(?,?,'JPY')""", id(1), id(2), id(1), id(2));
        admin.update("""
                INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,
                    opening_mode)
                VALUES (?,?,?,false,'ZERO')""", id(1), id(2), id(4));
        ledger = new LedgerPostingService(jdbc, json);
        payouts = new PayoutService(jdbc, tx, json, ledger);
        banks = new BankStatementImporter(jdbc, tx, json);
        matcher = new BankMatcher(jdbc, tx, json, ledger, payouts);
        reads = new Reads();
        importer =
                new FinancialImportService(jdbc, tx, json, reads, Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC));
        replay = new FinancialReplayService(
                jdbc,
                tx,
                json,
                ledger,
                RefundEvidencePort.unavailable(),
                com.paymentsledger.revenueshare.RevenueShareService.disabled(),
                null,
                payouts);
    }

    @AfterEach
    void writeSqlTrace(TestInfo test) {
        trace.write(Path.of(
                "target/failsafe-reports/sql-trace",
                getClass().getSimpleName(),
                test.getTestMethod().orElseThrow().getName() + ".txt"));
    }

    ProviderEvidence evidence() {
        return new ProviderEvidence("request", null, from, until, 200, null, "a".repeat(64), "v1");
    }

    PayoutFact fact(String id, String status, String out, String returned) {
        return new PayoutFact(id, status, 5700, "usd", out, returned, false, from.plusSeconds(100), null);
    }

    UUID accept(String id, String status, String out, String returned) {
        return payouts.accept(scope, fact(id, status, out, returned), evidence());
    }

    BalanceTransactionFact bt(String id, String po, long amount, long fee) {
        return new BalanceTransactionFact(
                id,
                amount < 0 ? "payout" : "payout_failure",
                amount,
                fee,
                amount - fee,
                "usd",
                "PAYMENTS",
                po,
                "payout",
                "available",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
    }

    void ingest(BalanceTransactionFact fact) {
        reads.pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(fact), false, null),
                evidence()));
        assertThat(importer.importPage(importer.createWindow(scope, from, until, "BACKFILL")))
                .isEqualTo("COMPLETE");
    }

    UUID outgoing(String po) {
        UUID local = accept(po, "paid", "bt_" + po, null);
        ingest(bt("bt_" + po, po, -5700, 0));
        assertThat(replay.replay(scope, "bt_" + po)).isEqualTo("POSTED");
        return local;
    }

    long balance(String purpose) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END),0)
                FROM platform.journal_entry e
                JOIN platform.ledger_account a ON a.id=e.ledger_account_id
                WHERE a.account_purpose=?""", Long.class, purpose);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM platform." + table, Long.class);
    }

    JsonNode statement(String fixture, String line, String kind, long amount, long fee, String... refs) {
        var s = json.createObjectNode();
        s.put("schemaVersion", 1)
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("fixtureId", fixture)
                .put("bankAccountRef", "sim-bank-1")
                .put("currency", "USD")
                .put("coverageFrom", from.toString())
                .put("coverageUntil", until.toString())
                .put("complete", true);
        var l = s.putArray("lines").addObject();
        l.put("lineId", line)
                .put("kind", kind)
                .put("bookedAt", from.plusSeconds(200).toString())
                .put("principalMinor", amount)
                .put("feeMinor", fee);
        var a = l.putArray("payoutReferences");
        for (String ref : refs) a.add(ref);
        return s;
    }

    UUID line(JsonNode data) {
        UUID statement = banks.importStatement(scope, data);
        return jdbc.queryForObject(
                "SELECT id FROM platform.bank_statement_line WHERE statement_id=?", UUID.class, statement);
    }

    class Reads implements BalanceTransactionPort, PayoutPort {
        final Queue<ProviderCallResult<BalanceTransactionPage>> pages = new ConcurrentLinkedQueue<>();
        PayoutFact queryFact;

        public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return pages.remove();
        }

        public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
            throw new IllegalStateException("Not used");
        }

        public ProviderCallResult<PayoutFact> getPayout(ProviderScope s, String id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new ProviderCallResult<>(ProviderCallClassification.SUCCESS, queryFact, evidence());
        }

        public ProviderCallResult<Page<PayoutFact>> listPayouts(PageQuery q) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new ProviderCallResult<>(
                    ProviderCallClassification.SUCCESS, new Page<>(List.of(queryFact), false, null), evidence());
        }
    }
}
