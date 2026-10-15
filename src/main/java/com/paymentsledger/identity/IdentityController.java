package com.paymentsledger.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
public class IdentityController {
    private final IdentityRepository repository;

    public IdentityController(IdentityRepository repository) {
        this.repository = repository;
    }

    private TenantContext tenant(HttpServletRequest request) {
        return TenantAccessGuard.require((TenantContext) request.getAttribute(AuthenticationFilter.TENANT));
    }

    @GetMapping("/v1/identity")
    public TenantContext identity(HttpServletRequest request) {
        return tenant(request);
    }

    @GetMapping("/v1/applications/{id}")
    public IdentityRepository.ApplicationView application(HttpServletRequest request, @PathVariable UUID id) {
        return repository.application(tenant(request), id);
    }

    @GetMapping("/v1/prices/{id}")
    public IdentityRepository.PriceView price(HttpServletRequest request, @PathVariable UUID id) {
        return repository.price(tenant(request), id);
    }

    @GetMapping("/ops/identity")
    public OpsContext ops(HttpServletRequest request) {
        OpsContext context = (OpsContext) request.getAttribute(AuthenticationFilter.OPS);
        if (context == null) throw TenantAccessGuard.unauthorized();
        return context;
    }
}
