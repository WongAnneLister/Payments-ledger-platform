package com.paymentsledger.common.request;

/**
 * 当前请求的关联 ID 持有者。
 *
 * <p>值同时写入 SLF4J MDC（键 {@value #MDC_KEY}），使结构化日志、trace 与错误响应中的
 * {@code requestId} 三者一致，便于按 ID 追溯（PRD §17.1）。
 */
public final class RequestContext {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestContext() {}

    static void set(String requestId) {
        CURRENT.set(requestId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** @return 当前请求 ID；不在请求线程中时返回 {@code "-"}，不抛异常。 */
    public static String currentRequestId() {
        String value = CURRENT.get();
        return value == null ? "-" : value;
    }
}
