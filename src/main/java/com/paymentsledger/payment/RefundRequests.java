package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.common.error.*;
import java.util.*;
import java.security.MessageDigest;

/** Strict refund boundary; all amounts remain integer minor units. */
public final class RefundRequests {
 private RefundRequests(){}
 public record Create(UUID paymentId,long amountMinor,String currency,String reason,String note,String clientRefundKey){
  public Create {
   require(paymentId!=null && amountMinor>0 && currency!=null && currency.matches("[A-Z]{3}"));
   reason=reason==null?"OTHER":reason;
   require(Set.of("REQUESTED_BY_CUSTOMER","DUPLICATE","FRAUDULENT","MERCHANT_DECISION","OTHER").contains(reason));
   require(note==null || note.length()<=500 && note.codePoints().noneMatch(Character::isISOControl));
   require(clientRefundKey==null || !clientRefundKey.isBlank() && clientRefundKey.length()<=255 && clientRefundKey.codePoints().noneMatch(Character::isISOControl));
  }
  public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(Arrays.asList("REFUND_V1",paymentId.toString(),amountMinor,currency,reason,note,clientRefundKey))));}catch(Exception e){throw new IllegalStateException(e);}}
 }
 public static Create create(JsonNode body){
  require(body!=null && body.isObject());
  body.fieldNames().forEachRemaining(k->require(Set.of("paymentId","amountMinor","currency","reason","note","clientRefundKey").contains(k)));
  JsonNode amount=body.get("amountMinor");require(amount!=null && amount.isIntegralNumber() && amount.canConvertToLong());
  return new Create(PaymentIds.payment(text(body,"paymentId",false)),amount.longValue(),text(body,"currency",false),text(body,"reason",true),text(body,"note",true),text(body,"clientRefundKey",true));
 }
 private static String text(JsonNode b,String name,boolean optional){var n=b.get(name);if(optional && (n==null || name.equals("clientRefundKey") && n.isNull()))return null;require(n!=null && n.isTextual());return n.textValue();}
 private static void require(boolean ok){if(!ok)throw new ApiException(ErrorCode.VALIDATION_ERROR,"Invalid refund request");}
}
