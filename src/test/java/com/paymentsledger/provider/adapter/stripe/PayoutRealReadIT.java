package com.paymentsledger.provider.adapter.stripe;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit read-only real test-account probe. Empty list never proves object, failure or bank paths. */
@EnabledIfEnvironmentVariable(named="PAYOUT_REAL_READ_TESTS",matches="true")
class PayoutRealReadIT {
 @Test void listAndReadExistingTestPayoutWithoutCreatingFundsOperations()throws Exception{
  var report=new LinkedHashMap<String,Object>();report.put("source","REAL_STRIPE_TEST_ACCOUNT_READ_ONLY");report.put("recordedAtUtc",Instant.now().toString());report.put("activePayoutCreation","N/A_OPTIONAL_NOT_CALLED");report.put("bankEvidence","NOT_RUN_SIMULATION_ONLY");report.put("failureReturn","NOT_RUN_NO_FORCED_FAILURE");report.put("payoutObjectRead","NOT_RUN");report.put("membershipRead","NOT_RUN");
  try{
   String key=required("STRIPE_API_KEY"),account=required("STRIPE_ACCOUNT_ID");assertTrue(key.startsWith("sk_test_")||key.startsWith("rk_test_"),"Test-mode credential required");
   var scope=new ProviderScope(UUID.fromString(required("STRIPE_ORGANIZATION_ID")),UUID.fromString(required("STRIPE_ENVIRONMENT_ID")),UUID.fromString(required("STRIPE_PROVIDER_ACCOUNT_ID")),false);
   report.put("accountSha256",hash(account));report.put("sdkVersion",StripeGateway.SDK_VERSION);report.put("apiVersion",StripeGateway.API_VERSION);
   var adapter=new StripeFinancialReadAdapter(new StripeGateway(key,scope,account,Clock.systemUTC()));
   Instant until=Instant.now().plusSeconds(1);var list=adapter.listPayouts(new PageQuery(scope,Instant.EPOCH,until,null,5));record(report,"list",list);
   assertEquals(ProviderCallClassification.SUCCESS,list.classification(),"Real read classification; inspect redacted evidence");assertNotNull(list.payload());
   report.put("listRead","VERIFIED");report.put("itemCount",list.payload().items().size());report.put("hasMore",list.payload().hasMore());
   report.put("continuationRead","NOT_RUN_NO_SECOND_PAGE_REQUESTED");
   if(list.payload().hasMore()){
    var next=adapter.listPayouts(new PageQuery(scope,Instant.EPOCH,until,list.payload().nextCursor(),5));record(report,"continuation",next);assertEquals(ProviderCallClassification.SUCCESS,next.classification());report.put("continuationRead","VERIFIED_TWO_BOUNDED_PAGES");
   }
   if(list.payload().items().isEmpty()){report.put("payoutObjectRead","NOT_RUN_EMPTY_TEST_ACCOUNT_LIST");return;}
   var first=list.payload().items().getFirst();var read=adapter.getPayout(scope,first.payoutId());record(report,"object",read);assertEquals(ProviderCallClassification.SUCCESS,read.classification());assertEquals(first.payoutId(),read.payload().payoutId());report.put("payoutObjectRead","VERIFIED");report.put("payoutIdSha256",hash(first.payoutId()));report.put("automatic",read.payload().automatic());
   for(String id:new String[]{read.payload().balanceTransactionId(),read.payload().failureBalanceTransactionId()})if(id!=null){var bt=adapter.getBalanceTransaction(scope,id);record(report,id.equals(read.payload().balanceTransactionId())?"outgoingBt":"failureBt",bt);assertEquals(ProviderCallClassification.SUCCESS,bt.classification());assertEquals(id,bt.payload().btId());}
   if(read.payload().automatic()){
    var membership=adapter.listBalanceTransactions(new ListBalanceTransactionsQuery(scope,Instant.EPOCH,until,null,5,first.payoutId()));record(report,"membership",membership);assertEquals(ProviderCallClassification.SUCCESS,membership.classification());report.put("membershipRead","VERIFIED_FIRST_BOUNDED_PAGE_ONLY");report.put("membershipHasMore",membership.payload().hasMore());
   }else report.put("membershipRead","N/A_MANUAL_PAYOUT_NO_GUESSED_MEMBERSHIP");
  }finally{Path out=Path.of("target/pr14-real-payout-read.json");Files.createDirectories(out.getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);}
 }
 private static void record(Map<String,Object> report,String name,ProviderCallResult<?> r){var e=r.evidence();var item=new LinkedHashMap<String,Object>();item.put("classification",r.classification().name());item.put("requestId",e.providerRequestId());item.put("requestedAt",e.requestedAt().toString());item.put("respondedAt",e.respondedAt()==null?null:e.respondedAt().toString());item.put("httpStatus",e.httpStatus());item.put("apiVersion",e.apiVersion());item.put("digest",e.rawDigest());report.put(name,item);}
 private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException("Required configuration missing: "+key);return v;}
 private static String hash(String v)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}
}
