package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/v1/payments")
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        TenantContext tenant = AuthenticationFilter.tenant(request);
        TenantAccessGuard.requireScope(tenant, "payments:write");
        var created = service.create(tenant, PaymentRequests.idempotencyKey(key), PaymentRequests.create(body));
        var id = created.resource().get("id");
        if (created.replay())
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(created.resource());
        // A local intent alone cannot satisfy the frozen 201 "hosted session created" contract.
        Object action = created.resource().get("nextAction");
        if (!(action instanceof Map<?, ?> map) || !"REDIRECT_TO_HOSTED_CHECKOUT".equals(map.get("type")))
            throw new ApiException(
                    ErrorCode.PROVIDER_UNKNOWN_RESULT,
                    "Payment intent saved; query the existing resource",
                    List.of(new ApiError.Detail("paymentId", String.valueOf(id))));
        return ResponseEntity.created(URI.create("/v1/payments/" + id)).body(created.resource());
    }

    @GetMapping("/v1/payments/{id}")
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String id) {
        TenantContext tenant = AuthenticationFilter.tenant(request);
        TenantAccessGuard.requireScope(tenant, "payments:read");
        return service.get(tenant, PaymentIds.payment(id));
    }

    @PostMapping("/v1/payments/{id}/cancel")
    public Map<String, Object> cancel(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) JsonNode body) {
        TenantContext tenant = AuthenticationFilter.tenant(request);
        TenantAccessGuard.requireScope(tenant, "payments:write");
        return service.cancel(
                tenant, PaymentIds.payment(id), PaymentRequests.idempotencyKey(key), PaymentRequests.cancel(body));
    }
}
