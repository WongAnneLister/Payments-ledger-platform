package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.webhook.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** VERIFIED_BOUNDARY_FIXTURE; signature verification is covered by HTTP IT; this suite tests the verified persistence boundary. */
class WebhookProcessingIT extends PaymentCoreFixture {
    WebhookInboxRepository inbox; WebhookProcessor processor;
    @BeforeEach void webhookSetup() {
        inbox=new WebhookInboxRepository(jdbc,tx,new ObjectMapper(),fake.clock());
        processor=new WebhookProcessor(inbox,repository,new ObjectMapper(),fake.clock());
    }
    UUID ready(String order){UUID p=repository.prepare(context,"key-pr08-"+order,command(order),"FAKE").paymentId();admin.update("UPDATE platform.provider_account SET provider='STRIPE',provider_account_ref='acct_test' WHERE id=?",id(4));return p;}
    void stripe(){admin.update("UPDATE platform.provider_account SET provider='STRIPE',provider_account_ref='acct_test' WHERE id=?",id(4));}
    byte[] raw(String event,String type,String body){return ("{\"id\":\""+event+"\",\"type\":\""+type+"\",\"created\":"+fake.clock().instant().getEpochSecond()+",\"livemode\":false,\"account\":\"acct_test\",\"api_version\":\"2026-07-29.dahlia\",\"data\":{\"object\":"+body+"}}").getBytes(StandardCharsets.UTF_8);}
    WebhookInboxRepository.Receipt receive(byte[] bytes)throws Exception{var n=new ObjectMapper().readTree(bytes);var v=new VerifiedWebhook(n.path("id").asText(),n.path("type").asText(),n.path("data").path("object").path("id").asText(),Instant.ofEpochSecond(n.path("created").asLong()),false);return inbox.receive(scope,"acct_test",bytes,new ProviderCallResult<>(ProviderCallClassification.SUCCESS,v,new ProviderEvidence(null,null,fake.clock().instant(),fake.clock().instant(),200,null,null,"2026-07-29.dahlia")));}
    void run()throws Exception{var repo=inbox;for(var claim:repo.claimDue(100))processor.process(claim);}
    String pi(UUID p){return "{\"object\":\"payment_intent\",\"id\":\"pi_test\",\"status\":\"succeeded\",\"amount\":10000,\"amount_received\":10000,\"currency\":\"usd\",\"metadata\":{\"payment_id\":\""+p.toString()+"\"}}";}
    String charge(long amount){return "{\"object\":\"charge\",\"id\":\"ch_test\",\"payment_intent\":\"pi_test\",\"status\":\"succeeded\",\"captured\":true,\"amount\":10000,\"amount_captured\":"+amount+",\"currency\":\"usd\"}";}
    String state(UUID p){return jdbc.queryForObject("SELECT payment_status FROM platform.payment WHERE id=?",String.class,p);}
    @Test void concurrentSameEventIsOneDurableReceipt()throws Exception{stripe();byte[] raw=raw("evt_1","charge.succeeded",charge(10000));try(var pool=Executors.newFixedThreadPool(2)){var barrier=new CyclicBarrier(2);Callable<Object> task=()->{barrier.await();return receive(raw);};var a=pool.submit(task);var b=pool.submit(task);assertThat(a.get()).isNotNull();assertThat(b.get()).isNotNull();}assertThat(count("webhook_inbox")).isEqualTo(1);assertThatThrownBy(()->receive(raw("evt_1","charge.succeeded",charge(9000)))).isInstanceOf(com.paymentsledger.common.error.ApiException.class);}
    @Test void outOfOrderChargeWaitsThenIntentEstablishesDependency()throws Exception{UUID p=ready("order");receive(raw("evt_c","charge.succeeded",charge(10000)));run();assertThat(count("dependency_wait")).isEqualTo(1);assertThat(state(p)).isNotEqualTo("SUCCEEDED");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();fake.clock().advance(Duration.ofMinutes(1));run();assertThat(state(p)).isEqualTo("SUCCEEDED");assertThat(count("payment_attempt")).isEqualTo(1);assertThat(count("journal")).isZero();}
    @Test void differentEventsSameCaptureAndQueryShareOneIdentity()throws Exception{UUID p=ready("order");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();assertThat(jdbc.queryForObject("SELECT payment_intent_id FROM platform.payment WHERE id=?",String.class,p)).as(jdbc.queryForList("SELECT * FROM platform.webhook_inbox").toString()).isEqualTo("pi_test");receive(raw("evt_c1","charge.succeeded",charge(10000)));receive(raw("evt_c2","charge.succeeded",charge(10000)));run();var fact=new ChargeFact("ch_test","pi_test","succeeded",true,10000,10000L,"usd",null,null,null,fake.clock().instant());UUID op=jdbc.queryForObject("SELECT id FROM platform.provider_operation WHERE payment_id=?",UUID.class,p);repository.mergeQuery(p,op,List.of(),new PaymentRepository.Facts(null,null,List.of(fact),true,false,fake.clock().instant()));assertThat(count("payment_attempt")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.normalized_observation WHERE fact_kind='CAPTURE'",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT captured_amount_minor FROM platform.payment WHERE id=?",Long.class,p)).isEqualTo(10000);}
    @Test void captureAmountConflictNeverChangesMoney()throws Exception{UUID p=ready("order");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();assertThat(jdbc.queryForObject("SELECT payment_intent_id FROM platform.payment WHERE id=?",String.class,p)).as(jdbc.queryForList("SELECT * FROM platform.webhook_inbox").toString()).isEqualTo("pi_test");receive(raw("evt_c1","charge.succeeded",charge(10000)));run();receive(raw("evt_c2","charge.succeeded",charge(9000)));run();assertThat(state(p)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT captured_amount_minor FROM platform.payment WHERE id=?",Long.class,p)).isEqualTo(10000);assertThat(jdbc.queryForObject("SELECT resolution_status FROM platform.payment WHERE id=?",String.class,p)).isEqualTo("CONFLICT");}
    @Test void sanitizerDropsSensitiveFieldsAndFutureResourcesWait()throws Exception{stripe();String body="{\"object\":\"refund\",\"id\":\"re_test\",\"charge\":\"ch_test\",\"amount\":100,\"currency\":\"usd\",\"status\":\"succeeded\",\"client_secret\":\"SECRET\",\"email\":\"PRIVATE\",\"metadata\":{\"secret\":\"HIDDEN\",\"payment_id\":\"pay_test\"},\"billing_details\":{\"name\":\"PERSON\"}}";receive(raw("evt_future","refund.updated",body));run();assertThat(count("dependency_wait")).isEqualTo(1);String stored=jdbc.queryForObject("SELECT object_snapshot::text FROM platform.webhook_inbox",String.class);assertThat(stored).contains("re_test","ch_test").doesNotContain("SECRET","PRIVATE","HIDDEN","PERSON","client_secret","billing_details");}
    @Test void missingPaymentRemainsDurableUntilItExists()throws Exception{stripe();UUID p=UUID.randomUUID();receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox",String.class)).isEqualTo("WAITING");assertThat(count("dependency_wait")).isEqualTo(1);}
    @Test void expiredClaimCannotCompleteNewOwnersWork()throws Exception{
        UUID p=ready("lease");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_charge","charge.succeeded",charge(10000)));
        var repo=inbox;var worker=processor;
        var old=repo.claimDue(1).getFirst();fake.clock().advance(Duration.ofSeconds(31));var current=repo.claimDue(1).getFirst();
        worker.process(old);assertThat(count("payment_attempt")).isZero();worker.process(current);assertThat(state(p)).isEqualTo("SUCCEEDED");
    }
    @Test void completionFailureRollsBackCaptureAndRetryRecovers()throws Exception{
        UUID p=ready("rollback");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_charge","charge.succeeded",charge(10000)));
        admin.execute("CREATE FUNCTION platform.test_webhook_crash() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='DONE' THEN RAISE EXCEPTION 'injected crash'; END IF; RETURN NEW; END $$");
        admin.execute("CREATE TRIGGER test_webhook_crash BEFORE UPDATE ON platform.webhook_inbox FOR EACH ROW EXECUTE FUNCTION platform.test_webhook_crash()");
        try{processor.processDue(100);assertThat(count("payment_attempt")).isZero();assertThat(state(p)).isNotEqualTo("SUCCEEDED");}finally{admin.execute("DROP TRIGGER test_webhook_crash ON platform.webhook_inbox");admin.execute("DROP FUNCTION platform.test_webhook_crash()");}
        fake.clock().advance(Duration.ofSeconds(31));run();assertThat(state(p)).isEqualTo("SUCCEEDED");
    }
    @Test void expiredSessionEventDoesNotProveNoCapture()throws Exception{
        UUID p=ready("expired");String session="cs_test";admin.update("UPDATE platform.payment SET checkout_session_id=? WHERE id=?",session,p);
        receive(raw("evt_exp","checkout.session.expired","{\"object\":\"checkout.session\",\"id\":\""+session+"\",\"status\":\"expired\",\"payment_status\":\"unpaid\",\"amount_total\":10000,\"currency\":\"usd\"}"));run();assertThat(state(p)).isNotEqualTo("CANCELED");
    }
    @Test void capturedWebhookBlocksStillPreparedCreate()throws Exception{
        var intent=repository.prepare(context,"key-unsent",command("unsent"),"FAKE");UUID p=intent.paymentId();stripe();receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_charge","charge.succeeded",charge(10000)));run();
        admin.update("UPDATE platform.provider_account SET provider='FAKE',provider_account_ref='fixture' WHERE id=?",id(4));executor.execute(intent.operationId());new OperationRecoveryCoordinator(repository,executor).recoverDue(25);
        assertThat(jdbc.queryForObject("SELECT checkout_session_id FROM platform.payment WHERE id=?",String.class,p)).isNull();assertThat(state(p)).isEqualTo("SUCCEEDED");
    }

    @Test void querySourceAndOldFailedAttemptsCannotRegressNewerFacts()throws Exception{
        UUID p=ready("source");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();
        UUID op=jdbc.queryForObject("SELECT id FROM platform.provider_operation WHERE payment_id=?",UUID.class,p);
        fake.clock().advance(Duration.ofSeconds(10));var fact=new PaymentIntentFact("pi_test","requires_action",10000,0L,"usd",List.of(),null,null,Map.of(),fake.clock().instant());
        repository.mergeQuery(p,op,List.of(),new PaymentRepository.Facts(null,fact,List.of(),true,false,fake.clock().instant()));
        byte[] older=raw("evt_old","payment_intent.payment_failed",pi(p).replace("succeeded","requires_payment_method"));receive(older);run();assertThat(state(p)).isEqualTo("REQUIRES_ACTION");
        receive(raw("evt_capture","charge.succeeded",charge(10000)));run();
        receive(raw("evt_failure","charge.failed",charge(0).replace("succeeded","failed").replace("\"captured\":true","\"captured\":false")));run();assertThat(state(p)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT provider_status FROM platform.payment_attempt WHERE provider_object_id='ch_test'",String.class)).isEqualTo("succeeded");
    }
    @Test void queryAndWebhookConcurrentCaptureUseOneImmutableFact()throws Exception{
        UUID p=ready("concurrent");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_c","charge.succeeded",charge(10000)));
        var repo=inbox;var worker=processor;var claim=repo.claimDue(1).getFirst();
        UUID op=jdbc.queryForObject("SELECT id FROM platform.provider_operation WHERE payment_id=?",UUID.class,p);var fact=new ChargeFact("ch_test","pi_test","succeeded",true,10000,10000L,"usd",null,null,null,fake.clock().instant());
        try(var pool=Executors.newFixedThreadPool(2)){var barrier=new CyclicBarrier(2);var a=pool.submit(()->{barrier.await();worker.process(claim);return true;});var b=pool.submit(()->{barrier.await();repository.mergeQuery(p,op,List.of(),new PaymentRepository.Facts(null,null,List.of(fact),true,false,fake.clock().instant()));return true;});assertThat(a.get()).isTrue();assertThat(b.get()).isTrue();}
        assertThat(count("payment_attempt")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT version FROM platform.payment_attempt",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.normalized_observation WHERE fact_kind='CAPTURE'",Long.class)).isEqualTo(1);assertThat(state(p)).isEqualTo("SUCCEEDED");
    }

    @Test void futureChargeRefundEventRetainsDependencyInsteadOfClaimingCompletion()throws Exception{
        UUID p=ready("future-charge");receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_refunded","charge.refunded",charge(10000)));run();
        assertThat(jdbc.queryForObject("SELECT status FROM platform.webhook_inbox WHERE event_id='evt_refunded'",String.class)).isEqualTo("WAITING");assertThat(count("payment_attempt")).isZero();
    }
    @Test void claimedCreateCannotSendAfterWebhookEstablishesRemotePayment()throws Exception{
        var intent=repository.prepare(context,"key-racing",command("racing"),"FAKE");var claim=repository.claim(intent.operationId(),"FAKE");UUID p=intent.paymentId();stripe();receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_charge","charge.succeeded",charge(10000)));run();
        admin.update("UPDATE platform.provider_account SET provider='FAKE',provider_account_ref='fixture' WHERE id=?",id(4));executor.executeClaim(claim);
        assertThat(jdbc.queryForObject("SELECT checkout_session_id FROM platform.payment WHERE id=?",String.class,p)).isNull();assertThat(state(p)).isEqualTo("SUCCEEDED");
    }
    @Test void staleCreateResponseCannotAttachAnotherIntentToCapturedPayment()throws Exception{
        var intent=repository.prepare(context,"key-response",command("response"),"FAKE");var claim=repository.claim(intent.operationId(),"FAKE");var command=repository.decode(claim.operation().get("command"),CreateCheckoutSessionCommand.class);var response=fake.createSession(command);
        UUID p=intent.paymentId();stripe();receive(raw("evt_pi","payment_intent.succeeded",pi(p)));run();receive(raw("evt_charge","charge.succeeded",charge(10000)));run();repository.finishCreate(claim,response);
        assertThat(state(p)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT resolution_status FROM platform.payment WHERE id=?",String.class,p)).isEqualTo("CONFLICT");
    }

    @Test void concurrentDifferentPaymentsCannotClaimTheSameScopedIntent()throws Exception{
        UUID a=repository.prepare(context,"key-owner-a",command("owner-a"),"FAKE").paymentId();
        UUID b=repository.prepare(context,"key-owner-b",command("owner-b"),"FAKE").paymentId();stripe();
        receive(raw("evt_owner_a","payment_intent.succeeded",pi(a)));receive(raw("evt_owner_b","payment_intent.succeeded",pi(b)));
        var barrier=new CyclicBarrier(2);var reached=new java.util.concurrent.atomic.AtomicInteger();
        var synchronizedRepository=new PaymentRepository(jdbc,tx,new ObjectMapper(),fake.clock()){
            @Override public Map<String,Object> lockWebhookPayment(UUID payment){
                if(reached.incrementAndGet()<=2)try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                return super.lockWebhookPayment(payment);
            }
        };
        var worker=new WebhookProcessor(inbox,synchronizedRepository,new ObjectMapper(),fake.clock());var claims=inbox.claimDue(2);
        try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->worker.process(claims.get(0)));var second=pool.submit(()->worker.process(claims.get(1)));first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payment WHERE payment_intent_id='pi_test'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM platform.payment WHERE payment_intent_id IS NULL",String.class)).isEqualTo("CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.webhook_inbox WHERE status='WAITING'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM platform.dependency_wait",String.class)).contains("CONFLICT");
    }
    @Test void olderSessionStillAddsMissingImmutableLinksWithoutRegressingNewerIntent()throws Exception{
        UUID p=ready("old-session");byte[] older=raw("evt_session_old","checkout.session.completed","{\"object\":\"checkout.session\",\"id\":\"cs_old\",\"status\":\"complete\",\"payment_status\":\"paid\",\"amount_total\":10000,\"currency\":\"usd\",\"payment_intent\":\"pi_test\",\"metadata\":{\"payment_id\":\""+p+"\"}}");
        fake.clock().advance(Duration.ofSeconds(10));receive(raw("evt_pi_new","payment_intent.requires_action",pi(p).replace("succeeded","requires_action")));run();receive(older);run();
        assertThat(jdbc.queryForMap("SELECT checkout_session_id,payment_intent_id,payment_status FROM platform.payment WHERE id=?",p)).containsEntry("checkout_session_id","cs_old").containsEntry("payment_intent_id","pi_test").containsEntry("payment_status","REQUIRES_ACTION");
    }

    @Test void definitiveCreateRejectCannotCancelAnIntentAlreadyProvenByWebhook()throws Exception{
        var intent=repository.prepare(context,"key-reject-after-pi",command("reject-after-pi"),"FAKE");
        var claim=repository.claim(intent.operationId(),"FAKE");UUID p=intent.paymentId();stripe();
        receive(raw("evt_pi_before_reject","payment_intent.succeeded",pi(p)));run();
        repository.finishCreate(claim,new ProviderCallResult<>(ProviderCallClassification.DEFINITIVE_REJECT,null,
            new ProviderEvidence(null,null,fake.clock().instant(),fake.clock().instant(),400,"invalid_request",null,"fixture")));
        assertThat(state(p)).isNotEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM platform.recovery_task WHERE payment_id=?",String.class,p)).isNotEqualTo("DONE");
        receive(raw("evt_capture_after_reject","charge.succeeded",charge(10000)));run();
        assertThat(state(p)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT captured_amount_minor FROM platform.payment WHERE id=?",Long.class,p)).isEqualTo(10000);
        assertThat(count("journal")).isZero();
    }

}
