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
                        "INSERT INTO platform.x(at,ref) VALUES (?,?) [Timestamp:1970-01-01T00:00:00Z, UUID:U1]");
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
