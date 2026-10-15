package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.ProviderCallClassification.*;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.ProviderScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in test-mode network smoke. Its evidence is deliberately not a DTO serialization. */
@EnabledIfEnvironmentVariable(named = "STRIPE_CONTRACT_TESTS", matches = "true")
class StripeRealContractIT {
    private final String runId = UUID.randomUUID().toString();
    private final Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Path evidenceFile = Path.of("target", "stripe-contract", runId + ".json");
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
    private ProviderScope scope;
    private StripeCheckoutAdapter checkout;
    private StripeFinancialReadAdapter financial;
    private StripeRefundAdapter refunds;
    private String createdSessionId;

    @Test
    void testModeContractEvidence() throws Exception {
        evidence.put("runId", runId);
        evidence.put("startedAtUtc", started.toString());
        evidence.put("sdkVersion", StripeGateway.SDK_VERSION);
        evidence.put("apiVersion", StripeGateway.API_VERSION);
        evidence.put("livemode", false);
        evidence.put("cases", cases);
        for (String name : List.of(
                "configuration",
                "checkoutCreate",
                "checkoutQuery",
                "checkoutExpireCleanup",
                "completedCheckoutChain",
                "refundRead",
                "disputeRead",
                "payoutRead",
                "balanceRead",
                "balanceTransactionList",
                "balanceTransactionContinuation",
                "reports",
                "refundCreate",
                "endpointSignature",
                "metadataSession",
                "metadataPaymentIntent",
                "automaticCapture",
                "cancellationRace",
                "decline",
                "authenticationRequired",
                "multipleChargeAttempts")) {
            cases.put(name, new LinkedHashMap<>(Map.of("status", "NOT_RUN")));
        }
        blocked("refundCreate", "explicit_refund_opt_in_not_yet_verified");
        blocked("metadataSession", "created_and_queried_session_metadata_not_yet_verified");
        blocked("metadataPaymentIntent", "smoke_session_expires_without_payment_intent_creation_proof");
        blocked("automaticCapture", "original_payment_intent_capture_method_unverified");
        blocked("cancellationRace", "manual_real_fixture_required");
        blocked("balanceTransactionContinuation", "single_bounded_page_does_not_verify_continuation");
        blocked("reports", "CAPABILITY_UNAVAILABLE_by_design_unverified");
        for (String name : List.of("endpointSignature", "decline", "authenticationRequired", "multipleChargeAttempts"))
            blocked(name, "manual_real_fixture_required");
        try {
            runCase("configuration", () -> {
                String apiKey = requiredEnv("STRIPE_API_KEY");
                check(
                        apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_"),
                        "STRIPE_API_KEY must be test mode");
                String account = requiredEnv("STRIPE_ACCOUNT_ID");
                check(account.matches("acct_[A-Za-z0-9]+"), "STRIPE_ACCOUNT_ID invalid");
                scope = new ProviderScope(
                        uuidEnv("STRIPE_ORGANIZATION_ID"),
                        uuidEnv("STRIPE_ENVIRONMENT_ID"),
                        uuidEnv("STRIPE_PROVIDER_ACCOUNT_ID"),
                        false);
                evidence.put("organizationId", scope.organizationId().toString());
                evidence.put("environmentId", scope.environmentId().toString());
                evidence.put("providerAccountId", scope.providerAccountId().toString());
                evidence.put("stripeAccountSha256", hash(account));
                var gateway = new StripeGateway(apiKey, scope, account, Clock.systemUTC());
                checkout = new StripeCheckoutAdapter(gateway);
                financial = new StripeFinancialReadAdapter(gateway);
                refunds = new StripeRefundAdapter(gateway);
            });
            if (checkout != null) {
                checkoutSmoke();
                optional("completedCheckoutChain", "STRIPE_CONTRACT_SESSION_ID", this::completedCheckoutChain);
                optional("refundRead", "STRIPE_CONTRACT_REFUND_ID", id -> {
                    var fact = success("refundRead", refunds.queryRefund(scope, id));
                    check(id.equals(fact.refundId()) && fact.amountMinor() > 0, "refund facts");
                    reference("refundRead", fact.refundId());
                    readBt("refundRead", fact.balanceTransactionId(), fact.refundId());
                    readBt("refundRead", fact.failureBalanceTransactionId(), null);
                });
                refundCreation();
                optional("disputeRead", "STRIPE_CONTRACT_DISPUTE_ID", id -> {
                    var fact = success("disputeRead", financial.getDispute(scope, id));
                    check(id.equals(fact.disputeId()) && fact.amountMinor() > 0, "dispute facts");
                    reference("disputeRead", fact.disputeId());
                    cases.get("disputeRead")
                            .put(
                                    "balanceTransactionCount",
                                    fact.balanceTransactionIds().size());
                    for (String bt : fact.balanceTransactionIds()) readBt("disputeRead", bt, null);
                });
                optional("payoutRead", "STRIPE_CONTRACT_PAYOUT_ID", id -> {
                    var fact = success("payoutRead", financial.getPayout(scope, id));
                    check(id.equals(fact.payoutId()) && fact.amountMinor() > 0, "payout facts");
                    reference("payoutRead", fact.payoutId());
                    readBt("payoutRead", fact.balanceTransactionId(), fact.payoutId());
                    readBt("payoutRead", fact.failureBalanceTransactionId(), null);
                });
                runCase("balanceRead", () -> {
                    String currency = System.getenv()
                            .getOrDefault("STRIPE_CONTRACT_BALANCE_CURRENCY", "usd")
                            .toLowerCase(Locale.ROOT);
                    check(currency.matches("[a-z]{3}"), "invalid_STRIPE_CONTRACT_BALANCE_CURRENCY");
                    var fact = success("balanceRead", financial.getBalance(scope, currency, "PAYMENTS"));
                    check(currency.equals(fact.currency()) && "PAYMENTS".equals(fact.balanceDomain()), "balance facts");
                    cases.get("balanceRead").put("currency", currency);
                });
                runCase("balanceTransactionList", () -> {
                    Instant until = started.plusSeconds(1);
                    Instant from = started.minus(1, ChronoUnit.DAYS);
                    var page = success(
                            "balanceTransactionList",
                            financial.listBalanceTransactions(
                                    new ListBalanceTransactionsQuery(scope, from, until, null, 5, null)));
                    check(page.items().size() <= 5, "balance transaction page size");
                    for (var fact : page.items()) {
                        check(
                                !fact.createdAt().isBefore(from)
                                        && fact.createdAt().isBefore(until),
                                "list window");
                        check(
                                Math.subtractExact(fact.amountMinor(), fact.feeMinor()) == fact.netMinor(),
                                "balance transaction arithmetic");
                        reference("balanceTransactionList", fact.btId());
                    }
                    cases.get("balanceTransactionList")
                            .put("itemCount", page.items().size());
                    cases.get("balanceTransactionList").put("hasMore", page.hasMore());
                    cases.get("balanceTransactionList").put("windowFromUtc", from.toString());
                    cases.get("balanceTransactionList").put("windowUntilUtc", until.toString());
                    if (page.items().isEmpty()) throw new Blocked("empty_window_no_transaction_facts_verified");
                });
                runCase("reports", () -> {
                    var result = financial.requestReport(
                            new ReportRequest(scope, "historical_balance", started.minus(1, ChronoUnit.DAYS), started));
                    observation("reports", result);
                    check(
                            result.classification() == CAPABILITY_UNAVAILABLE && result.payload() == null,
                            "reports must remain unavailable");
                    throw new Blocked("CAPABILITY_UNAVAILABLE_by_design_unverified");
                });
            }
        } finally {
            // Even a query/assertion failure must not leave a known open session behind.
            if (createdSessionId != null && checkout != null) {
                runCase("checkoutExpireCleanup", () -> {
                    var fact = success("checkoutExpireCleanup", checkout.expireSession(scope, createdSessionId));
                    check(
                            createdSessionId.equals(fact.sessionId()) && fact.status() == CheckoutSessionStatus.EXPIRED,
                            "expired session facts");
                    reference("checkoutExpireCleanup", fact.sessionId());
                });
                if (!"VERIFIED".equals(cases.get("checkoutExpireCleanup").get("status"))) {
                    cases.get("checkoutExpireCleanup").put("status", "FAILED");
                    cases.get("checkoutExpireCleanup").put("reason", "cleanup_not_verified_manual_check_required");
                }
            } else {
                blocked("checkoutExpireCleanup", "no_known_created_session_manual_check_if_timeout");
            }
            evidence.put("finishedAtUtc", Instant.now().toString());
            evidence.put(
                    "overallStatus",
                    cases.values().stream().anyMatch(c -> "FAILED".equals(c.get("status")))
                            ? "FAILED"
                            : "PARTIAL_VERIFICATION");
            writeEvidence();
        }
        if (cases.values().stream().anyMatch(c -> "FAILED".equals(c.get("status"))))
            fail("Stripe contract case failed; inspect allowlisted evidence: " + evidenceFile);
    }

    private void checkoutSmoke() {
        Instant expiresAt = started.plus(1, ChronoUnit.HOURS);
        String key = "contract-checkout-" + runId;
        boolean[] metadataChecked = {false, false};
        runCase("checkoutCreate", () -> {
            // Persist the generated non-secret operation key and exact absolute expiry BEFORE sending.
            // No arbitrary idempotency key or metadata from provider evidence is serialized.
            evidence.put(
                    "checkoutOperation",
                    Map.of(
                            "operationKey",
                            key,
                            "expiresAtUtc",
                            expiresAt.toString(),
                            "amountMinor",
                            100L,
                            "currency",
                            "usd",
                            "commandTemplate",
                            "checkout-smoke-v2"));
            writeEvidence();
            var result = checkout.createSession(new CreateCheckoutSessionCommand(
                    scope,
                    key,
                    100,
                    "usd",
                    "https://example.com/success",
                    "https://example.com/cancel",
                    Map.of("pr05_session", runId),
                    Map.of("pr05_payment", runId),
                    expiresAt));
            // Capture cleanup identity before checking any returned facts.
            if (result.payload() != null) createdSessionId = result.payload().sessionId();
            var fact = success("checkoutCreate", result);
            check(
                    fact.status() == CheckoutSessionStatus.OPEN && expiresAt.equals(fact.expiresAt()),
                    "created session facts");
            check(
                    fact.hostedUrl() != null && fact.hostedUrl().startsWith("https://checkout.stripe.com/"),
                    "hosted checkout");
            check(key.equals(result.evidence().idempotencyKeyUsed()), "create operation key");
            checkSessionMetadata(fact);
            metadataChecked[0] = true;
            reference("checkoutCreate", fact.sessionId());
        });
        if (createdSessionId != null)
            runCase("checkoutQuery", () -> {
                var fact = success("checkoutQuery", checkout.querySession(scope, createdSessionId));
                check(
                        createdSessionId.equals(fact.sessionId()) && fact.status() == CheckoutSessionStatus.OPEN,
                        "queried session facts");
                checkSessionMetadata(fact);
                metadataChecked[1] = true;
                reference("checkoutQuery", fact.sessionId());
            });
        if (metadataChecked[0] && metadataChecked[1])
            runCase("metadataSession", () -> {
                cases.get("metadataSession").put("checkedCreateAndQuery", true);
                reference("metadataSession", createdSessionId);
            });
    }

    private void checkSessionMetadata(CheckoutSessionFact fact) {
        check(
                runId.equals(fact.metadata().get("pr05_session"))
                        && !fact.metadata().containsKey("pr05_payment"),
                "session metadata marker and payment intent metadata isolation");
    }

    private void refundCreation() {
        String name = "refundCreate";
        var required = List.of(
                "STRIPE_CONTRACT_REFUND_CHARGE_ID",
                "STRIPE_CONTRACT_REFUND_AMOUNT_MINOR",
                "STRIPE_CONTRACT_REFUND_OPERATION_KEY");
        var missing = required.stream()
                .filter(variable -> System.getenv(variable) == null
                        || System.getenv(variable).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            blocked(name, "missing_" + String.join("_", missing));
            return;
        }
        runCase(name, () -> {
            String chargeId = requiredEnv("STRIPE_CONTRACT_REFUND_CHARGE_ID");
            check(chargeId.matches("ch_[A-Za-z0-9]+"), "invalid_STRIPE_CONTRACT_REFUND_CHARGE_ID");
            String key = requiredEnv("STRIPE_CONTRACT_REFUND_OPERATION_KEY");
            check(
                    key.matches("pr05-refund-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),
                    "invalid_STRIPE_CONTRACT_REFUND_OPERATION_KEY");
            long amount;
            try {
                amount = Long.parseLong(requiredEnv("STRIPE_CONTRACT_REFUND_AMOUNT_MINOR"));
            } catch (NumberFormatException error) {
                throw new SafeFailure("invalid_STRIPE_CONTRACT_REFUND_AMOUNT_MINOR");
            }
            check(amount > 0, "invalid_STRIPE_CONTRACT_REFUND_AMOUNT_MINOR");
            // The operator retains the original target externally; only its hash belongs in this artifact.
            // Persist the stable key and exact request parameters before this explicitly authorized mutation.
            evidence.put(
                    "refundOperation",
                    Map.of(
                            "operationKey",
                            key,
                            "amountMinor",
                            amount,
                            "reason",
                            "requested_by_customer",
                            "chargeSha256",
                            hash(chargeId)));
            writeEvidence();
            var result = refunds.createRefund(
                    new CreateRefundCommand(scope, key, chargeId, null, amount, "requested_by_customer"));
            var created = success(name, result);
            check(key.equals(result.evidence().idempotencyKeyUsed()), "refund operation key");
            check(chargeId.equals(created.chargeId()) && created.amountMinor() == amount, "created refund facts");
            reference(name, created.refundId());
            var queried = success(name, refunds.queryRefund(scope, created.refundId()));
            check(
                    created.refundId().equals(queried.refundId())
                            && chargeId.equals(queried.chargeId())
                            && queried.amountMinor() == amount,
                    "queried refund facts");
            cases.get(name).put("balanceTransactionPresent", queried.balanceTransactionId() != null);
            cases.get(name).put("failureBalanceTransactionPresent", queried.failureBalanceTransactionId() != null);
            if (Set.of("pending", "requires_action", "succeeded", "failed", "canceled")
                    .contains(queried.status())) cases.get(name).put("refundStatus", queried.status());
            // Pending refund financial references remain absent; there is no retry or second refund.
            readBt(name, queried.balanceTransactionId(), queried.refundId());
            readBt(name, queried.failureBalanceTransactionId(), null);
        });
    }

    private void completedCheckoutChain(String id) {
        String name = "completedCheckoutChain";
        var session = success(name, checkout.querySession(scope, id));
        check(
                id.equals(session.sessionId())
                        && session.status() == CheckoutSessionStatus.COMPLETE
                        && "paid".equals(session.paymentStatus())
                        && session.paymentIntentId() != null,
                "completed paid session fixture");
        reference(name, session.sessionId());
        var pi = success(name, checkout.queryPaymentIntent(scope, session.paymentIntentId()));
        check(
                session.paymentIntentId().equals(pi.paymentIntentId()) && "succeeded".equals(pi.status()),
                "succeeded intent association");
        reference(name, pi.paymentIntentId());
        var attempts = success(name, checkout.listChargeAttempts(scope, pi.paymentIntentId()));
        var chargeIds = new HashSet<String>();
        int btCount = 0;
        int succeededChargeCount = 0;
        boolean successfulChargeMissingBt = false;
        for (var listed : attempts) {
            check(chargeIds.add(listed.chargeId()), "unique charge attempts");
            var charge = success(name, checkout.queryCharge(scope, listed.chargeId()));
            check(
                    pi.paymentIntentId().equals(charge.paymentIntentId())
                            && listed.chargeId().equals(charge.chargeId()),
                    "charge association");
            if ("succeeded".equals(charge.status())) {
                check(
                        charge.captured()
                                && charge.amountCapturedMinor() != null
                                && charge.amountCapturedMinor() == charge.amountMinor(),
                        "succeeded charge fully captured");
                succeededChargeCount++;
            }
            reference(name, charge.chargeId());
            if (charge.balanceTransactionId() != null) {
                readBt(name, charge.balanceTransactionId(), charge.chargeId());
                btCount++;
            } else if ("succeeded".equals(charge.status())) {
                successfulChargeMissingBt = true;
            }
        }
        check(
                !chargeIds.isEmpty()
                        && chargeIds.equals(new HashSet<>(pi.chargeIds()))
                        && chargeIds.contains(pi.latestChargeId()),
                "complete intent charge enumeration");
        check(succeededChargeCount >= 1, "completed checkout has a succeeded captured charge");
        cases.get(name).put("chargeAttemptCount", chargeIds.size());
        cases.get(name).put("succeededCapturedChargeCount", succeededChargeCount);
        cases.get(name).put("balanceTransactionCount", btCount);
        if (btCount == 0 || successfulChargeMissingBt) throw new Blocked("balance_transaction_not_yet_available");
        // Seeing several attempts here does not prove a specific decline/authentication scenario.
        // Captured results do not prove the original PaymentIntent capture_method was automatic.
    }

    private void readBt(String name, String id, String source) {
        if (id == null) return;
        var fact = success(name, financial.getBalanceTransaction(scope, id));
        check(
                id.equals(fact.btId()) && Math.subtractExact(fact.amountMinor(), fact.feeMinor()) == fact.netMinor(),
                "balance transaction facts");
        if (source != null) check(source.equals(fact.sourceObjectId()), "balance transaction source association");
        reference(name, fact.btId());
    }

    private <T> T success(String name, ProviderCallResult<T> result) {
        observation(name, result);
        if (result.classification() == CAPABILITY_UNAVAILABLE) throw new Blocked("CAPABILITY_UNAVAILABLE");
        check(result.classification() == SUCCESS && result.payload() != null, "expected normalized SUCCESS");
        check(
                result.evidence().httpStatus() != null
                        && result.evidence().httpStatus() >= 200
                        && result.evidence().httpStatus() < 300,
                "successful HTTP evidence");
        check(StripeGateway.API_VERSION.equals(result.evidence().apiVersion()), "pinned API evidence");
        return result.payload();
    }

    @SuppressWarnings("unchecked")
    private void observation(String name, ProviderCallResult<?> result) {
        var entries =
                (List<Map<String, Object>>) cases.get(name).computeIfAbsent("observations", k -> new ArrayList<>());
        var item = new LinkedHashMap<String, Object>();
        item.put("classification", result.classification().name());
        item.put("httpStatus", result.evidence().httpStatus());
        item.put("requestedAtUtc", result.evidence().requestedAt().toString());
        if (result.evidence().respondedAt() != null)
            item.put("respondedAtUtc", result.evidence().respondedAt().toString());
        String digest = result.evidence().rawDigest();
        if (digest != null && digest.matches("[a-f0-9]{64}")) item.put("redactedEvidenceSha256", digest);
        String requestId = result.evidence().providerRequestId();
        if (requestId != null && requestId.matches("req_[A-Za-z0-9]+")) item.put("requestId", requestId);
        entries.add(item);
    }

    @SuppressWarnings("unchecked")
    private void reference(String name, String id) {
        var refs = (List<String>) cases.get(name).computeIfAbsent("objectSha256", k -> new ArrayList<>());
        refs.add(hash(id));
    }

    private void optional(String name, String env, Fixture fixture) {
        String id = System.getenv(env);
        if (id == null || id.isBlank()) blocked(name, "missing_" + env);
        else runCase(name, () -> fixture.run(id));
    }

    private void runCase(String name, Checked action) {
        try {
            action.run();
            cases.get(name).put("status", "VERIFIED");
            cases.get(name).remove("reason");
        } catch (Blocked blocked) {
            blocked(name, blocked.reason);
        } catch (Exception | AssertionError error) {
            cases.get(name).put("status", "FAILED");
            // Never serialize exception messages, causes, assertions' values, SDK DTOs, or evidence.
            cases.get(name)
                    .put(
                            "reason",
                            error instanceof SafeFailure ? error.getMessage() : "case_failed_details_suppressed");
        }
    }

    private void blocked(String name, String reason) {
        cases.get(name).put("status", "BLOCKED");
        cases.get(name).put("reason", reason);
    }

    private void writeEvidence() throws Exception {
        Files.createDirectories(evidenceFile.getParent());
        Path temporary = evidenceFile.resolveSibling(runId + ".tmp");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), evidence);
        Files.move(temporary, evidenceFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new SafeFailure("missing_" + name);
        return value;
    }

    private static UUID uuidEnv(String name) {
        String value = requiredEnv(name);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new SafeFailure("invalid_" + name);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new SafeFailure("hash_unavailable");
        }
    }

    private static void check(boolean condition, String safeMessage) {
        if (!condition) throw new SafeFailure(safeMessage);
    }

    private interface Checked {
        void run() throws Exception;
    }

    private interface Fixture {
        void run(String id);
    }

    private static final class SafeFailure extends RuntimeException {
        SafeFailure(String message) {
            super(message);
        }
    }

    private static final class Blocked extends RuntimeException {
        final String reason;

        Blocked(String reason) {
            this.reason = reason;
        }
    }
}
