package com.paymentsledger.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.config.RunRoleConfig;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class AuthenticationFilterTest {
    final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    final TenantContext tenant=new TenantContext(id,id,id,id,Set.of("USD"),false,Set.of());
    final IdentityRepository repository=mock(IdentityRepository.class);
    AuthenticationFilter filter(String role) {
        when(repository.authenticateApplication(any())).thenReturn(tenant);
        return new AuthenticationFilter(repository,new CredentialTokens(),new ObjectMapper(),new RunRoleConfig(new MockEnvironment().withProperty("spring.profiles.active",role)));
    }
    MockHttpServletRequest request(String path,String body) {
        var r=new MockHttpServletRequest("POST",path);
        r.addHeader("Authorization","Bearer app_"+id+"."+"a".repeat(43));
        r.setContentType("application/json"); r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return r;
    }
    @Test void rejectsForgedBodyAndDuplicateJsonFields() throws Exception {
        for(String body:new String[]{"{\"organization_id\":\"forged\"}","{\"organization_id\":\""+id+"\",\"organization_id\":\"forged\"}"}) {
            var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
            filter("api").doFilter(request("/v1/example",body),response,chain);
            assertThat(response.getStatus()).isIn(400,403); assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void preservesJsonBytesAndClearsIdentityAfterRequest() throws Exception {
        String body="{ \"organization_id\" : \""+id+"\", \"amountMinor\": 10000 }";
        var request=request("/v1/example",body);
        filter("api").doFilter(request,new MockHttpServletResponse(),(req,res) -> {
            assertThat(req.getInputStream().readAllBytes()).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(req.getAttribute(AuthenticationFilter.TENANT)).isEqualTo(tenant);
        });
        assertThat(request.getAttribute(AuthenticationFilter.TENANT)).isNull();
    }
    @Test void webhookRawBytesAreUntouchedAndWorkerRejectsBusinessRoutes() throws Exception {
        String body="not JSON: raw signed bytes";
        filter("api").doFilter(request("/webhooks/stripe",body),new MockHttpServletResponse(),(req,res) ->
                assertThat(req.getInputStream().readAllBytes()).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var response=new MockHttpServletResponse();
        filter("worker").doFilter(request("/v1/identity","{}"),response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(repository);
    }
    @Test void authenticationConnectionFailureIsRetryableAndDoesNotLeakDetails() throws Exception {
        var filter=filter("api");
        when(repository.authenticateApplication(any())).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("private database details"));
        var response=new MockHttpServletResponse();
        filter.doFilter(request("/v1/identity","{}"),response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_UNAVAILABLE","\"retryable\":true").doesNotContain("private database details");
    }
}
