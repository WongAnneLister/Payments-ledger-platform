package com.paymentsledger.webhook;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit scalar projection: expanded Stripe objects and arbitrary metadata never reach storage. */
public final class WebhookNormalizer {
    private WebhookNormalizer() {}
    private static final Set<String> TEXT=Set.of("id","object","status","payment_status","currency","failure_code","failure_reason","reason");
    private static final Set<String> IDS=Set.of("payment_intent","latest_charge","charge","balance_transaction","failure_balance_transaction","payout");
    private static final Set<String> NUMBERS=Set.of("amount","amount_total","amount_received","amount_captured","created","expires_at","arrival_date");
    public static ObjectNode sanitize(JsonNode object,ObjectMapper mapper){
        if(!object.isObject())throw new IllegalArgumentException("Webhook object required");
        ObjectNode result=mapper.createObjectNode();
        object.fields().forEachRemaining(e->{String k=e.getKey();JsonNode v=e.getValue();
            if(TEXT.contains(k)&&v.isTextual()&&v.textValue().length()<=256)result.set(k,v);
            else if(IDS.contains(k)){JsonNode id=v.isObject()?v.path("id"):v;if(id.isTextual()&&id.textValue().matches("[A-Za-z0-9_]{1,255}"))result.set(k,id);}
            else if(NUMBERS.contains(k)&&v.isIntegralNumber()&&v.canConvertToLong())result.set(k,v);
            else if(Set.of("captured","paid","automatic","livemode").contains(k)&&v.isBoolean())result.set(k,v);
        });
        if("dispute".equals(object.path("object").asText())){
            var due=object.path("evidence_details").path("due_by");
            if(due.isIntegralNumber()&&due.canConvertToLong()&&due.longValue()>=0)result.putObject("evidence_details").set("due_by",due);
            var links=object.path("balance_transactions");
            if(links.isArray()&&links.size()<=100){
                var ids=mapper.createArrayNode();boolean valid=true;
                for(var link:links){var id=link.isObject()?link.path("id"):link;
                    if(!id.isTextual()||!id.textValue().matches("[A-Za-z0-9_]{1,255}")){valid=false;break;}ids.add(id.textValue());}
                if(valid)result.set("balance_transactions",ids);
            }
        }
        JsonNode payment=object.path("metadata").path("payment_id");
        if(payment.isTextual()&&(payment.textValue().matches("pay_[0-9A-HJKMNP-TV-Z]{26}")||payment.textValue().matches("[0-9a-fA-F-]{36}")))result.putObject("metadata").set("payment_id",payment);
        return result;
    }
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String semanticKey(JsonNode object){return object.path("object").asText()+":"+object.path("id").asText()+":"+digest(canonical(object).getBytes(StandardCharsets.UTF_8));}
    private static String canonical(JsonNode n){if(n.isObject()){var fields=new TreeMap<String,String>();n.fields().forEachRemaining(e->fields.put(e.getKey(),canonical(e.getValue())));return fields.toString();}return n.toString();}
}
