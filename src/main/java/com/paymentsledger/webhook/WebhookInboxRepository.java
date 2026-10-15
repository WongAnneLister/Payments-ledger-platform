package com.paymentsledger.webhook;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class WebhookInboxRepository {
    final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public record Receipt(UUID inboxId, boolean duplicate) {}

    public record Claim(UUID id, UUID token, Map<String, Object> row) {}

    public WebhookInboxRepository(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    <T> T transaction(Supplier<T> action) {
        return tx.execute(s -> action.get());
    }

    public Receipt receive(
            ProviderScope scope, String accountRef, byte[] raw, ProviderCallResult<VerifiedWebhook> verified) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw invalid();
        }
        if (verified == null
                || verified.classification() != ProviderCallClassification.SUCCESS
                || verified.payload() == null) throw invalid();
        var v = verified.payload();
        var object = root.path("data").path("object");
        if (!root.path("id").asText().equals(v.eventId())
                || !root.path("type").asText().equals(v.eventType())
                || !object.path("id").asText().equals(v.objectId())
                || !root.path("created").isIntegralNumber()
                || root.path("created").asLong() != v.createdAt().getEpochSecond()
                || !root.path("livemode").isBoolean()
                || root.path("livemode").asBoolean() != v.livemode()
                || v.livemode()
                || scope.livemode()
                || (root.hasNonNull("account") && !root.path("account").asText().equals(accountRef))
                || !root.path("api_version").asText().equals(verified.evidence().apiVersion())
                || !object.path("object").isTextual()
                || object.path("livemode").asBoolean(false)) throw invalid();
        JsonNode sanitized = WebhookNormalizer.sanitize(object, json);
        String digest = WebhookNormalizer.digest(raw);
        Instant now = clock.instant();
        return transaction(() -> {
            var valid = jdbc.queryForList(
                    """
                    SELECT p.id
                    FROM platform.provider_account p
                    JOIN platform.organization o ON o.id=p.organization_id
                    JOIN platform.environment_config e ON e.id=p.environment_id AND e.organization_id=p.organization_id
                    WHERE p.organization_id=?
                        AND p.environment_id=?
                        AND p.id=?
                        AND p.provider='STRIPE'
                        AND p.provider_account_ref=?
                        AND p.status='ACTIVE'
                        AND o.status='ACTIVE'
                        AND e.status='ACTIVE'
                        AND NOT p.livemode
                        AND NOT e.livemode""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), accountRef);
            if (valid.isEmpty())
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Webhook account binding unavailable");
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update(
                    """
                    INSERT INTO platform.webhook_inbox(id,organization_id,environment_id,provider_account_id,livemode,
                        provider_account_ref,event_id,event_type,object_id,object_type,event_created_at,received_at,
                        verified_at,api_version,raw_digest,object_snapshot,available_at)
                    VALUES (?,?,?,?,false,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                    ON CONFLICT(organization_id,environment_id,provider_account_id,livemode,event_id)
                    DO NOTHING""",
                    id,
                    scope.organizationId(),
                    scope.environmentId(),
                    scope.providerAccountId(),
                    accountRef,
                    v.eventId(),
                    v.eventType(),
                    v.objectId(),
                    object.path("object").asText(),
                    Timestamp.from(v.createdAt()),
                    Timestamp.from(now),
                    Timestamp.from(
                            verified.evidence().respondedAt() == null
                                    ? now
                                    : verified.evidence().respondedAt()),
                    verified.evidence().apiVersion(),
                    digest,
                    sanitized.toString(),
                    Timestamp.from(now));
            if (inserted == 1) return new Receipt(id, false);
            var previous = jdbc.queryForMap(
                    """
                    SELECT id,raw_digest
                    FROM platform.webhook_inbox
                    WHERE organization_id=?
                        AND environment_id=?
                        AND provider_account_id=?
                        AND NOT livemode
                        AND event_id=?""", scope.organizationId(), scope.environmentId(), scope.providerAccountId(), v.eventId());
            if (!digest.equals(previous.get("raw_digest")))
                throw new ApiException(ErrorCode.EVIDENCE_CONFLICT, "Webhook event content conflict");
            return new Receipt((UUID) previous.get("id"), true);
        });
    }

    public List<Claim> claimDue(int limit) {
        return transaction(() -> {
            var rows = jdbc.queryForList(
                    """
                    SELECT *
                    FROM platform.webhook_inbox
                    WHERE status<>'DONE' AND available_at<=? AND (lease_until IS NULL OR lease_until<=?)
                    ORDER BY available_at,id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED""",
                    Timestamp.from(clock.instant()),
                    Timestamp.from(clock.instant()),
                    Math.min(100, Math.max(0, limit)));
            var claims = new ArrayList<Claim>();
            for (var row : rows) {
                UUID token = UUID.randomUUID(), id = (UUID) row.get("id");
                jdbc.update("""
                        UPDATE platform.webhook_inbox
                        SET status='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1
                        WHERE id=?""", token, Timestamp.from(clock.instant().plusSeconds(30)), id);
                claims.add(new Claim(id, token, row));
            }
            return claims;
        });
    }

    boolean owns(Claim claim) {
        var rows = jdbc.queryForList("""
                SELECT id
                FROM platform.webhook_inbox
                WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>?
                FOR UPDATE""", claim.id(), claim.token(), Timestamp.from(clock.instant()));
        return !rows.isEmpty();
    }

    void done(Claim claim) {
        jdbc.update("""
                UPDATE platform.webhook_inbox
                SET status='DONE',processed_at=?,lease_token=null,lease_until=null,reason=null
                WHERE id=? AND lease_token=?""", Timestamp.from(clock.instant()), claim.id(), claim.token());
        jdbc.update("""
                UPDATE platform.dependency_wait
                SET resolved_at=?,last_attempt_at=?
                WHERE inbox_id=?""", Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), claim.id());
    }

    void waitFor(Claim claim, String type, String dependency, String reason) {
        var r = claim.row();
        Instant now = clock.instant();
        long seconds = Math.min(300, 5L << Math.min(6, ((Number) r.get("attempt_count")).intValue()));
        jdbc.update(
                """
                INSERT INTO platform.dependency_wait(organization_id,environment_id,provider_account_id,inbox_id,
                    dependency_type,dependency_id,reason,first_seen_at,last_attempt_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(inbox_id)
                DO UPDATE SET
                    dependency_type=excluded.dependency_type,
                    dependency_id=excluded.dependency_id,
                    reason=excluded.reason,
                    last_attempt_at=excluded.last_attempt_at,
                    resolved_at=null""",
                r.get("organization_id"),
                r.get("environment_id"),
                r.get("provider_account_id"),
                claim.id(),
                type,
                dependency,
                reason,
                Timestamp.from(now),
                Timestamp.from(now));
        jdbc.update("""
                UPDATE platform.webhook_inbox
                SET status='WAITING',reason=?,available_at=?,lease_token=null,lease_until=null
                WHERE id=? AND lease_token=?""", reason, Timestamp.from(now.plusSeconds(seconds)), claim.id(), claim.token());
    }

    void retry(Claim claim) {
        transaction(() -> {
            if (owns(claim)) waitFor(claim, "RETRY", null, "PROCESSING_RETRY");
            return null;
        });
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.SIGNATURE_VERIFICATION_FAILED, "Verified webhook does not match event");
    }
}
