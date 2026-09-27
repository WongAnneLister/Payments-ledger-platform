package com.paymentsledger.notification;

import java.net.URI;
import java.util.UUID;

/** HTTP boundary: called only after the delivery claim transaction has committed. */
public interface MerchantTransport {
    record Target(URI url, String keyRef) {}
    enum Outcome { DELIVERED, RETRYABLE, PERMANENT_REJECT }
    record Result(Outcome outcome, int status, String code) {}
    Result send(Target target, UUID eventId, String businessKey, byte[] payload);
}
