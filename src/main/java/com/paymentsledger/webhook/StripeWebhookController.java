package com.paymentsledger.webhook;

import com.paymentsledger.common.error.*;
import com.paymentsledger.provider.ProviderCallClassification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Acknowledgement follows the completed receipt transaction; no business processing on this request. */
@RestController
@Profile("api")
public final class StripeWebhookController {
    private final ObjectProvider<StripeWebhookEndpoint> endpoint;
    private final WebhookInboxRepository inbox;
    public StripeWebhookController(ObjectProvider<StripeWebhookEndpoint> endpoint,WebhookInboxRepository inbox){
        this.endpoint=endpoint;this.inbox=inbox;
    }
    @PostMapping(path="/webhooks/stripe",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Boolean> receive(HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        StripeWebhookEndpoint binding=endpoint.getIfAvailable();
        if(binding==null)throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,"Stripe webhook endpoint is not configured");
        var signatures=Collections.list(request.getHeaders("Stripe-Signature"));
        if(signatures.size()!=1)throw invalidSignature();
        byte[] raw;
        try{raw=request.getInputStream().readNBytes(binding.maximumBytes()+1);}
        catch(IOException invalid){throw new ApiException(ErrorCode.VALIDATION_ERROR,"Unable to read webhook payload");}
        if(raw.length>binding.maximumBytes())throw new ApiException(ErrorCode.VALIDATION_ERROR,"Webhook payload exceeds configured limit");
        var verified=binding.verify(raw,signatures.getFirst());
        if(verified.classification()!=ProviderCallClassification.SUCCESS || verified.payload()==null)throw invalidSignature();
        var receipt=inbox.receive(binding.scope(),binding.accountRef(),raw,verified);
        return Map.of("received",true,"duplicate",receipt.duplicate());
    }
    private static ApiException invalidSignature(){
        return new ApiException(ErrorCode.SIGNATURE_VERIFICATION_FAILED,"Webhook signature or endpoint scope is invalid");
    }
}
