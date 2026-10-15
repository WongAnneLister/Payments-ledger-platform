package com.paymentsledger.payment;

import java.util.UUID;

/** Shared lease entry point. Claim transactions never call a provider. */
public final class OperationLeaseService {
    private final PaymentRepository repository;

    public OperationLeaseService(PaymentRepository repository) {
        this.repository = repository;
    }

    PaymentRepository.Claim acquire(UUID operation, String provider) {
        return repository.claimRecovery(operation, provider, true);
    }
}
