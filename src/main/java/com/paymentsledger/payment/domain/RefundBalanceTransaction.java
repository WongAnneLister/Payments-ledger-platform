package com.paymentsledger.payment.domain;

import java.util.UUID;

/** A provider balance transaction attributed to a refund. */
public record RefundBalanceTransaction(UUID id, String btId, String state, Long feeMinor, UUID journalId) {
    public boolean posted() {
        return "POSTED".equals(state);
    }
}
