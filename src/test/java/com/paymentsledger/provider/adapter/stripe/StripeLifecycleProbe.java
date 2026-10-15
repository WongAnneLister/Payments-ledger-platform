package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.ProviderCallClassification.SUCCESS;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Opt-in, test-source-only lifecycle contract probe. It does not process business callbacks. */
public final class StripeLifecycleProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DIR = Path.of("target/pr05-lifecycle");
    static final Set<String> EVENT_TYPES = Set.of(
            "checkout.session.completed",
            "payment_intent.succeeded",
            "charge.succeeded",
            "charge.updated",
            "charge.refunded",
            "refund.created",
            "refund.updated");
    final Path dir;
    final ProviderScope scope;
    final StripeGateway gateway;
    final StripeCheckoutAdapter checkout;
    final StripeRefundAdapter refunds;
    final StripeFinancialReadAdapter financial;
    final ObjectNode state;
    final ObjectNode evidence;
    ObjectNode current;

    StripeLifecycleProbe(Path dir, ProviderScope scope, StripeGateway gateway, ObjectNode state) throws IOException {
        this.dir = dir;
        this.scope = scope;
        this.gateway = gateway;
        this.state = state;
        checkout = new StripeCheckoutAdapter(gateway);
        refunds = new StripeRefundAdapter(gateway);
        financial = new StripeFinancialReadAdapter(gateway);
        Path file = dir.resolve("lifecycle-evidence.json");
        evidence = Files.exists(file) ? (ObjectNode) JSON.readTree(file.toFile()) : JSON.createObjectNode();
        evidence.put("apiVersion", StripeGateway.API_VERSION)
                .put("sdkVersion", StripeGateway.SDK_VERSION)
                .put("mode", "test")
                .put("runSha256", hash(state.path("runId").asText()));
    }

    public static void main(String[] args) {
        try {
            require("true".equals(System.getenv("STRIPE_LIFECYCLE_ENABLED")));
            require(args.length == 1
                    && Set.of("create", "inspect", "refund", "serve").contains(args[0]));
            String key = env("STRIPE_API_KEY"), account = env("STRIPE_ACCOUNT_ID");
            require(key.startsWith("sk_test_") || key.startsWith("rk_test_"));
            var scope = new ProviderScope(
                    UUID.fromString(env("STRIPE_ORGANIZATION_ID")),
                    UUID.fromString(env("STRIPE_ENVIRONMENT_ID")),
                    UUID.fromString(env("STRIPE_PROVIDER_ACCOUNT_ID")),
                    false);
            var gateway = new StripeGateway(key, scope, account, Clock.systemUTC());
            Files.createDirectories(DIR);
            if ("serve".equals(args[0])) {
                serve(DIR, scope, account);
                return;
            }
            // Serialize all CLI mutations so two retries cannot race local state persistence.
            try (var channel = FileChannel.open(
                            DIR.resolve("operation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                require(lock != null);
                require("create".equals(args[0]) || Files.exists(DIR.resolve("state.local.json")));
                var state = initialState(DIR, env("STRIPE_LIFECYCLE_BASE_URL"), account, scope, Instant.now());
                new StripeLifecycleProbe(DIR, scope, gateway, state).run(args[0]);
            }
        } catch (Exception error) {
            System.out.println("{\"status\":\"FAILED\",\"classification\":\"LOCAL_CONFIGURATION_OR_IO_FAILURE\"}");
            System.exit(1);
        }
    }

    void run(String command) throws IOException {
        current = JSON.createObjectNode();
        evidence.set(command, current);
        current.put("observedAtUtc", Instant.now().toString());
        current.putArray("observations");
        try {
            switch (command) {
                case "create" -> create();
                case "inspect" -> inspect();
                case "refund" -> refund();
                default -> throw new IllegalStateException();
            }
            current.put("status", "VERIFIED");
        } catch (Pending pending) {
            current.put("status", "PENDING");
        } catch (Exception error) {
            current.put("status", "FAILED");
        }
        atomic(dir.resolve("lifecycle-evidence.json"), evidence);
        System.out.println(JSON.writeValueAsString(current));
        if ("FAILED".equals(current.path("status").asText())) System.exit(1);
    }

    void create() throws IOException {
        CheckoutSessionFact session;
        if (state.hasNonNull("sessionId")) session = success(checkout.querySession(scope, value("sessionId")));
        else {
            // All request parameters, including the expiry and return URL base, were persisted first.
            session = success(checkout.createSession(new CreateCheckoutSessionCommand(
                    scope,
                    value("operationKey"),
                    100,
                    "usd",
                    value("baseUrl") + "/success",
                    value("baseUrl") + "/cancel",
                    Map.of("pr05_session", value("runId")),
                    Map.of("pr05_payment", value("runId")),
                    Instant.parse(value("expiresAt")))));
            state.put("sessionId", session.sessionId());
            if (session.hostedUrl() != null) state.put("hostedUrl", session.hostedUrl());
            saveState();
        }
        require(value("runId").equals(session.metadata().get("pr05_session"))
                && !session.metadata().containsKey("pr05_payment"));
        require(Instant.parse(value("expiresAt")).equals(session.expiresAt()));
        current.put("sessionSha256", hash(session.sessionId()))
                .put("sessionStatus", session.status().name())
                .put("sessionMetadataVerified", true)
                .put("amountMinor", 100)
                .put("currency", "usd");
    }

    ChargeFact inspect() throws IOException {
        require(state.hasNonNull("sessionId"));
        var session = success(checkout.querySession(scope, value("sessionId")));
        require(value("runId").equals(session.metadata().get("pr05_session"))
                && !session.metadata().containsKey("pr05_payment"));
        current.put("sessionSha256", hash(session.sessionId()))
                .put("sessionStatus", session.status().name())
                .put("sessionMetadataVerified", true);
        if (session.status() == CheckoutSessionStatus.OPEN) throw new Pending();
        require(session.status() == CheckoutSessionStatus.COMPLETE && "paid".equals(session.paymentStatus()));
        require(session.paymentIntentId() != null);
        state.put("piId", session.paymentIntentId());
        saveState();
        var pi = success(checkout.queryPaymentIntent(scope, value("piId")));
        require("succeeded".equals(pi.status())
                && pi.amountMinor() == 100
                && Objects.equals(pi.amountReceivedMinor(), 100L)
                && "usd".equalsIgnoreCase(pi.currency()));
        require(value("runId").equals(pi.metadata().get("pr05_payment"))
                && !pi.metadata().containsKey("pr05_session"));
        current.put("piSha256", hash(pi.paymentIntentId()))
                .put("paymentStatus", "succeeded")
                .put("paymentIntentMetadataVerified", true);
        String capture = success(gateway.call(scope, null, c -> {
            var raw = c.fetch(() -> c.client().v1().paymentIntents().retrieve(value("piId"), c.options()));
            StripeGateway.testMode(raw.getLivemode());
            if (!value("piId").equals(raw.getId()) || !"payment_intent".equals(raw.getObject()))
                throw StripeGateway.mismatch();
            return StripeGateway.required(raw.getCaptureMethod());
        }));
        require("automatic".equals(capture));
        current.put("originalCaptureMethod", "automatic");
        var attempts = success(checkout.listChargeAttempts(scope, value("piId")));
        Set<String> ids = new HashSet<>();
        ChargeFact successful = null;
        boolean pending = false;
        ArrayNode charges = current.putArray("charges");
        for (var listed : attempts) {
            require(ids.add(listed.chargeId()));
            var charge = success(checkout.queryCharge(scope, listed.chargeId()));
            require(value("piId").equals(charge.paymentIntentId()));
            var row = charges.addObject()
                    .put("chargeSha256", hash(charge.chargeId()))
                    .put("amountMinor", charge.amountMinor())
                    .put("currency", charge.currency())
                    .put("captured", charge.captured());
            if ("succeeded".equals(charge.status())) {
                require(charge.captured()
                        && charge.amountMinor() == 100
                        && Objects.equals(charge.amountCapturedMinor(), 100L)
                        && "usd".equalsIgnoreCase(charge.currency()));
                require(successful == null);
                successful = charge;
                row.put("status", "succeeded");
                if (charge.balanceTransactionId() == null) pending = true;
            }
            if (charge.balanceTransactionId() != null)
                row.set("balanceTransaction", readBt(charge.balanceTransactionId(), charge.chargeId()));
        }
        require(ids.equals(new HashSet<>(pi.chargeIds())) && ids.contains(pi.latestChargeId()) && successful != null);
        state.put("chargeId", successful.chargeId());
        saveState();
        current.put("chargeAttemptCount", ids.size()).put("amountMinor", 100).put("currency", "usd");
        if (pending) {
            current.put("balanceTransactionStatus", "PENDING");
            throw new Pending();
        }
        current.put("balanceTransactionStatus", "VERIFIED");
        return successful;
    }

    void refund() throws IOException {
        // Re-query the entire successful owned chain before using its persisted charge as a refund target.
        // A delayed payment BT does not make the captured charge unsafe to refund.
        try {
            inspect();
        } catch (Pending pending) {
            require(state.hasNonNull("chargeId"));
        }
        var charge = success(checkout.queryCharge(scope, value("chargeId")));
        require(value("piId").equals(charge.paymentIntentId())
                && charge.captured()
                && "succeeded".equals(charge.status())
                && charge.amountMinor() == 100
                && Objects.equals(charge.amountCapturedMinor(), 100L)
                && "usd".equalsIgnoreCase(charge.currency()));
        if (!state.hasNonNull("refundKey")) {
            state.put("refundKey", "pr05-refund-" + value("runId"));
            saveState();
        }
        RefundFact refund;
        if (state.hasNonNull("refundId")) refund = success(refunds.queryRefund(scope, value("refundId")));
        else {
            refund = success(refunds.createRefund(new CreateRefundCommand(
                    scope, value("refundKey"), charge.chargeId(), null, 100, "requested_by_customer")));
            state.put("refundId", refund.refundId());
            saveState();
            refund = success(refunds.queryRefund(scope, value("refundId")));
        }
        require(charge.chargeId().equals(refund.chargeId())
                && refund.amountMinor() == 100
                && "usd".equalsIgnoreCase(refund.currency()));
        current.put("refundSha256", hash(refund.refundId()))
                .put("refundAmountMinor", 100)
                .put("refundCurrency", "usd");
        require(Set.of("succeeded", "pending", "requires_action", "failed", "canceled")
                .contains(refund.status()));
        current.put("refundStatus", refund.status());
        if (refund.balanceTransactionId() != null)
            current.set("refundBalanceTransaction", readBt(refund.balanceTransactionId(), refund.refundId()));
        require(!Set.of("failed", "canceled").contains(refund.status()));
        if (!"succeeded".equals(refund.status()) || refund.balanceTransactionId() == null) throw new Pending();
    }

    ObjectNode readBt(String id, String source) {
        var bt = success(financial.getBalanceTransaction(scope, id));
        require(id.equals(bt.btId())
                && source.equals(bt.sourceObjectId())
                && Math.subtractExact(bt.amountMinor(), bt.feeMinor()) == bt.netMinor());
        // These are settlement amounts: do not compare JPY settlement units with the USD payment units.
        return JSON.createObjectNode()
                .put("btSha256", hash(bt.btId()))
                .put("sourceSha256", hash(source))
                .put("amountMinor", bt.amountMinor())
                .put("feeMinor", bt.feeMinor())
                .put("netMinor", bt.netMinor())
                .put("currency", bt.currency())
                .put("sourceAssociationVerified", true)
                .put("netArithmeticVerified", true);
    }

    <T> T success(ProviderCallResult<T> result) {
        ObjectNode row = ((ArrayNode) current.get("observations"))
                .addObject()
                .put("classification", result.classification().name());
        var e = result.evidence();
        if (e.httpStatus() != null) row.put("httpStatus", e.httpStatus());
        if (e.providerRequestId() != null && e.providerRequestId().matches("req_[A-Za-z0-9]+"))
            row.put("requestId", e.providerRequestId());
        if (e.rawDigest() != null && e.rawDigest().matches("[a-f0-9]{64}"))
            row.put("redactedEvidenceSha256", e.rawDigest());
        current.put("classification", result.classification().name());
        if (result.classification() != SUCCESS || result.payload() == null) throw new IllegalStateException();
        require(StripeGateway.API_VERSION.equals(e.apiVersion())
                && e.httpStatus() != null
                && e.httpStatus() >= 200
                && e.httpStatus() < 300);
        return result.payload();
    }

    static ObjectNode initialState(Path dir, String base, String account, ProviderScope scope, Instant now)
            throws IOException {
        Path file = dir.resolve("state.local.json");
        String binding = hash(account + "|" + scope);
        if (Files.exists(file)) {
            ObjectNode saved = (ObjectNode) JSON.readTree(file.toFile());
            require(binding.equals(saved.path("bindingSha256").asText()));
            return saved;
        }
        URI uri = URI.create(base);
        require("https".equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null);
        String run = UUID.randomUUID().toString();
        ObjectNode saved = JSON.createObjectNode()
                .put("runId", run)
                .put("operationKey", "pr05-lifecycle-" + run)
                .put(
                        "expiresAt",
                        now.plusSeconds(7200).truncatedTo(ChronoUnit.SECONDS).toString())
                .put("baseUrl", base.replaceAll("/+$", ""))
                .put("bindingSha256", binding);
        Files.createDirectories(dir);
        atomic(file, saved);
        return saved;
    }

    static void serve(Path dir, ProviderScope scope, String account) throws IOException {
        String path = Files.readString(dir.resolve("webhook-path.local")).trim();
        require(path.matches("/webhook/[A-Za-z0-9-]{20,100}"));
        var verifier = new StripeWebhookVerifier(
                scope, account, StripeGateway.API_VERSION, Clock.systemUTC(), Duration.ofMinutes(5));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18888), 0);
        server.createContext("/", exchange -> {
            int status = 404;
            String content = "Not found";
            String requested = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())
                    && Set.of("/health", "/success", "/cancel").contains(requested)) {
                status = 200;
                content = "/health".equals(requested) ? "OK" : "Test checkout return received";
            } else if (path.equals(requested)) {
                status = "POST".equals(exchange.getRequestMethod())
                        ? receive(
                                dir,
                                verifier,
                                exchange.getRequestBody(),
                                exchange.getRequestHeaders().getFirst("Stripe-Signature"))
                        : 405;
                content = status == 200 ? "Verified" : "Not accepted";
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("{\"status\":\"LISTENING\",\"mode\":\"test\",\"port\":18888}");
    }

    static synchronized int receive(Path dir, StripeWebhookVerifier verifier, InputStream input, String signature) {
        try {
            byte[] body = input.readNBytes(1024 * 1024 + 1);
            if (body.length > 1024 * 1024) return 413;
            Path secretFile = dir.resolve("webhook-secret.local");
            if (!Files.isRegularFile(secretFile)) return 503;
            String secret = Files.readString(secretFile).trim();
            if (!secret.startsWith("whsec_") || secret.length() <= 6) return 503;
            var result = verifier.verify(body, signature, secret);
            ObjectNode record = JSON.createObjectNode()
                    .put("receivedAtUtc", Instant.now().toString())
                    .put("classification", result.classification().name())
                    .put("apiVersion", StripeGateway.API_VERSION)
                    .put("mode", "test");
            boolean valid = result.classification() == SUCCESS && result.payload() != null;
            if (valid) {
                var fact = result.payload();
                record.put("eventIdSha256", hash(fact.eventId()))
                        .put("objectIdSha256", hash(fact.objectId()))
                        .put("eventType", EVENT_TYPES.contains(fact.eventType()) ? fact.eventType() : "unrecognized")
                        .put("redactedEvidenceSha256", result.evidence().rawDigest());
                byte[] changed = Arrays.copyOf(body, body.length + 1);
                changed[body.length] = ' ';
                var tampered = verifier.verify(changed, signature, secret);
                boolean rejected = tampered.classification() == ProviderCallClassification.DEFINITIVE_REJECT
                        && tampered.payload() == null;
                record.put("tamperClassification", tampered.classification().name())
                        .put("tamperRejected", rejected);
                valid = rejected;
            }
            byte[] line = (JSON.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
            try (var file = FileChannel.open(
                    dir.resolve("webhook-evidence.jsonl"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            return valid ? 200 : 400;
        } catch (Exception error) {
            return 503;
        }
    }

    String value(String name) {
        String value = state.path(name).asText();
        require(!value.isBlank());
        return value;
    }

    void saveState() throws IOException {
        atomic(dir.resolve("state.local.json"), state);
    }

    static void atomic(Path file, JsonNode value) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        try {
            Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException();
        }
    }

    static String env(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank());
        return value;
    }

    static void require(boolean value) {
        if (!value) throw new IllegalStateException();
    }

    static final class Pending extends RuntimeException {}
}
