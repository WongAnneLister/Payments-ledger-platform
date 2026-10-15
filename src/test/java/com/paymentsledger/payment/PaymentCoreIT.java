package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PaymentCoreIT extends PaymentCoreFixture {
    @Test
    void oneHundredConcurrentSameKeysHaveOneDurableIntentAndOneAcceptedSession() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PaymentService.Creation>> calls = new ArrayList<>();
            var ready = new CountDownLatch(100);
            var go = new CountDownLatch(1);
            for (int i = 0; i < 100; i++)
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.create(context, "same-key-000", command("order"));
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Set<UUID> ids = new HashSet<>();
            for (var call : calls)
                ids.add(payment(call.get(90, TimeUnit.SECONDS).resource()));
            assertThat(ids).hasSize(1);
        }
        for (String table :
                List.of("business_order", "payment", "provider_operation", "recovery_task", "api_idempotency_record"))
            assertThat(count(table)).as(table).isEqualTo(1);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("payments_app");
    }

    @Test
    void independentOrderAndApiGuardsAndImmutableSnapshot() {
        var created = service.create(context, "key-key-000", command("order"));
        String snapshot =
                jdbc.queryForObject("SELECT share_policy_snapshot::text FROM platform.business_order", String.class);
        admin.update("""
                UPDATE platform.share_policy_fixture
                SET share_basis_points=100,policy_version=2,status='DISABLED'""");
        assertThat(service.create(context, "key-key-000", command("order")).replay())
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT share_policy_snapshot::text FROM platform.business_order", String.class))
                .isEqualTo(snapshot);
        assertThatThrownBy(() -> service.create(context, "another-key-000", command("order")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_INTENT_CONFLICT));
        assertThatThrownBy(() -> service.create(context, "key-key-000", command("changed")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(payment(created.resource())).isNotNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.business_order SET share_policy_snapshot='{}'::jsonb"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void controlledAmountAndOverflowNeverCommitOrCallRemote() {
        var tampered = new PaymentRequests.Create(
                "tampered",
                "demo",
                1,
                9999,
                "USD",
                1,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
        assertThatThrownBy(() -> service.create(context, "bad-key-000", tampered))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AMOUNT_MISMATCH));
        admin.update("UPDATE platform.price_fixture SET amount_minor=?", Long.MAX_VALUE);
        var overflow = new PaymentRequests.Create(
                "overflow",
                "demo",
                1,
                Long.MAX_VALUE,
                "USD",
                2,
                "https://merchant.test/ok",
                "https://merchant.test/cancel",
                null,
                Map.of());
        assertThatThrownBy(() -> service.create(context, "overflow-key-000", overflow))
                .isInstanceOf(ApiException.class);
        assertThat(count("payment")).isZero();
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void rollbackDoesNotLeaveIntentOrCallProvider() {
        admin.execute("""
                CREATE OR REPLACE FUNCTION platform.fail_payment_task() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                    RAISE EXCEPTION 'injected task failure'; END $$""");
        admin.execute("""
                CREATE TRIGGER fail_payment_task BEFORE INSERT ON platform.recovery_task FOR EACH ROW EXECUTE
                    FUNCTION platform.fail_payment_task()""");
        try {
            assertThatThrownBy(() -> service.create(context, "key-key-000", command("order")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            admin.execute("DROP TRIGGER fail_payment_task ON platform.recovery_task");
        }
        assertThat(count("payment")).isZero();
        assertThat(count("business_order")).isZero();
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void preparedQueueSurvivesServiceRecreationAndUnknownNeverResends() {
        var intent = repository.prepare(context, "key-key-000", command("order"), "FAKE");
        assertThat(fake.counters(scope).requestCount()).isZero();
        assertThat(count("recovery_task")).isEqualTo(1);
        String operationKey =
                jdbc.queryForObject("SELECT operation_key FROM platform.provider_operation", String.class);
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.CREATE_SESSION,
                        operationKey,
                        FailureScript.Outcome.RESPONSE_LOST);
        new PaymentExecutor(repository, fake, fake, fake, "FAKE").drainPrepared(10);
        executor.drainPrepared(10);
        executor.drainPrepared(10);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("UNKNOWN");
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
        assertThat(fake.counters(scope).requestCount()).isEqualTo(1);
        assertThat(service.create(context, "key-key-000", command("order")).resource())
                .containsEntry("resolutionStatus", "UNKNOWN");
    }

    @Test
    void allAttemptsArePreservedAndCaptureIsStickyWithoutInventingLedger() {
        var resource = service.create(context, "key-key-000", command("order")).resource();
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.DECLINE);
        assertThat(service.get(context, payment(resource))).containsEntry("paymentStatus", "AWAITING_CUSTOMER");
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var success = service.get(context, payment(resource));
        assertThat(success)
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("financialStatus", "PENDING_EVIDENCE")
                .containsEntry("capturedAmountMinor", 10000L);
        assertThat(count("payment_attempt")).isEqualTo(2);
        assertThat(count("journal")).isZero();
        assertThatThrownBy(() -> service.cancel(
                        context, payment(resource), "cancel-key-000", new PaymentRequests.Cancel("OTHER", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE));
        assertThat(service.get(context, payment(resource))).containsEntry("paymentStatus", "SUCCEEDED");
    }

    @Test
    void cancelReusesOneOperationAndDoesNotRepeatExpire() {
        var resource = service.create(context, "key-key-000", command("order")).resource();
        var request = new PaymentRequests.Cancel("OTHER", null);
        assertThat(service.cancel(context, payment(resource), "cancel-key-000", request))
                .containsEntry("paymentStatus", "CANCELED");
        long accepted = fake.counters(scope).acceptedCount();
        assertThat(service.cancel(context, payment(resource), "cancel2-key-000", request))
                .containsEntry("paymentStatus", "CANCELED");
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);
        assertThat(count("provider_operation")).isEqualTo(2);
        assertThatThrownBy(() -> service.cancel(
                        context,
                        payment(resource),
                        "cancel-key-000",
                        new PaymentRequests.Cancel("MERCHANT_CANCELED", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void rejectsOtherApplicationsAndDisabledAccountsBeforeExecution() {
        var resource = service.create(context, "key-key-000", command("order")).resource();
        var sibling = new TenantContext(id(1), id(2), id(99), id(4), Set.of("USD"), false, context.grantedScopes());
        assertThatThrownBy(() -> service.get(sibling, payment(resource)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        admin.update("UPDATE platform.provider_account SET status='DISABLED'");
        assertThatThrownBy(() -> service.create(context, "new-key-000", command("new")))
                .isInstanceOf(ApiException.class);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);
    }

    @Test
    void outerTransactionIsRejectedBeforeRemoteEffect() {
        assertThatThrownBy(() -> new TransactionTemplate(tx)
                        .executeWithoutResult(s -> service.create(context, "key-key-000", command("order"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("payment")).isZero();
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void staleSenderCannotWriteControlButItsExternalAnchorSurvives() {
        var intent = repository.prepare(context, "stale-key", command("order"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
        var accepted = fake.createSession(command);
        admin.update("""
                UPDATE platform.provider_operation
                SET lease_token=?,version=version+1
                WHERE id=?""", UUID.randomUUID(), intent.operationId());
        repository.finishCreate(claim, accepted);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("SENDING");
        assertThat(jdbc.queryForObject("SELECT checkout_session_id FROM platform.payment", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT external_object_id FROM platform.provider_observation", String.class))
                .isEqualTo(accepted.payload().sessionId());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.provider_observation"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void captureRacingCancellationWinsAndIsNeverClaimedUnpaid() {
        var resource = service.create(context, "create-key", command("order")).resource();
        CheckoutPort racing = new CheckoutPort() {
            public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
                return fake.createSession(c);
            }

            public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String id) {
                return fake.querySession(s, id);
            }

            public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String id) {
                fake.controls().attemptPayment(s, id, FakeControls.AttemptOutcome.SUCCEED);
                return fake.expireSession(s, id);
            }
        };
        var racingService = new PaymentService(repository, new PaymentExecutor(repository, racing, fake, fake, "FAKE"));
        assertThatThrownBy(() -> racingService.cancel(
                        context, payment(resource), "cancel-key", new PaymentRequests.Cancel("OTHER", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE));
        assertThat(repository.read(context, payment(resource)))
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("capturedAmountMinor", 10000L);
    }

    @Test
    void queryFailureSuppressesRedirectPreservesProviderFactsAndSendingOwnership() {
        var resource = service.create(context, "create-key", command("order")).resource();
        var operation = repository.createOperation(payment(resource));
        admin.update("""
                UPDATE platform.provider_operation
                SET status='SENDING',lease_token=?,version=99
                WHERE id=?""", UUID.randomUUID(), operation);
        String previous = jdbc.queryForObject("SELECT provider_status FROM platform.payment", String.class);
        fake.controls()
                .enqueue(
                        scope,
                        FailureScript.Operation.QUERY_SESSION,
                        session(resource),
                        FailureScript.Outcome.TRANSIENT_ERROR);
        var result = service.get(context, payment(resource));
        assertThat(result).containsEntry("resolutionStatus", "UNKNOWN");
        assertThat(((Map<?, ?>) result.get("nextAction")).get("type")).isEqualTo("WAIT_FOR_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("SENDING");
        assertThat(jdbc.queryForObject("SELECT version FROM platform.provider_operation", Long.class))
                .isEqualTo(99);
        assertThat(result.get("providerStatus")).isNotEqualTo("UNKNOWN");
    }

    @Test
    void cancelOnlyProvesUnpaidWhenCreateWasDurablyUnsent() {
        var unsent = repository.prepare(context, "unsent-key", command("unsent"), "FAKE");
        assertThat(service.cancel(context, unsent.paymentId(), "cancel-key", new PaymentRequests.Cancel("OTHER", null)))
                .containsEntry("paymentStatus", "CANCELED");
        assertThat(fake.counters(scope).requestCount()).isZero();
        var sending = repository.prepare(context, "sending-key", command("sending"), "FAKE");
        repository.claim(sending.operationId(), "FAKE");
        assertThat(service.cancel(
                        context, sending.paymentId(), "cancel-key2", new PaymentRequests.Cancel("OTHER", null)))
                .containsEntry("resolutionStatus", "UNKNOWN");
        assertThat(repository.read(context, sending.paymentId()).get("paymentStatus"))
                .isNotEqualTo("CANCELED");
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void configuredStripeAccountReferenceMustMatchDatabaseBindingBeforeIntent() {
        admin.update("""
                UPDATE platform.provider_account
                SET provider='STRIPE',provider_account_ref='acct_other'""");
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("platform.stripe.enabled", "true")
                .withProperty("platform.stripe.account-id", "acct_expected")
                .withProperty("platform.stripe.organization-id", id(1).toString())
                .withProperty("platform.stripe.environment-id", id(2).toString())
                .withProperty("platform.stripe.provider-account-id", id(4).toString());
        var bound =
                new PaymentRepository(jdbc, tx, new ObjectMapper().findAndRegisterModules(), fake.clock(), environment);
        assertThatThrownBy(() -> bound.prepare(context, "binding-key", command("order"), "STRIPE"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(count("payment")).isZero();
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void databaseRejectsAttemptIdentityMutationAndCrossPaymentEvidenceAssociation() {
        var first = service.create(context, "first-key", command("first")).resource();
        fake.controls().attemptPayment(scope, session(first), FakeControls.AttemptOutcome.SUCCEED);
        service.get(context, payment(first));
        var second = service.create(context, "second-key", command("second")).resource();
        assertThatThrownBy(() -> jdbc.update("UPDATE platform.payment_attempt SET payment_id=?", payment(second)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE platform.payment_attempt SET captured=false,amount_captured_minor=0"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID operation = repository.createOperation(payment(first));
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO platform.provider_observation(organization_id,environment_id,application_id,
                            provider_account_id,currency,operation_id,payment_id,call_type,classification,evidence)
                        VALUES (?,?,?,?,'USD',?,?,'QUERY','SUCCESS','{}')""", id(1), id(2), id(3), id(4), operation, payment(second)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void oldQueryCannotOverwriteNewerConfirmedProviderFacts() {
        var resource = service.create(context, "create-key", command("order")).resource();
        UUID payment = payment(resource);
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
        var success = service.get(context, payment);
        var row = repository.payment(payment);
        var stale = new PaymentIntentFact(
                (String) row.get("payment_intent_id"),
                "requires_payment_method",
                10000,
                0L,
                "USD",
                List.of(),
                null,
                null,
                Map.of("payment_id", payment.toString()),
                fake.clock().instant().minusSeconds(10));
        repository.mergeQuery(
                payment,
                repository.createOperation(payment),
                List.of(),
                new PaymentRepository.Facts(
                        null,
                        stale,
                        List.of(),
                        true,
                        false,
                        fake.clock().instant().minusSeconds(10)));
        var after = repository.read(context, payment);
        assertThat(after)
                .containsEntry("paymentStatus", "SUCCEEDED")
                .containsEntry("providerStatus", "succeeded")
                .containsEntry("lastProviderConfirmedAt", success.get("lastProviderConfirmedAt"));
    }

    @Test
    void authenticationRequiredIntentCannotBeExpiredAsUnpaid() {
        var resource = service.create(context, "create-key", command("order")).resource();
        fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.REQUIRE_AUTH);
        long accepted = fake.counters(scope).acceptedCount();
        var result =
                service.cancel(context, payment(resource), "cancel-key", new PaymentRequests.Cancel("OTHER", null));
        assertThat(result).containsEntry("resolutionStatus", "UNKNOWN");
        assertThat(result.get("paymentStatus")).isNotEqualTo("CANCELED");
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);
    }

    @Test
    void evidenceNeverTurnsMissingReceivedAmountIntoZero() throws Exception {
        var resource = service.create(context, "create-key", command("order")).resource();
        UUID payment = payment(resource);
        UUID operation = repository.createOperation(payment);
        var pi = new PaymentIntentFact(
                "pi_unknown",
                "processing",
                10000,
                null,
                "USD",
                List.of(),
                null,
                null,
                Map.of(),
                fake.clock().instant());
        var result = new ProviderCallResult<>(
                ProviderCallClassification.SUCCESS,
                pi,
                new ProviderEvidence(
                        "req", null, fake.clock().instant(), fake.clock().instant(), 200, null, "digest", "v1"));
        repository.mergeQuery(
                payment,
                operation,
                List.of(new PaymentRepository.Observation("QUERY_PAYMENT_INTENT", result, "pi_unknown")),
                new PaymentRepository.Facts(
                        null, null, List.of(), false, false, fake.clock().instant()));
        String stored = jdbc.queryForObject("""
                SELECT fact::text
                FROM platform.provider_observation
                WHERE call_type='QUERY_PAYMENT_INTENT'""", String.class);
        assertThat(new ObjectMapper().readTree(stored).hasNonNull("amountReceivedMinor"))
                .isFalse();
    }

    @Test
    void concurrentAuthoritativeQueriesAndSendingWritebackShareConsistentLockOrder() throws Exception {
        var resource = service.create(context, "create-key", command("order")).resource();
        UUID payment = payment(resource);
        UUID operation = repository.createOperation(payment);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 10; round++) {
                // A known remote PI must never be reclaimed as an unsent create. Model a recovery lease.
                fake.clock().advance(Duration.ofMinutes(1));
                admin.update("""
                        UPDATE platform.provider_operation
                        SET status='UNKNOWN',version=version+1
                        WHERE id=?""", operation);
                admin.update("""
                        UPDATE platform.recovery_task
                        SET status='WAITING',available_at=?
                        WHERE operation_id=?""", java.sql.Timestamp.from(fake.clock().instant()), operation);
                var claim = repository.claimRecovery(operation, "FAKE", true);
                var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
                var accepted = fake.createSession(command);
                var start = new CountDownLatch(1);
                var sender = workers.submit(() -> {
                    start.await();
                    repository.finishCreate(claim, accepted);
                    return true;
                });
                var query = workers.submit(() -> {
                    start.await();
                    executor.refresh(payment);
                    return true;
                });
                start.countDown();
                assertThat(sender.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(query.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM platform.provider_operation WHERE id=?", String.class, operation))
                        .isEqualTo("RESOLVED");
            }
        }
    }

    @Test
    void expiredLeaseCannotWriteControlEvenBeforeAnotherWorkerClaims() {
        var intent = repository.prepare(context, "expired-key", command("order"), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        var command = repository.decode(claim.operation().get("command"), CreateCheckoutSessionCommand.class);
        var accepted = fake.createSession(command);
        admin.update("""
                UPDATE platform.provider_operation
                SET lease_until=now()-interval '1 second'
                WHERE id=?""", intent.operationId());
        repository.finishCreate(claim, accepted);
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("SENDING");
        assertThat(jdbc.queryForObject("SELECT external_object_id FROM platform.provider_observation", String.class))
                .isEqualTo(accepted.payload().sessionId());
    }

    @Test
    void firstCreateCannotReturnSuccessWhileAnotherSenderOwnsUnacknowledgedIntent() {
        var deferred = new PaymentExecutor(repository, fake, fake, fake, "FAKE") {
            @Override
            public void execute(UUID operation) {
                repository.claim(operation, "FAKE");
            }
        };
        var waiting = new PaymentService(repository, deferred);
        assertThatThrownBy(() -> waiting.create(context, "waiting-key", command("order")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PROVIDER_UNKNOWN_RESULT);
                    assertThat(e.details()).anySatisfy(detail -> {
                        assertThat(detail.field()).isEqualTo("paymentId");
                        assertThat(detail.issue()).startsWith("pay_");
                    });
                });
        assertThat(count("payment")).isEqualTo(1);
        assertThat(fake.counters(scope).requestCount()).isZero();
    }

    @Test
    void cancelCannotClearAnExistingEvidenceConflictOrExpireTheSession() {
        var resource = service.create(context, "create-key", command("order")).resource();
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", payment(resource));
        long accepted = fake.counters(scope).acceptedCount();
        assertThatThrownBy(() -> service.cancel(
                        context, payment(resource), "cancel-key", new PaymentRequests.Cancel("OTHER", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EVIDENCE_CONFLICT));
        assertThat(repository.read(context, payment(resource))).containsEntry("resolutionStatus", "CONFLICT");
        assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);
    }

    @Test
    void conflictArrivingDuringExpireCannotBeResolvedByCancelWriteback() {
        var resource = service.create(context, "create-key", command("order")).resource();
        UUID payment = payment(resource);
        var intent = repository.prepareCancel(
                context, payment, "cancel-key", new PaymentRequests.Cancel("OTHER", null), "FAKE");
        var claim = repository.claim(intent.operationId(), "FAKE");
        admin.update("UPDATE platform.payment SET resolution_status='CONFLICT' WHERE id=?", payment);
        var expired = fake.expireSession(scope, session(resource));
        var pi = fake.queryPaymentIntent(scope, expired.payload().paymentIntentId())
                .payload();
        repository.finishCancel(
                claim,
                List.of(new PaymentRepository.Observation("EXPIRE_SESSION", expired, session(resource))),
                new PaymentRepository.Facts(
                        expired.payload(),
                        pi,
                        List.of(),
                        true,
                        false,
                        fake.clock().instant()),
                true);
        assertThat(repository.read(context, payment)).containsEntry("resolutionStatus", "CONFLICT");
        assertThat(repository.read(context, payment).get("paymentStatus")).isNotEqualTo("CANCELED");
        assertThat(repository.operation(intent.operationId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("resolution", "CONFLICT");
    }

    @Test
    void twentyFiveBlockedBindingsCannotStarveANewerEligiblePreparedOperation() {
        for (int n = 0; n < 25; n++) repository.prepare(context, "blocked-key-" + n, command("blocked-" + n), "FAKE");
        admin.update("UPDATE platform.provider_account SET status='DISABLED' WHERE id=?", id(4));
        admin.update("""
                INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,
                    provider_account_ref,livemode)
                VALUES (?,?,?,'FAKE','eligible',false)""", id(7), id(1), id(2));
        var eligible = new TenantContext(id(1), id(2), id(3), id(7), Set.of("USD"), false, context.grantedScopes());
        var intent = repository.prepare(eligible, "eligible-key", command("eligible"), "FAKE");
        executor.drainPrepared(25);
        assertThat(repository.operation(intent.operationId())).containsEntry("status", "RESOLVED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.provider_operation WHERE status='PREPARED'", Integer.class))
                .isEqualTo(25);
        assertThat(fake.counters(scope).requestCount()).isZero();
        assertThat(fake.counters(new ProviderScope(id(1), id(2), id(7), false)).sessionCreatedCount())
                .isEqualTo(1);
    }

    @Test
    void definitiveRejectionIsKnownAndNeverPretendsAnUnknownRemoteEffect() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        CheckoutPort rejecting = new CheckoutPort() {
            public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
                calls.incrementAndGet();
                return new ProviderCallResult<>(
                        ProviderCallClassification.DEFINITIVE_REJECT,
                        null,
                        new ProviderEvidence(
                                "req_rejected",
                                c.operationIdempotencyKey(),
                                fake.clock().instant(),
                                fake.clock().instant(),
                                400,
                                "invalid_request",
                                null,
                                "test"));
            }

            public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String id) {
                return fake.querySession(s, id);
            }

            public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String id) {
                return fake.expireSession(s, id);
            }
        };
        var rejectedService =
                new PaymentService(repository, new PaymentExecutor(repository, rejecting, fake, fake, "FAKE"));
        assertThatThrownBy(() -> rejectedService.create(context, "rejected-key", command("order")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.details())
                            .anySatisfy(d -> assertThat(d.issue()).startsWith("pay_"));
                });
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("SELECT resolution FROM platform.provider_operation", String.class))
                .isEqualTo("KNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.recovery_task", String.class))
                .isEqualTo("DONE");
        var replay = rejectedService.create(context, "rejected-key", command("order"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.resource())
                .containsEntry("resolutionStatus", "KNOWN")
                .containsEntry("paymentStatus", "CANCELED");
        executor.drainPrepared(25);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void openSessionWithoutHostedUrlCannotBeKnownSuccessfulCreation() {
        CheckoutPort missingUrl = new CheckoutPort() {
            public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
                var result = fake.createSession(c);
                var f = result.payload();
                return new ProviderCallResult<>(
                        result.classification(),
                        new CheckoutSessionFact(
                                f.sessionId(),
                                f.status(),
                                null,
                                f.paymentIntentId(),
                                f.paymentStatus(),
                                f.expiresAt(),
                                f.metadata()),
                        result.evidence());
            }

            public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String id) {
                return fake.querySession(s, id);
            }

            public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String id) {
                return fake.expireSession(s, id);
            }
        };
        var missingService =
                new PaymentService(repository, new PaymentExecutor(repository, missingUrl, fake, fake, "FAKE"));
        assertThatThrownBy(() -> missingService.create(context, "missing-url", command("order")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PROVIDER_UNKNOWN_RESULT));
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM platform.payment", String.class))
                .isNotEqualTo("KNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.provider_operation", String.class))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT external_object_id FROM platform.provider_observation", String.class))
                .startsWith("cs_");
    }

    @Test
    void overlappingOldProviderQueryCannotOverwriteLaterCaptureEvenWithEqualClock() throws Exception {
        var resource = service.create(context, "create-key", command("order")).resource();
        UUID payment = payment(resource);
        var oldSession = fake.querySession(scope, session(resource));
        var oldPi = fake.queryPaymentIntent(scope, oldSession.payload().paymentIntentId());
        var oldCharges = new ProviderCallResult<List<ChargeFact>>(
                ProviderCallClassification.SUCCESS, List.of(), oldPi.evidence());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CheckoutPort delayed = new CheckoutPort() {
            public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand c) {
                return fake.createSession(c);
            }

            public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope s, String id) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return oldSession;
            }

            public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope s, String id) {
                return fake.expireSession(s, id);
            }
        };
        ChargePort delayedCharges = new ChargePort() {
            public ProviderCallResult<ChargeFact> queryCharge(ProviderScope s, String id) {
                return fake.queryCharge(s, id);
            }

            public ProviderCallResult<List<ChargeFact>> listChargeAttempts(ProviderScope s, String id) {
                return oldCharges;
            }
        };
        var staleExecutor = new PaymentExecutor(repository, delayed, (s, id) -> oldPi, delayedCharges, "FAKE");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = workers.submit(() -> staleExecutor.refresh(payment));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            fake.controls().attemptPayment(scope, session(resource), FakeControls.AttemptOutcome.SUCCEED);
            var newest = service.get(context, payment);
            release.countDown();
            old.get(10, TimeUnit.SECONDS);
            assertThat(repository.read(context, payment))
                    .containsEntry("paymentStatus", "SUCCEEDED")
                    .containsEntry("providerStatus", "succeeded")
                    .containsEntry("lastProviderConfirmedAt", newest.get("lastProviderConfirmedAt"));
        } finally {
            release.countDown();
        }
    }
}
