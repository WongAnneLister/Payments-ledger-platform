package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;

/** Strict JSON boundary and stable, semantic request fingerprints. No coercion of money. */
public final class PaymentRequests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> CREATE_FIELDS=Set.of("orderKey","productRef","priceVersion","amountMinor",
            "currency","quantity","successUrl","cancelUrl","customerRef","metadata");
    private static final Set<String> REASONS=Set.of("CUSTOMER_ABANDONED","MERCHANT_CANCELED","TIMEOUT","OTHER");
    private PaymentRequests() { }

    public record Create(String orderKey,String productRef,int priceVersion,long amountMinor,String currency,int quantity,
                         String successUrl,String cancelUrl,String customerRef,Map<String,String> metadata) {
        public Create {
            text(orderKey,128); text(productRef,128);
            require(priceVersion>0 && amountMinor>=0 && quantity>=1 && quantity<=10000);
            require(currency!=null && currency.matches("[A-Z]{3}"));
            url(successUrl); url(cancelUrl);
            if(customerRef!=null) text(customerRef,255);
            metadata=validatedMetadata(metadata==null?Map.of():metadata);
        }
        public String fingerprint() {
            return digest(Arrays.asList("PAYMENT_CREATE_V1",orderKey,productRef,priceVersion,amountMinor,currency,
                    quantity,successUrl,cancelUrl,customerRef,new TreeMap<>(metadata)));
        }
    }
    public record Cancel(String reason,String note) {
        public Cancel {
            reason=reason==null?"OTHER":reason;require(REASONS.contains(reason));
            if(note!=null) { require(note.length()<=500);require(note.codePoints().noneMatch(Character::isISOControl)); }
        }
        public String fingerprint(UUID paymentId) {
            Objects.requireNonNull(paymentId);
            return digest(Arrays.asList("PAYMENT_CANCEL_V1",paymentId.toString(),reason,note));
        }
    }
    public static Create create(JsonNode body) {
        fields(body,CREATE_FIELDS);
        long quantity=body.has("quantity")?integer(body,"quantity"):1;
        long version=integer(body,"priceVersion");
        require(quantity>=1 && quantity<=10000 && version>0 && version<=Integer.MAX_VALUE);
        Map<String,String> metadata=new TreeMap<>();
        if(body.has("metadata")) {
            JsonNode node=body.get("metadata");require(node.isObject());
            node.fields().forEachRemaining(e->{require(e.getValue().isTextual());metadata.put(e.getKey(),e.getValue().textValue());});
        }
        return new Create(string(body,"orderKey",false),string(body,"productRef",false),(int)version,
                integer(body,"amountMinor"),string(body,"currency",false),(int)quantity,
                string(body,"successUrl",false),string(body,"cancelUrl",false),string(body,"customerRef",true),metadata);
    }
    public static Cancel cancel(JsonNode body) {
        if(body==null) return new Cancel(null,null);
        fields(body,Set.of("reason","note"));
        // Explicit null is not part of the cancel schema; an absent property uses the default.
        return new Cancel(body.has("reason")?string(body,"reason",false):null,
                body.has("note")?string(body,"note",false):null);
    }
    public static String idempotencyKey(String key) {
        if(key==null || key.isBlank()) throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,"Idempotency-Key is required");
        require(key.length()>=8 && key.length()<=255 && key.codePoints().allMatch(c->c>=33 && c<=126));
        return key;
    }
    private static Map<String,String> validatedMetadata(Map<String,String> values) {
        require(values.size()<=44);
        TreeMap<String,String> copy=new TreeMap<>();
        values.forEach((key,value)->{
            text(key,40);require(value!=null && value.length()<=500 && value.codePoints().noneMatch(Character::isISOControl));
            String normalized=key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
            require(!key.contains("[") && !key.contains("]") && !normalized.startsWith("pr06")
                    && !Set.of("paymentid","businessorderid","organizationid","environmentid","provideraccountid",
                        "applicationid","livemode","cardnumber","cvc","cvv","cardcvc","clientsecret","authorization",
                        "apikey","secret","password","token").contains(normalized));
            copy.put(key,value);
        });
        return Collections.unmodifiableMap(copy);
    }
    private static void fields(JsonNode node,Set<String> allowed) {
        require(node!=null && node.isObject());node.fieldNames().forEachRemaining(name->require(allowed.contains(name)));
    }
    private static String string(JsonNode body,String name,boolean nullable) {
        JsonNode node=body.get(name);
        if(nullable && (node==null || node.isNull())) return null;
        require(node!=null && node.isTextual());return node.textValue();
    }
    private static long integer(JsonNode body,String name) {
        JsonNode node=body.get(name);require(node!=null && node.isIntegralNumber() && node.canConvertToLong());return node.longValue();
    }
    private static void text(String value,int max) {
        require(value!=null && !value.isBlank() && value.length()<=max && value.codePoints().noneMatch(Character::isISOControl));
    }
    private static void url(String value) {
        text(value,2048);
        try { URI uri=URI.create(value);require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null); }
        catch(IllegalArgumentException invalid) {throw invalid();}
    }
    private static String digest(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));}
        catch(Exception error) {throw new IllegalStateException("Cannot fingerprint payment request");}
    }
    private static void require(boolean condition) {if(!condition) throw invalid();}
    private static ApiException invalid() {return new ApiException(ErrorCode.VALIDATION_ERROR,"Invalid payment request");}
}
