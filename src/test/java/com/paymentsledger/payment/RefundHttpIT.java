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
class RefundHttpIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");
    static final String SECRET="b".repeat(43), HASH=new BCryptPasswordEncoder(10).encode(SECRET);
    static final Path FAKE_PATH=directory();
    static Path directory(){try{return Files.createTempDirectory("pr11-http-fake-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
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
            String scopes=app==4?"ARRAY['payments:write','refunds:write']":"ARRAY['payments:read','payments:write','refunds:read','refunds:write']";
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
    ObjectNode refundBody(int app){
        var created=send(app,"/v1/payments",HttpMethod.POST,body("refund-payment"),"payment-key");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String payment=created.getBody().path("id").asText();
        admin.update("UPDATE platform.payment SET captured_amount_minor=100,payment_status='SUCCEEDED',latest_charge_id='ch_http_refund' WHERE id=?",PaymentIds.payment(payment));
        return json.createObjectNode().put("paymentId",payment).put("amountMinor",60).put("currency","USD").put("clientRefundKey","merchant-refund");
    }
    @Test void acceptedReservationReplayAndScopedGetFollowContract(){
        var body=refundBody(1);var created=send(1,"/v1/refunds",HttpMethod.POST,body,"refund-key");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("refundStatus").asText()).isEqualTo("RESERVED");
        assertThat(created.getBody().path("financialStatus").asText()).isEqualTo("NOT_APPLICABLE");
        var path=created.getHeaders().getLocation().toString();
        var replay=send(1,"/v1/refunds",HttpMethod.POST,body,"refund-key");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(send(1,path,HttpMethod.GET,null,null).getBody().path("id")).isEqualTo(created.getBody().path("id"));
        for(int app:List.of(2,3))assertThat(send(app,path,HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body.deepCopy().put("amountMinor",50),"refund-key").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.refund",Long.class)).isEqualTo(1);
    }
    @Test void missingKeyForgedScopeCurrencyAndMoneyAreRejected(){
        var body=refundBody(1);
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body,null).getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body.deepCopy().put("amountMinor",1.5),"refund-key").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body.deepCopy().put("organizationId",id(2).toString()),"refund-key").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body.deepCopy().put("currency","JPY"),"refund-key").getBody().path("code").asText()).isEqualTo("CURRENCY_NOT_ENABLED");
        assertThat(send(1,"/v1/refunds",HttpMethod.POST,body.deepCopy().put("amountMinor",101),"refund-key").getBody().path("code").asText()).isEqualTo("REFUND_LIMIT_EXCEEDED");
        assertThat(send(0,"/v1/refunds",HttpMethod.POST,body,"refund-key").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin.queryForObject("SELECT count(*) FROM platform.refund",Long.class)).isZero();
    }
    @Test void writeOnlyRefundCallerMayCreateButCannotGet(){
        var created=send(4,"/v1/refunds",HttpMethod.POST,refundBody(4),"refund-key");assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(4,created.getHeaders().getLocation().toString(),HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
