package com.paymentsledger.revenueshare;
import java.util.UUID;
/** PR13 supplies dispute evidence gating; evaluated inside the existing payment transaction. */
@FunctionalInterface
public interface ShareEligibilityPolicy {
 boolean eligible(UUID paymentId);
 static ShareEligibilityPolicy allow(){return paymentId->true;}
}
