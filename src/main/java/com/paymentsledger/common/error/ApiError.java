package com.paymentsledger.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 统一错误响应体。字段与 {@code docs/contracts/openapi.yaml} 的 Error schema 一致。
 *
 * <p>{@code code}、{@code message}、{@code retryable}、{@code requestId} <b>必填</b>。
 *
 * <p>{@code message} 面向开发者，**不得**包含密钥、client secret、完整 token、
 * 卡号或客户敏感字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code, String message, boolean retryable, String requestId, List<Detail> details, String docUrl) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String field, String issue) {}

    public static ApiError of(ErrorCode code, String message, String requestId) {
        return new ApiError(code.name(), message, code.retryable(), requestId, null, null);
    }

    public static ApiError of(ErrorCode code, String message, String requestId, List<Detail> details) {
        return new ApiError(
                code.name(),
                message,
                code.retryable(),
                requestId,
                details == null || details.isEmpty() ? null : List.copyOf(details),
                null);
    }
}
