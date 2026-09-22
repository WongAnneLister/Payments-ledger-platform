package com.paymentsledger.provider.adapter.stripe;

import static org.assertj.core.api.Assertions.*;
import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.adapter.stripe.StripeTestTransport.*;
import com.paymentsledger.provider.ProviderModels.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** LOCAL_SDK_HTTP_FIXTURE: request serialization and response parsing use stripe-java 33.3.0. */
class StripeFinancialReadAdapterTest {
    private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-09-21T00:00:00Z");

    @Test void balanceTransactionsKeepSignedMoneyDomainFeesAndUtcPagination() {
        var http = new StripeTestTransport().reply(200, page(true, bt("txn_1", "payments", "\"re_fixture\"")));
        var result = new StripeFinancialReadAdapter(http.gateway()).listBalanceTransactions(btQuery("txn_previous", 500, null));
        assertThat(result.classification()).isEqualTo(SUCCESS);
        var fact = result.payload().items().getFirst();
        assertThat(fact.amountMinor()).isEqualTo(-400);
        assertThat(fact.feeMinor()).isEqualTo(-20);
        assertThat(fact.netMinor()).isEqualTo(-380);
        assertThat(fact.balanceDomain()).isEqualTo("PAYMENTS");
        assertThat(fact.sourceObjectId()).isEqualTo("re_fixture");
        assertThat(fact.sourceObjectType()).isEqualTo("refund");
        assertThat(fact.status()).isEqualTo("pending");
        assertThat(fact.createdAt()).isEqualTo(FROM);
        assertThat(fact.availableOn()).isEqualTo(UNTIL);
        assertThat(fact.feeDetails()).containsExactly(new FeeDetail(-20,"usd","stripe_fee","Fee returned"));
        assertThat(fact.rawDigest()).matches("[a-f0-9]{64}");
        assertThat(result.payload().hasMore()).isTrue();
        assertThat(result.payload().nextCursor()).isEqualTo("txn_1");
        assertPageParams(http, "/v1/balance_transactions", "txn_previous", 100);
    }

    @Test void transactionDomainComesFromBalanceTypeAndUnknownSourcesStayUnknown() {
        for (String domain : new String[]{"payments","issuing","refund_and_dispute_prefunding","risk_reserved"}) {
            var http = new StripeTestTransport().reply(200, bt("txn_1",domain,"\"future_123\""));
            var result = new StripeFinancialReadAdapter(http.gateway()).getBalanceTransaction(SCOPE,"txn_1");
            assertThat(result.classification()).as(domain).isEqualTo(SUCCESS);
            assertThat(result.payload().balanceDomain()).isEqualTo(domain.toUpperCase(java.util.Locale.ROOT));
            assertThat(result.payload().sourceObjectType()).isNull();
        }
    }

    @Test void missingDomainAndBrokenSignedArithmeticFailClosed() {
        for (String body : new String[]{bt("txn_1","payments","null").replace("\"balance_type\":\"payments\",", ""),
                bt("txn_1","payments","null").replace("\"net\":-380","\"net\":380")}) {
            var http = new StripeTestTransport().reply(200,body);
            assertThat(new StripeFinancialReadAdapter(http.gateway()).getBalanceTransaction(SCOPE,"txn_1").classification())
                    .isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test void sourceTypeUsesExpandedObjectAndChecksItsMode() {
        String source = "{\"id\":\"ch_fixture\",\"object\":\"charge\",\"livemode\":false}";
        var http = new StripeTestTransport().reply(200,bt("txn_1","payments",source));
        var result = new StripeFinancialReadAdapter(http.gateway()).getBalanceTransaction(SCOPE,"txn_1");
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.payload().sourceObjectType()).isEqualTo("charge");
        for (String invalid : new String[]{source.replace("false","true"),source.replace(",\"livemode\":false","")}) {
            var bad = new StripeTestTransport().reply(200,bt("txn_1","payments",invalid));
            assertThat(new StripeFinancialReadAdapter(bad.gateway()).getBalanceTransaction(SCOPE,"txn_1").classification())
                    .isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test void manualPayoutCannotBeUsedToInferMembership() {
        var http = new StripeTestTransport().reply(200,payout(false));
        var result = new StripeFinancialReadAdapter(http.gateway()).listBalanceTransactions(btQuery(null, 10, "po_fixture"));
        assertThat(result.classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        assertThat(http.requests).hasSize(2);
        assertThat(http.requests.getLast().url().getPath()).isEqualTo("/v1/payouts/po_fixture");
    }

    @Test void automaticPayoutIsVerifiedBeforeMembershipQuery() {
        var http = new StripeTestTransport().reply(200,payout(true)).reply(200,page(false,bt("txn_1","payments","null")));
        var result = new StripeFinancialReadAdapter(http.gateway()).listBalanceTransactions(btQuery(null,10,"po_fixture"));
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(http.requests).hasSize(3);
        assertThat(http.requests.get(1).url().getPath()).isEqualTo("/v1/payouts/po_fixture");
        assertThat(http.requests.getLast().params()).containsEntry("payout","po_fixture");
        assertThat(result.payload().nextCursor()).isNull();
    }

    @Test void disputeReadsPreserveRelationshipsDueDateAndPagination() {
        var http = new StripeTestTransport().reply(200,dispute()).reply(200,page(true,dispute()));
        var adapter = new StripeFinancialReadAdapter(http.gateway());
        var one = adapter.getDispute(SCOPE,"dp_fixture");
        assertThat(one.classification()).isEqualTo(SUCCESS);
        assertThat(one.payload()).isEqualTo(new DisputeFact("dp_fixture","ch_fixture","needs_response","fraudulent",400,"usd",
                UNTIL,java.util.List.of("txn_1")));
        var many = adapter.listDisputes(pageQuery("dp_previous",101));
        assertThat(many.classification()).isEqualTo(SUCCESS);
        assertThat(many.payload().items()).containsExactly(one.payload());
        assertThat(many.payload().nextCursor()).isEqualTo("dp_fixture");
        assertPageParams(http,"/v1/disputes","dp_previous",100);
    }

    @Test void payoutReadsPreserveFailureRelationshipAndPagination() {
        var http = new StripeTestTransport().reply(200,payout(true)).reply(200,page(true,payout(true)));
        var adapter = new StripeFinancialReadAdapter(http.gateway());
        var one = adapter.getPayout(SCOPE,"po_fixture");
        assertThat(one.classification()).isEqualTo(SUCCESS);
        assertThat(one.payload()).isEqualTo(new PayoutFact("po_fixture","failed",400,"usd","txn_payout","txn_failure",true,
                UNTIL,"account_closed"));
        var many = adapter.listPayouts(pageQuery("po_previous",20));
        assertThat(many.classification()).isEqualTo(SUCCESS);
        assertThat(many.payload().items()).containsExactly(one.payload());
        assertThat(many.payload().nextCursor()).isEqualTo("po_fixture");
        assertPageParams(http,"/v1/payouts","po_previous",20);
    }

    @Test void everyListItemMustHaveExplicitTestMode() {
        for (String invalid : new String[]{"true", "null"}) {
            var disputes = new StripeTestTransport().reply(200,page(false,dispute(),dispute().replace("\"livemode\":false","\"livemode\":"+invalid)));
            assertThat(new StripeFinancialReadAdapter(disputes.gateway()).listDisputes(pageQuery(null,10)).classification()).isEqualTo(CONTRACT_MISMATCH);
            var payouts = new StripeTestTransport().reply(200,page(false,payout(true),payout(true).replace("\"livemode\":false","\"livemode\":"+invalid)));
            assertThat(new StripeFinancialReadAdapter(payouts.gateway()).listPayouts(pageQuery(null,10)).classification()).isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test void incompleteEmptyPagesAndMissingPaginationStateFailClosed() {
        for (String body : new String[]{page(true), "{\"object\":\"list\",\"data\":[]}"}) {
            var http = new StripeTestTransport().reply(200,body).reply(200,body).reply(200,body);
            var adapter = new StripeFinancialReadAdapter(http.gateway());
            assertThat(adapter.listBalanceTransactions(btQuery(null,10,null)).classification()).isEqualTo(CONTRACT_MISMATCH);
            assertThat(adapter.listDisputes(pageQuery(null,10)).classification()).isEqualTo(CONTRACT_MISMATCH);
            assertThat(adapter.listPayouts(pageQuery(null,10)).classification()).isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test void completeEmptyPagesAreSuccessfulObservations() {
        var http = new StripeTestTransport().reply(200,page(false));
        var result = new StripeFinancialReadAdapter(http.gateway()).listBalanceTransactions(btQuery(null,10,null));
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.payload().items()).isEmpty();
        assertThat(result.payload().hasMore()).isFalse();
        assertThat(result.payload().nextCursor()).isNull();
    }

    @Test void balanceIsCurrentSignedPaymentsObservationAndMissingCurrencyIsUnavailable() {
        var http = new StripeTestTransport().reply(200,balance()).reply(200,balance());
        var adapter = new StripeFinancialReadAdapter(http.gateway());
        var result = adapter.getBalance(SCOPE,"usd","PAYMENTS");
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.payload()).isEqualTo(new BalanceSnapshot("usd","PAYMENTS",-20,400,CLOCK.instant()));
        assertThat(http.requests.getLast().url().getPath()).isEqualTo("/v1/balance");
        assertThat(adapter.getBalance(SCOPE,"eur","PAYMENTS").classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        assertThat(adapter.getBalance(SCOPE,"usd","RISK_RESERVED").classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
    }

    @Test void balanceRequiresExplicitTestModeAndBothCurrencyBuckets() {
        for (String body : new String[]{balance().replace("false","true"),balance().replace("\"livemode\":false,",""),
                balance().replace("\"pending\":[{\"currency\":\"usd\",\"amount\":400}]","\"pending\":[]")}) {
            var http = new StripeTestTransport().reply(200,body);
            var result = new StripeFinancialReadAdapter(http.gateway()).getBalance(SCOPE,"usd","PAYMENTS");
            assertThat(result.classification()).isIn(CONTRACT_MISMATCH,CAPABILITY_UNAVAILABLE);
            assertThat(result.payload()).isNull();
        }
    }

    @Test void reportRunIsNotPretendedToBeHistoricalBalanceProof() {
        var http = new StripeTestTransport();
        var adapter = new StripeFinancialReadAdapter(http.gateway());
        var requested = adapter.requestReport(new ReportRequest(SCOPE,"balance.summary.1",FROM,UNTIL));
        var fetched = adapter.fetchReport(SCOPE,"frr_fixture");
        assertThat(requested.classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        assertThat(fetched.classification()).isEqualTo(CAPABILITY_UNAVAILABLE);
        assertThat(requested.evidence().providerErrorCode()).isEqualTo("historical_report_contract_unverified");
        assertThat(fetched.payload()).isNull();
        assertThat(http.requests).isEmpty();
    }

    @Test void paginationCannotRepeatRequestedCursorOrDuplicateIds() {
        for (boolean repeatedCursor : new boolean[]{true,false}) {
            String transactions = repeatedCursor ? page(true,bt("txn_1","payments","null"))
                    : page(true,bt("txn_1","payments","null"),bt("txn_1","payments","null"));
            var http = new StripeTestTransport().reply(200,transactions)
                    .reply(200,repeatedCursor ? page(true,dispute()) : page(true,dispute(),dispute()))
                    .reply(200,repeatedCursor ? page(true,payout(true)) : page(true,payout(true),payout(true)));
            var adapter = new StripeFinancialReadAdapter(http.gateway());
            assertThat(adapter.listBalanceTransactions(btQuery(repeatedCursor ? "txn_1" : null,10,null)).classification())
                    .isEqualTo(CONTRACT_MISMATCH);
            assertThat(adapter.listDisputes(pageQuery(repeatedCursor ? "dp_fixture" : null,10)).classification())
                    .isEqualTo(CONTRACT_MISMATCH);
            assertThat(adapter.listPayouts(pageQuery(repeatedCursor ? "po_fixture" : null,10)).classification())
                    .isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test void transactionDigestIncludesSourceRelationshipAndObservationTimes() {
        String body = bt("txn_1","payments","\"re_first\"");
        var http = new StripeTestTransport().reply(200,body).reply(200,body.replace("re_first","re_second"))
                .reply(200,body.replace("\"created\":"+FROM.getEpochSecond(),"\"created\":"+(FROM.getEpochSecond()+1)));
        var adapter = new StripeFinancialReadAdapter(http.gateway());
        var first = adapter.getBalanceTransaction(SCOPE,"txn_1");
        var sourceChanged = adapter.getBalanceTransaction(SCOPE,"txn_1");
        var timeChanged = adapter.getBalanceTransaction(SCOPE,"txn_1");
        assertThat(first.payload().rawDigest()).isNotEqualTo(sourceChanged.payload().rawDigest());
        assertThat(first.payload().rawDigest()).isNotEqualTo(timeChanged.payload().rawDigest());
    }

    @Test void fractionalUtcBoundsRoundUpToPreserveIntegerTimestampMembership() {
        var http = new StripeTestTransport().reply(200,page(false));
        var result = new StripeFinancialReadAdapter(http.gateway()).listBalanceTransactions(new ListBalanceTransactionsQuery(
                SCOPE,FROM.plusNanos(1),UNTIL.plusNanos(1),null,10,null));
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(http.requests.getLast().params()).containsEntry("created",
                Map.of("gte",FROM.getEpochSecond()+1,"lt",UNTIL.getEpochSecond()+1));
    }

    private static ListBalanceTransactionsQuery btQuery(String cursor,int limit,String payout) {
        return new ListBalanceTransactionsQuery(SCOPE,FROM,UNTIL,cursor,limit,payout);
    }
    private static PageQuery pageQuery(String cursor,int limit) { return new PageQuery(SCOPE,FROM,UNTIL,cursor,limit); }
    private static void assertPageParams(StripeTestTransport http,String path,String cursor,int limit) {
        var request = http.requests.getLast();
        assertThat(request.url().getPath()).isEqualTo(path);
        assertThat(request.params()).containsEntry("limit",(long)limit).containsEntry("starting_after",cursor)
                .containsEntry("created",Map.of("gte",FROM.getEpochSecond(),"lt",UNTIL.getEpochSecond()));
    }
    private static String page(boolean more,String... items) {
        return "{\"object\":\"list\",\"has_more\":"+more+",\"data\":["+String.join(",",items)+"]}";
    }
    private static String bt(String id,String domain,String source) {
        return """
            {"id":"%s","object":"balance_transaction","balance_type":"%s","type":"refund",
             "amount":-400,"fee":-20,"net":-380,"currency":"usd","source":%s,"status":"pending",
             "created":%d,"available_on":%d,"fee_details":[{"amount":-20,"currency":"usd","type":"stripe_fee","description":"Fee returned"}]}
            """.formatted(id,domain,source,FROM.getEpochSecond(),UNTIL.getEpochSecond());
    }
    private static String payout(boolean automatic) {
        return """
            {"id":"po_fixture","object":"payout","livemode":false,"automatic":%s,"status":"failed",
             "amount":400,"currency":"usd","balance_transaction":"txn_payout","failure_balance_transaction":"txn_failure",
             "arrival_date":%d,"failure_code":"account_closed"}
            """.formatted(automatic,UNTIL.getEpochSecond());
    }
    private static String dispute() {
        return """
            {"id":"dp_fixture","object":"dispute","livemode":false,"charge":"ch_fixture","status":"needs_response",
             "reason":"fraudulent","amount":400,"currency":"usd","evidence_details":{"due_by":%d},"balance_transactions":[%s]}
            """.formatted(UNTIL.getEpochSecond(),bt("txn_1","payments","null"));
    }
    private static String balance() {
        return """
            {"object":"balance","livemode":false,"available":[{"currency":"usd","amount":-20}],
             "pending":[{"currency":"usd","amount":400}]}
            """;
    }
}
