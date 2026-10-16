package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.payment.domain.PaymentEvidenceRules;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.StripeDeployment;
import com.paymentsledger.payment.repository.OperationLeaseRepository;
import com.paymentsledger.payment.repository.PaymentIntentRepository;
import com.paymentsledger.payment.repository.PaymentRecordRepository;
import com.paymentsledger.payment.repository.PaymentStateRepository;
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
    private final JdbcTemplate jdbc;
    private final PaymentStateRepository states;
    private final PaymentIntentService intents;
    private final PaymentReader reader;
    private final OperationLeases leases;
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

    private static final String SCOPE = "organization_id,environment_id,application_id,provider_account_id,currency";

    public PaymentRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.states = new PaymentStateRepository(jdbc);
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

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
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
        return transaction(() -> one("""
                SELECT p.*,o.order_key
                FROM platform.payment p
                JOIN platform.business_order o ON o.id=p.business_order_id
                WHERE p.id=?""", id));
    }

    Map<String, Object> operation(UUID id) {
        return transaction(() -> one("SELECT * FROM platform.provider_operation WHERE id=?", id));
    }

    UUID createOperation(UUID payment) {
        return transaction(() -> jdbc.queryForObject("""
                SELECT id
                FROM platform.provider_operation
                WHERE payment_id=? AND operation_type='CREATE_CHECKOUT'""", UUID.class, payment));
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
        var op = one("SELECT * FROM platform.provider_operation WHERE id=?", operation);
        for (var observation : observations) {
            // Persist only project-owned fields; never serialize arbitrary provider payloads or SDK objects.
            var evidence = observation.result().evidence();
            if (op.get("refund_id") != null)
                jdbc.update(
                        "INSERT INTO platform.provider_observation(" + SCOPE
                                + ",operation_id,refund_id,observation_domain,lease_token,call_type,classification,external_object_id,evidence,fact) VALUES (?,?,?,?,?,?,?,'REFUND',?,?,?,?,?::jsonb,?::jsonb)",
                        op.get("organization_id"),
                        op.get("environment_id"),
                        op.get("application_id"),
                        op.get("provider_account_id"),
                        op.get("currency"),
                        operation,
                        op.get("refund_id"),
                        token,
                        observation.type(),
                        observation.result().classification().name(),
                        observation.externalId(),
                        encode(evidence),
                        observation.result().payload() == null
                                ? null
                                : encode(redacted(observation.result().payload())));
            else
                jdbc.update(
                        "INSERT INTO platform.provider_observation(" + SCOPE
                                + ",operation_id,payment_id,lease_token,call_type,classification,external_object_id,evidence,fact) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
                        op.get("organization_id"),
                        op.get("environment_id"),
                        op.get("application_id"),
                        op.get("provider_account_id"),
                        op.get("currency"),
                        operation,
                        payment,
                        token,
                        observation.type(),
                        observation.result().classification().name(),
                        observation.externalId(),
                        encode(evidence),
                        observation.result().payload() == null
                                ? null
                                : encode(redacted(observation.result().payload())));
        }
    }

    private Object redacted(Object value) {
        if (value == null) return null;
        if (value instanceof CheckoutSessionFact f)
            return Map.of(
                    "sessionId",
                    f.sessionId(),
                    "status",
                    f.status(),
                    "paymentStatus",
                    Objects.toString(f.paymentStatus(), ""),
                    "paymentIntentId",
                    Objects.toString(f.paymentIntentId(), ""));
        if (value instanceof PaymentIntentFact f) {
            var fact = new LinkedHashMap<String, Object>();
            fact.put("paymentIntentId", f.paymentIntentId());
            fact.put("status", f.status());
            fact.put("amountMinor", f.amountMinor());
            fact.put("currency", f.currency());
            fact.put("amountReceivedMinor", f.amountReceivedMinor());
            fact.put("chargeIds", f.chargeIds());
            return fact;
        }
        if (value instanceof ChargeFact f) return f;
        if (value instanceof RefundFact f) return f;
        if (value instanceof List<?> list)
            return list.stream().map(this::redacted).toList();
        throw new IllegalArgumentException("Unrecognized provider observation payload");
    }
    /** Recheck the committed payment immediately before a create call, without holding locks across the provider. */
    boolean deferCreateWithRemoteFact(Claim claim) {
        return transaction(() -> {
            lockPayment(claim.paymentId());
            if (!owns(claim)) return true;
            var p = one("SELECT * FROM platform.payment WHERE id=?", claim.paymentId());
            if (p.get("payment_intent_id") == null && number(p, "captured_amount_minor") == 0) return false;
            completeOperation(
                    claim,
                    "UNKNOWN",
                    str(p, "resolution_status"),
                    str(p, "checkout_session_id"),
                    str(p, "provider_status"));
            return true;
        });
    }

    void finishCreate(Claim claim, ProviderCallResult<CheckoutSessionFact> result) {
        transaction(() -> {
            lockPayment(claim.paymentId());
            observations(
                    claim.operationId(),
                    claim.paymentId(),
                    claim.token(),
                    List.of(new Observation(
                            "CREATE_SESSION",
                            result,
                            result.payload() == null ? null : result.payload().sessionId())));
            if (!owns(claim)) return null;
            var current = one("SELECT * FROM platform.payment WHERE id=?", claim.paymentId());
            if ("CONFLICT".equals(current.get("resolution_status"))) {
                completeOperation(
                        claim,
                        "UNKNOWN",
                        "CONFLICT",
                        result.payload() == null ? null : result.payload().sessionId(),
                        null);
                return null;
            }
            if (result.classification() == ProviderCallClassification.DEFINITIVE_REJECT) {
                if (number(current, "captured_amount_minor") > 0
                        || current.get("checkout_session_id") != null
                        || current.get("payment_intent_id") != null) {
                    markUnknown(claim.paymentId(), true);
                    completeOperation(claim, "UNKNOWN", "CONFLICT", null, null);
                } else {
                    jdbc.update("""
                            UPDATE platform.payment
                            SET
                                payment_status='CANCELED',
                                resolution_status='KNOWN',
                                unresolved_since=null,
                                version=version+1,
                                updated_at=now()
                            WHERE id=? AND version=?""", claim.paymentId(), number(current, "version"));
                    completeOperation(claim, "ACKNOWLEDGED", "KNOWN", null, null);
                }
                return null;
            }
            var fact = result.payload();
            boolean known = fact != null
                    && (result.classification() == ProviderCallClassification.SUCCESS
                            || result.classification() == ProviderCallClassification.AUTH_REQUIRED);
            boolean valid = known
                    && matchesMetadata(fact.metadata(), claim.paymentId())
                    && fact.expiresAt() != null
                    && (fact.status() != CheckoutSessionStatus.OPEN
                            || fact.hostedUrl() != null && !fact.hostedUrl().isBlank());
            if (valid) {
                var p = one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", claim.paymentId());
                if (p.get("checkout_session_id") != null
                                && !p.get("checkout_session_id").equals(fact.sessionId())
                        || p.get("payment_intent_id") != null
                                && fact.paymentIntentId() != null
                                && !p.get("payment_intent_id").equals(fact.paymentIntentId())) {
                    markUnknown(claim.paymentId(), true);
                    completeOperation(claim, "UNKNOWN", "CONFLICT", null, null);
                    return null;
                }
                String state = fact.status() == CheckoutSessionStatus.OPEN ? "AWAITING_CUSTOMER" : "PROCESSING";
                if ("SUCCEEDED".equals(p.get("payment_status")) || "CANCELED".equals(p.get("payment_status")))
                    state = str(p, "payment_status");
                jdbc.update(
                        """
                        UPDATE platform.payment
                        SET
                            checkout_session_id=?,
                            payment_intent_id=coalesce(payment_intent_id,?),
                            hosted_url=?,
                            payment_status=?,
                            provider_status=?,
                            resolution_status='KNOWN',
                            unresolved_since=null,
                            last_provider_confirmed_at=?,
                            version=version+1,
                            updated_at=now()
                        WHERE id=? AND version=?""",
                        fact.sessionId(),
                        fact.paymentIntentId(),
                        fact.hostedUrl(),
                        state,
                        sessionStatus(fact),
                        Timestamp.from(clock.instant()),
                        claim.paymentId(),
                        number(p, "version"));
                completeOperation(claim, "RESOLVED", "KNOWN", fact.sessionId(), sessionStatus(fact));
            } else {
                boolean conflict = known
                        || result.classification() == ProviderCallClassification.CONTRACT_MISMATCH
                        || result.classification() == ProviderCallClassification.IDEMPOTENCY_CONFLICT;
                markUnknown(claim.paymentId(), conflict);
                completeOperation(
                        claim,
                        "UNKNOWN",
                        conflict ? "CONFLICT" : "UNKNOWN",
                        fact == null ? null : fact.sessionId(),
                        null);
            }
            return null;
        });
    }

    static boolean matchesMetadata(Map<String, String> metadata, UUID payment) {
        return payment.toString().equals(metadata.get("payment_id"));
    }

    static String sessionStatus(CheckoutSessionFact fact) {
        return PaymentEvidenceRules.sessionStatus(fact);
    }

    void completeOperation(Claim claim, String status, String resolution, String external, String providerStatus) {
        // owns() is the authorization linearization point while the payment/operation locks are held.
        // Re-reading the wall clock here could reject the operation after payment facts already changed.
        int updated = jdbc.update(
                """
                UPDATE platform.provider_operation
                SET
                    status=?,
                    resolution=?,
                    external_object_id=coalesce(?,external_object_id),
                    last_provider_status=coalesce(?,last_provider_status),
                    lease_until=null,
                    version=version+1,
                    updated_at=?
                WHERE id=? AND lease_token=? AND version=? AND status='SENDING'""",
                status,
                resolution,
                external,
                providerStatus,
                Timestamp.from(now()),
                claim.operationId(),
                claim.token(),
                claim.version());
        if (updated != 1)
            throw new IllegalStateException("Lost operation ownership inside locked writeback transaction");
        scheduleTask(claim.operationId());
    }

    private void scheduleTask(UUID operation) {
        leases.schedule(operation);
    }

    private void markUnknown(UUID payment, boolean conflict) {
        jdbc.update("""
                UPDATE platform.payment
                SET
                    resolution_status=CASE WHEN resolution_status='CONFLICT' OR ? THEN 'CONFLICT' ELSE 'UNKNOWN'
                        END,
                    unresolved_since=coalesce(unresolved_since,?),
                    payment_status=CASE WHEN payment_status='CREATED' THEN 'PROCESSING' ELSE payment_status END,
                    version=version+1,
                    updated_at=now()
                WHERE id=?""", conflict, Timestamp.from(now()), payment);
        shares.reconcile(payment, "payment.resolution");
    }

    void mergeQuery(UUID payment, UUID operation, List<Observation> observations, PaymentFacts facts) {
        transaction(() -> {
            lockPayment(payment);
            observations(operation, payment, null, observations);
            boolean applicable = merge(payment, facts);
            jdbc.update(
                    "UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",
                    Timestamp.from(now()),
                    operation);
            // Query evidence never changes ownership or version while a sender owns the operation.
            if (applicable && facts.complete() && !facts.conflict())
                jdbc.update("""
                        UPDATE platform.provider_operation
                        SET status='RESOLVED',resolution='KNOWN',last_query_at=?,version=version+1,updated_at=now()
                        WHERE id=? AND status IN ('UNKNOWN','ACKNOWLEDGED')""", Timestamp.from(clock.instant()), operation);
            return null;
        });
    }
    /** Called only by the inbox transaction after acquiring the payment lock before the inbox lock. */
    public Map<String, Object> lockWebhookPayment(UUID payment) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Webhook merge requires transaction");
        return one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", payment);
    }

    public boolean mergeWebhook(UUID payment, PaymentFacts facts) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Webhook merge requires transaction");
        return merge(payment, facts, false);
    }
    /** Verified reconciliation reads use query ordering; they are not webhook events. */
    public boolean mergeReconciliationQuery(UUID payment, PaymentFacts facts) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reconciliation query merge requires transaction");
        return merge(payment, facts, true);
    }

    private boolean merge(UUID payment, PaymentFacts facts) {
        return merge(payment, facts, true);
    }

    private boolean merge(UUID payment, PaymentFacts facts, boolean query) {
        return new PaymentStateMerger(states, shares, json, clock).merge(payment, facts, query);
    }

    void finishCancel(Claim claim, List<Observation> observations, PaymentFacts facts, boolean canceled) {
        transaction(() -> {
            lockPayment(claim.paymentId());
            observations(claim.operationId(), claim.paymentId(), claim.token(), observations);
            if (!owns(claim)) return null;
            if (observations.stream()
                    .anyMatch(o -> o.type().startsWith("QUERY_") || o.type().equals("LIST_CHARGE_ATTEMPTS")))
                jdbc.update(
                        "UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",
                        Timestamp.from(now()),
                        claim.operationId());
            boolean applicable = merge(claim.paymentId(), facts);
            var p = one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE", claim.paymentId());
            boolean captured = number(p, "captured_amount_minor") > 0;
            boolean conflict = "CONFLICT".equals(p.get("resolution_status")) || facts.conflict();
            if (canceled && applicable && !captured && !conflict && facts.complete())
                jdbc.update("""
                        UPDATE platform.payment
                        SET
                            payment_status='CANCELED',
                            resolution_status='KNOWN',
                            unresolved_since=null,
                            version=version+1,
                            updated_at=now()
                        WHERE id=? AND version=?""", claim.paymentId(), number(p, "version"));
            if (canceled && captured) markUnknown(claim.paymentId(), true);
            boolean resolved = applicable && facts.complete() && !conflict && (captured || canceled);
            completeOperation(
                    claim,
                    resolved ? "RESOLVED" : "UNKNOWN",
                    resolved ? "KNOWN" : captured || conflict ? "CONFLICT" : "UNKNOWN",
                    str(p, "checkout_session_id"),
                    str(p, "provider_status"));
            return null;
        });
    }

    boolean cancelUnsent(Claim claim) {
        return transaction(() -> {
            lockPayment(claim.paymentId());
            if (!owns(claim)) return false;
            var payment = one("SELECT * FROM platform.payment WHERE id=?", claim.paymentId());
            if ("CONFLICT".equals(payment.get("resolution_status"))
                    || payment.get("payment_intent_id") != null
                    || number(payment, "captured_amount_minor") > 0) return false;
            var create = one("""
                    SELECT *
                    FROM platform.provider_operation
                    WHERE payment_id=? AND operation_type='CREATE_CHECKOUT'
                    FOR UPDATE""", claim.paymentId());
            if (!"PREPARED".equals(create.get("status"))) return false;
            jdbc.update("""
                    UPDATE platform.provider_operation
                    SET status='RESOLVED',version=version+1,updated_at=now()
                    WHERE id=? AND status='PREPARED'""", create.get("id"));
            jdbc.update("""
                    UPDATE platform.recovery_task
                    SET status='DONE',version=version+1
                    WHERE operation_id=?""", create.get("id"));
            jdbc.update("""
                    UPDATE platform.payment
                    SET
                        payment_status='CANCELED',
                        resolution_status='KNOWN',
                        unresolved_since=null,
                        version=version+1,
                        updated_at=now()
                    WHERE id=?""", claim.paymentId());
            completeOperation(claim, "RESOLVED", "KNOWN", null, null);
            return true;
        });
    }

    List<UUID> due(int limit, String provider) {
        return leases.due(limit, provider);
    }

    void checkProviderScope(ProviderScope scope, String provider) {
        var row = one("""
                SELECT id,currency
                FROM platform.payment
                WHERE organization_id=? AND environment_id=? AND provider_account_id=?
                LIMIT 1""", scope.organizationId(), scope.environmentId(), scope.providerAccountId());
        var app = one("""
                SELECT id
                FROM platform.application
                WHERE organization_id=? AND environment_id=? AND status='ACTIVE'
                ORDER BY id
                LIMIT 1""", scope.organizationId(), scope.environmentId());
        if (app == null) throw error(ErrorCode.SERVICE_UNAVAILABLE);
        checkBinding(
                new TenantContext(
                        scope.organizationId(),
                        scope.environmentId(),
                        uuid(app, "id"),
                        scope.providerAccountId(),
                        Set.of(row == null ? "USD" : str(row, "currency")),
                        false,
                        Set.of()),
                provider);
    }

    String refundRecoveryAnchor(Claim claim) {
        var anchors = new LinkedHashSet<String>();
        String bound = str(claim.payment(), "provider_refund_id");
        if (bound != null) anchors.add(bound);
        String external = str(claim.operation(), "external_object_id");
        if (external != null) anchors.add(external);
        anchors.addAll(transaction(() -> jdbc.queryForList(
                """
                SELECT DISTINCT external_object_id
                FROM platform.provider_observation
                WHERE operation_id=?
                    AND refund_id=?
                    AND call_type IN ('CREATE_REFUND','QUERY_REFUND')
                    AND classification IN ('SUCCESS','AUTH_REQUIRED')
                    AND external_object_id IS NOT NULL""", String.class, claim.operationId(), claim.operation().get("refund_id"))));
        return anchors.size() > 1
                ? "CONFLICTING_REFUND_ANCHORS"
                : anchors.isEmpty() ? null : anchors.iterator().next();
    }

    String recoveryAnchor(Claim claim) {
        var anchors = new LinkedHashSet<String>();
        String bound = str(claim.payment(), "checkout_session_id");
        if (bound != null) anchors.add(bound);
        String external = str(claim.operation(), "external_object_id");
        if (external != null && external.startsWith("cs_")) anchors.add(external);
        anchors.addAll(transaction(() -> jdbc.queryForList(
                """
            SELECT DISTINCT external_object_id FROM platform.provider_observation WHERE operation_id=? AND payment_id=?
             AND organization_id=? AND environment_id=? AND provider_account_id=?
             AND call_type IN ('CREATE_SESSION','QUERY_SESSION','EXPIRE_SESSION') AND left(external_object_id,3)='cs_'
             AND classification IN ('SUCCESS','AUTH_REQUIRED')
            """,
                String.class,
                claim.operationId(),
                claim.paymentId(),
                claim.operation().get("organization_id"),
                claim.operation().get("environment_id"),
                claim.operation().get("provider_account_id"))));
        if (anchors.size() > 1) return "CONFLICTING_SESSION_ANCHORS";
        return anchors.isEmpty() ? null : anchors.iterator().next();
    }

    void finishRecoveryQuery(Claim claim, ProviderQueryService.Queried query) {
        transaction(() -> {
            lockPayment(claim.paymentId());
            observations(claim.operationId(), claim.paymentId(), claim.token(), query.observations());
            if (!owns(claim)) return null;
            boolean applicable = merge(claim.paymentId(), query.facts());
            var p = one("SELECT * FROM platform.payment WHERE id=?", claim.paymentId());
            jdbc.update(
                    "UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",
                    Timestamp.from(now()),
                    claim.operationId());
            String resolution = str(p, "resolution_status");
            Instant unresolvedSince = instant(p, "unresolved_since");
            if (!"KNOWN".equals(resolution)
                    && unresolvedSince != null
                    && !unresolvedSince.plus(recoveryPolicy.unresolvedAge()).isAfter(now())) {
                alertAgeOwned(claim);
                escalateOwned(claim, "UNRESOLVED_AGE_LIMIT");
                return null;
            }
            completeOperation(
                    claim,
                    applicable ? "RESOLVED" : "UNKNOWN",
                    resolution,
                    str(p, "checkout_session_id"),
                    str(p, "provider_status"));
            return null;
        });
    }

    void conflictingAnchor(Claim claim) {
        transaction(() -> {
            lockPayment(claim.paymentId());
            if (owns(claim)) markUnknown(claim.paymentId(), true);
            return null;
        });
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
