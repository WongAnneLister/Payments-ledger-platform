package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.ProviderResultRules;
import com.paymentsledger.payment.domain.StripeDeployment;
import com.paymentsledger.payment.repository.OperationLeaseRepository;
import com.paymentsledger.payment.repository.PaymentIntentRepository;
import com.paymentsledger.payment.repository.PaymentRecordRepository;
import com.paymentsledger.payment.repository.PaymentStateRepository;
import com.paymentsledger.payment.repository.ProviderResultRepository;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.revenueshare.RevenueShareService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Explicit short transactions; no provider port is reachable from this class. */
@Repository
public class PaymentRepository {
    private RevenueShareService shares = RevenueShareService.disabled();
    private final PaymentIntentService intents;
    private final PaymentReader reader;
    private final OperationLeases leases;
    private final ProviderResults results;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    final Clock clock;
    private org.springframework.core.env.Environment configuration;
    private RecoveryPolicy recoveryPolicy = RecoveryPolicy.defaults();

    RecoveryPolicy recoveryPolicy() {
        return recoveryPolicy;
    }

    void recoveryPolicy(RecoveryPolicy policy) {
        this.recoveryPolicy = policy;
    }

    public PaymentRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json, Clock clock) {
        this.tx = new TransactionTemplate(transactions);
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.json = json;
        this.clock = clock;
        var records = new PaymentRecordRepository(jdbc);
        this.intents = new PaymentIntentService(
                new PaymentIntentRepository(jdbc), records, tx, json, clock, this::stripeDeployment);
        this.reader = new PaymentReader(intents, records, tx, clock);
        this.leases = new OperationLeases(
                new OperationLeaseRepository(jdbc),
                intents,
                tx,
                clock,
                this::recoveryPolicy,
                this::stripeDeployment,
                this::markUnknown);
        this.results = new ProviderResults(
                new ProviderResultRepository(jdbc),
                new PaymentStateRepository(jdbc),
                leases,
                intents,
                tx,
                json,
                clock,
                this::recoveryPolicy,
                () -> shares);
    }

    /** The Stripe account this deployment is configured for; all null when Stripe is not configured. */
    private StripeDeployment stripeDeployment() {
        return new StripeDeployment(
                configured("account-id", "STRIPE_ACCOUNT_ID"),
                configured("organization-id", "STRIPE_ORGANIZATION_ID"),
                configured("environment-id", "STRIPE_ENVIRONMENT_ID"),
                configured("provider-account-id", "STRIPE_PROVIDER_ACCOUNT_ID"));
    }

    public Instant now() {
        return clock.instant();
    }

    public PaymentRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ObjectMapper json,
            Clock clock,
            org.springframework.core.env.Environment environment) {
        this(jdbc, transactions, json, clock);
        this.configuration = environment;
        this.recoveryPolicy = RecoveryPolicy.from(environment);
    }

    public PaymentRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            RevenueShareService shares) {
        this(jdbc, tx, json, clock);
        this.shares = shares;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager tx,
            ObjectMapper json,
            Clock clock,
            org.springframework.core.env.Environment env,
            RevenueShareService shares) {
        this(jdbc, tx, json, clock, env);
        this.shares = shares;
    }

    public record Intent(UUID paymentId, UUID operationId, boolean replay) {}

    public record Claim(
            UUID operationId,
            UUID paymentId,
            UUID token,
            long version,
            String type,
            Map<String, Object> operation,
            Map<String, Object> payment) {}

    public record Observation(String type, ProviderCallResult<?> result, String externalId) {}

    <T> T transaction(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Payment orchestration must run outside database transactions");
    }

    static ApiException error(ErrorCode code) {
        return new ApiException(code, "Payment request cannot be completed: " + code.name());
    }

    static UUID uuid(Map<String, Object> row, String key) {
        return (UUID) row.get(key);
    }

    static String str(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode controlled payment command", e);
        }
    }

    <T> T decode(Object value, Class<T> type) {
        try {
            return json.readValue(value.toString(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted payment command", e);
        }
    }

    void checkBinding(TenantContext c, String provider) {
        intents.requireBinding(c, provider);
    }

    private String configured(String name, String fallback) {
        return configuration == null
                ? null
                : configuration.getProperty("platform.stripe." + name, configuration.getProperty(fallback));
    }

    public Intent prepare(TenantContext c, String key, PaymentRequests.Create request, String provider) {
        return intents.prepare(c, key, request, provider);
    }

    public Intent prepareCancel(
            TenantContext c, UUID payment, String key, PaymentRequests.Cancel request, String provider) {
        return intents.prepareCancel(c, payment, key, request, provider);
    }

    public Map<String, Object> read(TenantContext c, UUID id) {
        return reader.read(c, id);
    }

    Map<String, Object> payment(UUID id) {
        return results.payment(id);
    }

    Map<String, Object> operation(UUID id) {
        return results.operation(id);
    }

    UUID createOperation(UUID payment) {
        return results.createOperation(payment);
    }

    public List<UUID> prepared(int limit, String provider) {
        return leases.prepared(limit, provider);
    }

    Claim claim(UUID id, String provider) {
        return leases.claim(id, provider, false);
    }

    Claim claimRecovery(UUID id, String provider, boolean recovery) {
        return leases.claim(id, provider, recovery);
    }

    private void lockPayment(UUID payment) {
        leases.lockPayment(payment);
    }

    boolean owns(Claim claim) {
        return leases.owns(claim);
    }

    void observations(UUID operation, UUID payment, UUID token, List<Observation> observations) {
        results.observations(operation, payment, token, observations);
    }

    /** Recheck the committed payment immediately before a create call, without holding locks across the provider. */
    boolean deferCreateWithRemoteFact(Claim claim) {
        return results.deferCreateWithRemoteFact(claim);
    }

    void finishCreate(Claim claim, ProviderCallResult<CheckoutSessionFact> result) {
        results.finishCreate(claim, result);
    }

    static boolean matchesMetadata(Map<String, String> metadata, UUID payment) {
        return ProviderResultRules.matchesMetadata(metadata, payment);
    }

    void completeOperation(Claim claim, String status, String resolution, String external, String providerStatus) {
        results.completeOperation(claim, status, resolution, external, providerStatus);
    }

    private void scheduleTask(UUID operation) {
        leases.schedule(operation);
    }

    private void markUnknown(UUID payment, boolean conflict) {
        results.markUnknown(payment, conflict);
    }

    void mergeQuery(UUID payment, UUID operation, List<Observation> observations, PaymentFacts facts) {
        results.mergeQuery(payment, operation, observations, facts);
    }

    /** Called only by the inbox transaction after acquiring the payment lock before the inbox lock. */
    public Map<String, Object> lockWebhookPayment(UUID payment) {
        return results.lockWebhookPayment(payment);
    }

    public boolean mergeWebhook(UUID payment, PaymentFacts facts) {
        return results.mergeWebhook(payment, facts);
    }

    /** Verified reconciliation reads use query ordering; they are not webhook events. */
    public boolean mergeReconciliationQuery(UUID payment, PaymentFacts facts) {
        return results.mergeReconciliationQuery(payment, facts);
    }

    void finishCancel(Claim claim, List<Observation> observations, PaymentFacts facts, boolean canceled) {
        results.finishCancel(claim, observations, facts, canceled);
    }

    boolean cancelUnsent(Claim claim) {
        return results.cancelUnsent(claim);
    }

    List<UUID> due(int limit, String provider) {
        return leases.due(limit, provider);
    }

    void checkProviderScope(ProviderScope scope, String provider) {
        results.checkProviderScope(scope, provider);
    }

    String refundRecoveryAnchor(Claim claim) {
        return results.refundRecoveryAnchor(claim);
    }

    String recoveryAnchor(Claim claim) {
        return results.recoveryAnchor(claim);
    }

    void finishRecoveryQuery(Claim claim, ProviderQueryService.Queried query) {
        results.finishRecoveryQuery(claim, query);
    }

    void conflictingAnchor(Claim claim) {
        results.conflictingAnchor(claim);
    }

    void escalate(Claim claim, String reason) {
        leases.escalate(claim, reason);
    }

    /** Caller must retain the business/operation locks after a successful owns() check in this transaction. */
    void escalateOwned(Claim claim, String reason) {
        leases.escalateOwned(claim, reason);
    }

    void alertAge(Claim claim) {
        leases.alertAge(claim);
    }

    private void alertAgeOwned(Claim claim) {
        leases.alertAgeOwned(claim);
    }

    int wakeL2(int limit, String provider) {
        return leases.wakeL2(limit, provider);
    }
}
