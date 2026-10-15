package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.common.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIdsTest {
    @Test
    void preservesAll128BitsAndUsesFrozenCrockfordAlphabet() {
        for (UUID id : new UUID[] {new UUID(0, 0), new UUID(-1, -1), UUID.randomUUID()}) {
            String external = PaymentIds.format("pay", id);
            assertThat(external).matches("pay_[0-7][0-9A-HJKMNP-TV-Z]{25}");
            assertThat(PaymentIds.payment(external)).isEqualTo(id);
        }
    }

    @Test
    void generatedIdContainsCurrentTimestampAndUniqueRandomness() {
        long before = System.currentTimeMillis();
        UUID first = PaymentIds.newId(), second = PaymentIds.newId();
        assertThat(first.getMostSignificantBits() >>> 16).isBetween(before, System.currentTimeMillis());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsWrongPrefixOverflowAlphabetAndRawUuid() {
        for (String value : new String[] {
            "bor_00000000000000000000000000",
            "pay_80000000000000000000000000",
            "pay_0000000000000000000000000I",
            UUID.randomUUID().toString(),
            "pay_short"
        }) assertThatThrownBy(() -> PaymentIds.payment(value)).isInstanceOf(ApiException.class);
    }
}
