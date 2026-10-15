package com.paymentsledger.pr20.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only external state: its separate database is never part of the platform backup. */
public final class SqlPerformanceProvider
        implements CheckoutPort, PaymentIntentPort, ChargePort, BalanceTransactionPort, AutoCloseable {
    private final HikariDataSource pool = new HikariDataSource();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public SqlPerformanceProvider(String url, String user, String password) {
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(10);
        pool.setAutoCommit(true);
        jdbc = new JdbcTemplate(pool);
    }

    static void initialize(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE simulator_payment(
            seq bigserial PRIMARY KEY,scope text NOT NULL,operation_key text NOT NULL,amount bigint NOT NULL CHECK(amount>0),
            currency text NOT NULL,metadata jsonb NOT NULL,expires_at timestamptz NOT NULL,
            created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(scope,operation_key))
            """);
        db.execute("CREATE INDEX simulator_created ON simulator_payment(created_at,seq)");
        db.execute("""
                CREATE TABLE simulator_call(seq bigserial PRIMARY KEY,kind text NOT NULL,at timestamptz NOT NULL DEFAULT
                    clock_timestamp())""");
    }

    private String scoped(ProviderScope s) {
        return s.organizationId() + ":" + s.environmentId() + ":" + s.providerAccountId() + ":" + s.livemode();
    }

    private <T> ProviderCallResult<T> result(String kind, T fact, String key) {
        jdbc.update("INSERT INTO simulator_call(kind) VALUES(?)", kind);
        Instant now = Instant.now();
        return new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                fact,
                new ProviderEvidence(
                        "pr20_sql_" + UUID.randomUUID(), key, now, now, 200, null, "a".repeat(64), "pr20-sql-v1"));
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
        try {
            jdbc.update(
                    """
                    INSERT INTO simulator_payment(scope,operation_key,amount,currency,metadata,expires_at)
                    VALUES(?,?,?,?,?::jsonb,?)
                    ON CONFLICT
                    DO NOTHING""",
                    scoped(c.scope()),
                    c.operationIdempotencyKey(),
                    c.amountMinor(),
                    c.currency(),
                    json.writeValueAsString(c.sessionMetadata()),
                    Timestamp.from(c.expiresAt()));
            var row = jdbc.queryForMap(
                    "SELECT * FROM simulator_payment WHERE scope=? AND operation_key=?",
                    scoped(c.scope()),
                    c.operationIdempotencyKey());
            if (((Number) row.get("amount")).longValue() != c.amountMinor()
                    || !c.currency().equals(row.get("currency")))
                throw new IllegalArgumentException("Simulator idempotency mismatch");
            // Model hosted customer acceptance immediately outside the platform; create still returns the hosted OPEN
            // session.
            return result("CREATE_SESSION", session(row, false), c.operationIdempotencyKey());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Simulator serialization failed", e);
        }
    }

    private Map<String, Object> row(ProviderScope scope, String ref) {
        long seq = Long.parseLong(ref.substring(ref.lastIndexOf('_') + 1));
        return jdbc.queryForMap("SELECT * FROM simulator_payment WHERE scope=? AND seq=?", scoped(scope), seq);
    }

    private long sequence(Map<String, Object> r) {
        return ((Number) r.get("seq")).longValue();
    }

    private long amount(Map<String, Object> r) {
        return ((Number) r.get("amount")).longValue();
    }

    private Instant created(Map<String, Object> r) {
        return ((Timestamp) r.get("created_at")).toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> metadata(Map<String, Object> r) {
        try {
            return json.readValue(r.get("metadata").toString(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CheckoutSessionFact session(Map<String, Object> r, boolean complete) {
        long n = sequence(r);
        return new CheckoutSessionFact(
                "cs_pr20_" + n,
                complete ? CheckoutSessionStatus.COMPLETE : CheckoutSessionStatus.OPEN,
                "https://provider.test/pr20/" + n,
                "pi_pr20_" + n,
                complete ? "paid" : "unpaid",
                ((Timestamp) r.get("expires_at")).toInstant(),
                metadata(r));
    }

    private ChargeFact charge(Map<String, Object> r) {
        long n = sequence(r);
        return new ChargeFact(
                "ch_pr20_" + n,
                "pi_pr20_" + n,
                "succeeded",
                true,
                amount(r),
                amount(r),
                r.get("currency").toString(),
                "bt_pr20_" + n,
                null,
                "authorized",
                created(r));
    }

    private BalanceTransactionFact bt(Map<String, Object> r) {
        long n = sequence(r);
        return new BalanceTransactionFact(
                "bt_pr20_" + n,
                "charge",
                amount(r),
                0,
                amount(r),
                r.get("currency").toString().toLowerCase(Locale.ROOT),
                "PAYMENTS",
                "ch_pr20_" + n,
                "charge",
                "available",
                created(r),
                created(r),
                List.of(),
                "a".repeat(64));
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String id) {
        return result("QUERY_SESSION", session(row(s, id), true), null);
    }

    @Override
    public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String id) {
        return querySession(s, id);
    }

    @Override
    public ProviderCallResult<PaymentIntentFact> queryPaymentIntent(ProviderScope s, String id) {
        var r = row(s, id);
        long n = sequence(r);
        return result(
                "QUERY_INTENT",
                new PaymentIntentFact(
                        "pi_pr20_" + n,
                        "succeeded",
                        amount(r),
                        amount(r),
                        r.get("currency").toString(),
                        List.of("ch_pr20_" + n),
                        "ch_pr20_" + n,
                        null,
                        metadata(r),
                        created(r)),
                null);
    }

    @Override
    public ProviderCallResult<ChargeFact> queryCharge(ProviderScope s, String id) {
        return result("QUERY_CHARGE", charge(row(s, id)), null);
    }

    @Override
    public ProviderCallResult<List<ChargeFact>> listChargeAttempts(ProviderScope s, String id) {
        return result("LIST_CHARGES", List.of(charge(row(s, id))), null);
    }

    @Override
    public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s, String id) {
        return result("GET_BT", bt(row(s, id)), null);
    }

    @Override
    public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q) {
        long after = q.startingAfterCursor() == null
                ? 0
                : Long.parseLong(q.startingAfterCursor()
                        .substring(q.startingAfterCursor().lastIndexOf('_') + 1));
        var rows = jdbc.queryForList(
                """
                SELECT *
                FROM simulator_payment
                WHERE scope=? AND created_at>=? AND created_at<? AND seq>?
                ORDER BY seq
                LIMIT ?""",
                scoped(q.scope()),
                Timestamp.from(q.createdGte()),
                Timestamp.from(q.createdLt()),
                after,
                q.limit() + 1);
        boolean more = rows.size() > q.limit();
        if (more) rows = rows.subList(0, q.limit());
        var facts = rows.stream().map(this::bt).toList();
        return result(
                "LIST_BT",
                new BalanceTransactionPage(facts, more, more ? facts.getLast().btId() : null),
                null);
    }

    @Override
    public void close() {
        pool.close();
    }
}
