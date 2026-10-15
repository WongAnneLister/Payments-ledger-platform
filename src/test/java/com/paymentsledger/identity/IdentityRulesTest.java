package com.paymentsledger.identity;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import com.paymentsledger.common.money.Money;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityRulesTest {
    @Test
    void exactMoneyConversionAndBounds() {
        assertThat(Money.toMinor("100.01", "USD", 2)).isEqualTo(10001);
        assertThat(Money.toMinor("100", "JPY", 0)).isEqualTo(100);
        assertThat(Money.toMinor("92233720368547758.07", "USD", 2)).isEqualTo(Long.MAX_VALUE);
        for (String invalid : new String[] {"-1", "0", "1.001", "92233720368547758.08", "NaN"})
            assertThatThrownBy(() -> Money.toMinor(invalid, "USD", 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.toMinor("1.1", "JPY", 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.toMinor("1", "JPY", 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.toMinor("1", "USD", 5)).isInstanceOf(ApiException.class);
    }

    @Test
    void missingContextFailsClosedAndCollectionsAreImmutable() {
        assertThatThrownBy(() -> TenantAccessGuard.require(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        var tenant = new TenantContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Set.of("USD"),
                false,
                Set.of("payments:read"));
        assertThatThrownBy(() -> tenant.allowedCurrencies().add("JPY"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> TenantAccessGuard.requireScope(tenant, "refunds:write"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TenantAccessGuard.requireCurrency(tenant, "JPY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CURRENCY_NOT_ENABLED);
    }
}
