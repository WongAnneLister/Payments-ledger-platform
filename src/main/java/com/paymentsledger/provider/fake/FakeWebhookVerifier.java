package com.paymentsledger.provider.fake;

import com.paymentsledger.provider.*;
import com.paymentsledger.provider.ProviderModels.VerifiedWebhook;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.paymentsledger.provider.ProviderCallClassification.*;

/** Local fixture HMAC scheme. Intentionally not Stripe-Signature compatible. */
public final class FakeWebhookVerifier implements WebhookVerifierPort {
    private final Clock clock;
    private final Duration tolerance;
    public FakeWebhookVerifier(Clock clock,Duration tolerance) {
        this.clock=Objects.requireNonNull(clock);this.tolerance=Objects.requireNonNull(tolerance);
        if(tolerance.isNegative() || tolerance.isZero())throw new IllegalArgumentException("Positive signature tolerance required");
    }
    public String sign(byte[] rawBytes,String secret) {
        long timestamp=clock.instant().getEpochSecond();
        return "fake_t="+timestamp+",fake_v1="+HexFormat.of().formatHex(mac(rawBytes,secret,timestamp));
    }
    @Override public ProviderCallResult<VerifiedWebhook> verify(byte[] rawBytes,String signatureHeader,String secret) {
        Instant requested=clock.instant();ProviderCallClassification classification=DEFINITIVE_REJECT;VerifiedWebhook fact=null;
        try {
            if(rawBytes==null || signatureHeader==null || secret==null || secret.isBlank())throw new IllegalArgumentException("Missing signature input");
            String[] parts=signatureHeader.split(",",-1);
            if(parts.length!=2 || !parts[0].startsWith("fake_t=") || !parts[1].startsWith("fake_v1="))throw new IllegalArgumentException("Invalid fake signature");
            long timestamp=Long.parseLong(parts[0].substring(7));Instant signed=Instant.ofEpochSecond(timestamp);
            if(Duration.between(signed,requested).abs().compareTo(tolerance)>0)throw new IllegalArgumentException("Stale fake signature");
            byte[] given=HexFormat.of().parseHex(parts[1].substring(8));
            if(!MessageDigest.isEqual(given,mac(rawBytes,secret,timestamp)))throw new IllegalArgumentException("Signature mismatch");
            fact=FakeStateStore.JSON.readValue(rawBytes,VerifiedWebhook.class);
            if(fact.livemode())throw new IllegalArgumentException("Live fixture rejected");
            classification=SUCCESS;
        } catch(Exception invalid) { fact=null; }
        return new ProviderCallResult<>(classification,fact,new ProviderEvidence(null,null,requested,requested,
                classification==SUCCESS?200:400,classification==SUCCESS?null:"fake_invalid_signature",
                FakeState.digest(rawBytes==null?new byte[0]:rawBytes),FakeStripeProvider.API_VERSION));
    }
    private static byte[] mac(byte[] raw,String secret,long timestamp) {
        Objects.requireNonNull(raw);if(secret==null||secret.isBlank())throw new IllegalArgumentException("Fixture secret required");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));return mac.doFinal(raw);
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
