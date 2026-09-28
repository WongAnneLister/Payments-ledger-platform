package com.paymentsledger.payment;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class RefundRequestsTest {
 @Test void strictMoneyBoundaryAndStableFingerprint() throws Exception {
  var json=new ObjectMapper();String id=PaymentIds.format("pay",PaymentIds.newId());
  var a=RefundRequests.create(json.readTree("{\"paymentId\":\""+id+"\",\"amountMinor\":600,\"currency\":\"USD\"}"));
  assertThat(a.amountMinor()).isEqualTo(600);
  assertThat(a.fingerprint()).isEqualTo(RefundRequests.create(json.readTree("{\"currency\":\"USD\",\"amountMinor\":600,\"paymentId\":\""+id+"\"}")).fingerprint());
  assertThatThrownBy(()->RefundRequests.create(json.readTree("{\"paymentId\":\""+id+"\",\"amountMinor\":0,\"currency\":\"USD\"}"))).isInstanceOf(com.paymentsledger.common.error.ApiException.class);
  assertThatThrownBy(()->RefundRequests.create(json.readTree("{\"paymentId\":\""+id+"\",\"amountMinor\":1.2,\"currency\":\"USD\"}"))).isInstanceOf(com.paymentsledger.common.error.ApiException.class);
 }
}
