package com.paymentsledger.provider.adapter.stripe;

import static com.paymentsledger.provider.ProviderCallClassification.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.provider.ProviderCallClassification;
import com.paymentsledger.provider.ProviderCallResult;
import com.paymentsledger.provider.ProviderEvidence;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import com.paymentsledger.provider.ProviderScope;
import com.paymentsledger.provider.WebhookVerifierPort;
import com.stripe.net.Webhook;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Verifies snapshot webhooks for one bound direct Stripe test account; no Connect routing. */
public final class StripeWebhookVerifier implements WebhookVerifierPort {
    private static final String PINNED_API_VERSION = "2026-07-29.dahlia";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String expectedStripeAccountId;
    private final String expectedApiVersion;
    private final Clock clock;
    private final Duration tolerance;

    public StripeWebhookVerifier(
            ProviderScope expectedScope,
            String expectedStripeAccountId,
            String expectedApiVersion,
            Clock clock,
            Duration tolerance) {
        Objects.requireNonNull(expectedScope, "expectedScope");
        if (expectedScope.livemode()) throw new IllegalArgumentException("Only test mode is supported");
        if (expectedStripeAccountId == null || !expectedStripeAccountId.matches("acct_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("A valid bound Stripe account is required");
        if (!PINNED_API_VERSION.equals(expectedApiVersion))
            throw new IllegalArgumentException("The pinned Stripe API version is required");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative() || tolerance.isZero())
            throw new IllegalArgumentException("Positive signature tolerance required");
        this.expectedStripeAccountId = expectedStripeAccountId;
        this.expectedApiVersion = expectedApiVersion;
    }

    @Override
    public ProviderCallResult<VerifiedWebhook> verify(byte[] rawBytes, String signatureHeader, String secret) {
        Instant requestedAt = clock.instant();
        String body;
        try {
            if (rawBytes == null
                    || signatureHeader == null
                    || signatureHeader.isBlank()
                    || secret == null
                    || !secret.startsWith("whsec_")
                    || secret.substring(6).isBlank())
                return failure(DEFINITIVE_REJECT, "stripe_webhook_invalid_signature", requestedAt);
            // REPORT is essential: replacement decoding would verify bytes other than those received.
            body = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes))
                    .toString();
            // The SDK's timestamp check is past-only. Verify the MAC with it, then bound both directions.
            Webhook.Signature.verifyHeader(body, signatureHeader, secret, 0, Clock.fixed(requestedAt, clock.getZone()));
            Instant signedAt = Instant.ofEpochSecond(signatureTimestamp(signatureHeader));
            if (Duration.between(signedAt, requestedAt).abs().compareTo(tolerance) > 0)
                return failure(DEFINITIVE_REJECT, "stripe_webhook_invalid_signature", requestedAt);
        } catch (Exception invalidSignature) {
            // SDK exceptions may contain the signature or other input; never attach them to evidence.
            return failure(DEFINITIVE_REJECT, "stripe_webhook_invalid_signature", requestedAt);
        }

        try {
            JsonNode event = JSON.readTree(body);
            if (event == null
                    || !event.isObject()
                    || !"event".equals(requiredText(event, "object"))
                    || !expectedApiVersion.equals(requiredText(event, "api_version"))
                    || !event.path("livemode").isBoolean()
                    || event.path("livemode").booleanValue())
                return failure(CONTRACT_MISMATCH, "stripe_webhook_contract_mismatch", requestedAt);
            JsonNode account = event.get("account");
            // Direct account events omit account. Only the endpoint's configured binding supplies it.
            if (account != null
                    && !account.isNull()
                    && (!account.isTextual() || !expectedStripeAccountId.equals(account.textValue())))
                return failure(CONTRACT_MISMATCH, "stripe_webhook_contract_mismatch", requestedAt);
            JsonNode created = event.path("created");
            if (!created.isIntegralNumber() || !created.canConvertToLong() || created.longValue() < 0)
                return failure(CONTRACT_MISMATCH, "stripe_webhook_contract_mismatch", requestedAt);
            VerifiedWebhook fact = new VerifiedWebhook(
                    requiredText(event, "id"),
                    requiredText(event, "type"),
                    requiredText(event.path("data").path("object"), "id"),
                    Instant.ofEpochSecond(created.longValue()),
                    false);
            return new ProviderCallResult<>(SUCCESS, fact, evidence(requestedAt, null, digest(fact)));
        } catch (Exception invalidContract) {
            return failure(CONTRACT_MISMATCH, "stripe_webhook_contract_mismatch", requestedAt);
        }
    }

    private static long signatureTimestamp(String header) {
        // Select the same first t= component as Stripe's SDK to avoid parsing ambiguity.
        for (String part : header.split(",", -1)) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals("t")) return Long.parseLong(pair[1]);
        }
        throw new IllegalArgumentException("Missing signature timestamp");
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Invalid event field");
        return value.textValue();
    }

    private String digest(VerifiedWebhook fact) throws Exception {
        // A digest of explicitly allowlisted facts, never a hash of the raw sensitive event body.
        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("event_id", fact.eventId());
        allowed.put("event_type", fact.eventType());
        allowed.put("object_id", fact.objectId());
        allowed.put("created", fact.createdAt().getEpochSecond());
        allowed.put("livemode", false);
        allowed.put("account", expectedStripeAccountId);
        allowed.put("api_version", expectedApiVersion);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(allowed)));
    }

    private ProviderCallResult<VerifiedWebhook> failure(
            ProviderCallClassification classification, String code, Instant requestedAt) {
        return new ProviderCallResult<>(classification, null, evidence(requestedAt, code, null));
    }

    private ProviderEvidence evidence(Instant requestedAt, String code, String digest) {
        return new ProviderEvidence(null, null, requestedAt, clock.instant(), null, code, digest, expectedApiVersion);
    }
}
