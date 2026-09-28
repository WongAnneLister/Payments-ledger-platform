package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Explicit short transactions; no provider port is reachable from this class. */
@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    final Clock clock;
    private org.springframework.core.env.Environment configuration;
    private RecoveryPolicy recoveryPolicy=RecoveryPolicy.defaults();
    RecoveryPolicy recoveryPolicy(){return recoveryPolicy;}
    void recoveryPolicy(RecoveryPolicy policy){this.recoveryPolicy=policy;}
    private static final String SCOPE="organization_id,environment_id,application_id,provider_account_id,currency";
    public PaymentRepository(JdbcTemplate jdbc,PlatformTransactionManager transactions,ObjectMapper json,Clock clock){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);this.json=json;this.clock=clock;
    }
    public Instant now(){return clock.instant();}
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentRepository(JdbcTemplate jdbc,PlatformTransactionManager transactions,ObjectMapper json,Clock clock,org.springframework.core.env.Environment environment){this(jdbc,transactions,json,clock);this.configuration=environment;this.recoveryPolicy=RecoveryPolicy.from(environment);}
    public record Intent(UUID paymentId,UUID operationId,boolean replay){}
    public record Claim(UUID operationId,UUID paymentId,UUID token,long version,String type,Map<String,Object> operation,Map<String,Object> payment){}
    public record Observation(String type,ProviderCallResult<?> result,String externalId){}
    public record Facts(CheckoutSessionFact session,PaymentIntentFact intent,List<ChargeFact> charges,boolean complete,boolean conflict,Instant confirmedAt,Long paymentVersion){
        public Facts(CheckoutSessionFact session,PaymentIntentFact intent,List<ChargeFact> charges,boolean complete,boolean conflict,Instant confirmedAt){this(session,intent,charges,complete,conflict,confirmedAt,null);}
    }
    <T>T transaction(Supplier<T> work){return tx.execute(s->work.get());}
    static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Payment orchestration must run outside database transactions");}
    static ApiException error(ErrorCode code){return new ApiException(code,"Payment request cannot be completed: "+code.name());}
    static UUID uuid(Map<String,Object> row,String key){return (UUID)row.get(key);}
    static String str(Map<String,Object> row,String key){return (String)row.get(key);}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:((Timestamp)value).toInstant();}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot encode controlled payment command",e);}}
    <T>T decode(Object value,Class<T> type){try{return json.readValue(value.toString(),type);}catch(Exception e){throw new IllegalStateException("Invalid persisted payment command",e);}}
    private void lock(String key){jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",key);}
    private String scopeKey(TenantContext c){return c.organizationId()+":"+c.environmentId()+":"+c.applicationId();}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Object> api(TenantContext c,String key,String type){return one("SELECT * FROM platform.api_idempotency_record WHERE organization_id=? AND environment_id=? AND application_id=? AND request_type=? AND idempotency_key=?",c.organizationId(),c.environmentId(),c.applicationId(),type,key);}
    private Intent replay(Map<String,Object> row,String fingerprint){if(!fingerprint.equals(row.get("request_fingerprint")))throw error(ErrorCode.IDEMPOTENCY_CONFLICT);return new Intent(uuid(row,"payment_id"),uuid(row,"operation_id"),true);}
    private void checkBinding(TenantContext c,String provider){
        var row=one("""
                SELECT p.provider,p.provider_account_ref FROM platform.provider_account p
                JOIN platform.organization o ON o.id=p.organization_id
                JOIN platform.environment_config e ON e.organization_id=p.organization_id AND e.id=p.environment_id
                JOIN platform.application a ON a.organization_id=p.organization_id AND a.environment_id=p.environment_id
                WHERE p.organization_id=? AND p.environment_id=? AND p.id=? AND a.id=?
                AND p.status='ACTIVE' AND o.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE'
                AND NOT p.livemode AND NOT e.livemode
                """,c.organizationId(),c.environmentId(),c.allowedProviderAccountId(),c.applicationId());
        if(row==null)throw error(ErrorCode.FORBIDDEN);
        if(provider==null || !provider.equals(row.get("provider")))throw error(ErrorCode.SERVICE_UNAVAILABLE);
        if(provider.equals("STRIPE") && (!Objects.equals(str(row,"provider_account_ref"),configured("account-id","STRIPE_ACCOUNT_ID"))
                || !c.organizationId().toString().equals(configured("organization-id","STRIPE_ORGANIZATION_ID"))
                || !c.environmentId().toString().equals(configured("environment-id","STRIPE_ENVIRONMENT_ID"))
                || !c.allowedProviderAccountId().toString().equals(configured("provider-account-id","STRIPE_PROVIDER_ACCOUNT_ID"))))throw error(ErrorCode.SERVICE_UNAVAILABLE);
    }
    private String configured(String name,String fallback){return configuration==null?null:configuration.getProperty("platform.stripe."+name,configuration.getProperty(fallback));}
    public Intent prepare(TenantContext c,String key,PaymentRequests.Create request,String provider){
        TenantAccessGuard.requireScope(c,"payments:write");PaymentRequests.idempotencyKey(key);
        return transaction(()->{
            lock(scopeKey(c)+":CREATE_PAYMENT:"+key);
            var prior=api(c,key,"CREATE_PAYMENT");if(prior!=null)return replay(prior,request.fingerprint());
            TenantAccessGuard.requireCurrency(c,request.currency());
            lock(scopeKey(c)+":ORDER:"+request.orderKey());
            if(one("SELECT id FROM platform.business_order WHERE organization_id=? AND environment_id=? AND application_id=? AND order_key=?",c.organizationId(),c.environmentId(),c.applicationId(),request.orderKey())!=null)throw error(ErrorCode.ORDER_INTENT_CONFLICT);
            checkBinding(c,provider);
            var price=one("""
                SELECT p.*,to_jsonb(p)::text price_snapshot,to_jsonb(s)::text policy_snapshot
                FROM platform.price_fixture p JOIN platform.share_policy_fixture s
                 ON s.id=p.share_policy_id AND s.organization_id=p.organization_id AND s.environment_id=p.environment_id AND s.product_ref=p.product_ref
                JOIN platform.org_currency_enabled e ON e.organization_id=p.organization_id AND e.environment_id=p.environment_id AND e.currency=p.currency
                WHERE p.organization_id=? AND p.environment_id=? AND p.product_ref=? AND p.price_version=?
                 AND p.status='ACTIVE' AND s.status='ACTIVE' AND e.status='ACTIVE'
                 AND p.valid_from<=? AND p.valid_until>? AND s.valid_from<=? AND s.valid_until>?
                """,c.organizationId(),c.environmentId(),request.productRef(),request.priceVersion(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
            if(price==null)throw error(ErrorCode.NOT_FOUND);
            long amount;try{amount=Math.multiplyExact(number(price,"amount_minor"),request.quantity());}catch(ArithmeticException e){throw error(ErrorCode.AMOUNT_MISMATCH);}
            if(amount!=request.amountMinor() || !request.currency().equals(price.get("currency")))throw error(ErrorCode.AMOUNT_MISMATCH);
            UUID order=PaymentIds.newId(),payment=PaymentIds.newId(),operation=UUID.randomUUID();
            Instant expires=clock.instant().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            jdbc.update("INSERT INTO platform.business_order(id,"+SCOPE+",order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",order,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),request.orderKey(),request.fingerprint(),request.productRef(),request.priceVersion(),request.quantity(),number(price,"amount_minor"),amount,price.get("price_snapshot"),price.get("policy_snapshot"));
            jdbc.update("INSERT INTO platform.payment(id,"+SCOPE+",business_order_id,amount_minor,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",payment,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),request.currency(),order,amount,Timestamp.from(expires));
            var metadata=new TreeMap<>(request.metadata());metadata.put("payment_id",payment.toString());metadata.put("business_order_id",order.toString());
            metadata.put("organization_id",c.organizationId().toString());metadata.put("application_id",c.applicationId().toString());
            metadata.put("environment_id",c.environmentId().toString());metadata.put("provider_account_id",c.allowedProviderAccountId().toString());
            var command=new CreateCheckoutSessionCommand(new ProviderScope(c.organizationId(),c.environmentId(),c.allowedProviderAccountId(),false),"pay_create_"+operation,amount,request.currency(),request.successUrl(),request.cancelUrl(),metadata,metadata,expires);
            insertOperation(c,request.currency(),payment,operation,"CREATE_CHECKOUT",command.operationIdempotencyKey(),request.fingerprint(),encode(command),expires);
            insertApi(c,request.currency(),key,"CREATE_PAYMENT",request.fingerprint(),payment,operation);
            return new Intent(payment,operation,false);
        });
    }
    private void insertOperation(TenantContext c,String currency,UUID payment,UUID operation,String type,String key,String fingerprint,String command,Instant expires){
        jdbc.update("INSERT INTO platform.provider_operation(id,"+SCOPE+",operation_target,payment_id,operation_type,operation_key,request_fingerprint,command,expires_at) VALUES (?,?,?,?,?,?,'PAYMENT',?,?,?,?,?::jsonb,?)",operation,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),currency,payment,type,key,fingerprint,command,Timestamp.from(expires));
        jdbc.update("INSERT INTO platform.recovery_task("+SCOPE+",operation_id,payment_id,available_at) VALUES (?,?,?,?,?,?,?,?)",c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),currency,operation,payment,Timestamp.from(now()));
    }
    private void insertApi(TenantContext c,String currency,String key,String type,String fingerprint,UUID payment,UUID operation){
        jdbc.update("INSERT INTO platform.api_idempotency_record("+SCOPE+",request_type,idempotency_key,request_fingerprint,payment_id,operation_id) VALUES (?,?,?,?,?,?,?,?,?,?)",c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId(),currency,type,key,fingerprint,payment,operation);
    }
    public Intent prepareCancel(TenantContext c,UUID payment,String key,PaymentRequests.Cancel request,String provider){
        TenantAccessGuard.requireScope(c,"payments:write");PaymentRequests.idempotencyKey(key);
        return transaction(()->{
            lock(scopeKey(c)+":CANCEL_PAYMENT:"+key);
            var prior=api(c,key,"CANCEL_PAYMENT");if(prior!=null)return replay(prior,request.fingerprint(payment));
            var row=scoped(c,payment,true);checkBinding(c,provider);
            if("CONFLICT".equals(row.get("resolution_status")))throw error(ErrorCode.EVIDENCE_CONFLICT);
            if("SUCCEEDED".equals(row.get("payment_status")) || number(row,"captured_amount_minor")>0)throw error(ErrorCode.PAYMENT_NOT_CANCELABLE);
            var existing=one("SELECT * FROM platform.provider_operation WHERE payment_id=? AND operation_type='CANCEL_PAYMENT'",payment);
            UUID operation=existing==null?UUID.randomUUID():uuid(existing,"id");
            if(existing==null)insertOperation(c,str(row,"currency"),payment,operation,"CANCEL_PAYMENT","pay_cancel_"+operation,request.fingerprint(payment),encode(request),instant(row,"expires_at"));
            insertApi(c,str(row,"currency"),key,"CANCEL_PAYMENT",request.fingerprint(payment),payment,operation);
            return new Intent(payment,operation,existing!=null);
        });
    }
    private Map<String,Object> scoped(TenantContext c,UUID id,boolean locked){
        TenantAccessGuard.require(c);
        var row=one("SELECT p.*,o.order_key FROM platform.payment p JOIN platform.business_order o ON o.id=p.business_order_id WHERE p.id=? AND p.organization_id=? AND p.environment_id=? AND p.application_id=? AND p.provider_account_id=?"+(locked?" FOR UPDATE OF p":""),id,c.organizationId(),c.environmentId(),c.applicationId(),c.allowedProviderAccountId());
        if(row==null || !c.allowedCurrencies().contains(row.get("currency")))throw error(ErrorCode.NOT_FOUND);return row;
    }
    public Map<String,Object> read(TenantContext c,UUID id){return transaction(()->resource(scoped(c,id,false)));}
    Map<String,Object> payment(UUID id){return transaction(()->one("SELECT p.*,o.order_key FROM platform.payment p JOIN platform.business_order o ON o.id=p.business_order_id WHERE p.id=?",id));}
    Map<String,Object> operation(UUID id){return transaction(()->one("SELECT * FROM platform.provider_operation WHERE id=?",id));}
    UUID createOperation(UUID payment){return transaction(()->jdbc.queryForObject("SELECT id FROM platform.provider_operation WHERE payment_id=? AND operation_type='CREATE_CHECKOUT'",UUID.class,payment));}
    public List<UUID> prepared(int limit,String provider){
        if(provider==null)return List.of();
        return transaction(()->jdbc.queryForList("""
            SELECT o.id FROM platform.provider_operation o
            JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.payment pay ON pay.id=o.payment_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            WHERE o.status='PREPARED' AND t.status='READY' AND pay.resolution_status<>'CONFLICT'
              AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=?
              AND (?<>'STRIPE' OR (p.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
            ORDER BY o.created_at,o.id LIMIT ?
            """,UUID.class,provider,provider,configured("account-id","STRIPE_ACCOUNT_ID"),configured("organization-id","STRIPE_ORGANIZATION_ID"),configured("environment-id","STRIPE_ENVIRONMENT_ID"),configured("provider-account-id","STRIPE_PROVIDER_ACCOUNT_ID"),Math.min(100,Math.max(1,limit))));
    }
    Claim claim(UUID id,String provider){return claimRecovery(id,provider,false);}
    Claim claimRecovery(UUID id,String provider,boolean recovery){return transaction(()->{
        var reference=one("SELECT payment_id FROM platform.provider_operation WHERE id=?",id);if(reference==null)return null;
        lockPayment(uuid(reference,"payment_id"));
        var op=one("SELECT * FROM platform.provider_operation WHERE id=? FOR UPDATE",id);
        var task=one("SELECT * FROM platform.recovery_task WHERE operation_id=? FOR UPDATE",id);
        Instant now=now();
        if(op==null || "ESCALATED".equals(op.get("status")))return null;
        if(!recovery && !"PREPARED".equals(op.get("status")))return null;
        if(recovery && ("DONE".equals(task.get("status")) || instant(task,"available_at").isAfter(now)
                || instant(op,"lease_until")!=null && instant(op,"lease_until").isAfter(now)))return null;
        var c=new TenantContext(uuid(op,"organization_id"),uuid(op,"environment_id"),uuid(op,"application_id"),uuid(op,"provider_account_id"),Set.of(str(op,"currency")),false,Set.of());
        checkBinding(c,provider);
        var p=one("SELECT p.*,o.order_key FROM platform.payment p JOIN platform.business_order o ON o.id=p.business_order_id WHERE p.id=?",uuid(op,"payment_id"));
        if(!recovery && "CONFLICT".equals(p.get("resolution_status")))throw error(ErrorCode.EVIDENCE_CONFLICT);
        if(!recovery && "CREATE_CHECKOUT".equals(op.get("operation_type")) && (p.get("payment_intent_id")!=null || number(p,"captured_amount_minor")>0)){
            jdbc.update("UPDATE platform.provider_operation SET status='UNKNOWN',resolution='UNKNOWN',version=version+1 WHERE id=?",id);
            jdbc.update("UPDATE platform.recovery_task SET status='WAITING',reason='REMOTE_FACT_REQUIRES_QUERY',available_at=?,version=version+1 WHERE operation_id=?",Timestamp.from(now),id);
            return null;
        }
        if(one("SELECT id FROM platform.org_currency_enabled WHERE organization_id=? AND environment_id=? AND currency=? AND status='ACTIVE'",op.get("organization_id"),op.get("environment_id"),op.get("currency"))==null)throw error(ErrorCode.CURRENCY_NOT_ENABLED);
        // A shared account row meters recovery batches across processes. Never sleep while holding locks.
        if(recovery && !reserveBatch(op,now))return null;
        UUID token=UUID.randomUUID();long version=number(op,"version")+1;
        Instant first=instant(op,"first_send_at"),horizon=instant(op,"retry_until");
        if(first==null){first=now;Duration window=recoveryPolicy.verifiedWindow(provider);if(!window.isZero())horizon=first.plus(window);}
        Instant until=now.plus(recoveryPolicy.lease());
        jdbc.update("UPDATE platform.provider_operation SET status='SENDING',lease_token=?,lease_owner='pr07',lease_until=?,attempt_count=attempt_count+1,first_send_at=?,retry_until=?,version=?,updated_at=? WHERE id=?",token,Timestamp.from(until),Timestamp.from(first),horizon==null?null:Timestamp.from(horizon),version,Timestamp.from(now),id);
        jdbc.update("UPDATE platform.recovery_task SET status='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1,version=version+1,updated_at=? WHERE operation_id=?",token,Timestamp.from(until),Timestamp.from(now),id);
        // Original status determines whether the first command is known to be unsent.
        op.put("first_send_at",Timestamp.from(first));op.put("retry_until",horizon==null?null:Timestamp.from(horizon));
        return new Claim(id,uuid(op,"payment_id"),token,version,str(op,"operation_type"),op,p);
    });}
    private boolean reserveBatch(Map<String,Object> op,Instant now){
        jdbc.update("INSERT INTO platform.recovery_account_limit(organization_id,environment_id,provider_account_id,next_batch_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",op.get("organization_id"),op.get("environment_id"),op.get("provider_account_id"),Timestamp.from(now));
        var limit=one("SELECT next_batch_at FROM platform.recovery_account_limit WHERE organization_id=? AND environment_id=? AND provider_account_id=? FOR UPDATE",op.get("organization_id"),op.get("environment_id"),op.get("provider_account_id"));
        Instant next=instant(limit,"next_batch_at");
        if(next.isAfter(now)){jdbc.update("UPDATE platform.recovery_task SET available_at=greatest(available_at,?),version=version+1 WHERE operation_id=?",Timestamp.from(next),op.get("id"));return false;}
        jdbc.update("UPDATE platform.recovery_account_limit SET next_batch_at=? WHERE organization_id=? AND environment_id=? AND provider_account_id=?",Timestamp.from(now.plus(recoveryPolicy.accountBatchInterval())),op.get("organization_id"),op.get("environment_id"),op.get("provider_account_id"));return true;
    }
    private void lockPayment(UUID payment){one("SELECT id FROM platform.payment WHERE id=? FOR UPDATE",payment);}
    boolean owns(Claim claim){var row=one("SELECT o.id FROM platform.provider_operation o JOIN platform.recovery_task t ON t.operation_id=o.id WHERE o.id=? AND o.lease_token=? AND o.version=? AND o.status='SENDING' AND o.lease_until>? AND t.status='RUNNING' AND t.lease_token=o.lease_token AND t.lease_until=o.lease_until FOR UPDATE OF o",claim.operationId(),claim.token(),claim.version(),Timestamp.from(now()));return row!=null;}
    void observations(UUID operation,UUID payment,UUID token,List<Observation> observations){
        var op=one("SELECT * FROM platform.provider_operation WHERE id=?",operation);
        for(var observation:observations){
            // Persist only project-owned fields; never serialize arbitrary provider payloads or SDK objects.
            var evidence=observation.result().evidence();
            jdbc.update("INSERT INTO platform.provider_observation("+SCOPE+",operation_id,payment_id,lease_token,call_type,classification,external_object_id,evidence,fact) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",op.get("organization_id"),op.get("environment_id"),op.get("application_id"),op.get("provider_account_id"),op.get("currency"),operation,payment,token,observation.type(),observation.result().classification().name(),observation.externalId(),encode(evidence),observation.result().payload()==null?null:encode(redacted(observation.result().payload())));
        }
    }
    private Object redacted(Object value){
        if(value==null)return null;
        if(value instanceof CheckoutSessionFact f)return Map.of("sessionId",f.sessionId(),"status",f.status(),"paymentStatus",Objects.toString(f.paymentStatus(),""),"paymentIntentId",Objects.toString(f.paymentIntentId(),""));
        if(value instanceof PaymentIntentFact f){
            var fact=new LinkedHashMap<String,Object>();fact.put("paymentIntentId",f.paymentIntentId());fact.put("status",f.status());
            fact.put("amountMinor",f.amountMinor());fact.put("currency",f.currency());fact.put("amountReceivedMinor",f.amountReceivedMinor());fact.put("chargeIds",f.chargeIds());return fact;
        }
        if(value instanceof ChargeFact f)return f;
        if(value instanceof List<?> list)return list.stream().map(this::redacted).toList();
        throw new IllegalArgumentException("Unrecognized provider observation payload");
    }
    /** Recheck the committed payment immediately before a create call, without holding locks across the provider. */
    boolean deferCreateWithRemoteFact(Claim claim){return transaction(()->{
        lockPayment(claim.paymentId());
        if(!owns(claim))return true;
        var p=one("SELECT * FROM platform.payment WHERE id=?",claim.paymentId());
        if(p.get("payment_intent_id")==null && number(p,"captured_amount_minor")==0)return false;
        completeOperation(claim,"UNKNOWN",str(p,"resolution_status"),str(p,"checkout_session_id"),str(p,"provider_status"));
        return true;
    });}
    void finishCreate(Claim claim,ProviderCallResult<CheckoutSessionFact> result){transaction(()->{
        lockPayment(claim.paymentId());
        observations(claim.operationId(),claim.paymentId(),claim.token(),List.of(new Observation("CREATE_SESSION",result,result.payload()==null?null:result.payload().sessionId())));
        if(!owns(claim))return null;
        var current=one("SELECT * FROM platform.payment WHERE id=?",claim.paymentId());
        if("CONFLICT".equals(current.get("resolution_status"))){completeOperation(claim,"UNKNOWN","CONFLICT",result.payload()==null?null:result.payload().sessionId(),null);return null;}
        if(result.classification()==ProviderCallClassification.DEFINITIVE_REJECT){
            if(number(current,"captured_amount_minor")>0 || current.get("checkout_session_id")!=null || current.get("payment_intent_id")!=null){markUnknown(claim.paymentId(),true);completeOperation(claim,"UNKNOWN","CONFLICT",null,null);}
            else{
                jdbc.update("UPDATE platform.payment SET payment_status='CANCELED',resolution_status='KNOWN',unresolved_since=null,version=version+1,updated_at=now() WHERE id=? AND version=?",claim.paymentId(),number(current,"version"));
                completeOperation(claim,"ACKNOWLEDGED","KNOWN",null,null);
            }
            return null;
        }
        var fact=result.payload();boolean known=fact!=null && (result.classification()==ProviderCallClassification.SUCCESS || result.classification()==ProviderCallClassification.AUTH_REQUIRED);
        boolean valid=known && matchesMetadata(fact.metadata(),claim.paymentId()) && fact.expiresAt()!=null
                && (fact.status()!=CheckoutSessionStatus.OPEN || fact.hostedUrl()!=null && !fact.hostedUrl().isBlank());
        if(valid){
            var p=one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE",claim.paymentId());
            if(p.get("checkout_session_id")!=null && !p.get("checkout_session_id").equals(fact.sessionId()) || p.get("payment_intent_id")!=null && fact.paymentIntentId()!=null && !p.get("payment_intent_id").equals(fact.paymentIntentId())){markUnknown(claim.paymentId(),true);completeOperation(claim,"UNKNOWN","CONFLICT",null,null);return null;}
            String state=fact.status()==CheckoutSessionStatus.OPEN?"AWAITING_CUSTOMER":"PROCESSING";
            if("SUCCEEDED".equals(p.get("payment_status")) || "CANCELED".equals(p.get("payment_status")))state=str(p,"payment_status");
            jdbc.update("UPDATE platform.payment SET checkout_session_id=?,payment_intent_id=coalesce(payment_intent_id,?),hosted_url=?,payment_status=?,provider_status=?,resolution_status='KNOWN',unresolved_since=null,last_provider_confirmed_at=?,version=version+1,updated_at=now() WHERE id=? AND version=?",fact.sessionId(),fact.paymentIntentId(),fact.hostedUrl(),state,sessionStatus(fact),Timestamp.from(clock.instant()),claim.paymentId(),number(p,"version"));
            completeOperation(claim,"RESOLVED","KNOWN",fact.sessionId(),sessionStatus(fact));
        }else{
            boolean conflict=known || result.classification()==ProviderCallClassification.CONTRACT_MISMATCH || result.classification()==ProviderCallClassification.IDEMPOTENCY_CONFLICT;
            markUnknown(claim.paymentId(),conflict);completeOperation(claim,"UNKNOWN",conflict?"CONFLICT":"UNKNOWN",fact==null?null:fact.sessionId(),null);
        }
        return null;
    });}
    static boolean matchesMetadata(Map<String,String> metadata,UUID payment){return payment.toString().equals(metadata.get("payment_id"));}
    static String sessionStatus(CheckoutSessionFact fact){return fact.status().name().toLowerCase(Locale.ROOT);}
    private void completeOperation(Claim claim,String status,String resolution,String external,String providerStatus){
        // owns() is the authorization linearization point while the payment/operation locks are held.
        // Re-reading the wall clock here could reject the operation after payment facts already changed.
        int updated=jdbc.update("UPDATE platform.provider_operation SET status=?,resolution=?,external_object_id=coalesce(?,external_object_id),last_provider_status=coalesce(?,last_provider_status),lease_until=null,version=version+1,updated_at=? WHERE id=? AND lease_token=? AND version=? AND status='SENDING'",status,resolution,external,providerStatus,Timestamp.from(now()),claim.operationId(),claim.token(),claim.version());
        if(updated!=1)throw new IllegalStateException("Lost operation ownership inside locked writeback transaction");
        scheduleTask(claim.operationId());
    }
    private void scheduleTask(UUID operation){
        var op=one("SELECT * FROM platform.provider_operation WHERE id=?",operation);
        var p=one("SELECT * FROM platform.payment WHERE id=?",op.get("payment_id"));
        boolean done="ACKNOWLEDGED".equals(op.get("status")) || "CANCELED".equals(p.get("payment_status")) && "KNOWN".equals(p.get("resolution_status"))
            || "CANCEL_PAYMENT".equals(op.get("operation_type")) && "RESOLVED".equals(op.get("status"));
        Instant next=now().plus(recoveryPolicy.delay(operation,((Number)op.get("attempt_count")).intValue()));
        jdbc.update("UPDATE platform.recovery_task SET status=?,available_at=?,lease_until=null,lease_token=null,version=version+1,updated_at=? WHERE operation_id=?",done?"DONE":"WAITING",Timestamp.from(next),Timestamp.from(now()),operation);
    }
    private void markUnknown(UUID payment,boolean conflict){jdbc.update("UPDATE platform.payment SET resolution_status=CASE WHEN resolution_status='CONFLICT' OR ? THEN 'CONFLICT' ELSE 'UNKNOWN' END,unresolved_since=coalesce(unresolved_since,?),payment_status=CASE WHEN payment_status='CREATED' THEN 'PROCESSING' ELSE payment_status END,version=version+1,updated_at=now() WHERE id=?",conflict,Timestamp.from(now()),payment);}
    void mergeQuery(UUID payment,UUID operation,List<Observation> observations,Facts facts){transaction(()->{
        lockPayment(payment);
        observations(operation,payment,null,observations);boolean applicable=merge(payment,facts);
        jdbc.update("UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",Timestamp.from(now()),operation);
        // Query evidence never changes ownership or version while a sender owns the operation.
        if(applicable && facts.complete() && !facts.conflict())jdbc.update("UPDATE platform.provider_operation SET status='RESOLVED',resolution='KNOWN',last_query_at=?,version=version+1,updated_at=now() WHERE id=? AND status IN ('UNKNOWN','ACKNOWLEDGED')",Timestamp.from(clock.instant()),operation);
        return null;
    });}
    /** Called only by the inbox transaction after acquiring the payment lock before the inbox lock. */
    public Map<String,Object> lockWebhookPayment(UUID payment){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Webhook merge requires transaction");
        return one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE",payment);
    }
    public boolean mergeWebhook(UUID payment,Facts facts){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Webhook merge requires transaction");
        return merge(payment,facts,false);
    }
    private boolean merge(UUID payment,Facts facts){return merge(payment,facts,true);}
    private boolean merge(UUID payment,Facts facts,boolean query){
        var p=one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE",payment);
        boolean stale=facts.paymentVersion()!=null && facts.paymentVersion()!=number(p,"version")
                || instant(p,"last_provider_confirmed_at")!=null && facts.confirmedAt().toEpochMilli()<instant(p,"last_provider_confirmed_at").toEpochMilli();
        if(!query){
            Instant eventTime=instant(p,"last_webhook_created_at"),queryTime=instant(p,"last_query_confirmed_at");
            stale=(eventTime!=null&&facts.confirmedAt().isBefore(eventTime)) || (queryTime!=null&&!facts.confirmedAt().isAfter(queryTime));
        }
        boolean conflict=facts.conflict() || "CONFLICT".equals(p.get("resolution_status"));
        // Provider object identities are immutable evidence; missing links can be filled even by an older snapshot.
        String observedSession=facts.session()==null?null:facts.session().sessionId();
        String observedIntent=facts.intent()!=null?facts.intent().paymentIntentId():facts.session()==null?null:facts.session().paymentIntentId();
        if(observedSession!=null && p.get("checkout_session_id")!=null && !observedSession.equals(p.get("checkout_session_id"))){conflict=true;observedSession=null;}
        if(observedIntent!=null && p.get("payment_intent_id")!=null && !observedIntent.equals(p.get("payment_intent_id"))){conflict=true;observedIntent=null;}
        for(var charge:facts.charges()){
            var previousAttempt=one("SELECT * FROM platform.payment_attempt WHERE organization_id=? AND environment_id=? AND provider_account_id=? AND provider_object_id=?",p.get("organization_id"),p.get("environment_id"),p.get("provider_account_id"),charge.chargeId());
            if(previousAttempt!=null && (previousAttempt.get("balance_transaction_id")!=null && charge.balanceTransactionId()!=null && !charge.balanceTransactionId().equals(previousAttempt.get("balance_transaction_id")) || !payment.equals(previousAttempt.get("payment_id")) || !Objects.equals(charge.paymentIntentId(),previousAttempt.get("payment_intent_id")) || !charge.currency().equalsIgnoreCase(previousAttempt.get("currency").toString()) || charge.amountMinor()!=number(previousAttempt,"amount_minor") || Boolean.TRUE.equals(previousAttempt.get("captured")) && charge.captured() && !Objects.equals(charge.amountCapturedMinor(),previousAttempt.get("amount_captured_minor")))){conflict=true;continue;}
            if(previousAttempt!=null && !charge.captured() && facts.confirmedAt().isBefore(instant(previousAttempt,"fetched_at")))continue;
            if(charge.captured() && charge.status().equals("succeeded")){
                var normalized=new LinkedHashMap<String,Object>();normalized.put("charge_id",charge.chargeId());normalized.put("payment_intent_id",charge.paymentIntentId());normalized.put("payment_id",payment.toString());normalized.put("amount_minor",charge.amountMinor());normalized.put("amount_captured_minor",charge.amountCapturedMinor());normalized.put("currency",charge.currency().toUpperCase(Locale.ROOT));
                int accepted=jdbc.update("INSERT INTO platform.normalized_observation(organization_id,environment_id,provider_account_id,fact_key,fact_kind,object_id,payment_id,fact,first_source,last_source,first_confirmed_at,last_confirmed_at) VALUES (?,?,?,?,'CAPTURE',?,?,?::jsonb,?,?,?,?) ON CONFLICT(organization_id,environment_id,provider_account_id,fact_key) DO UPDATE SET last_confirmed_at=greatest(normalized_observation.last_confirmed_at,excluded.last_confirmed_at),last_source=CASE WHEN normalized_observation.last_source='QUERY' THEN 'QUERY' ELSE excluded.last_source END WHERE normalized_observation.fact=excluded.fact",p.get("organization_id"),p.get("environment_id"),p.get("provider_account_id"),"CAPTURE:"+charge.chargeId(),charge.chargeId(),payment,encode(normalized),query?"QUERY":"WEBHOOK",query?"QUERY":"WEBHOOK",Timestamp.from(facts.confirmedAt()),Timestamp.from(facts.confirmedAt()));
                if(accepted==0){conflict=true;continue;}
            }
            int written=jdbc.update("INSERT INTO platform.payment_attempt(id,"+SCOPE+",payment_id,provider_object_id,provider_status,payment_intent_id,captured,amount_minor,amount_captured_minor,balance_transaction_id,failure_code,provider_created_at,fetched_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (organization_id,environment_id,provider_account_id,provider_object_id) DO UPDATE SET provider_status=CASE WHEN payment_attempt.captured THEN payment_attempt.provider_status ELSE excluded.provider_status END,captured=payment_attempt.captured OR excluded.captured,amount_captured_minor=CASE WHEN payment_attempt.captured THEN payment_attempt.amount_captured_minor ELSE greatest(payment_attempt.amount_captured_minor,excluded.amount_captured_minor) END,balance_transaction_id=coalesce(excluded.balance_transaction_id,payment_attempt.balance_transaction_id),fetched_at=greatest(payment_attempt.fetched_at,excluded.fetched_at),version=payment_attempt.version+CASE WHEN (NOT payment_attempt.captured AND (payment_attempt.provider_status,payment_attempt.captured,payment_attempt.amount_captured_minor) IS DISTINCT FROM (excluded.provider_status,excluded.captured,excluded.amount_captured_minor)) OR (excluded.balance_transaction_id IS NOT NULL AND payment_attempt.balance_transaction_id IS DISTINCT FROM excluded.balance_transaction_id) THEN 1 ELSE 0 END,updated_at=now() WHERE payment_attempt.payment_id=excluded.payment_id AND payment_attempt.currency=excluded.currency AND payment_attempt.payment_intent_id=excluded.payment_intent_id AND payment_attempt.amount_minor=excluded.amount_minor",PaymentIds.newId(),p.get("organization_id"),p.get("environment_id"),p.get("application_id"),p.get("provider_account_id"),p.get("currency"),payment,charge.chargeId(),charge.status(),charge.paymentIntentId(),charge.captured(),charge.amountMinor(),charge.amountCapturedMinor(),charge.balanceTransactionId(),charge.failureCode(),charge.createdAt()==null?null:Timestamp.from(charge.createdAt()),Timestamp.from(facts.confirmedAt()));
            if(written==0)conflict=true;
        }
        long captured=jdbc.queryForObject("SELECT coalesce(sum(amount_captured_minor),0) FROM platform.payment_attempt WHERE payment_id=? AND captured AND provider_status='succeeded'",Long.class,payment);
        String state=str(p,"payment_status"),providerStatus=str(p,"provider_status"),financial=str(p,"financial_status");
        if(captured>0){
            if(state.equals("CANCELED"))conflict=true;else state="SUCCEEDED";
            long covered=jdbc.queryForObject("""
                    SELECT coalesce(sum(amount_minor),0) FROM platform.balance_transaction
                    WHERE payment_id=? AND state='POSTED' AND lower(business_type)='charge'
                    """,Long.class,payment);
            if("CONFLICT".equals(financial)||covered>captured)financial="CONFLICT";
            else if(covered==captured)financial="POSTED";
            else financial=covered>0?"PARTIALLY_POSTED":"PENDING_EVIDENCE";
            if(captured>number(p,"amount_minor"))conflict=true;
        }
        else if(!stale && !Set.of("SUCCEEDED","CANCELED").contains(state)){
            if(facts.intent()!=null){providerStatus=facts.intent().status();state=switch(providerStatus){case "requires_action"->"REQUIRES_ACTION";case "requires_payment_method"->facts.session()!=null && facts.session().status()==CheckoutSessionStatus.OPEN?"AWAITING_CUSTOMER":"PROCESSING";default->"PROCESSING";};}
            else if(facts.session()!=null){providerStatus=sessionStatus(facts.session());state=facts.session().status()==CheckoutSessionStatus.OPEN?"AWAITING_CUSTOMER":"PROCESSING";}
        }
        if(query && !stale && !conflict && captured==0 && !"SUCCEEDED".equals(state) && ProviderQueryService.unpaid(facts)
                && facts.session().status()==CheckoutSessionStatus.EXPIRED)state="CANCELED";
        if(!stale && facts.intent()!=null)providerStatus=facts.intent().status();
        var session=stale?null:facts.session();var intent=stale?null:facts.intent();
        String resolution=conflict?"CONFLICT":stale?str(p,"resolution_status"):facts.complete()?"KNOWN":"UNKNOWN";
        jdbc.update("UPDATE platform.payment SET payment_status=?,provider_status=?,resolution_status=?,unresolved_since=CASE WHEN ?='KNOWN' THEN NULL ELSE coalesce(unresolved_since,?) END,financial_status=?,captured_amount_minor=greatest(captured_amount_minor,?),checkout_session_id=coalesce(checkout_session_id,?),payment_intent_id=coalesce(payment_intent_id,?),latest_charge_id=coalesce(?,latest_charge_id),hosted_url=CASE WHEN ? THEN ? ELSE hosted_url END,last_provider_confirmed_at=CASE WHEN ? THEN ? ELSE last_provider_confirmed_at END,version=version+1,updated_at=now() WHERE id=? AND version=?",state,providerStatus,resolution,resolution,Timestamp.from(now()),financial,captured,observedSession,observedIntent,intent==null?null:intent.latestChargeId(),session!=null && (query || session.hostedUrl()!=null),session==null?null:session.hostedUrl(),!stale&&facts.complete()&&!facts.conflict(),Timestamp.from(facts.confirmedAt()),payment,number(p,"version"));
        if(query && !stale && facts.complete() && !facts.conflict())jdbc.update("UPDATE platform.payment SET last_query_confirmed_at=? WHERE id=?",Timestamp.from(facts.confirmedAt()),payment);
        if(!query && !stale)jdbc.update("UPDATE platform.payment SET last_webhook_created_at=? WHERE id=?",Timestamp.from(facts.confirmedAt()),payment);
        return !stale && "KNOWN".equals(resolution);
    }
    void finishCancel(Claim claim,List<Observation> observations,Facts facts,boolean canceled){transaction(()->{
        lockPayment(claim.paymentId());
        observations(claim.operationId(),claim.paymentId(),claim.token(),observations);if(!owns(claim))return null;
        if(observations.stream().anyMatch(o->o.type().startsWith("QUERY_") || o.type().equals("LIST_CHARGE_ATTEMPTS")))
            jdbc.update("UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",Timestamp.from(now()),claim.operationId());
        boolean applicable=merge(claim.paymentId(),facts);
        var p=one("SELECT * FROM platform.payment WHERE id=? FOR UPDATE",claim.paymentId());
        boolean captured=number(p,"captured_amount_minor")>0;
        boolean conflict="CONFLICT".equals(p.get("resolution_status")) || facts.conflict();
        if(canceled && applicable && !captured && !conflict && facts.complete())jdbc.update("UPDATE platform.payment SET payment_status='CANCELED',resolution_status='KNOWN',unresolved_since=null,version=version+1,updated_at=now() WHERE id=? AND version=?",claim.paymentId(),number(p,"version"));
        if(canceled && captured)markUnknown(claim.paymentId(),true);
        boolean resolved=applicable && facts.complete() && !conflict && (captured || canceled);
        completeOperation(claim,resolved?"RESOLVED":"UNKNOWN",resolved?"KNOWN":captured||conflict?"CONFLICT":"UNKNOWN",str(p,"checkout_session_id"),str(p,"provider_status"));
        return null;
    });}
    boolean cancelUnsent(Claim claim){return transaction(()->{
        lockPayment(claim.paymentId());
        if(!owns(claim))return false;
        var payment=one("SELECT * FROM platform.payment WHERE id=?",claim.paymentId());
        if("CONFLICT".equals(payment.get("resolution_status")) || payment.get("payment_intent_id")!=null || number(payment,"captured_amount_minor")>0)return false;
        var create=one("SELECT * FROM platform.provider_operation WHERE payment_id=? AND operation_type='CREATE_CHECKOUT' FOR UPDATE",claim.paymentId());
        if(!"PREPARED".equals(create.get("status")))return false;
        jdbc.update("UPDATE platform.provider_operation SET status='RESOLVED',version=version+1,updated_at=now() WHERE id=? AND status='PREPARED'",create.get("id"));
        jdbc.update("UPDATE platform.recovery_task SET status='DONE',version=version+1 WHERE operation_id=?",create.get("id"));
        jdbc.update("UPDATE platform.payment SET payment_status='CANCELED',resolution_status='KNOWN',unresolved_since=null,version=version+1,updated_at=now() WHERE id=?",claim.paymentId());
        completeOperation(claim,"RESOLVED","KNOWN",null,null);return true;
    });}
    List<UUID> due(int limit,String provider){
        if(provider==null)return List.of();
        return transaction(()->jdbc.queryForList("""
            SELECT o.id FROM platform.provider_operation o JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config e ON e.id=o.environment_id AND e.organization_id=o.organization_id
            JOIN platform.application a ON a.id=o.application_id AND a.organization_id=o.organization_id AND a.environment_id=o.environment_id
            JOIN platform.provider_account p ON p.id=o.provider_account_id AND p.organization_id=o.organization_id AND p.environment_id=o.environment_id
            JOIN platform.org_currency_enabled c ON c.organization_id=o.organization_id AND c.environment_id=o.environment_id AND c.currency=o.currency
            LEFT JOIN platform.recovery_account_limit lim ON lim.organization_id=o.organization_id AND lim.environment_id=o.environment_id AND lim.provider_account_id=o.provider_account_id
            WHERE t.status IN ('READY','WAITING','RUNNING') AND t.available_at<=? AND (o.lease_until IS NULL OR o.lease_until<=?)
              AND o.status<>'ESCALATED' AND org.status='ACTIVE' AND e.status='ACTIVE' AND a.status='ACTIVE' AND p.status='ACTIVE' AND c.status='ACTIVE'
              AND NOT e.livemode AND NOT p.livemode AND p.provider=? AND (lim.next_batch_at IS NULL OR lim.next_batch_at<=?)
              AND (?<>'STRIPE' OR (p.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
            ORDER BY t.available_at,o.id LIMIT ?
            """,UUID.class,Timestamp.from(now()),Timestamp.from(now()),provider,Timestamp.from(now()),provider,configured("account-id","STRIPE_ACCOUNT_ID"),configured("organization-id","STRIPE_ORGANIZATION_ID"),configured("environment-id","STRIPE_ENVIRONMENT_ID"),configured("provider-account-id","STRIPE_PROVIDER_ACCOUNT_ID"),Math.min(100,Math.max(1,limit))));
    }
    String recoveryAnchor(Claim claim){
        var anchors=new LinkedHashSet<String>();
        String bound=str(claim.payment(),"checkout_session_id");if(bound!=null)anchors.add(bound);
        String external=str(claim.operation(),"external_object_id");if(external!=null && external.startsWith("cs_"))anchors.add(external);
        anchors.addAll(transaction(()->jdbc.queryForList("""
            SELECT DISTINCT external_object_id FROM platform.provider_observation WHERE operation_id=? AND payment_id=?
             AND organization_id=? AND environment_id=? AND provider_account_id=?
             AND call_type IN ('CREATE_SESSION','QUERY_SESSION','EXPIRE_SESSION') AND left(external_object_id,3)='cs_'
             AND classification IN ('SUCCESS','AUTH_REQUIRED')
            """,String.class,claim.operationId(),claim.paymentId(),claim.operation().get("organization_id"),claim.operation().get("environment_id"),claim.operation().get("provider_account_id"))));
        if(anchors.size()>1)return "CONFLICTING_SESSION_ANCHORS";
        return anchors.isEmpty()?null:anchors.iterator().next();
    }
    void finishRecoveryQuery(Claim claim,ProviderQueryService.Queried query){transaction(()->{
        lockPayment(claim.paymentId());observations(claim.operationId(),claim.paymentId(),claim.token(),query.observations());if(!owns(claim))return null;
        boolean applicable=merge(claim.paymentId(),query.facts());var p=one("SELECT * FROM platform.payment WHERE id=?",claim.paymentId());
        jdbc.update("UPDATE platform.provider_operation SET last_query_at=? WHERE id=?",Timestamp.from(now()),claim.operationId());
        String resolution=str(p,"resolution_status");
        Instant unresolvedSince=instant(p,"unresolved_since");
        if(!"KNOWN".equals(resolution) && unresolvedSince!=null && !unresolvedSince.plus(recoveryPolicy.unresolvedAge()).isAfter(now())){
            alertAgeOwned(claim);escalateOwned(claim,"UNRESOLVED_AGE_LIMIT");return null;
        }
        completeOperation(claim,applicable?"RESOLVED":"UNKNOWN",resolution,str(p,"checkout_session_id"),str(p,"provider_status"));return null;
    });}
    void conflictingAnchor(Claim claim){transaction(()->{lockPayment(claim.paymentId());if(owns(claim))markUnknown(claim.paymentId(),true);return null;});}
    void escalate(Claim claim,String reason){transaction(()->{
        lockPayment(claim.paymentId());if(owns(claim))escalateOwned(claim,reason);return null;
    });}
    private void escalateOwned(Claim claim,String reason){
        markUnknown(claim.paymentId(),"CONFLICT".equals(claim.payment().get("resolution_status")));
        jdbc.update("UPDATE platform.provider_operation SET status='ESCALATED',resolution=(SELECT resolution_status FROM platform.payment WHERE id=provider_operation.payment_id),escalation_reason=?,escalated_at=coalesce(escalated_at,?),lease_until=null,version=version+1,updated_at=? WHERE id=?",reason,Timestamp.from(now()),Timestamp.from(now()),claim.operationId());
        jdbc.update("UPDATE platform.recovery_task SET status='ESCALATED',reason=?,escalated_at=coalesce(escalated_at,?),lease_until=null,lease_token=null,version=version+1,updated_at=? WHERE operation_id=?",reason,Timestamp.from(now()),Timestamp.from(now()),claim.operationId());
        org.slf4j.LoggerFactory.getLogger(PaymentRepository.class).warn("Payment recovery escalated operation={} payment={} reason={}",claim.operationId(),claim.paymentId(),reason);
    }
    void alertAge(Claim claim){transaction(()->{
        lockPayment(claim.paymentId());if(owns(claim))alertAgeOwned(claim);return null;
    });}
    private void alertAgeOwned(Claim claim){
        jdbc.update("UPDATE platform.provider_operation SET age_alert_at=coalesce(age_alert_at,?) WHERE id=?",Timestamp.from(now()),claim.operationId());
        jdbc.update("UPDATE platform.recovery_task SET age_alert_at=coalesce(age_alert_at,?) WHERE operation_id=?",Timestamp.from(now()),claim.operationId());
    }
    int wakeL2(int limit,String provider){
        if(provider==null)return 0;
        var ids=transaction(()->jdbc.queryForList("""
            SELECT o.id FROM platform.provider_operation o JOIN platform.payment p ON p.id=o.payment_id JOIN platform.recovery_task t ON t.operation_id=o.id
            JOIN platform.provider_account a ON a.id=o.provider_account_id
            JOIN platform.application app ON app.id=o.application_id
            JOIN platform.organization org ON org.id=o.organization_id
            JOIN platform.environment_config env ON env.id=o.environment_id
            JOIN platform.org_currency_enabled cur ON cur.organization_id=o.organization_id AND cur.environment_id=o.environment_id AND cur.currency=o.currency
            WHERE a.status='ACTIVE' AND app.status='ACTIVE' AND org.status='ACTIVE' AND env.status='ACTIVE' AND cur.status='ACTIVE' AND NOT a.livemode AND NOT env.livemode AND a.provider=?
             AND (?<>'STRIPE' OR (a.provider_account_ref=? AND o.organization_id::text=? AND o.environment_id::text=? AND o.provider_account_id::text=?))
             AND o.status<>'ESCALATED' AND (o.lease_until IS NULL OR o.lease_until<=?)
             AND (p.resolution_status<>'KNOWN' OR p.payment_status NOT IN ('SUCCEEDED','CANCELED') OR p.financial_status='PENDING_EVIDENCE')
             AND (t.status='DONE' OR t.available_at<=?) ORDER BY t.available_at,o.id LIMIT ?
            """,UUID.class,provider,provider,configured("account-id","STRIPE_ACCOUNT_ID"),configured("organization-id","STRIPE_ORGANIZATION_ID"),configured("environment-id","STRIPE_ENVIRONMENT_ID"),configured("provider-account-id","STRIPE_PROVIDER_ACCOUNT_ID"),Timestamp.from(now()),Timestamp.from(now()),Math.min(100,Math.max(1,limit))));
        for(UUID id:ids)transaction(()->{
            var ref=one("SELECT payment_id FROM platform.provider_operation WHERE id=?",id);lockPayment(uuid(ref,"payment_id"));
            var op=one("SELECT * FROM platform.provider_operation WHERE id=? FOR UPDATE",id);
            if(!"ESCALATED".equals(op.get("status")) && (instant(op,"lease_until")==null || !instant(op,"lease_until").isAfter(now())))
                jdbc.update("UPDATE platform.recovery_task SET status='WAITING',available_at=least(available_at,?),version=version+1 WHERE operation_id=? AND status<>'ESCALATED'",Timestamp.from(now()),id);
            return null;
        });return ids.size();
    }
    private Map<String,Object> recoverySummary(UUID payment){
        var rows=jdbc.queryForList("SELECT t.*,o.last_query_at,o.status operation_status FROM platform.recovery_task t JOIN platform.provider_operation o ON o.id=t.operation_id WHERE t.payment_id=? ORDER BY CASE t.status WHEN 'ESCALATED' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'WAITING' THEN 2 WHEN 'READY' THEN 2 ELSE 3 END,t.available_at",payment);
        var result=new LinkedHashMap<String,Object>();String status="IDLE";Instant next=null,last=null;int attempts=0;String reason=null;
        for(var row:rows){attempts+=((Number)row.get("attempt_count")).intValue();Instant queried=instant(row,"last_query_at");if(queried!=null&&(last==null||queried.isAfter(last)))last=queried;}
        if(!rows.isEmpty()){var row=rows.getFirst();status=switch(str(row,"status")){case "ESCALATED"->"ESCALATED";case "RUNNING"->"RUNNING";case "WAITING","READY"->"WAITING";default->"IDLE";};if(status.equals("RUNNING") && (instant(row,"lease_until")==null || !instant(row,"lease_until").isAfter(now())))status="WAITING";if(status.equals("WAITING"))next=instant(row,"available_at");reason=str(row,"reason");}
        result.put("status",status);result.put("nextAttemptAt",next==null?null:next.toString());result.put("attemptCount",attempts);result.put("lastQueryAt",last==null?null:last.toString());result.put("reason",reason);return result;
    }
    private Map<String,Object> resource(Map<String,Object> p){
        var result=new LinkedHashMap<String,Object>();
        result.put("recovery",recoverySummary(uuid(p,"id")));
        result.put("id",PaymentIds.format("pay",uuid(p,"id")));result.put("businessOrderId",PaymentIds.format("bor",uuid(p,"business_order_id")));result.put("orderKey",p.get("order_key"));
        result.put("amountMinor",number(p,"amount_minor"));result.put("currency",p.get("currency"));result.put("paymentStatus",p.get("payment_status"));result.put("providerStatus",p.get("provider_status"));result.put("resolutionStatus",p.get("resolution_status"));result.put("financialStatus",p.get("financial_status"));result.put("capturedAmountMinor",number(p,"captured_amount_minor"));result.put("revenueShares",List.of());
        result.put("createdAt",instant(p,"created_at").toString());result.put("updatedAt",instant(p,"updated_at").toString());result.put("lastProviderConfirmedAt",instant(p,"last_provider_confirmed_at")==null?null:instant(p,"last_provider_confirmed_at").toString());
        var refs=new LinkedHashMap<String,Object>();refs.put("checkoutSessionId",p.get("checkout_session_id"));refs.put("paymentIntentId",p.get("payment_intent_id"));refs.put("latestChargeId",p.get("latest_charge_id"));
        var attempts=new ArrayList<Map<String,Object>>();for(var a:jdbc.queryForList("SELECT * FROM platform.payment_attempt WHERE payment_id=? ORDER BY provider_created_at,id",p.get("id"))){
            var view=new LinkedHashMap<String,Object>();view.put("attemptId",PaymentIds.format("att",uuid(a,"id")));view.put("providerObjectType","CHARGE");view.put("providerObjectId",a.get("provider_object_id"));view.put("providerStatus",a.get("provider_status"));view.put("failureCode",a.get("failure_code"));view.put("captured",a.get("captured"));view.put("amountMinor",a.get("amount_minor"));view.put("balanceTransactionId",a.get("balance_transaction_id"));view.put("createdAt",instant(a,"provider_created_at")==null?null:instant(a,"provider_created_at").toString());attempts.add(view);
        }refs.put("attempts",attempts);result.put("provider",refs);
        String state=str(p,"payment_status");Map<String,Object> action;
        if(!"KNOWN".equals(p.get("resolution_status")))action=Map.of("type","WAIT_FOR_CONFIRMATION");
        else if(Set.of("SUCCEEDED","CANCELED").contains(state))action=Map.of("type","NONE");
        else if(p.get("hosted_url")!=null && Set.of("AWAITING_CUSTOMER","REQUIRES_ACTION").contains(state) && instant(p,"expires_at").isAfter(clock.instant()))action=Map.of("type","REDIRECT_TO_HOSTED_CHECKOUT","hostedUrl",p.get("hosted_url"),"expiresAt",instant(p,"expires_at").toString());
        else action=Map.of("type","WAIT_FOR_CONFIRMATION");result.put("nextAction",action);return result;
    }
}
