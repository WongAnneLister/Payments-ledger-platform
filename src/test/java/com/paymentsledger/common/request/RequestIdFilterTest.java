package com.paymentsledger.common.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 与被测类同包：{@code resolve} 保持包级私有，测试不因此放宽生产代码可见性。
 */
class RequestIdFilterTest {

    @Test
    @DisplayName("合法的入站 requestId 被复用")
    void reusesSafeInboundId() {
        assertThat(RequestIdFilter.resolve("req-123_ABC")).isEqualTo("req-123_ABC");
        assertThat(RequestIdFilter.resolve("a")).isEqualTo("a");
        assertThat(RequestIdFilter.resolve("x".repeat(64))).isEqualTo("x".repeat(64));
    }

    @Test
    @DisplayName("不合法的入站值一律改为服务端生成，防止日志注入")
    void regeneratesUnsafeInboundId() {
        // 换行会在日志中伪造出新的一行
        assertThat(RequestIdFilter.resolve("bad\nvalue")).startsWith("req_");
        assertThat(RequestIdFilter.resolve("bad\r\nvalue")).startsWith("req_");
        assertThat(RequestIdFilter.resolve("has space")).startsWith("req_");
        assertThat(RequestIdFilter.resolve("semi;colon")).startsWith("req_");
        // 超长值会撑大日志
        assertThat(RequestIdFilter.resolve("x".repeat(65))).startsWith("req_");
        assertThat(RequestIdFilter.resolve("")).startsWith("req_");
        assertThat(RequestIdFilter.resolve(null)).startsWith("req_");
    }

    @Test
    void regeneratesSecretLikeAndCardIdsWithoutEchoingThem() {
        for (String value : java.util.List.of(
                "sk_test_redactionfixture",
                "pi_fixture_secret_hidden",
                "whsec_fixture",
                "4242".repeat(4),
                "tok_fixture",
                "pm_fixture",
                "pk_test_fixture")) {
            assertThat(RequestIdFilter.resolve(value).equals(value)).isFalse();
        }
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(RequestIdFilter.resolve(uuid)).isEqualTo(uuid);
    }

    @Test
    void embeddedCredentialPrefixesAreRegeneratedWithoutEchoing() {
        for (String key : java.util.List.of("sk_test_", "sk_live_", "pk_test_", "pk_live_", "rk_test_", "rk_live_")) {
            String value = "req" + key + "synthetic";
            assertThat(RequestIdFilter.resolve(value).equals(value)).isFalse();
        }
    }

    @Test
    void generatedIdsUseGuardSafeCanonicalUuidAndInboundCardLikeTypedIdsStillRegenerate() {
        assertThat(RequestIdFilter.resolve(null))
                .matches("req_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        String typed = com.paymentsledger.payment.PaymentIds.format("pay", new java.util.UUID(16, 2701));
        assertThat(RequestIdFilter.resolve(typed).equals(typed)).isFalse();
    }

    @Test
    @DisplayName("生成的 ID 每次不同")
    void generatesUniqueIds() {
        assertThat(RequestIdFilter.resolve(null)).isNotEqualTo(RequestIdFilter.resolve(null));
    }

    @Test
    @DisplayName("不在请求线程中时返回占位符而非抛异常")
    void currentRequestIdFallsBack() {
        assertThat(RequestContext.currentRequestId()).isEqualTo("-");
    }
}
