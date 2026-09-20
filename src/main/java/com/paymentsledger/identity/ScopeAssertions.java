package com.paymentsledger.identity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Client assertions may be checked, but never used to construct identity. */
final class ScopeAssertions {
    private final Map<String,String> expected;
    ScopeAssertions(TenantContext c) {
        expected=Map.of("organizationid",c.organizationId().toString(),"environmentid",c.environmentId().toString(),
                "applicationid",c.applicationId().toString(),"provideraccountid",c.allowedProviderAccountId().toString(),
                "allowedprovideraccountid",c.allowedProviderAccountId().toString(),"accountid",c.allowedProviderAccountId().toString(),
                "livemode","false");
    }
    ScopeAssertions(OpsContext c) {
        expected=Map.of("organizationid",c.organizationId().toString(),"environmentid",c.environmentId().toString(),"livemode","false");
    }
    void check(String field,String value) {
        String key=field.replace("-","").replace("_","").toLowerCase(Locale.ROOT);
        if(key.startsWith("x")) key=key.substring(1);
        if(java.util.Set.of("organizationid","environmentid","applicationid","provideraccountid","allowedprovideraccountid","accountid","livemode").contains(key)
                && !java.util.Objects.equals(expected.get(key),value)) throw TenantAccessGuard.forbidden();
    }
    void checkRequest(HttpServletRequest request) {
        for(String name:Collections.list(request.getHeaderNames()))
            for(String value:Collections.list(request.getHeaders(name))) check(name,value);
        request.getParameterMap().forEach((name,values) -> { for(String value:values) check(name,value); });
    }
    void checkJson(JsonNode node) {
        if(node==null) return;
        if(node.isObject()) node.fields().forEachRemaining(e -> {
            check(e.getKey(),e.getValue().isValueNode()?e.getValue().asText():null);
            checkJson(e.getValue());
        });
        else if(node.isArray()) node.forEach(this::checkJson);
    }
}
