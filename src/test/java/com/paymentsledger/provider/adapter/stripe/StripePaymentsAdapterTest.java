package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.ProviderCallClassification.*;
import static com.paymentsledger.provider.adapter.stripe.StripeTestTransport.*;
import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StripePaymentsAdapterTest {
    static CreateCheckoutSessionCommand command() {
        return new CreateCheckoutSessionCommand(
                SCOPE,
                "checkout-operation-1",
                1000,
                "USD",
                "https://merchant.test/success",
                "https://merchant.test/cancel",
                Map.of("order", "o1"),
                Map.of("payment", "p1"),
                CLOCK.instant().plusSeconds(3600));
    }

    static String charge(String id, String status) {
        return """
        {"id":"%s","object":"charge","livemode":false,"payment_intent":"pi_fixture","status":"%s",
         "captured":%s,"amount":1000,"amount_captured":%d,"currency":"usd","created":1790035200,
         "balance_transaction":null,"failure_code":%s,"outcome":{"type":"%s"}}
        """.formatted(
                        id,
                        status,
                        status.equals("succeeded"),
                        status.equals("succeeded") ? 1000 : 0,
                        status.equals("failed") ? "\"card_declined\"" : "null",
                        status.equals("failed") ? "issuer_declined" : "authorized");
    }

    static String page(String data, boolean more) {
        return "{\"object\":\"list\",\"data\":[" + data + "],\"has_more\":" + more + ",\"url\":\"/v1/charges\"}";
    }

    @Test
    void createsHostedSessionWithTwoExplicitMetadataLocationsAutomaticCaptureAndStableKey() {
        var http = new StripeTestTransport().reply(200, session("open"));
        var result = new StripeCheckoutAdapter(http.gateway()).createSession(command());
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.payload().hostedUrl()).startsWith("https://checkout.stripe.com/");
        assertThat(result.payload().paymentIntentId()).isNull();
        var request = http.requests.getLast();
        assertThat(request.params()).containsEntry("mode", "payment").containsEntry("metadata", Map.of("order", "o1"));
        var piParams = (Map<?, ?>) request.params().get("payment_intent_data");
        assertThat(piParams.get("capture_method")).isEqualTo("automatic");
        assertThat(piParams.get("metadata")).isEqualTo(Map.of("payment", "p1"));
        assertThat(request.params().get("payment_method_types")).isEqualTo(List.of("card"));
        assertThat(request.options().getIdempotencyKey()).isEqualTo("checkout-operation-1");
        assertThat(result.evidence().idempotencyKeyUsed()).isEqualTo("checkout-operation-1");
    }

    @Test
    void absoluteExpiryAndParametersRemainIdenticalAcrossDelayedReplayAndRestart() {
        var firstHttp = new StripeTestTransport().reply(200, session("open"));
        new StripeCheckoutAdapter(firstHttp.gateway()).createSession(command());
        var secondHttp = new StripeTestTransport().reply(200, session("open"));
        var restarted = new StripeGateway(
                "sk_test_local_fixture", SCOPE, "acct_fixture", Clock.offset(CLOCK, Duration.ofHours(2)), secondHttp);
        assertThat(new StripeCheckoutAdapter(restarted).createSession(command()).classification())
                .isEqualTo(SUCCESS);
        assertThat(secondHttp.requests.getLast().params())
                .isEqualTo(firstHttp.requests.getLast().params());
        assertThat(secondHttp.requests.getLast().params())
                .containsEntry("expires_at", command().expiresAt().getEpochSecond());
        assertThat(secondHttp.requests.getLast().options().getIdempotencyKey()).isEqualTo("checkout-operation-1");
    }

    @Test
    void expireUsesOnlySessionEndpointAndCannotCancelSuccessfulPaymentIntent() {
        var http = new StripeTestTransport()
                .reply(
                        400,
                        "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"checkout_session_not_open\",\"message\":\"not open\"}}");
        var result = new StripeCheckoutAdapter(http.gateway()).expireSession(SCOPE, "cs_test_fixture");
        assertThat(result.classification()).isEqualTo(DEFINITIVE_REJECT);
        assertThat(http.requests.getLast().url().getPath()).isEqualTo("/v1/checkout/sessions/cs_test_fixture/expire");
        assertThat(http.requests).hasSize(2);
        assertThat(result.evidence().idempotencyKeyUsed())
                .isEqualTo(http.requests
                        .getLast()
                        .headers()
                        .firstValue("Idempotency-Key")
                        .orElseThrow());
    }

    @Test
    void collectsEveryChargeAttemptAndMapsAuthActionFromPaymentIntent() {
        var http = new StripeTestTransport().reply(200, """
            {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"requires_action",
             "amount":1000,"amount_received":0,"currency":"usd","latest_charge":"ch_second",
             "created":1790035200,"metadata":{"payment":"p1"},"next_action":{"type":"use_stripe_sdk"}}
            """);
        http.reply(200, page(charge("ch_second", "succeeded"), true));
        http.reply(200, page(charge("ch_first", "failed"), false));
        var result = new StripeCheckoutAdapter(http.gateway()).queryPaymentIntent(SCOPE, "pi_fixture");
        assertThat(result.classification()).isEqualTo(AUTH_REQUIRED);
        assertThat(result.payload().chargeIds()).containsExactly("ch_second", "ch_first");
        assertThat(result.payload().nextActionType()).isEqualTo("use_stripe_sdk");
        assertThat(http.requests.getLast().params()).containsEntry("starting_after", "ch_second");
    }

    @Test
    void knownLatestChargeCannotDisappearFromCompositePaymentIntentObservation() {
        for (String data : List.of("", charge("ch_other", "failed"))) {
            var http = new StripeTestTransport().reply(200, """
                {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"succeeded",
                 "amount":1000,"amount_received":1000,"currency":"usd","latest_charge":"ch_expected",
                 "created":1790035200,"metadata":{}}
                """).reply(200, page(data, false));
            assertThat(new StripeCheckoutAdapter(http.gateway())
                            .queryPaymentIntent(SCOPE, "pi_fixture")
                            .classification())
                    .isEqualTo(data.isEmpty() ? TRANSIENT_EMPTY : CONTRACT_MISMATCH);
        }
        var unattempted = new StripeTestTransport().reply(200, """
            {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"requires_payment_method",
             "amount":1000,"amount_received":0,"currency":"usd","latest_charge":null,"created":1790035200,"metadata":{}}
            """).reply(200, page("", false));
        assertThat(new StripeCheckoutAdapter(unattempted.gateway())
                        .queryPaymentIntent(SCOPE, "pi_fixture")
                        .classification())
                .isEqualTo(SUCCESS);
    }

    @Test
    void emptyChargeQueryDoesNotProvePaymentWasNeverAttempted() {
        var http = new StripeTestTransport().reply(200, page("", false));
        assertThat(new StripeCheckoutAdapter(http.gateway())
                        .listChargeAttempts(SCOPE, "pi_fixture")
                        .classification())
                .isEqualTo(TRANSIENT_EMPTY);
    }

    @Test
    void corruptChargePaginationAndWrongAssociationFailClosed() {
        for (String response :
                List.of(page("", true), page(charge("ch_wrong", "failed").replace("pi_fixture", "pi_other"), false))) {
            var http = new StripeTestTransport().reply(200, response);
            assertThat(new StripeCheckoutAdapter(http.gateway())
                            .listChargeAttempts(SCOPE, "pi_fixture")
                            .classification())
                    .isEqualTo(CONTRACT_MISMATCH);
        }
    }

    @Test
    void failedAndSuccessfulAttemptsRemainSeparateWithNullableFinancialEvidence() {
        var http = new StripeTestTransport()
                .reply(200, page(charge("ch_second", "succeeded") + "," + charge("ch_first", "failed"), false));
        var result = new StripeCheckoutAdapter(http.gateway()).listChargeAttempts(SCOPE, "pi_fixture");
        assertThat(result.payload()).extracting(ChargeFact::status).containsExactly("succeeded", "failed");
        assertThat(result.payload().getFirst().balanceTransactionId()).isNull();
        assertThat(result.payload().getLast().failureCode()).isEqualTo("card_declined");
    }

    @Test
    void refundCreationUsesOwnOperationKeyAndQueryPreservesLateAndFailureEvidence() {
        String refund = """
            {"id":"re_fixture","object":"refund","charge":"ch_fixture","payment_intent":"pi_fixture",
             "status":"pending","amount":500,"currency":"usd","balance_transaction":null,"created":1790035200}
            """;
        var http = new StripeTestTransport()
                .reply(200, refund)
                .reply(
                        200,
                        refund.replace("pending", "failed")
                                .replace(
                                        "\"created\"",
                                        "\"failure_reason\":\"declined\",\"failure_balance_transaction\":\"txn_return\",\"created\""));
        var adapter = new StripeRefundAdapter(http.gateway());
        var created = adapter.createRefund(
                new CreateRefundCommand(SCOPE, "refund-operation-1", "ch_fixture", null, 500, "requested_by_customer"));
        assertThat(created.classification()).isEqualTo(SUCCESS);
        assertThat(created.payload().balanceTransactionId()).isNull();
        assertThat(http.requests.getLast().params())
                .containsEntry("charge", "ch_fixture")
                .containsEntry("amount", 500L);
        assertThat(http.requests.getLast().options().getIdempotencyKey()).isEqualTo("refund-operation-1");
        var queried = adapter.queryRefund(SCOPE, "re_fixture");
        assertThat(queried.payload().failureBalanceTransactionId()).isEqualTo("txn_return");
        assertThat(queried.payload().failureReason()).isEqualTo("declined");
        assertThat(queried.evidence().idempotencyKeyUsed()).isNull();
    }
}
