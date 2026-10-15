package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Runtime-role PG and independently persisted Fake effects; no duplicate inherited test executions. */
class OperationRecoveryIT extends PaymentCoreFixture {
    @org.junit.jupiter.api.BeforeEach
    void configure() {
        recovery();
    }

    OperationRecoveryCoordinator recovery() {
        return new OperationRecoveryCoordinator(
                repository,
                executor,
                new RecoveryPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofDays(1),
                        Duration.ofDays(1),
                        0,
                        Duration.ofHours(1),
                        Duration.ZERO));
    }

    @Test
    void responseLostRetriesOriginalKeyAndProducesOneIndependentRemoteSession() {
        var intent = repository.prepare(context, "recovery-key", command("recover"), "FAKE");
        var key =
                repository.operation(intent.operationId()).get("operation_key").toString();
        fake.controls()
                .enqueue(scope, FailureScript.Operation.CREATE_SESSION, key, FailureScript.Outcome.RESPONSE_LOST);
        executor.execute(intent.operationId());
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        fake.clock().advance(Duration.ofSeconds(6));
        recovery().recoverDue(10);
        assertThat(repository.read(context, intent.paymentId())).containsEntry("resolutionStatus", "KNOWN");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(repository.operation(intent.operationId()).get("operation_key"))
                .isEqualTo(key);
    }

    @Test
    void expiredSendingAfterClaimBeforeCallIsRecoveredWithOriginalCommand() {
        var intent = repository.prepare(context, "claimed-key", command("claimed"), "FAKE");
        repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        recovery().recoverDue(10);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }

    @Test
    void twoWorkersCannotBothOwnOneExpiredOperation() throws Exception {
        var intent = repository.prepare(context, "race-key", command("race"), "FAKE");
        repository.claim(intent.operationId(), "FAKE");
        fake.clock().advance(Duration.ofSeconds(31));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var a = workers.submit(() -> {
                ready.countDown();
                start.await();
                recovery().recoverDue(10);
                return true;
            });
            var b = workers.submit(() -> {
                ready.countDown();
                start.await();
                recovery().recoverDue(10);
                return true;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.provider_operation", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void lateWorkerEvidenceBecomesQueryAnchorAfterLeaseExpiryWithoutResending() {
        var intent = repository.prepare(context, "late-key", command("late"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var accepted = fake.createSession(
                repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class));
        fake.clock().advance(Duration.ofSeconds(31));
        repository.finishCreate(claim, accepted);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "SENDING");
        recovery().recoverDue(10);
        assertThat(repository.read(context, intent.paymentId())).containsEntry("resolutionStatus", "KNOWN");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(1);
    }

    @Test
    void knownAnchorIsQueriedBeforeAgeEscalationAndFindsCapture() {
        var resource = service.create(context, "aged-key", command("aged")).resource();
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        admin.update(
                "UPDATE platform.payment SET resolution_status='UNKNOWN',unresolved_since=?",
                java.sql.Timestamp.from(fake.clock().instant()));
        fake.clock().advance(Duration.ofHours(2));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource)))
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("resolutionStatus", "KNOWN");
        assertThat(repository
                        .operation(repository.createOperation(payment(resource)))
                        .get("status"))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                        "SELECT age_alert_at IS NOT NULL FROM platform.provider_operation", Boolean.class))
                .isTrue();
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
    }

    @Test
    void expiredWindowNeverCreatesASecondSessionEvenWhenRemotePaymentExists() {
        var intent = repository.prepare(context, "expired-window", command("expired"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var accepted = fake.createSession(
                repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class));
        fake.controls().attemptPayment(scope, accepted.payload().sessionId(), FakeControls.AttemptOutcome.SUCCEED);
        fake.clock().advance(Duration.ofDays(2));
        recovery().recoverDue(10);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "ESCALATED");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
        assertThat(fake.counters(scope).requestCount()).isEqualTo(1);
        recovery().scanL2(10);
        recovery().recoverDue(10);
        assertThat(fake.counters(scope).requestCount()).isEqualTo(1);
    }

    @Test
    void transientEmptyWithAnchorNeverResendsCreateAndLaterConverges() {
        var resource = service.create(context, "empty-key", command("empty")).resource();
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_EMPTY);
        fake.clock().advance(Duration.ofSeconds(6));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource))).containsEntry("resolutionStatus", "UNKNOWN");
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        fake.clock().advance(Duration.ofSeconds(16));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource))).containsEntry("paymentStatus", "SUCCEEDED");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
    }

    @Test
    void unverifiedWindowEscalatesAndPersistsOriginalAge() {
        var noRetry = new OperationRecoveryCoordinator(repository, executor, RecoveryPolicy.defaults());
        var intent = repository.prepare(context, "unverified-key", command("unverified"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        Object first = repository.operation(intent.operationId()).get("first_send_at");
        fake.clock().advance(Duration.ofSeconds(31));
        noRetry.recoverDue(10);
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "IDEMPOTENCY_WINDOW_UNVERIFIED")
                .containsEntry("first_send_at", first);
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void authoritativeNaturalExpiryProvesUnpaidAndClosesWithoutAgeEscalation() {
        var resource =
                service.create(context, "customer-key", command("customer")).resource();
        fake.clock().advance(Duration.ofHours(2));
        recovery().recoverDue(10);
        assertThat(repository
                        .operation(repository.createOperation(payment(resource)))
                        .get("status"))
                .isNotEqualTo("ESCALATED");
        assertThat(repository.read(context, payment(resource)))
                .containsEntry("paymentStatus", "CANCELED")
                .containsEntry("resolutionStatus", "KNOWN");
        assertThat(jdbc.queryForObject("SELECT age_alert_at IS NULL FROM platform.provider_operation", Boolean.class))
                .isTrue();
    }

    @Test
    void l2DoesNotStealLiveLeaseOrResetItsRetryHorizon() {
        var intent = repository.prepare(context, "l2-key-000", command("l2"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var before = repository.operation(intent.operationId());
        recovery().scanL2(10);
        recovery().recoverDue(10);
        var after = repository.operation(intent.operationId());
        assertThat(after)
                .containsEntry("lease_token", before.get("lease_token"))
                .containsEntry("version", before.get("version"))
                .containsEntry("first_send_at", before.get("first_send_at"))
                .containsEntry("retry_until", before.get("retry_until"));
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void persistentAccountLimitAppliesAcrossCoordinatorInstances() {
        var policy = new RecoveryPolicy(
                Duration.ofSeconds(30),
                Duration.ofDays(1),
                Duration.ofDays(1),
                0,
                Duration.ofHours(1),
                Duration.ofSeconds(10));
        var first = new OperationRecoveryCoordinator(repository, executor, policy);
        var second = new OperationRecoveryCoordinator(repository, executor, policy);
        repository.prepare(context, "batch-one", command("batch1"), "FAKE");
        repository.prepare(context, "batch-two", command("batch2"), "FAKE");
        first.recoverDue(10);
        second.recoverDue(10);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        fake.clock().advance(Duration.ofSeconds(10));
        second.recoverDue(10);
        fake.clock().advance(Duration.ofSeconds(10));
        second.recoverDue(10);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.recovery_account_limit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void recoveryRefusesNetworkInsideTransaction() {
        new org.springframework.transaction.support.TransactionTemplate(tx).execute(status -> {
            assertThatThrownBy(() -> recovery().recoverDue(10)).isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void originalRetryHorizonAndTaskResourceCannotBeRewrittenAtRuntime() {
        var first = repository.prepare(context, "guard-first", command("first"), "FAKE");
        repository.claim(first.operationId(), "FAKE");
        var second = repository.prepare(context, "guard-second", command("second"), "FAKE");
        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE platform.provider_operation
                        SET first_send_at=first_send_at+interval '1 hour'
                        WHERE id=?""", first.operationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE platform.provider_operation
                        SET retry_until=retry_until+interval '1 hour'
                        WHERE id=?""", first.operationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE platform.recovery_task SET payment_id=? WHERE operation_id=?",
                        second.paymentId(),
                        first.operationId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.recovery_account_limit"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void oldWorkerReturnsAfterNewTokenFinishedAndCannotOverwriteControl() {
        var intent = repository.prepare(context, "old-worker", command("oldworker"), "FAKE");
        var old = repository.claim(intent.operationId(), "FAKE");
        var accepted = fake.createSession(
                repository.decode(old.operation().get("command"), CreateCheckoutSessionCommand.class));
        fake.clock().advance(Duration.ofSeconds(31));
        var newer = repository.claimRecovery(intent.operationId(), "FAKE", true);
        executor.executeClaim(newer);
        long version = PaymentRepository.number(repository.operation(intent.operationId()), "version");
        repository.finishCreate(old, accepted);
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("version", version);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.provider_observation", Integer.class))
                .isEqualTo(2);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }

    @Test
    void crashAfterQueryBeforeWritebackIsRecoveredFromSameDurableSession() {
        var resource =
                service.create(context, "query-crash", command("querycrash")).resource();
        var operation = repository.createOperation(payment(resource));
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        fake.clock().advance(Duration.ofSeconds(6));
        var old = repository.claimRecovery(operation, "FAKE", true);
        var discarded = executor.query(old.payment());
        assertThat(discarded.facts().charges()).anyMatch(c -> c.captured());
        fake.clock().advance(Duration.ofSeconds(31));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource)))
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("financialStatus", "PENDING_EVIDENCE");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
        assertThat(count("journal")).isZero();
    }

    @Test
    void cancellationRecoveryFindsCompletedExpireWithoutAnotherExpire() {
        var resource = service.create(context, "cancel-recover", command("cancelrecover"))
                .resource();
        var intent = repository.prepareCancel(
                context, payment(resource), "cancel-intent", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        repository.claim(intent.operationId(), "FAKE");
        fake.expireSession(scope, session(resource));
        long accepted = fake.counters(scope).acceptedCount();
        fake.clock().advance(Duration.ofSeconds(31));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource))).containsEntry("paymentStatus", "CANCELED");
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
    }

    @Test
    void captureRacingCancellationRecoveryNeverExpiresOrCancelsSuccessfulPayment() {
        var resource =
                service.create(context, "capture-race", command("capturerace")).resource();
        var intent = repository.prepareCancel(
                context, payment(resource), "cancel-race", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        repository.claim(intent.operationId(), "FAKE");
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        long accepted = fake.counters(scope).acceptedCount();
        fake.clock().advance(Duration.ofSeconds(31));
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource))).containsEntry("paymentStatus", "SUCCEEDED");
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);
        assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
    }

    @Test
    void conflictingObservationAnchorsNeverChooseAnArbitrarySession() {
        var intent = repository.prepare(context, "anchor-conflict", command("anchorconflict"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var original = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
        var first = fake.createSession(original);
        var different = new CreateCheckoutSessionCommand(
                original.scope(),
                "different_key",
                original.amountMinor(),
                original.currency(),
                original.successUrl(),
                original.cancelUrl(),
                original.sessionMetadata(),
                original.paymentIntentMetadata(),
                original.expiresAt());
        var second = fake.createSession(different);
        fake.clock().advance(Duration.ofSeconds(31));
        repository.finishCreate(claim, first);
        repository.finishCreate(claim, second);
        recovery().recoverDue(10);
        assertThat(repository.read(context, intent.paymentId())).containsEntry("resolutionStatus", "CONFLICT");
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "ESCALATED");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(2);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
    }

    @Test
    void aNewTransientFailureAfterLongCustomerWaitDoesNotImmediatelyAgeEscalate() {
        var resource =
                service.create(context, "new-outage", command("newoutage")).resource();
        fake.clock().advance(Duration.ofHours(2));
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_EMPTY);
        recovery().recoverDue(10);
        assertThat(repository.read(context, payment(resource))).containsEntry("resolutionStatus", "UNKNOWN");
        assertThat(repository
                        .operation(repository.createOperation(payment(resource)))
                        .get("status"))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT age_alert_at IS NULL FROM platform.provider_operation", Boolean.class))
                .isTrue();
    }

    @Test
    void writebackOwnershipIsLinearizedOnceWithinTheShortLockedTransaction() {
        var intent = repository.prepare(context, "clock-fence", command("clockfence"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var accepted = fake.createSession(
                repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class));
        var base = fake.clock().instant();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var clock = new Clock() {
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return reads.getAndIncrement() == 0 ? base : base.plusSeconds(31);
            }
        };
        var crossing = new PaymentRepository(
                jdbc, tx, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), clock);
        crossing.finishCreate(claim, accepted);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.recovery_task", String.class))
                .isEqualTo("WAITING");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }

    @Test
    void successfulCancellationUpdatesLastQueryTimestampWithoutAGet() {
        var resource = service.create(context, "cancel-query-time", command("cancelquerytime"))
                .resource();
        var canceled =
                service.cancel(context, payment(resource), "cancel-now-key", new PaymentRequests.Cancel("OTHER", null));
        assertThat(((Map<?, ?>) canceled.get("recovery")).get("lastQueryAt")).isNotNull();
        assertThat(jdbc.queryForObject("""
                        SELECT last_query_at IS NOT NULL
                        FROM platform.provider_operation
                        WHERE operation_type='CANCEL_PAYMENT'""", Boolean.class)).isTrue();
    }

    @Test
    void persistentUnknownWithAnchorQueriesBeforeDurableAgeEscalation() {
        var resource =
                service.create(context, "aged-unknown", command("agedunknown")).resource();
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_EMPTY);
        service.get(context, payment(resource));
        fake.clock().advance(Duration.ofHours(2));
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_EMPTY);
        long requests = fake.counters(scope).requestCount();
        recovery().recoverDue(10);
        assertThat(repository.operation(repository.createOperation(payment(resource))))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT");
        assertThat(jdbc.queryForObject(
                        "SELECT age_alert_at IS NOT NULL FROM platform.provider_operation", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("""
                        SELECT age_alert_at IS NOT NULL AND escalated_at IS NOT NULL
                        FROM platform.recovery_task""", Boolean.class)).isTrue();
        assertThat(fake.counters(scope).requestCount()).isGreaterThan(requests);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
    }

    @Test
    void ageEscalationSharesTheQueryWritebackAuthorizationPoint() {
        var resource =
                service.create(context, "age-clock-key", command("ageclock")).resource();
        var payment = payment(resource);
        var operation = repository.createOperation(payment);
        admin.update("""
                UPDATE platform.payment
                SET resolution_status='UNKNOWN',unresolved_since=?
                WHERE id=?""", java.sql.Timestamp.from(fake.clock().instant().minusSeconds(7200)), payment);
        fake.clock().advance(Duration.ofSeconds(6));
        var claim = repository.claimRecovery(operation, "FAKE", true);
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_EMPTY);
        var query = executor.query(claim.payment());
        var base = fake.clock().instant();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var clock = new Clock() {
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return reads.getAndIncrement() == 0 ? base : base.plusSeconds(31);
            }
        };
        var crossing = new PaymentRepository(
                jdbc, tx, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), clock);
        crossing.finishRecoveryQuery(claim, query);
        assertThat(repository.operation(operation))
                .containsEntry("status", "ESCALATED")
                .containsEntry("escalation_reason", "UNRESOLVED_AGE_LIMIT");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.recovery_task", String.class))
                .isEqualTo("ESCALATED");
    }
}
