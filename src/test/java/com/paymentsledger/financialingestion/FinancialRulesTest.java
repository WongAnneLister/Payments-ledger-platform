package com.paymentsledger.financialingestion;

import static org.assertj.core.api.Assertions.*;
import com.paymentsledger.provider.ProviderModels.*;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.ledger.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinancialRulesTest {
    static BalanceTransactionFact fact(String type,long amount,long fee,String domain) {
        return new BalanceTransactionFact("bt_1",type,amount,fee,amount-fee,"JPY",domain,"src_1",type,"pending",Instant.parse("2026-03-09T04:00:00Z"),Instant.parse("2026-03-10T04:00:00Z"),List.of(),"a".repeat(64));
    }
    final Map<String,UUID> accounts=Map.of("PSP_FUNDS",new UUID(0,1),"PSP_FEE_EXPENSE",new UUID(0,2),"SALES_REVENUE",new UUID(0,3),"SALES_RETURNS",new UUID(0,4),"SUSPENSE",new UUID(0,5));
    final ProviderScope scope=new ProviderScope(new UUID(0,10),new UUID(0,11),new UUID(0,12),false);
    @Test void chargeUsesActualGrossFeeAndNetInMinorUnits(){
        var command=new BTJournalMapper().map(scope,fact("charge",10000,300,"PAYMENTS"),FinancialEventNormalizer.Kind.CHARGE,accounts);
        assertThat(command.entries()).containsExactly(new PostingLine(accounts.get("PSP_FUNDS"),Side.DEBIT,9700),new PostingLine(accounts.get("PSP_FEE_EXPENSE"),Side.DEBIT,300),new PostingLine(accounts.get("SALES_REVENUE"),Side.CREDIT,10000));
    }
    @Test void refundPreservesPositiveAndNegativeFeeSigns(){
        for(long fee:new long[]{0,30,-30}) {
            var command=new BTJournalMapper().map(scope,fact("refund",-1000,fee,"PAYMENTS"),FinancialEventNormalizer.Kind.REFUND,accounts);
            assertThat(command.entries()).contains(new PostingLine(accounts.get("PSP_FUNDS"),Side.CREDIT,1000+fee),new PostingLine(accounts.get("SALES_RETURNS"),Side.DEBIT,1000));
            if(fee!=0)assertThat(command.entries()).contains(new PostingLine(accounts.get("PSP_FEE_EXPENSE"),fee>0?Side.DEBIT:Side.CREDIT,Math.abs(fee)));
        }
    }
    @Test void suspenseUsesSignedNetAndNeverCreatesEmptyJournal(){
        for(long amount:new long[]{100,-100}) {
            var command=new BTJournalMapper().map(scope,fact("adjustment",amount,0,"PAYMENTS"),FinancialEventNormalizer.Kind.UNKNOWN,accounts);
            assertThat(command.entries()).contains(new PostingLine(accounts.get("PSP_FUNDS"),amount>0?Side.DEBIT:Side.CREDIT,100));
        }
        assertThatThrownBy(()->new BTJournalMapper().map(scope,fact("adjustment",0,0,"PAYMENTS"),FinancialEventNormalizer.Kind.UNKNOWN,accounts)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void classifierQuarantinesDomainsFxAndBadSignsAndKeepsFutureDependenciesPending(){
        var normalizer=new FinancialEventNormalizer();
        assertThat(normalizer.classify(fact("charge",100,3,"PAYMENTS"))).isEqualTo(FinancialEventNormalizer.Kind.CHARGE);
        for(var f:List.of(fact("charge",-100,3,"PAYMENTS"),fact("refund",100,0,"PAYMENTS"),fact("adjustment",1,0,"ISSUING"),fact("currency_conversion",1,0,"PAYMENTS")))
            assertThat(normalizer.classify(f)).isEqualTo(FinancialEventNormalizer.Kind.QUARANTINE);
        assertThat(normalizer.classify(fact("payout",-100,0,"PAYMENTS"))).isEqualTo(FinancialEventNormalizer.Kind.PENDING);
        assertThat(normalizer.classify(fact("adjustment",-100,0,"PAYMENTS"))).isEqualTo(FinancialEventNormalizer.Kind.UNKNOWN);
    }
    @Test void overlapUsesSevenBusinessDatesAcrossDstRatherThan168Hours(){
        var window=ImportWindows.overlap(LocalDate.of(2026,3,10),ZoneId.of("America/New_York"));
        assertThat(window.from()).isEqualTo(Instant.parse("2026-03-03T05:00:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2026-03-10T04:00:00Z"));
    }
    @Test void fxFeeIsIsolated(){
        assertThat(new FinancialEventNormalizer().classify(fact("stripe_fx_fee",-1,0,"PAYMENTS"))).isEqualTo(FinancialEventNormalizer.Kind.QUARANTINE);
    }
    @Test void canonicalFactsDoNotDependOnJacksonTimeSerialization(){
        var numeric=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var iso=numeric.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertThat(FinancialFacts.immutable(numeric,fact("charge",100,3,"PAYMENTS"))).isEqualTo(FinancialFacts.immutable(iso,fact("charge",100,3,"PAYMENTS")));
    }

    @Test void failedRefundReturnUsesNewPositivePrincipalAndActualFeeEvidence(){
        for(long fee:new long[]{0,30,-30}) {
            var returned=new BalanceTransactionFact("bt_return","refund_failure",1000,fee,1000-fee,"JPY","PAYMENTS","re_1","refund","pending",Instant.parse("2026-03-09T04:00:00Z"),Instant.parse("2026-03-10T04:00:00Z"),List.of(),"b".repeat(64));
            var kind=new FinancialEventNormalizer().classify(returned);
            assertThat(kind.name()).isEqualTo("REFUND_FAILURE");
            var command=new BTJournalMapper().map(scope,returned,kind,accounts);
            assertThat(command.entries()).contains(new PostingLine(accounts.get("PSP_FUNDS"),Side.DEBIT,1000-fee),new PostingLine(accounts.get("SALES_RETURNS"),Side.CREDIT,1000));
            if(fee!=0)assertThat(command.entries()).contains(new PostingLine(accounts.get("PSP_FEE_EXPENSE"),fee>0?Side.DEBIT:Side.CREDIT,Math.abs(fee)));
            assertThat(command.eventKey()).endsWith(":bt_return");
        }
    }

    @Test void failedRefundReturnWithWrongPrincipalSignIsIsolated(){
        var invalid=new BalanceTransactionFact("bt_bad","refund_failure",-1000,0,-1000,"JPY","PAYMENTS","re_1","refund","pending",Instant.parse("2026-03-09T04:00:00Z"),Instant.parse("2026-03-10T04:00:00Z"),List.of(),"c".repeat(64));
        assertThat(new FinancialEventNormalizer().classify(invalid)).isEqualTo(FinancialEventNormalizer.Kind.QUARANTINE);
    }

}
