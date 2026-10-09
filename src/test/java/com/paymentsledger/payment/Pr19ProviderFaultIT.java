package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** F05: real operation/lease/recovery services and independently persisted Fake accept/effect logs. */
class Pr19ProviderFaultIT extends Pr19FaultFixture {
    @ParameterizedTest @ValueSource(strings={"payment-before-send","payment-accepted-timeout","payment-expired-key"})
    void paymentFailureWindows(String variant) throws Exception {
        fault("F-05",variant,190505,"before-send Fake script or discard accepted create response after independent remote capture; controlled key-window advance","OperationRecoveryCoordinator original operation/key/fingerprint; expired key escalates without create",e->{
            var intent=repository.prepare(context,"pr19-payment-request",command("pr19-payment-order"),"FAKE");paymentId=intent.paymentId();
            var original=repository.operation(intent.operationId());String key=original.get("operation_key").toString();Object fingerprint=original.get("request_fingerprint");
            if(variant.equals("payment-before-send")) {
                fake.controls().enqueue(scope,FailureScript.Operation.CREATE_SESSION,key,FailureScript.Outcome.BEFORE_SEND_FAILURE);executor.execute(intent.operationId());
                assertThat(fake.counters(scope).acceptedCount()).isZero();assertThat(fake.counters(scope).paymentEffectCount()).isZero();
            } else {
                // The local transport loses the successful reply after the independent Fake has committed.
                var claim=repository.claim(intent.operationId(),"FAKE");var accepted=fake.createSession(repository.decode(claim.operation().get("command"),CreateCheckoutSessionCommand.class));
                checkout=accepted.payload().sessionId();chargeId=fake.controls().attemptPayment(scope,checkout,FakeControls.AttemptOutcome.SUCCEED).latestChargeId();
                repository.finishCreate(claim,new ProviderCallResult<>(ProviderCallClassification.TIMEOUT_UNKNOWN,null,accepted.evidence()));
                observations.add(Map.of("injection","accepted remote result payload withheld from local finishCreate","remoteSession",checkout,"remoteCharge",chargeId,"localClassification","TIMEOUT_UNKNOWN","operationKey",key));
                assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
            }
            assertThat(repository.read(context,paymentId)).containsEntry("resolutionStatus","UNKNOWN");
            assertThat(repository.operation(intent.operationId())).containsEntry("operation_key",key).containsEntry("request_fingerprint",fingerprint);
            e.put("intermediateEvidence",snapshotFault());long beforeRequests=fake.counters(scope).requestCount();long beforeAccepted=fake.counters(scope).acceptedCount();
            recovery(e,ignored->{
                fake.clock().advance(variant.equals("payment-expired-key")?Duration.ofDays(2):Duration.ofSeconds(6));var coordinator=new OperationRecoveryCoordinator(repository,executor);coordinator.recoverDue(100);
                if(variant.equals("payment-expired-key")) {
                    assertThat(repository.operation(intent.operationId())).containsEntry("status","ESCALATED").containsEntry("escalation_reason","IDEMPOTENCY_WINDOW_EXPIRED");
                    coordinator.scanL2(100);coordinator.recoverDue(100);assertThat(fake.counters(scope).requestCount()).isEqualTo(beforeRequests);assertThat(fake.counters(scope).acceptedCount()).isEqualTo(beforeAccepted);
                    assertThat(repository.read(context,paymentId)).containsEntry("resolutionStatus","UNKNOWN");assertThat(count("journal")).isZero();assertThat(merchant.effects()).isZero();
                } else {
                    var restored=repository.read(context,paymentId);checkout=session(restored);
                    chargeId=fake.controls().attemptPayment(scope,checkout,FakeControls.AttemptOutcome.SUCCEED).latestChargeId();service.get(context,paymentId);
                    fake.controls().linkChargeBalanceTransaction(scope,chargeId,"capture");service.get(context,paymentId);post(bt("capture","charge",10000,300,chargeId,"charge"));deliverNativeSucceededTwice();
                    assertThat(repository.read(context,paymentId)).containsEntry("paymentStatus","SUCCEEDED").containsEntry("resolutionStatus","KNOWN");assertThat(merchant.effects()).isEqualTo(1);assertThat(sourceJournals("capture")).isEqualTo(1);
                }
            });
            assertThat(repository.operation(intent.operationId())).containsEntry("operation_key",key).containsEntry("request_fingerprint",fingerprint);
            assertThat(fake.counters(scope).sessionCreatedCount()).isEqualTo(1);assertThat(fake.counters(scope).acceptedCount()).isEqualTo(1);assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
            assertThat(fake.controls().calls(scope).stream().filter(c->c.path("operation").asText().equals("CREATE_SESSION")).map(c->c.path("target").asText()).distinct().toList()).containsExactly(key);
            observations.add(Map.of("originalOperationKey",key,"originalFingerprint",fingerprint,"requestCountBeforeRecovery",beforeRequests,"requestsAfterRecovery",fake.counters(scope).requestCount()));
        });
    }

    @ParameterizedTest @ValueSource(strings={"refund-before-send","refund-accepted-timeout","refund-expired-key"})
    void refundFailureWindows(String variant) throws Exception {
        fault("F-05",variant,190506,"Fake refund before-send or accepted-response-loss script; advance beyond verified retention without refund anchor","RefundExecutor on shared original operation; query after cached original-key recovery; expired key hold + escalation",e->{
            createPayment();capturePayment();post(bt("capture","charge",10000,300,chargeId,"charge"));
            var intent=reserve("pr19-refund",1000);var operation=repository.operation(intent.operationId());String key=operation.get("operation_key").toString();Object fingerprint=operation.get("request_fingerprint");
            var refundExecutor=new RefundExecutor(repository,refunds,fake,"FAKE");
            fake.controls().enqueue(scope,FailureScript.Operation.CREATE_REFUND,key,variant.equals("refund-before-send")?FailureScript.Outcome.BEFORE_SEND_FAILURE:FailureScript.Outcome.RESPONSE_LOST);refundExecutor.execute(intent.operationId());
            assertThat(refunds.read(refundContext,intent.refundId())).containsEntry("resolutionStatus","UNKNOWN");assertThat(held()).isEqualTo(1000);assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(variant.equals("refund-before-send")?0:1);
            e.put("intermediateEvidence",snapshotFault());long requests=fake.counters(scope).requestCount();long accepted=fake.counters(scope).acceptedCount();
            recovery(e,ignored->{
                fake.clock().advance(variant.equals("refund-expired-key")?Duration.ofDays(2):Duration.ofSeconds(6));
                var coordinator=new OperationRecoveryCoordinator(repository,executor,refundExecutor,repository.recoveryPolicy());coordinator.recoverDue(100);
                if(variant.equals("refund-expired-key")) {
                    assertThat(repository.operation(intent.operationId())).containsEntry("status","ESCALATED").containsEntry("escalation_reason","IDEMPOTENCY_WINDOW_EXPIRED");
                    assertThat(held()).isEqualTo(1000);coordinator.scanL2(100);coordinator.recoverDue(100);
                    assertThat(fake.controls().calls(scope).stream().filter(c->Set.of("CREATE_REFUND","QUERY_REFUND").contains(c.path("operation").asText())).count()).isEqualTo(1);
                    assertThat(fake.counters(scope).acceptedCount()).isEqualTo(accepted);assertThat(refunds.read(refundContext,intent.refundId())).containsEntry("resolutionStatus","UNKNOWN");
                } else {
                    assertThat(held()).isEqualTo(1000);String providerId=jdbc.queryForObject("SELECT provider_refund_id FROM platform.refund WHERE id=?",String.class,intent.refundId());assertThat(providerId).isNotBlank();
                    fake.controls().setRefundStatus(scope,providerId,"succeeded",null);fake.controls().linkRefundTransactions(scope,providerId,"refund",null);
                    fake.clock().advance(Duration.ofSeconds(16));coordinator.recoverDue(100);assertThat(held()).isZero();assertThat(refunds.read(refundContext,intent.refundId())).containsEntry("refundStatus","SUCCEEDED");
                    post(bt("refund","refund",-1000,0,providerId,"refund"));assertThat(balance("PSP_FUNDS")).isEqualTo(8700);assertThat(sourceJournals("capture")).isEqualTo(1);assertThat(sourceJournals("refund")).isEqualTo(1);
                }
            });
            assertThat(repository.operation(intent.operationId())).containsEntry("operation_key",key).containsEntry("request_fingerprint",fingerprint);
            assertThat(fake.counters(scope).refundEffectCount()).isEqualTo(1);assertThat(fake.counters(scope).paymentEffectCount()).isEqualTo(1);
            assertThat(fake.controls().calls(scope).stream().filter(c->c.path("operation").asText().equals("CREATE_REFUND")).map(c->c.path("target").asText()).distinct().toList()).containsExactly(key);
            observations.add(Map.of("originalOperationKey",key,"originalFingerprint",fingerprint,"refundHold",held(),"requestCountBeforeRecovery",requests,"requestsAfterRecovery",fake.counters(scope).requestCount(),"refundCreateAttempts",fake.controls().calls(scope).stream().filter(c->c.path("operation").asText().equals("CREATE_REFUND")).count()));
        });
    }
    long held(){return jdbc.queryForObject("SELECT reserved_minor FROM platform.refund_control WHERE payment_id=?",Long.class,paymentId);}
}
