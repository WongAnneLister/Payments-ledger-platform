package com.paymentsledger.payment;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class RecoveryPolicyTest {
 @Test void backoffAndJitterAreBoundedDeterministicAndCapAtTwoMinutes(){
  var p=new RecoveryPolicy(Duration.ofSeconds(30),Duration.ZERO,Duration.ZERO,0,Duration.ofHours(1),Duration.ofSeconds(1));
  UUID id=new UUID(0,1);for(int i=1;i<=6;i++)assertThat(p.delay(id,i).toSeconds()).isEqualTo(new long[]{5,15,30,60,120,120}[i-1]);
  p=RecoveryPolicy.defaults();for(int i=1;i<=6;i++){long base=new long[]{5,15,30,60,120,120}[i-1]*1000;assertThat(p.delay(id,i).toMillis()).isBetween((long)(base*.9),(long)(base*1.1));assertThat(p.delay(id,i)).isEqualTo(p.delay(id,i));}
 }
 @Test void unverifiedStripeIsAlwaysDisabledAndFakeCannotExceedActualConfiguredRetention(){
  var p=new RecoveryPolicy(Duration.ofSeconds(30),Duration.ofHours(1),Duration.ofDays(1),0,Duration.ofHours(1),Duration.ZERO);
  assertThat(p.verifiedWindow("STRIPE")).isEqualTo(Duration.ZERO);
  assertThatThrownBy(()->new RecoveryPolicy(Duration.ofSeconds(30),Duration.ofDays(2),Duration.ofDays(1),0,Duration.ofHours(1),Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void configuredL1DelaysReplaceDefaultsAndRejectNonpositiveIntervals(){
  var p=RecoveryPolicy.from(new org.springframework.mock.env.MockEnvironment().withProperty("platform.recovery.l1-delays","PT2S,PT4S"));
  assertThat(p.delay(new UUID(0,1),1).toMillis()).isBetween(1800L,2200L);assertThat(p.delay(new UUID(0,1),3).toMillis()).isBetween(3600L,4400L);
  assertThatThrownBy(()->RecoveryPolicy.from(new org.springframework.mock.env.MockEnvironment().withProperty("platform.recovery.l1-delays","PT0S"))).isInstanceOf(IllegalArgumentException.class);
 }
}
