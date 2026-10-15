package com.paymentsledger.ops;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.request.RequestContext;
import com.paymentsledger.identity.OpsContext;
import com.paymentsledger.webhook.WebhookNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audit survives command rollback. Only hashes/counts and controlled structural identifiers are
 * retained.
 */
@Service
public class OpsAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public OpsAuditService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID record(OpsContext c, String action, String target, JsonNode body, String result) {
        return tx.execute(s -> persist(c, action, target, body, result, null, null));
    }

    public UUID recordInTransaction(
            OpsContext c, String action, String target, JsonNode body, String result, JsonNode before, JsonNode after) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Success audit requires command transaction");
        return persist(c, action, target, body, result, before, after);
    }

    private UUID persist(
            OpsContext c, String action, String target, JsonNode body, String result, JsonNode before, JsonNode after) {
        var reason = json.createObjectNode();
        if (body != null && body.path("reason").isTextual()) {
            reason.put("digest", digest(body.path("reason").asText()));
            reason.put("length", body.path("reason").asText().length());
        }
        var evidence = json.createObjectNode();
        if (body != null) {
            evidence.put(
                    "referenceCount",
                    body.path("evidenceRefs").isArray()
                            ? body.path("evidenceRefs").size()
                            : 0);
            evidence.put(
                    "entryCount",
                    body.path("proposedEntries").isArray()
                            ? body.path("proposedEntries").size()
                            : 0);
        }
        UUID id = UUID.randomUUID();
        String request =
                "sha256:" + digest(java.util.Objects.toString(RequestContext.currentRequestId(), "unavailable"));
        var beforeSummary = summary(before);
        var afterSummary = summary(after);
        afterSummary.put("result", result);
        if (body != null && body.path("evidenceRefs").isArray()) {
            var digests = evidence.putArray("referenceDigests");
            for (var ref : body.path("evidenceRefs")) digests.add(digest(ref.toString()));
        }
        jdbc.update(
                "INSERT INTO"
                        + " platform.audit_log(id,organization_id,environment_id,actor_id,role,action,target,request_id,reason_summary,evidence_summary,before_summary,after_summary,result)"
                        + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)",
                id,
                c == null ? null : c.organizationId(),
                c == null ? null : c.environmentId(),
                c == null ? null : c.identityId(),
                c == null ? "ANONYMOUS" : c.role(),
                action,
                target,
                request,
                reason.toString(),
                evidence.toString(),
                beforeSummary.toString(),
                afterSummary.toString(),
                result);
        return id;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode summary(JsonNode value) {
        var out = json.createObjectNode();
        if (value != null)
            for (String field : java.util.List.of(
                    "adjustmentId",
                    "runId",
                    "bankStatementId",
                    "taskId",
                    "exceptionId",
                    "status",
                    "postedJournalId",
                    "createdBy",
                    "approvedBy",
                    "lineCount",
                    "matchedCount",
                    "conflictCount",
                    "dataCompleteness")) if (value.has(field)) out.set(field, value.get(field));
        return out;
    }

    public static String digest(String s) {
        return WebhookNormalizer.digest(s.getBytes(StandardCharsets.UTF_8));
    }
}
