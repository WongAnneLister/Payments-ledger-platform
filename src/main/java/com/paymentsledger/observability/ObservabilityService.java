package com.paymentsledger.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.identity.OpsContext;
import com.paymentsledger.ops.OpsAuthorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Observations of durable work and versioned checks; never posts, repairs, or changes outcomes. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ObservabilityService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public ObservabilityService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    public JsonNode snapshot(OpsContext context) {
        OpsAuthorization.read(context, false);
        return collect(context);
    }

    /** Internal Micrometer sampling: all active test scopes, independent of HTTP caller state. */
    public JsonNode internalSnapshot() { return collect(null); }

    private ObjectNode collect(OpsContext context) {
        Instant observedAt = clock.instant();
        String scopes = """
            WITH eligible AS (
              SELECT p.organization_id,p.environment_id,p.id AS provider_account_id,c.currency
              FROM platform.provider_account p
              JOIN platform.organization o ON o.id=p.organization_id AND o.status='ACTIVE'
              JOIN platform.environment_config e ON e.organization_id=p.organization_id AND e.id=p.environment_id
                AND e.status='ACTIVE' AND NOT e.livemode
              JOIN platform.org_currency_enabled c ON c.organization_id=p.organization_id
                AND c.environment_id=p.environment_id AND c.status='ACTIVE'
              WHERE p.status='ACTIVE' AND NOT p.livemode
            """ + (context == null ? "" : " AND p.organization_id=? AND p.environment_id=?") + ") ";
        Object[] args = context == null ? new Object[0] : new Object[]{context.organizationId(), context.environmentId()};
        var out = json.createObjectNode().put("observedAt", observedAt.toString());
        var metrics = out.putArray("metrics");
        var alerts = out.putArray("alerts");
        Map<String, ObjectNode> values = new LinkedHashMap<>();
        add(values, count("unknown_payment_count", aggregate(scopes,args,"payment", "resolution_status='UNKNOWN'", "coalesce(x.unresolved_since,x.created_at)",true), observedAt));
        add(values, age("unknown_payment_oldest_age", aggregate(scopes,args,"payment", "resolution_status='UNKNOWN'", "coalesce(x.unresolved_since,x.created_at)",true),observedAt));
        add(values, age("refund_reserved_age",aggregate(scopes,args,"refund", "source='LOCAL' AND refund_status IN ('RESERVED','SUBMITTED','PENDING','REQUIRES_ACTION')", "coalesce(x.unresolved_since,x.created_at)",true),observedAt));
        add(values, age("webhook_lag",aggregate(scopes,args,"webhook_inbox", "status<>'DONE' AND NOT livemode", "x.received_at",false),observedAt));
        add(values, age("outbox_oldest_age",aggregate(scopes,args,"outbox_event", "status<>'PUBLISHED'", "x.occurred_at",true),observedAt));
        var paymentPending = aggregate(scopes,args,"payment", "financial_status IN ('PENDING_EVIDENCE','PARTIALLY_POSTED')", "x.created_at",true);
        var btPending = aggregate(scopes,args,"balance_transaction", "state='PENDING_EVIDENCE' AND NOT livemode AND balance_domain='PAYMENTS'", "x.created_at",true);
        add(values, metric("ledger_pending_evidence","count", new BigDecimal(paymentPending.get("count").toString()).add(new BigDecimal(btPending.get("count").toString())).toBigIntegerExact().toString(), earliest(instant(paymentPending.get("source_at")),instant(btPending.get("source_at")))));
        // These are the normalizer/replay's persisted UNKNOWN classifications, including posted suspense.
        add(values, count("unclassified_bt_count", aggregate(scopes,args,"balance_transaction", "reason IN ('UNKNOWN_SOURCE','UNKNOWN_SOURCE_ZERO_MOVEMENT') AND NOT livemode AND balance_domain='PAYMENTS'", "x.updated_at",true), observedAt));
        var reports = jdbc.queryForList(scopes + """
            , latest AS (
              SELECT DISTINCT ON (r.organization_id,r.environment_id,r.provider_account_id,r.currency) r.*
              FROM platform.reconciliation_run r JOIN eligible s ON s.organization_id=r.organization_id
                AND s.environment_id=r.environment_id AND s.provider_account_id=r.provider_account_id AND s.currency=r.currency
              WHERE NOT r.livemode AND r.balance_domain='PAYMENTS'
              ORDER BY r.organization_id,r.environment_id,r.provider_account_id,r.currency,r.created_at DESC,r.id DESC
            )
            SELECT r.id,r.provider_account_id,r.currency,r.state,r.created_at,r.finished_at,
              t.outcome AS trial_outcome,t.created_at AS trial_at,
              h.outcome AS share_outcome,h.created_at AS share_at,
              p.outcome AS psp_outcome,p.residual AS psp_residual,p.completeness AS psp_completeness,p.created_at AS psp_at
            FROM latest r LEFT JOIN platform.reconciliation_check t ON t.run_id=r.id AND t.check_number=1
              LEFT JOIN platform.reconciliation_check h ON h.run_id=r.id AND h.check_number=3
              LEFT JOIN platform.reconciliation_check p ON p.run_id=r.id AND p.check_number=5
            ORDER BY r.provider_account_id,r.currency,r.organization_id,r.environment_id
            """, args);
        add(values, checkFailures("trial_balance_failures",reports,"trial"));
        add(values, checkFailures("share_cap_violations",reports,"share"));
        var residuals = out.putArray("reconciliationResiduals");
        for (var report : reports) {
            Instant source = reportTime(report,"psp");
            var row = residuals.addObject().put("runId",report.get("id").toString())
                .put("providerAccountId",report.get("provider_account_id").toString()).put("currency",report.get("currency").toString());
            if (report.get("psp_residual") == null) row.putNull("residualMinor");
            else row.put("residualMinor",new BigDecimal(report.get("psp_residual").toString()).stripTrailingZeros().toPlainString());
            row.put("outcome",Objects.toString(report.get("psp_outcome"),"INCOMPLETE"));
            var completeness = report.get("psp_completeness") == null ? json.createObjectNode() : tree(report.get("psp_completeness"));
            completeness.put("checkPresent",report.get("psp_outcome") != null).put("runState",report.get("state").toString());
            row.set("dataCompleteness", completeness); setTime(row,"sourceAt",source);
            if (!row.path("outcome").asText().equals("PASS")) {
                var alert = alert("reconciliation_residual", row.path("outcome").asText().equals("FAIL")?"CRITICAL":"WARNING", "Latest PSP reconciliation check is "+row.path("outcome").asText()+".",source);
                alert.put("runId",report.get("id").toString()).put("providerAccountId",report.get("provider_account_id").toString()).put("currency",report.get("currency").toString());
                alerts.add(alert);
            }
        }
        ObjectNode residual;
        if (reports.size()==1 && completePspInputs(reports.getFirst()))
            residual=metric("reconciliation_residual","minor",new BigDecimal(reports.getFirst().get("psp_residual").toString()).stripTrailingZeros().toPlainString(),reportTime(reports.getFirst(),"psp"));
        else {
            residual=metric("reconciliation_residual","minor",null,null);
            alerts.add(alert("reconciliation_residual","WARNING", reports.size()>1?"Multiple provider/currency reports: scalar residual unavailable; inspect dimensioned rows.":"Latest PSP reconciliation report/check is absent or lacks complete inputs; residual unavailable.",null));
        }
        add(values,residual);
        add(values, age("payout_unmatched_age",aggregate(scopes,args,"payout", "bank_match_state IN ('UNMATCHED','PARTIAL','CONFLICT') AND NOT livemode", "x.created_at",true),observedAt));
        // Stable names/order match the page contract. Each threshold is observation-only.
        for (String name : ObservabilityGauges.NAMES) {
            ObjectNode m=values.get(name); metrics.add(m);
            if (!m.path("status").asText().equals("AVAILABLE")) {
                if (Set.of("trial_balance_failures","share_cap_violations").contains(name)) alerts.add(alert(name,"WARNING","Latest reconciliation checks are absent or incomplete.",null));
                continue;
            }
            BigDecimal value = new BigDecimal(m.path("value").asText());
            long threshold = switch(name) {
                case "unknown_payment_oldest_age" -> 900;
                case "refund_reserved_age" -> 3600;
                case "webhook_lag" -> 300;
                case "outbox_oldest_age" -> 600;
                case "payout_unmatched_age" -> 86400;
                default -> 1;
            };
            boolean violated=name.equals("reconciliation_residual")?value.signum()!=0:value.compareTo(BigDecimal.valueOf(threshold))>=0;
            if (violated) alerts.add(alert(name,Set.of("trial_balance_failures","share_cap_violations","reconciliation_residual").contains(name)?"CRITICAL":"WARNING","Observation threshold exceeded for "+name+".",m.path("sourceAt").isNull()?null:Instant.parse(m.path("sourceAt").asText())));
        }
        return out;
    }

    /** A definite FAIL establishes a violation, never the completeness of its other inputs. */
    private boolean completePspInputs(Map<String,Object> report) {
        if (!"COMPLETE".equals(report.get("state")) || report.get("psp_outcome")==null
            || "INCOMPLETE".equals(report.get("psp_outcome")) || report.get("psp_residual")==null
            || report.get("psp_completeness")==null) return false;
        JsonNode completeness=tree(report.get("psp_completeness"));
        if(completeness.path("unresolved").asBoolean(false)) return false;
        for(String key:List.of("pagesComplete","independent","comparable"))
            if(completeness.path(key).isBoolean() && !completeness.path(key).booleanValue()) return false;
        for(String key:List.of("pending","unclassified","unknown","waiting"))
            if(completeness.path(key).isNumber() && completeness.path(key).decimalValue().signum()>0) return false;
        return true;
    }

    private Map<String,Object> aggregate(String scopes,Object[] args,String table,String predicate,String source,boolean currency) {
        return jdbc.queryForMap(scopes+"SELECT count(*) AS count,min("+source+") AS source_at FROM platform."+table+" x WHERE "+predicate+
            " AND EXISTS (SELECT 1 FROM eligible s WHERE s.organization_id=x.organization_id AND s.environment_id=x.environment_id AND s.provider_account_id=x.provider_account_id"+(currency?" AND s.currency=x.currency":"")+")",args);
    }
    private ObjectNode checkFailures(String name,List<Map<String,Object>> reports,String kind) {
        if(reports.isEmpty() || reports.stream().anyMatch(r->r.get(kind+"_outcome")==null || "INCOMPLETE".equals(r.get(kind+"_outcome")))) return metric(name,"count",null,null);
        long failed=reports.stream().filter(r->"FAIL".equals(r.get(kind+"_outcome"))).count();
        Instant source=reports.stream().map(r->reportTime(r,kind)).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        return metric(name,"count",Long.toString(failed),source);
    }
    private ObjectNode count(String name,Map<String,Object> aggregate,Instant observedAt) {return metric(name,"count",aggregate.get("count").toString(),instant(aggregate.get("source_at")));}
    private ObjectNode age(String name,Map<String,Object> aggregate,Instant observedAt) {
        Instant source=instant(aggregate.get("source_at"));
        return metric(name,"seconds",source==null?null:seconds(source,observedAt),source);
    }
    private static String seconds(Instant source,Instant observedAt) {
        Duration duration=Duration.between(source,observedAt);
        if(duration.isNegative()) return "0";
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(),9)).stripTrailingZeros().toPlainString();
    }
    private ObjectNode metric(String name,String unit,String value,Instant source) {
        var m=json.createObjectNode().put("name",name).put("status",value==null?"NO_DATA":"AVAILABLE").put("unit",unit);
        if(value==null)m.putNull("value");else m.put("value",value);setTime(m,"sourceAt",source);return m;
    }
    private ObjectNode alert(String metric,String severity,String message,Instant source) {var a=json.createObjectNode().put("metric",metric).put("severity",severity).put("message",message);setTime(a,"sourceAt",source);return a;}
    private void add(Map<String,ObjectNode> values,ObjectNode metric){values.put(metric.path("name").asText(),metric);}
    private static void setTime(ObjectNode node,String key,Instant value){if(value==null)node.putNull(key);else node.put(key,value.toString());}
    private static Instant instant(Object value){return value==null?null:((Timestamp)value).toInstant();}
    private static Instant earliest(Instant a,Instant b){return a==null?b:b==null?a:a.isBefore(b)?a:b;}
    private static Instant reportTime(Map<String,Object> report,String kind){return instant(report.get("finished_at")!=null?report.get("finished_at"):report.get(kind+"_at")!=null?report.get(kind+"_at"):report.get("created_at"));}
    private ObjectNode tree(Object value){try{return (ObjectNode)json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid stored reconciliation completeness");}}
}
