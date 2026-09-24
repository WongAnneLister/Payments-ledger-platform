package com.paymentsledger.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.*;
import com.paymentsledger.common.request.RequestContext;
import com.paymentsledger.config.RunRole;
import com.paymentsledger.config.RunRoleConfig;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class AuthenticationFilter extends OncePerRequestFilter {
    static final String TENANT=AuthenticationFilter.class.getName()+".tenant";
    static final String OPS=AuthenticationFilter.class.getName()+".ops";
    /** Only the authentication filter establishes this trusted request context. */
    public static TenantContext tenant(HttpServletRequest request) {
        return TenantAccessGuard.require((TenantContext)request.getAttribute(TENANT));
    }
    private final IdentityRepository repository;
    private final CredentialTokens tokens;
    private final ObjectMapper mapper;
    private final RunRoleConfig role;
    public AuthenticationFilter(IdentityRepository repository,CredentialTokens tokens,ObjectMapper mapper,RunRoleConfig role) {
        this.repository=repository; this.tokens=tokens; this.mapper=mapper; this.role=role;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean business=path.equals("/v1") || path.startsWith("/v1/");
        boolean ops=path.equals("/ops") || path.startsWith("/ops/");
        if(!business && !ops) { chain.doFilter(request,response); return; }
        HttpServletRequest checked=request;
        try {
            if(role.runRole()!=RunRole.API) throw TenantAccessGuard.notFound();
            var headers=Collections.list(request.getHeaders("Authorization"));
            if(headers.size()!=1) throw TenantAccessGuard.unauthorized();
            String header=headers.getFirst();
            String scheme=business?"Bearer ":"OpsBearer ";
            if(!header.regionMatches(true,0,scheme,0,scheme.length())) throw TenantAccessGuard.unauthorized();
            var token=tokens.parse(header.substring(scheme.length()),business?"app_":"ops_");
            ScopeAssertions assertions;
            if(business) {
                TenantContext context=repository.authenticateApplication(token);
                request.setAttribute(TENANT,context); assertions=new ScopeAssertions(context);
            } else {
                OpsContext context=repository.authenticateOps(token);
                request.setAttribute(OPS,context); assertions=new ScopeAssertions(context);
            }
            assertions.checkRequest(request);
            String contentType=request.getContentType();
            if(contentType!=null && (contentType.toLowerCase(Locale.ROOT).contains("/json") || contentType.toLowerCase(Locale.ROOT).contains("+json"))) {
                byte[] body=request.getInputStream().readNBytes(65537);
                if(body.length>65536) throw new ApiException(ErrorCode.VALIDATION_ERROR,"JSON body exceeds 64 KiB");
                try(var parser=mapper.getFactory().createParser(body)) {
                    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                    assertions.checkJson(mapper.readTree(parser));
                    if(parser.nextToken()!=null) throw new ApiException(ErrorCode.VALIDATION_ERROR,"A single JSON document is required");
                }
                checked=new ReplayableJsonRequest(request,body);
            }
        } catch(ApiException ex) {
            clear(request); render(response,ex.errorCode(),ex.getMessage()); return;
        } catch(JsonProcessingException ex) {
            clear(request); render(response,ErrorCode.VALIDATION_ERROR,"Invalid JSON request"); return;
        } catch(DataAccessException | org.springframework.transaction.TransactionException ex) {
            clear(request); render(response,ErrorCode.SERVICE_UNAVAILABLE,"Authentication store unavailable"); return;
        }
        response.setHeader("Cache-Control","no-store");
        try { chain.doFilter(checked,response); } finally { clear(request); }
    }
    private static void clear(HttpServletRequest request) { request.removeAttribute(TENANT); request.removeAttribute(OPS); }
    private void render(HttpServletResponse response,ErrorCode code,String message) throws IOException {
        response.setStatus(code.httpStatus().value()); response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");
        mapper.writeValue(response.getOutputStream(),ApiError.of(code,message,RequestContext.currentRequestId()));
    }
}
