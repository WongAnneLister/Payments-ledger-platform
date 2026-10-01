package com.paymentsledger.webhook;
import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.payout.PayoutService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.PayoutFact;
import java.sql.Timestamp;import java.time.*;import java.util.*;
import org.springframework.stereotype.Component;
/** Provider state and verified webhook acknowledgement commit atomically; paid never confirms bank cash. */
@Component public final class PayoutWebhookHandler {
 private final WebhookInboxRepository inbox;private final PayoutService payouts;
 public PayoutWebhookHandler(WebhookInboxRepository inbox,PayoutService payouts){this.inbox=inbox;this.payouts=payouts;}
 public void process(WebhookInboxRepository.Claim claim,JsonNode object){var row=claim.row();var scope=new ProviderScope((UUID)row.get("organization_id"),(UUID)row.get("environment_id"),(UUID)row.get("provider_account_id"),false);String id=text(object,"id");
  inbox.transaction(()->{
   if(id!=null)payouts.lock(scope,id);
   if(!inbox.owns(claim))return null;
   if(!Set.of("payout.created","payout.updated","payout.paid","payout.failed","payout.canceled","payout.reconciliation_completed").contains(row.get("event_type").toString())){inbox.waitFor(claim,"EVIDENCE",id,"UNSUPPORTED_PAYOUT_EVENT");return null;}
   var amount=object.path("amount");String currency=text(object,"currency"),status=text(object,"status");
   if(id==null||currency==null||!currency.matches("[A-Za-z]{3}")||status==null||!amount.isIntegralNumber()||!amount.canConvertToLong()||amount.longValue()<=0||!object.path("automatic").isBoolean()||!object.path("arrival_date").isIntegralNumber()||!object.path("arrival_date").canConvertToLong()){inbox.waitFor(claim,"EVIDENCE",id,"INCOMPLETE_PAYOUT_FACT");return null;}
   Instant arrival;try{arrival=Instant.ofEpochSecond(object.path("arrival_date").longValue());}catch(DateTimeException error){inbox.waitFor(claim,"EVIDENCE",id,"INVALID_PAYOUT_ARRIVAL");return null;}
   Instant confirmed=((Timestamp)row.get("event_created_at")).toInstant();
   payouts.accept(scope,new PayoutFact(id,status,amount.longValue(),currency,text(object,"balance_transaction"),text(object,"failure_balance_transaction"),object.path("automatic").booleanValue(),arrival,text(object,"failure_code")),new ProviderEvidence(null,null,confirmed,confirmed,200,null,(String)row.get("raw_digest"),(String)row.get("api_version")));
   inbox.done(claim);return null;
  });
 }
 private static String text(JsonNode node,String field){return node.path(field).isTextual()?node.path(field).textValue():null;}
}
