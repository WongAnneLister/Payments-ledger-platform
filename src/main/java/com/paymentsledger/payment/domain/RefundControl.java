package com.paymentsledger.payment.domain;

import java.util.UUID;

/** A payment's refund ledger: captured money, refunds that succeeded, and reservations still in flight. */
public record RefundControl(
        UUID paymentId,
        long capturedMinor,
        long refundedMinor,
        long reservedMinor,
        RefundControlState state,
        long version) {
    /** What may still be reserved; negative while a frozen control holds more than was captured. */
    public long availableMinor() {
        return capturedMinor - refundedMinor - reservedMinor;
    }
}
