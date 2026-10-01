package com.paymentsledger.financialingestion;
import com.paymentsledger.provider.ProviderScope;
@FunctionalInterface public interface PayoutSourceResolver {
 void resolve(ProviderScope scope,String payoutId);
 static PayoutSourceResolver unavailable(){return (scope,id)->{};}
}
