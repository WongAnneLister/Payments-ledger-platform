package com.paymentsledger.common.error;

import com.paymentsledger.common.request.RequestContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误渲染。保证**每个**错误响应都带 {@code code}、{@code retryable}、{@code requestId}。
 *
 * <p>兜底分支刻意返回 {@link ErrorCode#INTERNAL_ERROR} 且不回传异常文案——
 * 避免把内部细节（可能含敏感值）泄露给调用方；细节只进服务端日志，按 requestId 关联。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("api_error code={} status={} retryable={}", code, code.httpStatus().value(), code.retryable());
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, ex.getMessage(), RequestContext.currentRequestId(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.Detail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Request validation failed", RequestContext.currentRequestId(), details));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableJson() {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Invalid JSON request", RequestContext.currentRequestId()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        boolean idempotency = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName());
        ErrorCode code = idempotency ? ErrorCode.IDEMPOTENCY_KEY_REQUIRED : ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Missing required header: " + ex.getHeaderName(),
                        RequestContext.currentRequestId()));
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound() {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Resource not found in caller scope", RequestContext.currentRequestId()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter() {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Invalid request parameter", RequestContext.currentRequestId()));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ApiError> handlePersistenceUnavailable() {
        ErrorCode code = ErrorCode.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Persistence service unavailable", RequestContext.currentRequestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String requestId = RequestContext.currentRequestId();
        // 异常细节只进日志，不进响应体。
        log.error("unhandled_exception requestId={} type={}", requestId, ex.getClass().getName(), ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Internal error; see server logs by requestId", requestId));
    }
}
