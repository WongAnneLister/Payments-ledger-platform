package com.paymentsledger.payment;

import com.paymentsledger.common.error.*;
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

/** PR06 only sends committed PREPARED work. UNKNOWN and orphaned SENDING require PR07 evidence recovery. */
@Component
public class PaymentExecutor {
    private final PaymentRepository repository;
    private final CheckoutPort checkout;
    private final PaymentIntentPort intents;
    private final ChargePort charges;
    private final String providerName;
    @Autowired public PaymentExecutor(PaymentRepository repository,ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<PaymentIntentPort> intents,ObjectProvider<ChargePort> charges,Environment environment){
        this(repository,checkout.getIfAvailable(),intents.getIfAvailable(),charges.getIfAvailable(),
                environment.getProperty("platform.stripe.enabled",Boolean.class,false)?"STRIPE":
                environment.getProperty("platform.provider.fake.enabled",Boolean.class,false)?"FAKE":null);
    }
    public PaymentExecutor(PaymentRepository repository,CheckoutPort checkout,PaymentIntentPort intents,ChargePort charges,String providerName){
        this.repository=repository;this.checkout=checkout;this.intents=intents;this.charges=charges;this.providerName=providerName;
    }
    String providerName(){return checkout==null?null:providerName;}
    public void drainPrepared(int limit){outsideTransaction();if(checkout==null)return;for(UUID id:repository.prepared(limit,providerName)){
        try{execute(id);}catch(ApiException unavailable){/* A disabled binding remains durable and unsent. */}
    }}
    public void execute(UUID operation){
        outsideTransaction();if(checkout==null)return;
        var claim=repository.claim(operation,providerName);if(claim==null)return;
        if(claim.type().equals("CREATE_CHECKOUT")){
            var command=repository.decode(claim.operation().get("command"),CreateCheckoutSessionCommand.class);
            repository.finishCreate(claim,call(()->checkout.createSession(command),command.operationIdempotencyKey()));
        }else cancel(claim);
    }
    private <T>ProviderCallResult<T> call(Supplier<ProviderCallResult<T>> action,String key){
        outsideTransaction();Instant start=repository.now();
        try{return action.get();}catch(RuntimeException failed){
            // Exception messages may contain secrets: retain only a fixed redacted classification.
            return new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN,null,new ProviderEvidence(null,key,start,null,null,"port_exception",null,null));
        }
    }
    private ProviderScope scope(Map<String,Object> p){return new ProviderScope(uuid(p,"organization_id"),uuid(p,"environment_id"),uuid(p,"provider_account_id"),false);}
    private static boolean success(ProviderCallResult<?> result){return result.payload()!=null && (result.classification()==ProviderCallClassification.SUCCESS || result.classification()==ProviderCallClassification.AUTH_REQUIRED);}
    private record Queried(Facts facts,List<Observation> observations){}
    public void refresh(UUID payment){
        outsideTransaction();var p=repository.payment(payment);if(p==null || p.get("checkout_session_id")==null)return;
        var query=query(p);repository.mergeQuery(payment,repository.createOperation(payment),query.observations(),query.facts());
    }
    private Queried query(Map<String,Object> p){
        Instant queryStarted=repository.now();
        long paymentVersion=number(p,"version");
        var observations=new ArrayList<Observation>();var verified=new LinkedHashMap<String,ChargeFact>();
        var providerScope=scope(p);UUID payment=uuid(p,"id");String expectedSession=str(p,"checkout_session_id"),expectedIntent=str(p,"payment_intent_id");
        CheckoutSessionFact session=null;PaymentIntentFact intent=null;boolean complete=true,conflict=false;
        if(checkout==null || expectedSession==null)return new Queried(new Facts(null,null,List.of(),false,false,queryStarted,paymentVersion),observations);
        var sessionResult=call(()->checkout.querySession(providerScope,expectedSession),null);
        observations.add(new Observation("QUERY_SESSION",sessionResult,sessionResult.payload()==null?expectedSession:sessionResult.payload().sessionId()));
        if(success(sessionResult)){
            var fact=sessionResult.payload();
            if(!expectedSession.equals(fact.sessionId()) || !matchesMetadata(fact.metadata(),payment) || expectedIntent!=null && !expectedIntent.equals(fact.paymentIntentId())){conflict=true;complete=false;}
            else{session=fact;if(expectedIntent==null)expectedIntent=fact.paymentIntentId();}
        }else complete=false;
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
    private boolean unpaid(Facts facts){
        if(!facts.complete() || facts.conflict() || facts.session()==null || "paid".equals(facts.session().paymentStatus()))return false;
        if(facts.charges().stream().anyMatch(c->c.captured() || !c.status().equals("failed")))return false;
        var intent=facts.intent();return intent==null || Set.of("requires_payment_method","canceled").contains(intent.status()) && intent.amountReceivedMinor()!=null && intent.amountReceivedMinor()==0;
    }
    private void cancel(Claim claim){
        if(claim.payment().get("checkout_session_id")==null){
            if(repository.cancelUnsent(claim))return;
            repository.finishCancel(claim,List.of(),new Facts(null,null,List.of(),false,false,repository.now()),false);return;
        }
        var before=query(claim.payment());var facts=before.facts();var observations=new ArrayList<>(before.observations());
        boolean canceled=false;
        if(unpaid(facts)){
            if(facts.session().status()==CheckoutSessionStatus.OPEN){
                String sessionId=facts.session().sessionId();
                var result=call(()->checkout.expireSession(scope(claim.payment()),sessionId),str(claim.operation(),"operation_key"));
                observations.add(new Observation("EXPIRE_SESSION",result,result.payload()==null?facts.session().sessionId():result.payload().sessionId()));
                if(success(result) && result.payload().sessionId().equals(facts.session().sessionId()) && matchesMetadata(result.payload().metadata(),claim.paymentId()) && result.payload().status()==CheckoutSessionStatus.EXPIRED){
                    var after=query(claim.payment());observations.addAll(after.observations());facts=after.facts();
                    canceled=unpaid(facts) && facts.session().status()==CheckoutSessionStatus.EXPIRED;
                }else{
                    var after=query(claim.payment());observations.addAll(after.observations());facts=after.facts();
                }
            }else if(facts.session().status()==CheckoutSessionStatus.EXPIRED)canceled=true;
        }
        // Persist uncertain cancellation separately even if the query has known facts.
        if(!canceled && facts.charges().stream().noneMatch(ChargeFact::captured))facts=new Facts(facts.session(),facts.intent(),facts.charges(),false,facts.conflict(),facts.confirmedAt(),facts.paymentVersion());
        repository.finishCancel(claim,observations,facts,canceled);
    }
}
