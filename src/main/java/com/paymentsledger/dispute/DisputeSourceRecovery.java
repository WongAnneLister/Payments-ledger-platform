package com.paymentsledger.dispute;

import com.paymentsledger.financialingestion.DisputeSourceResolver;
import com.paymentsledger.provider.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Query-only recovery; no evidence submission and no external funds operation. */
@Component
public final class DisputeSourceRecovery implements DisputeSourceResolver {
 private final DisputePort provider;private final DisputeService disputes;
 @org.springframework.beans.factory.annotation.Autowired
 public DisputeSourceRecovery(ObjectProvider<DisputePort> providers,DisputeService disputes){this(providers.getIfAvailable(),disputes);}
 public DisputeSourceRecovery(DisputePort provider,DisputeService disputes){this.provider=provider;this.disputes=disputes;}
 @Override public void resolve(ProviderScope scope,String id){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dispute queries require an external transaction boundary");
  if(provider==null)return;
  var result=provider.getDispute(scope,id);
  if(result.classification()==ProviderCallClassification.SUCCESS && result.payload()!=null && id.equals(result.payload().disputeId()))disputes.accept(scope,result.payload(),result.evidence());
 }
}
