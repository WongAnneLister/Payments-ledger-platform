package com.paymentsledger.ops;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
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

@Import(ObservabilityIT.Time.class)
@Testcontainers
@ActiveProfiles({"api", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT {
  static final Instant NOW=Instant.parse("2026-10-06T12:00:00Z");
  @TestConfiguration static class Time { @Bean @Primary Clock observationClock() { return Clock.fixed(NOW,ZoneOffset.UTC); } }
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");

  static final String SECRET = "o".repeat(43), HASH = new BCryptPasswordEncoder(10).encode(SECRET);
  static final Path FAKE = directory();

  static Path directory() {
    try {
      return Files.createTempDirectory("pr17-observability-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", () -> "payments_app");
    r.add("spring.datasource.password", () -> "pr02_test_only");
    r.add("spring.flyway.url", DB::getJdbcUrl);
    r.add("spring.flyway.user", DB::getUsername);
    r.add("spring.flyway.password", DB::getPassword);
    r.add("platform.provider.fake.enabled", () -> true);
    r.add("platform.provider.fake.directory", FAKE::toString);
    r.add("platform.provider.fake.seed", () -> 16);
    r.add("platform.provider.fake.initial-time", () -> Instant.now().toString());
    r.add("platform.provider.fake.retention", () -> "P1D");
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired io.micrometer.core.instrument.MeterRegistry registry;
  @Autowired com.paymentsledger.ledger.LedgerPostingService ledger;
  JdbcTemplate admin;

  static UUID id(int n) {
    return new UUID(16, n);
  }

  @BeforeEach
  void seed() {
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
    admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
    admin.update(
        "INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES"
            + " ('USD',2),('JPY',0)");
    for (int o : List.of(1, 2)) {
      admin.update("INSERT INTO platform.organization(id,code) VALUES (?,?)", id(o), "ops-" + o);
      for (int e : List.of(10 + o, 20 + o)) {
        admin.update(
            "INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES"
                + " (?,?,?,false)",
            id(e),
            id(o),
            "test-" + e);
        admin.update(
            "INSERT INTO"
                + " platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode)"
                + " VALUES (?,?,?,'FAKE',?,false)",
            id(100 + e),
            id(o),
            id(e),
            "ops-" + e);
        admin.update(
            "INSERT INTO"
                + " platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode)"
                + " VALUES (?,?,?,false,'ZERO')",
            id(o),
            id(e),
            id(100 + e));
        admin.update(
            "INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency)"
                + " VALUES (?,?,'USD'),(?,?,'JPY')",
            id(o),
            id(e),
            id(o),
            id(e));
        admin.update(
            "INSERT INTO"
                + " platform.ledger_account(id,organization_id,environment_id,currency,account_purpose,source_domain)"
                + " VALUES"
                + " (?,?,?,'USD','BANK_CASH','SIMULATED_BANK'),(?,?,?,'USD','OPENING_EQUITY','INTERNAL')",
            id(200 + e),
            id(o),
            id(e),
            id(300 + e),
            id(o),
            id(e));
      }
    }
    String[] roles = {
      "OPS_VIEWER",
      "OPS_OPERATOR",
      "OPS_REVIEWER",
      "OPS_ADMIN",
      "PARTNER_VIEWER",
      "OPS_REVIEWER",
      "OPS_OPERATOR",
      "OPS_OPERATOR"
    };
    for (int a = 1; a <= roles.length; a++) {
      int org = a == 7 ? 2 : 1, env = a == 8 ? 21 : 10 + org;
      admin.update(
          "INSERT INTO platform.ops_identity(id,organization_id,subject,role,partner_id) VALUES"
              + " (?,?,?,?,?)",
          id(400 + a),
          id(org),
          "actor-" + a,
          roles[a - 1],
          a == 5 ? id(900) : null);
      admin.update(
          "INSERT INTO"
              + " platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,secret_hash,not_before,expires_at)"
              + " VALUES (?,?,?,?,'ops',?,now()-interval '1 day',now()+interval '1 day')",
          id(500 + a),
          id(400 + a),
          id(org),
          id(env),
          HASH);
    }
  }

  ResponseEntity<JsonNode> call(
      int actor, String path, HttpMethod method, JsonNode body, String key) {
    var h = new HttpHeaders();
    if (actor > 0) h.set("Authorization", "OpsBearer ops_" + id(500 + actor) + "." + SECRET);
    if (key != null) h.set("Idempotency-Key", key);
    if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
  }


  JsonNode observe(int actor) {
    var r=call(actor,"/ops/observability",HttpMethod.GET,null,null);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(r.getHeaders().getCacheControl()).contains("no-store");
    return r.getBody();
  }
  JsonNode metric(JsonNode result,String name) {
    for(var m:result.path("metrics")) if(m.path("name").asText().equals(name)) return m;
    throw new AssertionError("Missing metric: "+name);
  }
  void value(JsonNode result,String name,String expected,String unit) {
    var m=metric(result,name); assertThat(m.path("status").asText()).isEqualTo("AVAILABLE");
    assertThat(m.path("value").isTextual()).isTrue(); assertThat(m.path("value").asText()).isEqualTo(expected);
    assertThat(m.path("unit").asText()).isEqualTo(unit);
  }
  UUID payment(int n,int org,int env,String currency,String resolution,String financial,int age,int unresolvedAge) {
    UUID app=id(700+env); admin.update("INSERT INTO platform.application(id,organization_id,environment_id,code) VALUES (?,?,?,'obs') ON CONFLICT DO NOTHING",app,id(org),id(env));
    admin.update("INSERT INTO platform.business_order(id,organization_id,environment_id,application_id,provider_account_id,currency,order_key,request_fingerprint,product_ref,price_version,quantity,unit_amount_minor,amount_minor,price_snapshot,share_policy_snapshot,created_at) VALUES (?,?,?,?,?,?,?,'fixture','fixture',1,1,100,100,'{}'::jsonb,'{}'::jsonb,?)",id(n+10000),id(org),id(env),app,id(100+env),currency,"order-"+n,Timestamp.from(NOW.minusSeconds(age)));
    admin.update("INSERT INTO platform.payment(id,organization_id,environment_id,application_id,provider_account_id,currency,business_order_id,amount_minor,resolution_status,financial_status,expires_at,created_at,unresolved_since) VALUES (?,?,?,?,?,?,?,100,?,?,?, ?,?)",id(n),id(org),id(env),app,id(100+env),currency,id(n+10000),resolution,financial,Timestamp.from(NOW.plusSeconds(3600)),Timestamp.from(NOW.minusSeconds(age)),unresolvedAge<0?null:Timestamp.from(NOW.minusSeconds(unresolvedAge)));
    return id(n);
  }
  void report(int n,String currency,String outcome,String residual,boolean checks,int age) {
    report(n,currency,outcome,residual,checks,age,"COMPLETE","{\"pagesComplete\":true}");
  }
  void report(int n,String currency,String outcome,String residual,boolean checks,int age,String runState,String completeness) {
    admin.update("INSERT INTO platform.import_window(id,organization_id,environment_id,provider_account_id,livemode,created_gte,created_lt,purpose) VALUES (?,?,?, ?,false,?,?,'BACKFILL')",id(n+20000),id(1),id(11),id(111),Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW));
    admin.update("INSERT INTO platform.reconciliation_run(id,organization_id,environment_id,provider_account_id,livemode,currency,business_date,business_zone,from_at,until_at,import_window_id,rule_version,created_at) VALUES (?,?,?,?,false,?,'2026-10-06','UTC',?,?,?,'fixture',?)",id(n),id(1),id(11),id(111),currency,Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW),id(n+20000),Timestamp.from(NOW.minusSeconds(age)));
    if(checks) {
      for(int c=1;c<=7;c++) admin.update("INSERT INTO platform.reconciliation_check(run_id,check_number,name,organization_id,environment_id,provider_account_id,livemode,currency,outcome,residual,reason,owner,input_versions,snapshot,completeness,created_at) VALUES (?,?,'fixture',?,?,?,false,?,?,?::numeric,'safe fixture','finance','{}'::jsonb,'snapshot',?::jsonb,?)",id(n),c,id(1),id(11),id(111),currency,c==1||c==3||c==5?outcome:"PASS",c==5?residual:"0",c==5?completeness:"{\"pagesComplete\":true}",Timestamp.from(NOW.minusSeconds(age)));
      admin.update("UPDATE platform.reconciliation_run SET state=?,finished_at=? WHERE id=?",runState,Timestamp.from(NOW.minusSeconds(age)),id(n));
    }
  }
  @Test void emptySnapshotRetainsNoDataInsteadOfInventingPassingReports() {
    var r=observe(1); assertThat(r.path("observedAt").asText()).isEqualTo(NOW.toString());
    assertThat(r.path("metrics")).hasSize(11); value(r,"unknown_payment_count","0","count");
    for(String name:List.of("unknown_payment_oldest_age","refund_reserved_age","webhook_lag","outbox_oldest_age","trial_balance_failures","reconciliation_residual","payout_unmatched_age","share_cap_violations")) {
      var m=metric(r,name); assertThat(m.path("status").asText()).as(name).isEqualTo("NO_DATA");
      assertThat(m.path("value").isNull()).isTrue(); assertThat(m.path("sourceAt").isNull()).isTrue();
    }
    assertThat(r.path("reconciliationResiduals")).isEmpty();
  }
  @Test void fixedClockPersistentPredicatesAndScopeDoNotMutateMoney() {
    payment(1001,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",7200,1800);
    payment(1002,2,12,"USD","UNKNOWN","PENDING_EVIDENCE",86400,-1);
    payment(1003,1,21,"USD","UNKNOWN","PENDING_EVIDENCE",86400,-1);
    payment(1004,1,11,"JPY","UNKNOWN","PENDING_EVIDENCE",86400,-1);
    admin.update("UPDATE platform.org_currency_enabled SET status='DISABLED' WHERE organization_id=? AND environment_id=? AND currency='JPY'",id(1),id(11));
    admin.update("INSERT INTO platform.refund(id,organization_id,environment_id,application_id,provider_account_id,currency,payment_id,amount_minor,request_fingerprint,source,refund_status,provider_charge_id,created_at,unresolved_since) VALUES (?,?,?,?,?,'USD',?,10,'fixture','LOCAL','REQUIRES_ACTION','ch_fixture',?,?)",id(1101),id(1),id(11),id(711),id(111),id(1001),Timestamp.from(NOW.minusSeconds(7200)),Timestamp.from(NOW.minusSeconds(3601)));
    admin.update("INSERT INTO platform.webhook_inbox(id,organization_id,environment_id,provider_account_id,livemode,provider_account_ref,event_id,event_type,object_id,object_type,event_created_at,received_at,verified_at,api_version,raw_digest,object_snapshot,available_at) VALUES (?,?,?,?,false,'ops-11','evt_fixture','fixture','pi_fixture','payment_intent',?,?,?,'fixture',?,'{}'::jsonb,?)",id(1201),id(1),id(11),id(111),Timestamp.from(NOW.minusSeconds(400)),Timestamp.from(NOW.minusSeconds(301)),Timestamp.from(NOW.minusSeconds(301)),"a".repeat(64),Timestamp.from(NOW));
    admin.update("INSERT INTO platform.payout(id,organization_id,environment_id,provider_account_id,livemode,provider_payout_id,currency,amount_minor,automatic,last_observed_at,created_at,bank_match_state) VALUES (?,?,?,?,false,'po_fixture','USD',100,true,?,?,'PARTIAL')",id(1301),id(1),id(11),id(111),Timestamp.from(NOW.minusSeconds(300)),Timestamp.from(NOW.minusSeconds(86401)));
    var before=admin.queryForList("SELECT id,captured_amount_minor,version FROM platform.payment ORDER BY id");
    var r=observe(1); value(r,"unknown_payment_count","1","count"); value(r,"unknown_payment_oldest_age","1800","seconds");
    value(r,"refund_reserved_age","3601","seconds"); value(r,"webhook_lag","301","seconds"); value(r,"payout_unmatched_age","86401","seconds"); value(r,"ledger_pending_evidence","1","count");
    assertThat(r.path("alerts").toString()).contains("refund_reserved_age","payout_unmatched_age");
    assertThat(admin.queryForList("SELECT id,captured_amount_minor,version FROM platform.payment ORDER BY id")).isEqualTo(before);
  }
  @Test void postedUnknownSuspenseAndPendingEvidenceRemainObservableAndOutboxAgeUsesOccurrence() {
    payment(2301,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",100,-1);
    admin.update("INSERT INTO platform.ledger_account(id,organization_id,environment_id,provider_account_id,currency,account_purpose,source_domain,balance_domain) VALUES (?,?,?,?,'USD','PSP_FUNDS','PROVIDER','PAYMENTS'),(?,?,?,?,'USD','SUSPENSE','PROVIDER','PAYMENTS')",id(2302),id(1),id(11),id(111),id(2303),id(1),id(11),id(111));
    var journal=ledger.post(new com.paymentsledger.ledger.LedgerScope(id(1),id(11),"USD",id(111)),new com.paymentsledger.ledger.PostingCommand("obs-unknown","L-UNKNOWN","PROVIDER","obs-unknown",json.createObjectNode(),List.of(new com.paymentsledger.ledger.PostingLine(id(2302),com.paymentsledger.ledger.Side.DEBIT,10),new com.paymentsledger.ledger.PostingLine(id(2303),com.paymentsledger.ledger.Side.CREDIT,10)),null));
    for(int n:List.of(2401,2402)) {
      admin.update("INSERT INTO platform.provider_observation(id,organization_id,environment_id,provider_account_id,call_type,classification,evidence,observation_domain,livemode,fetched_at) VALUES (?,?,?,?,'LIST_BT','FACT','{}'::jsonb,'FINANCIAL',false,?)",id(n+10000),id(1),id(11),id(111),Timestamp.from(NOW.minusSeconds(100)));
      admin.update("INSERT INTO platform.balance_transaction(id,organization_id,environment_id,provider_account_id,livemode,bt_id,amount_minor,fee_minor,net_minor,currency,balance_domain,provider_created_at,provider_status,api_version,fetched_at,immutable_fact,fact,first_observation_id,state,reason,journal_id,created_at) VALUES (?,?,?,?,false,?,10,0,10,'USD','PAYMENTS',?,'available','fixture',?,'{}'::jsonb,'{}'::jsonb,?,?,?,?,?)",id(n),id(1),id(11),id(111),"bt_fixture"+n,Timestamp.from(NOW.minusSeconds(100)),Timestamp.from(NOW.minusSeconds(100)),id(n+10000),n==2401?"POSTED":"PENDING_EVIDENCE",n==2401?"UNKNOWN_SOURCE":"TRUSTED_BUSINESS_EVIDENCE_MISSING",n==2401?journal:null,Timestamp.from(NOW.minusSeconds(100)));
    }
    admin.update("INSERT INTO platform.outbox_event(id,organization_id,environment_id,application_id,provider_account_id,currency,payment_id,order_id,event_type,resource_version,business_key,origin,occurred_at,payload,payload_digest) VALUES (?,?,?,?,?,'USD',?,?,'payment.state_changed',1,'obs','NATIVE',?,'{}',encode(sha256(convert_to('{}','UTF8')),'hex'))",id(2501),id(1),id(11),id(711),id(111),id(2301),id(12301),Timestamp.from(NOW.minusSeconds(601)));
    var r=observe(1); value(r,"unclassified_bt_count","1","count"); value(r,"ledger_pending_evidence","2","count"); value(r,"outbox_oldest_age","601","seconds");
  }

  @Test void latestPerCurrencyResidualsNeverCancelAndMissingNewChecksNeverFallBack() {
    report(2001,"USD","FAIL","9007199254740993",true,300);
    report(2002,"JPY","FAIL","-9007199254740993",true,250);
    var r=observe(1); assertThat(r.path("reconciliationResiduals")).hasSize(2);
    assertThat(r.path("reconciliationResiduals").toString()).contains("9007199254740993","-9007199254740993","FAIL","pagesComplete");
    assertThat(metric(r,"reconciliation_residual").path("status").asText()).isEqualTo("NO_DATA");
    assertThat(r.path("alerts").toString()).contains("reconciliation_residual"); value(r,"trial_balance_failures","2","count");
    report(2003,"USD","PASS","0",true,100);
    report(2004,"USD","INCOMPLETE","0",false,50);
    r=observe(1); assertThat(r.path("reconciliationResiduals").toString()).contains(id(2004).toString(),"INCOMPLETE").doesNotContain(id(2001).toString(),id(2003).toString());
    assertThat(metric(r,"trial_balance_failures").path("status").asText()).isEqualTo("NO_DATA");
  }
  @Test void singleCurrencyExactResidualAndLatestPassReplaceHistoricalFailure() {
    report(2101,"USD","FAIL","9007199254740993",true,300);
    value(observe(1),"reconciliation_residual","9007199254740993","minor");
    report(2102,"USD","PASS","0",true,100);
    var r=observe(1); value(r,"reconciliation_residual","0","minor"); value(r,"trial_balance_failures","0","count"); value(r,"share_cap_violations","0","count");
  }
  @Test void incompleteFailedResidualIsVisibleByDimensionButNeverAScalarOrGauge() {
    report(2161,"USD","FAIL","42",true,100,"INCOMPLETE","{\"pagesComplete\":true,\"unresolved\":true,\"knownLocalViolation\":true}");
    var r=observe(1); var scalar=metric(r,"reconciliation_residual");
    assertThat(scalar.path("status").asText()).isEqualTo("NO_DATA"); assertThat(scalar.path("value").isNull()).isTrue();
    var row=r.path("reconciliationResiduals").get(0); assertThat(row.path("residualMinor").asText()).isEqualTo("42");
    assertThat(row.path("outcome").asText()).isEqualTo("FAIL"); assertThat(row.path("dataCompleteness").path("unresolved").asBoolean()).isTrue();
    assertThat(row.path("dataCompleteness").path("runState").asText()).isEqualTo("INCOMPLETE");
    assertThat(r.path("alerts").toString()).contains("CRITICAL","reconciliation_residual");
    assertThat(registry.find("payments.observability.reconciliation_residual").gauge().value()).isNaN();
  }
  @Test void defensiveMissingInputFlagsPreventScalarEvenWithCompleteRunAndFailCheck() {
    for(String completeness:List.of("{\"unresolved\":true}","{\"pagesComplete\":false}","{\"pending\":1}","{\"unclassified\":1}","{\"unknown\":1}","{\"waiting\":1}")) {
      admin.execute("TRUNCATE platform.reconciliation_run,platform.import_window CASCADE");
      report(2162,"USD","FAIL","0",true,100,"COMPLETE",completeness);
      var r=observe(1); assertThat(metric(r,"reconciliation_residual").path("status").asText()).isEqualTo("NO_DATA");
      assertThat(metric(r,"reconciliation_residual").path("value").isNull()).isTrue();
      assertThat(r.path("reconciliationResiduals").get(0).path("outcome").asText()).isEqualTo("FAIL");
      assertThat(registry.find("payments.observability.reconciliation_residual").gauge().value()).isNaN();
    }
  }
  @Test void fractionalNumericResidualIsAnExactDecimalStringWithoutRounding() {
    report(2151,"USD","FAIL","9007199254740993.125",true,100);
    var r=observe(1); value(r,"reconciliation_residual","9007199254740993.125","minor");
    assertThat(r.path("reconciliationResiduals").get(0).path("residualMinor").asText()).isEqualTo("9007199254740993.125");
  }
  @Test void globalAndScopedObservationsExcludeLiveRowsInsertedAfterStartup() {
    payment(2801,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",100,-1);
    admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'live-fixture')",id(3));
    admin.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'live',true)",id(13),id(3));
    admin.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'STRIPE','live-fixture-ref',true)",id(113),id(3),id(13));
    admin.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD')",id(3),id(13));
    payment(2802,3,13,"USD","UNKNOWN","PENDING_EVIDENCE",86400,-1);
    var gauge=registry.find("payments.observability.unknown_payment_count").gauge(); assertThat(gauge.value()).isEqualTo(1);
    value(observe(1),"unknown_payment_count","1","count");
    admin.update("UPDATE platform.org_currency_enabled SET status='DISABLED' WHERE organization_id=? AND environment_id=? AND currency='USD'",id(1),id(11));
    assertThat(gauge.value()).isEqualTo(0);value(observe(1),"unknown_payment_count","0","count");
  }
  @Test void generalOpsRolesReadPartnerDeniedForeignAssertionsAndPublicActuatorHidden() {
    for(int actor:List.of(1,2,3,4)) observe(actor);
    assertThat(call(0,"/ops/observability",HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(5,"/ops/observability",HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(1,"/ops/observability?organizationId="+id(2),HttpMethod.GET,null,null).getStatusCode()).isIn(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND);
    assertThat(call(1,"/ops/observability?providerAccountId="+id(112),HttpMethod.GET,null,null).getStatusCode()).isIn(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND);
    assertThat(http.getForEntity("/actuator/metrics",String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(http.getForEntity("/actuator/health",String.class).getBody()).doesNotContain("observability","components");
  }
  @Test void gaugesSampleDurableActiveGlobalScopeWithoutAnyOpsRequestAndConflictsAreNotUnknown() {
    payment(2601,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",1800,-1);
    payment(2602,2,12,"USD","UNKNOWN","PENDING_EVIDENCE",3600,-1);
    payment(2603,1,11,"USD","CONFLICT","CONFLICT",7200,-1);
    var gauge=registry.find("payments.observability.unknown_payment_count").gauge();
    assertThat(gauge).isNotNull(); assertThat(gauge.getId().getTags()).isEmpty(); assertThat(gauge.value()).isEqualTo(2);
    payment(2604,1,21,"USD","UNKNOWN","PENDING_EVIDENCE",100,-1);
    assertThat(gauge.value()).isEqualTo(3);
    value(observe(1),"unknown_payment_count","1","count");
    assertThat(gauge.value()).isEqualTo(3);
    admin.update("UPDATE platform.provider_account SET status='DISABLED' WHERE id=?",id(121));
    assertThat(gauge.value()).isEqualTo(2);
    admin.execute("REVOKE SELECT ON platform.payment FROM payments_app");
    try { assertThat(gauge.value()).isNaN(); }
    finally { admin.execute("GRANT SELECT ON platform.payment TO payments_app"); }
  }
  @Test void successfulOperationalReadEmitsOnlyRealKnownCorrelationAssociations() throws Exception {
    payment(2701,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",100,-1);
    var logger=(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.paymentsledger.observability.CorrelationLog.class);
    var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start(); logger.addAppender(events);
    try {
      var h=new HttpHeaders(); h.set("Authorization","OpsBearer ops_"+id(501)+"."+SECRET); h.set("X-Request-Id","req-log-fixture");
      var r=http.exchange("/ops/payments/"+id(2701),HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(events.list).isNotEmpty();
      var log=json.readTree(events.list.getFirst().getFormattedMessage());
      assertThat(log.path("requestId").asText()).isEqualTo("req-log-fixture");
      assertThat(log.path("paymentId").asText()).isNotBlank(); assertThat(log.path("businessOrderId").asText()).isNotBlank();
      assertThat(log.has("providerRequestId") || log.has("reason") || log.has("metadata")).isFalse();
    } finally { logger.detachAppender(events); events.stop(); }
  }
  @Test void realTraceProjectsAndEmitsOnlySafeKnownOperationProviderAndEventIds() throws Exception {
    payment(2901,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",100,-1);
    UUID operation=id(2902);
    admin.update("INSERT INTO platform.provider_operation(id,organization_id,environment_id,application_id,provider_account_id,currency,operation_target,payment_id,operation_type,operation_key,request_fingerprint,command,expires_at) VALUES (?,?,?,?,?,'USD','PAYMENT',?,'CREATE_CHECKOUT','trace-fixture','fixture','{}'::jsonb,?)",operation,id(1),id(11),id(711),id(111),id(2901),Timestamp.from(NOW.plusSeconds(3600)));
    admin.update("INSERT INTO platform.recovery_task(id,organization_id,environment_id,application_id,provider_account_id,currency,payment_id,operation_id,available_at) VALUES (?,?,?,?,?,'USD',?,?,?)",id(2903),id(1),id(11),id(711),id(111),id(2901),operation,Timestamp.from(NOW));
    String sensitive="reqsk_test_"+"synthetic";
    for(int n:List.of(2904,2905)) {
      var evidence=json.createObjectNode().put("providerRequestId",n==2904?"req-provider-fixture":sensitive).put("reason",sensitive);
      admin.update("INSERT INTO platform.provider_observation(id,organization_id,environment_id,application_id,provider_account_id,currency,operation_id,payment_id,call_type,classification,evidence) VALUES (?,?,?,?,?,'USD',?,?,'QUERY_PAYMENT','SUCCESS',?::jsonb)",id(n),id(1),id(11),id(711),id(111),operation,id(2901),evidence.toString());
    }
    UUID journal=ledger.post(new com.paymentsledger.ledger.LedgerScope(id(1),id(11),"USD",null),new com.paymentsledger.ledger.PostingCommand("trace-journal","L-OPENING","INTERNAL","trace-journal",json.createObjectNode(),List.of(new com.paymentsledger.ledger.PostingLine(id(211),com.paymentsledger.ledger.Side.DEBIT,10),new com.paymentsledger.ledger.PostingLine(id(311),com.paymentsledger.ledger.Side.CREDIT,10)),null));
    String event=admin.queryForObject("SELECT financial_event_id::text FROM platform.journal WHERE id=?",String.class,journal);
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.paymentsledger.observability.CorrelationLog.class);
    var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start();logger.addAppender(events);
    try {
      var r=call(1,"/ops/payments/"+id(2901),HttpMethod.GET,null,null);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(r.getBody().toString().contains(sensitive)).isFalse();
      assertThat(r.getBody().path("operations").get(0).path("operationId").asText()).isEqualTo(operation.toString());
      var j=call(1,"/ops/ledger/journals/"+journal,HttpMethod.GET,null,null);assertThat(j.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(j.getBody().path("financialEventId").asText()).isEqualTo(event);
      var emitted=json.createArrayNode(); for(var logged:events.list) { assertThat(logged.getFormattedMessage().contains(sensitive)).isFalse(); emitted.add(json.readTree(logged.getFormattedMessage())); }
      assertThat(emitted.toString()).contains(operation.toString(),"req-provider-fixture",event,journal.toString());
      assertThat(emitted.toString()).doesNotContain("reason","metadata");
    } finally {logger.detachAppender(events);events.stop();}
  }
  @Test void disabledProvidersDisappearAndDatabaseFailureIs503NotFakeZero() {
    payment(2201,1,11,"USD","UNKNOWN","PENDING_EVIDENCE",1000,-1);
    admin.update("UPDATE platform.provider_account SET status='DISABLED' WHERE id=?",id(111));
    value(observe(1),"unknown_payment_count","0","count");
    admin.execute("REVOKE SELECT ON platform.payment FROM payments_app");
    try { var r=call(1,"/ops/observability",HttpMethod.GET,null,null); assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(r.getBody().path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE"); }
    finally { admin.execute("GRANT SELECT ON platform.payment TO payments_app"); }
  }
}
