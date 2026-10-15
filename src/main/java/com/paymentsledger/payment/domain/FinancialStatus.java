package com.paymentsledger.payment.domain;

/** platform.payment.financial_status: whether captured money is backed by posted provider balance transactions. */
public enum FinancialStatus {
    NOT_APPLICABLE,
    PENDING_EVIDENCE,
    PARTIALLY_POSTED,
    POSTED,
    CONFLICT
}
