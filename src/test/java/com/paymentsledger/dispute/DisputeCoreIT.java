package com.paymentsledger.dispute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.revenueshare.RevenueShareService;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DisputeCoreIT extends DisputeCoreFixture {
 DisputeService disputes(){var mapper=new ObjectMapper().findAndRegisterModules();return new DisputeService(jdbc,tx,mapper,new LedgerPostingService(jdbc,mapper),RevenueShareService.disabled());}
 ProviderEvidence evidence(Instant at){return new ProviderEvidence("req",null,at,at,200,null,"digest","v");}
 DisputeFact fact(String id,String status,String charge,long amount,List<String> bts){return new DisputeFact(id,charge,status,"fraudulent",amount,"USD",Instant.parse("2027-01-01T00:00:00Z"),bts);}
 @Test void missingChargeRetainsEvidenceAndUnknownBtLinksWithoutCreatingMoney(){
  var service=disputes();UUID id=service.accept(scope,fact("dp_missing","needs_response","ch_missing",3000,List.of("txn_missing")),evidence(Instant.now()));
  assertThat(service.lookup(scope,"dp_missing")).containsEntry("id",id).containsEntry("payment_id",null).containsEntry("blocking",true);
  assertThat(count("dispute_observation")).isEqualTo(1);assertThat(count("dispute_bt_link")).isEqualTo(1);assertThat(count("journal")).isZero();
 }
 @Test void staleStatusCannotRegressTerminalStateButRetainsNewFinancialReference(){
  var service=disputes();Instant now=Instant.now();
  service.accept(scope,fact("dp_stale","won","ch_missing",3000,List.of()),evidence(now));
  service.accept(scope,fact("dp_stale","needs_response","ch_missing",3000,List.of("txn_late")),evidence(now.minusSeconds(10)));
  assertThat(service.lookup(scope,"dp_stale")).containsEntry("provider_status","won").containsEntry("blocking",true);
  assertThat(count("dispute_observation")).isEqualTo(2);assertThat(count("dispute_bt_link")).isEqualTo(1);
 }
 @Test void scopedProviderIdentityDoesNotLeakLookup(){
  disputes().accept(scope,fact("dp_scope","lost","ch_missing",3000,List.of()),evidence(Instant.now()));
  assertThat(disputes().lookup(new ProviderScope(id(90),id(91),id(92),false),"dp_scope")).isNull();
 }

 @Test void unboundCurrencyConflictBindsTrustedPaymentAndRetainsBothObservations(){
  var pay=service.create(context,"dispute-pay",command("dispute-pay")).resource();UUID payment=payment(pay);
  String charge=fake.controls().attemptPayment(scope,session(pay),com.paymentsledger.provider.fake.FakeControls.AttemptOutcome.SUCCEED).latestChargeId();
  var domain=disputes();Instant at=Instant.now();
  UUID dispute=domain.accept(scope,new DisputeFact("dp_currency",charge,"needs_response","fraudulent",3000,"EUR",null,List.of()),evidence(at));
  assertThat(domain.lookup(scope,"dp_currency")).containsEntry("payment_id",null).containsEntry("currency","EUR");
  service.get(context,payment);
  domain.accept(scope,fact("dp_currency","needs_response",charge,3000,List.of()),evidence(at.plusSeconds(1)));
  assertThat(domain.lookup(scope,"dp_currency")).containsEntry("payment_id",payment).containsEntry("currency","USD").containsEntry("conflict",true);
  assertThat(domain.blocks(payment)).isTrue();assertThat(count("dispute_observation")).isEqualTo(2);
  assertThat(domain.cases(context,payment)).hasSize(1);
  assertThatThrownBy(()->jdbc.update("UPDATE platform.dispute SET outstanding_receivable_minor=100,debited_principal_minor=100 WHERE id=?",dispute)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
}
