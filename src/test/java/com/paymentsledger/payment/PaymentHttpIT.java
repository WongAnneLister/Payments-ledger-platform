package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles({"api","test"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentHttpIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");
    static final String SECRET="b".repeat(43), HASH=new BCryptPasswordEncoder(10).encode(SECRET);
    static final Path FAKE_PATH=directory();
    static Path directory(){try{return Files.createTempDirectory("pr06-http-fake-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",()->"payments_app");r.add("spring.datasource.password",()->"pr02_test_only");
        r.add("spring.flyway.url",DB::getJdbcUrl);r.add("spring.flyway.user",DB::getUsername);r.add("spring.flyway.password",DB::getPassword);
        r.add("platform.provider.fake.enabled",()->true);r.add("platform.provider.fake.directory",FAKE_PATH::toString);
        r.add("platform.provider.fake.seed",()->72);r.add("platform.provider.fake.initial-time",()->Instant.now().toString());r.add("platform.provider.fake.retention",()->"P1D");
    }
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;
    @Autowired OperationRecoveryCoordinator recovery;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.paymentsledger.provider.fake.FakeStripeProvider fake;
    JdbcTemplate admin;
    static UUID id(int value){return new UUID(1,value);}
    @BeforeEach void seed(){
        admin=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
        admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2),('JPY',0)");
        for(int org:List.of(1,2)){
            admin.update("INSERT INTO platform.organization(id,code) VALUES (?,?)",id(org),"org-"+org);
            admin.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",id(10+org),id(org));
            admin.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE',?,false)",id(20+org),id(org),id(10+org),"http-fixture-"+org);
            admin.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD'),(?,?,'JPY')",id(org),id(10+org),id(org),id(10+org));
            admin.update("INSERT INTO platform.share_policy_fixture(id,organization_id,environment_id,product_ref,policy_version,partner_ref,share_basis_points) VALUES (?,?,?,'demo',1,'partner',1000)",id(70+org),id(org),id(10+org));
            admin.update("INSERT INTO platform.price_fixture(id,organization_id,environment_id,product_ref,price_version,currency,amount_minor,share_policy_id) VALUES (?,?,?,'demo',1,'USD',100,?)",id(80+org),id(org),id(10+org),id(70+org));
        }
        for(int app:List.of(1,2,3,4)){
            int org=app==2?2:1;
            admin.update("INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,?)",id(30+app),id(org),id(10+org),"app-"+app);
            String scopes=app==4?"ARRAY['payments:write']":"ARRAY['payments:read','payments:write']";
            admin.update("INSERT INTO platform.application_credential(id,key_id,organization_id,environment_id,application_id,provider_account_id,secret_hash,not_before,expires_at,granted_scopes) VALUES (?,'http',?,?,?,?,?,now()-interval '1 day',now()+interval '1 day',"+scopes+")",id(40+app),id(org),id(10+org),id(30+app),id(20+org),HASH);
            admin.update("INSERT INTO platform.credential_currency(credential_id,organization_id,environment_id,currency) VALUES (?,?,?,'USD'),(?,?,?,'JPY')",id(40+app),id(org),id(10+org),id(40+app),id(org),id(10+org));
        }
    }
    ObjectNode body(String order){return json.createObjectNode().put("orderKey",order).put("productRef","demo").put("priceVersion",1).put("amountMinor",100).put("currency","USD").put("successUrl","https://merchant.test/payment-test.html").put("cancelUrl","https://merchant.test/payment-test.html");}
    ResponseEntity<JsonNode> send(int app,String path,HttpMethod method,JsonNode body,String key){
        HttpHeaders headers=new HttpHeaders();if(app!=0)headers.setBearerAuth("app_"+id(40+app)+"."+SECRET);
        if(key!=null)headers.set("Idempotency-Key",key);if(body!=null)headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    @Test void authenticatedCreateReplayQueryAndCancelFollowFrozenHttpContract(){
        var created=send(1,"/v1/payments",HttpMethod.POST,body("order"),"create-key");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String path=created.getHeaders().getLocation().toString();
        assertThat(created.getBody().path("paymentStatus").asText()).isEqualTo("AWAITING_CUSTOMER");
        assertThat(created.getBody().path("capturedAmountMinor").asLong()).isZero();
        var replay=send(1,"/v1/payments",HttpMethod.POST,body("order"),"create-key");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody().path("id")).isEqualTo(created.getBody().path("id"));
        var query=send(1,path,HttpMethod.GET,null,null);assertThat(query.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(query.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");assertThat(query.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        var canceled=send(1,path+"/cancel",HttpMethod.POST,null,"cancel-key");assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(canceled.getBody().path("paymentStatus").asText()).isEqualTo("CANCELED");
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment",Long.class)).isEqualTo(1L);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.journal",Long.class)).isZero();
    }
    @Test void independentOrderConflictPayloadConflictAndAuthoritativeAmount(){
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("order"),"create-key").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("order"),"different-key").getBody().path("code").asText()).isEqualTo("ORDER_INTENT_CONFLICT");
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("changed"),"create-key").getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("tamper").put("amountMinor",1),"tamper-key").getBody().path("code").asText()).isEqualTo("AMOUNT_MISMATCH");
    }
    @Test void authenticationScopesAndApplicationIsolationAreEnforced(){
        assertThat(send(0,"/v1/payments",HttpMethod.POST,body("none"),"create-key").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var created=send(1,"/v1/payments",HttpMethod.POST,body("own"),"create-key");String path=created.getHeaders().getLocation().toString();
        for(int app:List.of(2,3)){
            assertThat(send(app,path,HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(send(app,path+"/cancel",HttpMethod.POST,null,"cancel-key").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        var writeOnly=send(4,"/v1/payments",HttpMethod.POST,body("write-only"),"create-key");assertThat(writeOnly.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(4,writeOnly.getHeaders().getLocation().toString(),HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void forgedScopeAndBadMoneyAreRejectedBeforeIntent(){
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("forged").put("providerAccountId",id(22).toString()),"create-key").getStatusCode().is4xxClientError()).isTrue();
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("float").put("amountMinor",100.0),"create-key").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(1,"/v1/payments",HttpMethod.POST,body("unknown").put("unrecognized",true),"create-key").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment",Long.class)).isZero();
    }
    @Test void zeroDecimalJpyAndQuantityUseMinorUnits(){
        admin.update("UPDATE platform.price_fixture SET currency='JPY',amount_minor=125 WHERE organization_id=?",id(1));
        var created=send(1,"/v1/payments",HttpMethod.POST,body("jpy").put("currency","JPY").put("quantity",2).put("amountMinor",250),"create-key");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(created.getBody().path("amountMinor").asLong()).isEqualTo(250L);
    }
    @Test void testPageIsAvailableWithoutExposingCredentials(){
        var page=http.getForEntity("/payment-test.html",String.class);assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("创建并查询测试付款").doesNotContain(SECRET,HASH);
    }
    @Test void paymentQueryShowsDurableRecoveryProgressWithoutLeaseSecrets(){
        var created=send(1,"/v1/payments",HttpMethod.POST,body("recovery-progress"),"recovery-key");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String path=created.getHeaders().getLocation().toString();
        var response=send(1,path,HttpMethod.GET,null,null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode progress=response.getBody().path("recovery");
        assertThat(progress.isObject()).as("persistent recovery summary").isTrue();
        assertThat(progress.path("status").asText()).isEqualTo("WAITING");
        assertThat(progress.path("attemptCount").asInt()).isPositive();
        assertThat(progress.hasNonNull("nextAttemptAt")).isTrue();
        assertThat(progress.hasNonNull("lastQueryAt")).isTrue();
        assertThat(Instant.parse(progress.path("nextAttemptAt").asText())).isNotNull();
        assertThat(Instant.parse(progress.path("lastQueryAt").asText())).isNotNull();
        assertThat(progress.toString()).doesNotContain("leaseToken","lease_token","operation_key","command",SECRET);
        assertThat(send(3,path,HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void lostCreateResponseWithoutVerifiedWindowEscalatesAndNeverCreatesAgain(){
        var scope=new com.paymentsledger.provider.ProviderScope(id(1),id(11),id(21),false);
        var before=fake.counters(scope);
        // Inject a lost response after real persistent Fake acceptance and capture.
        org.mockito.Mockito.doAnswer(call->{
            var accepted=(com.paymentsledger.provider.ProviderCallResult<com.paymentsledger.provider.ProviderModels.CheckoutSessionFact>)call.callRealMethod();
            fake.controls().attemptPayment(scope,accepted.payload().sessionId(),com.paymentsledger.provider.fake.FakeControls.AttemptOutcome.SUCCEED);
            return new com.paymentsledger.provider.ProviderCallResult<>(com.paymentsledger.provider.ProviderCallClassification.TIMEOUT_UNKNOWN,null,accepted.evidence());
        }).when(fake).createSession(org.mockito.ArgumentMatchers.any());
        var created=send(1,"/v1/payments",HttpMethod.POST,body("lost-http-response"),"lost-http-key");
        assertThat(created.getBody().path("code").asText()).isEqualTo("PROVIDER_UNKNOWN_RESULT");
        String payment=created.getBody().path("details").get(0).path("issue").asText();
        admin.update("UPDATE platform.recovery_task SET available_at=now()-interval '1 minute'");
        recovery.recoverDue(25);
        String path="/v1/payments/"+payment;
        var queried=send(1,path,HttpMethod.GET,null,null);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queried.getBody().path("resolutionStatus").asText()).isEqualTo("UNKNOWN");
        var progress=queried.getBody().path("recovery");
        assertThat(progress.path("status").asText()).isEqualTo("ESCALATED");
        assertThat(progress.path("reason").asText()).isNotBlank();
        assertThat(progress.hasNonNull("nextAttemptAt")).isFalse();
        assertThat(send(3,path,HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        recovery.scanL2(25);recovery.recoverDue(25);
        var replay=send(1,"/v1/payments",HttpMethod.POST,body("lost-http-response"),"lost-http-key");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("id").asText()).isEqualTo(payment);
        assertThat(replay.getBody().path("recovery").path("status").asText()).isEqualTo("ESCALATED");
        var after=fake.counters(scope);
        assertThat(after.sessionCreatedCount()-before.sessionCreatedCount()).isEqualTo(1);
        assertThat(after.paymentEffectCount()-before.paymentEffectCount()).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.payment",Long.class)).isEqualTo(1);
    }
}
