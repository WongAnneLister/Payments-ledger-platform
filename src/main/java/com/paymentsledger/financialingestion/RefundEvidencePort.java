package com.paymentsledger.financialingestion;

import com.paymentsledger.provider.ProviderScope;
import java.util.*;

/** PR11 supplies a trusted local refund lookup, under the already locked payment row. The flag verifies the association, not business refund success; a real debit may precede resolution. No provider I/O. */
public interface RefundEvidencePort {
    record Evidence(UUID paymentId,String refundId,String balanceTransactionId,long amountMinor,String currency,boolean evidenceVerified){}
    Optional<Evidence> find(ProviderScope scope,String refundId);
    static RefundEvidencePort unavailable(){return (scope,id)->Optional.empty();}
}
