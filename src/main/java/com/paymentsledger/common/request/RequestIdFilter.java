package com.paymentsledger.common.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立关联 ID，并回写 {@code X-Request-Id} 响应头。
 *
 * <p>入站头会被**校验**后才复用：只接受有限长度的安全字符集，防止把任意内容
 * （含换行）注入日志造成日志伪造。不合法则改为服务端生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 仅允许字母、数字、连字符、下划线；长度上限防止日志膨胀。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(RequestContext.HEADER));
        RequestContext.set(requestId);
        MDC.put(RequestContext.MDC_KEY, requestId);
        response.setHeader(RequestContext.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.MDC_KEY);
            RequestContext.clear();
        }
    }

    static String resolve(String inbound) {
        if (inbound != null && SAFE_REQUEST_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
