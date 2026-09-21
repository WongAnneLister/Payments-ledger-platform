package com.paymentsledger.provider.fake;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.fake.FailureScript.*;
import static com.paymentsledger.provider.fake.FakeStateStore.JSON;

/** Deterministic local remote-provider model. No network, business tables or ledger dependencies. */
public final class FakeStripeProvider implements CheckoutPort, PaymentIntentPort, ChargePort, RefundPort,
        BalanceTransactionPort, DisputePort, PayoutPort, BalancePort, ReportPort {
    public static final String API_VERSION="FAKE-PR04-v1";
    final FakeStateStore store;
    private final PersistentFakeClock clock;
    private final FakeControls controls;
    public FakeStripeProvider(Path directory,long seed,Instant initialTime,Duration retention) {
        store=new FakeStateStore(directory,seed,initialTime,retention);
        clock=new PersistentFakeClock(store); controls=new FakeControls(store);
    }
    public PersistentFakeClock clock() { return clock; }
    public FakeControls controls() { return controls; }
    public ProviderCounters counters(ProviderScope scope) { return store.update(root -> new FakeState(root,scope).counters()); }
    public static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash() { super("PROVIDER_SIMULATOR: remote commit completed before caller received a response"); }
    }
    private record Reply(ProviderCallClassification classification,JsonNode payload) {}
    private static Reply success(Object payload) { return new Reply(SUCCESS,JSON.valueToTree(payload)); }
    private static Reply reject() { return new Reply(DEFINITIVE_REJECT,null); }
    private static Reply lookup(FakeState state,String kind,String id) {
        JsonNode node=state.find(kind,id);
        return node!=null?success(node):new Reply(state.objects(kind).has(id)?TRANSIENT_EMPTY:NOT_FOUND,null);
    }

    private <T> ProviderCallResult<T> call(ProviderScope scope,Operation operation,String target,Object request,
                                          boolean keyed,boolean write,JavaType type,Function<FakeState,Reply> action) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider calls must run outside local database transactions");
        Objects.requireNonNull(scope); Objects.requireNonNull(target);
        ObjectNode result=store.update(root -> {
            var state=new FakeState(root,scope); state.increment("requestCount");
            String requestId=state.id("req");
            JsonNode parameters=FakeState.canonical(JSON.valueToTree(request));
            String cacheKey=operation.name()+":"+target;
            ObjectNode cache=state.map("cache"); JsonNode prior=cache.get(cacheKey);
            boolean cached=keyed && prior!=null && Instant.parse(prior.path("expiresAt").asText()).isAfter(state.now);
            Outcome script=null; Reply reply;
            if(scope.livemode()) reply=reject();
            else if(cached) {
                reply=FakeState.canonical(prior.path("parameters")).equals(parameters)
                        ? new Reply(ProviderCallClassification.valueOf(prior.path("classification").asText()),prior.get("payload"))
                        : new Reply(IDEMPOTENCY_CONFLICT,null);
            } else {
                ArrayNode scripts=state.array("scripts");
                for(int i=0;i<scripts.size();i++) {
                    JsonNode item=scripts.get(i);
                    if(item.path("operation").asText().equals(operation.name()) && item.path("target").asText().equals(target)) {
                        script=Outcome.valueOf(item.path("outcome").asText()); scripts.remove(i); break;
                    }
                }
                reply=switch(script==null?"NORMAL":script.name()) {
                    case "BEFORE_SEND_FAILURE", "TRANSIENT_ERROR", "CACHED_TRANSIENT_ERROR" -> new Reply(RETRYABLE_TRANSIENT,null);
                    case "TRANSIENT_EMPTY" -> new Reply(TRANSIENT_EMPTY,null);
                    case "RATE_LIMIT" -> new Reply(RATE_LIMITED,null);
                    case "REJECT" -> reject();
                    case "CONTRACT_MISMATCH" -> new Reply(CONTRACT_MISMATCH,null);
                    default -> action.apply(state);
                };
                if(write && reply.classification()==SUCCESS) state.increment("acceptedCount");
                if(script==Outcome.AUTH_REQUIRED && reply.classification()==SUCCESS && operation==Operation.CREATE_SESSION) {
                    String piId=reply.payload().path("paymentIntentId").asText();
                    state.require("paymentIntents",piId).put("status","requires_action").put("nextActionType","use_fake_sdk");
                    reply=new Reply(AUTH_REQUIRED,reply.payload());
                }
                if(keyed && (reply.classification()==SUCCESS || reply.classification()==AUTH_REQUIRED || script==Outcome.CACHED_TRANSIENT_ERROR)) {
                    ObjectNode saved=cache.putObject(cacheKey); saved.set("parameters",parameters);
                    saved.put("classification",reply.classification().name()); saved.set("payload",reply.payload());
                    saved.put("expiresAt",state.now.plus(Duration.parse(root.path("retention").asText())).toString());
                }
            }
            boolean lost=(script==Outcome.RESPONSE_LOST || script==Outcome.CRASH_AFTER_COMMIT) && (reply.classification()==SUCCESS || reply.classification()==AUTH_REQUIRED);
            if(lost) reply=new Reply(TIMEOUT_UNKNOWN,null);
            boolean before=script==Outcome.BEFORE_SEND_FAILURE;
            Integer status=lost||before?null:switch(reply.classification()) {
                case SUCCESS,AUTH_REQUIRED -> 200; case NOT_FOUND -> 404; case RATE_LIMITED -> 429;
                case RETRYABLE_TRANSIENT -> 503; case IDEMPOTENCY_CONFLICT -> 409; default -> 400;
            };
            String error=reply.classification()==SUCCESS||reply.classification()==AUTH_REQUIRED?null:"fake_"+reply.classification().name().toLowerCase(Locale.ROOT);
            ObjectNode digestInput=JSON.createObjectNode().put("classification",reply.classification().name()); digestInput.set("payload",reply.payload());
            var evidence=new ProviderEvidence(before?null:requestId,keyed?target:null,state.now,lost||before?null:state.now,status,error,FakeState.digest(digestInput),API_VERSION);
            ObjectNode output=JSON.createObjectNode().put("classification",reply.classification().name());
            output.set("payload",reply.payload()); output.set("evidence",JSON.valueToTree(evidence));
            ObjectNode log=output.deepCopy(); log.put("operation",operation.name()).put("target",target).put("replayed",cached);
            state.array("calls").add(log);
            output.put("crash",lost && script==Outcome.CRASH_AFTER_COMMIT);
            return output;
        });
        if(result.path("crash").asBoolean()) throw new SimulatedCrash();
        JsonNode payload=result.get("payload");
        return new ProviderCallResult<>(ProviderCallClassification.valueOf(result.path("classification").asText()),
                payload==null||payload.isNull()?null:JSON.convertValue(payload,type),JSON.convertValue(result.get("evidence"),ProviderEvidence.class));
    }
    private <T> ProviderCallResult<T> call(ProviderScope scope,Operation operation,String target,Object request,
                                          boolean keyed,boolean write,Class<T> type,Function<FakeState,Reply> action) {
        return call(scope,operation,target,request,keyed,write,JSON.constructType(type),action);
    }
    @Override public ProviderCallResult<CheckoutSessionFact> createSession(CreateCheckoutSessionCommand command) {
        return call(command.scope(),Operation.CREATE_SESSION,command.operationIdempotencyKey(),command,true,true,CheckoutSessionFact.class,s -> {
            String session=s.id("cs"),pi=s.id("pi");
            var intent=new PaymentIntentFact(pi,"requires_payment_method",command.amountMinor(),0L,command.currency(),List.of(),null,null,command.paymentIntentMetadata(),s.now);
            var fact=new CheckoutSessionFact(session,CheckoutSessionStatus.OPEN,"https://checkout.fake.invalid/"+session,pi,"unpaid",s.now.plus(command.expiresAfter()),command.sessionMetadata());
            s.put("paymentIntents",pi,intent,s.now); s.put("sessions",session,fact,s.now); s.increment("sessionCreatedCount");
            return success(fact);
        });
    }
    @Override public ProviderCallResult<CheckoutSessionFact> querySession(ProviderScope scope,String id) {
        return call(scope,Operation.QUERY_SESSION,id,id,false,false,CheckoutSessionFact.class,s -> { s.expireDue(id);return lookup(s,"sessions",id); });
    }
    @Override public ProviderCallResult<CheckoutSessionFact> expireSession(ProviderScope scope,String id) {
        return call(scope,Operation.EXPIRE_SESSION,id,id,false,true,CheckoutSessionFact.class,s -> {
            s.expireDue(id); JsonNode found=s.find("sessions",id);if(found==null)return new Reply(NOT_FOUND,null);
            var fact=(ObjectNode)found;
            if(!fact.path("status").asText().equals("OPEN")) return reject();
            fact.put("status","EXPIRED").putNull("hostedUrl");return success(fact);
        });
    }
    @Override public ProviderCallResult<PaymentIntentFact> queryPaymentIntent(ProviderScope scope,String id) {
        return call(scope,Operation.QUERY_PAYMENT_INTENT,id,id,false,false,PaymentIntentFact.class,s -> lookup(s,"paymentIntents",id));
    }
    @Override public ProviderCallResult<ChargeFact> queryCharge(ProviderScope scope,String id) {
        return call(scope,Operation.QUERY_CHARGE,id,id,false,false,ChargeFact.class,s -> lookup(s,"charges",id));
    }
    @Override public ProviderCallResult<List<ChargeFact>> listChargeAttempts(ProviderScope scope,String id) {
        return call(scope,Operation.LIST_CHARGE_ATTEMPTS,id,id,false,false,JSON.getTypeFactory().constructCollectionType(List.class,ChargeFact.class),s -> {
            JsonNode pi=s.find("paymentIntents",id); if(pi==null)return new Reply(NOT_FOUND,null);
            var list=new ArrayList<JsonNode>(); pi.path("chargeIds").forEach(c -> {var charge=s.find("charges",c.asText());if(charge!=null)list.add(charge);});
            return success(list);
        });
    }
    @Override public ProviderCallResult<RefundFact> createRefund(CreateRefundCommand command) {
        return call(command.scope(),Operation.CREATE_REFUND,command.operationIdempotencyKey(),command,true,true,RefundFact.class,s -> {
            String chargeId=command.chargeId();
            if(chargeId==null) { JsonNode pi=s.find("paymentIntents",command.paymentIntentId()); if(pi==null)return new Reply(NOT_FOUND,null); chargeId=pi.path("latestChargeId").asText(); }
            JsonNode charge=s.find("charges",chargeId);
            if(charge==null)return new Reply(NOT_FOUND,null);
            if(!charge.path("captured").asBoolean())return reject();
            long refunded=0;
            for(JsonNode wrapper:s.objects("refunds")) { JsonNode r=wrapper.path("fact");
                if(r.path("chargeId").asText().equals(chargeId) && !r.path("status").asText().equals("failed")) refunded=Math.addExact(refunded,r.path("amountMinor").asLong()); }
            if(command.amountMinor()>charge.path("amountCapturedMinor").asLong()-refunded)return reject();
            String id=s.id("re");
            var fact=new RefundFact(id,chargeId,"pending",command.amountMinor(),charge.path("currency").asText(),null,null,null,s.now);
            s.put("refunds",id,fact,s.now);s.effect("REFUND",id,command.amountMinor(),fact.currency());return success(fact);
        });
    }
    @Override public ProviderCallResult<RefundFact> queryRefund(ProviderScope scope,String id) {
        return call(scope,Operation.QUERY_REFUND,id,id,false,false,RefundFact.class,s -> lookup(s,"refunds",id));
    }
    @Override public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope scope,String id) {
        return call(scope,Operation.GET_BALANCE_TRANSACTION,id,id,false,false,BalanceTransactionFact.class,s -> lookup(s,"balanceTransactions",id));
    }
    @Override public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery query) {
        return call(query.scope(),Operation.LIST_BALANCE_TRANSACTIONS,"list",query,false,false,BalanceTransactionPage.class,s -> {
            if(query.payoutId()!=null) {
                JsonNode payout=s.find("payouts",query.payoutId());
                if(payout==null)return lookup(s,"payouts",query.payoutId());
                if(!payout.path("automatic").asBoolean())return reject();
            }
            return page(s,"balanceTransactions",query.createdGte(),query.createdLt(),query.startingAfterCursor(),query.limit(),query.payoutId());
        });
    }
    @Override public ProviderCallResult<Page<DisputeFact>> listDisputes(PageQuery query) {
        return call(query.scope(),Operation.LIST_DISPUTES,"list",query,false,false,JSON.getTypeFactory().constructParametricType(Page.class,DisputeFact.class),
                s -> page(s,"disputes",query.createdGte(),query.createdLt(),query.startingAfterCursor(),query.limit(),null));
    }
    @Override public ProviderCallResult<DisputeFact> getDispute(ProviderScope scope,String id) {
        return call(scope,Operation.GET_DISPUTE,id,id,false,false,DisputeFact.class,s -> lookup(s,"disputes",id));
    }
    @Override public ProviderCallResult<Page<PayoutFact>> listPayouts(PageQuery query) {
        return call(query.scope(),Operation.LIST_PAYOUTS,"list",query,false,false,JSON.getTypeFactory().constructParametricType(Page.class,PayoutFact.class),
                s -> page(s,"payouts",query.createdGte(),query.createdLt(),query.startingAfterCursor(),query.limit(),null));
    }
    @Override public ProviderCallResult<PayoutFact> getPayout(ProviderScope scope,String id) {
        return call(scope,Operation.GET_PAYOUT,id,id,false,false,PayoutFact.class,s -> lookup(s,"payouts",id));
    }
    private Reply page(FakeState state,String kind,Instant from,Instant to,String cursor,int limit,String payout) {
        String query=state.scopeKey+":"+kind+":"+from+":"+to+":"+payout;
        var rows=new ArrayList<Map.Entry<String,JsonNode>>();
        state.objects(kind).fields().forEachRemaining(e -> {
            JsonNode w=e.getValue(); Instant created=Instant.parse(w.path("createdAt").asText(w.path("fact").path("createdAt").asText()));
            if(!created.isBefore(from) && created.isBefore(to) && !Instant.parse(w.path("visibleAt").asText()).isAfter(state.now)
                    && (payout==null || payout.equals(w.path("payoutId").asText()))) rows.add(e);
        });
        rows.sort(Comparator.comparing((Map.Entry<String,JsonNode> e) -> Instant.parse(e.getValue().path("createdAt").asText(e.getValue().path("fact").path("createdAt").asText()))).thenComparing(Map.Entry::getKey));
        int start=0;
        if(cursor!=null) {
            JsonNode anchor=state.objects("cursors").get(cursor);
            if(anchor==null || !anchor.path("fact").path("query").asText().equals(query))return reject();
            String id=anchor.path("fact").path("id").asText();
            int index=-1;for(int i=0;i<rows.size();i++)if(rows.get(i).getKey().equals(id))index=i;
            if(index<0)return reject(); start=index+1;
        }
        int end=start+Math.min(rows.size()-start,limit);ArrayNode items=JSON.createArrayNode();
        for(int i=start;i<end;i++) items.add(rows.get(i).getValue().path("fact"));
        String next=null; boolean more=end<rows.size();
        if(more) {
            next="cursor_"+FakeState.digest(JSON.createObjectNode().put("query",query).put("id",rows.get(end-1).getKey()));
            state.put("cursors",next,JSON.createObjectNode().put("query",query).put("id",rows.get(end-1).getKey()),state.now);
        }
        ObjectNode result=JSON.createObjectNode();result.set("items",items);result.put("hasMore",more);result.put("nextCursor",next);return success(result);
    }
    @Override public ProviderCallResult<BalanceSnapshot> getBalance(ProviderScope scope,String currency,String domain) {
        String key=currency+":"+domain;
        return call(scope,Operation.GET_BALANCE,key,key,false,false,BalanceSnapshot.class,s -> {
            JsonNode node=s.find("balances",key); if(node==null)return new Reply(CAPABILITY_UNAVAILABLE,null);
            ObjectNode snapshot=node.deepCopy();snapshot.put("capturedAt",s.now.toString());return success(snapshot);
        });
    }
    @Override public ProviderCallResult<ReportFact> requestReport(ReportRequest request) {
        return call(request.scope(),Operation.REQUEST_REPORT,request.reportType(),request,false,false,ReportFact.class,s -> {
            JsonNode node=s.find("reports",reportKey(request));return node==null?new Reply(CAPABILITY_UNAVAILABLE,null):success(node);
        });
    }
    @Override public ProviderCallResult<ReportFact> fetchReport(ProviderScope scope,String id) {
        return call(scope,Operation.FETCH_REPORT,id,id,false,false,ReportFact.class,s -> {
            JsonNode node=s.find("reportIds",id);return node==null?new Reply(CAPABILITY_UNAVAILABLE,null):success(node);
        });
    }
    static String reportKey(ReportRequest request) { return request.reportType()+":"+request.createdGte()+":"+request.createdLt(); }
}
