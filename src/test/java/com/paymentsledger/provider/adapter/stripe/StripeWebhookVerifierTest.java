package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.ProviderCallClassification.*;
import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import com.paymentsledger.provider.ProviderScope;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic SDK-signed fixtures only: these are not webhooks received from Stripe. */
@Tag("LOCAL_SIGNATURE_FIXTURE")
class StripeWebhookVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final String VERSION = "2026-07-29.dahlia";
    private static final String SECRET = "whsec_LOCAL_SIGNATURE_FIXTURE";
    private static final ProviderScope SCOPE = new ProviderScope(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), false);
    private final StripeWebhookVerifier verifier = new StripeWebhookVerifier(
            SCOPE, "acct_fixture", VERSION, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    private static String body() {
        return """
                {"id":"evt_local","object":"event","api_version":"2026-07-29.dahlia",
                 "created":1790035200,"livemode":false,"type":"payment_intent.succeeded",
                 "data":{"object":{"id":"pi_local","object":"payment_intent",
                   "customer":"cus_sensitive","description":"原始字节 🧾",
                   "metadata":{"account":"acct_wrong","secret":"do-not-copy"}}}}
                """;
    }

    private static String sign(String body, long timestamp) throws Exception {
        return Webhook.Signature.generateSignatureHeader(body, SECRET, timestamp);
    }

    private ProviderCallResult<VerifiedWebhook> verify(String body) throws Exception {
        return verifier.verify(body.getBytes(StandardCharsets.UTF_8), sign(body, NOW.getEpochSecond()), SECRET);
    }

    @Test
    void verifiesOriginalUnicodeBytesAndMapsOnlyProjectFacts() throws Exception {
        var result = verify(body());
        assertThat(result.classification()).isEqualTo(SUCCESS);
        assertThat(result.payload())
                .isEqualTo(new VerifiedWebhook("evt_local", "payment_intent.succeeded", "pi_local", NOW, false));
        assertThat(result.evidence().apiVersion()).isEqualTo(VERSION);
        assertThat(result.evidence().rawDigest()).matches("[0-9a-f]{64}");
        assertThat(result.evidence().requestedAt()).isEqualTo(NOW);
        assertThat(result.evidence().respondedAt()).isEqualTo(NOW);
        assertThat(result.toString()).doesNotContain(SECRET, "cus_sensitive", "原始字节", "do-not-copy", "acct_wrong");
    }

    @Test
    void evidenceDigestExcludesUnallowlistedFieldsAndJsonFormatting() throws Exception {
        String edited = body().replace("cus_sensitive", "cus_other").replace("原始字节 🧾", "private card text");
        assertThat(verify(edited).evidence().rawDigest())
                .isEqualTo(verify(body()).evidence().rawDigest());
        assertThat(verify(body().replace("\n", " ")).evidence().rawDigest())
                .isEqualTo(verify(body()).evidence().rawDigest());
        assertThat(verify(body().replace("pi_local", "pi_other")).evidence().rawDigest())
                .isNotEqualTo(verify(body()).evidence().rawDigest());
    }

    @Test
    void rejectsTamperingAndReformattingEvenWhenJsonMeaningIsUnchanged() throws Exception {
        String signature = sign(body(), NOW.getEpochSecond());
        for (String changed : new String[] {
            body() + " ",
            body().replace("pi_local", "pi_changed"),
            body().replace("\n", " "),
            body().replace("原始字节", "\\u539f始字节")
        }) {
            assertSignatureFailure(verifier.verify(changed.getBytes(StandardCharsets.UTF_8), signature, SECRET));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301, -999999999, 999999999})
    void rejectsStaleAndFutureSignatures(long delta) throws Exception {
        assertSignatureFailure(verifier.verify(
                body().getBytes(StandardCharsets.UTF_8), sign(body(), NOW.getEpochSecond() + delta), SECRET));
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 0, 300})
    void acceptsTimestampsWithinTheInclusiveTolerance(long delta) throws Exception {
        assertThat(verifier.verify(
                                body().getBytes(StandardCharsets.UTF_8),
                                sign(body(), NOW.getEpochSecond() + delta),
                                SECRET)
                        .classification())
                .isEqualTo(SUCCESS);
    }

    @Test
    void acceptsMultipleV1SignaturesForSecretRotation() throws Exception {
        String header = "v0=ignored,v1=bad," + sign(body(), NOW.getEpochSecond());
        assertThat(verifier.verify(body().getBytes(StandardCharsets.UTF_8), header, SECRET)
                        .classification())
                .isEqualTo(SUCCESS);
    }

    @Test
    void rejectsInvalidSignatureInputsWithoutExposingInput() throws Exception {
        for (String secret : new String[] {null, "", " ", "not-a-webhook-secret", "whsec_wrong"}) {
            assertSignatureFailure(verifier.verify(
                    body().getBytes(StandardCharsets.UTF_8), sign(body(), NOW.getEpochSecond()), secret));
        }
        for (String signature :
                new String[] {null, "", "t=bad,v1=secret", "t=1,v1", "t=99999999999999999999999,v1=bad", "v1=bad"}) {
            assertSignatureFailure(verifier.verify(body().getBytes(StandardCharsets.UTF_8), signature, SECRET));
        }
        assertSignatureFailure(verifier.verify(null, sign(body(), NOW.getEpochSecond()), SECRET));
    }

    @Test
    void rejectsMalformedUtf8RatherThanSilentlyReplacingBytes() throws Exception {
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        String replacementDecoded = new String(malformed, StandardCharsets.UTF_8);
        assertSignatureFailure(verifier.verify(malformed, sign(replacementDecoded, NOW.getEpochSecond()), SECRET));
    }

    @Test
    void acceptsBoundDirectAccountAndExplicitMatchingAccount() throws Exception {
        assertThat(verify(body()).classification()).isEqualTo(SUCCESS);
        assertThat(verify(body().replace("\"object\":\"event\"", "\"object\":\"event\",\"account\":null"))
                        .classification())
                .isEqualTo(SUCCESS);
        assertThat(verify(body().replace("\"object\":\"event\"", "\"object\":\"event\",\"account\":\"acct_fixture\""))
                        .classification())
                .isEqualTo(SUCCESS);
    }

    @Test
    void rejectsWrongAccountVersionOrLiveMode() throws Exception {
        assertContractFailure(
                verify(body().replace("\"object\":\"event\"", "\"object\":\"event\",\"account\":\"acct_other\"")));
        assertContractFailure(verify(body().replace(VERSION, "2025-01-01.old")));
        assertContractFailure(verify(body().replace("\"livemode\":false", "\"livemode\":true")));
    }

    @Test
    void rejectsMissingOrMalformedMandatoryEventFields() throws Exception {
        for (String field : new String[] {
            "\"id\":\"evt_local\",",
            "\"object\":\"event\",",
            "\"api_version\":\"" + VERSION + "\",",
            "\"created\":1790035200,",
            "\"livemode\":false,",
            "\"type\":\"payment_intent.succeeded\",",
            "\"id\":\"pi_local\","
        }) {
            assertContractFailure(verify(body().replace(field, "")));
        }
        for (String malformed : new String[] {
            "{",
            "null",
            "[]",
            body() + " {}",
            body().replace("\"livemode\":false", "\"livemode\":\"false\""),
            body().replace("1790035200", "1.5"),
            body().replace("1790035200", "\"1790035200\""),
            body().replace("1790035200", "-1"),
            body().replace("evt_local", " "),
            body().replace("\"livemode\":false", "\"livemode\":false,\"livemode\":true")
        }) {
            assertContractFailure(verify(malformed));
        }
    }

    @Test
    void rejectsUnsafeConfigurationWithoutEchoingIt() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new StripeWebhookVerifier(
                        new ProviderScope(
                                SCOPE.organizationId(), SCOPE.environmentId(), SCOPE.providerAccountId(), true),
                        "acct_fixture",
                        VERSION,
                        clock,
                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new StripeWebhookVerifier(SCOPE, "secret-config", VERSION, clock, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-config");
        assertThatThrownBy(() ->
                        new StripeWebhookVerifier(SCOPE, "acct_fixture", "bad-version", clock, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("bad-version");
        assertThatThrownBy(() -> new StripeWebhookVerifier(SCOPE, "acct_fixture", VERSION, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertSignatureFailure(ProviderCallResult<VerifiedWebhook> result) {
        assertThat(result.classification()).isEqualTo(DEFINITIVE_REJECT);
        assertThat(result.payload()).isNull();
        assertThat(result.evidence().providerErrorCode()).isEqualTo("stripe_webhook_invalid_signature");
        assertThat(result.evidence().rawDigest()).isNull();
        assertThat(result.toString()).doesNotContain(SECRET, "cus_sensitive", "do-not-copy");
    }

    private static void assertContractFailure(ProviderCallResult<VerifiedWebhook> result) {
        assertThat(result.classification()).isEqualTo(CONTRACT_MISMATCH);
        assertThat(result.payload()).isNull();
        assertThat(result.evidence().providerErrorCode()).isEqualTo("stripe_webhook_contract_mismatch");
        assertThat(result.evidence().rawDigest()).isNull();
        assertThat(result.toString()).doesNotContain(SECRET, "cus_sensitive", "do-not-copy");
    }
}
