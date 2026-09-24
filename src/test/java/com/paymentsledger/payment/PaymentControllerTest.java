package com.paymentsledger.payment;

import com.paymentsledger.common.error.GlobalExceptionHandler;
import com.paymentsledger.identity.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerTest {
    PaymentService service=mock(PaymentService.class);
    MockMvc mvc;
    UUID id=UUID.randomUUID();
    String publicId(){return PaymentIds.format("pay",id);}
    static final String BODY="""
        {"orderKey":"order-1","productRef":"demo","priceVersion":1,"amountMinor":100,
         "currency":"USD","successUrl":"https://merchant.example/ok","cancelUrl":"https://merchant.example/cancel"}
        """;
    TenantContext context=tenant(Set.of("payments:read","payments:write"));
    static TenantContext tenant(Set<String> scopes) {
        return new TenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Set.of("USD"),false,scopes);
    }
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new PaymentController(service)).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters((request,response,chain)->{
                    if(context!=null) request.setAttribute(AuthenticationFilter.class.getName()+".tenant",context);
                    chain.doFilter(request,response);
                }).build();
    }
    Map<String,Object> hosted() {
        return Map.of("id",publicId(),"paymentStatus","AWAITING_CUSTOMER","nextAction",Map.of("type","REDIRECT_TO_HOSTED_CHECKOUT","hostedUrl","https://example.com/checkout"));
    }
    @Test void newHostedSessionReturns201LocationAndReplayReturns200() throws Exception {
        when(service.create(eq(context),eq("key-123456"),any())).thenReturn(new PaymentService.Creation(hosted(),false),new PaymentService.Creation(hosted(),true));
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/v1/payments/"+publicId()));
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay","true"));
    }
    @Test void missingKeyAndUnknownFieldsNeverReachService() throws Exception {
        mvc.perform(post("/v1/payments").contentType("application/json").content(BODY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content(BODY.replace("\"orderKey\"","\"forgedField\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void malformedJsonGetsStable400WithoutEcho() throws Exception {
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content("{brokenSecret"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("brokenSecret"))));
    }
    @Test void unknownNewIntentCannotMasqueradeAsCreatedHostedSession() throws Exception {
        when(service.create(any(),any(),any())).thenReturn(new PaymentService.Creation(Map.of("id",publicId(),"nextAction",Map.of("type","WAIT_FOR_CONFIRMATION")),false));
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("PROVIDER_UNKNOWN_RESULT"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.details[0].issue").value(publicId()));
    }
    @Test void writeOnlyCallerCanCreateButCannotRead() throws Exception {
        context=tenant(Set.of("payments:write"));when(service.create(any(),any(),any())).thenReturn(new PaymentService.Creation(hosted(),false));
        mvc.perform(post("/v1/payments").header("Idempotency-Key","key-123456").contentType("application/json").content(BODY)).andExpect(status().isCreated());
        mvc.perform(get("/v1/payments/"+publicId())).andExpect(status().isForbidden());verify(service,never()).get(any(),any());
    }
    @Test void missingTrustedContextIsRejected() throws Exception {
        context=null;mvc.perform(get("/v1/payments/"+publicId())).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void cancelAllowsAbsentBodyAndBindsTargetToService() throws Exception {
        when(service.cancel(eq(context),eq(id),eq("cancel-key"),any())).thenReturn(Map.of("id",publicId(),"paymentStatus","CANCELED"));
        mvc.perform(post("/v1/payments/"+publicId()+"/cancel").header("Idempotency-Key","cancel-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("CANCELED"));
        verify(service).cancel(context,id,"cancel-key",new PaymentRequests.Cancel("OTHER",null));
    }
}
