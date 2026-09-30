package com.paymentsledger.payment;

import com.paymentsledger.revenueshare.RevenueShareService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.paymentsledger.payment.PaymentRepository.*;

/** Payment/control locks serialize reservations; authoritative observations never perform provider I/O. */
@Repository
public class RefundRepository {
    private com.paymentsledger.dispute.DisputeService disputes;
    private RevenueShareService shares=RevenueShareService.disabled();
 private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final PaymentRepository operations;private RefundEligibilityPolicy eligibility=RefundEligibilityPolicy.unrestricted();
 private static final String SCOPE="organization_id,environment_id,application_id,provider_account_id,currency";
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock){this(jdbc,manager,json,clock,new PaymentRepository(jdbc,manager,json,clock));}
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock,PaymentRepository operations){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.operations=operations;}
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock,PaymentRepository operations,org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies){this(jdbc,manager,json,clock,operations);this.eligibility=policies.getIfAvailable(RefundEligibilityPolicy::unrestricted);}
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,Clock clock,PaymentRepository operations,RevenueShareService shares){this(jdbc,tx,json,clock,operations);this.shares=shares;}
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,Clock clock,PaymentRepository operations,org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,RevenueShareService shares){this(jdbc,tx,json,clock,operations,policies);this.shares=shares;}
 public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,Clock clock,PaymentRepository operations,RevenueShareService shares,com.paymentsledger.dispute.DisputeService disputes){this(jdbc,tx,json,clock,operations,shares);this.disputes=disputes;this.eligibility=(tenant,payment,control)->{if(disputes.blocks(uuid(payment,"id")))throw error(ErrorCode.REFUND_BLOCKED);};}
 @Autowired public RefundRepository(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,Clock clock,PaymentRepository operations,org.springframework.beans.factory.ObjectProvider<RefundEligibilityPolicy> policies,RevenueShareService shares,com.paymentsledger.dispute.DisputeService disputes){this(jdbc,tx,json,clock,operations,policies,shares);this.disputes=disputes;}
 public record Intent(UUID refundId,UUID operationId,boolean replay){}
 private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
 private String scopeKey(TenantContext c){return c.organizationId()+":"+c.environmentId()+":"+c.applicationId();}
 private void lockKey(String key){jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",key);}
 public Intent prepare(TenantContext c,String key,RefundRequests.Create request,String provider){
  TenantAccessGuard.requireScope(c,"refunds:write");PaymentRequests.idempotencyKey(key);
  return tx.execute(status->{
   lockKey(scopeKey(c)+":CREATE_REFUND:"+key);
   var prior=one("SELECT * FROM platform.api_idempotency_record WHERE organization_id=? AND environment_id=? AND application_id=? AND request_type='CREATE_REFUND' AND idempotency_key=?",c.organizationId(),c.environmentId(),c.applicationId(),key);
   if(prior!=null)return replay(prior,request.fingerprint());
   TenantAccessGuard.requireCurrency(c,request.currency());operations.checkBinding(c,provider);
   if(request.clientRefundKey()!=null){
    lockKey(scopeKey(c)+":CLIENT_REFUND:"+request.clientRefundKey());
    var existing=one("SELECT r.id refund_id,r.request_fingerprint,o.id operation_id FROM platform.refund r JOIN platform.provider_operation o ON o.refund_id=r.id WHERE r.organization_id=? AND r.environment_id=? AND r.application_id=? AND r.client_refund_key=?",c.organizationId(),c.environmentId(),c.applicationId(),request.clientRefundKey());
    if(existing!=null){var replay=replay(existing,request.fingerprint());insertApi(c,request,key,replay.refundId(),replay.operationId());return replay;}
   }
   var payment=one("SELECT * FROM platform.payment WHERE id=? AND organization_id=? AND environment_id=? AND application_id=? AND provider_account_id=? FOR UPDATE",request.paymentId(),c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId());
   if(payment==null || !c.allowedCurrencies().contains(payment.get("currency")))throw error(ErrorCode.NOT_FOUND);
   if(!request.currency().equals(payment.get("currency")))throw error(ErrorCode.CURRENCY_NOT_ENABLED);
   if("CONFLICT".equals(payment.get("resolution_status")))throw error(ErrorCode.EVIDENCE_CONFLICT);
   if(number(payment,"captured_amount_minor")<=0 || payment.get("latest_charge_id")==null)throw error(ErrorCode.REFUND_BLOCKED);
   var control=control(payment);eligibility.check(c,Collections.unmodifiableMap(payment),Collections.unmodifiableMap(control));
   if(!"NORMAL".equals(control.get("control_state")))throw error("EXTERNAL_CONFLICT".equals(control.get("control_state"))?ErrorCode.EVIDENCE_CONFLICT:ErrorCode.REFUND_BLOCKED);
   if(request.amountMinor()>number(control,"captured_minor")-number(control,"refunded_minor")-number(control,"reserved_minor"))throw error(ErrorCode.REFUND_LIMIT_EXCEEDED);
   UUID refund=PaymentIds.newId(),operation=UUID.randomUUID();
   jdbc.update("UPDATE platform.refund_control SET reserved_minor=reserved_minor+?,version=version+1,updated_at=now() WHERE payment_id=?",request.amountMinor(),request.paymentId());
   jdbc.update("INSERT INTO platform.refund(id,"+SCOPE+",payment_id,amount_minor,client_refund_key,request_fingerprint,source,reason,note,provider_charge_id) VALUES (?,?,?,?,?,?,?,?,?,?,'LOCAL',?,?,?)",refund,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),request.paymentId(),request.amountMinor(),request.clientRefundKey(),request.fingerprint(),request.reason(),request.note(),payment.get("latest_charge_id"));
   var command=new CreateRefundCommand(new ProviderScope(c.organizationId(),c.environmentId(),c.allowedProviderAccountId(),false),"refund_create_"+operation,str(payment,"latest_charge_id"),null,request.amountMinor(),providerReason(request.reason()));
   jdbc.update("INSERT INTO platform.provider_operation(id,"+SCOPE+",operation_target,refund_id,operation_type,operation_key,request_fingerprint,command,expires_at) VALUES (?,?,?,?,?,?,'REFUND',?,'CREATE_REFUND',?,?,?::jsonb,?)",operation,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),refund,command.operationIdempotencyKey(),request.fingerprint(),operations.encode(command),Timestamp.from(operations.now().plus(Duration.ofDays(1))));
   jdbc.update("INSERT INTO platform.recovery_task("+SCOPE+",operation_id,refund_id,available_at) VALUES (?,?,?,?,?,?,?,?)",c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),operation,refund,Timestamp.from(operations.now()));
   insertApi(c,request,key,refund,operation);shares.reconcile(request.paymentId(),"refund.reserve:"+refund);return new Intent(refund,operation,false);
  });
 }
 /** Conservative audited unfreeze. Unresolved reservations or ambiguous source associations require investigation. */
 public void resolveExternalConflict(ProviderScope scope,UUID paymentId,long expectedVersion,UUID observationId,String auditKey){
  if(scope.livemode() || auditKey==null || auditKey.isBlank() || auditKey.length()>255)throw error(ErrorCode.VALIDATION_ERROR);
  tx.execute(status->{
   var payment=one("SELECT * FROM platform.payment WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=? FOR UPDATE",paymentId,scope.organizationId(),scope.environmentId(),scope.providerAccountId());
   if(payment==null)throw error(ErrorCode.NOT_FOUND);var control=control(payment);
   var prior=one("SELECT observation_id FROM platform.refund_control_audit WHERE payment_id=? AND audit_key=?",paymentId,auditKey);
   if(prior!=null){if(!observationId.equals(prior.get("observation_id")))throw error(ErrorCode.IDEMPOTENCY_CONFLICT);return null;}
   var observation=one("SELECT id FROM platform.refund_source_observation WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=? AND payment_id=?",observationId,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),paymentId);
   long unsettled=jdbc.queryForObject("SELECT count(*) FROM platform.refund WHERE payment_id=? AND (resolution_status<>'KNOWN' OR refund_status NOT IN ('SUCCEEDED','FAILED','CANCELED'))",Long.class,paymentId);
   long succeeded=jdbc.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM platform.refund WHERE payment_id=? AND refund_status='SUCCEEDED'",Long.class,paymentId);
   // Historical facts may be associated after their charge arrives. Only compatible evidence is resolved:
   // nonterminal observations may advance, but terminal facts and supplied BT identities cannot change.
   long unmatched=jdbc.queryForObject("""
       SELECT count(*) FROM platform.refund_source_observation o
       WHERE o.organization_id=? AND o.environment_id=? AND o.provider_account_id=? AND o.refund_id IS NULL
         AND o.fact->>'chargeId' IN (SELECT provider_object_id FROM platform.payment_attempt WHERE payment_id=?)
         AND NOT EXISTS (
           SELECT 1 FROM platform.refund r
           WHERE r.organization_id=o.organization_id AND r.environment_id=o.environment_id
             AND r.provider_account_id=o.provider_account_id AND r.provider_refund_id=o.provider_refund_id
             AND r.provider_charge_id=o.fact->>'chargeId' AND r.amount_minor::text=o.fact->>'amountMinor'
             AND r.currency=upper(o.fact->>'currency') AND r.resolution_status='KNOWN'
             AND (upper(o.fact->>'status') IN ('PENDING','REQUIRES_ACTION') OR upper(o.fact->>'status')=r.refund_status)
             AND (o.fact->>'balanceTransactionId' IS NULL OR o.fact->>'balanceTransactionId'=r.balance_transaction_id)
             AND (o.fact->>'failureBalanceTransactionId' IS NULL OR o.fact->>'failureBalanceTransactionId'=r.failure_balance_transaction_id)
         )
       """,Long.class,scope.organizationId(),scope.environmentId(),scope.providerAccountId(),paymentId);
   if(observation==null || number(control,"version")!=expectedVersion || !"EXTERNAL_CONFLICT".equals(control.get("control_state")) || unsettled!=0 || unmatched!=0 || number(control,"reserved_minor")!=0 || succeeded!=number(control,"refunded_minor") || succeeded>number(control,"captured_minor"))throw error(ErrorCode.EVIDENCE_CONFLICT);
   var before=Map.of("controlState","EXTERNAL_CONFLICT","capturedMinor",control.get("captured_minor"),"refundedMinor",succeeded,"reservedMinor",0,"version",expectedVersion);
   var after=Map.of("controlState","NORMAL","capturedMinor",control.get("captured_minor"),"refundedMinor",succeeded,"reservedMinor",0,"version",expectedVersion+1);
   jdbc.update("INSERT INTO platform.refund_control_audit(organization_id,environment_id,provider_account_id,payment_id,observation_id,audit_key,before_state,after_state) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),paymentId,observationId,auditKey,operations.encode(before),operations.encode(after));
   jdbc.update("UPDATE platform.refund_control SET control_state='NORMAL',version=version+1,updated_at=now() WHERE payment_id=?",paymentId);shares.reconcile(paymentId,"refund.unfreeze:"+auditKey);return null;
  });
 }
 private String providerReason(String r){return switch(r){case "DUPLICATE"->"duplicate";case "FRAUDULENT"->"fraudulent";case "REQUESTED_BY_CUSTOMER"->"requested_by_customer";default->null;};}
 private Intent replay(Map<String,Object> row,String fingerprint){if(!fingerprint.equals(row.get("request_fingerprint")))throw error(ErrorCode.IDEMPOTENCY_CONFLICT);return new Intent(uuid(row,"refund_id"),uuid(row,"operation_id"),true);}
 private void insertApi(TenantContext c,RefundRequests.Create request,String key,UUID refund,UUID operation){jdbc.update("INSERT INTO platform.api_idempotency_record("+SCOPE+",request_type,idempotency_key,request_fingerprint,refund_id,operation_id) VALUES (?,?,?,?,?,'CREATE_REFUND',?,?,?,?)",c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),key,request.fingerprint(),refund,operation);}
 private Map<String,Object> control(Map<String,Object> payment){
  jdbc.update("INSERT INTO platform.refund_control("+SCOPE+",payment_id,captured_minor) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",payment.get("organization_id"),payment.get("environment_id"),payment.get("application_id"),payment.get("provider_account_id"),payment.get("currency"),payment.get("id"),payment.get("captured_amount_minor"));
  jdbc.update("UPDATE platform.refund_control SET captured_minor=greatest(captured_minor,?),updated_at=now() WHERE payment_id=?",payment.get("captured_amount_minor"),payment.get("id"));
  return one("SELECT * FROM platform.refund_control WHERE payment_id=? FOR UPDATE",payment.get("id"));
 }
 public Map<String,Object> lookup(ProviderScope scope,String providerRefundId){if(scope.livemode())throw error(ErrorCode.FORBIDDEN);return one("SELECT * FROM platform.refund WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_refund_id=?",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),providerRefundId);}
 public UUID paymentForCharge(ProviderScope scope,String chargeId){
  if(scope.livemode())throw error(ErrorCode.FORBIDDEN);
  var row=one("SELECT payment_id FROM platform.payment_attempt WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_object_id=? AND captured",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),chargeId);
  return row==null?null:uuid(row,"payment_id");
 }
 /** Caller authenticates provider scope; null local id never guesses an ambiguous reservation by amount. */
 public UUID accept(ProviderScope scope,RefundFact fact,ProviderEvidence evidence,UUID localRefundId){return tx.execute(status->acceptLocked(scope,fact,evidence,localRefundId));}
 private UUID acceptLocked(ProviderScope scope,RefundFact fact,ProviderEvidence evidence,UUID localRefundId){
  UUID accepted=mergeFact(scope,fact,evidence,localRefundId);
  UUID parent=accepted==null?paymentForCharge(scope,fact.chargeId()):uuid(refund(accepted),"payment_id");
  if(parent!=null){if(disputes!=null)disputes.observeRefundOverlap(parent);shares.reconcile(parent,"refund.fact:"+fact.refundId());}
  return accepted;
 }
 private UUID mergeFact(ProviderScope scope,RefundFact fact,ProviderEvidence evidence,UUID localRefundId){
  if(scope.livemode())throw error(ErrorCode.FORBIDDEN);
  UUID paymentId=paymentForCharge(scope,fact.chargeId());
  var bound=lookup(scope,fact.refundId());
  if(bound!=null && localRefundId==null)localRefundId=uuid(bound,"id");
  var local=localRefundId==null?null:one("SELECT * FROM platform.refund WHERE id=? AND organization_id=? AND environment_id=? AND provider_account_id=?",localRefundId,scope.organizationId(),scope.environmentId(),scope.providerAccountId());
  if(local!=null)paymentId=uuid(local,"payment_id");
  if(paymentId==null){observe(scope,fact,evidence,null,null);return null;}
  var payment=one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE",paymentId);control(payment);
  if(shares.enabled()){
   shares.lock(paymentId);
   com.paymentsledger.financialingestion.FinancialSourceLocks.lock(jdbc,paymentId,List.of(fact.refundId()));
  }
  bound=lookup(scope,fact.refundId());
  if(bound!=null && local==null)local=bound;
  if(bound!=null && local!=null && !bound.get("id").equals(local.get("id"))){
   observe(scope,fact,evidence,uuid(local,"id"),paymentId);
   jdbc.update("UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT',version=version+1 WHERE payment_id=?",paymentId);
   jdbc.update("UPDATE platform.refund SET resolution_status='CONFLICT',unresolved_since=coalesce(unresolved_since,?),version=version+1 WHERE id=?",Timestamp.from(operations.now()),local.get("id"));return uuid(local,"id");
  }
  if(local!=null)local=one("SELECT * FROM platform.refund WHERE id=? FOR UPDATE",local.get("id"));
  boolean external=local==null;
  if(external){
   // Preserve genuine external facts even when R + H exceeds C. Freeze before changing amounts.
   jdbc.update("UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now() WHERE payment_id=?",paymentId);
   if(!fact.currency().equalsIgnoreCase(str(payment,"currency"))){observe(scope,fact,evidence,null,null);return null;}
   UUID id=PaymentIds.newId();
   jdbc.update("INSERT INTO platform.refund(id,"+SCOPE+",payment_id,amount_minor,request_fingerprint,source,provider_charge_id,provider_refund_id,refund_status) VALUES (?,?,?,?,?,?,?,?,?,'EXTERNAL',?,?,'PENDING')",id,payment.get("organization_id"),payment.get("environment_id"),payment.get("application_id"),payment.get("provider_account_id"),payment.get("currency"),paymentId,fact.amountMinor(),"external:"+fact.refundId(),fact.chargeId(),fact.refundId());
   local=one("SELECT * FROM platform.refund WHERE id=?",id);
   prepareExternalQuery(local,scope);
  }
  UUID refundId=uuid(local,"id");observe(scope,fact,evidence,refundId,paymentId);
  boolean conflict=fact.amountMinor()!=number(local,"amount_minor") || !fact.currency().equalsIgnoreCase(str(local,"currency")) || !Objects.equals(fact.chargeId(),local.get("provider_charge_id"))
   || local.get("provider_refund_id")!=null && !fact.refundId().equals(local.get("provider_refund_id"))
   || local.get("balance_transaction_id")!=null && fact.balanceTransactionId()!=null && !fact.balanceTransactionId().equals(local.get("balance_transaction_id"))
   || local.get("failure_balance_transaction_id")!=null && fact.failureBalanceTransactionId()!=null && !fact.failureBalanceTransactionId().equals(local.get("failure_balance_transaction_id"));
  String before=str(local,"refund_status"),state=switch(Objects.toString(fact.status(),"")){case "succeeded"->"SUCCEEDED";case "failed"->"FAILED";case "canceled"->"CANCELED";case "pending"->"PENDING";case "requires_action"->"REQUIRES_ACTION";default->before;};
  boolean recognized=Set.of("succeeded","failed","canceled","pending","requires_action").contains(Objects.toString(fact.status(),""));
  if(terminal(before) && terminal(state) && !before.equals(state))conflict=true;
  if(conflict || "CONFLICT".equals(local.get("resolution_status"))){
   jdbc.update("UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT',version=version+1 WHERE payment_id=?",paymentId);
   jdbc.update("UPDATE platform.refund SET resolution_status='CONFLICT',unresolved_since=coalesce(unresolved_since,?),version=version+1,updated_at=now() WHERE id=?",Timestamp.from(operations.now()),refundId);return refundId;
  }
  if(terminal(before)){state=before;recognized=true;}
  if(!terminal(before) && terminal(state)){
   long held="LOCAL".equals(local.get("source"))?number(local,"amount_minor"):0;
   jdbc.update("UPDATE platform.refund_control SET reserved_minor=reserved_minor-?,refunded_minor=refunded_minor+?,version=version+1,updated_at=now() WHERE payment_id=?",held,"SUCCEEDED".equals(state)?number(local,"amount_minor"):0,paymentId);
  }
  // Older pending evidence can add BT references but must not regress a terminal state or provider status.
  String providerStatus=terminal(before)?str(local,"provider_status"):fact.status();
  jdbc.update("UPDATE platform.refund SET refund_status=?,resolution_status=?,provider_status=?,provider_refund_id=coalesce(provider_refund_id,?),balance_transaction_id=coalesce(balance_transaction_id,?),failure_balance_transaction_id=coalesce(failure_balance_transaction_id,?),failure_reason=coalesce(failure_reason,?),last_provider_confirmed_at=?,unresolved_since=CASE WHEN ? THEN NULL ELSE coalesce(unresolved_since,?) END,version=version+1,updated_at=now() WHERE id=?",state,recognized?"KNOWN":"UNKNOWN",providerStatus,fact.refundId(),fact.balanceTransactionId(),fact.failureBalanceTransactionId(),fact.failureReason(),Timestamp.from(operations.now()),recognized,Timestamp.from(operations.now()),refundId);
  // A webhook/query may converge business facts, but cannot steal an active sender's operation lease.
  jdbc.update("UPDATE platform.recovery_task SET status='WAITING',available_at=least(available_at,?),version=version+1 WHERE refund_id=? AND status<>'RUNNING' AND status<>'ESCALATED'",Timestamp.from(operations.now()),refundId);
  return refundId;
 }
 private void prepareExternalQuery(Map<String,Object> refund,ProviderScope scope){
  UUID operation=UUID.randomUUID();String key="refund_query_"+operation;
  var command=Map.of("scope",scope,"refundId",refund.get("provider_refund_id"));
  jdbc.update("INSERT INTO platform.provider_operation(id,"+SCOPE+",operation_target,refund_id,operation_type,operation_key,request_fingerprint,command,expires_at,external_object_id) VALUES (?,?,?,?,?,?,'REFUND',?,'QUERY_REFUND',?,?,?::jsonb,?,?)",operation,refund.get("organization_id"),refund.get("environment_id"),refund.get("application_id"),refund.get("provider_account_id"),refund.get("currency"),refund.get("id"),key,"external-query:"+refund.get("provider_refund_id"),operations.encode(command),Timestamp.from(operations.now().plus(Duration.ofDays(1))),refund.get("provider_refund_id"));
  jdbc.update("INSERT INTO platform.recovery_task("+SCOPE+",operation_id,refund_id,available_at) VALUES (?,?,?,?,?,?,?,?)",refund.get("organization_id"),refund.get("environment_id"),refund.get("application_id"),refund.get("provider_account_id"),refund.get("currency"),operation,refund.get("id"),Timestamp.from(operations.now()));
 }
 private void observe(ProviderScope scope,RefundFact fact,ProviderEvidence evidence,UUID refund,UUID payment){jdbc.update("INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,provider_refund_id,refund_id,payment_id,fact,evidence) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),fact.refundId(),refund,payment,operations.encode(fact),operations.encode(evidence));}
 private long feeRefund(List<Map<String,Object>> rows){long total=0;for(var b:rows)if("POSTED".equals(b.get("state")) && number(b,"fee_minor")<0)total=Math.addExact(total,Math.negateExact(number(b,"fee_minor")));return total;}
 public void recordSourceQuery(ProviderScope scope,String providerRefundId,ProviderCallResult<RefundFact> result){
  if(result.payload()!=null){accept(scope,result.payload(),result.evidence(),null);return;}
  tx.execute(status->{var fact=new LinkedHashMap<String,Object>();fact.put("refundId",providerRefundId);fact.put("classification",result.classification().name());
   jdbc.update("INSERT INTO platform.refund_source_observation(organization_id,environment_id,provider_account_id,provider_refund_id,fact,evidence) VALUES (?,?,?,?,?::jsonb,?::jsonb)",scope.organizationId(),scope.environmentId(),scope.providerAccountId(),providerRefundId,operations.encode(fact),operations.encode(result.evidence()));return null;});
 }
 static boolean terminal(String state){return Set.of("SUCCEEDED","FAILED","CANCELED").contains(state);}
 void lock(UUID refund){var r=one("SELECT payment_id FROM platform.refund WHERE id=?",refund);if(r==null)throw error(ErrorCode.NOT_FOUND);one("SELECT id FROM platform.payment WHERE id=? FOR UPDATE",r.get("payment_id"));one("SELECT payment_id FROM platform.refund_control WHERE payment_id=? FOR UPDATE",r.get("payment_id"));one("SELECT id FROM platform.refund WHERE id=? FOR UPDATE",refund);}
 Map<String,Object> refund(UUID id){return one("SELECT * FROM platform.refund WHERE id=?",id);}
 /** Freeze contradictory remote identities only at the common owned-lease linearization point. */
 void conflictingAnchors(Claim claim){operations.transaction(()->{
  UUID id=uuid(claim.operation(),"refund_id");lock(id);
  if(!operations.owns(claim))return null;
  jdbc.update("UPDATE platform.refund_control SET control_state='EXTERNAL_CONFLICT',version=version+1,updated_at=now() WHERE payment_id=?",claim.paymentId());
  jdbc.update("UPDATE platform.refund SET resolution_status='CONFLICT',unresolved_since=coalesce(unresolved_since,?),version=version+1,updated_at=now() WHERE id=?",Timestamp.from(operations.now()),id);
  // Ownership was checked under the retained operation lock. Do not recheck wall-clock expiry after changing business state.
  shares.reconcile(claim.paymentId(),"refund.anchor-conflict");
  operations.escalateOwned(claim,"CONFLICTING_REFUND_ANCHORS");return null;
 });}
 void finish(Claim claim,ProviderCallResult<RefundFact> result,boolean query){operations.transaction(()->{
  UUID id=uuid(claim.operation(),"refund_id");lock(id);
  operations.observations(claim.operationId(),claim.paymentId(),claim.token(),List.of(new Observation(query?"QUERY_REFUND":"CREATE_REFUND",result,result.payload()==null?null:result.payload().refundId())));
  if(!operations.owns(claim))return null;
  if(query)jdbc.update("UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",Timestamp.from(operations.now()),claim.operationId());
  var current=refund(id);
  if(result.payload()!=null && (result.classification()==ProviderCallClassification.SUCCESS || result.classification()==ProviderCallClassification.AUTH_REQUIRED)){
   acceptLocked(scope(current),result.payload(),result.evidence(),id);
  }else if(!query && result.classification()==ProviderCallClassification.DEFINITIVE_REJECT && current.get("provider_refund_id")==null && !terminal(str(current,"refund_status"))){
   jdbc.update("UPDATE platform.refund_control SET reserved_minor=reserved_minor-?,version=version+1 WHERE payment_id=?",current.get("amount_minor"),current.get("payment_id"));
   jdbc.update("UPDATE platform.refund SET refund_status='FAILED',resolution_status='KNOWN',version=version+1,updated_at=now() WHERE id=?",id);
  }else if(!terminal(str(current,"refund_status")))jdbc.update("UPDATE platform.refund SET resolution_status=CASE WHEN resolution_status='CONFLICT' THEN 'CONFLICT' ELSE 'UNKNOWN' END,unresolved_since=coalesce(unresolved_since,?),version=version+1 WHERE id=?",Timestamp.from(operations.now()),id);
  if(result.payload()==null || (result.classification()!=ProviderCallClassification.SUCCESS && result.classification()!=ProviderCallClassification.AUTH_REQUIRED))shares.reconcile(claim.paymentId(),"refund.operation:"+claim.operationId());
  current=refund(id);String resolution=str(current,"resolution_status");
  Instant unresolved=instant(current,"unresolved_since");
  if(!"KNOWN".equals(resolution) && unresolved!=null && !unresolved.plus(operations.recoveryPolicy().unresolvedAge()).isAfter(operations.now())){
   operations.alertAge(claim);operations.escalate(claim,"UNRESOLVED_AGE_LIMIT");return null;
  }
  operations.completeOperation(claim,"KNOWN".equals(resolution)?"RESOLVED":"UNKNOWN",resolution,str(current,"provider_refund_id"),str(current,"provider_status"));return null;
 });}
 boolean submitted(Claim claim){return operations.transaction(()->{
  UUID id=uuid(claim.operation(),"refund_id");lock(id);if(!operations.owns(claim))return false;
  var current=refund(id);var control=one("SELECT control_state FROM platform.refund_control WHERE payment_id=?",current.get("payment_id"));
  if(terminal(str(current,"refund_status")) || current.get("provider_refund_id")!=null || !"NORMAL".equals(control.get("control_state")) || "CONFLICT".equals(current.get("resolution_status"))){
   operations.completeOperation(claim,terminal(str(current,"refund_status"))?"RESOLVED":"UNKNOWN",str(current,"resolution_status"),str(current,"provider_refund_id"),str(current,"provider_status"));return false;
  }
  jdbc.update("UPDATE platform.refund SET refund_status='SUBMITTED',version=version+1,updated_at=now() WHERE id=? AND refund_status='RESERVED'",id);return true;
 });}
 static ProviderScope scope(Map<String,Object> row){return new ProviderScope(uuid(row,"organization_id"),uuid(row,"environment_id"),uuid(row,"provider_account_id"),false);}
 public Map<String,Object> read(TenantContext c,UUID id){TenantAccessGuard.requireScope(c,"refunds:read");return tx.execute(status->{
  var r=one("SELECT * FROM platform.refund WHERE id=? AND organization_id=? AND environment_id=? AND application_id=? AND provider_account_id=?",id,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId());
  if(r==null || !c.allowedCurrencies().contains(r.get("currency")))throw error(ErrorCode.NOT_FOUND);
  var control=one("SELECT * FROM platform.refund_control WHERE payment_id=?",r.get("payment_id"));
  var result=new LinkedHashMap<String,Object>();result.put("id",PaymentIds.format("ref",id));result.put("paymentId",PaymentIds.format("pay",uuid(r,"payment_id")));
  result.put("amountMinor",r.get("amount_minor"));result.put("currency",r.get("currency"));result.put("refundStatus",r.get("refund_status"));result.put("resolutionStatus",r.get("resolution_status"));result.put("providerStatus",r.get("provider_status"));result.put("providerRefundId",r.get("provider_refund_id"));result.put("reason",r.get("reason"));result.put("controlState",control.get("control_state"));
  result.put("reservation",Map.of("capturedMinor",control.get("captured_minor"),"refundedMinor",control.get("refunded_minor"),"reservedMinor",control.get("reserved_minor"),"availableMinor",Math.max(0,number(control,"captured_minor")-number(control,"refunded_minor")-number(control,"reserved_minor"))));
  var bts=jdbc.queryForList("SELECT * FROM platform.balance_transaction WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND (refund_id=? OR bt_id IN (?,?))",r.get("organization_id"),r.get("environment_id"),r.get("provider_account_id"),id,r.get("balance_transaction_id"),r.get("failure_balance_transaction_id"));
  long posted=bts.stream().filter(b->"POSTED".equals(b.get("state"))).count();boolean conflict=bts.stream().anyMatch(b->"QUARANTINED".equals(b.get("state")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.bt_evidence_conflict WHERE balance_transaction_id=?)",Boolean.class,b.get("id"))));
  boolean referencedMissing=(r.get("balance_transaction_id")!=null && bts.stream().noneMatch(b->r.get("balance_transaction_id").equals(b.get("bt_id")) && "POSTED".equals(b.get("state")))) || (r.get("failure_balance_transaction_id")!=null && bts.stream().noneMatch(b->r.get("failure_balance_transaction_id").equals(b.get("bt_id")) && "POSTED".equals(b.get("state"))));
  String financial=conflict || "CONFLICT".equals(r.get("resolution_status"))?"CONFLICT":posted>0?(referencedMissing || posted<bts.size()?"PARTIALLY_POSTED":"POSTED"):(terminal(str(r,"refund_status")) && !"SUCCEEDED".equals(r.get("refund_status")) || "RESERVED".equals(r.get("refund_status"))) && bts.isEmpty() && r.get("balance_transaction_id")==null && r.get("failure_balance_transaction_id")==null?"NOT_APPLICABLE":"PENDING_EVIDENCE";
  result.put("financialStatus",financial);var evidence=new LinkedHashMap<String,Object>();evidence.put("refundBalanceTransactionId",r.get("balance_transaction_id"));evidence.put("failureBalanceTransactionId",r.get("failure_balance_transaction_id"));evidence.put("actualFeeRefundMinor",feeRefund(bts));evidence.put("postedJournalIds",bts.stream().filter(b->b.get("journal_id")!=null).map(b->b.get("journal_id").toString()).toList());result.put("evidence",evidence);
  for(String field:List.of("created_at","updated_at","last_provider_confirmed_at")){String camel=switch(field){case "created_at"->"createdAt";case "updated_at"->"updatedAt";default->"lastProviderConfirmedAt";};result.put(camel,instant(r,field)==null?null:instant(r,field).toString());}return result;
 });}
}
