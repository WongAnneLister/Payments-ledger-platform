package com.paymentsledger.payment.domain;

/** The active price and revenue-share policy for a product version, with JSON snapshots kept on the order. */
public record PriceQuote(long unitAmountMinor, String currency, String priceSnapshot, String policySnapshot) {}
