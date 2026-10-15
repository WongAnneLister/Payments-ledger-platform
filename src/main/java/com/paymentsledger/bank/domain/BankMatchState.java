package com.paymentsledger.bank.domain;

/** platform.payout.bank_match_state: how much of the outgoing principal the bank has confirmed. */
public enum BankMatchState {
    UNMATCHED,
    PARTIAL,
    MATCHED,
    CONFLICT
}
