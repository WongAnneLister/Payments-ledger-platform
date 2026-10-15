package com.paymentsledger.notification.fixture;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.paymentsledger.notification.NotificationSignature;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent merchant fixture: its database owns both receipt deduplication and fulfillment. */
public final class SimulatedMerchantServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String secret;
    private final Clock clock;
    private final AtomicBoolean drop = new AtomicBoolean();
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SimulatedMerchantServer(InetSocketAddress address, DataSource ds, String secret, Clock clock)
            throws IOException {
        if (address.isUnresolved() || !address.getAddress().isLoopbackAddress())
            throw new IllegalArgumentException("LOOPBACK_REQUIRED");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("KEY_UNAVAILABLE");
        this.secret = secret;
        this.clock = clock;
        this.jdbc = new JdbcTemplate(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS merchant_fixture");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS merchant_fixture.inbox (
              event_id uuid PRIMARY KEY, business_key text NOT NULL, digest text NOT NULL,
              received_at timestamptz NOT NULL DEFAULT now())
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS merchant_fixture.fulfillment (
              business_key text PRIMARY KEY, order_id uuid NOT NULL, payment_id uuid NOT NULL,
              currency text NOT NULL, amount_minor bigint NOT NULL, fulfilled_at timestamptz NOT NULL DEFAULT now())
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS merchant_fixture.refund_effect (
              business_key text PRIMARY KEY, refund_id uuid NOT NULL, payment_id uuid NOT NULL,
              refund_status text NOT NULL, currency text NOT NULL, amount_minor bigint NOT NULL,
              recorded_at timestamptz NOT NULL DEFAULT now())
            """);
        server = HttpServer.create(address, 16);
        server.setExecutor(executor);
        server.createContext("/notifications", this::handle);
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/notifications");
    }

    public void dropNextResponse() {
        drop.set(true);
    }

    public long effects() {
        return jdbc.queryForObject("SELECT count(*) FROM merchant_fixture.fulfillment", Long.class);
    }

    public long receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM merchant_fixture.inbox", Long.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/notifications".equals(exchange.getRequestURI().toString())) {
                respond(exchange, 404);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(16385);
            if (body.length == 0 || body.length > 16384) {
                respond(exchange, 413);
                return;
            }
            String eventHeader = header(exchange, "X-Payments-Event-Id"),
                    key = header(exchange, "X-Payments-Business-Key");
            UUID id;
            try {
                id = UUID.fromString(eventHeader);
            } catch (RuntimeException e) {
                respond(exchange, 401);
                return;
            }
            if (key == null
                    || key.length() > 512
                    || !NotificationSignature.verify(
                            body,
                            id,
                            key,
                            header(exchange, "X-Payments-Timestamp"),
                            header(exchange, "X-Payments-Signature"),
                            secret,
                            clock)) {
                respond(exchange, 401);
                return;
            }
            JsonNode event;
            try {
                event = json.readTree(body);
                validate(event, id, key);
            } catch (Exception e) {
                respond(exchange, 400);
                return;
            }
            String digest = digest(body);
            boolean accepted = Boolean.TRUE.equals(tx.execute(s -> {
                int inserted = jdbc.update("""
                        INSERT INTO merchant_fixture.inbox(event_id,business_key,digest)
                        VALUES(?,?,?)
                        ON CONFLICT
                        DO NOTHING""", id, key, digest);
                if (inserted == 0)
                    return digest.equals(jdbc.queryForObject(
                            "SELECT digest FROM merchant_fixture.inbox WHERE event_id=?", String.class, id));
                if ("payment.succeeded".equals(event.path("eventType").asText())) {
                    jdbc.update(
                            """
                        INSERT INTO merchant_fixture.fulfillment(business_key,order_id,payment_id,currency,amount_minor)
                        VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING
                        """,
                            key,
                            UUID.fromString(event.path("orderId").asText()),
                            UUID.fromString(event.path("paymentId").asText()),
                            event.path("currency").asText(),
                            event.path("amountMinor").longValue());
                }
                if (event.path("eventType").asText().startsWith("refund.")) {
                    jdbc.update(
                            """
                        INSERT INTO merchant_fixture.refund_effect(business_key,refund_id,payment_id,refund_status,currency,amount_minor)
                        VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
                        """,
                            key,
                            UUID.fromString(event.path("refundId").asText()),
                            UUID.fromString(event.path("paymentId").asText()),
                            event.path("refundStatus").asText(),
                            event.path("currency").asText(),
                            event.path("amountMinor").longValue());
                }
                return true;
            }));
            if (!accepted) {
                respond(exchange, 409);
                return;
            }
            // Durable merchant commit happens before the fault injection and before any 2xx bytes.
            if (drop.compareAndSet(true, false)) {
                exchange.close();
                return;
            }
            respond(exchange, 200);
        } catch (RuntimeException e) {
            respond(exchange, 500);
        } finally {
            exchange.close();
        }
    }

    private static String header(HttpExchange exchange, String name) {
        var values = exchange.getRequestHeaders().get(name);
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    private static void validate(JsonNode event, UUID id, String key) {
        if (!event.isObject()
                || !event.path("schemaVersion").isInt()
                || event.path("schemaVersion").intValue() != 1
                || !id.toString().equals(event.path("eventId").asText())
                || !key.equals(event.path("businessKey").asText())) throw new IllegalArgumentException();
        for (String field : List.of(
                "organizationId", "environmentId", "applicationId", "providerAccountId", "orderId", "paymentId"))
            UUID.fromString(event.required(field).asText());
        String type = event.required("eventType").asText();
        if (!Set.of(
                        "payment.succeeded",
                        "payment.canceled",
                        "payment.state_changed",
                        "refund.succeeded",
                        "refund.failed",
                        "refund.canceled")
                .contains(type)) throw new IllegalArgumentException();
        boolean refund = type.startsWith("refund.");
        if (refund) {
            UUID.fromString(event.required("refundId").asText());
            if (!type.substring(7)
                    .toUpperCase(Locale.ROOT)
                    .equals(event.path("refundStatus").asText())) throw new IllegalArgumentException();
        }
        String expected = event.path("organizationId").asText() + ":"
                + event.path("environmentId").asText() + ":"
                + event.path("applicationId").asText() + ":"
                + event.path(refund ? "refundId" : "orderId").asText() + ":" + type;
        if (!expected.equals(key)
                || !event.path("amountMinor").isIntegralNumber()
                || !event.path("amountMinor").canConvertToLong()
                || event.path("amountMinor").longValue() <= 0
                || !event.path("currency").asText().matches("[A-Z]{3}")) throw new IllegalArgumentException();
        if (type.equals("payment.succeeded")
                && (!event.path("paymentStatus").asText().equals("SUCCEEDED")
                        || !event.path("origin").asText().equals("NATIVE")
                        || !event.path("capturedAmountMinor").isIntegralNumber()
                        || !event.path("capturedAmountMinor").canConvertToLong()
                        || event.path("capturedAmountMinor").longValue()
                                != event.path("amountMinor").longValue())) throw new IllegalArgumentException();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE");
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
