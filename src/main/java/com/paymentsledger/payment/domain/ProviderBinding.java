package com.paymentsledger.payment.domain;

/** The active test provider account an application may charge through. */
public record ProviderBinding(String provider, String providerAccountRef) {}
