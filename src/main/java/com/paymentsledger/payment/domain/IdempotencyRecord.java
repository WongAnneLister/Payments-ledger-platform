package com.paymentsledger.payment.domain;

import java.util.UUID;

/** A stored API request: its fingerprint decides whether a retry with the same key is a replay or a conflict. */
public record IdempotencyRecord(String requestFingerprint, UUID paymentId, UUID operationId) {}
