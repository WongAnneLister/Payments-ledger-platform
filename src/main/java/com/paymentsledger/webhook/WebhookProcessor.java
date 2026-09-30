package com.paymentsledger.webhook;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.payment.*;
import com.paymentsledger.payment.PaymentRepository.Facts;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Claims commit independently; business effects and acknowledgement commit together, payment lock first. */
@Service
public class WebhookProcessor {
    private final WebhookInboxRepository inbox;private final PaymentRepository payments;private final ObjectMapper json;private final Clock clock;
    private final RefundWebhookHandler refunds;
    private DisputeWebhookHandler disputes;
    public WebhookProcessor(WebhookInboxRepository inbox,PaymentRepository payments,ObjectMapper json,Clock clock){this(inbox,payments,json,clock,null);}
    public WebhookProcessor(WebhookInboxRepository inbox,PaymentRepository payments,ObjectMapper json,Clock clock,RefundWebhookHandler refunds){this.inbox=inbox;this.payments=payments;this.json=json;this.clock=clock;this.refunds=refunds;}
    @org.springframework.beans.factory.annotation.Autowired
    public WebhookProcessor(WebhookInboxRepository inbox,PaymentRepository payments,ObjectMapper json,Clock clock,RefundWebhookHandler refunds,DisputeWebhookHandler disputes){this(inbox,payments,json,clock,refunds);this.disputes=disputes;}
    public int processDue(int limit){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Webhook processing must start outside transactions");
        var claims=inbox.claimDue(limit);for(var claim:claims){try{process(claim);}catch(RuntimeException failure){inbox.retry(claim);}}return claims.size();
    }
    public void process(WebhookInboxRepository.Claim claim){
        JsonNode object;try{object=json.readTree(claim.row().get("object_snapshot").toString());}catch(Exception e){throw new IllegalStateException("Invalid controlled webhook snapshot",e);}
        String type=text(object,"object"),objectId=text(object,"id"),pi=type.equals("payment_intent")?objectId:text(object,"payment_intent");
        if("dispute".equals(type)&&disputes!=null){disputes.process(claim,object);return;}
        if("refund".equals(type)&&refunds!=null){refunds.process(claim,object);return;}
        UUID candidate=null;String candidateError=null;
        if(Set.of("checkout.session","payment_intent","charge").contains(type)){
            var ids=inbox.jdbc().queryForList("SELECT id FROM platform.payment WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND ((CAST(? AS text) IS NOT NULL AND payment_intent_id=?) OR (?='checkout.session' AND checkout_session_id=?))",UUID.class,claim.row().get("organization_id"),claim.row().get("environment_id"),claim.row().get("provider_account_id"),pi,pi,type,objectId);
            String metadata=text(object.path("metadata"),"payment_id");UUID hinted=null;try{if(metadata!=null)hinted=metadata.startsWith("pay_")?PaymentIds.payment(metadata):UUID.fromString(metadata);}catch(RuntimeException ignored){candidateError="INVALID_PAYMENT_REFERENCE";}
            if(ids.size()>1){candidate=hinted;candidateError="AMBIGUOUS_RELATIONSHIP";}
            else if(ids.size()==1){candidate=ids.getFirst();if(hinted!=null&&!hinted.equals(candidate)){candidate=hinted;candidateError="RELATIONSHIP_CONFLICT";}}
            else if(!type.equals("charge"))candidate=hinted;
        }
        UUID payment=candidate;String initialError=candidateError;
        try { inbox.transaction(()->{
            Map<String,Object> p=payment==null?null:payments.lockWebhookPayment(payment);
            if(!inbox.owns(claim))return null;
            if(!supported(type,claim.row().get("event_type").toString())){inbox.waitFor(claim,"FUTURE_RESOURCE",objectId,"FUTURE_RESOURCE_HANDLER");return null;}
            if(p==null && initialError!=null){inbox.waitFor(claim,"EVIDENCE",objectId,initialError);return null;}
            if(p==null){inbox.waitFor(claim,type.equals("charge")?"PAYMENT_INTENT":"PAYMENT",pi==null?objectId:pi,"MISSING_PAYMENT_DEPENDENCY");return null;}
            boolean scoped=Objects.equals(p.get("organization_id"),claim.row().get("organization_id"))&&Objects.equals(p.get("environment_id"),claim.row().get("environment_id"))&&Objects.equals(p.get("provider_account_id"),claim.row().get("provider_account_id"));
            if(!scoped){inbox.waitFor(claim,"RELATIONSHIP",objectId,"SCOPE_CONFLICT");return null;}
            String error=initialError;
            long amount=object.path(type.equals("checkout.session")?"amount_total":"amount").asLong(-1);
            String currency=text(object,"currency");
            if(amount!=((Number)p.get("amount_minor")).longValue()||currency==null||!currency.equalsIgnoreCase(p.get("currency").toString()))error="AMOUNT_CURRENCY_CONFLICT";
            if(pi!=null&&p.get("payment_intent_id")!=null&&!pi.equals(p.get("payment_intent_id")))error="RELATIONSHIP_CONFLICT";
            if(type.equals("checkout.session")&&p.get("checkout_session_id")!=null&&!objectId.equals(p.get("checkout_session_id")))error="RELATIONSHIP_CONFLICT";
            if(type.equals("charge")&&(pi==null||!pi.equals(p.get("payment_intent_id")))){inbox.waitFor(claim,"PAYMENT_INTENT",pi,"MISSING_INTENT_DEPENDENCY");return null;}
            Instant eventTime=((Timestamp)claim.row().get("event_created_at")).toInstant();
            if(error!=null){payments.mergeWebhook(payment,new Facts(null,null,List.of(),false,true,eventTime));inbox.waitFor(claim,"EVIDENCE",objectId,error);return null;}
            CheckoutSessionFact session=null;PaymentIntentFact intent=null;List<ChargeFact> charges=List.of();
            String status=text(object,"status");
            if(type.equals("payment_intent") && (status==null || !Set.of("requires_payment_method","requires_confirmation","requires_action","processing","requires_capture","canceled","succeeded").contains(status))){inbox.waitFor(claim,"EVIDENCE",objectId,"UNKNOWN_INTENT_STATUS");return null;}
            switch(type){
                case "checkout.session" -> {CheckoutSessionStatus state=switch(status==null?"":status){case "open"->CheckoutSessionStatus.OPEN;case "complete"->CheckoutSessionStatus.COMPLETE;case "expired"->CheckoutSessionStatus.EXPIRED;default->null;};if(state==null){inbox.waitFor(claim,"EVIDENCE",objectId,"UNKNOWN_SESSION_STATUS");return null;}session=new CheckoutSessionFact(objectId,state,null,pi,text(object,"payment_status"),time(object,"expires_at"),Map.of());}
                case "payment_intent" -> intent=new PaymentIntentFact(objectId,status,amount,number(object,"amount_received"),currency,List.of(),text(object,"latest_charge"),null,Map.of(),time(object,"created"));
                case "charge" -> {boolean captured=object.path("captured").asBoolean();Long capturedAmount=number(object,"amount_captured");if(status==null||captured&&(!status.equals("succeeded")||capturedAmount==null||capturedAmount<=0||capturedAmount>amount)){inbox.waitFor(claim,"EVIDENCE",objectId,"INCOMPLETE_CAPTURE");return null;}charges=List.of(new ChargeFact(objectId,pi,status,captured,amount,capturedAmount,currency,text(object,"balance_transaction"),text(object,"failure_code"),null,time(object,"created")));}
            }
            boolean capture=charges.stream().anyMatch(ChargeFact::captured);
            payments.mergeWebhook(payment,new Facts(session,intent,charges,capture,false,eventTime));
            // Per-snapshot identity records the source evidence, including new BT relationships, independently of capture identity.
            inbox.jdbc().update("INSERT INTO platform.normalized_observation(organization_id,environment_id,provider_account_id,fact_key,fact_kind,object_id,payment_id,fact,first_source,last_source,first_confirmed_at,last_confirmed_at) VALUES (?,?,?,?,'OBJECT',?,?,?::jsonb,'WEBHOOK','WEBHOOK',?,?) ON CONFLICT(organization_id,environment_id,provider_account_id,fact_key) DO UPDATE SET last_confirmed_at=greatest(normalized_observation.last_confirmed_at,excluded.last_confirmed_at)",p.get("organization_id"),p.get("environment_id"),p.get("provider_account_id"),WebhookNormalizer.semanticKey(object),objectId,payment,object.toString(),Timestamp.from(eventTime),Timestamp.from(eventTime));
            inbox.done(claim);return null;
        }); } catch(DuplicateKeyException collision) {
            String detail=collision.getMostSpecificCause().getMessage();
            if(detail==null || !detail.contains("payment_provider_intent_identity"))throw collision;
            // The failed merge is fully rolled back before quarantining its evidence in a new transaction.
            inbox.transaction(()->{
                Map<String,Object> p=payment==null?null:payments.lockWebhookPayment(payment);
                if(!inbox.owns(claim))return null;
                if(p!=null)payments.mergeWebhook(payment,new Facts(null,null,List.of(),false,true,((Timestamp)claim.row().get("event_created_at")).toInstant()));
                inbox.waitFor(claim,"EVIDENCE",objectId,"PROVIDER_OBJECT_OWNERSHIP_CONFLICT");return null;
            });
        }
    }
    private static boolean supported(String type,String eventType){
        return switch(type){
            case "checkout.session" -> Set.of("checkout.session.completed","checkout.session.expired","checkout.session.async_payment_succeeded","checkout.session.async_payment_failed").contains(eventType);
            case "payment_intent" -> Set.of("payment_intent.created","payment_intent.succeeded","payment_intent.processing","payment_intent.payment_failed","payment_intent.requires_action","payment_intent.canceled","payment_intent.amount_capturable_updated").contains(eventType);
            case "charge" -> Set.of("charge.succeeded","charge.failed","charge.pending","charge.captured").contains(eventType);
            default -> false;
        };
    }
    private static String text(JsonNode n,String field){JsonNode v=n.path(field);return v.isTextual()?v.textValue():null;}
    private static Long number(JsonNode n,String field){JsonNode v=n.path(field);return v.isIntegralNumber()?v.longValue():null;}
    private static Instant time(JsonNode n,String field){Long value=number(n,field);return value==null?null:Instant.ofEpochSecond(value);}
}
