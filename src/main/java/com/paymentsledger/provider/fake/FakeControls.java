package com.paymentsledger.provider.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.paymentsledger.provider.fake.FakeStateStore.JSON;

/** Explicit fixture controls. Exposed only as Java API; never registered as an HTTP controller. */
public final class FakeControls {
    public enum AttemptOutcome { REQUIRE_AUTH, DECLINE, SUCCEED }
    public record WebhookDelivery(String eventId,String rawBody,Instant deliveryAt) {}
    private final FakeStateStore store;
    FakeControls(FakeStateStore store) { this.store=store; }
    private <T> T edit(ProviderScope scope,Function<FakeState,T> operation) {
        if(scope.livemode())throw new IllegalArgumentException("Fake fixtures cannot be live");
        return store.update(root -> operation.apply(new FakeState(root,scope)));
    }
    public void enqueue(ProviderScope scope,FailureScript.Operation operation,String target,FailureScript.Outcome outcome) {
        var script=new FailureScript(operation,target,outcome);
        if(outcome==FailureScript.Outcome.AUTH_REQUIRED && operation!=FailureScript.Operation.CREATE_SESSION)
            throw new IllegalArgumentException("AUTH_REQUIRED script targets CREATE_SESSION; subsequent customer actions use attemptPayment");
        if(outcome==FailureScript.Outcome.CACHED_TRANSIENT_ERROR && operation!=FailureScript.Operation.CREATE_SESSION && operation!=FailureScript.Operation.CREATE_REFUND)
            throw new IllegalArgumentException("Cached outcomes require an idempotency-keyed write");
        edit(scope,s -> { s.array("scripts").add(JSON.valueToTree(script));return null; });
    }
    public List<JsonNode> calls(ProviderScope scope) { return edit(scope,s -> copy(s.array("calls"))); }
    public List<JsonNode> effects(ProviderScope scope) { return edit(scope,s -> copy(s.array("effects"))); }
    private List<JsonNode> copy(ArrayNode array) { var result=new ArrayList<JsonNode>();array.forEach(n -> result.add(n.deepCopy()));return List.copyOf(result); }

    public PaymentIntentFact attemptPayment(ProviderScope scope,String sessionId,AttemptOutcome outcome) {
        Objects.requireNonNull(outcome);
        return edit(scope,s -> {
            s.expireDue(sessionId); ObjectNode session=s.require("sessions",sessionId);
            String piId=session.path("paymentIntentId").asText();ObjectNode pi=s.require("paymentIntents",piId);
            if(session.path("status").asText().equals("COMPLETE"))return JSON.convertValue(pi,PaymentIntentFact.class);
            if(session.path("status").asText().equals("EXPIRED"))throw new IllegalStateException("Expired Checkout cannot capture");
            if(outcome==AttemptOutcome.REQUIRE_AUTH) {
                pi.put("status","requires_action").put("nextActionType","use_fake_sdk");
            } else {
                boolean success=outcome==AttemptOutcome.SUCCEED;String chargeId=s.id("ch");long amount=pi.path("amountMinor").asLong();
                var charge=new ChargeFact(chargeId,piId,success?"succeeded":"failed",success,amount,success?amount:0L,
                        pi.path("currency").asText(),null,success?null:"card_declined",success?"authorized":"issuer_declined",s.now);
                s.put("charges",chargeId,charge,s.now);
                ((ArrayNode)pi.path("chargeIds")).add(chargeId); pi.put("latestChargeId",chargeId).putNull("nextActionType");
                pi.put("status",success?"succeeded":"requires_payment_method");
                if(success) {
                    pi.put("amountReceivedMinor",amount);session.put("status","COMPLETE").put("paymentStatus","paid").putNull("hostedUrl");
                    s.effect("PAYMENT",chargeId,amount,charge.currency());
                }
            }
            return JSON.convertValue(pi,PaymentIntentFact.class);
        });
    }
    public void setRefundStatus(ProviderScope scope,String refundId,String status,String reason) {
        if(!Set.of("pending","succeeded","failed").contains(status))throw new IllegalArgumentException("Unsupported refund fixture status");
        edit(scope,s -> {
            ObjectNode refund=s.require("refunds",refundId);String prior=refund.path("status").asText();
            if(!prior.equals("pending") && !prior.equals(status))throw new IllegalStateException("Refund terminal state cannot be replaced");
            refund.put("status",status);refund.put("failureReason",status.equals("failed")?Objects.requireNonNull(reason):null);return null;
        });
    }
    public void linkChargeBalanceTransaction(ProviderScope scope,String chargeId,String btId) {
        edit(scope,s -> { link(s.require("charges",chargeId),"balanceTransactionId",btId);return null; });
    }
    public void linkRefundTransactions(ProviderScope scope,String refundId,String debitId,String returnId) {
        edit(scope,s -> {
            ObjectNode refund=s.require("refunds",refundId);
            if(debitId!=null)link(refund,"balanceTransactionId",debitId);
            if(returnId!=null) {
                boolean first=refund.path("failureBalanceTransactionId").isNull();
                link(refund,"failureBalanceTransactionId",returnId);
                if(first)s.effect("REFUND_RETURN",returnId,refund.path("amountMinor").asLong(),refund.path("currency").asText());
            }
            return null;
        });
    }
    private static void link(ObjectNode fact,String key,String id) {
        if(id==null||id.isBlank())throw new IllegalArgumentException("External reference required");
        if(fact.hasNonNull(key) && !fact.path(key).asText().equals(id))throw new IllegalStateException("Fixture reference conflict");
        fact.put(key,id);
    }
    public void putBalanceTransaction(ProviderScope scope,BalanceTransactionFact fact,Instant visibleAt,String payoutId) {
        edit(scope,s -> {
            JsonNode prior=s.objects("balanceTransactions").get(fact.btId());
            if(prior!=null) {
                ObjectNode before=prior.path("fact").deepCopy(),after=JSON.valueToTree(fact);
                before.remove(List.of("status","availableOn","rawDigest"));after.remove(List.of("status","availableOn","rawDigest"));
                if(!FakeState.canonical(before).equals(FakeState.canonical(after)))throw new IllegalStateException("Immutable BT evidence conflict");
                if(prior.path("fact").path("status").asText().equals("available") && !fact.status().equals("available"))throw new IllegalStateException("Availability cannot regress");
                if(!Objects.equals(prior.path("payoutId").isNull()?null:prior.path("payoutId").asText(),payoutId))throw new IllegalStateException("Payout membership conflict");
            }
            s.put("balanceTransactions",fact.btId(),fact,Objects.requireNonNull(visibleAt));
            ((ObjectNode)s.objects("balanceTransactions").get(fact.btId())).put("payoutId",payoutId);return null;
        });
    }
    public void putDispute(ProviderScope scope,DisputeFact fact,Instant createdAt,Instant visibleAt) {
        edit(scope,s -> { s.put("disputes",fact.disputeId(),fact,visibleAt);((ObjectNode)s.objects("disputes").get(fact.disputeId())).put("createdAt",createdAt.toString());return null; });
    }
    public void putPayout(ProviderScope scope,PayoutFact fact,Instant createdAt,Instant visibleAt) {
        edit(scope,s -> { s.put("payouts",fact.payoutId(),fact,visibleAt);((ObjectNode)s.objects("payouts").get(fact.payoutId())).put("createdAt",createdAt.toString());return null; });
    }
    public void putBalance(ProviderScope scope,BalanceSnapshot snapshot) {
        edit(scope,s -> { s.put("balances",snapshot.currency()+":"+snapshot.balanceDomain(),snapshot,s.now);return null; });
    }
    public void putReport(ReportRequest request,ReportFact report) {
        if(!request.createdGte().equals(report.createdGte())||!request.createdLt().equals(report.createdLt()))throw new IllegalArgumentException("Report window mismatch");
        edit(request.scope(),s -> { s.put("reports",FakeStripeProvider.reportKey(request),report,s.now);s.put("reportIds",report.reportId(),report,s.now);return null; });
    }
    public String emitWebhook(ProviderScope scope,String type,String objectId) {
        if(type==null||type.isBlank()||objectId==null||objectId.isBlank())throw new IllegalArgumentException("Webhook fact required");
        return edit(scope,s -> {
            String id=s.id("evt");
            s.array("events").addObject().put("eventId",id).put("eventType",type).put("objectId",objectId)
                    .put("createdAt",s.now.toString()).put("livemode",false);return id;
        });
    }
    /** No delivery is automatic: omit this call to model loss; call twice to model duplication. */
    public void scheduleDelivery(ProviderScope scope,String eventId,Instant at) {
        edit(scope,s -> {
            JsonNode event=null;for(JsonNode item:s.array("events"))if(item.path("eventId").asText().equals(eventId))event=item;
            if(event==null)throw new IllegalArgumentException("Unknown event in scope");
            s.array("deliveries").addObject().put("eventId",eventId).put("rawBody",event.toString()).put("deliveryAt",Objects.requireNonNull(at).toString());return null;
        });
    }
    public List<WebhookDelivery> takeDeliveries(ProviderScope scope) {
        return edit(scope,s -> {
            var ready=new ArrayList<WebhookDelivery>();ArrayNode queue=s.array("deliveries");
            for(int i=queue.size()-1;i>=0;i--) {
                var delivery=JSON.convertValue(queue.get(i),WebhookDelivery.class);
                if(!delivery.deliveryAt().isAfter(s.now)) { ready.add(delivery);queue.remove(i); }
            }
            ready.sort(Comparator.comparing(WebhookDelivery::deliveryAt));return List.copyOf(ready);
        });
    }
}
