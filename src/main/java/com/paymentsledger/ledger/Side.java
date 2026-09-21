package com.paymentsledger.ledger;

public enum Side {
    DEBIT, CREDIT;

    public Side opposite() { return this == DEBIT ? CREDIT : DEBIT; }
}
