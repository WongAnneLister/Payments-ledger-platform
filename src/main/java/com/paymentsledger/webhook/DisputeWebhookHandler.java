package com.paymentsledger.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.dispute.DisputeService;
import com.paymentsledger.payment.PaymentRepository;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.DisputeFact;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Verified snapshots, business blocking and Inbox acknowledgement share one local transaction. */
@Component
public final class DisputeWebhookHandler {
    private final WebhookInboxRepository inbox;
    private final PaymentRepository payments;
    private final DisputeService disputes;
    public DisputeWebhookHandler(WebhookInboxRepository inbox,PaymentRepository payments,DisputeService disputes){this.inbox=inbox;this.payments=payments;this.disputes=disputes;}
    public void process(WebhookInboxRepository.Claim claim,JsonNode object){
        var row=claim.row();
        var scope=new ProviderScope((UUID)row.get("organization_id"),(UUID)row.get("environment_id"),(UUID)row.get("provider_account_id"),false);
        String id=text(object,"id"),charge=text(object,"charge");
        var existing=id==null?null:disputes.lookup(scope,id);
        UUID payment=existing==null?disputes.paymentForCharge(scope,charge):(UUID)existing.get("payment_id");
        inbox.transaction(()->{
            if(payment!=null)payments.lockWebhookPayment(payment);
            if(!inbox.owns(claim))return null;
            if(!Set.of("charge.dispute.created","charge.dispute.updated","charge.dispute.closed","charge.dispute.funds_withdrawn","charge.dispute.funds_reinstated").contains(row.get("event_type").toString())){
                inbox.waitFor(claim,"EVIDENCE",id,"UNSUPPORTED_DISPUTE_EVENT");return null;
            }
            var amount=object.path("amount");String currency=text(object,"currency"),status=text(object,"status");
            if(id==null||charge==null||!amount.isIntegralNumber()||!amount.canConvertToLong()||amount.longValue()<=0||currency==null||!currency.matches("[A-Za-z]{3}")||status==null||!object.path("balance_transactions").isArray()){
                inbox.waitFor(claim,"EVIDENCE",id,"INCOMPLETE_DISPUTE_FACT");return null;
            }
            Instant deadline=null;
            try{var due=object.path("evidence_details").path("due_by");if(due.isIntegralNumber())deadline=Instant.ofEpochSecond(due.longValue());}
            catch(DateTimeException invalid){inbox.waitFor(claim,"EVIDENCE",id,"INVALID_DISPUTE_DEADLINE");return null;}
            var ids=new ArrayList<String>();for(var link:object.path("balance_transactions"))ids.add(link.asText());
            Instant confirmed=((Timestamp)row.get("event_created_at")).toInstant();
            var fact=new DisputeFact(id,charge,status,text(object,"reason"),amount.longValue(),currency,deadline,ids);
            var evidence=new ProviderEvidence(null,null,confirmed,confirmed,200,null,(String)row.get("raw_digest"),(String)row.get("api_version"));
            disputes.accept(scope,fact,evidence);
            var accepted=disputes.lookup(scope,id);
            if(accepted==null||accepted.get("payment_id")==null){inbox.waitFor(claim,"CHARGE",charge,"MISSING_DISPUTE_PAYMENT_DEPENDENCY");return null;}
            inbox.done(claim);return null;
        });
    }
    private static String text(JsonNode node,String field){return node.path(field).isTextual()?node.path(field).textValue():null;}
}
