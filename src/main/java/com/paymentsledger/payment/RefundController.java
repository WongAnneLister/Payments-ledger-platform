package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
public class RefundController {
    private final RefundRepository repository;
    private final RefundExecutor executor;

    public RefundController(RefundRepository repository, RefundExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    @PostMapping("/v1/refunds")
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        var tenant = AuthenticationFilter.tenant(request);
        TenantAccessGuard.requireScope(tenant, "refunds:write");
        var intent = repository.prepare(
                tenant, PaymentRequests.idempotencyKey(key), RefundRequests.create(body), executor.providerName());
        // POST is an accepted durable reservation; worker execution is independent of the request lifetime.
        var resource = repository.read(
                new TenantContext(
                        tenant.organizationId(),
                        tenant.environmentId(),
                        tenant.applicationId(),
                        tenant.allowedProviderAccountId(),
                        tenant.allowedCurrencies(),
                        false,
                        Set.of("refunds:read")),
                intent.refundId());
        return intent.replay()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(resource)
                : ResponseEntity.created(URI.create("/v1/refunds/" + resource.get("id")))
                        .body(resource);
    }

    @GetMapping("/v1/refunds/{id}")
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String id) {
        return repository.read(AuthenticationFilter.tenant(request), PaymentIds.parse("ref", id));
    }
}
