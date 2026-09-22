package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;
import static com.paymentsledger.provider.ProviderModels.*;
import static com.paymentsledger.provider.ProviderCallClassification.*;
import com.paymentsledger.provider.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FakeEvidenceTest {
    @TempDir Path directory;
    static final Instant START=Instant.parse("2026-01-01T00:00:00Z");
    final ProviderScope scope=new ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
    FakeStripeProvider fake;
    @BeforeEach void setup() { fake=new FakeStripeProvider(directory,42,START,Duration.ofMinutes(10)); }
    BalanceTransactionFact bt(String id,Instant created,String status) {
        return new BalanceTransactionFact(id,"charge",10000,300,9700,"USD","PAYMENTS","ch_fixture","charge",status,
                created,START.plusSeconds(100),List.of(new FeeDetail(300,"USD","stripe_fee","fixture fee")),"a".repeat(64));
    }
    ListBalanceTransactionsQuery query(String cursor) { return new ListBalanceTransactionsQuery(scope,START,START.plusSeconds(30),cursor,1,null); }
    @Test void pagesHaveStableTieBreakAndExplicitEndAndScopeBoundCursor() {
        for(String id:List.of("bt_b","bt_a","bt_c"))fake.controls().putBalanceTransaction(scope,bt(id,START,"pending"),START,null);
        fake.controls().putBalanceTransaction(scope,bt("bt_excluded",START.plusSeconds(30),"pending"),START,null);
        var first=fake.listBalanceTransactions(query(null)).payload();
        assertThat(first.items()).extracting(BalanceTransactionFact::btId).containsExactly("bt_a"); assertThat(first.hasMore()).isTrue();
        var second=fake.listBalanceTransactions(query(first.nextCursor())).payload();
        assertThat(second.items()).extracting(BalanceTransactionFact::btId).containsExactly("bt_b");
        var last=fake.listBalanceTransactions(query(second.nextCursor())).payload();
        assertThat(last.items()).extracting(BalanceTransactionFact::btId).containsExactly("bt_c");assertThat(last.hasMore()).isFalse();assertThat(last.nextCursor()).isNull();
        assertThat(fake.listBalanceTransactions(query("unknown")).classification()).isEqualTo(DEFINITIVE_REJECT);
        var wrongWindow=new ListBalanceTransactionsQuery(scope,START,START.plusSeconds(31),first.nextCursor(),1,null);
        assertThat(fake.listBalanceTransactions(wrongWindow).classification()).isEqualTo(DEFINITIVE_REJECT);
    }
    @Test void cursorCannotCrossProviderScopeEvenWithIdenticalObjectIds() {
        var other=new ProviderScope(scope.organizationId(),scope.environmentId(),new UUID(0,4),false);
        for(var binding:List.of(scope,other))for(String id:List.of("bt_a","bt_b"))
            fake.controls().putBalanceTransaction(binding,bt(id,START,"pending"),START,null);
        var first=fake.listBalanceTransactions(query(null)).payload();
        var otherFirst=fake.listBalanceTransactions(new ListBalanceTransactionsQuery(other,START,START.plusSeconds(30),null,1,null)).payload();
        assertThat(otherFirst.nextCursor()).isNotEqualTo(first.nextCursor());
        assertThat(fake.listBalanceTransactions(new ListBalanceTransactionsQuery(other,START,START.plusSeconds(30),first.nextCursor(),1,null)).classification()).isEqualTo(DEFINITIVE_REJECT);
    }
    @Test void largePageLimitCannotOverflowWhenResuming() {
        for(String id:List.of("bt_a","bt_b"))fake.controls().putBalanceTransaction(scope,bt(id,START,"pending"),START,null);
        var first=fake.listBalanceTransactions(query(null)).payload();
        var next=fake.listBalanceTransactions(new ListBalanceTransactionsQuery(scope,START,START.plusSeconds(30),first.nextCursor(),Integer.MAX_VALUE,null));
        assertThat(next.payload().items()).extracting(BalanceTransactionFact::btId).containsExactly("bt_b");
    }
    @Test void delayedObjectsAreTransientEmptyRatherThanDefinitelyAbsent() {
        fake.controls().putBalanceTransaction(scope,bt("hidden",START,"pending"),START.plusSeconds(5),null);
        fake.controls().putDispute(scope,new DisputeFact("dp_hidden","ch","needs_response","fraudulent",100,"USD",START,List.of()),START,START.plusSeconds(5));
        fake.controls().putPayout(scope,new PayoutFact("po_hidden","pending",100,"USD",null,null,true,START,null),START,START.plusSeconds(5));
        assertThat(fake.getBalanceTransaction(scope,"hidden").classification()).isEqualTo(TRANSIENT_EMPTY);
        assertThat(fake.getDispute(scope,"dp_hidden").classification()).isEqualTo(TRANSIENT_EMPTY);
        assertThat(fake.getPayout(scope,"po_hidden").classification()).isEqualTo(TRANSIENT_EMPTY);
        assertThat(fake.getBalanceTransaction(scope,"absent").classification()).isEqualTo(NOT_FOUND);
        fake.clock().advance(Duration.ofSeconds(5));
        assertThat(fake.getBalanceTransaction(scope,"hidden").classification()).isEqualTo(SUCCESS);
        assertThat(fake.getDispute(scope,"dp_hidden").classification()).isEqualTo(SUCCESS);
        assertThat(fake.getPayout(scope,"po_hidden").classification()).isEqualTo(SUCCESS);
    }
    @Test void lateVisibilityAndAvailabilityChangesDoNotCreatePaymentEffects() {
        fake.controls().putBalanceTransaction(scope,bt("bt_late",START,"pending"),START.plusSeconds(5),null);
        assertThat(fake.listBalanceTransactions(query(null)).payload().items()).isEmpty();
        fake.clock().advance(Duration.ofSeconds(5));
        assertThat(fake.getBalanceTransaction(scope,"bt_late").payload().status()).isEqualTo("pending");
        fake.controls().putBalanceTransaction(scope,bt("bt_late",START,"available"),START,null);
        assertThat(fake.getBalanceTransaction(scope,"bt_late").payload().netMinor()).isEqualTo(9700);
        assertThat(fake.listBalanceTransactions(query(null)).payload().items()).hasSize(1);
        assertThat(fake.counters(scope).paymentEffectCount()).isZero();
        var conflict=new BalanceTransactionFact("bt_late","charge",10000,301,9699,"USD","PAYMENTS","ch_fixture","charge","available",START,START,List.of(),"b".repeat(64));
        assertThatThrownBy(() -> fake.controls().putBalanceTransaction(scope,conflict,START,null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void refundStatusAndFinancialEvidenceCanArriveInEitherOrder() {
        var session=fake.createSession(new CreateCheckoutSessionCommand(scope,"pay",10000,"USD","https://example.test/ok","https://example.test/no",Map.of(),Map.of(),START.plusSeconds(3600))).payload();
        var pi=fake.controls().attemptPayment(scope,session.sessionId(),FakeControls.AttemptOutcome.SUCCEED);
        var refund=fake.createRefund(new CreateRefundCommand(scope,"refund",pi.latestChargeId(),null,1000,"requested_by_customer")).payload();
        assertThat(refund.status()).isEqualTo("pending");assertThat(refund.balanceTransactionId()).isNull();
        fake.controls().linkRefundTransactions(scope,refund.refundId(),"bt_refund",null);
        assertThat(fake.queryRefund(scope,refund.refundId()).payload().status()).isEqualTo("pending");
        fake.controls().setRefundStatus(scope,refund.refundId(),"failed","fixture_failure");
        assertThat(fake.queryRefund(scope,refund.refundId()).payload().failureBalanceTransactionId()).isNull();
        var debit=new BalanceTransactionFact("bt_refund","refund",-1000,0,-1000,"USD","PAYMENTS",refund.refundId(),"refund","pending",START,START,List.of(),"a".repeat(64));
        var credit=new BalanceTransactionFact("bt_return","refund_failure",1000,0,1000,"USD","PAYMENTS",refund.refundId(),"refund","available",START,START,List.of(),"b".repeat(64));
        fake.controls().putBalanceTransaction(scope,debit,START,null);fake.controls().putBalanceTransaction(scope,credit,START,null);
        fake.controls().linkRefundTransactions(scope,refund.refundId(),null,"bt_return");
        fake.controls().linkRefundTransactions(scope,refund.refundId(),null,"bt_return");
        assertThat(fake.queryRefund(scope,refund.refundId()).payload().failureBalanceTransactionId()).isEqualTo("bt_return");
        assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);
        assertThat(fake.controls().effects(scope).stream().filter(n -> n.path("type").asText().equals("REFUND_RETURN"))).hasSize(1);
    }
    @Test void disputePayoutAndAutomaticMembershipRemainExplicitFixtures() {
        var dispute=new DisputeFact("dp_1","ch_fixture","needs_response","fraudulent",10000,"USD",START.plusSeconds(100),List.of("bt_dispute"));
        fake.controls().putDispute(scope,dispute,START,START);
        var automatic=new PayoutFact("po_auto","paid",9700,"USD","bt_po",null,true,START.plusSeconds(100),null);
        var manual=new PayoutFact("po_manual","pending",9700,"USD","bt_po2",null,false,START.plusSeconds(100),null);
        fake.controls().putPayout(scope,automatic,START,START);fake.controls().putPayout(scope,manual,START,START);
        var page=new PageQuery(scope,START,START.plusSeconds(1),null,10);
        assertThat(fake.listDisputes(page).payload().items()).containsExactly(dispute);
        assertThat(fake.getDispute(scope,"dp_1").payload()).isEqualTo(dispute);
        assertThat(fake.listPayouts(page).payload().items()).hasSize(2);
        assertThat(fake.getPayout(scope,"po_auto").payload()).isEqualTo(automatic);
        fake.controls().putBalanceTransaction(scope,bt("bt_member",START,"available"),START,"po_auto");
        var autoQuery=new ListBalanceTransactionsQuery(scope,START,START.plusSeconds(1),null,10,"po_auto");
        assertThat(fake.listBalanceTransactions(autoQuery).payload().items()).hasSize(1);
        var manualQuery=new ListBalanceTransactionsQuery(scope,START,START.plusSeconds(1),null,10,"po_manual");
        assertThat(fake.listBalanceTransactions(manualQuery).classification()).isEqualTo(DEFINITIVE_REJECT);
    }
    @Test void currentBalanceAndOptionalHistoricalReportAreSeparateEvidence() {
        var request=new ReportRequest(scope,"closing_balance",START.minusSeconds(10),START);
        assertThat(fake.requestReport(request).classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        var snapshot=new BalanceSnapshot("USD","PAYMENTS",500,100,START);
        fake.controls().putBalance(scope,snapshot);fake.clock().advance(Duration.ofSeconds(5));
        assertThat(fake.getBalance(scope,"USD","PAYMENTS").payload().capturedAt()).isEqualTo(START.plusSeconds(5));
        assertThat(fake.getBalance(scope,"JPY","PAYMENTS").classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        var report=new ReportFact("report_fixture","succeeded",request.createdGte(),request.createdLt(),List.of(snapshot),"c".repeat(64));
        fake.controls().putReport(request,report);
        assertThat(fake.requestReport(request).payload()).isEqualTo(report);assertThat(fake.fetchReport(scope,report.reportId()).payload()).isEqualTo(report);
    }
    @Test void webhookLossDuplicateAndOrderAreReplayedAfterRestart() {
        String a=fake.controls().emitWebhook(scope,"payment_intent.processing","pi_1");
        String b=fake.controls().emitWebhook(scope,"payment_intent.succeeded","pi_1");
        fake.controls().emitWebhook(scope,"charge.updated","ch_lost"); // deliberately never scheduled
        fake.controls().scheduleDelivery(scope,a,START.plusSeconds(2));
        fake.controls().scheduleDelivery(scope,b,START);fake.controls().scheduleDelivery(scope,b,START.plusSeconds(1));
        fake=new FakeStripeProvider(directory,42,START,Duration.ofMinutes(10));fake.clock().advance(Duration.ofSeconds(2));
        var deliveries=fake.controls().takeDeliveries(scope);
        assertThat(deliveries).extracting(FakeControls.WebhookDelivery::eventId).containsExactly(b,b,a);
        assertThat(deliveries.get(0).rawBody()).isEqualTo(deliveries.get(1).rawBody());
        assertThat(fake.controls().takeDeliveries(scope)).isEmpty();
    }
    @Test void localWebhookSignatureUsesOriginalBytesAndEnforcesTimestampAndSecret() {
        var verifier=new FakeWebhookVerifier(fake.clock(),Duration.ofMinutes(5));
        String event=fake.controls().emitWebhook(scope,"payment_intent.succeeded","pi_1");
        fake.controls().scheduleDelivery(scope,event,START);
        byte[] bytes=fake.controls().takeDeliveries(scope).getFirst().rawBody().getBytes(StandardCharsets.UTF_8);
        String signature=verifier.sign(bytes,"fixture_secret");
        assertThat(verifier.verify(bytes,signature,"fixture_secret").payload().eventId()).isEqualTo(event);
        assertThat(verifier.verify(bytes,signature,"wrong").classification()).isEqualTo(DEFINITIVE_REJECT);
        assertThat(verifier.verify((new String(bytes,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),signature,"fixture_secret").classification()).isEqualTo(DEFINITIVE_REJECT);
        fake.clock().advance(Duration.ofMinutes(6));
        assertThat(verifier.verify(bytes,signature,"fixture_secret").classification()).isEqualTo(DEFINITIVE_REJECT);
    }
}
