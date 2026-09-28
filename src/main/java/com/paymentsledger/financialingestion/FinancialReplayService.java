package com.paymentsledger.financialingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.BalanceTransactionFact;
import com.paymentsledger.ledger.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Replays durable facts. Payment(20) is locked before BT serialization and ledger(70/80); no external I/O. */
public class FinancialReplayService {
    private final JdbcTemplate jdbc;
    private final FinancialTransactions transactions;
    private final ObjectMapper mapper;
    private final LedgerPostingService posting;
    private final RefundEvidencePort refunds;
    private final FinancialEventNormalizer normalizer=new FinancialEventNormalizer();
    private final BTJournalMapper journalMapper=new BTJournalMapper();
    public FinancialReplayService(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper mapper,LedgerPostingService posting,RefundEvidencePort refunds){
        this.jdbc=jdbc;this.transactions=new FinancialTransactions(tx);this.mapper=mapper.copy().findAndRegisterModules();this.posting=posting;this.refunds=refunds;
    }
    public String replay(ProviderScope scope,String btId){return transactions.run(()->{
        var initial=bt(scope,btId,false);
        if(initial==null)throw new IllegalArgumentException("Unknown balance transaction");
        var fact=decode(initial.get("fact").toString());
        var kind=normalizer.classify(fact);
        UUID payment=null;
        if(kind==FinancialEventNormalizer.Kind.CHARGE){var a=charge(scope,fact.sourceObjectId());if(a!=null)payment=(UUID)a.get("payment_id");}
        boolean refundMovement=kind==FinancialEventNormalizer.Kind.REFUND||kind==FinancialEventNormalizer.Kind.REFUND_FAILURE;
        if(refundMovement)payment=refunds.find(scope,fact.sourceObjectId()).map(RefundEvidencePort.Evidence::paymentId).orElse(null);
        Map<String,Object> paymentRow=null;
        if(payment!=null)paymentRow=one("SELECT * FROM platform.payment WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=? FOR UPDATE",payment,scope.organizationId(),scope.environmentId(),scope.providerAccountId());
        var row=bt(scope,btId,true);
        String state=(String)row.get("state");
        if(!"PENDING_EVIDENCE".equals(state))return state;
        var policy=one("SELECT * FROM platform.ingestion_policy WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode());
        if(policy==null)return mark(row,"PENDING_EVIDENCE","OPENING_POLICY_REQUIRED");
        if(policy.get("cutoff_at") instanceof Timestamp cutoff && !fact.createdAt().isAfter(cutoff.toInstant()))return mark(row,"EXCLUDED","BEFORE_VERIFIED_CUTOFF");
        if(jdbc.queryForObject("SELECT count(*) FROM platform.bt_evidence_conflict WHERE balance_transaction_id=?",Long.class,row.get("id"))>0)return quarantine(row,"EVIDENCE_CONFLICT");
        if(jdbc.queryForObject("SELECT count(*) FROM platform.org_currency_enabled WHERE organization_id=? AND environment_id=? AND currency=?",Long.class,scope.organizationId(),scope.environmentId(),fact.currency().toUpperCase(Locale.ROOT))==0)return quarantine(row,"CURRENCY_NOT_ENABLED");
        if(kind==FinancialEventNormalizer.Kind.QUARANTINE)return quarantine(row,"DOMAIN_FX_OR_SIGN_MISMATCH");
        if(kind==FinancialEventNormalizer.Kind.PENDING)return mark(row,"PENDING_EVIDENCE","BUSINESS_DEPENDENCY_REQUIRED");
        if(kind==FinancialEventNormalizer.Kind.CHARGE){
            var attempt=charge(scope,fact.sourceObjectId());
            if(attempt==null||paymentRow==null||attempt.get("balance_transaction_id")==null||!Boolean.TRUE.equals(attempt.get("captured"))||!"succeeded".equals(attempt.get("provider_status")))return mark(row,"PENDING_EVIDENCE","CAPTURE_EVIDENCE_REQUIRED");
            if(!payment.equals(attempt.get("payment_id"))||!fact.btId().equals(attempt.get("balance_transaction_id"))||!fact.currency().equalsIgnoreCase((String)attempt.get("currency"))||attempt.get("amount_captured_minor")==null||fact.amountMinor()!=((Number)attempt.get("amount_captured_minor")).longValue()||!"SUCCEEDED".equals(paymentRow.get("payment_status"))||"CONFLICT".equals(paymentRow.get("resolution_status")))return quarantine(row,"CAPTURE_EVIDENCE_MISMATCH");
        }
        UUID localRefundId=null;
        if(refundMovement){
            var refund=refunds.find(scope,fact.sourceObjectId()).orElse(null);
            if(refund==null||paymentRow==null||!refund.evidenceVerified())return mark(row,"PENDING_EVIDENCE","REFUND_EVIDENCE_REQUIRED");
            boolean returned=kind==FinancialEventNormalizer.Kind.REFUND_FAILURE;
            String expectedBt=returned?refund.failureBalanceTransactionId():refund.balanceTransactionId();
            if(expectedBt==null)return mark(row,"PENDING_EVIDENCE","REFUND_BT_LINK_REQUIRED");
            if(!Objects.equals(payment,refund.paymentId())||!Objects.equals(fact.sourceObjectId(),refund.refundId())||!Objects.equals(fact.btId(),expectedBt)||!fact.currency().equalsIgnoreCase(refund.currency())||fact.amountMinor()==Long.MIN_VALUE||refund.amountMinor()!=(returned?fact.amountMinor():-fact.amountMinor())||!fact.currency().equalsIgnoreCase((String)paymentRow.get("currency")))return quarantine(row,"REFUND_EVIDENCE_MISMATCH");
            localRefundId=refund.localRefundId();
        }
        if(kind==FinancialEventNormalizer.Kind.UNKNOWN&&fact.netMinor()==0)return mark(row,"ZERO","UNKNOWN_SOURCE_ZERO_MOVEMENT");
        Map<String,UUID> accounts=new HashMap<>();
        for(var account:jdbc.queryForList("SELECT id,account_purpose FROM platform.ledger_account WHERE organization_id=? AND environment_id=? AND currency=? AND (provider_account_id=? OR provider_account_id IS NULL)",scope.organizationId(),scope.environmentId(),fact.currency().toUpperCase(Locale.ROOT),scope.providerAccountId()))accounts.put((String)account.get("account_purpose"),(UUID)account.get("id"));
        PostingCommand command;
        try{command=journalMapper.map(scope,fact,kind,accounts);}
        catch(IllegalArgumentException missing){return mark(row,"PENDING_EVIDENCE",missing.getMessage());}
        catch(ArithmeticException invalid){return quarantine(row,"MINOR_UNIT_OVERFLOW");}
        UUID journal=posting.post(new LedgerScope(scope.organizationId(),scope.environmentId(),fact.currency().toUpperCase(Locale.ROOT),scope.providerAccountId()),command);
        jdbc.update("UPDATE platform.balance_transaction SET state='POSTED',journal_id=?,payment_id=?,refund_id=?,reason=?,updated_at=now(),version=version+1 WHERE id=?",journal,payment,localRefundId,kind==FinancialEventNormalizer.Kind.UNKNOWN?"UNKNOWN_SOURCE":null,row.get("id"));
        if(kind==FinancialEventNormalizer.Kind.UNKNOWN)recordQuarantine(row,"UNKNOWN_SOURCE");
        if(kind==FinancialEventNormalizer.Kind.CHARGE){
            // Already owns payment(20); this financial-only update cannot emit a duplicate payment.succeeded event.
            long covered=jdbc.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM platform.balance_transaction WHERE payment_id=? AND state='POSTED' AND lower(business_type)='charge'",Long.class,payment);
            long captured=((Number)paymentRow.get("captured_amount_minor")).longValue();
            String financial="CONFLICT".equals(paymentRow.get("financial_status"))||covered>captured?"CONFLICT":covered==captured?"POSTED":"PARTIALLY_POSTED";
            jdbc.update("UPDATE platform.payment SET financial_status=?,version=version+1,updated_at=now() WHERE id=?",financial,payment);
        }
        return "POSTED";
    });}
    private String mark(Map<String,Object> row,String state,String reason){jdbc.update("UPDATE platform.balance_transaction SET state=?,reason=?,updated_at=now(),version=version+1 WHERE id=?",state,reason,row.get("id"));return state;}
    private String quarantine(Map<String,Object> row,String reason){recordQuarantine(row,reason);return mark(row,"QUARANTINED",reason);}
    private void recordQuarantine(Map<String,Object> row,String reason){jdbc.update("INSERT INTO platform.quarantine_observation(organization_id,environment_id,provider_account_id,livemode,observation_id,balance_transaction_id,reason) VALUES (?,?,?,?,?,?,?)",row.get("organization_id"),row.get("environment_id"),row.get("provider_account_id"),row.get("livemode"),row.get("first_observation_id"),row.get("id"),reason);}
    private Map<String,Object> charge(ProviderScope scope,String source){return one("SELECT * FROM platform.payment_attempt WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_object_id=?",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),source);}
    private Map<String,Object> bt(ProviderScope scope,String btId,boolean lock){return one("SELECT * FROM platform.balance_transaction WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=? AND bt_id=?"+(lock?" FOR UPDATE":""),scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),btId);}
    private Map<String,Object> one(String sql,Object... args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    private BalanceTransactionFact decode(String fact){try{return mapper.readValue(fact,BalanceTransactionFact.class);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored balance transaction",e);}}
}
