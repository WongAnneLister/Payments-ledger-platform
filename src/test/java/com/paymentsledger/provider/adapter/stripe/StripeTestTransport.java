package com.paymentsledger.provider.adapter.stripe;

import com.paymentsledger.provider.ProviderScope;
import com.stripe.exception.*;
import com.stripe.net.*;
import java.time.*;
import java.util.*;

/** LOCAL_SDK_HTTP_FIXTURE: real SDK serialization/deserialization, no Stripe account calls. */
final class StripeTestTransport extends HttpClient {
    static final ProviderScope SCOPE = new ProviderScope(new UUID(0,1), new UUID(0,2), new UUID(0,3), false);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC);
    final List<StripeRequest> requests = new ArrayList<>();
    final Deque<Object> replies = new ArrayDeque<>();
    StripeTestTransport() { reply(200, "{\"id\":\"acct_fixture\",\"object\":\"account\"}"); }
    StripeTestTransport reply(int status, String body) {
        replies.add(new StripeResponse(status, HttpHeaders.of(Map.of(
                "Request-Id", List.of("req_fixture_" + replies.size()),
                "Stripe-Version", List.of("2026-07-29.dahlia"))), body));
        return this;
    }
    StripeGateway gateway() { return new StripeGateway("sk_test_local_fixture", SCOPE, "acct_fixture", CLOCK, this); }
    @Override public StripeResponse request(StripeRequest request) throws StripeException {
        requests.add(request);
        Object next = replies.removeFirst();
        if (next instanceof StripeException error) throw error;
        return (StripeResponse) next;
    }
    static String session(String status) { return """
        {"id":"cs_test_fixture","object":"checkout.session","status":"%s","livemode":false,
         "url":"https://checkout.stripe.com/c/pay/cs_test_fixture","payment_intent":null,
         "payment_status":"unpaid","expires_at":1790042400,"metadata":{"order":"o1"}}
        """.formatted(status); }
}
