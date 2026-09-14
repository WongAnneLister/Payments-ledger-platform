package com.paymentsledger.common.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码目录。**与 {@code docs/contracts/openapi.yaml} 的 ErrorCode schema 逐项对应**，
 * 任何增改必须同时修订契约文件。
 *
 * <p>{@code retryable} 是契约的一部分，客户端据此决策，不解析 message 文案。
 *
 * <p><b>注意 {@link #PROVIDER_UNKNOWN_RESULT}：</b>它的 {@code retryable=false}
 * <b>不等于</b>「远端未执行」。它表示结果确定性未知，必须以查询收敛，
 * 禁止换键重发（ADR-0003）。
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, false),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, false),
    SIGNATURE_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, false),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false),

    FORBIDDEN(HttpStatus.FORBIDDEN, false),
    APPROVAL_SELF_NOT_ALLOWED(HttpStatus.FORBIDDEN, false),

    NOT_FOUND(HttpStatus.NOT_FOUND, false),

    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, false),
    ORDER_INTENT_CONFLICT(HttpStatus.CONFLICT, false),
    PAYMENT_NOT_CANCELABLE(HttpStatus.CONFLICT, false),
    REFUND_BLOCKED(HttpStatus.CONFLICT, false),
    EVIDENCE_CONFLICT(HttpStatus.CONFLICT, false),
    IMPORT_DUPLICATE_CONFLICT(HttpStatus.CONFLICT, false),

    AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, false),
    CURRENCY_NOT_ENABLED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    REFUND_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    ADJUSTMENT_EVIDENCE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, false),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),

    PROVIDER_UNKNOWN_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false),

    PERSISTENCE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, true),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true);

    private final HttpStatus httpStatus;
    private final boolean retryable;

    ErrorCode(HttpStatus httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
