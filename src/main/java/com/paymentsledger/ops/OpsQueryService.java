package com.paymentsledger.ops;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.OpsContext;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Scoped safe DTO projections. Raw facts, tokens, commands and bank descriptions never leave this
 * boundary.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OpsQueryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.paymentsledger.payment.PaymentRepository payments;
    private final com.paymentsledger.payment.RefundRepository refunds;
    private final com.paymentsledger.bank.BankMatcher bank;

    public OpsQueryService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock,
            com.paymentsledger.payment.PaymentRepository payments,
            com.paymentsledger.payment.RefundRepository refunds,
            com.paymentsledger.bank.BankMatcher bank) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.payments = payments;
        this.refunds = refunds;
        this.bank = bank;
    }

    public JsonNode detail(OpsContext c, String kind, UUID id) {
        OpsAuthorization.read(c, kind.equals("revenue-shares"));
        return switch (kind) {
            case "payments" -> payment(c, id);
            case "refunds" -> refund(c, id);
            case "disputes" -> dispute(c, id);
            case "payouts" -> payout(c, id);
            case "revenue-shares" -> shares(c, id);
            case "journals" -> journal(c, id);
            case "reconciliation-runs" -> run(c, id);
            case "adjustments" -> adjustment(c, id);
            default -> throw invalid();
        };
    }

    public JsonNode list(OpsContext c, String kind, Map<String, String> q) {
        OpsAuthorization.read(c, false);
        if (kind.equals("balances")) return balances(c, q);
        int limit = limit(q.get("limit"));
        String table = switch (kind) {
            case "journals" -> "journal";
            case "exceptions" -> "reconciliation_exception";
            case "tasks" -> "recovery_task";
            default -> throw invalid();
        };
        String filterFingerprint = OpsAuditService.digest(new TreeMap<>(q.entrySet().stream()
                        .filter(e -> !Set.of("cursor", "limit").contains(e.getKey()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .toString());
        UUID after = cursor(c, kind, q.get("cursor"), filterFingerprint);
        var args = new ArrayList<Object>(List.of(c.organizationId(), c.environmentId(), after));
        String where = " WHERE x.organization_id=? AND x.environment_id=? AND x.id>?";
        if (q.containsKey("providerAccountId")) {
            where += kind.equals("journals") ? " AND f.provider_account_id=?" : " AND x.provider_account_id=?";
            args.add(uuid(q.get("providerAccountId")));
        }
        if (kind.equals("journals")) {
            where += " AND x.sealed_at IS NOT NULL";
            for (String field :
                    List.of("eventKey", "postedFrom", "postedTo", "businessOrderId", "balanceTransactionId")) {
                if (q.containsKey(field)) {
                    where += switch (field) {
                        case "eventKey" -> " AND f.event_key=?";
                        case "postedFrom" -> " AND x.sealed_at>=?";
                        case "postedTo" -> " AND x.sealed_at<?";
                        case "businessOrderId" ->
                            " AND EXISTS(SELECT 1 FROM platform.balance_transaction bt JOIN"
                                    + " platform.payment p ON p.id=bt.payment_id WHERE bt.journal_id=x.id AND"
                                    + " p.business_order_id=?)";
                        default ->
                            " AND EXISTS(SELECT 1 FROM platform.balance_transaction bt WHERE"
                                    + " bt.journal_id=x.id AND bt.bt_id=?)";
                    };
                    args.add(
                            field.startsWith("posted")
                                    ? stamp(q.get(field))
                                    : field.equals("businessOrderId")
                                            ? OpsAuthorization.resourceId(q.get(field), "bor")
                                            : q.get(field));
                }
            }
        }
        if (kind.equals("tasks")) {
            if (q.containsKey("taskType")) {
                where += " AND x.task_type=?";
                args.add(q.get("taskType"));
            }
            if (q.containsKey("status")) {
                if (q.get("status").equals("PENDING")) where += " AND x.status IN ('READY','WAITING')";
                else {
                    where += " AND x.status=?";
                    args.add(
                            switch (q.get("status")) {
                                case "LEASED" -> "RUNNING";
                                case "SUCCEEDED" -> "DONE";
                                default -> q.get("status");
                            });
                }
            }
        }
        // Disposition/type filtering happens after safe projection, with a bounded scan and stable
        // continuation.
        args.add(limit + 1);
        String joins = kind.equals("journals") ? " JOIN platform.financial_event f ON f.id=x.financial_event_id" : "";
        var rows = jdbc.queryForList(
                "SELECT x.* FROM platform." + table + " x" + joins + where + " ORDER BY x.id LIMIT ?", args.toArray());
        var out = json.createObjectNode();
        var items = out.putArray("items");
        int scanned = Math.min(limit, rows.size());
        for (int i = 0; i < scanned; i++) {
            var row = rows.get(i);
            JsonNode item = switch (kind) {
                case "journals" -> journalSummary(c, row);
                case "exceptions" -> exception(c, row);
                default -> task(row);
            };
            if (kind.equals("exceptions")
                    && (q.containsKey("status")
                                    && !q.get("status")
                                            .equals(item.path("status").asText())
                            || q.containsKey("type")
                                    && !q.get("type").equals(item.path("type").asText()))) continue;
            items.add(item);
        }
        if (rows.size() > limit)
            out.put(
                    "nextCursor",
                    encodeCursor(c, kind, (UUID) rows.get(limit - 1).get("id"), filterFingerprint));
        else out.putNull("nextCursor");
        return out;
    }

    private ObjectNode adjustment(OpsContext c, UUID id) {
        var row = scoped(c, "ops_adjustment", id);
        var out = project(
                row,
                "status",
                "target_type",
                "target_id",
                "currency",
                "provider_account_id",
                "created_by",
                "approved_by",
                "created_at",
                "approved_at",
                "reason_digest",
                "reason_length");
        out.put("adjustmentId", id.toString());
        set(out, "postedJournalId", row.get("posted_journal_id"));
        out.set("proposedEntries", tree(row.get("entries")));
        out.set("evidenceRefs", tree(row.get("evidence_refs")));
        return out;
    }

    private ObjectNode payment(OpsContext c, UUID id) {
        var r = scoped(c, "payment", id);
        ObjectNode out = json.valueToTree(payments.read(readContext(c, r), id));
        var control = one(
                "SELECT captured_minor,refunded_minor,reserved_minor,control_state FROM"
                        + " platform.refund_control WHERE payment_id=?",
                id);
        long refunded = control == null ? 0 : number(control, "refunded_minor"),
                reserved = control == null ? 0 : number(control, "reserved_minor");
        out.put("refundedAmountMinor", refunded)
                .put("reservedAmountMinor", reserved)
                .put("refundableAmountMinor", Math.max(0, number(r, "captured_amount_minor") - refunded - reserved))
                .put(
                        "refundControlState",
                        control == null
                                ? "NORMAL"
                                : control.get("control_state").toString());
        enrich(c, out, r, "payment_id", id);
        var shareView = shares(c, id);
        out.set("revenueShares", shareView.path("lines"));
        for (var section : shareView.path("truncatedSections"))
            if (section.asText().equals("lines")) markTruncated(out, "revenueShares");
        return out;
    }

    private ObjectNode refund(OpsContext c, UUID id) {
        var r = scoped(c, "refund", id);
        ObjectNode out = json.valueToTree(refunds.read(readContext(c, r), id));
        out.remove("reason");
        enrich(c, out, r, "refund_id", id);
        return out;
    }

    private com.paymentsledger.identity.TenantContext readContext(OpsContext c, Map<String, Object> row) {
        // Ops authorization and scoped() have already resolved ownership. This context is used solely
        // by existing read methods.
        return new com.paymentsledger.identity.TenantContext(
                c.organizationId(),
                c.environmentId(),
                (UUID) row.get("application_id"),
                (UUID) row.get("provider_account_id"),
                Set.of(row.get("currency").toString()),
                false,
                Set.of("payments:read", "refunds:read"));
    }

    private ObjectNode dispute(OpsContext c, UUID id) {
        var r = scoped(c, "dispute", id);
        var out = project(
                r,
                "provider_status",
                "local_handling",
                "currency",
                "payment_id",
                "reason",
                "evidence_due_by",
                "outstanding_receivable_minor",
                "confirmed_loss_minor",
                "dispute_fee_minor",
                "created_at",
                "last_observed_at");
        out.put("disputeId", id.toString()).put("amountMinor", number(r, "disputed_principal_minor"));
        set(out, "chargeId", r.get("provider_charge_id"));
        out.put("blocksRefunds", Boolean.TRUE.equals(r.get("blocking")))
                .put("revenueShareBlocked", Boolean.TRUE.equals(r.get("blocking")));
        enrich(c, out, r, "dispute_id", id);
        return out;
    }

    private ObjectNode payout(OpsContext c, UUID id) {
        var r = scoped(c, "payout", id);
        var out = project(
                r,
                "provider_status",
                "automatic",
                "amount_minor",
                "currency",
                "bank_match_state",
                "created_at",
                "last_observed_at",
                "arrival_date");
        out.put("payoutId", id.toString())
                .put("inTransitPrincipalMinor", number(r, "transit_minor"))
                .put("confirmedBankPrincipalMinor", number(r, "bank_confirmed_minor"))
                .put("returnedPrincipalMinor", number(r, "returned_minor"))
                .put(
                        "bankFeeMinor",
                        jdbc.queryForObject(
                                "SELECT coalesce(sum(fee_minor),0)::bigint FROM platform.payout_bank_match WHERE"
                                        + " payout_id=?",
                                Long.class,
                                id))
                .put("evidenceSource", "SIMULATED_BANK_EVIDENCE")
                .put("shortReceiptState", shortReceipt(c, r, id));
        for (String role : List.of("OUT", "RETURN")) {
            var rows = jdbc.queryForList(
                    "SELECT bt_id FROM platform.payout_bt_link WHERE payout_id=? AND role=? ORDER BY"
                            + " bt_id LIMIT 1",
                    String.class,
                    id,
                    role);
            if (rows.isEmpty())
                out.putNull(role.equals("OUT") ? "deductionBalanceTransactionId" : "failureBalanceTransactionId");
            else
                out.put(
                        role.equals("OUT") ? "deductionBalanceTransactionId" : "failureBalanceTransactionId",
                        rows.getFirst());
        }
        enrich(c, out, r, "payout_id", id);
        return out;
    }

    private String shortReceipt(OpsContext c, Map<String, Object> payout, UUID id) {
        String state = bank.evidenceState(
                new com.paymentsledger.provider.ProviderScope(
                        c.organizationId(), c.environmentId(), (UUID) payout.get("provider_account_id"), false),
                id);
        return switch (state) {
            case "SHORT_RECEIPT" -> "SHORT_RECEIPT";
            case "MATCHED", "RETURNED" -> "NONE";
            default -> "WAITING_EVIDENCE";
        };
    }

    private ObjectNode shares(OpsContext c, UUID id) {
        var p = scoped(c, "payment", id);
        boolean partner = c.role().equals("PARTNER_VIEWER");
        var out = json.createObjectNode().put("paymentId", id.toString());
        initializeTrace(out);
        var lines = out.putArray("lines");
        var rows = jdbc.queryForList(
                "SELECT * FROM platform.share_allocation WHERE payment_id=? AND organization_id=? AND"
                        + " environment_id=?"
                        + (partner ? " AND partner_ref=?" : "")
                        + " ORDER BY updated_at DESC,id DESC LIMIT 101",
                partner
                        ? new Object[] {
                            id,
                            c.organizationId(),
                            c.environmentId(),
                            c.partnerId().toString()
                        }
                        : new Object[] {id, c.organizationId(), c.environmentId()});
        if (partner && rows.isEmpty()) throw notFound();
        var control = one(
                "SELECT captured_minor,refunded_minor,reserved_minor,control_state FROM"
                        + " platform.refund_control WHERE payment_id=?",
                id);
        boolean blocked = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.dispute WHERE payment_id=? AND blocking)", Boolean.class, id);
        long base = blocked || control != null && !control.get("control_state").equals("NORMAL")
                ? 0
                : control == null
                        ? number(p, "captured_amount_minor")
                        : Math.max(
                                0,
                                number(control, "captured_minor")
                                        - number(control, "refunded_minor")
                                        - number(control, "reserved_minor"));
        for (var a : boundedTrace(out, "lines", rows)) {
            var line = json.createObjectNode()
                    .put("partnerId", a.get("partner_ref").toString())
                    .put("rateBps", number(a, "rate_bps"))
                    .put("eligibleBaseMinor", base)
                    .put("capMinor", number(a, "cap_minor"))
                    .put("netAccruedMinor", number(a, "effective_minor"));
            var sums = jdbc.queryForMap(
                    "SELECT coalesce(sum(delta_minor) FILTER(WHERE delta_minor>0),0)::bigint"
                            + " accrued,coalesce(-sum(delta_minor) FILTER(WHERE delta_minor<0),0)::bigint"
                            + " reversed FROM platform.share_adjustment WHERE allocation_id=?",
                    a.get("id"));
            line.put("accruedMinor", number(sums, "accrued")).put("reversedMinor", number(sums, "reversed"));
            set(line, "blockedReason", blocked ? "OPEN_DISPUTE" : base == 0 ? "NO_POSTING_EVIDENCE" : null);
            lines.add(line);
        }
        if (!partner) {
            var order = scoped(c, "business_order", (UUID) p.get("business_order_id"));
            out.set("policySnapshot", tree(order.get("share_policy_snapshot")));
            JsonNode version = out.path("policySnapshot").path("policy_version");
            if (version.isIntegralNumber() || version.asText().matches("[0-9]+"))
                out.put("policyVersion", version.asInt());
            out.set("journals", relatedJournals(c, "payment_id", id, out));
        }
        return out;
    }

    private ObjectNode journal(OpsContext c, UUID id) {
        var r = scoped(c, "journal", id);
        if (r.get("sealed_at") == null) throw notFound();
        var out = journalSummary(c, r);
        var entries = out.putArray("entries");
        for (var e : jdbc.queryForList(
                "SELECT e.id,e.ledger_account_id,a.account_purpose,e.side,e.amount_minor,e.currency"
                        + " FROM platform.journal_entry e JOIN platform.ledger_account a ON"
                        + " a.id=e.ledger_account_id WHERE e.journal_id=? ORDER BY e.line_no",
                id)) {
            var entry = project(e, "ledger_account_id", "account_purpose", "amount_minor", "currency");
            entry.put("entryId", e.get("id").toString())
                    .put("direction", e.get("side").toString());
            entries.add(entry);
        }
        set(out, "reversalOfJournalId", r.get("reversal_of_journal_id"));
        var reversed = out.putArray("reversedByJournalIds");
        jdbc.queryForList("SELECT id FROM platform.journal WHERE reversal_of_journal_id=? ORDER BY id", UUID.class, id)
                .forEach(i -> reversed.add(i.toString()));
        var bt = jdbc.queryForList(
                "SELECT bt_id FROM platform.balance_transaction WHERE journal_id=? ORDER BY bt_id LIMIT" + " 1",
                String.class,
                id);
        set(out, "sourceBalanceTransactionId", bt.isEmpty() ? null : bt.getFirst());
        return out;
    }

    private ObjectNode journalSummary(OpsContext c, Map<String, Object> r) {
        var out = json.createObjectNode()
                .put("journalId", r.get("id").toString())
                .put("financialEventId", r.get("financial_event_id").toString())
                .put("status", "POSTED")
                .put("currency", r.get("currency").toString());
        set(out, "postedAt", r.get("sealed_at"));
        var totals = jdbc.queryForMap(
                "SELECT coalesce(sum(amount_minor) FILTER(WHERE side='DEBIT'),0)::bigint"
                        + " debit,coalesce(sum(amount_minor) FILTER(WHERE side='CREDIT'),0)::bigint credit"
                        + " FROM platform.journal_entry WHERE journal_id=?",
                r.get("id"));
        out.put("totalDebitMinor", number(totals, "debit")).put("totalCreditMinor", number(totals, "credit"));
        out.put(
                "financialEventKey",
                jdbc.queryForObject(
                        "SELECT event_key FROM platform.financial_event WHERE id=?",
                        String.class,
                        r.get("financial_event_id")));
        return out;
    }

    private ObjectNode run(OpsContext c, UUID id) {
        var r = scoped(c, "reconciliation_run", id);
        var out = json.createObjectNode().put("runId", id.toString());
        var request = one("SELECT run_type,business_date FROM platform.ops_reconciliation_request WHERE run_id=?", id);
        out.put(
                "runType",
                request == null ? "NIGHTLY_ALL" : request.get("run_type").toString());
        var cursor = one(
                "SELECT next_cursor,accepted_pages,api_version,import_version,replay_after_bt FROM"
                        + " platform.reconciliation_cursor WHERE run_id=?",
                id);
        var window = one("SELECT state,last_error FROM platform.import_window WHERE id=?", r.get("import_window_id"));
        boolean complete = r.get("state").equals("COMPLETE");
        out.put(
                        "status",
                        r.get("finished_at") != null
                                ? (complete ? "COMPLETED" : "INCOMPLETE")
                                : number(cursor, "accepted_pages") > 0 ? "FETCHING" : "CREATED")
                .put("dataCompleteness", complete ? "COMPLETE" : "INCOMPLETE")
                .put(
                        "businessDate",
                        (request == null ? r.get("business_date") : request.get("business_date")).toString())
                .put("businessTimezone", r.get("business_zone").toString())
                .put("currency", r.get("currency").toString())
                .put("ruleVersion", r.get("rule_version").toString());
        set(out, "windowStartUtc", r.get("from_at"));
        set(out, "windowEndUtc", r.get("until_at"));
        set(out, "providerCursor", cursor.get("next_cursor"));
        out.put("allPagesFetched", window.get("state").equals("COMPLETE"));
        set(out, "inputHighWatermark", r.get("until_at"));
        var external = one(
                "SELECT evidence_version FROM platform.reconciliation_evidence WHERE run_id=? AND"
                        + " evidence_kind IN ('PERIOD_REPORT','QUIESCENT_SNAPSHOT','INDEPENDENT_FIXTURE')"
                        + " ORDER BY created_at DESC,id DESC LIMIT 1",
                id);
        set(out, "externalEvidenceVersion", external == null ? null : external.get("evidence_version"));
        var evidence = out.putArray("evidence");
        for (var item : jdbc.queryForList(
                "SELECT id,evidence_kind,source,evidence_version,digest,created_at FROM"
                        + " platform.reconciliation_evidence WHERE run_id=? ORDER BY created_at,id",
                id)) {
            var summary = project(item, "id", "source", "digest", "created_at");
            summary.put("kind", item.get("evidence_kind").toString())
                    .put("version", item.get("evidence_version").toString())
                    .put("evidenceRef", "reconciliation-evidence:" + item.get("id"));
            evidence.add(summary);
        }
        var residual = one(
                "SELECT residual FROM platform.reconciliation_check WHERE run_id=? AND check_number=5"
                        + " AND outcome<>'INCOMPLETE'",
                id);
        set(out, "residualMinor", residual == null ? null : residual.get("residual"));
        var checks = json.createArrayNode();
        for (var check : jdbc.queryForList(
                "SELECT"
                        + " check_number,name,outcome,residual,reason,owner,input_versions,completeness,snapshot"
                        + " FROM platform.reconciliation_check WHERE run_id=? ORDER BY check_number",
                id)) {
            var item = project(check, "check_number", "name", "outcome", "residual", "reason", "owner", "snapshot");
            item.set("inputVersions", tree(check.get("input_versions")));
            item.set("completeness", tree(check.get("completeness")));
            checks.add(item);
        }
        out.set("checks", checks);
        boolean failed = false, incomplete = checks.size() != 7;
        for (var check : checks) {
            failed |= check.path("outcome").asText().equals("FAIL");
            incomplete |= check.path("outcome").asText().equals("INCOMPLETE");
        }
        out.put("checkResult", failed ? "FAIL" : incomplete ? "INCOMPLETE" : "PASS");
        out.put(
                        "matchedCount",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform.reconciliation_check WHERE run_id=? AND"
                                        + " outcome='PASS'",
                                Long.class,
                                id))
                .put(
                        "unresolvedCount",
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform.reconciliation_exception WHERE run_id=?",
                                Long.class,
                                id));
        set(
                out,
                "incompleteReason",
                complete
                        ? null
                        : Objects.toString(
                                window.get("last_error"),
                                r.get("finished_at") == null
                                        ? "RUN_AWAITING_EVIDENCE"
                                        : "INDEPENDENT_EVIDENCE_INCOMPLETE"));
        return out;
    }

    private ObjectNode exception(OpsContext c, Map<String, Object> r) {
        var action = one(
                "SELECT status,evidence_refs,resolution_journal_id FROM platform.ops_exception_action"
                        + " WHERE exception_id=? ORDER BY action_order DESC LIMIT 1",
                r.get("id"));
        var out = json.createObjectNode()
                .put("exceptionId", r.get("id").toString())
                .put("type", exceptionType(r.get("reason").toString()))
                .put("status", action == null ? "OPEN" : action.get("status").toString())
                .put("currency", r.get("currency").toString())
                .put("ageSeconds", age(r.get("created_at")));
        set(out, "amountMinor", r.get("residual"));
        set(out, "createdAt", r.get("created_at"));
        set(out, "resolutionJournalId", action == null ? null : action.get("resolution_journal_id"));
        out.set("evidenceRefs", action == null ? json.createArrayNode() : tree(action.get("evidence_refs")));
        out.put("runId", r.get("run_id").toString())
                .put("findingReason", r.get("reason").toString());
        out.set("inputVersions", tree(r.get("input_versions")));
        return out;
    }

    private ObjectNode task(Map<String, Object> r) {
        var out = json.createObjectNode()
                .put("taskId", r.get("id").toString())
                .put("taskType", r.get("task_type").toString())
                .put(
                        "status",
                        switch (r.get("status").toString()) {
                            case "READY", "WAITING" -> "PENDING";
                            case "RUNNING" -> "LEASED";
                            case "DONE" -> "SUCCEEDED";
                            default -> r.get("status").toString();
                        })
                .put("attemptCount", number(r, "attempt_count"))
                .put("ageSeconds", age(r.get("created_at")));
        Object resource = r.get("refund_id") != null
                ? r.get("refund_id")
                : r.get("payment_id") != null ? r.get("payment_id") : r.get("source_bt_id");
        out.put(
                "resourceType",
                r.get("refund_id") != null ? "REFUND" : r.get("payment_id") != null ? "PAYMENT" : "FINANCIAL");
        set(out, "resourceId", resource);
        set(out, "leaseUntil", r.get("lease_until"));
        set(
                out,
                "nextScheduledAt",
                Set.of("DONE", "ESCALATED").contains(r.get("status")) ? null : r.get("available_at"));
        if (r.get("operation_id") != null) {
            set(out, "operationId", r.get("operation_id"));
            var owner = one("SELECT lease_owner FROM platform.provider_operation WHERE id=?", r.get("operation_id"));
            set(out, "leaseOwner", owner == null ? null : owner.get("lease_owner"));
        } else out.putNull("leaseOwner");
        return out;
    }

    private ObjectNode balances(OpsContext c, Map<String, String> q) {
        if (q.containsKey("currency")) enabled(c, q.get("currency"));
        Instant cutoff = q.containsKey("asOf") ? stamp(q.get("asOf")).toInstant() : null;
        if (cutoff != null && cutoff.isAfter(clock.instant())) throw invalid();
        var out = json.createObjectNode();
        var items = out.putArray("items");
        var arguments = new ArrayList<Object>(List.of(c.organizationId(), c.environmentId()));
        String predicates = "";
        if (q.containsKey("currency")) {
            predicates += " AND a.currency=?";
            arguments.add(q.get("currency"));
        }
        if (q.containsKey("accountPurpose")) {
            predicates += " AND a.account_purpose=?";
            arguments.add(q.get("accountPurpose"));
        }
        if (q.containsKey("providerAccountId")) {
            predicates += " AND (a.provider_account_id=? OR a.provider_account_id IS NULL)";
            arguments.add(uuid(q.get("providerAccountId")));
        }
        var rows = jdbc.queryForList(
                "SELECT a.*,p.balance_minor FROM platform.ledger_account a LEFT JOIN"
                        + " platform.balance_projection p ON p.ledger_account_id=a.id JOIN"
                        + " platform.org_currency_enabled ce ON ce.organization_id=a.organization_id AND"
                        + " ce.environment_id=a.environment_id AND ce.currency=a.currency AND"
                        + " ce.status='ACTIVE' WHERE a.organization_id=? AND a.environment_id=?"
                        + predicates
                        + " ORDER BY a.currency,a.id LIMIT 1001",
                arguments.toArray());
        if (rows.size() > 1000)
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Balance scope exceeds 1000 accounts; narrow currency or accountPurpose");
        for (var r : rows) {
            if (q.containsKey("providerAccountId")
                    && r.get("provider_account_id") != null
                    && !q.get("providerAccountId")
                            .equals(r.get("provider_account_id").toString())) continue;
            if (q.containsKey("currency") && !q.get("currency").equals(r.get("currency"))
                    || q.containsKey("accountPurpose")
                            && !q.get("accountPurpose").equals(r.get("account_purpose"))) continue;
            var line =
                    project(r, "account_purpose", "source_domain", "provider_account_id", "balance_domain", "currency");
            line.put("ledgerAccountId", r.get("id").toString());
            BigInteger rebuilt = BigInteger.ZERO;
            for (var e : jdbc.queryForList(
                    "SELECT e.side,e.amount_minor FROM platform.journal_entry e JOIN platform.journal j"
                            + " ON j.id=e.journal_id WHERE e.ledger_account_id=? AND j.sealed_at IS NOT NULL"
                            + (cutoff != null ? " AND j.sealed_at<=?" : ""),
                    cutoff != null ? new Object[] {r.get("id"), Timestamp.from(cutoff)} : new Object[] {r.get("id")})) {
                BigInteger amount = BigInteger.valueOf(number(e, "amount_minor"));
                rebuilt = rebuilt.add(e.get("side").equals("DEBIT") ? amount : amount.negate());
            }
            if (rebuilt.bitLength() > 63) throw invalid();
            line.put(
                    "balanceMinor",
                    cutoff != null
                            ? rebuilt.longValue()
                            : r.get("balance_minor") == null ? 0 : number(r, "balance_minor"));
            if (cutoff == null)
                line.put(
                        "rebuildMatches",
                        rebuilt.equals(
                                BigInteger.valueOf(r.get("balance_minor") == null ? 0 : number(r, "balance_minor"))));
            else line.putNull("rebuildMatches");
            items.add(line);
        }
        out.put("asOf", cutoff == null ? clock.instant().toString() : cutoff.toString());
        return out;
    }

    private void enrich(OpsContext c, ObjectNode out, Map<String, Object> resource, String field, UUID id) {
        initializeTrace(out);
        out.set("journals", relatedJournals(c, field, id, out));
        var bts = out.putArray("balanceTransactions");
        var ids = out.putArray("balanceTransactionIds");
        for (var bt : boundedTrace(
                out,
                "balanceTransactions",
                jdbc.queryForList(
                        "SELECT"
                                + " id,bt_id,provider_status,business_type,amount_minor,fee_minor,net_minor,currency,balance_domain,source_object_id,source_object_type,provider_created_at,available_on,fetched_at,api_version,raw_digest,journal_id"
                                + " FROM platform.balance_transaction WHERE "
                                + field
                                + "=? AND organization_id=? AND environment_id=? AND provider_account_id=? AND"
                                + " currency=? ORDER BY provider_created_at DESC,id DESC LIMIT 101",
                        id,
                        c.organizationId(),
                        c.environmentId(),
                        resource.get("provider_account_id"),
                        resource.get("currency")))) {
            ids.add(bt.get("bt_id").toString());
            bts.add(project(
                    bt,
                    "id",
                    "bt_id",
                    "provider_status",
                    "business_type",
                    "amount_minor",
                    "fee_minor",
                    "net_minor",
                    "currency",
                    "balance_domain",
                    "source_object_id",
                    "source_object_type",
                    "provider_created_at",
                    "available_on",
                    "fetched_at",
                    "api_version",
                    "raw_digest",
                    "journal_id"));
        }
        var operations = out.putArray("operations");
        if (Set.of("payment_id", "refund_id").contains(field))
            for (var t : boundedTrace(
                    out,
                    "operations",
                    jdbc.queryForList(
                            "SELECT * FROM platform.recovery_task WHERE "
                                    + field
                                    + "=? AND organization_id=? AND environment_id=? ORDER BY created_at DESC,id"
                                    + " DESC LIMIT 101",
                            id,
                            c.organizationId(),
                            c.environmentId()))) operations.add(task(t));
        var banks = out.putArray("bankEvidence");
        if (field.equals("payout_id"))
            for (var line : boundedTrace(
                    out,
                    "bankEvidence",
                    jdbc.queryForList(
                            "SELECT"
                                    + " l.id,l.statement_id,l.kind,l.booked_at,l.principal_minor,l.fee_minor,m.journal_id,s.fixture_id,s.digest,s.complete,s.coverage_from,s.coverage_until"
                                    + " FROM platform.payout_bank_match m JOIN platform.bank_statement_line l ON"
                                    + " l.id=m.line_id JOIN platform.bank_statement s ON s.id=l.statement_id"
                                    + " WHERE m.payout_id=? AND m.organization_id=? AND m.environment_id=? ORDER"
                                    + " BY l.booked_at DESC,l.id DESC LIMIT 101",
                            id,
                            c.organizationId(),
                            c.environmentId())))
                banks.add(project(
                        line,
                        "id",
                        "statement_id",
                        "kind",
                        "booked_at",
                        "principal_minor",
                        "fee_minor",
                        "journal_id",
                        "fixture_id",
                        "digest",
                        "complete",
                        "coverage_from",
                        "coverage_until"));
        var timeline = out.putArray("timeline");
        if (resource.get("created_at") != null) {
            var item = timeline.addObject().put("kind", "LOCAL_RESOURCE").put("summary", "Local resource created");
            set(item, "at", resource.get("created_at"));
            item.put("evidenceRef", field.replace('_', '-') + ":" + id);
        }
        var exceptions = out.putArray("exceptions");
        for (var e : boundedTrace(
                out,
                "exceptions",
                jdbc.queryForList(
                        "SELECT e.* FROM platform.reconciliation_exception e WHERE e.organization_id=? AND"
                                + " e.environment_id=? AND e.provider_account_id=? AND e.currency=? AND"
                                + " (e.source_ref=? OR EXISTS(SELECT 1 FROM platform.balance_transaction b"
                                + " WHERE b."
                                + field
                                + "=? AND b.organization_id=e.organization_id AND"
                                + " b.environment_id=e.environment_id AND"
                                + " b.provider_account_id=e.provider_account_id AND b.currency=e.currency AND"
                                + " (e.source_ref=b.bt_id OR e.source_ref=b.id::text))) ORDER BY e.created_at"
                                + " DESC,e.id DESC LIMIT 101",
                        c.organizationId(),
                        c.environmentId(),
                        resource.get("provider_account_id"),
                        resource.get("currency"),
                        id.toString(),
                        id))) exceptions.add(exception(c, e));
        if (Set.of("payment_id", "refund_id").contains(field)) {
            var observations = out.putArray("providerObservations");
            for (var observation : boundedTrace(
                    out,
                    "providerObservations",
                    jdbc.queryForList(
                            "SELECT"
                                    + " id,operation_id,call_type,classification,external_object_id,created_at,fetched_at,evidence->>'providerRequestId' AS provider_request_id,evidence->>'apiVersion'"
                                    + " AS api_version,evidence->>'rawDigest' AS"
                                    + " raw_digest,evidence->>'httpStatus' AS http_status FROM"
                                    + " platform.provider_observation WHERE "
                                    + field
                                    + "=? AND organization_id=? AND environment_id=? AND provider_account_id=?"
                                    + " AND currency=? ORDER BY created_at DESC,id DESC LIMIT 101",
                            id,
                            c.organizationId(),
                            c.environmentId(),
                            resource.get("provider_account_id"),
                            resource.get("currency")))) {
                var view = project(
                        observation,
                        "id",
                        "operation_id",
                        "call_type",
                        "classification",
                        "external_object_id",
                        "created_at",
                        "fetched_at",
                        "api_version",
                        "raw_digest",
                        "http_status");
                // Provider evidence contributes only this known identifier; secret-like values are omitted.
                String providerRequest = Objects.toString(observation.get("provider_request_id"), null);
                if (com.paymentsledger.common.request.SafeCorrelationId.accepts(providerRequest, 128))
                    view.put("providerRequestId", providerRequest);
                observations.add(view);
                var event = timeline.addObject()
                        .put("kind", "PROVIDER_OBSERVATION")
                        .put("summary", "Provider result observed")
                        .put("evidenceRef", "provider-observation:" + observation.get("id"));
                set(event, "at", observation.get("created_at"));
            }
        }
        for (var bt : bts) {
            var event = timeline.addObject()
                    .put("kind", "BALANCE_TRANSACTION")
                    .put("summary", "Balance transaction evidence available")
                    .put("evidenceRef", "balance-transaction:" + bt.path("id").asText());
            event.set("at", bt.path("fetchedAt"));
        }
        if (!field.equals("payout_id"))
            for (var line : boundedTrace(
                    out,
                    "bankEvidence",
                    jdbc.queryForList(
                            "SELECT"
                                    + " l.id,l.statement_id,l.kind,l.booked_at,l.principal_minor,l.fee_minor,m.journal_id,s.fixture_id,s.digest,s.complete,s.coverage_from,s.coverage_until"
                                    + " FROM platform.balance_transaction b JOIN platform.payout_membership"
                                    + " member ON member.bt_id=b.bt_id JOIN platform.payout p ON"
                                    + " p.id=member.payout_id AND p.organization_id=b.organization_id AND"
                                    + " p.environment_id=b.environment_id AND"
                                    + " p.provider_account_id=b.provider_account_id AND p.currency=b.currency"
                                    + " JOIN platform.payout_bank_match m ON m.payout_id=p.id JOIN"
                                    + " platform.bank_statement_line l ON l.id=m.line_id JOIN"
                                    + " platform.bank_statement s ON s.id=l.statement_id WHERE b."
                                    + field
                                    + "=? AND b.organization_id=? AND b.environment_id=? AND"
                                    + " b.provider_account_id=? AND b.currency=? ORDER BY l.booked_at DESC,l.id"
                                    + " DESC LIMIT 101",
                            id,
                            c.organizationId(),
                            c.environmentId(),
                            resource.get("provider_account_id"),
                            resource.get("currency"))))
                banks.add(project(
                        line,
                        "id",
                        "statement_id",
                        "kind",
                        "booked_at",
                        "principal_minor",
                        "fee_minor",
                        "journal_id",
                        "fixture_id",
                        "digest",
                        "complete",
                        "coverage_from",
                        "coverage_until"));
        for (var line : banks) {
            var event = timeline.addObject()
                    .put("kind", "SIMULATED_BANK_EVIDENCE")
                    .put("summary", "Simulated bank evidence matched")
                    .put("evidenceRef", "bank-line:" + line.path("id").asText());
            event.set("at", line.path("bookedAt"));
        }
        var events = new ArrayList<JsonNode>();
        timeline.forEach(events::add);
        events.sort(Comparator.comparing(e -> e.path("at").asText()));
        timeline.removeAll();
        timeline.addAll(events);
    }

    private ArrayNode relatedJournals(OpsContext c, String field, UUID id, ObjectNode detail) {
        String type = switch (field) {
            case "payment_id" -> "PAYMENT";
            case "refund_id" -> "REFUND";
            case "dispute_id" -> "DISPUTE";
            case "payout_id" -> "PAYOUT";
            default -> throw invalid();
        };
        var arguments = new ArrayList<Object>(List.of(c.organizationId(), c.environmentId(), id, type, id));
        String ownerRelation = "";
        if (field.equals("payment_id")) {
            ownerRelation = " OR EXISTS(SELECT 1 FROM platform.share_adjustment a JOIN platform.share_allocation s ON"
                    + " s.id=a.allocation_id WHERE a.journal_id=j.id AND s.payment_id=?)";
            arguments.add(id);
        } else if (field.equals("payout_id")) {
            ownerRelation = " OR EXISTS(SELECT 1 FROM platform.payout_bank_match m WHERE m.journal_id=j.id AND"
                    + " m.payout_id=?)";
            arguments.add(id);
        }
        var rows = jdbc.queryForList(
                "SELECT j.* FROM platform.journal j WHERE j.organization_id=? AND j.environment_id=?"
                        + " AND j.sealed_at IS NOT NULL AND (EXISTS(SELECT 1 FROM"
                        + " platform.balance_transaction b WHERE b.journal_id=j.id AND b."
                        + field
                        + "=?) OR EXISTS(SELECT 1 FROM platform.ops_adjustment a WHERE"
                        + " a.organization_id=j.organization_id AND a.environment_id=j.environment_id AND"
                        + " a.currency=j.currency AND a.status='APPROVED' AND a.posted_journal_id=j.id AND"
                        + " a.target_type=? AND a.target_id=?)"
                        + ownerRelation
                        + ") ORDER BY j.sealed_at DESC,j.id DESC LIMIT 101",
                arguments.toArray());
        var out = json.createArrayNode();
        for (var row : boundedTrace(detail, "journals", rows)) out.add(journalSummary(c, row));
        return out;
    }

    private void initializeTrace(ObjectNode detail) {
        detail.put("traceCompleteness", "COMPLETE");
        detail.putArray("truncatedSections");
    }

    private void markTruncated(ObjectNode detail, String section) {
        detail.put("traceCompleteness", "INCOMPLETE");
        var sections = (ArrayNode) detail.withArray("truncatedSections");
        for (var existing : sections) if (existing.asText().equals(section)) return;
        sections.add(section);
    }

    /** Fetch one overflow row, expose at most the newest 100, then display chronologically. */
    private List<Map<String, Object>> boundedTrace(ObjectNode detail, String section, List<Map<String, Object>> rows) {
        if (rows.size() > 100) markTruncated(detail, section);
        var newest = new ArrayList<>(rows.subList(0, Math.min(100, rows.size())));
        Collections.reverse(newest);
        return newest;
    }

    private Map<String, Object> scoped(OpsContext c, String table, UUID id) {
        var r = one(
                "SELECT * FROM platform." + table + " WHERE id=? AND organization_id=? AND environment_id=?",
                id,
                c.organizationId(),
                c.environmentId());
        if (r == null) throw notFound();
        if (r.get("currency") != null) enabled(c, r.get("currency").toString());
        return r;
    }

    private void enabled(OpsContext c, String currency) {
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM platform.org_currency_enabled WHERE organization_id=? AND"
                                + " environment_id=? AND currency=? AND status='ACTIVE'",
                        Long.class,
                        c.organizationId(),
                        c.environmentId(),
                        currency)
                != 1) throw notFound();
    }

    private ObjectNode project(Map<String, Object> r, String... fields) {
        var out = json.createObjectNode();
        for (String f : fields) set(out, OpsCommandService.camel(f), r.get(f));
        return out;
    }

    private void set(ObjectNode out, String key, Object value) {
        out.set(key, json.valueToTree(OpsCommandService.value(value)));
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private JsonNode tree(Object value) {
        try {
            return json.readTree(value.toString());
        } catch (Exception e) {
            throw invalid();
        }
    }

    private long age(Object t) {
        return Math.max(
                0,
                Duration.between(((Timestamp) t).toInstant(), clock.instant()).toSeconds());
    }

    private static long number(Map<String, Object> r, String f) {
        return r.get(f) == null ? 0 : ((Number) r.get(f)).longValue();
    }

    private static int limit(String value) {
        try {
            int n = value == null ? 50 : Integer.parseInt(value);
            if (n < 1 || n > 200) throw invalid();
            return n;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private UUID cursor(OpsContext c, String kind, String value, String fingerprint) {
        if (value == null) return new UUID(0, 0);
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5
                    || !fields[0].equals(c.organizationId().toString())
                    || !fields[1].equals(c.environmentId().toString())
                    || !fields[2].equals(kind)
                    || !fields[4].equals(fingerprint)) throw invalid();
            return uuid(fields[3]);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private String encodeCursor(OpsContext c, String kind, UUID id, String fingerprint) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (c.organizationId() + "|" + c.environmentId() + "|" + kind + "|" + id + "|" + fingerprint)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static String exceptionType(String reason) {
        if (reason.contains("FEE")) return "FEE_VARIANCE";
        if (reason.contains("BALANCE")) return "BALANCE_RESIDUAL";
        if (reason.contains("BANK") || reason.contains("PAYOUT")) return "PAYOUT_WAITING";
        if (reason.contains("AMOUNT")) return "AMOUNT_MISMATCH";
        return "UNKNOWN_SOURCE";
    }

    private static Timestamp stamp(String s) {
        try {
            return Timestamp.from(Instant.parse(s));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid operations query");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Resource not found in caller scope");
    }
}
