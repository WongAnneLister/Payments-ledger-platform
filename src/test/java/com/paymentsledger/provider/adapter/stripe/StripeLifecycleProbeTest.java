package com.paymentsledger.provider.adapter.stripe;

import com.paymentsledger.provider.ProviderScope;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StripeLifecycleProbeTest {
    @TempDir Path dir;
    static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    static final String SECRET = "whsec_LOCAL_FIXTURE";
    static final ProviderScope SCOPE = new ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
    static final String BODY = """
        {"id":"evt_fixture","object":"event","api_version":"2026-07-29.dahlia",
        "created":1790121600,"livemode":false,"type":"payment_intent.succeeded",
        "data":{"object":{"id":"pi_fixture","customer":"cus_sensitive","metadata":{"private":"不要保存"}}}}
        """;
    StripeWebhookVerifier verifier() {
        return new StripeWebhookVerifier(SCOPE,"acct_fixture",StripeGateway.API_VERSION,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
    }
    String signature() throws Exception { return Webhook.Signature.generateSignatureHeader(BODY,SECRET,NOW.getEpochSecond()); }
    int receive(byte[] body, String sig) throws Exception {
        return StripeLifecycleProbe.receive(dir,verifier(),new ByteArrayInputStream(body),sig);
    }
    void configured() throws Exception { Files.writeString(dir.resolve("webhook-secret.local"),SECRET); }
    @Test void acknowledgesOriginalBytesOnlyAfterWritingAllowlistedEvidenceAndTamperRejection() throws Exception {
        configured();
        assertThat(receive(BODY.getBytes(StandardCharsets.UTF_8),signature())).isEqualTo(200);
        String saved = Files.readString(dir.resolve("webhook-evidence.jsonl"));
        assertThat(saved).contains("payment_intent.succeeded","SUCCESS","DEFINITIVE_REJECT","tamperRejected\":true");
        assertThat(saved).doesNotContain("evt_fixture","pi_fixture","cus_sensitive","不要保存",SECRET,BODY);
        assertThat(saved.lines()).hasSize(1);
    }
    @Test void rejectsModifiedBytesWithOriginalSignatureWithoutAcknowledgment() throws Exception {
        configured();
        assertThat(receive((BODY+" ").getBytes(StandardCharsets.UTF_8),signature())).isEqualTo(400);
        assertThat(Files.readString(dir.resolve("webhook-evidence.jsonl"))).doesNotContain("SUCCESS","cus_sensitive");
    }
    @Test void unconfiguredSecretReturnsControlledFailureAndReloadsSecretForNextRequest() throws Exception {
        assertThat(receive(BODY.getBytes(StandardCharsets.UTF_8),signature())).isEqualTo(503);
        configured();
        assertThat(receive(BODY.getBytes(StandardCharsets.UTF_8),signature())).isEqualTo(200);
    }
    @Test void oversizedBodyIsRejected() throws Exception {
        configured();
        assertThat(receive(new byte[1024*1024+1],signature())).isEqualTo(413);
        assertThat(Files.exists(dir.resolve("webhook-evidence.jsonl"))).isFalse();
    }
    @Test void evidenceWriteFailureNeverAcknowledgesEvent() throws Exception {
        configured(); Files.createDirectory(dir.resolve("webhook-evidence.jsonl"));
        assertThat(receive(BODY.getBytes(StandardCharsets.UTF_8),signature())).isEqualTo(503);
    }
    @Test void persistedCreationStateReusesExactParametersAcrossRestarts() throws Exception {
        var first = StripeLifecycleProbe.initialState(dir,"https://example.com","acct_fixture",SCOPE,NOW);
        var resumed = StripeLifecycleProbe.initialState(dir,"https://changed.example","acct_fixture",SCOPE,NOW.plusSeconds(300));
        assertThat(first).isNotNull();
        assertThat(resumed).isEqualTo(first);
        assertThat(first.path("operationKey").asText()).startsWith("pr05-lifecycle-");
        assertThat(first.path("expiresAt").asText()).isEqualTo(NOW.plusSeconds(7200).toString());
        assertThat(first.path("baseUrl").asText()).isEqualTo("https://example.com");
        assertThatThrownBy(() -> StripeLifecycleProbe.initialState(dir,"https://example.com","acct_other",SCOPE,NOW))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void unknownCreateOutcomeReplaysPersistedKeyAndParametersThenQueriesKnownSession() throws Exception {
        var state=StripeLifecycleProbe.initialState(dir,"https://example.com","acct_fixture",SCOPE,NOW);
        var firstHttp=new StripeTestTransport();
        firstHttp.replies.add(new com.stripe.exception.ApiConnectionException("sensitive network details"));
        var first=new StripeLifecycleProbe(dir,SCOPE,firstHttp.gateway(),state);
        first.current=StripeLifecycleProbe.JSON.createObjectNode(); first.current.putArray("observations");
        assertThatThrownBy(first::create).isInstanceOf(IllegalStateException.class);
        assertThat(state.hasNonNull("sessionId")).isFalse();
        var persisted=StripeLifecycleProbe.initialState(dir,"https://changed.example","acct_fixture",SCOPE,NOW.plusSeconds(500));
        String response=StripeTestTransport.session("open")
                .replace("1790042400",Long.toString(NOW.plusSeconds(7200).getEpochSecond()))
                .replace("\"order\":\"o1\"","\"pr05_session\":\""+persisted.path("runId").asText()+"\"");
        var retryHttp=new StripeTestTransport().reply(200,response).reply(200,response);
        var retry=new StripeLifecycleProbe(dir,SCOPE,retryHttp.gateway(),persisted);
        retry.current=StripeLifecycleProbe.JSON.createObjectNode(); retry.current.putArray("observations");
        retry.create();
        var mutation=retryHttp.requests.getLast();
        assertThat(mutation.params()).isEqualTo(firstHttp.requests.getLast().params());
        assertThat(mutation.options().getIdempotencyKey()).isEqualTo(firstHttp.requests.getLast().options().getIdempotencyKey());
        assertThat(Files.readString(dir.resolve("state.local.json"))).contains("hostedUrl");
        assertThat(retry.current.toString()).doesNotContain("https://checkout.stripe.com",state.path("runId").asText());
        retry.create();
        assertThat(retryHttp.requests.getLast().url().getPath()).endsWith("/cs_test_fixture");
        assertThat(retryHttp.requests.getLast().options().getIdempotencyKey()).isNull();
    }

}
