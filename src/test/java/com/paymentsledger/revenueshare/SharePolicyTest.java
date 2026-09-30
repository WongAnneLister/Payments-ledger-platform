package com.paymentsledger.revenueshare;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Random;
import org.junit.jupiter.api.Test;
class SharePolicyTest {
 private final ObjectMapper mapper=new ObjectMapper();
 @Test void cumulativeCeilingMatchesIndependentIntegerModel(){
  assertThat(SharePolicy.cap(10000,0,0,2000)).isEqualTo(2000);
  assertThat(SharePolicy.cap(10000,0,4000,2000)).isEqualTo(1200);
  assertThat(SharePolicy.cap(10000,4000,0,2000)).isEqualTo(1200);
  var random=new Random(1291);
  for(int i=0;i<10000;i++){
   long c=random.nextLong(Long.MAX_VALUE),r=random.nextLong(c+1),h=random.nextLong(c-r+1);int rate=random.nextInt(10001);
   long base=c-r-h;
   long expected=Math.addExact(Math.multiplyExact(base/10000,rate),((base%10000)*rate)/10000);
   assertThat(SharePolicy.cap(c,r,h,rate)).isEqualTo(expected);
  }
  assertThat(SharePolicy.cap(1,Long.MAX_VALUE,Long.MAX_VALUE,10000)).isZero();
  assertThat(SharePolicy.cap(Long.MAX_VALUE,0,0,10000)).isEqualTo(Long.MAX_VALUE);
  // Three one-unit refunds at 3333 bps must use the shrinking cumulative base.
  assertThat(SharePolicy.cap(10,3,0,3333)).isEqualTo(2);
  assertThatThrownBy(()->SharePolicy.cap(-1,0,0,1000)).isInstanceOf(RuntimeException.class);
 }
 @Test void validatesExactRatesAndUniquePartners() throws Exception {
  assertThat(SharePolicy.parse(mapper.readTree("{\"policy_version\":1,\"allocations\":[{\"partner_ref\":\"a\",\"share_basis_points\":2000},{\"partner_ref\":\"b\",\"share_basis_points\":8000}]}" )).partners()).hasSize(2);
  for(String allocations:new String[]{"[{\"partner_ref\":\"a\",\"share_basis_points\":0.5}]","[{\"partner_ref\":\"a\",\"share_basis_points\":-1}]","[{\"partner_ref\":\"a\",\"share_basis_points\":10001}]","[{\"partner_ref\":\"a\",\"share_basis_points\":5000},{\"partner_ref\":\"a\",\"share_basis_points\":1}]","[{\"partner_ref\":\"a\",\"share_basis_points\":6000},{\"partner_ref\":\"b\",\"share_basis_points\":6000}]"}) {
   var policy=mapper.readTree("{\"policy_version\":1,\"allocations\":"+allocations+"}");assertThatThrownBy(()->SharePolicy.parse(policy)).isInstanceOf(RuntimeException.class);
  }
 }
}
