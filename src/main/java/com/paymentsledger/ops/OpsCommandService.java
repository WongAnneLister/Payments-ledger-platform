package com.paymentsledger.ops;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.bank.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.financialingestion.FinancialImportService;
import com.paymentsledger.identity.OpsContext;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.reconciliation.ReconciliationService;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operational transaction owner. No provider network calls and no direct ledger/projection writes.
 */
@Service
public class OpsCommandService {
    public record Result(JsonNode body, boolean replay, JsonNode before) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final LedgerPostingService ledger;
    private final BankStatementImporter bank;
    private final BankMatcher matcher;
    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final OpsAuditService audit;
    private final com.paymentsledger.payment.RecoveryTaskOperations taskOperations;

    public OpsCommandService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager manager,
            LedgerPostingService ledger,
            BankStatementImporter bank,
            BankMatcher matcher,
            Clock clock,
            OpsAuditService audit,
            com.paymentsledger.payment.RecoveryTaskOperations taskOperations) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.ledger = ledger;
        this.bank = bank;
        this.matcher = matcher;
        this.clock = clock;
        this.audit = audit;
        this.taskOperations = taskOperations;
        // Pure creation reuses the domain owner. A null read port cannot enable provider fetching or
        // workers in API.
        var importer = new FinancialImportService(jdbc, manager, json, null, clock);
        this.reconciliation = new ReconciliationService(
                jdbc, manager, json, importer, new BalanceRebuildService(jdbc, clock), matcher, clock);
    }

    public Result execute(OpsContext c, String action, UUID target, String key, JsonNode body) {
        OpsAuthorization.write(c, action);
        if (key == null || key.isBlank())
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key required");
        if (key.length() > 128 || !key.matches("[\\x21-\\x7e]+")) throw invalid();
        validate(body, action);
        String namespace = action.equals("CREATE_ADJUSTMENT")
                ? text(body, "targetType") + ":" + adjustmentTarget(body)
                : Objects.toString(target, "collection");
        String fingerprint =
                OpsAuditService.digest(c.identityId() + ":" + action + ":" + target + ":" + canonical(body));
        try {
            return tx.execute(s -> {
                // Serializes immutable command insertion and duplicate same-key execution, including
                // bank matching.
                jdbc.queryForObject(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?,16))",
                        Object.class,
                        c.organizationId() + ":" + c.environmentId() + ":" + action + ":" + key);
                var prior = one(
                        "SELECT actor_id,target,request_fingerprint,result FROM platform.ops_command"
                                + " WHERE organization_id=? AND environment_id=? AND action=? AND target=?"
                                + " AND idempotency_key=?",
                        c.organizationId(),
                        c.environmentId(),
                        action,
                        namespace,
                        key);
                if (prior != null) {
                    if (!fingerprint.equals(prior.get("request_fingerprint")))
                        throw new ApiException(
                                ErrorCode.IDEMPOTENCY_CONFLICT, "Key already used for a different command");
                    var cached = (ObjectNode) tree(prior.get("result"));
                    JsonNode current = replaySummary(c, action, target, cached);
                    cached.put(
                            "auditLogId",
                            audit.recordInTransaction(
                                            c,
                                            action,
                                            Objects.toString(target, "collection"),
                                            body,
                                            "REPLAYED",
                                            current,
                                            current)
                                    .toString());
                    return new Result(cached, true, current);
                }
                JsonNode before = before(c, action, target);
                JsonNode result = switch (action) {
                    case "CREATE_RECONCILIATION" -> createRun(c, body);
                    case "IMPORT_BANK" -> importBank(c, body);
                    case "EXCEPTION_ACTION" -> exception(c, target, body);
                    case "RETRY_TASK" -> retry(c, target);
                    case "CREATE_ADJUSTMENT" -> adjust(c, body);
                    case "APPROVE_ADJUSTMENT" -> approve(c, target, body);
                    default -> throw invalid();
                };
                ((ObjectNode) result)
                        .put(
                                "auditLogId",
                                audit.recordInTransaction(
                                                c,
                                                action,
                                                Objects.toString(target, "collection"),
                                                body,
                                                "SUCCEEDED",
                                                before,
                                                result)
                                        .toString());
                jdbc.update(
                        "INSERT INTO"
                                + " platform.ops_command(organization_id,environment_id,actor_id,action,target,idempotency_key,request_fingerprint,result)"
                                + " VALUES (?,?,?,?,?,?,?,?::jsonb)",
                        c.organizationId(),
                        c.environmentId(),
                        c.identityId(),
                        action,
                        namespace,
                        key,
                        fingerprint,
                        result.toString());
                return new Result(result, false, before);
            });
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private JsonNode createRun(OpsContext c, JsonNode body) {
        String currency = text(body, "currency");
        currency(c, currency);
        ProviderScope scope = provider(c, body);
        LocalDate day;
        try {
            day = LocalDate.parse(text(body, "businessDate"));
        } catch (RuntimeException e) {
            throw invalid();
        }
        int lookback = body.has("overlapLookbackDays") ? integer(body, "overlapLookbackDays", 0, 31) : 7;
        ZoneId zone;
        try {
            zone = ZoneId.of(jdbc.queryForObject(
                    "SELECT business_zone FROM platform.ingestion_policy WHERE organization_id=? AND"
                            + " environment_id=? AND provider_account_id=?",
                    String.class,
                    c.organizationId(),
                    c.environmentId(),
                    scope.providerAccountId()));
        } catch (RuntimeException e) {
            throw invalid();
        }
        Instant until = day.plusDays(1).atStartOfDay(zone).toInstant();
        if (until.isAfter(clock.instant())) throw invalid();
        UUID run = reconciliation.createBackfill(
                scope, currency, day.minusDays(lookback).atStartOfDay(zone).toInstant(), until, zone);
        jdbc.update(
                "INSERT INTO"
                        + " platform.ops_reconciliation_request(run_id,run_type,lookback_days,business_date)"
                        + " VALUES (?,?,?,?)",
                run,
                text(body, "runType"),
                lookback,
                day);
        return json.createObjectNode()
                .put("runId", run.toString())
                .put("runType", text(body, "runType"))
                .put("status", "CREATED")
                .put("dataCompleteness", "INCOMPLETE")
                .put("businessDate", day.toString())
                .put("currency", currency);
    }

    private JsonNode importBank(OpsContext c, JsonNode b) {
        if (!"1".equals(text(b, "schemaVersion"))
                || !b.path("lines").isArray()
                || b.path("lines").isEmpty()
                || b.path("lines").size() > 1000) throw invalid();
        String fixture = identifier(b, "fixtureId"), digest = text(b, "contentDigest");
        var source = (ObjectNode) b.deepCopy();
        source.remove(
                List.of("reason", "contentDigest", "providerAccountId", "organizationId", "environmentId", "livemode"));
        // File identity includes all external evidence fields, including supplied description and
        // coverage.
        var sorted = new ArrayList<JsonNode>();
        source.path("lines").forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(l -> number(l, "lineNo")));
        source.putArray("lines").addAll(sorted);
        if (digest == null
                || !digest.matches("[a-f0-9]{64}")
                || !digest.equals(OpsAuditService.digest(canonical(source).toString()))) throw invalid();
        ProviderScope scope = provider(c, b);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,17))", Object.class, scope + ":" + digest);
        var duplicate = one(
                "SELECT statement_id,fixture_id FROM platform.ops_bank_import WHERE organization_id=?"
                        + " AND environment_id=? AND provider_account_id=? AND content_digest=?",
                c.organizationId(),
                c.environmentId(),
                scope.providerAccountId(),
                digest);
        if (duplicate != null)
            return bankResult(
                    (UUID) duplicate.get("statement_id"),
                    duplicate.get("fixture_id").toString(),
                    true);
        var previous = one(
                "SELECT content_digest FROM platform.ops_bank_import WHERE organization_id=? AND"
                        + " environment_id=? AND provider_account_id=? AND fixture_id=?",
                c.organizationId(),
                c.environmentId(),
                scope.providerAccountId(),
                fixture);
        if (previous != null)
            throw new ApiException(ErrorCode.IMPORT_DUPLICATE_CONFLICT, "Fixture identity has different content");
        String currency = null;
        Instant from = null, until = null;
        var translated = json.createObjectNode();
        var lines = translated.putArray("lines");
        var seen = new HashSet<Long>();
        for (var line : sorted) {
            fields(
                    line,
                    Set.of("lineNo", "valueDate", "amountMinor", "currency", "bankRef", "feeMinor", "description"));
            long no = number(line, "lineNo"),
                    amount = number(line, "amountMinor"),
                    fee = line.has("feeMinor") ? number(line, "feeMinor") : 0;
            if (no <= 0
                    || !seen.add(no)
                    || amount == 0
                    || amount == Long.MIN_VALUE
                    || fee < 0
                    || fee > Math.abs(amount)
                    || amount < 0 && fee != 0) throw invalid();
            String cc = text(line, "currency");
            currency(c, cc);
            if (currency != null && !currency.equals(cc)) throw invalid();
            currency = cc;
            Instant date;
            try {
                date = LocalDate.parse(text(line, "valueDate"))
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            } catch (RuntimeException e) {
                throw invalid();
            }
            from = from == null || date.isBefore(from) ? date : from;
            Instant end = date.plus(Duration.ofDays(1));
            until = until == null || end.isAfter(until) ? end : until;
            String ref = identifier(line, "bankRef");
            if (!ref.matches("[A-Za-z0-9_]{1,255}")) throw invalid();
            var output = lines.addObject()
                    .put("lineId", fixture + ":" + no)
                    .put("kind", amount > 0 ? "CREDIT" : "RETURN")
                    .put("bookedAt", date.toString())
                    .put("principalMinor", Math.abs(amount))
                    .put("feeMinor", fee);
            output.putArray("payoutReferences").add(ref);
        }
        boolean explicit = b.has("coverageFrom") && b.has("coverageUntil");
        if (b.has("coverageFrom") != b.has("coverageUntil")) throw invalid();
        if (explicit) {
            try {
                from = Instant.parse(text(b, "coverageFrom"));
                until = Instant.parse(text(b, "coverageUntil"));
            } catch (RuntimeException e) {
                throw invalid();
            }
        }
        boolean complete = explicit && b.path("complete").asBoolean(false);
        if (b.has("complete") && !b.path("complete").isBoolean()) throw invalid();
        translated
                .put("schemaVersion", 1)
                .put("evidenceType", "SIMULATED_BANK_EVIDENCE")
                .put("fixtureId", fixture)
                .put("bankAccountRef", b.has("bankAccountRef") ? identifier(b, "bankAccountRef") : "ops-simulated-bank")
                .put("currency", currency)
                .put("coverageFrom", from.toString())
                .put("coverageUntil", until.toString())
                .put("complete", complete);
        UUID statement;
        try {
            statement = bank.importStatement(scope, translated);
        } catch (IllegalArgumentException failure) {
            throw new ApiException(
                    failure.getCause() instanceof org.springframework.dao.DuplicateKeyException
                                    || failure.getMessage().equals("Fixture identity has different immutable evidence")
                            ? ErrorCode.IMPORT_DUPLICATE_CONFLICT
                            : ErrorCode.VALIDATION_ERROR,
                    "Bank fixture evidence conflicts or is invalid");
        }
        jdbc.update(
                "INSERT INTO"
                        + " platform.ops_bank_import(organization_id,environment_id,provider_account_id,content_digest,statement_id,fixture_id,schema_version)"
                        + " VALUES (?,?,?,?,?,?,?)",
                c.organizationId(),
                c.environmentId(),
                scope.providerAccountId(),
                digest,
                statement,
                fixture,
                "1");
        // Only unambiguous stored scoped payout references are eligible for the existing matcher.
        for (var line : jdbc.queryForList(
                "SELECT id,principal_minor,kind,payout_references FROM platform.bank_statement_line"
                        + " WHERE statement_id=? ORDER BY external_line_id",
                statement)) {
            String ref;
            try {
                ref = ((String[]) ((java.sql.Array) line.get("payout_references")).getArray())[0];
            } catch (Exception e) {
                throw invalid();
            }
            var candidates = jdbc.queryForList(
                    "SELECT id,transit_minor,bank_confirmed_minor,arrival_date FROM platform.payout WHERE"
                            + " organization_id=? AND environment_id=? AND provider_account_id=? AND NOT"
                            + " livemode AND currency=? AND provider_payout_id=? AND NOT conflict",
                    c.organizationId(),
                    c.environmentId(),
                    scope.providerAccountId(),
                    currency,
                    ref);
            if (candidates.size() != 1 || candidates.getFirst().get("arrival_date") == null) continue;
            var payout = candidates.getFirst();
            long available = ((Number)
                            payout.get(line.get("kind").equals("CREDIT") ? "transit_minor" : "bank_confirmed_minor"))
                    .longValue();
            long amount = ((Number) line.get("principal_minor")).longValue();
            if (available >= amount) matcher.match(scope, (UUID) payout.get("id"), (UUID) line.get("id"), amount);
        }
        return bankResult(statement, fixture, false);
    }

    private JsonNode bankResult(UUID id, String fixture, boolean duplicate) {
        return json.createObjectNode()
                .put("bankStatementId", id.toString())
                .put("fixtureId", fixture)
                .put("duplicate", duplicate)
                .put(
                        "lineCount",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform.bank_statement_line WHERE statement_id=?",
                                Long.class,
                                id))
                .put(
                        "matchedCount",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform.payout_bank_match m JOIN"
                                        + " platform.bank_statement_line l ON l.id=m.line_id WHERE l.statement_id=?",
                                Long.class,
                                id))
                .put(
                        "conflictCount",
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT p.id) FROM platform.bank_statement_line l JOIN"
                                        + " platform.bank_statement s ON s.id=l.statement_id JOIN platform.payout p ON"
                                        + " p.organization_id=s.organization_id AND p.environment_id=s.environment_id"
                                        + " AND p.provider_account_id=s.provider_account_id AND p.currency=s.currency"
                                        + " AND p.provider_payout_id=ANY(l.payout_references) WHERE s.id=? AND"
                                        + " p.conflict",
                                Long.class,
                                id))
                .put("evidenceSource", "SIMULATED_BANK_EVIDENCE");
    }

    private JsonNode exception(OpsContext c, UUID id, JsonNode b) {
        lockException(c, id);
        var row = scoped(c, "reconciliation_exception", id, false);
        String action = text(b, "actionType");
        String status = switch (action) {
            case "INVESTIGATE" -> "INVESTIGATING";
            case "WAIT_EVIDENCE" -> "WAITING_EVIDENCE";
            case "RESOLVE" -> "RESOLVED";
            case "REOPEN" -> "REOPENED";
            default -> throw invalid();
        };
        JsonNode refs = b.has("evidenceRefs") ? b.path("evidenceRefs") : json.createArrayNode();
        UUID journal = b.hasNonNull("resolutionJournalId") ? uuid(text(b, "resolutionJournalId")) : null;
        evidence(c, refs, row.get("currency").toString(), (UUID) row.get("provider_account_id"), false);
        if (status.equals("RESOLVED")) resolutionEvidence(c, id, row, refs);
        if (journal != null) {
            var j = scoped(c, "journal", journal, false);
            if (!row.get("currency").equals(j.get("currency"))
                    || jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.ops_adjustment WHERE posted_journal_id=? AND"
                                            + " status='APPROVED' AND organization_id=? AND environment_id=? AND"
                                            + " target_type='EXCEPTION' AND target_id=?",
                                    Long.class,
                                    journal,
                                    c.organizationId(),
                                    c.environmentId(),
                                    id)
                            != 1)
                throw new ApiException(
                        ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "A scoped approved adjustment journal is required");
        }
        if (status.equals("RESOLVED") && refs.isEmpty() && journal == null)
            throw new ApiException(ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Resolution requires verified evidence");
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " platform.ops_exception_action(id,exception_id,organization_id,environment_id,actor_id,status,evidence_refs,resolution_journal_id,reason_digest)"
                        + " VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
                actionId,
                id,
                c.organizationId(),
                c.environmentId(),
                c.identityId(),
                status,
                refs.toString(),
                journal,
                OpsAuditService.digest(text(b, "reason")));
        return json.createObjectNode()
                .put("exceptionId", id.toString())
                .put("status", status)
                .put("actionId", actionId.toString());
    }

    private JsonNode retry(OpsContext c, UUID id) {
        return json.valueToTree(taskOperations.wake(c.organizationId(), c.environmentId(), id));
    }

    private UUID adjustmentTarget(JsonNode body) {
        return OpsAuthorization.resourceId(
                text(body, "targetId"),
                switch (text(body, "targetType")) {
                    case "PAYMENT" -> "pay";
                    case "REFUND" -> "ref";
                    case "DISPUTE" -> "dis";
                    case "PAYOUT" -> "po";
                    case "BALANCE" -> "acct";
                    case "EXCEPTION" -> "exc";
                    default -> throw invalid();
                });
    }

    private void lockException(OpsContext c, UUID id) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,18))",
                Object.class,
                c.organizationId() + ":" + c.environmentId() + ":" + id);
    }

    /** Scope authorizes viewing a fact; resolution also requires a relation to this finding. */
    private void resolutionEvidence(OpsContext c, UUID exceptionId, Map<String, Object> finding, JsonNode refs) {
        String source = Objects.toString(finding.get("source_ref"), "");
        JsonNode metadata = tree(finding.get("evidence"));
        for (var ref : refs) {
            String[] parts = ref.asText().split(":", 2);
            UUID evidenceId = uuid(parts[1]);
            boolean related = switch (parts[0]) {
                case "journal" ->
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.ops_adjustment WHERE posted_journal_id=? AND"
                                            + " target_type='EXCEPTION' AND target_id=? AND status='APPROVED' AND"
                                            + " organization_id=? AND environment_id=?",
                                    Long.class,
                                    evidenceId,
                                    exceptionId,
                                    c.organizationId(),
                                    c.environmentId())
                            == 1;
                case "reconciliation-evidence" ->
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.reconciliation_evidence WHERE id=? AND"
                                            + " run_id=?",
                                    Long.class,
                                    evidenceId,
                                    finding.get("run_id"))
                            == 1;
                case "source-observation" ->
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM platform.reconciliation_source_observation o JOIN"
                                            + " platform.balance_transaction b ON b.id=o.source_bt_id WHERE o.id=?"
                                            + " AND o.run_id=? AND (?='' OR b.bt_id=?)",
                                    Long.class,
                                    evidenceId,
                                    finding.get("run_id"),
                                    source,
                                    source)
                            == 1;
                case "balance-transaction" ->
                    !source.isEmpty()
                            && jdbc.queryForObject(
                                            "SELECT count(*) FROM platform.balance_transaction WHERE id=? AND"
                                                    + " bt_id=?",
                                            Long.class,
                                            evidenceId,
                                            source)
                                    == 1;
                case "provider-observation" ->
                    !metadata.path("sourceId").asText().isBlank()
                            && jdbc.queryForObject(
                                            "SELECT count(*) FROM platform.provider_observation WHERE id=? AND"
                                                    + " external_object_id=? AND fact->>'sourceObjectType'=?",
                                            Long.class,
                                            evidenceId,
                                            metadata.path("sourceId").asText(),
                                            metadata.path("sourceType").asText())
                                    == 1;
                case "bank-line", "bank-statement" -> relatedBankEvidence(evidenceId, parts[0], source);
                default -> false;
            };
            if (!related)
                throw new ApiException(
                        ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Evidence is not related to this finding");
        }
    }

    private boolean relatedBankEvidence(UUID evidenceId, String kind, String source) {
        if (source.isEmpty()) return false;
        String selector = kind.equals("bank-line") ? "l.id=?" : "l.statement_id=?";
        return jdbc.queryForObject(
                        "SELECT count(*) FROM platform.bank_statement_line l JOIN platform.bank_statement s ON"
                                + " s.id=l.statement_id JOIN platform.balance_transaction b ON"
                                + " b.organization_id=s.organization_id AND b.environment_id=s.environment_id AND"
                                + " b.provider_account_id=s.provider_account_id AND b.currency=s.currency WHERE "
                                + selector
                                + " AND b.bt_id=? AND b.source_object_type='payout' AND"
                                + " b.source_object_id=ANY(l.payout_references)",
                        Long.class,
                        evidenceId,
                        source)
                > 0;
    }

    private JsonNode adjust(OpsContext c, JsonNode b) {
        String type = text(b, "targetType");
        UUID target = adjustmentTarget(b);
        String table = switch (type) {
            case "PAYMENT" -> "payment";
            case "REFUND" -> "refund";
            case "DISPUTE" -> "dispute";
            case "PAYOUT" -> "payout";
            case "BALANCE" -> "ledger_account";
            case "EXCEPTION" -> "reconciliation_exception";
            default -> throw invalid();
        };
        var row = scoped(c, table, target, false);
        String currency = row.get("currency").toString();
        UUID provider = (UUID) row.get("provider_account_id");
        currency(c, currency);
        activeProvider(c, provider);
        JsonNode entries = b.path("proposedEntries"), refs = b.path("evidenceRefs");
        evidence(c, refs, currency, provider, true);
        entries(c, entries, currency, provider);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " platform.ops_adjustment(id,organization_id,environment_id,provider_account_id,currency,target_type,target_id,created_by,entries,evidence_refs,reason_digest,reason_length)"
                        + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)",
                id,
                c.organizationId(),
                c.environmentId(),
                provider,
                currency,
                type,
                target,
                c.identityId(),
                entries.toString(),
                refs.toString(),
                OpsAuditService.digest(text(b, "reason")),
                text(b, "reason").length());
        return adjustment(scoped(c, "ops_adjustment", id, false));
    }

    private JsonNode approve(OpsContext c, UUID id, JsonNode b) {
        var row = scoped(c, "ops_adjustment", id, true);
        if (c.identityId().equals(row.get("created_by")))
            throw new ApiException(ErrorCode.APPROVAL_SELF_NOT_ALLOWED, "Author cannot approve their adjustment");
        String decision = text(b, "decision"), state = row.get("status").toString();
        if (!state.equals("PENDING_APPROVAL")) {
            if (!state.equals(decision.equals("APPROVE") ? "APPROVED" : "REJECTED"))
                throw new ApiException(ErrorCode.EVIDENCE_CONFLICT, "Adjustment already has a final decision");
            return adjustment(row);
        }
        currency(c, row.get("currency").toString());
        UUID provider = (UUID) row.get("provider_account_id");
        activeProvider(c, provider);
        evidence(c, tree(row.get("evidence_refs")), row.get("currency").toString(), provider, true);
        var lines = entries(c, tree(row.get("entries")), row.get("currency").toString(), provider);
        UUID journal = null;
        if (decision.equals("APPROVE"))
            journal = ledger.post(
                    new LedgerScope(
                            c.organizationId(),
                            c.environmentId(),
                            row.get("currency").toString(),
                            provider),
                    new PostingCommand(
                            "ops.adjustment:" + id,
                            "OPS_ADJUSTMENT",
                            "INTERNAL",
                            id.toString(),
                            json.createObjectNode()
                                    .put("adjustmentId", id.toString())
                                    .put(
                                            "evidenceCount",
                                            tree(row.get("evidence_refs")).size()),
                            lines,
                            null));
        jdbc.update(
                "UPDATE platform.ops_adjustment SET"
                        + " status=?,approved_by=?,approved_at=?,posted_journal_id=? WHERE id=? AND"
                        + " status='PENDING_APPROVAL'",
                decision.equals("APPROVE") ? "APPROVED" : "REJECTED",
                c.identityId(),
                Timestamp.from(clock.instant()),
                journal,
                id);
        return adjustment(scoped(c, "ops_adjustment", id, false));
    }

    /** Replays return their original result but audit the resource observed during this replay. */
    private JsonNode replaySummary(OpsContext c, String action, UUID target, JsonNode cached) {
        if (target != null) return before(c, action, target);
        return switch (action) {
            case "CREATE_ADJUSTMENT" ->
                adjustment(scoped(
                        c, "ops_adjustment", uuid(cached.path("adjustmentId").asText()), false));
            case "IMPORT_BANK" -> {
                UUID statementId = uuid(cached.path("bankStatementId").asText());
                var statement = scoped(c, "bank_statement", statementId, false);
                yield bankResult(statementId, statement.get("fixture_id").toString(), true);
            }
            case "CREATE_RECONCILIATION" -> {
                UUID runId = uuid(cached.path("runId").asText());
                var run = scoped(c, "reconciliation_run", runId, false);
                var cursor = one("SELECT accepted_pages FROM platform.reconciliation_cursor WHERE run_id=?", runId);
                boolean complete = "COMPLETE".equals(run.get("state"));
                yield json.createObjectNode()
                        .put("runId", runId.toString())
                        .put(
                                "status",
                                run.get("finished_at") != null
                                        ? complete ? "COMPLETED" : "INCOMPLETE"
                                        : ((Number) cursor.get("accepted_pages")).longValue() > 0
                                                ? "FETCHING"
                                                : "CREATED")
                        .put("dataCompleteness", complete ? "COMPLETE" : "INCOMPLETE");
            }
            default -> throw invalid();
        };
    }

    private JsonNode before(OpsContext c, String action, UUID target) {
        if (target == null) return json.createObjectNode();
        String table = switch (action) {
            case "APPROVE_ADJUSTMENT" -> "ops_adjustment";
            case "RETRY_TASK" -> "recovery_task";
            case "EXCEPTION_ACTION" -> "reconciliation_exception";
            default -> null;
        };
        if (table == null) return json.createObjectNode();
        if (action.equals("EXCEPTION_ACTION")) lockException(c, target);
        var row = scoped(c, table, target, false);
        var out = json.createObjectNode();
        if (action.equals("EXCEPTION_ACTION")) {
            var latest = one(
                    "SELECT status FROM platform.ops_exception_action WHERE exception_id=? ORDER BY"
                            + " action_order DESC LIMIT 1",
                    target);
            out.put("exceptionId", target.toString());
            out.put("status", latest == null ? "OPEN" : latest.get("status").toString());
        } else if (action.equals("RETRY_TASK")) {
            out.put("taskId", target.toString());
            out.put(
                    "status",
                    switch (row.get("status").toString()) {
                        case "READY", "WAITING" -> "PENDING";
                        case "RUNNING" -> "LEASED";
                        case "DONE" -> "SUCCEEDED";
                        default -> row.get("status").toString();
                    });
        } else {
            out = (ObjectNode) adjustment(row);
        }
        return out;
    }

    private JsonNode adjustment(Map<String, Object> row) {
        var out = json.createObjectNode();
        for (String field : List.of(
                "id",
                "status",
                "target_type",
                "target_id",
                "created_by",
                "approved_by",
                "created_at",
                "approved_at",
                "posted_journal_id")) {
            String name = switch (field) {
                case "id" -> "adjustmentId";
                case "posted_journal_id" -> "postedJournalId";
                default -> camel(field);
            };
            out.set(name, json.valueToTree(value(row.get(field))));
        }
        return out;
    }

    private List<PostingLine> entries(OpsContext c, JsonNode entries, String currency, UUID provider) {
        if (!entries.isArray() || entries.size() < 2 || entries.size() > 100) throw invalid();
        var lines = new ArrayList<PostingLine>();
        BigInteger debits = BigInteger.ZERO, credits = BigInteger.ZERO;
        for (var e : entries) {
            fields(e, Set.of("entryId", "ledgerAccountId", "accountPurpose", "direction", "amountMinor", "currency"));
            var account = scoped(c, "ledger_account", uuid(text(e, "ledgerAccountId")), false);
            if (!currency.equals(text(e, "currency"))
                    || !currency.equals(account.get("currency"))
                    || !Objects.equals(account.get("account_purpose"), text(e, "accountPurpose"))
                    || account.get("provider_account_id") != null
                            && !Objects.equals(provider, account.get("provider_account_id"))) throw invalid();
            String entryId = text(e, "entryId");
            if (entryId == null || entryId.isBlank() || entryId.length() > 128) throw invalid();
            long amount = number(e, "amountMinor");
            if (amount <= 0) throw invalid();
            Side side;
            try {
                side = Side.valueOf(text(e, "direction"));
            } catch (RuntimeException ex) {
                throw invalid();
            }
            if (side == Side.DEBIT) debits = debits.add(BigInteger.valueOf(amount));
            else credits = credits.add(BigInteger.valueOf(amount));
            lines.add(new PostingLine((UUID) account.get("id"), side, amount));
        }
        if (!debits.equals(credits) || debits.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw invalid();
        return lines;
    }

    private void evidence(OpsContext c, JsonNode refs, String currency, UUID provider, boolean required) {
        if (!refs.isArray() || refs.size() > 100 || required && refs.isEmpty())
            throw new ApiException(ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Verified evidence is required");
        for (var ref : refs) {
            if (!ref.isTextual()) throw invalid();
            String[] parts = ref.asText().split(":", 2);
            String table = parts.length == 2
                    ? switch (parts[0]) {
                        case "ledger-account" -> "ledger_account";
                        case "journal" -> "journal";
                        case "reconciliation-evidence" -> "reconciliation_evidence";
                        case "bank-line" -> "bank_statement_line";
                        case "bank-statement" -> "bank_statement";
                        case "balance-transaction" -> "balance_transaction";
                        case "provider-observation" -> "provider_observation";
                        case "source-observation" -> "reconciliation_source_observation";
                        default -> null;
                    }
                    : null;
            if (table == null)
                throw new ApiException(ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Unknown evidence reference");
            var row = scoped(c, table, uuid(parts[1]), false);
            if (table.equals("journal")) {
                var event = one(
                        "SELECT provider_account_id FROM platform.financial_event WHERE id=?",
                        row.get("financial_event_id"));
                row.put("provider_account_id", event.get("provider_account_id"));
            }
            if (table.equals("provider_observation") && row.get("currency") == null)
                throw new ApiException(
                        ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Use a currency-scoped financial fact reference");
            if (row.get("currency") != null && !currency.equals(row.get("currency"))
                    || row.get("provider_account_id") != null
                            && !Objects.equals(provider, row.get("provider_account_id")))
                throw new ApiException(ErrorCode.ADJUSTMENT_EVIDENCE_REQUIRED, "Evidence scope differs from target");
        }
    }

    private ProviderScope provider(OpsContext c, JsonNode b) {
        var args = new ArrayList<Object>(List.of(c.organizationId(), c.environmentId()));
        String selector = "";
        if (b.has("providerAccountId")) {
            selector = " AND p.id=?";
            args.add(uuid(text(b, "providerAccountId")));
        }
        var rows = jdbc.queryForList(
                "SELECT p.id FROM platform.provider_account p JOIN platform.environment_config e ON"
                        + " e.id=p.environment_id JOIN platform.organization o ON o.id=p.organization_id"
                        + " WHERE p.organization_id=? AND p.environment_id=? AND p.status='ACTIVE' AND"
                        + " e.status='ACTIVE' AND o.status='ACTIVE' AND NOT p.livemode AND NOT e.livemode"
                        + selector
                        + " ORDER BY p.id LIMIT 2",
                args.toArray());
        if (rows.size() != 1)
            throw new ApiException(
                    rows.isEmpty() ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_ERROR,
                    "An active provider in trusted scope is required");
        return new ProviderScope(
                c.organizationId(), c.environmentId(), (UUID) rows.getFirst().get("id"), false);
    }

    private void activeProvider(OpsContext c, UUID provider) {
        if (provider != null
                && jdbc.queryForObject(
                                "SELECT count(*) FROM platform.provider_account WHERE id=? AND organization_id=?"
                                        + " AND environment_id=? AND status='ACTIVE' AND NOT livemode",
                                Long.class,
                                provider,
                                c.organizationId(),
                                c.environmentId())
                        != 1) throw new ApiException(ErrorCode.NOT_FOUND, "Active provider not found in caller scope");
    }

    private void currency(OpsContext c, String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) throw invalid();
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM platform.org_currency_enabled c JOIN platform.environment_config"
                                + " e ON e.id=c.environment_id JOIN platform.organization o ON"
                                + " o.id=c.organization_id WHERE c.organization_id=? AND c.environment_id=? AND"
                                + " c.currency=? AND c.status='ACTIVE' AND e.status='ACTIVE' AND o.status='ACTIVE'"
                                + " AND NOT e.livemode",
                        Long.class,
                        c.organizationId(),
                        c.environmentId(),
                        currency)
                != 1) throw new ApiException(ErrorCode.CURRENCY_NOT_ENABLED, "Currency is not enabled");
    }

    private Map<String, Object> scoped(OpsContext c, String table, UUID id, boolean lock) {
        var row = one(
                "SELECT * FROM platform."
                        + table
                        + " WHERE id=? AND organization_id=? AND environment_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                id,
                c.organizationId(),
                c.environmentId());
        if (row == null) throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found in caller scope");
        return row;
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validate(JsonNode b, String action) {
        if (b == null || !b.isObject()) throw invalid();
        String reason = text(b, "reason");
        int maxReason = Set.of("CREATE_RECONCILIATION", "RETRY_TASK").contains(action) ? 500 : 1000;
        if (reason == null || reason.isBlank() || reason.length() > maxReason) throw invalid();
        Set<String> required = switch (action) {
            case "CREATE_RECONCILIATION" -> Set.of("runType", "businessDate", "currency");
            case "IMPORT_BANK" -> Set.of("fixtureId", "schemaVersion", "contentDigest", "lines");
            case "EXCEPTION_ACTION" -> Set.of("actionType");
            case "CREATE_ADJUSTMENT" -> Set.of("targetType", "targetId", "evidenceRefs", "proposedEntries");
            case "APPROVE_ADJUSTMENT" -> Set.of("decision");
            default -> Set.of();
        };
        for (String field : required) if (!b.hasNonNull(field)) throw invalid();
        for (String field : required) {
            if (!Set.of("lines", "evidenceRefs", "proposedEntries").contains(field)) {
                String value = text(b, field);
                if (value == null || value.isBlank()) throw invalid();
            } else if (!b.path(field).isArray()) throw invalid();
        }
        if (action.equals("CREATE_ADJUSTMENT")
                && !Set.of("PAYMENT", "REFUND", "DISPUTE", "PAYOUT", "BALANCE", "EXCEPTION")
                        .contains(text(b, "targetType"))) throw invalid();
        if (action.equals("EXCEPTION_ACTION")
                && !Set.of("INVESTIGATE", "WAIT_EVIDENCE", "RESOLVE", "REOPEN").contains(text(b, "actionType")))
            throw invalid();
        var allowed =
                new HashSet<>(Set.of("reason", "organizationId", "environmentId", "livemode", "providerAccountId"));
        allowed.addAll(
                switch (action) {
                    case "CREATE_RECONCILIATION" ->
                        Set.of("runType", "businessDate", "currency", "overlapLookbackDays");
                    case "IMPORT_BANK" ->
                        Set.of(
                                "fixtureId",
                                "schemaVersion",
                                "contentDigest",
                                "lines",
                                "bankAccountRef",
                                "coverageFrom",
                                "coverageUntil",
                                "complete");
                    case "EXCEPTION_ACTION" -> Set.of("actionType", "evidenceRefs", "resolutionJournalId");
                    case "RETRY_TASK" -> Set.of();
                    case "CREATE_ADJUSTMENT" -> Set.of("targetType", "targetId", "evidenceRefs", "proposedEntries");
                    case "APPROVE_ADJUSTMENT" -> Set.of("decision");
                    default -> throw invalid();
                });
        fields(b, allowed);
        if (action.equals("CREATE_RECONCILIATION")
                && !Set.of("TRANSACTION_LEVEL", "BALANCE_LEVEL", "PAYOUT_BANK_LEVEL", "NIGHTLY_ALL")
                        .contains(Objects.toString(text(b, "runType"), ""))) throw invalid();
        if (action.equals("APPROVE_ADJUSTMENT")
                && !Set.of("APPROVE", "REJECT").contains(Objects.toString(text(b, "decision"), ""))) throw invalid();
    }

    private static void fields(JsonNode b, Set<String> allowed) {
        if (!b.isObject()) throw invalid();
        b.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) throw invalid();
        });
    }

    private JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            var out = json.createObjectNode();
            var keys = new TreeSet<String>();
            n.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) out.set(k, canonical(n.get(k)));
            return out;
        }
        if (n.isArray()) {
            var out = json.createArrayNode();
            n.forEach(v -> out.add(canonical(v)));
            return out;
        }
        return n.deepCopy();
    }

    private JsonNode tree(Object value) {
        try {
            return json.readTree(value.toString());
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static String text(JsonNode b, String f) {
        return b.path(f).isTextual() ? b.path(f).asText() : null;
    }

    private static String identifier(JsonNode b, String f) {
        String s = text(b, f);
        if (s == null || !s.matches("[A-Za-z0-9_.:-]{1,128}")) throw invalid();
        return s;
    }

    private static long number(JsonNode b, String f) {
        if (!b.path(f).isIntegralNumber() || !b.path(f).canConvertToLong()) throw invalid();
        return b.path(f).longValue();
    }

    private static int integer(JsonNode b, String f, int min, int max) {
        long n = number(b, f);
        if (n < min || n > max) throw invalid();
        return (int) n;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid operations command");
    }

    static Object value(Object v) {
        if (v instanceof Timestamp t) return t.toInstant().toString();
        return v;
    }

    static String camel(String s) {
        var out = new StringBuilder();
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
