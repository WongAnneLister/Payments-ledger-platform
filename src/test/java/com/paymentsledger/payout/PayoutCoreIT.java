package com.paymentsledger.payout;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.ledger.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PayoutCoreIT {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16").withInitScript("db/pr02-runtime-role.sql");
 final ProviderScope scope=new ProviderScope(id(1),id(2),id(4),false);
 final Instant at=Instant.parse("2026-09-20T00:00:00Z");
 JdbcTemplate jdbc,admin;DataSourceTransactionManager manager;ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();Object service;
 static UUID id(int n){return new UUID(0,n);}
 @BeforeEach void setup() throws Exception {
  Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas("platform").load().migrate();
  admin=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
  var ds=new DriverManagerDataSource(DB.getJdbcUrl(),"payments_app","pr02_test_only");jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
  admin.execute("TRUNCATE platform.organization,platform.currency_config CASCADE");
  admin.update("INSERT INTO platform.organization(id,code) VALUES (?,'payout')",id(1));
  admin.update("INSERT INTO platform.environment_config(id,organization_id,code,livemode) VALUES (?,?,'test',false)",id(2),id(1));
  admin.update("INSERT INTO platform.provider_account(id,organization_id,environment_id,provider,provider_account_ref,livemode) VALUES (?,?,?,'FAKE','fixture',false)",id(4),id(1),id(2));
  admin.update("INSERT INTO platform.currency_config(code,minor_unit_exponent) VALUES ('USD',2)");
  admin.update("INSERT INTO platform.org_currency_enabled(organization_id,environment_id,currency) VALUES (?,?,'USD')",id(1),id(2));
  admin.update("INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode) VALUES (?,?,?,false,'ZERO')",id(1),id(2),id(4));
  service=Class.forName("com.paymentsledger.payout.PayoutService").getConstructor(JdbcTemplate.class,org.springframework.transaction.PlatformTransactionManager.class,ObjectMapper.class,LedgerPostingService.class).newInstance(jdbc,manager,mapper,new LedgerPostingService(jdbc,mapper));
 }
 UUID accept(String status,String outgoing,String returned,Instant observed) throws Exception {return (UUID)service.getClass().getMethod("accept",ProviderScope.class,PayoutFact.class,ProviderEvidence.class).invoke(service,scope,new PayoutFact("po_test",status,5700,"USD",outgoing,returned,true,at.plusSeconds(86400),null),new ProviderEvidence("req",null,observed,observed,200,null,"digest","v"));}
 Map<String,Object> payout(){return jdbc.queryForMap("SELECT * FROM platform.payout WHERE provider_payout_id='po_test'");}
 long balance(String purpose){return jdbc.queryForObject("SELECT coalesce(sum(p.balance_minor),0) FROM platform.balance_projection p JOIN platform.ledger_account a ON a.id=p.ledger_account_id WHERE a.account_purpose=?",Long.class,purpose);}
 String post(String bt,String type,long amount,long fee) throws Exception {
  var fact=new BalanceTransactionFact(bt,type,amount,fee,amount-fee,"USD","PAYMENTS","po_test","payout","available",at,null,List.of(),"digest");
  UUID observation=UUID.randomUUID(),row=UUID.randomUUID();
  jdbc.update("INSERT INTO platform.provider_observation(id,organization_id,environment_id,provider_account_id,observation_domain,livemode,call_type,classification,evidence,fetched_at) VALUES (?,?,?,?,'FINANCIAL',false,'GET','SUCCESS','{}'::jsonb,now())",observation,id(1),id(2),id(4));
  jdbc.update("INSERT INTO platform.balance_transaction(id,organization_id,environment_id,provider_account_id,livemode,bt_id,amount_minor,fee_minor,net_minor,currency,source_object_id,source_object_type,business_type,balance_domain,provider_created_at,provider_status,api_version,fetched_at,immutable_fact,fact,first_observation_id) VALUES (?,?,?,?,false,?,?,?,?,'USD','po_test','payout',?,'PAYMENTS',?,'available','v',?,'{}'::jsonb,?::jsonb,?)",row,id(1),id(2),id(4),bt,amount,fee,amount-fee,type,Timestamp.from(at),Timestamp.from(at),mapper.valueToTree(fact).toString(),observation);
  return new TransactionTemplate(manager).execute(tx->{try {
   service.getClass().getMethod("lock",ProviderScope.class,String.class).invoke(service,scope,"po_test");
   jdbc.queryForList("SELECT id FROM platform.balance_transaction WHERE id=? FOR UPDATE",row);
   return (String)service.getClass().getMethod("postLocked",ProviderScope.class,BalanceTransactionFact.class,UUID.class).invoke(service,scope,fact,row);
  }catch(Exception e){throw new IllegalStateException(e);}});
 }
 @Test void paidOnlyRetainsObservationAndNeverCreatesBankCash() throws Exception {accept("paid","txn_out",null,at);assertThat(payout()).containsEntry("provider_status","paid").containsEntry("outgoing_minor",0L);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.journal",Long.class)).isZero();assertThat(balance("BANK_CASH")).isZero();}
 @Test void outgoingAndFailureRestoreProviderFundsWithoutBankCash() throws Exception {accept("failed","txn_out","txn_return",at);assertThat(post("txn_out","payout",-5700,12)).isEqualTo("POSTED");assertThat(payout()).containsEntry("transit_minor",5700L).containsEntry("psp_fee_minor",12L);assertThat(balance("PSP_FUNDS")).isEqualTo(-5712);assertThat(post("txn_return","payout_failure",5700,-12)).isEqualTo("POSTED");assertThat(payout()).containsEntry("transit_minor",0L).containsEntry("returned_minor",5700L).containsEntry("psp_fee_minor",0L);assertThat(balance("PSP_FUNDS")).isZero();assertThat(balance("BANK_CASH")).isZero();assertThat(new BalanceRebuildService(jdbc,Clock.systemUTC()).rebuild(new LedgerScope(id(1),id(2),"USD",null),BalanceRebuildService.Mode.OPENING_JOURNAL,null).matches()).isTrue();}
 @Test void partialReturnOnlyConsumesAvailableTransitAndPreservesFees() throws Exception {accept("failed","txn_out","txn_return",at);post("txn_out","payout",-5700,5);post("txn_return","payout_failure",1200,2);assertThat(payout()).containsEntry("transit_minor",4500L).containsEntry("returned_minor",1200L).containsEntry("psp_fee_minor",7L);}
 @Test void returnBeforeOutRemainsPending() throws Exception {accept("failed","txn_out","txn_return",at);assertThat(post("txn_return","payout_failure",5700,0)).isEqualTo("PENDING_EVIDENCE");assertThat(balance("PSP_FUNDS")).isZero();}
 @Test void unknownProviderStatusCannotMoveMoney() throws Exception {accept("future_status","txn_out",null,at);assertThat(payout()).containsEntry("conflict",true);assertThat(post("txn_out","payout",-5700,0)).isEqualTo("PENDING_EVIDENCE");assertThat(balance("PSP_FUNDS")).isZero();}
 @Test void wrongLinkedBtCreatesCaseAndQuarantinesWithoutMoney() throws Exception {accept("pending","txn_expected",null,at);assertThat(post("txn_wrong","payout",-5700,0)).isEqualTo("QUARANTINED");assertThat(payout()).containsEntry("conflict",true);assertThat(jdbc.queryForObject("SELECT reason FROM platform.payout_case",String.class)).isEqualTo("PAYOUT_BT_LINK_CONFLICT");assertThat(balance("PSP_FUNDS")).isZero();}
 @Test void declaredPayoutIdentityCannotBeChangedByLaterObservation() throws Exception {accept("pending","txn_out",null,at);service.getClass().getMethod("accept",ProviderScope.class,PayoutFact.class,ProviderEvidence.class).invoke(service,scope,new PayoutFact("po_test","pending",5800,"USD","txn_other",null,false,at,null),new ProviderEvidence("req",null,at.plusSeconds(1),at.plusSeconds(1),200,null,"digest","v"));assertThat(payout()).containsEntry("amount_minor",5700L).containsEntry("automatic",true).containsEntry("conflict",true);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_bt_link WHERE bt_id='txn_other'",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_observation",Long.class)).isEqualTo(2);}
 @Test void staleObservationRetainsRawFactWithoutRegressingTerminalStatus() throws Exception {accept("paid","txn_out",null,at);accept("pending","txn_out","txn_return",at.minusSeconds(1));assertThat(payout()).containsEntry("provider_status","paid").containsEntry("conflict",false);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_observation",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_bt_link WHERE role='RETURN'",Long.class)).isEqualTo(1);accept("failed","txn_out","txn_return",at.plusSeconds(1));assertThat(payout()).containsEntry("provider_status","paid").containsEntry("conflict",true);}
 @Test void staleUnknownStateRemainsRawEvidenceWithoutPoisoningNewerKnownState() throws Exception {accept("paid","txn_out",null,at);accept("future_status","txn_out",null,at.minusSeconds(5));assertThat(payout()).containsEntry("provider_status","paid").containsEntry("conflict",false);assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payout_observation",Long.class)).isEqualTo(2);}
 @Test void readOnlyEvidenceMethodsAreScopedToProviderAndPayout() throws Exception {UUID payout=accept("pending","txn_out",null,at);post("txn_out","payout",-5700,0);for(String method:List.of("observations","financialFacts","cases","bankMatches")){var read=service.getClass().getMethod(method,ProviderScope.class,UUID.class);assertThat((List<?>)read.invoke(service,new ProviderScope(id(91),id(92),id(93),false),payout)).isEmpty();assertThat((List<?>)read.invoke(service,scope,id(94))).isEmpty();}assertThat((List<?>)service.getClass().getMethod("observations",ProviderScope.class,UUID.class).invoke(service,scope,payout)).hasSize(1);assertThat((List<?>)service.getClass().getMethod("financialFacts",ProviderScope.class,UUID.class).invoke(service,scope,payout)).hasSize(1);}
 @Test void unbackedCountersAndHistoryMutationAreRejected() throws Exception {UUID payout=accept("pending","txn_out",null,at);assertThatThrownBy(()->jdbc.update("UPDATE platform.payout SET outgoing_minor=1,transit_minor=1 WHERE id=?",payout)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->admin.update("DELETE FROM platform.payout_observation WHERE payout_id=?",payout)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
}
