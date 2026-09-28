package com.paymentsledger.payment;

import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import static com.paymentsledger.payment.PaymentRepository.*;

/** Refund adapter for the shared coordinator. Provider calls always follow committed common leases. */
@Component
public class RefundExecutor {
 private final PaymentRepository operations;private final RefundRepository refunds;private final RefundPort port;private final String provider;
 @Autowired public RefundExecutor(PaymentRepository operations,RefundRepository refunds,ObjectProvider<RefundPort> ports,Environment env){this(operations,refunds,ports.getIfAvailable(),env.getProperty("platform.stripe.enabled",Boolean.class,false)?"STRIPE":env.getProperty("platform.provider.fake.enabled",Boolean.class,false)?"FAKE":null);}
 public RefundExecutor(PaymentRepository operations,RefundRepository refunds,RefundPort port,String provider){this.operations=operations;this.refunds=refunds;this.port=port;this.provider=provider;}
 String providerName(){return port==null?null:provider;}
 public void execute(UUID operation){outsideTransaction();if(port==null)return;var claim=operations.claim(operation,provider);if(claim!=null)executeClaim(claim);}
 void executeClaim(Claim claim){outsideTransaction();if("QUERY_REFUND".equals(claim.type())){recover(claim);return;}if(!refunds.submitted(claim))return;var command=operations.decode(claim.operation().get("command"),CreateRefundCommand.class);refunds.finish(claim,call(()->port.createRefund(command),command.operationIdempotencyKey()),false);}
 void recover(Claim claim){
  outsideTransaction();if(port==null)return;
  String anchor=operations.refundRecoveryAnchor(claim);
  if("CONFLICTING_REFUND_ANCHORS".equals(anchor)){refunds.conflictingAnchors(claim);return;}
  if("CONFLICT".equals(claim.payment().get("resolution_status"))){operations.escalate(claim,"EVIDENCE_CONFLICT");return;}
  if(anchor!=null){String id=anchor;refunds.finish(claim,call(()->port.queryRefund(RefundRepository.scope(claim.payment()),id),str(claim.operation(),"operation_key")),true);return;}
  if("QUERY_REFUND".equals(claim.type())){operations.escalate(claim,"QUERY_WITHOUT_TRUSTED_REFUND");return;}
  if("PREPARED".equals(claim.operation().get("status"))){executeClaim(claim);return;}
  Instant horizon=instant(claim.operation(),"retry_until"),first=instant(claim.operation(),"first_send_at");
  Duration verified=operations.recoveryPolicy().verifiedWindow(provider);
  if(horizon!=null && first!=null && operations.now().isBefore(horizon) && !verified.isZero() && operations.now().isBefore(first.plus(verified)))executeClaim(claim);
  else operations.escalate(claim,horizon==null?"IDEMPOTENCY_WINDOW_UNVERIFIED":"IDEMPOTENCY_WINDOW_EXPIRED");
 }
 public void refreshProviderRefund(ProviderScope scope,String providerRefundId){
  outsideTransaction();if(port==null || scope.livemode())return;
  operations.checkProviderScope(scope,provider);
  var result=call(()->port.queryRefund(scope,providerRefundId),null);
  if(result.classification()==ProviderCallClassification.SUCCESS && result.payload()!=null && providerRefundId.equals(result.payload().refundId()))refunds.accept(scope,result.payload(),result.evidence(),null);
  else refunds.recordSourceQuery(scope,providerRefundId,new ProviderCallResult<>(result.classification(),null,result.evidence()));
 }
 private ProviderCallResult<RefundFact> call(Supplier<ProviderCallResult<RefundFact>> action,String key){outsideTransaction();Instant start=operations.now();try{return action.get();}catch(RuntimeException e){return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN,null,new ProviderEvidence(null,key,start,null,null,"port_exception",null,null));}}
}
