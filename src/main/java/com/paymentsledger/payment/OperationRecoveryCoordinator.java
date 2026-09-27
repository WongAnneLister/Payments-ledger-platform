package com.paymentsledger.payment;

import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static com.paymentsledger.payment.PaymentRepository.*;

/** L1 and L2 reuse the original task. Unknown absence is never proof of zero remote effects. */
@Component
public class OperationRecoveryCoordinator {
 private static final Logger LOG=LoggerFactory.getLogger(OperationRecoveryCoordinator.class);
 private final PaymentRepository repository;private final PaymentExecutor executor;private final OperationLeaseService leases;private final RecoveryPolicy policy;
 @Autowired public OperationRecoveryCoordinator(PaymentRepository r,PaymentExecutor e){this(r,e,r.recoveryPolicy());}
 public OperationRecoveryCoordinator(PaymentRepository r,PaymentExecutor e,RecoveryPolicy p){repository=r;executor=e;policy=p;leases=new OperationLeaseService(r);r.recoveryPolicy(p);}
 public void recoverDue(int limit){
  outsideTransaction();
  for(UUID operation:repository.due(limit,executor.providerName())){
   try{var claim=leases.acquire(operation,executor.providerName());if(claim!=null)recover(claim);}
   catch(com.paymentsledger.common.error.ApiException bindingChanged){/* Binding disabled after listing: durable task remains queued. */}
  }
 }
 public void scanL2(int limit){outsideTransaction();repository.wakeL2(limit,executor.providerName());}
 private void recover(Claim claim){
  String anchor=repository.recoveryAnchor(claim);
  if("CONFLICTING_SESSION_ANCHORS".equals(anchor)){repository.conflictingAnchor(claim);escalate(claim,"CONFLICTING_SESSION_ANCHORS");return;}
  Instant first=instant(claim.operation(),"first_send_at");
  Instant unresolvedSince=instant(claim.payment(),"unresolved_since");
  boolean unresolved=!"KNOWN".equals(claim.payment().get("resolution_status"));
  if(unresolved&&unresolvedSince!=null&&!unresolvedSince.plus(policy.unresolvedAge()).isAfter(repository.now())){
   repository.alertAge(claim);LOG.warn("Payment recovery age alert operation={} payment={}",claim.operationId(),claim.paymentId());

  }
  if("CONFLICT".equals(claim.payment().get("resolution_status"))){escalate(claim,"EVIDENCE_CONFLICT");return;}
  if("CANCEL_PAYMENT".equals(claim.type())){
   if(anchor!=null){var p=new LinkedHashMap<>(claim.payment());p.put("checkout_session_id",anchor);executor.cancel(new Claim(claim.operationId(),claim.paymentId(),claim.token(),claim.version(),claim.type(),claim.operation(),p));}
   else if("PREPARED".equals(claim.operation().get("status")))executor.executeClaim(claim);
   else escalate(claim,"CANCEL_WITHOUT_TRUSTED_SESSION");return;
  }
  if(anchor!=null){var p=new LinkedHashMap<>(claim.payment());p.put("checkout_session_id",anchor);repository.finishRecoveryQuery(claim,executor.query(p));return;}
  if(claim.payment().get("payment_intent_id")!=null || number(claim.payment(),"captured_amount_minor")>0){repository.finishRecoveryQuery(claim,executor.query(claim.payment()));return;}
  if("PREPARED".equals(claim.operation().get("status"))){executor.executeClaim(claim);return;}
  if(unresolved&&unresolvedSince!=null&&!unresolvedSince.plus(policy.unresolvedAge()).isAfter(repository.now())){escalate(claim,"UNRESOLVED_AGE_LIMIT");return;}
  Instant horizon=instant(claim.operation(),"retry_until");
  if(horizon!=null&&repository.now().isBefore(horizon)&&first!=null&&repository.now().isBefore(first.plus(policy.verifiedWindow(executor.providerName())))&&!policy.verifiedWindow(executor.providerName()).isZero())executor.executeClaim(claim);
  else escalate(claim,horizon==null?"IDEMPOTENCY_WINDOW_UNVERIFIED":"IDEMPOTENCY_WINDOW_EXPIRED");
 }
 private void escalate(Claim claim,String reason){repository.escalate(claim,reason);LOG.warn("Payment recovery escalated operation={} payment={} reason={}",claim.operationId(),claim.paymentId(),reason);}
}
