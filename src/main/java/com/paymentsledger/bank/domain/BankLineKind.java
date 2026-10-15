package com.paymentsledger.bank.domain;

/** A bank line either receives payout principal or returns principal previously received. */
public enum BankLineKind {
    CREDIT,
    RETURN
}
