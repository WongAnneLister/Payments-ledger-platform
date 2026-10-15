package com.paymentsledger.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SqlTraceTest {
    @TempDir
    Path directory;

    /** A database stand-in: statements succeed and update one row; connections start in auto-commit. */
    static DataSource stubDatabase() {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                SqlTraceTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (p, m, a) -> switch (m.getName()) {
                    case "executeUpdate" -> 1;
                    case "getUpdateCount" -> -1;
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                SqlTraceTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "prepareStatement" -> statement;
                    case "getAutoCommit" -> true;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    default -> m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null;
                });
        return (DataSource) Proxy.newProxyInstance(
                SqlTraceTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (p, m, a) -> m.getName().equals("getConnection") ? connection : null);
    }

    @Test
    void recordsStatementsParametersAndTransactionBoundariesWithStableLabels() throws Exception {
        var trace = new SqlTrace();
        DataSource ds = trace.wrap(stubDatabase());
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID payout = UUID.randomUUID(), line = UUID.randomUUID();

        tx.executeWithoutResult(s -> {
            jdbc.update("SELECT 1\n  FROM   platform.payout WHERE id=? FOR UPDATE", payout);
            jdbc.update("UPDATE platform.bank_statement_line SET allocated_minor=? WHERE id=?", 5700L, line);
        });
        jdbc.update("INSERT INTO platform.x(at,ref) VALUES (?,?)", Timestamp.from(Instant.EPOCH), payout);
        Path out = directory.resolve("trace.txt");
        trace.write(out);

        assertThat(Files.readAllLines(out))
                .containsExactly(
                        "CONNECT",
                        "BEGIN",
                        "SELECT 1 FROM platform.payout WHERE id=? FOR UPDATE [UUID:U1]",
                        "UPDATE platform.bank_statement_line SET allocated_minor=? WHERE id=? [Long:5700, UUID:U2]",
                        "COMMIT",
                        "AUTOCOMMIT",
                        "CONNECT",
                        "INSERT INTO platform.x(at,ref) VALUES (?,?) [Timestamp:@1, UUID:U1]");
    }

    @Test
    void runVaryingIdsAndInstantsInsideStringsAreLabelledPerKind() throws Exception {
        var trace = new SqlTrace();
        var jdbc = new JdbcTemplate(trace.wrap(stubDatabase()));
        String payment = "pay_01M4RJQNKY9KZWRRDF5R3H5DCE", other = "pay_01M4RJQNKYBPS6FVANFZ0C40NW";
        Instant at = Instant.parse("2026-10-12T17:31:58.956948Z");
        jdbc.update(
                "INSERT INTO platform.e(doc,seen) VALUES (?::jsonb,?)",
                "{\"id\":\"" + payment + "\",\"at\":\"" + at + "\"}",
                Timestamp.from(at));
        jdbc.update(
                "INSERT INTO platform.e(doc) VALUES (?::jsonb)",
                "{\"id\":\"" + other + "\",\"again\":\"" + payment + "\",\"at\":\"" + at.plusSeconds(1) + "\"}");
        Path out = directory.resolve("ids.txt");
        trace.write(out);
        assertThat(Files.readAllLines(out))
                .containsExactly(
                        "CONNECT",
                        "INSERT INTO platform.e(doc,seen) VALUES (?::jsonb,?) [String:{\"at\":\"@1\",\"id\":\"ID1\"},"
                                + " Timestamp:@1]",
                        "CONNECT",
                        "INSERT INTO platform.e(doc) VALUES (?::jsonb) [String:{\"again\":\"ID1\",\"at\":\"@2\","
                                + "\"id\":\"ID2\"}]");
    }

    @Test
    void jsonKeyOrderDoesNotChangeTheTraceOrShiftLaterLabels() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), later = UUID.randomUUID();
        String a = traceOf(jdbc -> {
            jdbc.update(
                    "INSERT INTO platform.op(command) VALUES (?::jsonb)",
                    "{\"metadata\":{\"payment_id\":\"" + first + "\",\"order_id\":\"" + second + "\"},\"amount\":1.0}");
            jdbc.update("UPDATE platform.op SET x=? WHERE id=?", 1, later);
        });
        String b = traceOf(jdbc -> {
            jdbc.update(
                    "INSERT INTO platform.op(command) VALUES (?::jsonb)",
                    "{\"amount\":1.0,\"metadata\":{\"order_id\":\"" + second + "\",\"payment_id\":\"" + first + "\"}}");
            jdbc.update("UPDATE platform.op SET x=? WHERE id=?", 1, later);
        });
        assertThat(b).isEqualTo(a);
        assertThat(a)
                .contains("{\"amount\":1.0,\"metadata\":{\"order_id\":\"U1\",\"payment_id\":\"U2\"}}")
                .contains("[Integer:1, UUID:U3]");
    }

    @Test
    void offsetTimestampsShareTheLabelOfTheSameInstantAndArrayLiteralsStayRaw() throws Exception {
        Instant at = Instant.parse("2026-10-15T08:34:27.564858Z");
        String trace = traceOf(jdbc -> {
            jdbc.update(
                    "INSERT INTO platform.s(snapshot,at) VALUES (?::jsonb,?)",
                    "{\"created_at\": \"2026-10-15T08:34:27.564858+00:00\", \"valid_from\": \"2026-10-14T08:34:27+00:00\"}",
                    Timestamp.from(at));
            jdbc.update(
                    "INSERT INTO platform.s(text,refs) VALUES (?,?::text[])",
                    "booked 2026-10-15 08:34:27.564858+00",
                    "{\"po_a\",\"po_b\"}");
        });
        assertThat(trace)
                .contains("[String:{\"created_at\":\"@1\",\"valid_from\":\"@2\"}, Timestamp:@1]")
                .contains("[String:booked @1, String:{\"po_a\",\"po_b\"}]");
    }

    private String traceOf(java.util.function.Consumer<JdbcTemplate> work) throws Exception {
        var trace = new SqlTrace();
        work.accept(new JdbcTemplate(trace.wrap(stubDatabase())));
        Path out = Files.createTempFile(directory, "trace", ".txt");
        trace.write(out);
        return Files.readString(out);
    }

    @Test
    void listenerSeesStatementsWhicheverJdbcTemplateMethodIssuedThem() {
        var seen = new java.util.ArrayList<String>();
        DataSource ds = new SqlTrace(seen::add).wrap(stubDatabase());
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("UPDATE platform.payout SET x=? WHERE id=?", 1, 2);
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                .update(
                        "SELECT *\n  FROM platform.payout\n  WHERE id=:id FOR UPDATE",
                        java.util.Map.of("id", UUID.randomUUID()));
        assertThat(seen)
                .containsExactly(
                        "UPDATE platform.payout SET x=? WHERE id=?",
                        "SELECT * FROM platform.payout WHERE id=? FOR UPDATE");
    }

    @Test
    void racingThreadsAreLabelledLocallyAndSortedSoTheWinnerDoesNotMatter() throws Exception {
        String first = traceOfRace(true), second = traceOfRace(false);
        assertThat(first).isEqualTo(second).contains("-- other thread x1");
    }

    private String traceOfRace(boolean aFirst) throws Exception {
        var trace = new SqlTrace();
        var jdbc = new JdbcTemplate(trace.wrap(stubDatabase()));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (var pool = Executors.newSingleThreadExecutor();
                var other = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> jdbc.update("WIN ?", aFirst ? a : b)).get(5, TimeUnit.SECONDS);
            other.submit(() -> jdbc.update("LOSE ?", aFirst ? b : a)).get(5, TimeUnit.SECONDS);
        }
        Path out = directory.resolve("race-" + aFirst + ".txt");
        trace.write(out);
        return Files.readString(out);
    }
}
