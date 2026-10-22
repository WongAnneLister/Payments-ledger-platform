package com.paymentsledger.payment.repository;

import com.paymentsledger.payment.domain.ReconciliationSourceRules.ExistingPayment;
import com.paymentsledger.payment.domain.ReconciliationSourceRules.OrderBinding;
import com.paymentsledger.payment.domain.ReconciliationSourceRules.RecoverableOrder;
import com.paymentsledger.payment.domain.ReconciliationSourceRules.SourceBalanceTransaction;
import com.paymentsledger.payment.domain.ReconciliationSourceRules.SourceRun;
import com.paymentsledger.payment.domain.ReconciliationSourceRules.SourceTask;
import com.paymentsledger.provider.ProviderScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Data access for reconciliation source recovery: the trusted scope and run checks, source tasks with their leases,
 * the order binding and row locks taken while linking (order, payment, task, then BT), observations, exceptions and
 * the recovered payment with its provenance.
 */
public class ReconciliationSourceRepository {
    private static final String SCOPE =
            "organization_id=:organization AND environment_id=:environment AND provider_account_id=:providerAccount AND livemode=:livemode";

    private static final RowMapper<SourceRun> RUN = (rs, n) -> new SourceRun(
            rs.getObject("id", UUID.class), rs.getString("currency"), rs.getObject("import_window_id", UUID.class));
    private static final RowMapper<SourceBalanceTransaction> BT = (rs, n) -> new SourceBalanceTransaction(
            rs.getObject("id", UUID.class),
            rs.getString("bt_id"),
            rs.getString("balance_domain"),
            rs.getString("business_type"),
            rs.getString("source_object_type"),
            rs.getString("source_object_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("api_version"),
            rs.getString("state"));
    private static final RowMapper<SourceTask> TASK = (rs, n) -> new SourceTask(
            rs.getObject("id", UUID.class),
            rs.getString("status"),
            rs.getObject("lease_token", UUID.class),
            rs.getLong("version"),
            rs.getObject("reconciliation_run_id", UUID.class),
            rs.getObject("source_bt_id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getObject("livemode", Boolean.class),
            instant(rs, "lease_until"));
    private static final RowMapper<OrderBinding> BINDING = (rs, n) -> new OrderBinding(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getString("currency"),
            rs.getLong("amount_minor"),
            instant(rs, "eligible_until"));
    private static final RowMapper<RecoverableOrder> ORDER = (rs, n) -> new RecoverableOrder(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getObject("application_id", UUID.class),
            rs.getObject("provider_account_id", UUID.class),
            rs.getString("currency"),
            rs.getLong("amount_minor"));
    private static final RowMapper<ExistingPayment> PAYMENT = (rs, n) -> new ExistingPayment(
            rs.getObject("id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("payment_intent_id"),
            rs.getString("payment_status"),
            rs.getString("resolution_status"));

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Whether the scope's provider account, organization and environment are active and in test mode. */
    public boolean activeTestScope(ProviderScope scope) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.provider_account p
                JOIN platform.organization o ON o.id=p.organization_id
                JOIN platform.environment_config e ON e.id=p.environment_id
                WHERE p.id=:providerAccount
                    AND p.organization_id=:organization
                    AND p.environment_id=:environment
                    AND NOT p.livemode
                    AND NOT e.livemode
                    AND p.status='ACTIVE'
                    AND o.status='ACTIVE'
                    AND e.status='ACTIVE'""",
                new MapSqlParameterSource()
                        .addValue("providerAccount", scope.providerAccountId())
                        .addValue("organization", scope.organizationId())
                        .addValue("environment", scope.environmentId()),
                Long.class);
        return count == 1;
    }

    public Optional<SourceRun> run(ProviderScope scope, UUID id) {
        return one(
                "SELECT * FROM platform.reconciliation_run WHERE " + SCOPE + " AND id=:id",
                scope(scope).addValue("id", id),
                RUN);
    }

    public boolean currencyEnabled(ProviderScope scope, String currency) {
        Long enabled = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.org_currency_enabled
                WHERE organization_id=:organization AND environment_id=:environment AND currency=:currency AND status='ACTIVE'""",
                new MapSqlParameterSource()
                        .addValue("organization", scope.organizationId())
                        .addValue("environment", scope.environmentId())
                        .addValue("currency", currency),
                Long.class);
        return enabled == 1;
    }

    public Optional<SourceBalanceTransaction> balanceTransaction(ProviderScope scope, String btId) {
        return one(
                "SELECT * FROM platform.balance_transaction WHERE " + SCOPE + " AND bt_id=:btId",
                scope(scope).addValue("btId", btId),
                BT);
    }

    public Optional<SourceBalanceTransaction> balanceTransaction(ProviderScope scope, UUID id) {
        return one(
                "SELECT * FROM platform.balance_transaction WHERE " + SCOPE + " AND id=:id",
                scope(scope).addValue("id", id),
                BT);
    }

    /** Import conflict writers lock the BT too, so its state can be rechecked under this lock. */
    public Optional<SourceBalanceTransaction> lockBalanceTransaction(ProviderScope scope, UUID id) {
        return one(
                "SELECT * FROM platform.balance_transaction WHERE " + SCOPE + " AND id=:id FOR UPDATE",
                scope(scope).addValue("id", id),
                BT);
    }

    /** Whether the run's import enumerated the BT on one of its pages. */
    public boolean enumerated(UUID importWindow, String btId) {
        long pages = jdbc.queryForObject(
                "SELECT count(*) FROM platform.import_page WHERE window_id=:window AND :btId=ANY(bt_ids)",
                new MapSqlParameterSource().addValue("window", importWindow).addValue("btId", btId),
                Long.class);
        return pages > 0;
    }

    /** Creates the run's task for a BT once; returns the task's id either way. */
    public UUID insertTask(ProviderScope scope, SourceRun run, UUID btId, Instant availableAt) {
        jdbc.update(
                """
                INSERT INTO platform.recovery_task(organization_id,environment_id,provider_account_id,currency,
                    task_type,reconciliation_run_id,source_bt_id,livemode,available_at)
                VALUES (:organization,:environment,:providerAccount,:currency,'RECONCILIATION_SOURCE',:run,:bt,:livemode,:availableAt)
                ON CONFLICT(reconciliation_run_id,source_bt_id)
                DO NOTHING""",
                scope(scope)
                        .addValue("currency", run.currency())
                        .addValue("run", run.id())
                        .addValue("bt", btId)
                        .addValue("availableAt", Timestamp.from(availableAt)));
        return jdbc.queryForObject(
                """
                SELECT id
                FROM platform.recovery_task
                WHERE reconciliation_run_id=:run AND source_bt_id=:bt""", new MapSqlParameterSource().addValue("run", run.id()).addValue("bt", btId), UUID.class);
    }

    /** Due tasks of the run whose lease has expired, oldest first, skipping tasks another claimer holds. */
    public List<SourceTask> lockClaimable(ProviderScope scope, UUID runId, Instant now, int limit) {
        return jdbc.query(
                "SELECT * FROM platform.recovery_task WHERE " + SCOPE
                        + " AND reconciliation_run_id=:run AND task_type='RECONCILIATION_SOURCE' AND status IN ('READY','WAITING','RUNNING') AND available_at<=:now AND (lease_until IS NULL OR lease_until<=:now) ORDER BY available_at,id LIMIT :limit FOR UPDATE SKIP LOCKED",
                scope(scope)
                        .addValue("run", runId)
                        .addValue("now", Timestamp.from(now))
                        .addValue("limit", limit),
                TASK);
    }

    public void lease(UUID taskId, UUID token, Instant leaseUntil, long version, Instant now) {
        jdbc.update(
                """
                UPDATE platform.recovery_task
                SET
                    status='RUNNING',
                    lease_token=:token,
                    lease_until=:leaseUntil,
                    attempt_count=attempt_count+1,
                    version=:version,
                    updated_at=:now
                WHERE id=:id""",
                new MapSqlParameterSource()
                        .addValue("token", token)
                        .addValue("leaseUntil", Timestamp.from(leaseUntil))
                        .addValue("version", version)
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", taskId));
    }

    /** Capture BTs the run's import enumerated that have no task yet, in BT id order. */
    public List<String> unqueued(ProviderScope scope, SourceRun run, int limit) {
        return jdbc.queryForList(
                """
   SELECT DISTINCT b.bt_id FROM platform.import_page p JOIN platform.balance_transaction b ON b.bt_id=ANY(p.bt_ids)
   WHERE p.window_id=:window AND b.organization_id=:organization AND b.environment_id=:environment AND b.provider_account_id=:providerAccount AND b.livemode=:livemode
    AND b.currency=:currency AND b.balance_domain='PAYMENTS' AND b.business_type='charge' AND b.source_object_type='charge'
    AND NOT EXISTS(SELECT 1 FROM platform.recovery_task t WHERE t.reconciliation_run_id=:run AND t.source_bt_id=b.id)
   ORDER BY b.bt_id LIMIT :limit
   """,
                scope(scope)
                        .addValue("window", run.importWindowId())
                        .addValue("currency", run.currency())
                        .addValue("run", run.id())
                        .addValue("limit", limit),
                String.class);
    }

    public Optional<OrderBinding> binding(ProviderScope scope, String paymentIntentId) {
        return one(
                "SELECT * FROM platform.order_provider_binding WHERE " + SCOPE
                        + " AND payment_intent_id=:paymentIntent",
                scope(scope).addValue("paymentIntent", paymentIntentId),
                BINDING);
    }

    /** Locks the order if it belongs to the scope, through a function so the runtime role needs no UPDATE grant. */
    public boolean lockOrder(UUID orderId, ProviderScope scope) {
        Boolean locked = jdbc.queryForObject(
                "SELECT platform.lock_reconciliation_order(:order,:organization,:environment,:providerAccount)",
                new MapSqlParameterSource()
                        .addValue("order", orderId)
                        .addValue("organization", scope.organizationId())
                        .addValue("environment", scope.environmentId())
                        .addValue("providerAccount", scope.providerAccountId()),
                Boolean.class);
        return Boolean.TRUE.equals(locked);
    }

    public Optional<RecoverableOrder> order(UUID id) {
        return one("SELECT * FROM platform.business_order WHERE id=:id", new MapSqlParameterSource("id", id), ORDER);
    }

    public Optional<ExistingPayment> lockPaymentForOrder(UUID orderId) {
        return one(
                "SELECT * FROM platform.payment WHERE business_order_id=:order FOR UPDATE",
                new MapSqlParameterSource("order", orderId),
                PAYMENT);
    }

    public Optional<SourceTask> lockTask(UUID id) {
        return one(
                "SELECT * FROM platform.recovery_task WHERE id=:id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                TASK);
    }

    public boolean hasEvidenceConflict(UUID btId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.bt_evidence_conflict
                WHERE balance_transaction_id=:bt""", new MapSqlParameterSource("bt", btId), Long.class) > 0;
    }

    /** Whether the order's fulfillment eligibility is granted under policy 1 for its amount and an active app. */
    public boolean fulfillmentEligible(RecoverableOrder order) {
        Long eligible = jdbc.queryForObject(
                """
   SELECT count(*) FROM platform.order_fulfillment_eligibility e JOIN platform.application a ON a.id=e.application_id
   WHERE e.order_id=:order AND e.eligible AND e.policy_version=1 AND e.amount_minor=:amount AND a.status='ACTIVE'
    AND (e.organization_id,e.environment_id,e.application_id,e.provider_account_id,e.currency)=(:organization,:environment,:application,:providerAccount,:currency)
   """,
                new MapSqlParameterSource()
                        .addValue("order", order.id())
                        .addValue("amount", order.amountMinor())
                        .addValue("organization", order.organizationId())
                        .addValue("environment", order.environmentId())
                        .addValue("application", order.applicationId())
                        .addValue("providerAccount", order.providerAccountId())
                        .addValue("currency", order.currency()),
                Long.class);
        return eligible == 1;
    }

    public void insertObservation(
            UUID id,
            ProviderScope scope,
            UUID runId,
            UUID btId,
            String outcome,
            String reason,
            String fact,
            String evidence,
            String digest) {
        jdbc.update(
                """
                INSERT INTO platform.reconciliation_source_observation(id,organization_id,environment_id,
                    provider_account_id,livemode,run_id,source_bt_id,outcome,reason,fact,evidence,digest)
                VALUES (:id,:organization,:environment,:providerAccount,:livemode,:run,:bt,:outcome,:reason,:fact::jsonb,:evidence::jsonb,:digest)""",
                scope(scope)
                        .addValue("id", id)
                        .addValue("run", runId)
                        .addValue("bt", btId)
                        .addValue("outcome", outcome)
                        .addValue("reason", reason)
                        .addValue("fact", fact)
                        .addValue("evidence", evidence)
                        .addValue("digest", digest));
    }

    public String observationDigest(UUID id) {
        return jdbc.queryForObject(
                "SELECT digest FROM platform.reconciliation_source_observation WHERE id=:id",
                new MapSqlParameterSource("id", id),
                String.class);
    }

    /**
     * Locks the run, serializing with its finish, and tells whether it is still open. A finished run's report is
     * frozen and receives no further exceptions.
     */
    public boolean lockOpenRun(UUID runId) {
        List<Boolean> open = jdbc.query(
                "SELECT finished_at FROM platform.reconciliation_run WHERE id=:id FOR UPDATE",
                new MapSqlParameterSource("id", runId),
                (rs, n) -> rs.getTimestamp("finished_at") == null);
        return !open.isEmpty() && open.getFirst();
    }

    public void insertException(
            UUID runId, ProviderScope scope, String currency, String reason, String sourceRef, String evidence) {
        jdbc.update(
                """
                INSERT INTO platform.reconciliation_exception(run_id,organization_id,environment_id,
                    provider_account_id,livemode,currency,reason,owner,source_ref,input_versions,evidence)
                VALUES (:run,:organization,:environment,:providerAccount,:livemode,:currency,:reason,'FINANCE_OPERATIONS',:sourceRef,'{}',:evidence::jsonb)""",
                scope(scope)
                        .addValue("run", runId)
                        .addValue("currency", currency)
                        .addValue("reason", reason)
                        .addValue("sourceRef", sourceRef)
                        .addValue("evidence", evidence));
    }

    /**
     * Ends the lease with {@code status}, fenced by token, version and an unexpired lease. Escalation stamps the
     * escalation and age alert times once. Returns the number of rows changed.
     */
    public int complete(
            UUID taskId, UUID token, long version, String status, String reason, Instant availableAt, Instant now) {
        return jdbc.update(
                """
                UPDATE platform.recovery_task
                SET
                    status=:status,
                    reason=:reason,
                    available_at=:availableAt,
                    lease_token=null,
                    lease_until=null,
                    version=version+1,
                    updated_at=:now,
                    escalated_at=CASE WHEN :status='ESCALATED' THEN :now ELSE escalated_at END,
                    age_alert_at=CASE WHEN :status='ESCALATED' THEN coalesce(age_alert_at,:now) ELSE age_alert_at END WHERE
                        id=:id AND lease_token=:token AND version=:version AND lease_until>:now""",
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("reason", reason)
                        .addValue("availableAt", Timestamp.from(availableAt))
                        .addValue("now", Timestamp.from(now))
                        .addValue("id", taskId)
                        .addValue("token", token)
                        .addValue("version", version));
    }

    /** Inserts the payment an L3 link restores for an order that has none, with the run and BT it came from. */
    public void insertPayment(
            UUID paymentId, RecoverableOrder order, Instant expiresAt, UUID runId, UUID btId, String digest) {
        jdbc.update(
                """
    INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,expires_at,origin,reconciliation_run_id,reconciliation_source_bt_id,reconciliation_evidence_digest)
    VALUES (:id,:organization,:environment,:application,:providerAccount,:currency,:order,:amount,:expiresAt,'RECONCILIATION',:run,:bt,:digest)
    """,
                new MapSqlParameterSource()
                        .addValue("id", paymentId)
                        .addValue("organization", order.organizationId())
                        .addValue("environment", order.environmentId())
                        .addValue("application", order.applicationId())
                        .addValue("providerAccount", order.providerAccountId())
                        .addValue("currency", order.currency())
                        .addValue("order", order.id())
                        .addValue("amount", order.amountMinor())
                        .addValue("expiresAt", Timestamp.from(expiresAt))
                        .addValue("run", runId)
                        .addValue("bt", btId)
                        .addValue("digest", digest));
    }

    public void insertProvenance(
            UUID paymentId,
            ProviderScope scope,
            RecoverableOrder order,
            UUID runId,
            UUID btId,
            UUID bindingId,
            UUID observationId,
            String chargeId,
            String paymentIntentId,
            String digest) {
        jdbc.update(
                """
    INSERT INTO platform.reconciliation_payment_provenance(payment_id,organization_id,environment_id,application_id,provider_account_id,livemode,currency,run_id,source_bt_id,binding_id,observation_id,charge_id,payment_intent_id,evidence_digest)
    VALUES (:payment,:organization,:environment,:application,:providerAccount,:livemode,:currency,:run,:bt,:binding,:observation,:charge,:paymentIntent,:digest)
    """,
                scope(scope)
                        .addValue("payment", paymentId)
                        .addValue("application", order.applicationId())
                        .addValue("currency", order.currency())
                        .addValue("run", runId)
                        .addValue("bt", btId)
                        .addValue("binding", bindingId)
                        .addValue("observation", observationId)
                        .addValue("charge", chargeId)
                        .addValue("paymentIntent", paymentIntentId)
                        .addValue("digest", digest));
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private static MapSqlParameterSource scope(ProviderScope scope) {
        return new MapSqlParameterSource()
                .addValue("organization", scope.organizationId())
                .addValue("environment", scope.environmentId())
                .addValue("providerAccount", scope.providerAccountId())
                .addValue("livemode", scope.livemode());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
