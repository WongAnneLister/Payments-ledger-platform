package com.paymentsledger.testsupport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import java.util.function.Consumer;
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
 * <p>Output is normalized so two runs compare equal: SQL whitespace is collapsed, UUIDs, ULID IDs and instants become stable labels in order
 * of first appearance, and statements issued by threads other than the test thread are written as separately
 * labelled blocks sorted by content, because which racing thread wins is not deterministic.
 */
public final class SqlTrace {
    /** Values that differ between runs: random UUIDs, ULID wire IDs (pay_01J...) and wall-clock instants. */
    private static final Pattern VOLATILE =
            Pattern.compile("(?<uuid>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
                    + "|(?<ulid>\\b[a-z]+_[0-9A-HJKMNP-TV-Z]{26}\\b)"
                    // Java renders Z; PostgreSQL row JSON and text render +00:00 / +00, possibly with a space.
                    + "|(?<instant>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?"
                    + "(?:Z|[+-]\\d{2}(?::?\\d{2})?))");

    /** Parses JSON-looking string parameters so they can be recorded with sorted keys. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private static final Set<String> EXECUTE =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final Thread testThread = Thread.currentThread();
    private final Map<Thread, List<String>> events = new LinkedHashMap<>();
    private final Consumer<String> onStatement;

    public SqlTrace() {
        this(sql -> {});
    }

    /**
     * {@code onStatement} receives each executed statement's whitespace-normalized SQL as it runs, on the executing
     * thread. Observing at the DataSource sees every statement, however the caller reached JDBC.
     */
    public SqlTrace(Consumer<String> onStatement) {
        this.onStatement = onStatement;
    }

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
        var labels = new Labels();
        for (String event : events.getOrDefault(testThread, List.of())) lines.add(label(event, labels, "U"));
        var others = new TreeMap<String, Integer>();
        for (var entry : events.entrySet()) {
            if (entry.getKey() == testThread) continue;
            var local = new Labels();
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
                String flat = sql.replaceAll("\\s+", " ").trim();
                onStatement.accept(flat);
                // Layout around commas and parentheses is not semantic: "(?, ?)" and "(?,?)" trace the same.
                String sqlText =
                        flat.replaceAll(" ?, ?", ",").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
                record(sqlText + (parameters.isEmpty() ? "" : " " + parameters.values()));
            }
            return invoke(statement, method, args);
        });
    }

    private static String describe(String setter, Object value) {
        if (setter.equals("setNull")) return "null";
        if (value == null) return "null";
        if (value instanceof Timestamp t) return "Timestamp:" + t.toInstant();
        if (value instanceof byte[] b) return "bytes[" + b.length + "]";
        if (value instanceof String s) return "String:" + canonicalJson(s);
        return value.getClass().getSimpleName() + ":" + value;
    }

    /**
     * JSON objects are recorded with keys sorted, recursively, before volatile values are labelled. Key order is not
     * semantic for jsonb, and Map.of iteration order is salted per JVM, so unsorted keys would also reorder the first
     * appearance of IDs and shift every later label. Array order is kept. Non-JSON strings pass through unchanged.
     */
    static String canonicalJson(String value) {
        String trimmed = value.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return value;
        try {
            return JSON.writeValueAsString(sorted(JSON.readTree(trimmed)));
        } catch (IOException notJson) {
            return value;
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var out = JSON.createObjectNode();
            var names = new java.util.TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) out.set(name, sorted(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            var out = JSON.createArrayNode();
            node.forEach(element -> out.add(sorted(element)));
            return out;
        }
        return node;
    }

    /** One label per instant, whichever offset or separator a value uses. */
    private static String instantKey(String text) {
        String iso = text.replace(' ', 'T');
        if (iso.matches(".*[+-]\\d{2}$")) iso += ":00";
        else if (iso.matches(".*[+-]\\d{4}$"))
            iso = iso.substring(0, iso.length() - 2) + ":" + iso.substring(iso.length() - 2);
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toString();
        } catch (java.time.format.DateTimeParseException unparsed) {
            return text;
        }
    }

    /** Labels for volatile values, numbered per kind (U, T, ID, @) in order of first appearance. */
    private static final class Labels {
        private final Map<String, String> byValue = new HashMap<>();
        private final Map<String, Integer> counters = new HashMap<>();

        String of(String kind, String value) {
            return byValue.computeIfAbsent(value, ignored -> kind + counters.merge(kind, 1, Integer::sum));
        }
    }

    private static String label(String event, Labels labels, String uuidPrefix) {
        Matcher m = VOLATILE.matcher(event);
        var out = new StringBuilder();
        while (m.find()) {
            String kind = m.group("uuid") != null ? uuidPrefix : m.group("ulid") != null ? "ID" : "@";
            String key = kind.equals("@") ? "@" + instantKey(m.group()) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(labels.of(kind, key)));
        }
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
