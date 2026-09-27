package com.paymentsledger.payment;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.paymentsledger.payment.PaymentRepository.*;
/** One shared authoritative Session → Intent → all Charge proof; at most 32 remote requests per batch. */
public final class ProviderQueryService {
 private final PaymentRepository repository;private final CheckoutPort checkout;private final PaymentIntentPort intents;private final ChargePort charges;
 private int remaining;
 ProviderQueryService(PaymentRepository r,CheckoutPort c,PaymentIntentPort i,ChargePort ch){repository=r;checkout=c;intents=i;charges=ch;remaining=32;}
 ProviderQueryService budget(int budget){remaining=budget;return this;}
 record Queried(Facts facts,List<Observation> observations){}
 private ProviderScope scope(Map<String,Object> p){return new ProviderScope(uuid(p,"organization_id"),uuid(p,"environment_id"),uuid(p,"provider_account_id"),false);}
 private static boolean success(ProviderCallResult<?> r){return r.payload()!=null&&(r.classification()==ProviderCallClassification.SUCCESS||r.classification()==ProviderCallClassification.AUTH_REQUIRED);}
 private <T>ProviderCallResult<T> call(Supplier<ProviderCallResult<T>> action,String key){
  outsideTransaction();Instant start=repository.now();
  if(remaining--<=0)return new ProviderCallResult<>(ProviderCallClassification.RETRYABLE_TRANSIENT,null,new ProviderEvidence(null,key,start,null,null,"query_budget_exhausted",null,null));
  try{return action.get();}catch(RuntimeException failure){return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN,null,new ProviderEvidence(null,key,start,null,null,"query_exception",null,null));}
 }
    Queried query(Map<String,Object> p){
        try(var budget=ProviderRequestBudget.openOrJoin(32)){return queryWithinBudget(p);}
    }
    private Queried queryWithinBudget(Map<String,Object> p){
        Instant queryStarted=repository.now();
        long paymentVersion=number(p,"version");
        var observations=new ArrayList<Observation>();var verified=new LinkedHashMap<String,ChargeFact>();
        var providerScope=scope(p);UUID payment=uuid(p,"id");String expectedSession=str(p,"checkout_session_id"),expectedIntent=str(p,"payment_intent_id");
        CheckoutSessionFact session=null;PaymentIntentFact intent=null;boolean complete=true,conflict=false;
        if((checkout==null || expectedSession==null) && expectedIntent==null)return new Queried(new Facts(null,null,List.of(),false,false,queryStarted,paymentVersion),observations);
        if(expectedSession!=null && checkout!=null){
        var sessionResult=call(()->checkout.querySession(providerScope,expectedSession),null);
        observations.add(new Observation("QUERY_SESSION",sessionResult,sessionResult.payload()==null?expectedSession:sessionResult.payload().sessionId()));
        if(success(sessionResult)){
            var fact=sessionResult.payload();
            if(fact.expiresAt()==null || fact.status()==CheckoutSessionStatus.OPEN && (fact.hostedUrl()==null || fact.hostedUrl().isBlank()) || !expectedSession.equals(fact.sessionId()) || !matchesMetadata(fact.metadata(),payment) || expectedIntent!=null && !expectedIntent.equals(fact.paymentIntentId())){conflict=true;complete=false;}
            else{session=fact;if(expectedIntent==null)expectedIntent=fact.paymentIntentId();}
        }else complete=false;
        }
        if(expectedIntent!=null){
            String pi=expectedIntent;
            if(intents==null || charges==null)complete=false;
            else{
                var piResult=call(()->intents.queryPaymentIntent(providerScope,pi),null);
                observations.add(new Observation("QUERY_PAYMENT_INTENT",piResult,piResult.payload()==null?pi:piResult.payload().paymentIntentId()));
                if(success(piResult)){
                    var fact=piResult.payload();
                    if(!pi.equals(fact.paymentIntentId()) || !matchesMetadata(fact.metadata(),payment) || number(p,"amount_minor")!=fact.amountMinor() || !str(p,"currency").equalsIgnoreCase(fact.currency())){conflict=true;complete=false;}
                    else intent=fact;
                }else complete=false;
                var attempts=call(()->charges.listChargeAttempts(providerScope,pi),null);
                observations.add(new Observation("LIST_CHARGE_ATTEMPTS",attempts,pi));
                if(success(attempts))for(var charge:attempts.payload()){
                    if(validCharge(p,pi,charge))verified.put(charge.chargeId(),charge);else{conflict=true;complete=false;}
                }else complete=false;
                if(intent!=null){
                    Set<String> ids=new LinkedHashSet<>(intent.chargeIds());if(intent.latestChargeId()!=null)ids.add(intent.latestChargeId());
                    for(String chargeId:ids)if(!verified.containsKey(chargeId)){
                        var result=call(()->charges.queryCharge(providerScope,chargeId),null);
                        observations.add(new Observation("QUERY_CHARGE",result,result.payload()==null?chargeId:result.payload().chargeId()));
                        if(success(result) && chargeId.equals(result.payload().chargeId()) && validCharge(p,pi,result.payload()))verified.put(chargeId,result.payload());
                        else{complete=false;if(success(result))conflict=true;}
                    }
                    if(intent.status().equals("succeeded") && verified.values().stream().noneMatch(c->c.captured() && c.status().equals("succeeded")))complete=false;
                }
            }
        }else if(session!=null && session.status()==CheckoutSessionStatus.COMPLETE)complete=false;
        return new Queried(new Facts(session,intent,List.copyOf(verified.values()),complete,conflict,queryStarted,paymentVersion),observations);
    }
    private boolean validCharge(Map<String,Object> p,String intent,ChargeFact charge){
        return intent.equals(charge.paymentIntentId()) && str(p,"currency").equalsIgnoreCase(charge.currency())
                && charge.amountMinor()==number(p,"amount_minor")
                && (!charge.captured() || charge.status().equals("succeeded") && charge.amountCapturedMinor()!=null && charge.amountCapturedMinor()>0 && charge.amountCapturedMinor()<=charge.amountMinor());
    }
    static boolean unpaid(Facts facts){
        if(!facts.complete() || facts.conflict() || facts.session()==null || "paid".equals(facts.session().paymentStatus()))return false;
        if(facts.charges().stream().anyMatch(c->c.captured() || !c.status().equals("failed")))return false;
        var intent=facts.intent();return intent==null || Set.of("requires_payment_method","canceled").contains(intent.status()) && intent.amountReceivedMinor()!=null && intent.amountReceivedMinor()==0;
    }
}
