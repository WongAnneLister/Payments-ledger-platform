package com.paymentsledger.bank.domain;

/** External completeness of a payout's bank evidence, separate from the commit-time allocation invariant. */
public enum BankEvidenceState {
    CONFLICT,
    WAITING_EVIDENCE,
    MATCHED,
    RETURNED,
    SHORT_RECEIPT
}
