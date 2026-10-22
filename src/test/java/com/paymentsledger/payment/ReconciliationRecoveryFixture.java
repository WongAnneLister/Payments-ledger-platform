package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.bank.repository.BankMatchRepository;
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

/**
 * A reconciliation run over one captured provider balance transaction ({@code txn_recovered}), with a
 * {@link ReconciliationSourceRecovery} whose provider reads are controlled by {@link Reads}.
 */
abstract class ReconciliationRecoveryFixture extends PaymentCoreFixture {
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
        var bank = new BankMatcher(new BankMatchRepository(jdbc), tx, json, ledger);
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
        recovery = sources(reads, reads);
    }

    /** A source recovery over this fixture's run; null ports model a deployment without provider reads. */
    ReconciliationSourceRecovery sources(ChargePort charges, PaymentIntentPort intents) {
        return new ReconciliationSourceRecovery(jdbc, tx, json, charges, intents, repository, replay, clock);
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

    class Reads implements BalanceTransactionPort, ChargePort, PaymentIntentPort {
        List<BalanceTransactionFact> extra = List.of();
        Map<String, String> metadata = Map.of();
        long captureAmount = 10000;
        boolean unavailable, pagesUnavailable;
        String readVersion = "independent-fixture-v1";
        String chargeIntent = "pi_recovered";
        RuntimeException chargeFailure;

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
            if (chargeFailure != null) throw chargeFailure;
            if (unavailable)
                return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN, null, evidence());
            return ok(new ChargeFact(
                    "ch_recovered",
                    chargeIntent,
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
