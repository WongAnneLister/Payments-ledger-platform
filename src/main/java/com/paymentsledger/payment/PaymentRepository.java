package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.payment.domain.PaymentEvidenceRules;
import com.paymentsledger.payment.domain.PaymentFacts;
import com.paymentsledger.payment.domain.StripeDeployment;
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
        if (provider == null) return List.of();
        return transaction(() -> jdbc.queryForList(
                """
            SELECT o.id FROM platform.provider_operation o
            JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.payment pay ON pay.id=o.payment_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            WHERE o.status='PREPARED' AND t.status='READY' AND pay.resolution_status<>'CONFLICT'
              AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=?
              AND (?<>'STRIPE' OR (p.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
            ORDER BY o.created_at,o.id LIMIT ?
            """,
                UUID.class,
                provider,
                provider,
                configured("account-id", "STRIPE_ACCOUNT_ID"),
                configured("organization-id", "STRIPE_ORGANIZATION_ID"),
                configured("environment-id", "STRIPE_ENVIRONMENT_ID"),
                configured("provider-account-id", "STRIPE_PROVIDER_ACCOUNT_ID"),
                Math.min(100, Math.max(1, limit))));
    }

    Claim claim(UUID id, String provider) {
        return claimRecovery(id, provider, false);
    }

    Claim claimRecovery(UUID id, String provider, boolean recovery) {
        return transaction(() -> {
            var reference = one("SELECT payment_id,refund_id FROM platform.provider_operation WHERE id=?", id);
            if (reference == null) return null;
            boolean refundTarget = reference.get("refund_id") != null;
            UUID parent = uuid(reference, "payment_id");
            if (refundTarget) {
                var r = one("SELECT payment_id FROM platform.refund WHERE id=?", reference.get("refund_id"));
                parent = uuid(r, "payment_id");
            }
            lockPayment(parent);
            if (refundTarget) {
                one("SELECT payment_id FROM platform.refund_control WHERE payment_id=? FOR UPDATE", parent);
                one("SELECT id FROM platform.refund WHERE id=? FOR UPDATE", reference.get("refund_id"));
            }
            var op = one("SELECT * FROM platform.provider_operation WHERE id=? FOR UPDATE", id);
            var task = one("SELECT * FROM platform.recovery_task WHERE operation_id=? FOR UPDATE", id);
            Instant now = now();
            if (op == null || "ESCALATED".equals(op.get("status"))) return null;
            if (!recovery && !"PREPARED".equals(op.get("status"))) return null;
            if (recovery
                    && ("DONE".equals(task.get("status"))
                            || instant(task, "available_at").isAfter(now)
                            || instant(op, "lease_until") != null
                                    && instant(op, "lease_until").isAfter(now))) return null;
            var c = new TenantContext(
                    uuid(op, "organization_id"),
                    uuid(op, "environment_id"),
                    uuid(op, "application_id"),
                    uuid(op, "provider_account_id"),
                    Set.of(str(op, "currency")),
                    false,
                    Set.of());
            checkBinding(c, provider);
            var p = refundTarget
                    ? one("SELECT * FROM platform.refund WHERE id=?", op.get("refund_id"))
                    : one("""
                            SELECT p.*,o.order_key
                            FROM platform.payment p
                            JOIN platform.business_order o ON o.id=p.business_order_id
                            WHERE p.id=?""", parent);
            if (!recovery && "CONFLICT".equals(p.get("resolution_status"))) throw error(ErrorCode.EVIDENCE_CONFLICT);
            if (!recovery
                    && "CREATE_CHECKOUT".equals(op.get("operation_type"))
                    && (p.get("payment_intent_id") != null || number(p, "captured_amount_minor") > 0)) {
                jdbc.update("""
                        UPDATE platform.provider_operation
                        SET status='UNKNOWN',resolution='UNKNOWN',version=version+1
                        WHERE id=?""", id);
                jdbc.update("""
                        UPDATE platform.recovery_task
                        SET status='WAITING',reason='REMOTE_FACT_REQUIRES_QUERY',available_at=?,version=version+1
                        WHERE operation_id=?""", Timestamp.from(now), id);
                return null;
            }
            if (one("""
                            SELECT id
                            FROM platform.org_currency_enabled
                            WHERE organization_id=? AND environment_id=? AND currency=? AND status='ACTIVE'""", op.get("organization_id"), op.get("environment_id"), op.get("currency")) == null)
                throw error(ErrorCode.CURRENCY_NOT_ENABLED);
            // A shared account row meters recovery batches across processes. Never sleep while holding locks.
            if (recovery && !reserveBatch(op, now)) return null;
            UUID token = UUID.randomUUID();
            long version = number(op, "version") + 1;
            Instant first = instant(op, "first_send_at"), horizon = instant(op, "retry_until");
            if (first == null) {
                first = now;
                Duration window = recoveryPolicy.verifiedWindow(provider);
                if (!window.isZero()) horizon = first.plus(window);
            }
            Instant until = now.plus(recoveryPolicy.lease());
            jdbc.update(
                    """
                    UPDATE platform.provider_operation
                    SET
                        status='SENDING',
                        lease_token=?,
                        lease_owner='pr07',
                        lease_until=?,
                        attempt_count=attempt_count+1,
                        first_send_at=?,
                        retry_until=?,
                        version=?,
                        updated_at=?
                    WHERE id=?""",
                    token,
                    Timestamp.from(until),
                    Timestamp.from(first),
                    horizon == null ? null : Timestamp.from(horizon),
                    version,
                    Timestamp.from(now),
                    id);
            jdbc.update("""
                    UPDATE platform.recovery_task
                    SET
                        status='RUNNING',
                        lease_token=?,
                        lease_until=?,
                        attempt_count=attempt_count+1,
                        version=version+1,
                        updated_at=?
                    WHERE operation_id=?""", token, Timestamp.from(until), Timestamp.from(now), id);
            // Original status determines whether the first command is known to be unsent.
            op.put("first_send_at", Timestamp.from(first));
            op.put("retry_until", horizon == null ? null : Timestamp.from(horizon));
            return new Claim(id, parent, token, version, str(op, "operation_type"), op, p);
        });
    }

    private boolean reserveBatch(Map<String, Object> op, Instant now) {
        jdbc.update(
                """
                INSERT INTO platform.recovery_account_limit(organization_id,environment_id,provider_account_id,
                    next_batch_at)
                VALUES (?,?,?,?)
                ON CONFLICT
                DO NOTHING""",
                op.get("organization_id"),
                op.get("environment_id"),
                op.get("provider_account_id"),
                Timestamp.from(now));
        var limit = one("""
                SELECT next_batch_at
                FROM platform.recovery_account_limit
                WHERE organization_id=? AND environment_id=? AND provider_account_id=?
                FOR UPDATE""", op.get("organization_id"), op.get("environment_id"), op.get("provider_account_id"));
        Instant next = instant(limit, "next_batch_at");
        if (next.isAfter(now)) {
            jdbc.update("""
                    UPDATE platform.recovery_task
                    SET available_at=greatest(available_at,?),version=version+1
                    WHERE operation_id=?""", Timestamp.from(next), op.get("id"));
            return false;
        }
        jdbc.update(
                """
                UPDATE platform.recovery_account_limit
                SET next_batch_at=?
                WHERE organization_id=? AND environment_id=? AND provider_account_id=?""",
                Timestamp.from(now.plus(recoveryPolicy.accountBatchInterval())),
                op.get("organization_id"),
                op.get("environment_id"),
                op.get("provider_account_id"));
        return true;
    }

    private void lockPayment(UUID payment) {
        one("SELECT id FROM platform.payment WHERE id=? FOR UPDATE", payment);
    }

    boolean owns(Claim claim) {
        var row = one("""
                SELECT o.id
                FROM platform.provider_operation o
                JOIN platform.recovery_task t ON t.operation_id=o.id
                WHERE o.id=?
                    AND o.lease_token=?
                    AND o.version=?
                    AND o.status='SENDING'
                    AND o.lease_until>?
                    AND t.status='RUNNING'
                    AND t.lease_token=o.lease_token
                    AND t.lease_until=o.lease_until
                FOR UPDATE OF o""", claim.operationId(), claim.token(), claim.version(), Timestamp.from(now()));
        return row != null;
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
        var op = one("SELECT * FROM platform.provider_operation WHERE id=?", operation);
        if (op.get("refund_id") != null) {
            var refund = one("SELECT * FROM platform.refund WHERE id=?", op.get("refund_id"));
            boolean done = RefundRepository.terminal(str(refund, "refund_status"))
                    && "KNOWN".equals(refund.get("resolution_status"));
            Instant next = now().plus(recoveryPolicy.delay(operation, ((Number) op.get("attempt_count")).intValue()));
            jdbc.update("""
                    UPDATE platform.recovery_task
                    SET status=?,available_at=?,lease_until=null,lease_token=null,version=version+1,updated_at=?
                    WHERE operation_id=?""", done ? "DONE" : "WAITING", Timestamp.from(next), Timestamp.from(now()), operation);
            return;
        }
        var p = one("SELECT * FROM platform.payment WHERE id=?", op.get("payment_id"));
        boolean done = "ACKNOWLEDGED".equals(op.get("status"))
                || "CANCELED".equals(p.get("payment_status")) && "KNOWN".equals(p.get("resolution_status"))
                || "CANCEL_PAYMENT".equals(op.get("operation_type")) && "RESOLVED".equals(op.get("status"));
        Instant next = now().plus(recoveryPolicy.delay(operation, ((Number) op.get("attempt_count")).intValue()));
        jdbc.update("""
                UPDATE platform.recovery_task
                SET status=?,available_at=?,lease_until=null,lease_token=null,version=version+1,updated_at=?
                WHERE operation_id=?""", done ? "DONE" : "WAITING", Timestamp.from(next), Timestamp.from(now()), operation);
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
        if (provider == null) return List.of();
        return transaction(() -> jdbc.queryForList(
                """
            SELECT o.id FROM platform.provider_operation o JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            LEFT JOIN platform.recovery_account_limit lim ON lim.organization_id=o.organization_id AND lim.environment_id=o.environment_id AND lim.provider_account_id=o.provider_account_id
            WHERE t.status IN ('READY','WAITING','RUNNING') AND t.available_at<=? AND (o.lease_until IS NULL OR o.lease_until<=?)
              AND o.status<>'ESCALATED' AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=? AND (lim.next_batch_at IS NULL OR lim.next_batch_at<=?)
              AND (?<>'STRIPE' OR (p.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
            ORDER BY t.available_at,o.id LIMIT ?
            """,
                UUID.class,
                Timestamp.from(now()),
                Timestamp.from(now()),
                provider,
                Timestamp.from(now()),
                provider,
                configured("account-id", "STRIPE_ACCOUNT_ID"),
                configured("organization-id", "STRIPE_ORGANIZATION_ID"),
                configured("environment-id", "STRIPE_ENVIRONMENT_ID"),
                configured("provider-account-id", "STRIPE_PROVIDER_ACCOUNT_ID"),
                Math.min(100, Math.max(1, limit))));
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
        transaction(() -> {
            lockPayment(claim.paymentId());
            if (owns(claim)) escalateOwned(claim, reason);
            return null;
        });
    }
    /** Caller must retain the business/operation locks after a successful owns() check in this transaction. */
    void escalateOwned(Claim claim, String reason) {
        boolean refund = claim.operation().get("refund_id") != null;
        if (refund) jdbc.update("""
                    UPDATE platform.refund
                    SET
                        resolution_status=CASE WHEN resolution_status='CONFLICT' THEN 'CONFLICT' ELSE 'UNKNOWN' END,
                        unresolved_since=coalesce(unresolved_since,?),
                        version=version+1
                    WHERE id=?""", Timestamp.from(now()), claim.operation().get("refund_id"));
        else markUnknown(claim.paymentId(), "CONFLICT".equals(claim.payment().get("resolution_status")));
        jdbc.update("""
                UPDATE platform.provider_operation
                SET
                    status='ESCALATED',
                    resolution=coalesce((SELECT resolution_status FROM platform.payment WHERE
                        id=provider_operation.payment_id),(SELECT resolution_status FROM platform.refund WHERE
                        id=provider_operation.refund_id)),
                    escalation_reason=?,
                    escalated_at=coalesce(escalated_at,?),
                    lease_until=null,
                    version=version+1,
                    updated_at=?
                WHERE id=?""", reason, Timestamp.from(now()), Timestamp.from(now()), claim.operationId());
        jdbc.update("""
                UPDATE platform.recovery_task
                SET
                    status='ESCALATED',
                    reason=?,
                    escalated_at=coalesce(escalated_at,?),
                    lease_until=null,
                    lease_token=null,
                    version=version+1,
                    updated_at=?
                WHERE operation_id=?""", reason, Timestamp.from(now()), Timestamp.from(now()), claim.operationId());
        org.slf4j.LoggerFactory.getLogger(PaymentRepository.class)
                .warn(
                        "Payment recovery escalated operation={} payment={} reason={}",
                        claim.operationId(),
                        claim.paymentId(),
                        reason);
    }

    void alertAge(Claim claim) {
        transaction(() -> {
            lockPayment(claim.paymentId());
            if (owns(claim)) alertAgeOwned(claim);
            return null;
        });
    }

    private void alertAgeOwned(Claim claim) {
        jdbc.update("""
                UPDATE platform.provider_operation
                SET age_alert_at=coalesce(age_alert_at,?)
                WHERE id=?""", Timestamp.from(now()), claim.operationId());
        jdbc.update("""
                UPDATE platform.recovery_task
                SET age_alert_at=coalesce(age_alert_at,?)
                WHERE operation_id=?""", Timestamp.from(now()), claim.operationId());
    }

    int wakeL2(int limit, String provider) {
        if (provider == null) return 0;
        var ids = transaction(() -> jdbc.queryForList(
                """
            SELECT o.id FROM platform.provider_operation o LEFT JOIN platform.payment p ON p.id=o.payment_id LEFT JOIN platform.refund r ON r.id=o.refund_id JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.provider_account a ON a.id=o.provider_account_id
            JOIN platform.application app ON app.id=o.application_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config env ON env.id=o.environment_id
            JOIN platform.org_currency_enabled cur ON cur.organization_id=o.organization_id AND cur.environment_id=o.environment_id AND cur.currency=o.currency
            WHERE a.status='ACTIVE' AND app.status='ACTIVE' AND org.status='ACTIVE' AND env.status='ACTIVE' AND cur.status='ACTIVE' AND NOT a.livemode AND NOT env.livemode AND a.provider=?
             AND (?<>'STRIPE' OR (a.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
             AND o.status<>'ESCALATED' AND (o.lease_until IS NULL OR o.lease_until<=?)
             AND (p.resolution_status<>'KNOWN' OR p.payment_status NOT IN ('SUCCEEDED','CANCELED') OR p.financial_status='PENDING_EVIDENCE' OR r.resolution_status<>'KNOWN' OR r.refund_status NOT IN ('SUCCEEDED','FAILED','CANCELED')
               OR (r.refund_status='SUCCEEDED' AND r.balance_transaction_id IS NULL)
               OR (r.balance_transaction_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=r.organization_id AND b.environment_id=r.environment_id AND b.provider_account_id=r.provider_account_id AND b.bt_id=r.balance_transaction_id AND b.state='POSTED'))
               OR (r.failure_balance_transaction_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM platform.balance_transaction b WHERE b.organization_id=r.organization_id AND b.environment_id=r.environment_id AND b.provider_account_id=r.provider_account_id AND b.bt_id=r.failure_balance_transaction_id AND b.state='POSTED')))
             AND (t.status='DONE' OR t.available_at<=?) ORDER BY t.available_at,o.id LIMIT ?
            """,
                UUID.class,
                provider,
                provider,
                configured("account-id", "STRIPE_ACCOUNT_ID"),
                configured("organization-id", "STRIPE_ORGANIZATION_ID"),
                configured("environment-id", "STRIPE_ENVIRONMENT_ID"),
                configured("provider-account-id", "STRIPE_PROVIDER_ACCOUNT_ID"),
                Timestamp.from(now()),
                Timestamp.from(now()),
                Math.min(100, Math.max(1, limit))));
        for (UUID id : ids)
            transaction(() -> {
                var ref = one("SELECT payment_id,refund_id FROM platform.provider_operation WHERE id=?", id);
                UUID parent = uuid(ref, "payment_id");
                if (parent == null)
                    parent = jdbc.queryForObject(
                            "SELECT payment_id FROM platform.refund WHERE id=?", UUID.class, ref.get("refund_id"));
                lockPayment(parent);
                var op = one("SELECT * FROM platform.provider_operation WHERE id=? FOR UPDATE", id);
                if (!"ESCALATED".equals(op.get("status"))
                        && (instant(op, "lease_until") == null
                                || !instant(op, "lease_until").isAfter(now())))
                    jdbc.update("""
                            UPDATE platform.recovery_task
                            SET status='WAITING',available_at=least(available_at,?),version=version+1
                            WHERE operation_id=? AND status<>'ESCALATED'""", Timestamp.from(now()), id);
                return null;
            });
        return ids.size();
    }
}
