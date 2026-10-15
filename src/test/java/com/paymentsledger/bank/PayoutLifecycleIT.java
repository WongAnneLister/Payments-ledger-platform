package com.paymentsledger.bank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.ledger.*;
import com.paymentsledger.payout.*;
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
class PayoutLifecycleIT {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

    static UUID id(int n) {
        return new UUID(0, n);
    }

    final ProviderScope scope = new ProviderScope(id(1), id(2), id(4), false);
    final Instant from = Instant.parse("2026-03-01T00:00:00Z"), until = from.plusSeconds(86400);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
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
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), "payments_app", "pr02_test_only");
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

    @Test
    void paidAndOutgoingRemainTransitUntilSimulatedBankEvidence() {
        UUID po = outgoing("po_paid");
        assertThat(balance("BANK_CASH")).isZero();
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(5700);
        assertThat(matcher.evidenceState(scope, po)).isEqualTo("WAITING_EVIDENCE");
        UUID line = line(statement("a", "line-a", "CREDIT", 5700, 0, "po_paid"));
        assertThat(matcher.match(scope, po, line, 5700).state()).isEqualTo("MATCHED");
        assertThat(balance("BANK_CASH")).isEqualTo(5700);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isZero();
        assertThat(new BalanceRebuildService(jdbc, Clock.systemUTC())
                        .rebuild(
                                new LedgerScope(id(1), id(2), "USD", null),
                                BalanceRebuildService.Mode.OPENING_JOURNAL,
                                null)
                        .matches())
                .isTrue();
    }

    @Test
    void importerAndMatchAreIdempotentButChangedIdentityRejects() {
        UUID po = outgoing("po_duplicate");
        var s = statement("a", "line-a", "CREDIT", 5700, 0, "po_duplicate");
        UUID first = banks.importStatement(scope, s);
        assertThat(banks.importStatement(scope, s)).isEqualTo(first);
        UUID l = jdbc.queryForObject("SELECT id FROM platform.bank_statement_line", UUID.class);
        UUID match = matcher.match(scope, po, l, 5700).matchId();
        assertThat(matcher.match(scope, po, l, 5700).matchId()).isEqualTo(match);
        assertThatThrownBy(() -> matcher.match(scope, po, l, 1)).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) s.path("lines").get(0)).put("principalMinor", 5701);
        assertThatThrownBy(() -> banks.importStatement(scope, s)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bank_statement")).isEqualTo(1);
        assertThat(count("payout_bank_match")).isEqualTo(1);
    }

    @Test
    void splitLineFeesAreExactAndDoNotConsumeExtraPrincipal() {
        UUID a = outgoing("po_a"), b = outgoing("po_b");
        UUID l = line(statement("split", "split-line", "CREDIT", 5700, 101, "po_a", "po_b"));
        assertThat(matcher.match(scope, a, l, 2000).state()).isEqualTo("PARTIAL");
        matcher.match(scope, b, l, 3700);
        assertThat(balance("BANK_CASH")).isEqualTo(5599);
        assertThat(balance("BANK_FEE_EXPENSE")).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT sum(bank_confirmed_minor) FROM platform.payout", Long.class))
                .isEqualTo(5700);
        assertThat(jdbc.queryForObject("SELECT allocated_minor FROM platform.bank_statement_line", Long.class))
                .isEqualTo(5700);
        assertThatThrownBy(() -> matcher.match(scope, b, l, 5700)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bankReceiptThenReturnUsesSuspenseUntilExplicitBankClawback() {
        UUID po = outgoing("po_overlap");
        matcher.match(scope, po, line(statement("a", "credit", "CREDIT", 5700, 0, "po_overlap")), 5700);
        accept("po_overlap", "failed", "bt_po_overlap", "bt_return");
        ingest(bt("bt_return", "po_overlap", 5700, 0));
        assertThat(replay.replay(scope, "bt_return")).isEqualTo("POSTED");
        assertThat(balance("BANK_CASH")).isEqualTo(5700);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isZero();
        assertThat(balance("SUSPENSE")).isEqualTo(-5700);
        var d = payouts.lookup(scope, "po_overlap");
        assertThat(d.get("bank_match_state")).isEqualTo("CONFLICT");
        assertThat(((Number) d.get("returned_minor")).longValue()).isZero();
        UUID l = line(statement("b", "clawback", "RETURN", 5700, 0, "po_overlap"));
        matcher.match(scope, po, l, 5700);
        matcher.match(scope, po, l, 5700);
        assertThat(balance("BANK_CASH")).isZero();
        assertThat(balance("SUSPENSE")).isZero();
        assertThat(((Number) payouts.lookup(scope, "po_overlap").get("returned_minor")).longValue())
                .isEqualTo(5700);
    }

    @Test
    void noBankBeforeReturnRestoresPspWithoutBankCash() {
        outgoing("po_failed");
        accept("po_failed", "failed", "bt_po_failed", "bt_return");
        ingest(bt("bt_return", "po_failed", 5700, 0));
        replay.replay(scope, "bt_return");
        assertThat(balance("PSP_FUNDS")).isZero();
        assertThat(balance("BANK_CASH")).isZero();
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isZero();
    }

    @Test
    void partialCompleteEvidenceCanShowShortButIncompleteEvidenceWaits() {
        UUID po = outgoing("po_short");
        var s = statement("a", "short", "CREDIT", 5000, 0, "po_short");
        ((com.fasterxml.jackson.databind.node.ObjectNode) s).put("complete", false);
        UUID l = line(s);
        matcher.match(scope, po, l, 5000);
        assertThat(matcher.evidenceState(scope, po)).isEqualTo("WAITING_EVIDENCE");
        UUID other = outgoing("po_complete");
        matcher.match(scope, other, line(statement("b", "short-complete", "CREDIT", 5000, 0, "po_complete")), 5000);
        assertThat(matcher.evidenceState(scope, other)).isEqualTo("SHORT_RECEIPT");
    }

    @Test
    void wrongReferenceCurrencyDateAndNonSimulatedSourceHaveNoFinancialEffects() {
        UUID po = outgoing("po_checks");
        UUID wrong = line(statement("a", "wrong", "CREDIT", 5700, 0, "po_other"));
        long journals = count("journal");
        assertThatThrownBy(() -> matcher.match(scope, po, wrong, 5700)).isInstanceOf(IllegalArgumentException.class);
        var currency = statement("b", "currency", "CREDIT", 5700, 0, "po_checks");
        ((com.fasterxml.jackson.databind.node.ObjectNode) currency).put("currency", "JPY");
        UUID yen = line(currency);
        assertThatThrownBy(() -> matcher.match(scope, po, yen, 5700)).isInstanceOf(IllegalArgumentException.class);
        var invalid = statement("c", "invalid", "CREDIT", 5700, 0, "po_checks");
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("evidenceType", "REAL_BANK");
        assertThatThrownBy(() -> banks.importStatement(scope, invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(count("payout_bank_match")).isZero();
    }

    @Test
    void separatePostgresConnectionsRaceForSingleBankLineWithoutOverAllocation() throws Exception {
        UUID a = outgoing("po_a"), b = outgoing("po_b");
        UUID l = line(statement("race", "race-line", "CREDIT", 5700, 0, "po_a", "po_b"));
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (UUID p : List.of(a, b))
                jobs.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        matcher.match(scope, p, l, 5700);
                        return true;
                    } catch (IllegalArgumentException rejected) {
                        return false;
                    }
                }));
            assertThat(jobs.get(0).get(30, TimeUnit.SECONDS) ^ jobs.get(1).get(30, TimeUnit.SECONDS))
                    .isTrue();
        }
        assertThat(balance("BANK_CASH")).isEqualTo(5700);
        assertThat(count("payout_bank_match")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT allocated_minor FROM platform.bank_statement_line", Long.class))
                .isEqualTo(5700);
    }

    @Test
    void matchInsertFailureRollsBackJournalProjectionAndBothCounters() {
        UUID po = outgoing("po_rollback"), l = line(statement("a", "a", "CREDIT", 5700, 0, "po_rollback"));
        long journals = count("journal");
        admin.execute("""
                CREATE FUNCTION platform.test_match_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE
                    EXCEPTION 'fixture failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER test_match_fail BEFORE INSERT ON platform.payout_bank_match FOR EACH ROW EXECUTE
                    FUNCTION platform.test_match_fail()""");
        try {
            assertThatThrownBy(() -> matcher.match(scope, po, l, 5700)).isInstanceOf(RuntimeException.class);
        } finally {
            admin.execute("DROP TRIGGER test_match_fail ON platform.payout_bank_match");
            admin.execute("DROP FUNCTION platform.test_match_fail()");
        }
        assertThat(count("journal")).isEqualTo(journals);
        assertThat(balance("BANK_CASH")).isZero();
        assertThat(((Number) payouts.lookup(scope, "po_rollback").get("transit_minor")).longValue())
                .isEqualTo(5700);
        assertThat(jdbc.queryForObject("SELECT allocated_minor FROM platform.bank_statement_line", Long.class))
                .isZero();
    }

    @Test
    void payoutReadWindowPersistsQueryFactsAndMissingSourceRecoveryRunsOutsideTransaction() {
        reads.queryFact = fact("po_read", "paid", "bt_read", null);
        var query = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        UUID run = query.createWindow(scope, from, until);
        assertThat(query.importPage(run)).isEqualTo("COMPLETE");
        assertThat(payouts.lookup(scope, "po_read").get("provider_status")).isEqualTo("paid");
        assertThat(count("payout_read_page")).isEqualTo(1);
        assertThat(balance("BANK_CASH")).isZero();
        ingest(bt("bt_read", "po_read", -5700, 0));
        assertThat(replay.replay(scope, "bt_read")).isEqualTo("POSTED");
        reads.queryFact = fact("po_missing", "paid", "bt_missing", null);
        ingest(bt("bt_missing", "po_missing", -5700, 0));
        assertThat(replay.replay(scope, "bt_missing")).isEqualTo("PENDING_EVIDENCE");
        new PayoutSourceRecovery(reads, payouts, jdbc, tx, json).resolve(scope, "po_missing");
        assertThat(replay.replay(scope, "bt_missing")).isEqualTo("POSTED");
    }

    @Test
    void automaticMembershipUsesProviderFilteredPageAndManualNeverGuesses() {
        UUID manual = outgoing("po_manual");
        var query = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        assertThat(query.createMembershipWindow(scope, manual, from, until)).isNull();
        assertThat(count("payout_membership")).isZero();
        UUID automatic = payouts.accept(
                scope,
                new PayoutFact("po_auto", "paid", 5700, "usd", "bt_auto", null, true, from.plusSeconds(100), null),
                evidence());
        var member = new BalanceTransactionFact(
                "bt_member",
                "charge",
                6000,
                300,
                5700,
                "usd",
                "PAYMENTS",
                "ch_1",
                "charge",
                "available",
                from.plusSeconds(1),
                until,
                List.of(),
                null);
        reads.pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(member), false, null),
                evidence()));
        UUID run = query.createMembershipWindow(scope, automatic, from, until);
        assertThat(query.importPage(run)).isEqualTo("COMPLETE");
        assertThat(count("payout_membership")).isEqualTo(1);
        assertThat(count("balance_transaction"))
                .isEqualTo(1); // only manual own payout BT, membership never double-posts
    }

    @Test
    void payoutWebhookAcceptAndAckAreAtomicAndPaidCreatesNoBankCash() throws Exception {
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_test'
                WHERE id=?""", id(4));
        var inbox = new com.paymentsledger.webhook.WebhookInboxRepository(
                jdbc, tx, json, Clock.fixed(until, ZoneOffset.UTC));
        var root = json.createObjectNode();
        root.put("id", "evt_payout")
                .put("type", "payout.paid")
                .put("created", from.getEpochSecond())
                .put("livemode", false)
                .put("api_version", "v1");
        root.putObject("data")
                .putObject("object")
                .put("id", "po_webhook")
                .put("object", "payout")
                .put("status", "paid")
                .put("amount", 5700)
                .put("currency", "usd")
                .put("automatic", false)
                .put("arrival_date", from.plusSeconds(100).getEpochSecond())
                .put("balance_transaction", "bt_hook");
        var verified = new VerifiedWebhook("evt_payout", "payout.paid", "po_webhook", from, false);
        inbox.receive(
                scope,
                "acct_test",
                root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new ProviderCallResult<>(ProviderCallClassification.SUCCESS, verified, evidence()));
        var claim = inbox.claimDue(1).getFirst();
        var handler = new com.paymentsledger.webhook.PayoutWebhookHandler(inbox, payouts);
        admin.execute("""
                CREATE FUNCTION platform.test_ack_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF
                    NEW.status='DONE' THEN RAISE EXCEPTION 'fixture ack'; END IF; RETURN NEW; END $$""");
        admin.execute("""
                CREATE TRIGGER test_ack_fail BEFORE
                UPDATE ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION platform.test_ack_fail()""");
        try {
            assertThatThrownBy(() -> handler.process(claim, root.path("data").path("object")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            admin.execute("DROP TRIGGER test_ack_fail ON platform.webhook_inbox");
            admin.execute("DROP FUNCTION platform.test_ack_fail()");
        }
        assertThat(count("payout")).isZero();
        handler.process(claim, root.path("data").path("object"));
        assertThat(count("payout")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox", String.class))
                .isEqualTo("DONE");
        assertThat(balance("BANK_CASH")).isZero();
    }

    @Test
    void returnBeforeOutgoingWaitsThenReplaysExactlyOnce() {
        UUID po = accept("po_order", "failed", "bt_out", "bt_return");
        ingest(bt("bt_return", "po_order", 5700, 0));
        assertThat(replay.replay(scope, "bt_return")).isEqualTo("PENDING_EVIDENCE");
        assertThat(count("journal")).isZero();
        ingest(bt("bt_out", "po_order", -5700, 0));
        assertThat(replay.replay(scope, "bt_out")).isEqualTo("POSTED");
        replay.replay(scope, "bt_return");
        replay.replay(scope, "bt_return");
        assertThat(count("journal")).isEqualTo(2);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isZero();
    }

    @Test
    void receiptAfterFullReturnKeepsConflictEvidenceWithoutDoubleAllocation() {
        UUID po = outgoing("po_reverse");
        accept("po_reverse", "failed", "bt_po_reverse", "bt_return");
        ingest(bt("bt_return", "po_reverse", 5700, 0));
        replay.replay(scope, "bt_return");
        var result =
                matcher.match(scope, po, line(statement("late", "late-bank", "CREDIT", 5700, 0, "po_reverse")), 5700);
        assertThat(result.state()).isEqualTo("CONFLICT");
        assertThat(result.matchId()).isNull();
        assertThat(count("payout_bank_match")).isZero();
        assertThat(balance("BANK_CASH")).isZero();
        assertThat(count("bank_statement_line")).isEqualTo(1);
    }

    @Test
    void amountOverflowInvalidSchemaDuplicateCrossFileAndRuntimeTamperingRejectAtomically() {
        UUID po = outgoing("po_scope");
        var data = statement("a", "stable-line", "CREDIT", 5700, 0, "po_scope");
        UUID l = line(data);
        var repeated = statement("b", "stable-line", "CREDIT", 5700, 0, "po_scope");
        assertThatThrownBy(() -> banks.importStatement(scope, repeated)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bank_statement")).isEqualTo(1);
        var fractional = statement("c", "fractional", "CREDIT", 5700, 0, "po_scope");
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        fractional.path("lines").get(0))
                .put("principalMinor", 57.01);
        assertThatThrownBy(() -> banks.importStatement(scope, fractional)).isInstanceOf(IllegalArgumentException.class);
        var extra = statement("d", "extra", "CREDIT", 5700, 0, "po_scope");
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("accountNumber", "private");
        assertThatThrownBy(() -> banks.importStatement(scope, extra)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.bank_statement_line SET allocated_minor=1 WHERE id=?", l))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.bank_statement_line SET principal_minor=1 WHERE id=?", l))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> matcher.match(new ProviderScope(id(1), id(2), id(99), false), po, l, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualPspFeesStaySeparateFromPrincipalIncludingSignedFeeReturn() {
        UUID po = accept("po_fee", "paid", "bt_fee", null);
        ingest(bt("bt_fee", "po_fee", -5700, 30));
        replay.replay(scope, "bt_fee");
        assertThat(balance("PSP_FUNDS")).isEqualTo(-5730);
        assertThat(balance("BANK_PAYOUT_IN_TRANSIT")).isEqualTo(5700);
        assertThat(balance("PSP_FEE_EXPENSE")).isEqualTo(30);
        accept("po_fee", "failed", "bt_fee", "bt_fee_return");
        ingest(bt("bt_fee_return", "po_fee", 5700, -30));
        replay.replay(scope, "bt_fee_return");
        assertThat(balance("PSP_FUNDS")).isZero();
        assertThat(balance("PSP_FEE_EXPENSE")).isZero();
        assertThat(((Number) payouts.lookup(scope, "po_fee").get("returned_minor")).longValue())
                .isEqualTo(5700);
    }

    @Test
    void wrongDateDoesNotMatchAndFullyMatchedIdempotencyCannotAllocateTwice() {
        UUID po = outgoing("po_date");
        var s = statement("far", "far", "CREDIT", 5700, 0, "po_date");
        var o = (com.fasterxml.jackson.databind.node.ObjectNode) s;
        o.put("coverageFrom", from.plusSeconds(864000).toString())
                .put("coverageUntil", from.plusSeconds(950400).toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) o.path("lines").get(0))
                .put("bookedAt", from.plusSeconds(864100).toString());
        UUID l = line(s);
        assertThatThrownBy(() -> matcher.match(scope, po, l, 5700)).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance("BANK_CASH")).isZero();
    }

    @Test
    void financialWorkerQueriesMissingPayoutAndKnownWorkerPersistsRefreshRotation() {
        reads.queryFact = fact("po_worker", "paid", "bt_worker", null);
        ingest(bt("bt_worker", "po_worker", -5700, 0));
        var source = new PayoutSourceRecovery(reads, payouts, jdbc, tx, json);
        var worker = new FinancialIngestionWorker(
                jdbc,
                importer,
                replay,
                scope,
                Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC),
                RefundSourceResolver.unavailable(),
                DisputeSourceResolver.unavailable(),
                source);
        worker.tick();
        assertThat(payouts.lookup(scope, "po_worker")).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM platform.balance_transaction WHERE bt_id='bt_worker'", String.class))
                .isEqualTo("POSTED");
        var queries = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        var known = new PayoutRecoveryWorker(
                jdbc,
                queries,
                source,
                Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC),
                id(1).toString(),
                id(2).toString(),
                id(4).toString());
        known.tick();
        assertThat(payouts.lookup(scope, "po_worker").get("last_query_attempt_at"))
                .isNotNull();
        assertThat(count("payout_read_observation")).isGreaterThanOrEqualTo(2);
    }

    UUID automatic(String name) {
        return payouts.accept(
                scope,
                new PayoutFact(name, "paid", 5700, "usd", "bt_auto", null, true, from.plusSeconds(100), null),
                evidence());
    }

    BalanceTransactionFact member(String currency, String status) {
        return new BalanceTransactionFact(
                "bt_member",
                "charge",
                6000,
                300,
                5700,
                currency,
                "PAYMENTS",
                "ch_1",
                "charge",
                status,
                from.plusSeconds(1),
                until,
                List.of(),
                null);
    }

    void membershipPage(BalanceTransactionFact member) {
        reads.pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new BalanceTransactionPage(List.of(member), false, null),
                evidence()));
    }

    @Test
    void membershipCurrencyMustMatchPayoutAndMutableAvailabilityDoesNotConflict() {
        UUID po = automatic("po_members");
        var queries = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        membershipPage(member("jpy", "pending"));
        assertThat(queries.importPage(queries.createMembershipWindow(scope, po, from, until)))
                .isEqualTo("REJECTED");
        assertThat(count("payout_membership")).isZero();
    }

    @Test
    void membershipRefreshOnlyComparesImmutableMoneyIdentity() {
        UUID po = automatic("po_availability");
        var queries = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        membershipPage(member("usd", "pending"));
        queries.importPage(queries.createMembershipWindow(scope, po, from, until));
        automatic("po_availability");
        membershipPage(member("usd", "available"));
        assertThatCode(() -> assertThat(queries.importPage(queries.createMembershipWindow(scope, po, from, until)))
                        .isEqualTo("COMPLETE"))
                .doesNotThrowAnyException();
        assertThat(count("payout_membership")).isEqualTo(1);
    }

    @Test
    void queryReadEvidenceCannotCrossProviderScopeAndManualRunCannotForgeMembership() {
        UUID po = outgoing("po_lineage");
        var queries = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        UUID run = queries.createWindow(scope, from, until);
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','other',false)""", id(8), id(1), id(2));
        var other = new ProviderScope(id(1), id(2), id(8), false);
        var result = new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                new Page<>(List.of(fact("po_other", "paid", null, null)), false, null),
                evidence());
        assertThatThrownBy(() -> queries.recordRead(other, run, "LIST_PAYOUTS", null, result))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO platform.payout_read_run(organization_id,environment_id,provider_account_id,livemode,
                            kind,payout_id,created_gte,created_lt)
                        VALUES (?,?,?,false,'MEMBERSHIP',?,?,?)""", id(1), id(2), id(4), po, java.sql.Timestamp.from(from), java.sql.Timestamp.from(until)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void laterProviderObservationCreatesNewAutomaticMembershipReadAfterEmptyPage() {
        UUID po = automatic("po_later");
        reads.queryFact =
                new PayoutFact("po_later", "paid", 5700, "usd", "bt_auto", null, true, from.plusSeconds(100), null);
        var queries = new PayoutIngestionService(jdbc, tx, json, reads, reads, payouts);
        var source = new PayoutSourceRecovery(reads, payouts, jdbc, tx, json);
        var known = new PayoutRecoveryWorker(
                jdbc,
                queries,
                source,
                Clock.fixed(until.plusSeconds(100), ZoneOffset.UTC),
                id(1).toString(),
                id(2).toString(),
                id(4).toString());
        known.tick();
        UUID first = jdbc.queryForObject("SELECT id FROM platform.payout_read_run WHERE payout_id=?", UUID.class, po);
        reads.pages.add(new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS, new BalanceTransactionPage(List.of(), false, null), evidence()));
        queries.importPage(first);
        known.tick();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.payout_read_run WHERE payout_id=?", Long.class, po))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM platform.payout_read_run
                        WHERE payout_id=? AND state='INCOMPLETE'""", Long.class, po)).isEqualTo(1);
    }

    @Test
    void outOfWindowSharedLineAllocatedToAnotherPayoutCannotProveShortReceipt() {
        UUID a = outgoing("po_early");
        UUID b = payouts.accept(
                scope,
                new PayoutFact("po_late", "paid", 5700, "usd", "bt_late", null, false, from.plusSeconds(864000), null),
                evidence());
        ingest(bt("bt_late", "po_late", -5700, 0));
        replay.replay(scope, "bt_late");
        var s = statement("wide", "late-bank", "CREDIT", 5700, 0, "po_early", "po_late");
        ((com.fasterxml.jackson.databind.node.ObjectNode) s)
                .put("coverageUntil", from.plusSeconds(1036800).toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) s.path("lines").get(0))
                .put("bookedAt", from.plusSeconds(864100).toString());
        UUID l = line(s);
        matcher.match(scope, b, l, 5700);
        assertThatThrownBy(() -> matcher.match(scope, a, l, 5700)).isInstanceOf(IllegalArgumentException.class);
        assertThat(matcher.evidenceState(scope, a)).isEqualTo("WAITING_EVIDENCE");
        assertThat(((Number) payouts.lookup(scope, "po_early").get("transit_minor")).longValue())
                .isEqualTo(5700);
    }
}
