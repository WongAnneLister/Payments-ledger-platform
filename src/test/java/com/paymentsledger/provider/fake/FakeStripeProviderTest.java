package com.paymentsledger.provider.fake;

import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.ProviderModels.*;
import static com.paymentsledger.provider.fake.FailureScript.*;
import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FakeStripeProviderTest {
    @TempDir
    Path directory;

    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    final ProviderScope scope = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), false);
    FakeStripeProvider fake;

    @BeforeEach
    void setup() {
        fake = open();
    }

    FakeStripeProvider open() {
        return new FakeStripeProvider(directory, 42, START, Duration.ofMinutes(10));
    }

    CreateCheckoutSessionCommand command(String key) {
        return new CreateCheckoutSessionCommand(
                scope,
                key,
                10000,
                "USD",
                "https://example.test/success",
                "https://example.test/cancel",
                Map.of("sessionOnly", "s"),
                Map.of("order", "o"),
                START.plusSeconds(3600));
    }

    CheckoutSessionFact session(String key) {
        return fake.createSession(command(key)).payload();
    }

    ChargeFact captured(String key) {
        var session = session(key);
        var pi = fake.controls().attemptPayment(scope, session.sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        return fake.queryCharge(scope, pi.latestChargeId()).payload();
    }

    @Test
    void checkoutIsNotCaptureAndMetadataDoesNotAutomaticallyPropagate() {
        var result = fake.createSession(command("one"));
        assertThat(result.classification()).isEqualTo(SUCCESS);
        var pi = fake.queryPaymentIntent(scope, result.payload().paymentIntentId())
                .payload();
        assertThat(pi.metadata()).containsExactlyEntriesOf(Map.of("order", "o"));
        assertThat(result.payload().metadata()).containsKey("sessionOnly");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
        assertThat(result.evidence().rawDigest()).hasSize(64);
        assertThat(result.evidence().apiVersion()).isEqualTo("FAKE-PR04-v1");
    }

    @Test
    void retainedKeyReplaysConflictingKeyRejectsExpiredKeyCreatesNewSession() {
        var a = session("key");
        assertThat(session("key")).isEqualTo(a);
        var c = command("key");
        var changed = new CreateCheckoutSessionCommand(
                scope,
                "key",
                10001,
                "USD",
                c.successUrl(),
                c.cancelUrl(),
                c.sessionMetadata(),
                c.paymentIntentMetadata(),
                c.expiresAt());
        assertThat(fake.createSession(changed).classification()).isEqualTo(IDEMPOTENCY_CONFLICT);
        fake.clock().advance(Duration.ofMinutes(10));
        assertThat(session("key").sessionId()).isNotEqualTo(a.sessionId());
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(2);
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(2);
    }

    @Test
    void newExpiryMustBeInProviderWindowButRetainedReplayIgnoresElapsedClock() {
        fake = new FakeStripeProvider(directory.resolve("long-retention"), 42, START, Duration.ofHours(2));
        var original = session("expiry-replay");
        fake.clock().advance(Duration.ofHours(1));
        assertThat(session("expiry-replay")).isEqualTo(original);
        assertThat(fake.createSession(command("new-expired")).classification()).isEqualTo(DEFINITIVE_REJECT);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }

    @Test
    void absoluteExpirySurvivesDelayedReplayAndRestart() {
        var original = session("expiry-key");
        fake.clock().advance(Duration.ofMinutes(5));
        fake = open();
        assertThat(session("expiry-key")).isEqualTo(original);
        assertThat(original.expiresAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        var c = command("expiry-key");
        var changed = new CreateCheckoutSessionCommand(
                scope,
                c.operationIdempotencyKey(),
                c.amountMinor(),
                c.currency(),
                c.successUrl(),
                c.cancelUrl(),
                c.sessionMetadata(),
                c.paymentIntentMetadata(),
                c.expiresAt().plusSeconds(1));
        assertThat(fake.createSession(changed).classification()).isEqualTo(IDEMPOTENCY_CONFLICT);
    }

    @Test
    void acceptedResponseLossPersistsObjectAndReplayReturnsOriginal() {
        fake.controls().enqueue(scope, Operation.CREATE_SESSION, "key", Outcome.RESPONSE_LOST);
        var lost = fake.createSession(command("key"));
        assertThat(lost.classification()).isEqualTo(TIMEOUT_UNKNOWN);
        assertThat(lost.payload()).isNull();
        assertThat(lost.evidence().respondedAt()).isNull();
        assertThat(lost.evidence().idempotencyKeyUsed()).isEqualTo("key");
        fake = open();
        var recovered = session("key");
        assertThat(recovered).isNotNull();
        assertThat(fake.counters(scope).requestCount()).isEqualTo(2);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.controls().calls(scope)).hasSize(2);
    }

    @Test
    void beforeSendFailureDoesNotAcceptAndScriptSurvivesRestart() {
        fake.controls().enqueue(scope, Operation.CREATE_SESSION, "key", Outcome.BEFORE_SEND_FAILURE);
        fake = open();
        assertThat(fake.createSession(command("key")).classification()).isEqualTo(RETRYABLE_TRANSIENT);
        assertThat(fake.counters(scope).acceptedCount()).isZero();
        assertThat(fake.counters(scope).sessionCreatedCount()).isZero();
        assertThat(session("key")).isNotNull();
    }

    @Test
    void everyReadFailureIsClassifiedAndDoesNotEraseRemoteFact() {
        var s = session("key");
        Map<Outcome, ProviderCallClassification> expectations = Map.of(
                Outcome.TRANSIENT_EMPTY,
                TRANSIENT_EMPTY,
                Outcome.TRANSIENT_ERROR,
                RETRYABLE_TRANSIENT,
                Outcome.RATE_LIMIT,
                RATE_LIMITED,
                Outcome.REJECT,
                DEFINITIVE_REJECT,
                Outcome.CONTRACT_MISMATCH,
                CONTRACT_MISMATCH);
        expectations.forEach((outcome, classification) -> {
            fake.controls().enqueue(scope, Operation.QUERY_SESSION, s.sessionId(), outcome);
            var result = fake.querySession(scope, s.sessionId());
            assertThat(result.classification()).isEqualTo(classification);
            assertThat(result.evidence()).isNotNull();
            assertThat(result.payload()).isNull();
        });
        assertThat(fake.querySession(scope, s.sessionId()).payload()).isEqualTo(s);
    }

    @Test
    void cachedTransientResponsePersistsUntilRetentionExpires() {
        fake.controls().enqueue(scope, Operation.CREATE_SESSION, "cached", Outcome.CACHED_TRANSIENT_ERROR);
        assertThat(fake.createSession(command("cached")).classification()).isEqualTo(RETRYABLE_TRANSIENT);
        fake = open();
        assertThat(fake.createSession(command("cached")).classification()).isEqualTo(RETRYABLE_TRANSIENT);
        assertThat(fake.counters(scope).sessionCreatedCount()).isZero();
        fake.clock().advance(Duration.ofMinutes(10));
        assertThat(fake.createSession(command("cached")).classification()).isEqualTo(SUCCESS);
    }

    @Test
    void authenticationFailedAttemptAndSuccessPreserveAllAttempts() {
        var s = session("key");
        var auth = fake.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.REQUIRE_AUTH);
        assertThat(auth.nextActionType()).isEqualTo("use_fake_sdk");
        var failed = fake.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.DECLINE);
        var paid = fake.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        assertThat(paid.chargeIds()).hasSize(2).contains(failed.latestChargeId());
        assertThat(fake.listChargeAttempts(scope, paid.paymentIntentId()).payload())
                .hasSize(2);
        assertThat(fake.querySession(scope, s.sessionId()).payload().status())
                .isEqualTo(CheckoutSessionStatus.COMPLETE);
        assertThat(fake.expireSession(scope, s.sessionId()).classification()).isEqualTo(DEFINITIVE_REJECT);
        fake.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
    }

    @Test
    void explicitAndTimedExpiryPreventLaterCapture() {
        var s = session("expire");
        assertThat(fake.expireSession(scope, s.sessionId()).payload().status())
                .isEqualTo(CheckoutSessionStatus.EXPIRED);
        assertThatThrownBy(
                        () -> fake.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.SUCCEED))
                .isInstanceOf(IllegalStateException.class);
        var t = session("timed");
        fake.clock().advance(Duration.ofHours(1));
        assertThat(fake.querySession(scope, t.sessionId()).payload().status()).isEqualTo(CheckoutSessionStatus.EXPIRED);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
    }

    @Test
    void concurrentExpireAndCaptureHaveOneSerializedWinner() throws Exception {
        var s = session("race");
        var second = open();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                return fake.expireSession(scope, s.sessionId());
            });
            var b = executor.submit(() -> {
                start.await();
                try {
                    return second.controls().attemptPayment(scope, s.sessionId(), FakeControls.AttemptOutcome.SUCCEED);
                } catch (IllegalStateException e) {
                    return null;
                }
            });
            start.countDown();
            a.get();
            b.get();
        }
        var status = fake.querySession(scope, s.sessionId()).payload().status();
        assertThat(status).isIn(CheckoutSessionStatus.COMPLETE, CheckoutSessionStatus.EXPIRED);
        assertThat(fake.counters(scope).paymentEffectCount())
                .isEqualTo(status == CheckoutSessionStatus.COMPLETE ? 1 : 0);
    }

    @Test
    void refundTimeoutAndExpiredKeyShowIndependentDuplicateEffectRisk() {
        var charge = captured("paid");
        var refund = new CreateRefundCommand(scope, "refund", charge.chargeId(), null, 1000, "requested_by_customer");
        fake.controls().enqueue(scope, Operation.CREATE_REFUND, "refund", Outcome.RESPONSE_LOST);
        assertThat(fake.createRefund(refund).classification()).isEqualTo(TIMEOUT_UNKNOWN);
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
        var recovered = fake.createRefund(refund).payload();
        assertThat(fake.createRefund(refund).payload()).isEqualTo(recovered);
        fake.clock().advance(Duration.ofMinutes(10));
        assertThat(fake.createRefund(refund).payload().refundId()).isNotEqualTo(recovered.refundId());
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(2);
        assertThat(fake.controls().effects(scope)).hasSize(3);
    }

    @Test
    void postCommitCrashKeepsRemoteEffectsAndRequestEvidence() {
        var charge = captured("paid");
        fake.controls().enqueue(scope, Operation.CREATE_REFUND, "refund", Outcome.CRASH_AFTER_COMMIT);
        var command = new CreateRefundCommand(scope, "refund", charge.chargeId(), null, 500, "requested_by_customer");
        assertThatThrownBy(() -> fake.createRefund(command)).isInstanceOf(FakeStripeProvider.SimulatedCrash.class);
        fake = open();
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
        assertThat(fake.createRefund(command).payload()).isNotNull();
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
    }

    @Test
    void authRequiredScriptReturnsFactsAndCanResumeWithoutNewSession() {
        fake.controls().enqueue(scope, Operation.CREATE_SESSION, "auth", Outcome.AUTH_REQUIRED);
        var auth = fake.createSession(command("auth"));
        assertThat(auth.classification()).isEqualTo(AUTH_REQUIRED);
        assertThat(fake.queryPaymentIntent(scope, auth.payload().paymentIntentId())
                        .payload()
                        .status())
                .isEqualTo("requires_action");
        assertThat(fake.createSession(command("auth")).payload().sessionId())
                .isEqualTo(auth.payload().sessionId());
        fake.controls().attemptPayment(scope, auth.payload().sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
    }

    @Test
    void concurrentKeyReplayAcrossInstancesAcceptsOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<ProviderCallResult<CheckoutSessionFact>>>();
            for (int i = 0; i < 6; i++) {
                var instance = open();
                results.add(executor.submit(() -> {
                    start.await();
                    return instance.createSession(command("concurrent"));
                }));
            }
            start.countDown();
            var ids = new HashSet<String>();
            for (var result : results) {
                var reply = result.get(10, TimeUnit.SECONDS);
                assertThat(reply.classification()).isEqualTo(SUCCESS);
                ids.add(reply.payload().sessionId());
            }
            assertThat(ids).hasSize(1);
        }
        assertThat(fake.counters(scope).requestCount()).isEqualTo(6);
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(1);
    }

    @Test
    void refundByPaymentIntentAndOverRefundRejection() {
        var charge = captured("paid");
        var byPi =
                new CreateRefundCommand(scope, "full", null, charge.paymentIntentId(), 10000, "requested_by_customer");
        assertThat(fake.createRefund(byPi).payload().chargeId()).isEqualTo(charge.chargeId());
        assertThat(fake.createRefund(new CreateRefundCommand(
                                scope, "over", charge.chargeId(), null, 1, "requested_by_customer"))
                        .classification())
                .isEqualTo(DEFINITIVE_REJECT);
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
    }

    @Test
    void sameSeedAndActionsReplayIdenticalIdsAndEvidence() {
        var other = new FakeStripeProvider(directory.resolve("replay"), 42, START, Duration.ofMinutes(10));
        assertThat(other.createSession(command("deterministic")))
                .isEqualTo(fake.createSession(command("deterministic")));
        assertThat(other.controls().calls(scope)).isEqualTo(fake.controls().calls(scope));
    }

    @Test
    void corruptedRetainedPayloadFailsBeforeReplay() throws Exception {
        session("cache");
        var path = directory.resolve("state.json");
        var broken = FakeStateStore.JSON.readTree(java.nio.file.Files.readAllBytes(path));
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) broken.path("scopes")
                .elements()
                .next()
                .path("cache")
                .elements()
                .next()
                .path("payload");
        payload.put("sessionId", "wrong_session");
        java.nio.file.Files.writeString(path, broken.toString());
        assertThatThrownBy(() -> fake.createSession(command("cache"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void corruptedRefundAmountCannotAllowASecondFullRefund() throws Exception {
        var charge = captured("paid");
        fake.createRefund(
                new CreateRefundCommand(scope, "full", charge.chargeId(), null, 10000, "requested_by_customer"));
        var path = directory.resolve("state.json");
        String original = java.nio.file.Files.readString(path);
        var broken = FakeStateStore.JSON.readTree(original);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) broken.path("scopes")
                .elements()
                .next()
                .path("objects")
                .path("refunds")
                .elements()
                .next()
                .path("fact");
        fact.put("amountMinor", "oops");
        String corrupt = broken.toString();
        java.nio.file.Files.writeString(path, corrupt);
        var second = new CreateRefundCommand(scope, "another", charge.chargeId(), null, 10000, "requested_by_customer");
        assertThatThrownBy(() -> fake.createRefund(second)).isInstanceOf(IllegalStateException.class);
        assertThat(java.nio.file.Files.readString(path)).isEqualTo(corrupt);
        java.nio.file.Files.writeString(path, original);
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
    }

    @Test
    void scopeIsolationAndActiveTransactionGuard() {
        var s = session("scope");
        var other = new ProviderScope(scope.organizationId(), scope.environmentId(), new UUID(0, 4), false);
        assertThat(fake.querySession(other, s.sessionId()).classification()).isEqualTo(NOT_FOUND);
        assertThat(fake.querySession(
                                new ProviderScope(
                                        scope.organizationId(), scope.environmentId(), scope.providerAccountId(), true),
                                s.sessionId())
                        .classification())
                .isEqualTo(DEFINITIVE_REJECT);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> fake.createSession(command("tx"))).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }
}
