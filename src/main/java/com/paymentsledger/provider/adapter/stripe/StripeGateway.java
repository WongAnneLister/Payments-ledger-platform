package com.paymentsledger.provider.adapter.stripe;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.PaymentIntentFact;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.exception.*;
import com.stripe.model.StripeObject;
import com.stripe.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.paymentsledger.provider.ProviderCallClassification.*;

/** One credential bound to one trusted test-account scope. No SDK globals or business persistence. */
public final class StripeGateway {
    public static final String API_VERSION = "2026-07-29.dahlia";
    public static final String SDK_VERSION = "33.3.0";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final StripeClient client;
    private final ProviderScope scope;
    private final String accountId;
    private final Clock clock;
    private volatile boolean accountVerified;

    public StripeGateway(String apiKey, ProviderScope scope, String accountId, Clock clock) {
        this(apiKey, scope, accountId, clock, null);
    }

    // Only deterministic contract tests inject a transport; production always uses Stripe's API host.
    StripeGateway(String apiKey, ProviderScope scope, String accountId, Clock clock, HttpClient transport) {
        if (apiKey == null || !(apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_")))
            throw new IllegalArgumentException("A Stripe test credential is required");
        this.scope = Objects.requireNonNull(scope);
        if (scope.livemode()) throw new IllegalArgumentException("Live scope is unsupported");
        if (accountId == null || !accountId.matches("acct_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("A bound Stripe account ID is required");
        if (!SDK_VERSION.equals(Stripe.VERSION) || !API_VERSION.equals(Stripe.API_VERSION))
            throw new IllegalStateException("Stripe dependency differs from the frozen contract");
        this.accountId = accountId;
        this.clock = Objects.requireNonNull(clock);
        var builder = StripeClient.builder().setApiKey(apiKey).setConnectTimeout(5000)
                .setReadTimeout(15000).setMaxNetworkRetries(0);
        if (transport != null) builder.setHttpClient(transport);
        client = builder.build();
    }

    void guard(ProviderScope requested) {
        if (!scope.equals(requested)) throw new IllegalArgumentException("Provider scope differs from credential binding");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Stripe calls must execute outside local transactions and business locks");
    }

    @FunctionalInterface interface Work<T> { T run(Call call) throws StripeException; }
    @FunctionalInterface interface Fetch<T extends StripeObject> { T get() throws StripeException; }

    <T> ProviderCallResult<T> call(ProviderScope requested, String key, Work<T> work) {
        guard(requested);
        if (key != null && (key.isBlank() || key.length() > 255))
            throw new IllegalArgumentException("Operation key must contain 1 to 255 characters");
        var call = new Call(clock.instant());
        try {
            if (!accountVerified) {
                var account = call.fetch(() -> client.v1().accounts().retrieveCurrent(call.options()));
                if (!accountId.equals(account.getId())) throw mismatch();
                accountVerified = true;
            }
            call.key = key;
            T fact = work.run(call);
            if (fact == null) throw mismatch();
            var classification = fact instanceof PaymentIntentFact pi && "requires_action".equals(pi.status())
                    ? AUTH_REQUIRED : SUCCESS;
            return new ProviderCallResult<>(classification, fact, call.evidence(null, fact));
        } catch (Signal signal) {
            return new ProviderCallResult<>(signal.classification, null, call.evidence(signal.code, null));
        } catch (StripeException error) {
            call.requestId = error.getRequestId();
            call.status = error instanceof ApiConnectionException ? null : error.getStatusCode();
            call.responded = error instanceof ApiConnectionException ? null : clock.instant();
            call.usedKey = call.sentKey;
            String code = error.getCode();
            return new ProviderCallResult<>(classify(error), null, call.evidence(code, null));
        } catch (RuntimeException error) {
            // SDK deserialization / required-field failures: never leak its raw response or message.
            return new ProviderCallResult<>(CONTRACT_MISMATCH, null, call.evidence("stripe_contract_mismatch", null));
        }
    }

    <T> ProviderCallResult<T> unavailable(ProviderScope requested, String code) {
        guard(requested);
        return new ProviderCallResult<>(CAPABILITY_UNAVAILABLE, null, new Call(clock.instant()).evidence(code, null));
    }

    private static ProviderCallClassification classify(StripeException error) {
        Integer status = error.getStatusCode();
        if (error instanceof ApiConnectionException) return TIMEOUT_UNKNOWN;
        if ("idempotency_key_in_use".equals(error.getCode())) return RETRYABLE_TRANSIENT;
        if (error instanceof IdempotencyException) return IDEMPOTENCY_CONFLICT;
        if ("authentication_required".equals(error.getCode())) return AUTH_REQUIRED;
        if (status == null) return CONTRACT_MISMATCH;
        if (status == 429) return RATE_LIMITED;
        if (status >= 500 || status == 409 || status == 424) return RETRYABLE_TRANSIENT;
        if (status == 404 && "resource_missing".equals(error.getCode())) return NOT_FOUND;
        if (status == 401 || status == 403) return CAPABILITY_UNAVAILABLE;
        if (status == 400 || status == 402 || status == 404) return DEFINITIVE_REJECT;
        return CONTRACT_MISMATCH;
    }

    final class Call {
        private final Instant started;
        private Instant responded;
        private String key, sentKey, usedKey, requestId;
        private Integer status;
        private final List<Map<String,Object>> observations = new ArrayList<>();
        Call(Instant started) { this.started = started; }
        StripeClient client() { return client; }
        Clock clock() { return clock; }
        RequestOptions options() {
            return RequestOptions.builder().setMaxNetworkRetries(0).setConnectTimeout(5000)
                    .setReadTimeout(15000).setIdempotencyKey(key).build();
        }
        <T extends StripeObject> T fetch(Fetch<T> fetch) throws StripeException {
            sentKey = null; usedKey = null;
            requestId = null; status = null; responded = null;
            if (!ProviderRequestBudget.tryAcquire())
                throw new Signal(RETRYABLE_TRANSIENT,"query_budget_exhausted");
            sentKey = key; usedKey = key;
            T result = fetch.get();
            responded = clock.instant();
            if (result == null || result.getLastResponse() == null) throw mismatch();
            var response = result.getLastResponse();
            requestId = response.requestId(); status = response.code();
            observations.add(Map.of("requestId", Objects.toString(requestId,""), "httpStatus",status));
            var version = response.headers().firstValue("Stripe-Version");
            if (version.isPresent() && !API_VERSION.equals(version.get())) throw mismatch();
            return result;
        }
        ProviderEvidence evidence(String code, Object fact) {
            var summary = new LinkedHashMap<String,Object>();
            summary.put("scope",scope); summary.put("stripeAccountId",accountId);
            summary.put("responses",observations); summary.put("errorCode",code);
            summary.put("fact",fact);
            return new ProviderEvidence(requestId,usedKey,started,responded,status,code,digest(summary),API_VERSION);
        }
    }

    static Signal mismatch() { return new Signal(CONTRACT_MISMATCH,"stripe_contract_mismatch"); }
    static Signal empty() { return new Signal(TRANSIENT_EMPTY,"stripe_query_empty"); }
    static Signal unavailable(String code) { return new Signal(CAPABILITY_UNAVAILABLE,code); }
    static Signal reject(String code) { return new Signal(DEFINITIVE_REJECT,code); }
    static final class Signal extends RuntimeException {
        final ProviderCallClassification classification; final String code;
        Signal(ProviderCallClassification classification, String code) { super(code); this.classification=classification; this.code=code; }
    }
    static void testMode(Boolean mode) { if (!Boolean.FALSE.equals(mode)) throw mismatch(); }
    static String required(String value) { if (value == null || value.isBlank()) throw mismatch(); return value; }
    static Instant instant(Long value) { if (value == null) throw mismatch(); return Instant.ofEpochSecond(value); }
    static Map<String,String> metadata(Map<String,String> value) { return value == null ? Map.of() : value; }
    static String digest(Object value) {
        try {
            JsonNode tree = JSON.valueToTree(value);
            redact(tree);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Cannot construct redacted evidence"); }
    }
    private static void redact(JsonNode tree) {
        if (tree instanceof ObjectNode object) object.remove(List.of("metadata","hostedUrl","description"));
        for (JsonNode child : tree) redact(child);
    }
}
