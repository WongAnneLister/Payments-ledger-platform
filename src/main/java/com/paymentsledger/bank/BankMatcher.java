package com.paymentsledger.bank;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.ledger.*;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Payout(60), bank line(65), financial event and sorted ledger accounts. Money history is append-only. */
@Service public class BankMatcher {
 public record MatchResult(String state,UUID matchId){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;private final LedgerPostingService ledger;private final PayoutService payouts;
 public BankMatcher(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,LedgerPostingService ledger,PayoutService payouts){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json.copy().findAndRegisterModules();this.ledger=ledger;this.payouts=payouts;}
 public MatchResult match(ProviderScope scope,UUID payoutId,UUID lineId,long principal){
  if(principal<=0)throw new IllegalArgumentException("Positive principal allocation required");
  if(TransactionSynchronizationManager.isActualTransactionActive())return apply(scope,payoutId,lineId,principal);
  for(int attempt=0;;attempt++)try{return tx.execute(status->apply(scope,payoutId,lineId,principal));}catch(RuntimeException failure){boolean retry=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&Set.of("40P01","40001").contains(sql.getSQLState()))retry=true;if(!retry||attempt>=2)throw failure;}
 }
 private MatchResult apply(ProviderScope scope,UUID payoutId,UUID lineId,long principal){
  var p=scopedPayout(scope,payoutId,true);var l=one("SELECT * FROM platform.bank_statement_line WHERE id=? FOR UPDATE",lineId);
  if(l==null||!Objects.equals(l.get("organization_id"),scope.organizationId())||!Objects.equals(l.get("environment_id"),scope.environmentId())||!Objects.equals(l.get("provider_account_id"),scope.providerAccountId())||!Objects.equals(l.get("livemode"),scope.livemode())||!p.get("currency").equals(l.get("currency")))throw new IllegalArgumentException("Bank line scope or currency mismatch");
  try{if(!Arrays.asList((String[])((java.sql.Array)l.get("payout_references")).getArray()).contains(p.get("provider_payout_id")))throw new IllegalArgumentException("Payout reference mismatch");}catch(SQLException e){throw new IllegalStateException(e);}
  if(p.get("arrival_date")==null)throw new IllegalArgumentException("Arrival date evidence required");
  long days=java.time.temporal.ChronoUnit.DAYS.between(date(p,"arrival_date"),date(l,"booked_at"));if(days<-7||days>7)throw new IllegalArgumentException("Bank booking outside +/-7 UTC dates");
  boolean credit="CREDIT".equals(l.get("kind"));String kind=credit?"MATCH":"CLAWBACK";
  var prior=one("SELECT * FROM platform.payout_bank_match WHERE payout_id=? AND line_id=? AND kind=?",payoutId,lineId,kind);
  if(prior!=null){if(number(prior,"principal_minor")!=principal)throw new IllegalArgumentException("Stable match identity conflicts with retry amount");return new MatchResult(p.get("bank_match_state").toString(),(UUID)prior.get("id"));}
  if(principal>Math.subtractExact(number(l,"principal_minor"),number(l,"allocated_minor")))throw new IllegalArgumentException("Bank line capacity exceeded");
  if(credit&&(number(p,"returned_minor")>0||number(p,"suspense_minor")>0)&&principal>number(p,"transit_minor")){
   jdbc.update("UPDATE platform.payout SET conflict=true,bank_match_state='CONFLICT',updated_at=now() WHERE id=?",payoutId);
   jdbc.update("INSERT INTO platform.payout_case(payout_id,reason,evidence) VALUES (?,'BANK_RECEIPT_RETURN_OVERLAP',?::jsonb) ON CONFLICT(payout_id,reason) DO NOTHING",payoutId,json.createObjectNode().put("lineId",lineId.toString()).put("principalMinor",principal).toString());return new MatchResult("CONFLICT",null);
  }
  if(credit&&(Boolean.TRUE.equals(p.get("conflict"))||principal>number(p,"transit_minor")))throw new IllegalArgumentException("Payout unresolved or principal capacity exceeded");
  if(!credit&&(principal>number(p,"suspense_minor")||principal>number(p,"bank_confirmed_minor")))throw new IllegalArgumentException("Verified overlap and prior net bank principal required for clawback");
  long consumed=Math.addExact(number(l,"allocated_minor"),principal);
  long fee=credit?Math.subtractExact(BigInteger.valueOf(number(l,"fee_minor")).multiply(BigInteger.valueOf(consumed)).divide(BigInteger.valueOf(number(l,"principal_minor"))).longValueExact(),number(l,"fee_allocated_minor")):0;
  jdbc.queryForObject("SELECT platform.ensure_payout_accounts(?)",Object.class,payoutId);jdbc.queryForObject("SELECT platform.lock_payout_accounts(?)",Object.class,payoutId);
  var accounts=new HashMap<String,UUID>();for(var a:jdbc.queryForList("SELECT id,account_purpose FROM platform.ledger_account WHERE organization_id=? AND environment_id=? AND currency=? AND ((source_domain='SIMULATED_BANK' AND provider_account_id IS NULL) OR(source_domain='PROVIDER' AND provider_account_id=? AND balance_domain='PAYMENTS'))",scope.organizationId(),scope.environmentId(),p.get("currency"),scope.providerAccountId()))accounts.put(a.get("account_purpose").toString(),(UUID)a.get("id"));
  var lines=new ArrayList<PostingLine>();if(credit){add(lines,accounts,"BANK_CASH",principal-fee);add(lines,accounts,"BANK_FEE_EXPENSE",fee);add(lines,accounts,"BANK_PAYOUT_IN_TRANSIT",-principal);}else{add(lines,accounts,"SUSPENSE",principal);add(lines,accounts,"BANK_CASH",-principal);}
  var facts=json.createObjectNode().put("evidenceType","SIMULATED_BANK_EVIDENCE").put("payoutId",payoutId.toString()).put("lineId",lineId.toString()).put("principalMinor",principal).put("feeMinor",fee);
  UUID journal=ledger.post(new LedgerScope(scope.organizationId(),scope.environmentId(),p.get("currency").toString(),scope.providerAccountId()),new PostingCommand(credit?"bank.match:"+payoutId+":"+lineId:"bank.clawback:"+payoutId+":"+lineId+":1",credit?"L-BANK-IN":"L-BANK-CLAWBACK","SIMULATED_BANK",lineId.toString(),facts,lines,null));
  UUID match=UUID.randomUUID();jdbc.update("INSERT INTO platform.payout_bank_match(id,payout_id,line_id,organization_id,environment_id,currency,kind,principal_minor,fee_minor,journal_id) VALUES (?,?,?,?,?,?,?,?,?,?)",match,payoutId,lineId,scope.organizationId(),scope.environmentId(),p.get("currency"),kind,principal,fee,journal);
  long transit=credit?number(p,"transit_minor")-principal:number(p,"transit_minor"),bank=credit?Math.addExact(number(p,"bank_confirmed_minor"),principal):number(p,"bank_confirmed_minor")-principal,returned=credit?number(p,"returned_minor"):Math.addExact(number(p,"returned_minor"),principal),suspense=credit?number(p,"suspense_minor"):number(p,"suspense_minor")-principal;
  String state=Boolean.TRUE.equals(p.get("conflict"))?"CONFLICT":bank==number(p,"outgoing_minor")?"MATCHED":bank>0?"PARTIAL":"UNMATCHED";
  jdbc.update("UPDATE platform.payout SET transit_minor=?,bank_confirmed_minor=?,returned_minor=?,suspense_minor=?,bank_match_state=?,updated_at=now() WHERE id=?",transit,bank,returned,suspense,state,payoutId);
  jdbc.update("UPDATE platform.bank_statement_line SET allocated_minor=?,fee_allocated_minor=? WHERE id=?",consumed,Math.addExact(number(l,"fee_allocated_minor"),fee),lineId);
  return new MatchResult(state,match);
 }
 /** External completeness is separate from the commit-time allocation invariant. PR15 consumes this evidence. */
 public String evidenceState(ProviderScope scope,UUID payoutId){
  var p=scopedPayout(scope,payoutId,false);if(Boolean.TRUE.equals(p.get("conflict")))return "CONFLICT";
  if(number(p,"outgoing_minor")==0||p.get("arrival_date")==null)return "WAITING_EVIDENCE";
  if(number(p,"bank_confirmed_minor")==number(p,"outgoing_minor"))return "MATCHED";
  if(number(p,"returned_minor")==number(p,"outgoing_minor"))return "RETURNED";
  Long complete=jdbc.queryForObject("SELECT count(*) FROM platform.bank_statement_line l JOIN platform.bank_statement s ON s.id=l.statement_id JOIN platform.payout_bank_match m ON m.line_id=l.id AND m.payout_id=? AND m.kind='MATCH' WHERE l.organization_id=? AND l.environment_id=? AND l.provider_account_id=? AND NOT l.livemode AND l.currency=? AND ?=ANY(l.payout_references) AND l.kind='CREDIT' AND s.complete AND s.coverage_from<=? AND s.coverage_until>? AND s.coverage_until<=clock_timestamp() AND l.allocated_minor=l.principal_minor AND (l.booked_at AT TIME ZONE 'UTC')::date BETWEEN ((CAST(? AS timestamptz) AT TIME ZONE 'UTC')::date-7) AND ((CAST(? AS timestamptz) AT TIME ZONE 'UTC')::date+7)",Long.class,payoutId,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),p.get("currency"),p.get("provider_payout_id"),p.get("arrival_date"),p.get("arrival_date"),p.get("arrival_date"),p.get("arrival_date"));
  Long waiting=jdbc.queryForObject("SELECT count(*) FROM platform.bank_statement_line l WHERE l.organization_id=? AND l.environment_id=? AND l.provider_account_id=? AND NOT l.livemode AND l.currency=? AND ?=ANY(l.payout_references) AND l.kind='CREDIT' AND l.allocated_minor<l.principal_minor",Long.class,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),p.get("currency"),p.get("provider_payout_id"));
  return complete>0&&waiting==0&&number(p,"transit_minor")>0?"SHORT_RECEIPT":"WAITING_EVIDENCE";
 }
 private Map<String,Object> scopedPayout(ProviderScope scope,UUID id,boolean lock){if(scope==null||scope.livemode())throw new IllegalArgumentException("Test provider scope required");var p=one("SELECT * FROM platform.payout WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?"+(lock?" FOR UPDATE":""),id,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode());if(p==null)throw new IllegalArgumentException("Payout outside scope");return p;}
 private static LocalDate date(Map<String,Object> row,String field){return ((Timestamp)row.get(field)).toInstant().atZone(ZoneOffset.UTC).toLocalDate();}
 private static void add(List<PostingLine> lines,Map<String,UUID> accounts,String purpose,long amount){if(amount!=0)lines.add(new PostingLine(Objects.requireNonNull(accounts.get(purpose)),amount>0?Side.DEBIT:Side.CREDIT,Math.abs(amount)));}
 private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
}
