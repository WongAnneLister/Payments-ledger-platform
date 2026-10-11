package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.financialingestion.FinancialReplayService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L3 only links existing server-proven orders. Provider I/O never runs inside local transactions. */
public final class ReconciliationSourceRecovery {
 public record Claim(UUID taskId,UUID runId,UUID btId,ProviderScope scope,UUID token,long version) {}
 private record Query(ChargeFact charge,PaymentIntentFact intent,Map<String,Object> evidence,String problem) {}
 private record Linked(String outcome,UUID paymentId) {}
 private static final String SCOPE="organization_id=? AND environment_id=? AND provider_account_id=? AND livemode=?";
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
 private final ChargePort charges;private final PaymentIntentPort intents;private final PaymentRepository payments;
 private final FinancialReplayService replay;private final Clock clock;
 public ReconciliationSourceRecovery(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,ChargePort charges,PaymentIntentPort intents,PaymentRepository payments,FinancialReplayService replay,Clock clock){
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json.copy().findAndRegisterModules();this.charges=charges;this.intents=intents;this.payments=payments;this.replay=replay;this.clock=clock;
 }
 public UUID enqueue(ProviderScope scope,UUID runId,String btId){
  return transaction(()->{
   active(scope);var run=run(scope,runId);var bt=one("SELECT * FROM platform.balance_transaction WHERE "+SCOPE+" AND bt_id=?",append(args(scope),btId));
   if(bt==null || !"PAYMENTS".equals(bt.get("balance_domain")) || !"charge".equals(bt.get("business_type")) || !"charge".equals(bt.get("source_object_type")) || amount(bt,"amount_minor")<=0 || !run.get("currency").equals(bt.get("currency")))throw new IllegalArgumentException("Scoped capture BT required");
   long pages=jdbc.queryForObject("SELECT count(*) FROM platform.import_page WHERE window_id=? AND ?=ANY(bt_ids)",Long.class,run.get("import_window_id"),btId);
   if(pages==0)throw new IllegalArgumentException("BT is not enumerated in this reconciliation run");
   jdbc.update("INSERT INTO platform.recovery_task(organization_id,environment_id,provider_account_id,currency,task_type,reconciliation_run_id,source_bt_id,livemode,available_at) VALUES (?,?,?,?,'RECONCILIATION_SOURCE',?,?,?,?) ON CONFLICT(reconciliation_run_id,source_bt_id) DO NOTHING",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),run.get("currency"),runId,bt.get("id"),scope.livemode(),ts(clock.instant()));
   return jdbc.queryForObject("SELECT id FROM platform.recovery_task WHERE reconciliation_run_id=? AND source_bt_id=?",UUID.class,runId,bt.get("id"));
  });
 }
 public List<Claim> claim(ProviderScope scope,UUID runId,int limit){
  if(limit<1||limit>100)throw new IllegalArgumentException("Bounded claim limit required");
  return transaction(()->{
   active(scope);run(scope,runId);
   var rows=jdbc.queryForList("SELECT * FROM platform.recovery_task WHERE "+SCOPE+" AND reconciliation_run_id=? AND task_type='RECONCILIATION_SOURCE' AND status IN ('READY','WAITING','RUNNING') AND available_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY available_at,id LIMIT ? FOR UPDATE SKIP LOCKED",append(args(scope),runId,ts(clock.instant()),ts(clock.instant()),limit));
   var result=new ArrayList<Claim>();
   for(var row:rows){UUID token=UUID.randomUUID();long version=amount(row,"version")+1;
    jdbc.update("UPDATE platform.recovery_task SET status='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1,version=?,updated_at=? WHERE id=?",token,ts(clock.instant().plusSeconds(60)),version,ts(clock.instant()),row.get("id"));
    result.add(new Claim((UUID)row.get("id"),runId,(UUID)row.get("source_bt_id"),scope,token,version));
   }
   return List.copyOf(result);
  });
 }
 public void drain(ProviderScope scope,UUID runId,int limit){
  if(limit<1||limit>100)throw new IllegalArgumentException("Bounded drain limit required");
  PaymentRepository.outsideTransaction();active(scope);var run=run(scope,runId);
  var ids=jdbc.queryForList("""
   SELECT DISTINCT b.bt_id FROM platform.import_page p JOIN platform.balance_transaction b ON b.bt_id=ANY(p.bt_ids)
   WHERE p.window_id=? AND b.organization_id=? AND b.environment_id=? AND b.provider_account_id=? AND b.livemode=?
    AND b.currency=? AND b.balance_domain='PAYMENTS' AND b.business_type='charge' AND b.source_object_type='charge'
    AND NOT EXISTS(SELECT 1 FROM platform.recovery_task t WHERE t.reconciliation_run_id=? AND t.source_bt_id=b.id)
   ORDER BY b.bt_id LIMIT ?
   """,String.class,run.get("import_window_id"),scope.organizationId(),scope.environmentId(),scope.providerAccountId(),scope.livemode(),run.get("currency"),runId,limit);
  for(String bt:ids)enqueue(scope,runId,bt);
  for(Claim claim:claim(scope,runId,limit))recover(claim);
 }
 public String recover(Claim claim){
  PaymentRepository.outsideTransaction();active(claim.scope());run(claim.scope(),claim.runId());
  var bt=one("SELECT * FROM platform.balance_transaction WHERE "+SCOPE+" AND id=?",append(args(claim.scope()),claim.btId()));
  if(bt==null)throw new IllegalArgumentException("Missing scoped source BT");
  Query read=query(claim.scope(),bt);
  Linked linked=transaction(()->link(claim,bt,read));
  if(linked.paymentId()!=null)replay.replay(claim.scope(),bt.get("bt_id").toString());
  return linked.outcome();
 }
 private Query query(ProviderScope scope,Map<String,Object> bt){
  var evidence=new LinkedHashMap<String,Object>();
  if(charges==null||intents==null)return new Query(null,null,Map.of("classification","UNAVAILABLE"),"SOURCE_READ_UNAVAILABLE");
  try{
   var chargeRead=charges.queryCharge(scope,bt.get("source_object_id").toString());evidence.put("charge",safe(chargeRead));
   if(!trusted(chargeRead))return new Query(null,null,evidence,"SOURCE_READ_UNAVAILABLE");
   ChargeFact charge=chargeRead.payload();
   if(charge.paymentIntentId()==null)return new Query(charge,null,evidence,"SOURCE_EVIDENCE_CONFLICT");
   var intentRead=intents.queryPaymentIntent(scope,charge.paymentIntentId());evidence.put("paymentIntent",safe(intentRead));
   if(!trusted(intentRead))return new Query(charge,null,evidence,"SOURCE_READ_UNAVAILABLE");
   PaymentIntentFact intent=intentRead.payload();
   boolean valid=Objects.equals(chargeRead.evidence().apiVersion(),bt.get("api_version"))&&Objects.equals(intentRead.evidence().apiVersion(),bt.get("api_version"))&&Objects.equals(charge.chargeId(),bt.get("source_object_id"))&&Objects.equals(charge.balanceTransactionId(),bt.get("bt_id"))
    &&Objects.equals(charge.paymentIntentId(),intent.paymentIntentId())&&charge.captured()&&"succeeded".equals(charge.status())&&"succeeded".equals(intent.status())
    &&charge.amountMinor()==amount(bt,"amount_minor")&&Objects.equals(charge.amountCapturedMinor(),charge.amountMinor())&&intent.amountMinor()==charge.amountMinor()
    &&Objects.equals(intent.amountReceivedMinor(),charge.amountMinor())&&charge.currency().equalsIgnoreCase(bt.get("currency").toString())&&intent.currency().equalsIgnoreCase(charge.currency())
    &&Objects.equals(intent.latestChargeId(),charge.chargeId())&&intent.chargeIds().contains(charge.chargeId())
    &&"PAYMENTS".equals(bt.get("balance_domain"))&&"charge".equals(bt.get("business_type"))&&"charge".equals(bt.get("source_object_type"))
    &&!"QUARANTINED".equals(bt.get("state"))&&!"EXCLUDED".equals(bt.get("state"));
   return new Query(charge,intent,evidence,valid?null:"SOURCE_EVIDENCE_CONFLICT");
  }catch(RuntimeException unavailable){return new Query(null,null,Map.of("classification","READ_THROWN"),"SOURCE_READ_UNAVAILABLE");}
 }
 private Linked link(Claim claim,Map<String,Object> bt,Query read){
  active(claim.scope());
  // Discover the immutable order first. No task lock or BT lock is held while acquiring rank10/20.
  Map<String,Object> binding=read.intent()==null?null:one("SELECT * FROM platform.order_provider_binding WHERE "+SCOPE+" AND payment_intent_id=?",append(args(claim.scope()),read.intent().paymentIntentId()));
  Map<String,Object> order=null;
  if(binding!=null){
   Boolean locked=jdbc.queryForObject("SELECT platform.lock_reconciliation_order(?,?,?,?)",Boolean.class,binding.get("order_id"),claim.scope().organizationId(),claim.scope().environmentId(),claim.scope().providerAccountId());
   if(Boolean.TRUE.equals(locked))order=one("SELECT * FROM platform.business_order WHERE id=?",binding.get("order_id"));
  }
  Map<String,Object> payment=order==null?null:one("SELECT * FROM platform.payment WHERE business_order_id=? FOR UPDATE",order.get("id"));
  var task=one("SELECT * FROM platform.recovery_task WHERE id=? FOR UPDATE",claim.taskId());
  if(!owns(task,claim))return new Linked("STALE_CLAIM",null);
  // Import conflict writers lock this BT too. Recheck under the same lock before creating a business link.
  var currentBt=one("SELECT * FROM platform.balance_transaction WHERE "+SCOPE+" AND id=? FOR UPDATE",append(args(claim.scope()),claim.btId()));
  String problem=read.problem();
  if(currentBt==null||Set.of("QUARANTINED","EXCLUDED").contains(currentBt.get("state"))||jdbc.queryForObject("SELECT count(*) FROM platform.bt_evidence_conflict WHERE balance_transaction_id=?",Long.class,claim.btId())>0)problem="SOURCE_EVIDENCE_CONFLICT";
  if(problem==null && binding==null)problem="NO_TRUSTED_ORDER";
  if(problem==null && (!Objects.equals(binding.get("currency"),bt.get("currency")) || amount(binding,"amount_minor")!=read.charge().amountMinor()
    || amount(order,"amount_minor")!=read.charge().amountMinor() || !orderEligible(order,binding)))problem="ORDER_NOT_ELIGIBLE";
  if(problem==null && payment!=null && (amount(payment,"amount_minor")!=read.charge().amountMinor()
    || payment.get("payment_intent_id")!=null&&!payment.get("payment_intent_id").equals(read.intent().paymentIntentId())
    || "CANCELED".equals(payment.get("payment_status")) || "CONFLICT".equals(payment.get("resolution_status"))))problem="SOURCE_EVIDENCE_CONFLICT";
  if(problem!=null){
   UUID observation=observe(claim,read,problem,problem);
   boolean waiting=problem.equals("SOURCE_READ_UNAVAILABLE");
   complete(claim,waiting?"WAITING":"ESCALATED",problem);
   exception(claim,bt,problem,observation);
   return new Linked(waiting?"WAITING_EVIDENCE":"UNALLOCATED",null);
  }
  UUID observation=observe(claim,read,"LINKED",null);String digest=jdbc.queryForObject("SELECT digest FROM platform.reconciliation_source_observation WHERE id=?",String.class,observation);
  UUID paymentId=payment==null?UUID.randomUUID():(UUID)payment.get("id");
  if(payment==null){
   jdbc.update("""
    INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,expires_at,origin,reconciliation_run_id,reconciliation_source_bt_id,reconciliation_evidence_digest)
    VALUES (?,?,?,?,?,?,?,?,?,'RECONCILIATION',?,?,?)
    """,paymentId,order.get("organization_id"),order.get("environment_id"),order.get("application_id"),order.get("provider_account_id"),order.get("currency"),order.get("id"),order.get("amount_minor"),ts(clock.instant().plusSeconds(1)),claim.runId(),claim.btId(),digest);
   jdbc.update("""
    INSERT INTO platform.reconciliation_payment_provenance(payment_id,organization_id,environment_id,application_id,provider_account_id,livemode,currency,run_id,source_bt_id,binding_id,observation_id,charge_id,payment_intent_id,evidence_digest)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """,paymentId,claim.scope().organizationId(),claim.scope().environmentId(),order.get("application_id"),claim.scope().providerAccountId(),claim.scope().livemode(),order.get("currency"),claim.runId(),claim.btId(),binding.get("id"),observation,read.charge().chargeId(),read.intent().paymentIntentId(),digest);
  }
  // Existing repository owns normalized capture/attempt/control/share and canonical notification updates.
  if(!payments.mergeReconciliationQuery(paymentId,new PaymentRepository.Facts(null,read.intent(),List.of(read.charge()),true,false,clock.instant())))throw new IllegalStateException("Verified source merge did not apply");
  complete(claim,"DONE","LINKED");return new Linked("LINKED",paymentId);
 }
 private boolean orderEligible(Map<String,Object> order,Map<String,Object> binding){
  if(binding.get("eligible_until")!=null&&!((Timestamp)binding.get("eligible_until")).toInstant().isAfter(clock.instant()))return false;
  Long eligible=jdbc.queryForObject("""
   SELECT count(*) FROM platform.order_fulfillment_eligibility e JOIN platform.application a ON a.id=e.application_id
   WHERE e.order_id=? AND e.eligible AND e.policy_version=1 AND e.amount_minor=? AND a.status='ACTIVE'
    AND (e.organization_id,e.environment_id,e.application_id,e.provider_account_id,e.currency)=(?,?,?,?,?)
   """,Long.class,order.get("id"),order.get("amount_minor"),order.get("organization_id"),order.get("environment_id"),order.get("application_id"),order.get("provider_account_id"),order.get("currency"));
  return eligible==1;
 }
 private UUID observe(Claim claim,Query read,String outcome,String reason){
  var fact=new LinkedHashMap<String,Object>();
  if(read.charge()!=null){fact.put("chargeId",read.charge().chargeId());fact.put("paymentIntentId",read.charge().paymentIntentId());fact.put("btId",read.charge().balanceTransactionId());fact.put("amountMinor",read.charge().amountMinor());fact.put("capturedAmountMinor",read.charge().amountCapturedMinor());fact.put("currency",read.charge().currency().toUpperCase(Locale.ROOT));fact.put("status",read.charge().status());fact.put("captured",read.charge().captured());}
  if(read.intent()!=null){fact.put("intentStatus",read.intent().status());fact.put("intentReceivedMinor",read.intent().amountReceivedMinor());}
  String encoded=encode(fact);String ev=encode(read.evidence());String digest=sha(encoded+ev);UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO platform.reconciliation_source_observation(id,organization_id,environment_id,provider_account_id,livemode,run_id,source_bt_id,outcome,reason,fact,evidence,digest) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",id,claim.scope().organizationId(),claim.scope().environmentId(),claim.scope().providerAccountId(),claim.scope().livemode(),claim.runId(),claim.btId(),outcome,reason,encoded,ev,digest);return id;
 }
 private void exception(Claim claim,Map<String,Object> bt,String reason,UUID observation){
  // Serialize with finish: operational observations remain append-only while frozen reports never change.
  var report=one("SELECT finished_at FROM platform.reconciliation_run WHERE id=? FOR UPDATE",claim.runId());
  if(report==null||report.get("finished_at")!=null)return;
  jdbc.update("INSERT INTO platform.reconciliation_exception(run_id,organization_id,environment_id,provider_account_id,livemode,currency,reason,owner,source_ref,input_versions,evidence) VALUES (?,?,?,?,?,?,?,'FINANCE_OPERATIONS',?,'{}',?::jsonb)",claim.runId(),claim.scope().organizationId(),claim.scope().environmentId(),claim.scope().providerAccountId(),claim.scope().livemode(),bt.get("currency"),reason,bt.get("bt_id"),encode(Map.of("sourceObservationId",observation)));
 }
 private void complete(Claim claim,String status,String reason){
  int changed=jdbc.update("UPDATE platform.recovery_task SET status=?,reason=?,available_at=?,lease_token=null,lease_until=null,version=version+1,updated_at=?,escalated_at=CASE WHEN ?='ESCALATED' THEN ? ELSE escalated_at END,age_alert_at=CASE WHEN ?='ESCALATED' THEN coalesce(age_alert_at,?) ELSE age_alert_at END WHERE id=? AND lease_token=? AND version=? AND lease_until>?",status,reason,ts(clock.instant().plusSeconds(300)),ts(clock.instant()),status,ts(clock.instant()),status,ts(clock.instant()),claim.taskId(),claim.token(),claim.version(),ts(clock.instant()));
  if(changed!=1)throw new IllegalStateException("L3 completion ownership changed");
 }
 private boolean owns(Map<String,Object> task,Claim claim){return task!=null&&"RUNNING".equals(task.get("status"))&&Objects.equals(task.get("lease_token"),claim.token())&&amount(task,"version")==claim.version()
  &&Objects.equals(task.get("reconciliation_run_id"),claim.runId())&&Objects.equals(task.get("source_bt_id"),claim.btId())&&Objects.equals(task.get("organization_id"),claim.scope().organizationId())&&Objects.equals(task.get("environment_id"),claim.scope().environmentId())&&Objects.equals(task.get("provider_account_id"),claim.scope().providerAccountId())&&Objects.equals(task.get("livemode"),claim.scope().livemode())&&task.get("lease_until")!=null&&((Timestamp)task.get("lease_until")).toInstant().isAfter(clock.instant());}
 private Map<String,Object> run(ProviderScope scope,UUID id){var row=one("SELECT * FROM platform.reconciliation_run WHERE "+SCOPE+" AND id=?",append(args(scope),id));if(row==null)throw new IllegalArgumentException("Unknown scoped reconciliation run");Long enabled=jdbc.queryForObject("SELECT count(*) FROM platform.org_currency_enabled WHERE organization_id=? AND environment_id=? AND currency=? AND status='ACTIVE'",Long.class,scope.organizationId(),scope.environmentId(),row.get("currency"));if(enabled!=1)throw new IllegalArgumentException("Active scoped currency required");return row;}
 private void active(ProviderScope scope){
  if(scope==null||scope.livemode())throw new IllegalArgumentException("Test scope required");
  Long count=jdbc.queryForObject("SELECT count(*) FROM platform.provider_account p JOIN platform.organization o ON o.id=p.organization_id JOIN platform.environment_config e ON e.id=p.environment_id WHERE p.id=? AND p.organization_id=? AND p.environment_id=? AND NOT p.livemode AND NOT e.livemode AND p.status='ACTIVE' AND o.status='ACTIVE' AND e.status='ACTIVE'",Long.class,scope.providerAccountId(),scope.organizationId(),scope.environmentId());
  if(count!=1)throw new IllegalArgumentException("Active trusted provider scope required");
 }
 private static boolean trusted(ProviderCallResult<?> r){if(r==null||r.payload()==null||r.classification()!=ProviderCallClassification.SUCCESS||r.evidence()==null)return false;var e=r.evidence();return Objects.equals(e.httpStatus(),200)&&e.apiVersion()!=null&&!e.apiVersion().isBlank()&&e.rawDigest()!=null&&e.rawDigest().matches("[a-f0-9]{64}")&&e.respondedAt()!=null&&!e.respondedAt().isBefore(e.requestedAt());}
 private static Map<String,Object> safe(ProviderCallResult<?> r){var map=new LinkedHashMap<String,Object>();if(r==null){map.put("classification","MISSING_RESULT");return map;}map.put("classification",r.classification());map.put("evidence",r.evidence());return map;}
 private <T>T transaction(Supplier<T> work){for(int attempt=0;;attempt++){try{return tx.execute(s->work.get());}catch(RuntimeException failure){boolean retry=false;for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql&&Set.of("40P01","40001").contains(sql.getSQLState()))retry=true;if(!retry||attempt>=2)throw failure;}}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid controlled recovery evidence",e);}}
 private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
 private static Object[] args(ProviderScope s){return new Object[]{s.organizationId(),s.environmentId(),s.providerAccountId(),s.livemode()};}
 private static Object[] append(Object[] prefix,Object...values){Object[] result=Arrays.copyOf(prefix,prefix.length+values.length);System.arraycopy(values,0,result,prefix.length,values.length);return result;}
 private static long amount(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
 private static Timestamp ts(Instant at){return Timestamp.from(at);}
}
