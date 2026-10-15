package com.paymentsledger.payment;

import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final PaymentRepository repository;
    private final PaymentExecutor executor;

    public PaymentService(PaymentRepository repository, PaymentExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    public record Creation(Map<String, Object> resource, boolean replay) {}

    public Creation create(TenantContext context, String key, PaymentRequests.Create request) {
        PaymentRepository.outsideTransaction();
        TenantAccessGuard.requireScope(context, "payments:write");
        var intent = repository.prepare(context, key, request, executor.providerName());
        if (!intent.replay()) {
            try {
                executor.execute(intent.operationId());
            } catch (RuntimeException failure) {
                var code = failure instanceof ApiException api ? api.errorCode() : ErrorCode.PROVIDER_UNKNOWN_RESULT;
                throw new ApiException(
                        code,
                        "Payment intent persisted; query the existing payment before further action",
                        List.of(new ApiError.Detail("paymentId", PaymentIds.format("pay", intent.paymentId()))));
            }
        }
        var resource = repository.read(context, intent.paymentId());
        if (!intent.replay()) {
            var operation = repository.operation(intent.operationId());
            if ("ACKNOWLEDGED".equals(operation.get("status")) && "KNOWN".equals(operation.get("resolution")))
                throw new ApiException(
                        ErrorCode.VALIDATION_ERROR,
                        "Provider definitively rejected this payment intent; reuse the existing resource reference",
                        List.of(new ApiError.Detail(
                                "paymentId", resource.get("id").toString())));
        }
        var provider = (Map<?, ?>) resource.get("provider");
        if (!intent.replay()
                && (!"KNOWN".equals(resource.get("resolutionStatus")) || provider.get("checkoutSessionId") == null))
            throw new ApiException(
                    ErrorCode.PROVIDER_UNKNOWN_RESULT,
                    "Payment intent persisted; query the existing payment before further action",
                    List.of(new ApiError.Detail("paymentId", resource.get("id").toString())));
        return new Creation(resource, intent.replay());
    }

    public Map<String, Object> get(TenantContext context, UUID id) {
        PaymentRepository.outsideTransaction();
        TenantAccessGuard.requireScope(context, "payments:read");
        repository.read(context, id);
        executor.refresh(id);
        return repository.read(context, id);
    }

    public Map<String, Object> cancel(TenantContext context, UUID id, String key, PaymentRequests.Cancel request) {
        PaymentRepository.outsideTransaction();
        TenantAccessGuard.requireScope(context, "payments:write");
        var intent = repository.prepareCancel(context, id, key, request, executor.providerName());
        if (!intent.replay()) executor.execute(intent.operationId());
        var resource = repository.read(context, id);
        if ("SUCCEEDED".equals(resource.get("paymentStatus")))
            throw PaymentRepository.error(ErrorCode.PAYMENT_NOT_CANCELABLE);
        return resource;
    }
}
