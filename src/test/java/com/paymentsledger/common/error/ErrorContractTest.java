package com.paymentsledger.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsledger.common.logging.Masking;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保护 PR00 冻结的错误契约不被静默改动。
 */
class ErrorContractTest {

    @Test
    @DisplayName("每个错误码都有 HTTP 状态；retryable 与契约一致")
    void errorCodeCatalogMatchesContract() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.httpStatus()).as("httpStatus for %s", code).isNotNull();
        }
        // 仅这四个在契约中标记为可重试
        List<ErrorCode> retryable = java.util.Arrays.stream(ErrorCode.values())
                .filter(ErrorCode::retryable)
                .toList();
        assertThat(retryable)
                .containsExactlyInAnyOrder(
                        ErrorCode.RATE_LIMITED, ErrorCode.PERSISTENCE_FAILED, ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("PROVIDER_UNKNOWN_RESULT 不可重试，但这不代表远端未执行")
    void unknownResultIsNotRetryable() {
        assertThat(ErrorCode.PROVIDER_UNKNOWN_RESULT.retryable()).isFalse();
        assertThat(ErrorCode.PROVIDER_UNKNOWN_RESULT.httpStatus().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("错误响应必含 code / retryable / requestId")
    void errorBodyCarriesMandatoryFields() {
        ApiError error = ApiError.of(ErrorCode.REFUND_LIMIT_EXCEEDED, "R + H would exceed C", "req_abc");
        assertThat(error.code()).isEqualTo("REFUND_LIMIT_EXCEEDED");
        assertThat(error.requestId()).isEqualTo("req_abc");
        assertThat(error.retryable()).isFalse();
        assertThat(error.details()).isNull();
    }

    @Test
    @DisplayName("敏感键名一律屏蔽")
    void masksForbiddenKeys() {
        assertThat(Masking.maskByKey("Authorization", "Bearer sk_test_x")).isEqualTo(Masking.redacted());
        assertThat(Masking.maskByKey("Stripe-Signature", "t=1,v1=abc")).isEqualTo(Masking.redacted());
        assertThat(Masking.maskByKey("client_secret", "cs_x")).isEqualTo(Masking.redacted());
        assertThat(Masking.maskByKey("order_key", "order-1")).isEqualTo("order-1");
    }
}
