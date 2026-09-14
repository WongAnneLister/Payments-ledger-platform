package com.paymentsledger.common.error;

import java.util.List;

/**
 * 业务异常。携带稳定 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 渲染为
 * {@link ApiError}。HTTP 状态与 retryable 由错误码决定，调用点不自行指定。
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient List<ApiError.Detail> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message, List<ApiError.Detail> details) {
        this(errorCode, message, details, null);
    }

    public ApiException(ErrorCode errorCode, String message, List<ApiError.Detail> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
