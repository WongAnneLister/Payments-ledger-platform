package com.paymentsledger.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Records every JDBC statement, parameter list and transaction boundary a test issues.
 *
 * <p>Used to prove that a refactor keeps database behavior: the same statements, in the same order (which is also
 * the lock order), with the same parameters and the same commit/rollback boundaries. Run the suite before and after
 * the refactor and compare the written traces.
 *
 * <p>Output is normalized so two runs compare equal: SQL whitespace is collapsed, UUIDs become stable labels in order
 * of first appearance, and statements issued by threads other than the test thread are written as separately
 * labelled blocks sorted by content, because which racing thread wins is not deterministic.
 */
public final class SqlTrace {
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> EXECUTE =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final Thread testThread = Thread.currentThread();
    private final Map<Thread, List<String>> events = new LinkedHashMap<>();

    public synchronized void reset() {
        events.clear();
    }

    public DataSource wrap(DataSource dataSource) {
        return proxy(DataSource.class, (target, method, args) -> {
            Object result = invoke(dataSource, method, args);
            if (method.getName().equals("getConnection")) {
                record("CONNECT");
                return connection((Connection) result);
            }
            return result;
        });
    }

    /** Writes the normalized trace; the file name is usually the test method name. */
    public synchronized void write(Path file) {
        var lines = new ArrayList<String>();
        var labels = new HashMap<String, String>();
        for (String event : events.getOrDefault(testThread, List.of())) lines.add(label(event, labels, "U"));
        var others = new TreeMap<String, Integer>();
        for (var entry : events.entrySet()) {
            if (entry.getKey() == testThread) continue;
            var local = new HashMap<String, String>();
            var block = new StringBuilder();
            for (String event : entry.getValue())
                block.append("  ").append(label(event, local, "T")).append('\n');
            others.merge(block.toString(), 1, Integer::sum);
        }
        others.forEach((block, count) -> {
            lines.add("-- other thread x" + count);
            lines.add(block.stripTrailing());
        });
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void record(String event) {
        events.computeIfAbsent(Thread.currentThread(), ignored -> new ArrayList<>())
                .add(event);
    }

    private Connection connection(Connection connection) {
        return proxy(Connection.class, (target, method, args) -> {
            switch (method.getName()) {
                case "setAutoCommit" -> record(Boolean.FALSE.equals(args[0]) ? "BEGIN" : "AUTOCOMMIT");
                case "commit" -> record("COMMIT");
                case "rollback" -> record(args == null ? "ROLLBACK" : "ROLLBACK TO SAVEPOINT");
                case "setSavepoint" -> record("SAVEPOINT");
                case "releaseSavepoint" -> record("RELEASE SAVEPOINT");
                case "setTransactionIsolation" -> record("ISOLATION " + args[0]);
                case "setReadOnly" -> record("READ ONLY " + args[0]);
                default -> {}
            }
            Object result = invoke(connection, method, args);
            if (method.getName().equals("prepareStatement"))
                return statement(PreparedStatement.class, (PreparedStatement) result, (String) args[0]);
            if (method.getName().equals("createStatement")) return statement(Statement.class, (Statement) result, null);
            return result;
        });
    }

    private <T extends Statement> T statement(Class<T> type, T statement, String preparedSql) {
        var parameters = new TreeMap<Integer, String>();
        return proxy(type, (target, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index)
                parameters.put(index, describe(name, args[1]));
            else if (name.equals("clearParameters")) parameters.clear();
            else if (EXECUTE.contains(name)) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                record(sql.replaceAll("\\s+", " ").trim() + (parameters.isEmpty() ? "" : " " + parameters.values()));
            }
            return invoke(statement, method, args);
        });
    }

    private static String describe(String setter, Object value) {
        if (setter.equals("setNull")) return "null";
        if (value == null) return "null";
        if (value instanceof Timestamp t) return "Timestamp:" + t.toInstant();
        if (value instanceof byte[] b) return "bytes[" + b.length + "]";
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static String label(String event, Map<String, String> labels, String prefix) {
        Matcher m = UUID_PATTERN.matcher(event);
        var out = new StringBuilder();
        while (m.find())
            m.appendReplacement(out, labels.computeIfAbsent(m.group(), ignored -> prefix + (labels.size() + 1)));
        m.appendTail(out);
        return out.toString();
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        // Spring keys transaction resources by DataSource and compares connections; keep identity semantics.
        InvocationHandler identity = (self, method, args) -> switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && self == args[0];
            case "hashCode" -> args == null ? System.identityHashCode(self) : handler.invoke(self, method, args);
            default -> handler.invoke(self, method, args);
        };
        return (T) Proxy.newProxyInstance(SqlTrace.class.getClassLoader(), new Class<?>[] {type}, identity);
    }
}
