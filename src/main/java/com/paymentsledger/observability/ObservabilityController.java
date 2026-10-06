package com.paymentsledger.observability;
import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.identity.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController
@Profile("api")
public class ObservabilityController {
    private final ObservabilityService service;
    public ObservabilityController(ObservabilityService service) {this.service=service;}
    @GetMapping("/ops/observability")
    public JsonNode read(HttpServletRequest request) {
        var snapshot=service.snapshot(AuthenticationFilter.ops(request));
        CorrelationLog.read("ops.observability",snapshot);
        return snapshot;
    }
}
