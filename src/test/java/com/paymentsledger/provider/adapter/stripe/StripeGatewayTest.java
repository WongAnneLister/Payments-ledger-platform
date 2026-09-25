package com.paymentsledger.provider.adapter.stripe;

import static org.assertj.core.api.Assertions.*;
import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.adapter.stripe.StripeTestTransport.*;
import com.paymentsledger.provider.*;
import com.stripe.exception.ApiConnectionException;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

class StripeGatewayTest {
    @Test void pinsVersionAndDisablesRetriesWithoutGlobalSdkState() {
        var http = new StripeTestTransport().reply(200, session("open"));
        var result = new StripeCheckoutAdapter(http.gateway()).querySession(SCOPE,"cs_test_fixture");
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.evidence().apiVersion()).isEqualTo("2026-07-29.dahlia");
        assertThat(result.evidence().providerRequestId()).isEqualTo("req_fixture_1");
        assertThat(result.evidence().rawDigest()).matches("[a-f0-9]{64}");
        assertThat(http.requests).hasSize(2);
        assertThat(http.requests.getLast().options().getMaxNetworkRetries()).isZero();
        assertThat(http.requests.getLast().headers().firstValue("Stripe-Version")).contains("2026-07-29.dahlia");
        assertThat(http.requests.getLast().options().getStripeAccount()).isNull();
    }
    @Test void rejectsLiveKeysMismatchedScopeAndActiveTransactionsBeforeNetwork() {
        var http = new StripeTestTransport();
        assertThatThrownBy(() -> new StripeGateway("sk_live_never",SCOPE,"acct_fixture",CLOCK,http)).isInstanceOf(IllegalArgumentException.class);
        var adapter = new StripeCheckoutAdapter(http.gateway());
        var other = new ProviderScope(SCOPE.organizationId(),SCOPE.environmentId(),UUID.randomUUID(),false);
        assertThatThrownBy(() -> adapter.querySession(other,"cs_test_fixture")).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> adapter.querySession(SCOPE,"cs_test_fixture")).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.clear(); }
        assertThat(http.requests).isEmpty();
    }
    @Test void accountMismatchPreventsBusinessCall() {
        var http = new StripeTestTransport(); http.replies.clear();
        http.reply(200,"{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        var result = new StripeCheckoutAdapter(http.gateway()).querySession(SCOPE,"cs_test_fixture");
        assertThat(result.classification()).isEqualTo(CONTRACT_MISMATCH);
        assertThat(http.requests).hasSize(1);
    }
    @Test void preservesUnknownOutcomeWithoutRetryOrLeakingException() {
        var http = new StripeTestTransport(); http.replies.add(new ApiConnectionException("secret card data"));
        var result = new StripeCheckoutAdapter(http.gateway()).querySession(SCOPE,"cs_test_fixture");
        assertThat(result.classification()).isEqualTo(TIMEOUT_UNKNOWN);
        assertThat(result.evidence().respondedAt()).isNull();
        assertThat(result.evidence().httpStatus()).isNull();
        assertThat(result.toString()).doesNotContain("secret card data", "sk_test");
        assertThat(http.requests).hasSize(2);
    }
    @Test void classifiesProviderErrorsAndNeverReturnsRawErrorMessage() {
        Object[][] cases = {
            {404,"invalid_request_error","resource_missing",NOT_FOUND},
            {400,"idempotency_error","idempotency_error",IDEMPOTENCY_CONFLICT},
            {409,"invalid_request_error","idempotency_key_in_use",RETRYABLE_TRANSIENT},
            {429,"rate_limit_error","rate_limit",RATE_LIMITED},
            {500,"api_error","api_error",RETRYABLE_TRANSIENT},
            {402,"card_error","card_declined",DEFINITIVE_REJECT},
            {402,"card_error","authentication_required",AUTH_REQUIRED},
            {403,"invalid_request_error","permission_denied",CAPABILITY_UNAVAILABLE}
        };
        for (var c : cases) {
            var http = new StripeTestTransport().reply((int)c[0],"{\"error\":{\"type\":\""+c[1]+"\",\"code\":\""+c[2]+"\",\"message\":\"sensitive\"}}");
            var result = new StripeCheckoutAdapter(http.gateway()).querySession(SCOPE,"cs_test_fixture");
            assertThat(result.classification()).as("HTTP %s",c[0]).isEqualTo(c[3]);
            assertThat(result.toString()).doesNotContain("sensitive");
        }
    }
    @Test void liveOrMalformedSuccessfulResponseFailsClosed() {
        for (String body : new String[]{session("open").replace("false","true"),session("unexpected"),"{}"}) {
            var http = new StripeTestTransport().reply(200,body);
            assertThat(new StripeCheckoutAdapter(http.gateway()).querySession(SCOPE,"cs_test_fixture").classification()).isEqualTo(CONTRACT_MISMATCH);
        }
    }
    @Test void nestedPaymentIntentPaginationStopsAtActualRequestBudgetAndCleansUpScope() {
        var http = new StripeTestTransport().reply(200,"""
            {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"requires_payment_method",
             "amount":1000,"amount_received":0,"currency":"usd","latest_charge":null,"created":1790035200,"metadata":{}}
            """);
        http.reply(200,StripePaymentsAdapterTest.page(StripePaymentsAdapterTest.charge("ch_first","failed"),true));
        http.reply(200,StripePaymentsAdapterTest.page(StripePaymentsAdapterTest.charge("ch_second","failed"),false));
        var adapter = new StripeCheckoutAdapter(http.gateway());
        try (var outer = ProviderRequestBudget.openOrJoin(3)) {
            try (var inner = ProviderRequestBudget.openOrJoin(32)) {
                var result = adapter.queryPaymentIntent(SCOPE,"pi_fixture");
                assertThat(result.classification()).isEqualTo(RETRYABLE_TRANSIENT);
                assertThat(result.payload()).isNull();
                assertThat(result.evidence().providerErrorCode()).isEqualTo("query_budget_exhausted");
            }
            assertThat(adapter.listChargeAttempts(SCOPE,"pi_fixture").classification()).isEqualTo(RETRYABLE_TRANSIENT);
            assertThat(http.requests).hasSize(3); // account + PI + first page; nested scope cannot refill
        }
        http.replies.clear(); http.reply(200,session("open"));
        assertThat(adapter.querySession(SCOPE,"cs_test_fixture").classification()).isEqualTo(SUCCESS);
        assertThat(http.requests).hasSize(4); // closing root releases the thread-local scope
    }
    @Test void sessionAndChargePaginationShareOneThirtyTwoRequestBatch() {
        var http = new StripeTestTransport().reply(200,session("open"));
        for (int i=0;i<33;i++) http.reply(200,StripePaymentsAdapterTest.page(StripePaymentsAdapterTest.charge("ch_"+i,"failed"),i<32));
        var adapter = new StripeCheckoutAdapter(http.gateway());
        try (var budget = ProviderRequestBudget.openOrJoin(32)) {
            assertThat(adapter.querySession(SCOPE,"cs_test_fixture").classification()).isEqualTo(SUCCESS);
            var result = adapter.listChargeAttempts(SCOPE,"pi_fixture");
            assertThat(result.classification()).isEqualTo(RETRYABLE_TRANSIENT);
            assertThat(result.payload()).isNull();
            assertThat(http.requests).hasSize(32);
            assertThat(adapter.expireSession(SCOPE,"cs_test_fixture").classification()).isEqualTo(RETRYABLE_TRANSIENT);
            assertThat(http.requests).hasSize(32); // no cancellation request after budget exhaustion
        }
    }

}
