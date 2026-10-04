package com.paymentsledger.reconciliation;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.bank.BankMatcher;
import com.paymentsledger.ledger.*;
import com.paymentsledger.provider.ProviderScope;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent read arithmetic. It never calls a posting or projection mutation function. */
final class ReconciliationChecks {
    record Evaluation(List<ReconciliationReport.Check> checks, Map<String,Object> versions,
            List<ExceptionFact> exceptions, boolean complete) { }
    record ExceptionFact(String reason,String source,BigInteger residual,String owner,Map<String,Object> versions,Map<String,Object> evidence) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final BankMatcher bank;
    private final Map<String,Object> run;
    private final ProviderScope scope;
    private final String currency,snapshot;
    private final List<Map<String,Object>> bts;
    private final ImportCoverage.Result coverage;
    private final Map<String,Object> versions=new LinkedHashMap<>();
    private final List<ExceptionFact> exceptions=new ArrayList<>();

    ReconciliationChecks(JdbcTemplate jdbc,ObjectMapper json,Clock clock,BankMatcher bank,Map<String,Object> run,String snapshot) {
        this.jdbc=jdbc;this.json=json;this.clock=clock;this.bank=bank;this.run=run;this.snapshot=snapshot;
        scope=ReconciliationService.scope(run);currency=run.get("currency").toString();
        coverage=new ImportCoverage(jdbc,json).inspect(run);
        bts=jdbc.queryForList("""
            SELECT * FROM platform.balance_transaction WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?
            AND provider_created_at>=? AND provider_created_at<? ORDER BY bt_id
            """,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),run.get("from_at"),run.get("until_at"));
        versions.put("importWindow",run.get("import_window_id").toString());versions.put("rule",run.get("rule_version"));
        versions.put("pages",coverage.pages());versions.put("snapshot",snapshot);
        versions.put("providerCutoff",ReconciliationService.instant(run,"until_at").toString());
        versions.put("ledgerAsOf",jdbc.queryForObject("SELECT transaction_timestamp()::text",String.class));
        versions.put("balanceTransactions",pins(bts));
        versions.put("journals",pins(jdbc.queryForList("SELECT id,financial_event_id,sealed_at,created_at FROM platform.journal WHERE organization_id=? AND environment_id=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),currency)));
        versions.put("events",pins(jdbc.queryForList("SELECT * FROM platform.financial_event WHERE organization_id=? AND environment_id=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),currency)));
        versions.put("ledgerEntries",pins(jdbc.queryForList("SELECT e.* FROM platform.journal_entry e WHERE e.organization_id=? AND e.environment_id=? AND e.currency=? ORDER BY e.journal_id,e.line_no",scope.organizationId(),scope.environmentId(),currency)));
        versions.put("projection",pins(jdbc.queryForList("SELECT * FROM platform.balance_projection WHERE organization_id=? AND environment_id=? AND currency=? ORDER BY ledger_account_id",scope.organizationId(),scope.environmentId(),currency)));
        versions.put("openingPolicy",pins(List.of(policy())));
        versions.put("importCursor",pins(jdbc.queryForList("SELECT * FROM platform.import_cursor WHERE window_id=?",run.get("import_window_id"))));
        versions.put("feeModels",pins(jdbc.queryForList("SELECT * FROM platform.fee_model WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),currency)));
        versions.put("cutoffLimitation","Provider business timestamps bound BT; database snapshot bounds local observations. Untimed non-BT PSP movements cannot establish historical closing.");
    }
    Evaluation evaluate(IndependentBalanceEvidence independent) {
        versions.put("independentEvidence",independent==null?"ABSENT":json.valueToTree(independent));
        var results=List.of(trialBalance(),refunds(),shares(),btCoverage(),psp(independent),payouts(),projection());
        // A definite violation takes precedence in a check's outcome, but does not
        // establish completeness of its other inputs. Preserve both conclusions.
        boolean complete=results.stream().noneMatch(c->c.outcome().equals("INCOMPLETE") || missingInputs(c.completeness()))
                && exceptions.stream().noneMatch(e->e.reason().equals("UNMODELED_FEE"));
        return new Evaluation(results,Map.copyOf(versions),List.copyOf(exceptions),complete);
    }
    private boolean missingInputs(Map<String,Object> completeness) {
        if(Boolean.FALSE.equals(completeness.get("pagesComplete")) || Boolean.TRUE.equals(completeness.get("unresolved")))return true;
        for(String key:List.of("pending","unclassified","unknown","waiting")) {
            Object value=completeness.get(key);
            if(value instanceof Number count && new BigDecimal(count.toString()).signum()>0)return true;
        }
        return false;
    }
    private ReconciliationReport.Check trialBalance() {
        long invalid=count("""
            SELECT count(*) FROM (SELECT j.id FROM platform.journal j LEFT JOIN platform.journal_entry e ON e.journal_id=j.id
            WHERE j.organization_id=? AND j.environment_id=? AND j.currency=? GROUP BY j.id,j.sealed_at
            HAVING j.sealed_at IS NULL OR count(e.id)<2 OR coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0)<>0) x
            """,scope.organizationId(),scope.environmentId(),currency);
        // V3 canonical financial_event has no status: every persisted row is an accepted event.
        long missing=count("""
            SELECT count(*) FROM platform.financial_event e LEFT JOIN platform.journal j ON j.financial_event_id=e.id
            WHERE e.organization_id=? AND e.environment_id=? AND e.currency=? AND j.id IS NULL
            """,scope.organizationId(),scope.environmentId(),currency);
        long orphan=count("""
            SELECT count(*) FROM platform.journal j LEFT JOIN platform.financial_event e ON e.id=j.financial_event_id
            WHERE j.organization_id=? AND j.environment_id=? AND j.currency=? AND (e.id IS NULL OR e.organization_id<>j.organization_id OR e.environment_id<>j.environment_id OR e.currency<>j.currency)
            """,scope.organizationId(),scope.environmentId(),currency);
        long failures=invalid+missing+orphan;
        return check(1,"Trial balance and bidirectional event coverage",failures>0?"FAIL":"PASS",BigInteger.valueOf(failures),failures>0?"UNBALANCED_UNSEALED_OR_EVENT_COVERAGE":"SEALED_BALANCED_BIDIRECTIONAL",Map.of("invalidJournals",invalid,"missingJournals",missing,"orphanJournals",orphan));
    }
    private ReconciliationReport.Check refunds() {
        var controls=jdbc.queryForList("SELECT c.*,p.captured_amount_minor FROM platform.refund_control c JOIN platform.payment p ON p.id=c.payment_id WHERE c.organization_id=? AND c.environment_id=? AND c.provider_account_id=? AND c.currency=?",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency);
        var refunds=jdbc.queryForList("SELECT * FROM platform.refund WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency);
        var tasks=jdbc.queryForList("SELECT * FROM platform.recovery_task WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency);
        versions.put("refundControls",pins(controls));versions.put("refunds",pins(refunds));versions.put("recoveryTasks",pins(tasks));
        long failures=0,unknown=0;
        BigInteger residual=BigInteger.ZERO;
        for(var control:controls) {
            BigInteger returned=BigInteger.ZERO,held=BigInteger.ZERO;
            for(var refund:refunds)if(Objects.equals(control.get("payment_id"),refund.get("payment_id"))) {
                if("SUCCEEDED".equals(refund.get("refund_status")))returned=returned.add(number(refund,"amount_minor"));
                else if(!Set.of("FAILED","CANCELED").contains(refund.get("refund_status")))held=held.add(number(refund,"amount_minor"));
                if(!"KNOWN".equals(refund.get("resolution_status")))unknown++;
            }
            BigInteger captured=number(control,"captured_minor"),r=number(control,"refunded_minor"),h=number(control,"reserved_minor");
            BigInteger difference=r.subtract(returned).abs().add(h.subtract(held).abs()).add(captured.subtract(number(control,"captured_amount_minor")).abs());
            if(difference.signum()!=0 || captured.signum()<0 || r.signum()<0 || h.signum()<0 || ("NORMAL".equals(control.get("control_state"))&&r.add(h).compareTo(captured)>0))failures++;
            residual=residual.add(difference);
            if(!"NORMAL".equals(control.get("control_state")))unknown++;
        }
        for(var refund:refunds)if(refund.get("source").equals("LOCAL") && !Set.of("SUCCEEDED","FAILED","CANCELED").contains(refund.get("refund_status"))) {
            Instant since=refund.get("unresolved_since")==null?ReconciliationService.instant(refund,"created_at"):ReconciliationService.instant(refund,"unresolved_since");
            if(since.plus(Duration.ofHours(1)).isBefore(clock.instant())) {
                boolean handled=tasks.stream().anyMatch(t->Objects.equals(t.get("refund_id"),refund.get("id")) && (t.get("age_alert_at")!=null || t.get("escalated_at")!=null));
                if(!handled)failures++;
            }
        }
        for(var task:tasks)if(!"DONE".equals(task.get("status"))) {
            unknown++;
            Instant created=ReconciliationService.instant(task,"created_at");
            boolean expired=task.get("lease_until")!=null && ReconciliationService.instant(task,"lease_until").isBefore(clock.instant());
            if((created.plus(Duration.ofHours(1)).isBefore(clock.instant()) || expired&&"RUNNING".equals(task.get("status")))
                    && task.get("age_alert_at")==null && task.get("escalated_at")==null)failures++;
        }
        String outcome=failures>0?"FAIL":unknown>0?"INCOMPLETE":"PASS";
        return check(2,"Refund controls and aging",outcome,residual,failures>0?"REFUND_COUNTER_OR_UNALERTED_AGING_VIOLATION":unknown>0?"UNRESOLVED_EXTERNAL_REFUNDS_OR_LEASES":"RECONSTRUCTED_REFUND_LIMITS_AND_ALERTS",Map.of("failures",failures,"unknown",unknown,"ageRule","PT1H"));
    }
    private ReconciliationReport.Check shares() {
        var allocations=jdbc.queryForList("""
            SELECT a.*,p.captured_amount_minor,p.resolution_status,p.financial_status,o.share_policy_snapshot,
            coalesce(c.refunded_minor,0) AS refunded_minor,coalesce(c.reserved_minor,0) AS reserved_minor,coalesce(c.control_state,'NORMAL') AS control_state
            FROM platform.share_allocation a JOIN platform.payment p ON p.id=a.payment_id JOIN platform.business_order o ON o.id=p.business_order_id
            LEFT JOIN platform.refund_control c ON c.payment_id=p.id WHERE a.organization_id=? AND a.environment_id=? AND a.provider_account_id=? AND a.currency=? ORDER BY a.id
            """,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency);
        versions.put("shareAllocations",pins(allocations));
        long failures=0;
        BigInteger residual=BigInteger.ZERO;
        var rateTotals=new HashMap<Object,Integer>();
        for(var allocation:allocations) {
            int rate=((Number)allocation.get("rate_bps")).intValue();rateTotals.merge(allocation.get("payment_id"),rate,Integer::sum);
            BigInteger captured=number(allocation,"captured_amount_minor");
            BigInteger covered=sum("SELECT coalesce(sum(amount_minor::numeric),0) FROM platform.balance_transaction WHERE payment_id=? AND state='POSTED' AND lower(business_type)='charge' AND amount_minor>0",allocation.get("payment_id"));
            boolean eligible="KNOWN".equals(allocation.get("resolution_status")) && !"CONFLICT".equals(allocation.get("financial_status")) && "NORMAL".equals(allocation.get("control_state")) && captured.equals(covered)
                    && count("SELECT count(*) FROM platform.dispute WHERE payment_id=? AND blocking",allocation.get("payment_id"))==0
                    && count("SELECT count(*) FROM platform.balance_transaction b WHERE payment_id=? AND (state='QUARANTINED' OR EXISTS(SELECT 1 FROM platform.bt_evidence_conflict c WHERE c.balance_transaction_id=b.id))",allocation.get("payment_id"))==0;
            BigInteger base=eligible?captured.subtract(number(allocation,"refunded_minor")).subtract(number(allocation,"reserved_minor")).max(BigInteger.ZERO):BigInteger.ZERO;
            BigInteger cap=base.multiply(BigInteger.valueOf(rate)).divide(BigInteger.valueOf(10000));
            BigInteger effective=number(allocation,"effective_minor");
            if(effective.signum()<0 || effective.compareTo(cap)>0 || number(allocation,"cap_minor").compareTo(cap)>0)failures++;
            try {
                JsonNode policy=json.readTree(allocation.get("share_policy_snapshot").toString()),partner=policy;
                if(policy.path("allocations").isArray()) {
                    partner=null;
                    for(var item:policy.path("allocations"))if(item.path("partner_ref").asText().equals(allocation.get("partner_ref"))){if(partner!=null)failures++;partner=item;}
                }
                if(partner==null || !partner.path("partner_ref").asText().equals(allocation.get("partner_ref")) || partner.path("share_basis_points").asInt(-1)!=rate || !policy.path("policy_version").asText().equals(allocation.get("policy_version")))failures++;
            } catch(Exception malformed){failures++;}
            var history=jdbc.queryForList("SELECT * FROM platform.share_adjustment WHERE allocation_id=? ORDER BY generation",allocation.get("id"));
            versions.put("shareHistory:"+allocation.get("id"),pins(history));
            BigInteger historical=BigInteger.ZERO;
            long generation=0;
            for(var movement:history) {
                generation++;
                if(number(movement,"generation").longValueExact()!=generation)failures++;
                BigInteger delta=number(movement,"delta_minor");historical=historical.add(delta);
                var entries=jdbc.queryForList("SELECT e.*,a.account_purpose,j.sealed_at FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id JOIN platform.journal j ON j.id=e.journal_id WHERE e.journal_id=?",movement.get("journal_id"));
                BigInteger expense=BigInteger.ZERO,payable=BigInteger.ZERO;
                for(var entry:entries) {
                    if(entry.get("sealed_at")==null)failures++;
                    BigInteger signed=number(entry,"amount_minor").multiply("DEBIT".equals(entry.get("side"))?BigInteger.ONE:BigInteger.ONE.negate());
                    if("PARTNER_SHARE_EXPENSE".equals(entry.get("account_purpose")))expense=expense.add(signed);
                    else if("PARTNER_PAYABLE".equals(entry.get("account_purpose")))payable=payable.add(signed);else failures++;
                }
                if(entries.size()!=2 || !expense.equals(delta) || !payable.equals(delta.negate()))failures++;
            }
            if(!historical.equals(effective) || number(allocation,"generation").longValueExact()!=generation)failures++;
            residual=residual.add(effective.subtract(historical).abs()).add(effective.subtract(cap).max(BigInteger.ZERO));
        }
        if(rateTotals.values().stream().anyMatch(rate->rate>10000))failures++;
        return check(3,"Share ceilings and journal coverage",failures>0?"FAIL":"PASS",residual,failures>0?"SHARE_CAP_POLICY_OR_JOURNAL_COVERAGE_VIOLATION":"CUMULATIVE_SHARE_CAP_AND_HISTORY_MATCH",Map.of("failures",failures,"allocations",allocations.size()));
    }
    private ReconciliationReport.Check payouts() {
        var payouts=jdbc.queryForList("SELECT * FROM platform.payout WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),currency);
        versions.put("payouts",pins(payouts));
        // evidenceState also consumes unmatched lines and statement coverage. Freeze those inputs,
        // including provider attribution, rather than only the successful allocation history.
        versions.put("bankStatements",pins(jdbc.queryForList("SELECT * FROM platform.bank_statement WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),currency)));
        versions.put("bankLines",pins(jdbc.queryForList("SELECT * FROM platform.bank_statement_line WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND currency=? ORDER BY id",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),currency)));
        versions.put("bankEvaluationClock",clock.instant().toString());
        versions.put("bankDatabaseEvaluationStartedAt",jdbc.queryForObject("SELECT clock_timestamp()::text",String.class));
        long failures=0,waiting=0;
        BigInteger residual=BigInteger.ZERO;
        var states=new LinkedHashMap<String,Object>();
        for(var payout:payouts) {
            UUID id=(UUID)payout.get("id");
            var facts=jdbc.queryForList("SELECT f.*,b.state AS bt_state,b.payout_id AS bt_payout_id,b.journal_id AS bt_journal_id,j.sealed_at FROM platform.payout_fact f LEFT JOIN platform.balance_transaction b ON b.id=f.balance_transaction_id LEFT JOIN platform.journal j ON j.id=f.journal_id WHERE f.payout_id=? ORDER BY f.id",id);
            var matches=jdbc.queryForList("SELECT m.*,j.sealed_at,l.fee_minor AS line_fee,l.principal_minor AS line_principal,l.allocated_minor,l.fee_allocated_minor,s.complete,s.coverage_from,s.coverage_until FROM platform.payout_bank_match m LEFT JOIN platform.journal j ON j.id=m.journal_id LEFT JOIN platform.bank_statement_line l ON l.id=m.line_id LEFT JOIN platform.bank_statement s ON s.id=l.statement_id WHERE m.payout_id=? ORDER BY m.id",id);
            versions.put("payoutFacts:"+id,pins(facts));versions.put("payoutBankMatches:"+id,pins(matches));
            BigInteger outgoing=BigInteger.ZERO,transit=BigInteger.ZERO,returned=BigInteger.ZERO,bankPrincipal=BigInteger.ZERO,suspense=BigInteger.ZERO,fees=BigInteger.ZERO;
            for(var fact:facts) {
                if("OUT".equals(fact.get("kind")))outgoing=outgoing.add(number(fact,"principal_minor"));
                transit=transit.add(number(fact,"transit_delta"));returned=returned.add(number(fact,"returned_delta"));suspense=suspense.add(number(fact,"suspense_delta"));fees=fees.add(number(fact,"fee_minor"));
                if(!"POSTED".equals(fact.get("bt_state")) || !id.equals(fact.get("bt_payout_id")) || !Objects.equals(fact.get("journal_id"),fact.get("bt_journal_id")) || fact.get("sealed_at")==null)failures++;
                BigInteger expectedPsp=number(fact,"principal_minor").multiply("OUT".equals(fact.get("kind"))?BigInteger.ONE.negate():BigInteger.ONE).subtract(number(fact,"fee_minor"));
                if(!journalAmount(fact.get("journal_id"),"PSP_FUNDS").equals(expectedPsp)
                        || !journalAmount(fact.get("journal_id"),"PSP_FEE_EXPENSE").equals(number(fact,"fee_minor"))
                        || !journalAmount(fact.get("journal_id"),"BANK_PAYOUT_IN_TRANSIT").equals(number(fact,"transit_delta"))
                        || !journalAmount(fact.get("journal_id"),"SUSPENSE").equals(number(fact,"suspense_delta").negate()))failures++;
            }
            for(var match:matches) {
                BigInteger principal=number(match,"principal_minor");
                if(match.get("sealed_at")==null || number(match,"allocated_minor").compareTo(number(match,"line_principal"))>0 || number(match,"fee_allocated_minor").compareTo(number(match,"line_fee"))>0)failures++;
                if("MATCH".equals(match.get("kind"))) {transit=transit.subtract(principal);bankPrincipal=bankPrincipal.add(principal);}
                else {bankPrincipal=bankPrincipal.subtract(principal);returned=returned.add(principal);suspense=suspense.subtract(principal);}
                BigInteger cash=sum("SELECT coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id WHERE e.journal_id=? AND a.account_purpose='BANK_CASH'",match.get("journal_id"));
                BigInteger expected="MATCH".equals(match.get("kind"))?principal.subtract(number(match,"fee_minor")):principal.negate();
                if(!cash.equals(expected))failures++;
                boolean receipt="MATCH".equals(match.get("kind"));
                if(!journalAmount(match.get("journal_id"),"BANK_FEE_EXPENSE").equals(receipt?number(match,"fee_minor"):BigInteger.ZERO)
                        || !journalAmount(match.get("journal_id"),"BANK_PAYOUT_IN_TRANSIT").equals(receipt?principal.negate():BigInteger.ZERO)
                        || !journalAmount(match.get("journal_id"),"SUSPENSE").equals(receipt?BigInteger.ZERO:principal))failures++;

            }
            BigInteger difference=outgoing.subtract(number(payout,"outgoing_minor")).abs().add(transit.subtract(number(payout,"transit_minor")).abs()).add(bankPrincipal.subtract(number(payout,"bank_confirmed_minor")).abs()).add(returned.subtract(number(payout,"returned_minor")).abs()).add(suspense.subtract(number(payout,"suspense_minor")).abs()).add(fees.subtract(number(payout,"psp_fee_minor")).abs());
            BigInteger conservation=number(payout,"outgoing_minor").subtract(number(payout,"transit_minor")).subtract(number(payout,"bank_confirmed_minor")).subtract(number(payout,"returned_minor"));
            residual=residual.add(difference).add(conservation.abs());
            if(difference.signum()!=0 || conservation.signum()!=0 || transit.signum()<0 || bankPrincipal.signum()<0 || returned.signum()<0 || Boolean.TRUE.equals(payout.get("conflict")))failures++;
            String state=bank==null?"WAITING_EVIDENCE":bank.evidenceState(scope,id);states.put(id.toString(),state);
            if(Set.of("CONFLICT","SHORT_RECEIPT").contains(state))failures++;
            else if(!Set.of("MATCHED","RETURNED").contains(state))waiting++;
        }
        versions.put("bankDatabaseEvaluationCompletedAt",jdbc.queryForObject("SELECT clock_timestamp()::text",String.class));
        return check(6,"Payout cash transit returns and fees",failures>0?"FAIL":waiting>0?"INCOMPLETE":"PASS",residual,failures>0?"PAYOUT_HISTORY_OVERLAP_OR_SHORT_RECEIPT":waiting>0?"BANK_OR_PSP_EVIDENCE_MISSING":"PAYOUT_PRINCIPAL_AND_DISTINCT_FEES_MATCH",Map.of("failures",failures,"waiting",waiting,"states",states,"evidenceType","SIMULATED_BANK_EVIDENCE"));
    }

    private ReconciliationReport.Check btCoverage() {
        long failures=coverage.failures(),pending=0,unclassified=0;
        var availableIds=new HashSet<String>();
        for(var bt:bts) {
            availableIds.add(bt.get("bt_id").toString());
            // Provider pages cover the whole account. Retain every ID for page coverage,
            // while financial state and fee checks apply only to this run's money scope.
            if(!currency.equals(bt.get("currency")) || !"PAYMENTS".equals(bt.get("balance_domain")))continue;
            String state=bt.get("state").toString();
            if(state.equals("POSTED")) {
                if(count("SELECT count(*) FROM platform.journal j JOIN platform.financial_event e ON e.id=j.financial_event_id WHERE j.id=? AND j.sealed_at IS NOT NULL AND e.source_domain='PROVIDER' AND e.source_ref=? AND e.provider_account_id=?",bt.get("journal_id"),bt.get("bt_id"),scope.providerAccountId())!=1)failures++;
                if(!journalAmount(bt.get("journal_id"),"PSP_FUNDS").equals(number(bt,"net_minor")))failures++;
                if("UNKNOWN_SOURCE".equals(bt.get("reason")))unclassified++;
            } else if(state.equals("EXCLUDED")) {
                var policy=policy();
                if(policy.get("cutoff_at")==null || ReconciliationService.instant(bt,"provider_created_at").isAfter(ReconciliationService.instant(policy,"cutoff_at")))failures++;
            } else if(state.equals("ZERO")) {
                if(number(bt,"net_minor").signum()!=0)failures++;
                unclassified++;
            } else pending++;
            if(!state.equals("EXCLUDED") && Set.of("charge","refund","dispute","payout").contains(Objects.toString(bt.get("source_object_type"),""))) {
                var comparison=new SourceComparison(jdbc).compare(scope,bt,ReconciliationService.instant(run,"until_at"));
                versions.put("sourceObject:"+bt.get("bt_id"),pins(comparison.inputs()));
                if(comparison.outcome().equals("FAIL")) {
                    failures++;
                    exceptions.add(new ExceptionFact(comparison.reason(),bt.get("bt_id").toString(),comparison.residual(),"payments-operations",Map.of("btVersion",bt.get("version")),Map.of("sourceType",bt.get("source_object_type"),"sourceId",Objects.toString(bt.get("source_object_id"),""))));
                } else if(comparison.outcome().equals("INCOMPLETE")) {
                    pending++;
                    exceptions.add(new ExceptionFact(comparison.reason(),bt.get("bt_id").toString(),comparison.residual(),"payments-operations",Map.of("btVersion",bt.get("version")),Map.of("sourceType",bt.get("source_object_type"),"sourceId",Objects.toString(bt.get("source_object_id"),""))));
                }
            }
            if(count("SELECT count(*) FROM platform.bt_evidence_conflict WHERE balance_transaction_id=?",bt.get("id"))>0)pending++;
            if(!Objects.equals(bt.get("api_version"),apiVersion()))pending++;
            // Model expectations explain actual facts; they never change the accepted fee.
            var fee=new FeeModelService(jdbc).compare(scope,currency,"PAYMENTS",(String)bt.get("business_type"),ReconciliationService.instant(bt,"provider_created_at"),number(bt,"amount_minor").longValueExact(),number(bt,"fee_minor").longValueExact());
            if(!fee.matches()) {
                var feeVersions=new LinkedHashMap<String,Object>();feeVersions.put("btVersion",bt.get("version"));
                if(fee.modelVersion()!=null){feeVersions.put("feeModel",fee.modelVersion());feeVersions.put("feeModelId",fee.modelId().toString());}
                var fact=new LinkedHashMap<String,Object>();fact.put("actualFee",fee.actualMinor());
                if(fee.expectedMinor()!=null)fact.put("expectedFee",fee.expectedMinor());
                exceptions.add(new ExceptionFact(fee.reason(),bt.get("bt_id").toString(),fee.residual(),"finance",feeVersions,fact));
                if(fee.modeled())failures++;else pending++;
            }

        }
        if(coverage.complete() && !availableIds.containsAll(coverage.ids()))failures++;
        if(coverage.complete() && !coverage.ids().containsAll(availableIds))pending++; // overlap-discovered late facts require a new complete page set.
        var completion=Map.<String,Object>of("pagesComplete",coverage.complete(),"pagesReason",coverage.reason(),"pending",pending,"unclassified",unclassified,"failures",failures);
        String outcome=failures>0?"FAIL":!coverage.complete()||pending>0||unclassified>0?"INCOMPLETE":"PASS";
        return check(4,"BT page and event coverage",outcome,BigInteger.valueOf(failures),outcome.equals("PASS")?"COMPLETE_ACCEPTED_COVERAGE":failures>0?"DEFINITE_BT_COVERAGE_VIOLATION":coverage.complete()?"UNRESOLVED_OR_UNCLASSIFIED_FINANCIAL_FACTS":coverage.reason(),completion);
    }
    private ReconciliationReport.Check psp(IndependentBalanceEvidence evidence) {
        if(count("SELECT count(*) FROM platform.ledger_account WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND currency=? AND account_purpose='PSP_FUNDS' AND balance_domain='PAYMENTS'",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency)!=1)
            return check(5,"Independent PSP three-way balance","INCOMPLETE",BigInteger.ZERO,"PSP_ACCOUNT_NOT_PROVISIONED",Map.of("accountPresent",false));
        var policy=policy();
        Instant cutoff=ReconciliationService.instant(run,"from_at");
        if(policy.get("cutoff_at")!=null && ReconciliationService.instant(policy,"cutoff_at").isAfter(cutoff))cutoff=ReconciliationService.instant(policy,"cutoff_at");
        // A business window is half-open. Only the verified snapshot policy has an inclusive
        // opening cutoff; a BT exactly at an ordinary day's start belongs to that day's delta.
        boolean openingInclusive=policy.get("cutoff_at")!=null
                && !ReconciliationService.instant(policy,"cutoff_at").isBefore(ReconciliationService.instant(run,"from_at"));
        if(evidence==null || !scope.equals(evidence.scope()) || !currency.equals(evidence.currency()) || !"PAYMENTS".equals(evidence.domain())
                || !cutoff.equals(evidence.openingCutoff()) || !ReconciliationService.instant(run,"until_at").equals(evidence.closingCutoff())
                || !Objects.equals(apiVersion(),evidence.apiVersion()) || (evidence.kind()==IndependentBalanceEvidence.Kind.QUIESCENT_SNAPSHOT&&!evidence.quiescenceVerified()))
            return check(5,"Independent PSP three-way balance","INCOMPLETE",BigInteger.ZERO,"MISSING_OR_INCOMPARABLE_INDEPENDENT_CLOSING",Map.of("independent",false));
        if(!coverage.complete())return check(5,"Independent PSP three-way balance","INCOMPLETE",BigInteger.ZERO,coverage.reason(),Map.of("pagesComplete",false));
        var entries=jdbc.queryForList("""
            SELECT e.id,e.side,e.amount_minor,j.id AS journal_id,j.sealed_at,f.event_type,b.bt_id,b.provider_created_at,b.net_minor
            FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id
            JOIN platform.journal j ON j.id=e.journal_id LEFT JOIN platform.financial_event f ON f.id=j.financial_event_id
            LEFT JOIN platform.balance_transaction b ON b.journal_id=j.id
            WHERE a.organization_id=? AND a.environment_id=? AND a.provider_account_id=? AND a.currency=? AND a.account_purpose='PSP_FUNDS' AND a.balance_domain='PAYMENTS'
            """,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),currency);
        versions.put("pspEntries",pins(entries));
        BigInteger opening=BigInteger.ZERO,delta=BigInteger.ZERO,btDelta=BigInteger.ZERO;
        boolean unknownCutoff=false,unresolved=false;
        for(var entry:entries) {
            BigInteger signed=number(entry,"amount_minor").multiply("DEBIT".equals(entry.get("side"))?BigInteger.ONE:BigInteger.ONE.negate());
            if(entry.get("sealed_at")==null){unresolved=true;continue;}
            if("L-OPENING".equals(entry.get("event_type"))) {opening=opening.add(signed);continue;}
            if(entry.get("provider_created_at")==null) {unknownCutoff=true;continue;}
            Instant created=ReconciliationService.instant(entry,"provider_created_at");
            if(created.isBefore(cutoff) || openingInclusive&&created.equals(cutoff))opening=opening.add(signed);
            else if(created.isBefore(evidence.closingCutoff()))delta=delta.add(signed);
        }
        for(var bt:bts) {
            if(!currency.equals(bt.get("currency")) || !"PAYMENTS".equals(bt.get("balance_domain")))continue;
            Instant created=ReconciliationService.instant(bt,"provider_created_at");
            if(created.isBefore(cutoff) || openingInclusive&&created.equals(cutoff))continue;
            btDelta=btDelta.add(number(bt,"net_minor"));
            if(!Set.of("POSTED","ZERO").contains(bt.get("state")) || count("SELECT count(*) FROM platform.bt_evidence_conflict WHERE balance_transaction_id=?",bt.get("id"))>0)unresolved=true;
        }
        if(unknownCutoff)return check(5,"Independent PSP three-way balance","INCOMPLETE",delta.add(opening).subtract(BigInteger.valueOf(evidence.closingMinor())),"NON_BT_PSP_MOVEMENT_HAS_NO_VERIFIED_BUSINESS_CUTOFF",Map.of("comparable",false));
        BigInteger suppliedOpening=BigInteger.valueOf(evidence.openingMinor());
        BigInteger expected=suppliedOpening.add(btDelta),ledger=opening.add(delta),closing=BigInteger.valueOf(evidence.closingMinor());
        BigInteger residual=expected.subtract(closing);
        var values=new LinkedHashMap<String,Object>();values.put("importedClosing",expected);values.put("ledgerClosing",ledger);values.put("independentClosing",closing);values.put("localOpening",opening);values.put("independentOpening",suppliedOpening);values.put("openingExcluded",true);values.put("unresolved",unresolved);
        boolean mismatch=!opening.equals(suppliedOpening)||!delta.equals(btDelta)||!ledger.equals(closing)||residual.signum()!=0;
        if(mismatch && !opening.equals(suppliedOpening))residual=opening.subtract(suppliedOpening);
        if(mismatch && residual.signum()==0)residual=ledger.subtract(expected);
        boolean knownLocalViolation=!opening.equals(suppliedOpening);
        for(var bt:bts)if(currency.equals(bt.get("currency")) && "PAYMENTS".equals(bt.get("balance_domain")) && "POSTED".equals(bt.get("state"))) {
            Instant created=ReconciliationService.instant(bt,"provider_created_at");
            if((created.isAfter(cutoff) || !openingInclusive&&created.equals(cutoff))
                    && !journalAmount(bt.get("journal_id"),"PSP_FUNDS").equals(number(bt,"net_minor")))knownLocalViolation=true;
        }
        values.put("knownLocalViolation",knownLocalViolation);
        String outcome=knownLocalViolation?"FAIL":unresolved?"INCOMPLETE":mismatch?"FAIL":"PASS";
        String reason=knownLocalViolation&&unresolved?"KNOWN_LOCAL_PSP_OPENING_OR_MAPPING_VIOLATION":unresolved?"UNRESOLVED_FINANCIAL_INPUTS":mismatch?"PSP_OPENING_MAPPING_OR_EXTERNAL_RESIDUAL":"THREE_INDEPENDENT_INPUTS_MATCH";
        return check(5,"Independent PSP three-way balance",outcome,residual,reason,values);

    }
    private ReconciliationReport.Check projection() {
        if(count("SELECT count(*) FROM platform.ledger_account WHERE organization_id=? AND environment_id=? AND currency=?",scope.organizationId(),scope.environmentId(),currency)==0)
            return check(7,"Independent projection rebuild and source isolation","INCOMPLETE",BigInteger.ZERO,"LEDGER_SCOPE_NOT_PROVISIONED",Map.of("accountsPresent",false));
        // Invoke the existing independent scanner directly on this bound JDBC connection. Its Spring
        // REQUIRES_NEW proxy would otherwise compare a different snapshot from the other six checks.
        var rebuilt=new BalanceRebuildService(jdbc,clock).rebuild(new LedgerScope(scope.organizationId(),scope.environmentId(),currency,null),BalanceRebuildService.Mode.OPENING_JOURNAL,null);
        versions.put("rebuild",json.valueToTree(rebuilt));
        long isolation=count("""
            SELECT count(*) FROM platform.journal_entry e JOIN platform.journal j ON j.id=e.journal_id
            JOIN platform.ledger_account a ON a.id=e.ledger_account_id LEFT JOIN platform.financial_event f ON f.id=j.financial_event_id
            WHERE j.organization_id=? AND j.environment_id=? AND j.currency=? AND
            (e.organization_id<>j.organization_id OR e.environment_id<>j.environment_id OR e.currency<>j.currency OR a.organization_id<>j.organization_id OR a.environment_id<>j.environment_id OR a.currency<>j.currency
            OR (a.source_domain='PROVIDER' AND (a.provider_account_id IS DISTINCT FROM f.provider_account_id OR a.balance_domain<>'PAYMENTS')))
            """,scope.organizationId(),scope.environmentId(),currency);
        long duplicates=count("""
            SELECT count(*) FROM (SELECT source_ref FROM platform.financial_event WHERE organization_id=? AND environment_id=? AND currency=? AND source_domain='PROVIDER'
            GROUP BY provider_account_id,source_ref HAVING count(DISTINCT coalesce(facts->>'balanceDomain',facts->>'balance_domain','PAYMENTS'))>1) x
            """,scope.organizationId(),scope.environmentId(),currency);
        BigInteger residual=rebuilt.accounts().stream().map(a->a.residual().abs()).reduce(BigInteger.ZERO,BigInteger::add);
        boolean failed=!rebuilt.matches()||isolation>0||duplicates>0;
        return check(7,"Independent projection rebuild and source isolation",failed?"FAIL":"PASS",residual,failed?"PROJECTION_OR_SOURCE_ISOLATION_VIOLATION":"INDEPENDENT_ENTRIES_MATCH_PROJECTION",Map.of("isolationViolations",isolation,"duplicateDomains",duplicates,"rebuildSnapshot",rebuilt.databaseSnapshot()));
    }
    private ReconciliationReport.Check check(int number,String name,String outcome,BigInteger residual,String reason,Map<String,Object> completeness) {
        if(!outcome.equals("PASS"))exceptions.add(new ExceptionFact(reason,"check:"+number,residual,number==2?"payments-operations":"finance",Map.of("snapshot",snapshot),completeness));
        return new ReconciliationReport.Check(number,name,outcome,residual,reason,number==2?"payments-operations":"finance",versions,snapshot,completeness);
    }
    private Map<String,Object> policy() {return jdbc.queryForList("SELECT * FROM platform.ingestion_policy WHERE provider_account_id=?",scope.providerAccountId()).getFirst();}
    private String apiVersion() {return jdbc.queryForObject("SELECT api_version FROM platform.import_cursor WHERE window_id=?",String.class,run.get("import_window_id"));}
    private BigInteger journalAmount(Object journal,String purpose) {
        return sum("SELECT coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) FROM platform.journal_entry e JOIN platform.ledger_account a ON a.id=e.ledger_account_id WHERE e.journal_id=? AND a.account_purpose=?",journal,purpose);
    }
    private BigInteger sum(String sql,Object...args) {return jdbc.queryForObject(sql,BigDecimal.class,args).toBigIntegerExact();}
    private long count(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
    private static BigInteger number(Map<String,Object> row,String key) {return new BigDecimal(row.get(key).toString()).toBigIntegerExact();}
    private List<Map<String,Object>> pins(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{var pin=new LinkedHashMap<String,Object>();row.forEach((key,value)->{if(value!=null){if(value instanceof Timestamp time)pin.put(key,time.toInstant().toString());else if(value instanceof UUID)pin.put(key,value.toString());else if(value instanceof java.sql.Array array){try{pin.put(key,Arrays.asList((Object[])array.getArray()));}catch(java.sql.SQLException invalid){throw new IllegalArgumentException("Invalid bank reference array",invalid);}}else if(value.getClass().getName().equals("org.postgresql.util.PGobject")){try{pin.put(key,json.readTree(value.toString()));}catch(Exception e){pin.put(key,value.toString());}}else pin.put(key,value);}});return (Map<String,Object>)pin;}).toList();
    }
}
