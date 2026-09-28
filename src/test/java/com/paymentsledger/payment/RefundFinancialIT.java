package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.financialingestion.*;
import com.paymentsledger.identity.TenantContext;
import com.paymentsledger.ledger.LedgerPostingService;
import com.paymentsledger.notification.*;
import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.fake.FakeControls;
import com.paymentsledger.refund.RefundFinancialEvidence;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class RefundFinancialIT extends PaymentCoreFixture {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final TenantContext refundsContext=new TenantContext(id(1),id(2),id(3),id(4),Set.of("USD"),false,Set.of("refunds:read","refunds:write"));
    RefundRepository refunds;
    FinancialImportService importer;
    FinancialReplayService replay;
    UUID paymentId;
    String chargeId;
    @BeforeEach void setupFinancialRefund(){
        var starts=java.sql.Timestamp.from(fake.clock().instant().minus(Duration.ofDays(1)));
        admin.update("UPDATE platform.price_fixture SET valid_from=?",starts);admin.update("UPDATE platform.share_policy_fixture SET valid_from=?",starts);
        var payment=service.create(context,"refund-ledger-pay",command("refund-ledger-pay")).resource();
        paymentId=payment(payment);chargeId=fake.controls().attemptPayment(scope,session(payment),FakeControls.AttemptOutcome.SUCCEED).latestChargeId();service.get(context,paymentId);
        admin.update("INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode,business_zone) VALUES (?,?,?,false,'ZERO','UTC')",id(1),id(2),id(4));
        for(String purpose:List.of("PSP_FUNDS","PSP_FEE_EXPENSE","SALES_REVENUE","SALES_RETURNS","SUSPENSE")){
            boolean external=!purpose.startsWith("SALES_");
            admin.update("INSERT INTO platform.ledger_account(organization_id,environment_id,currency,account_purpose,source_domain,provider_account_id,balance_domain) VALUES (?,?,'USD',?,?,?,?)",id(1),id(2),purpose,external?"PROVIDER":"INTERNAL",external?id(4):null,external?"PAYMENTS":null);
        }
        refunds=new RefundRepository(jdbc,tx,json,fake.clock());
        importer=new FinancialImportService(jdbc,tx,json,fake,fake.clock());
        replay=new FinancialReplayService(jdbc,tx,json,new LedgerPostingService(jdbc,json),new RefundFinancialEvidence(refunds));
    }
    ProviderEvidence evidence(){return new ProviderEvidence(null,null,fake.clock().instant(),fake.clock().instant(),200,null,"a".repeat(64),"fixture");}
    RefundFact refund(String status,String debit,String returned){return new RefundFact("re_financial",chargeId,status,4000,"usd",debit,status.equals("failed")?"declined":null,returned,fake.clock().instant());}
    BalanceTransactionFact bt(String id,String type,long amount,long fee){
        Instant at=fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new BalanceTransactionFact(id,type,amount,fee,amount-fee,"usd","PAYMENTS","re_financial","refund","pending",at,at.plusSeconds(86400),List.of(),"b".repeat(64));
    }
    void importFact(BalanceTransactionFact fact){
        fake.controls().putBalanceTransaction(scope,fact,fake.clock().instant(),null);fake.clock().advance(Duration.ofSeconds(2));
        var window=importer.createWindow(scope,fact.createdAt().minusSeconds(1),fake.clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),"BACKFILL");
        importer.runWindow(window,10);
    }
    long balance(String purpose){return jdbc.queryForObject("SELECT coalesce(sum(b.balance_minor),0) FROM platform.balance_projection b JOIN platform.ledger_account a ON a.id=b.ledger_account_id WHERE a.account_purpose=?",Long.class,purpose);}
    void assertProjection(){
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.ledger_account a LEFT JOIN platform.balance_projection p ON p.ledger_account_id=a.id LEFT JOIN (SELECT ledger_account_id,sum(CASE WHEN side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END) rebuilt FROM platform.journal_entry GROUP BY ledger_account_id) e ON e.ledger_account_id=a.id WHERE coalesce(p.balance_minor,0)<>coalesce(e.rebuilt,0)",Long.class)).isZero();
    }
    @Test void refundBtWaitsForAssociationThenPostsEvenWhileBusinessPending(){
        importFact(bt("txn_debit","refund",-4000,30));
        assertThat(replay.replay(scope,"txn_debit")).isEqualTo("PENDING_EVIDENCE");assertThat(count("journal")).isZero();
        UUID id=refunds.accept(scope,refund("pending","txn_debit",null),evidence(),null);
        assertThat(replay.replay(scope,"txn_debit")).isEqualTo("POSTED");
        assertThat(refunds.read(refundsContext,id)).containsEntry("refundStatus","PENDING").containsEntry("financialStatus","POSTED");
        replay.replay(scope,"txn_debit");assertThat(count("journal")).isEqualTo(1);assertThat(balance("PSP_FUNDS")).isEqualTo(-4030);assertProjection();
    }
    @Test void failedRefundReturnIsANewJournalWithActualFeesAndNeverDeletesDebit(){
        UUID id=refunds.accept(scope,refund("pending","txn_debit",null),evidence(),null);
        importFact(bt("txn_debit","refund",-4000,30));replay.replay(scope,"txn_debit");
        UUID original=jdbc.queryForObject("SELECT journal_id FROM platform.balance_transaction WHERE bt_id='txn_debit'",UUID.class);
        refunds.accept(scope,refund("failed","txn_debit","txn_return"),evidence(),id);
        assertThat(refunds.read(refundsContext,id)).containsEntry("refundStatus","FAILED").containsEntry("financialStatus","PARTIALLY_POSTED");
        importFact(bt("txn_return","refund_failure",4000,-30));assertThat(replay.replay(scope,"txn_return")).isEqualTo("POSTED");
        replay.replay(scope,"txn_return");
        assertThat(jdbc.queryForObject("SELECT journal_id FROM platform.balance_transaction WHERE bt_id='txn_debit'",UUID.class)).isEqualTo(original);
        assertThat(count("journal")).isEqualTo(2);assertThat(balance("PSP_FUNDS")).isZero();assertThat(balance("PSP_FEE_EXPENSE")).isZero();assertProjection();
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","POSTED");
        byte[] payload=jdbc.queryForObject("SELECT payload FROM platform.outbox_event WHERE event_type='refund.failed'",String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var inbox=new ConsumerInboxService(jdbc,tx,json,fake.clock());
        assertThat(inbox.accept(payload)).isEqualTo(ConsumerInboxService.Acceptance.CREATED);assertThat(inbox.accept(payload)).isEqualTo(ConsumerInboxService.Acceptance.DUPLICATE);
        assertThat(count("delivery_task")).isEqualTo(1);
    }
    @Test void returnBeforeDebitRetainsPartialFinancialStatusUntilBothActualFactsPost(){
        UUID id=refunds.accept(scope,refund("failed","txn_late_debit","txn_early_return"),evidence(),null);
        importFact(bt("txn_early_return","refund_failure",4000,0));assertThat(replay.replay(scope,"txn_early_return")).isEqualTo("POSTED");
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","PARTIALLY_POSTED");
        importFact(bt("txn_late_debit","refund",-4000,0));assertThat(replay.replay(scope,"txn_late_debit")).isEqualTo("POSTED");
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","POSTED");assertThat(balance("PSP_FUNDS")).isZero();assertProjection();
    }
    @Test void failedWithoutAnyBtDoesNotInventFundsAndWrongAmountCannotPost(){
        UUID id=refunds.accept(scope,refund("failed",null,null),evidence(),null);
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","NOT_APPLICABLE");assertThat(count("journal")).isZero();
        refunds.accept(scope,refund("failed","txn_wrong",null),evidence(),id);
        importFact(bt("txn_wrong","refund",-3999,0));assertThat(replay.replay(scope,"txn_wrong")).isEqualTo("QUARANTINED");assertThat(count("journal")).isZero();
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","CONFLICT");
    }
    @Test void workerDiscoversRefundSourceFromBtWithoutWebhookOrLocalRefund(){
        var remote=fake.createRefund(new CreateRefundCommand(scope,"dashboard-fixture-refund",chargeId,null,4000,null)).payload();
        fake.controls().linkRefundTransactions(scope,remote.refundId(),"txn_discover",null);
        var template=bt("txn_discover","refund",-4000,0);
        var fact=new BalanceTransactionFact(template.btId(),template.type(),template.amountMinor(),template.feeMinor(),template.netMinor(),template.currency(),template.balanceDomain(),remote.refundId(),"refund",template.status(),template.createdAt(),template.availableOn(),List.of(),template.rawDigest());
        importFact(fact);
        var refundExecutor=new RefundExecutor(repository,refunds,fake,"FAKE");
        var worker=new FinancialIngestionWorker(jdbc,importer,replay,scope,fake.clock(),refundExecutor::refreshProviderRefund);
        worker.tick();
        assertThat(count("refund")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM platform.balance_transaction WHERE bt_id='txn_discover'",String.class)).isEqualTo("POSTED");
        assertThat(balance("PSP_FUNDS")).isEqualTo(-4000);assertProjection();
    }
    @Test void latePostedBtConflictIsVisibleWithoutChangingOriginalJournal(){
        UUID id=refunds.accept(scope,refund("succeeded","txn_conflict",null),evidence(),null);
        var original=bt("txn_conflict","refund",-4000,0);importFact(original);replay.replay(scope,original.btId());
        UUID journal=jdbc.queryForObject("SELECT journal_id FROM platform.balance_transaction WHERE bt_id='txn_conflict'",UUID.class);
        var changed=new BalanceTransactionFact(original.btId(),original.type(),-4000,30,-4030,original.currency(),original.balanceDomain(),original.sourceObjectId(),original.sourceObjectType(),original.status(),original.createdAt(),original.availableOn(),List.of(),"c".repeat(64));
        BalanceTransactionPort provider=new BalanceTransactionPort(){
            public ProviderCallResult<BalanceTransactionPage> listBalanceTransactions(ListBalanceTransactionsQuery q){return fake.listBalanceTransactions(q);}
            public ProviderCallResult<BalanceTransactionFact> getBalanceTransaction(ProviderScope s,String btId){return new ProviderCallResult<>(ProviderCallClassification.SUCCESS,changed,fake.getBalanceTransaction(s,btId).evidence());}
        };
        new FinancialImportService(jdbc,tx,json,provider,fake.clock()).refreshPending(scope,original.btId());
        assertThat(count("bt_evidence_conflict")).isEqualTo(1);
        assertThat(refunds.read(refundsContext,id)).containsEntry("financialStatus","CONFLICT");
        assertThat(count("journal")).isEqualTo(1);assertThat(balance("PSP_FUNDS")).isEqualTo(-4000);
        assertThat(jdbc.queryForObject("SELECT journal_id FROM platform.balance_transaction WHERE bt_id='txn_conflict'",UUID.class)).isEqualTo(journal);assertProjection();
    }
}
