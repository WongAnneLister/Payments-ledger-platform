package com.paymentsledger.payment;

import static org.assertj.core.api.Assertions.*;

import com.paymentsledger.pr20.recovery.RecoveryReleaseGate;
import java.util.*;
import org.junit.jupiter.api.Test;

class Pr20RecoveryGateTest {
    @Test
    void fundsWritesStayBlockedUntilEveryIndependentGateHasPassed() {
        var gate = new RecoveryReleaseGate();
        assertThatThrownBy(gate::requireFundsWrite).isInstanceOf(IllegalStateException.class);
        UUID eligible = UUID.randomUUID();
        gate.authorize(eligible, true, true);
        assertThatThrownBy(() -> gate.requireFundsWrite(eligible)).isInstanceOf(IllegalStateException.class);
        for (String name : RecoveryReleaseGate.REQUIRED) gate.record(name, true);
        gate.record("INDEPENDENT_BALANCE", false);
        assertThatThrownBy(() -> gate.requireFundsWrite(eligible)).isInstanceOf(IllegalStateException.class);
        gate.record("INDEPENDENT_BALANCE", true);
        assertThatCode(() -> gate.requireFundsWrite(eligible)).doesNotThrowAnyException();
        assertThatThrownBy(gate::requireFundsWrite).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate.requireFundsWrite(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate.authorize(UUID.randomUUID(), false, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gate.authorize(UUID.randomUUID(), true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resettingOrReplacingExternalEffectsCannotSatisfyContinuity() {
        assertThat(RecoveryReleaseGate.externalContinuous(List.of("capture-1", "refund-1"), List.of()))
                .isFalse();
        assertThat(RecoveryReleaseGate.externalContinuous(
                        List.of("capture-1", "refund-1"), List.of("capture-1", "refund-new")))
                .isFalse();
        assertThat(RecoveryReleaseGate.externalContinuous(
                        List.of("capture-1", "refund-1"), List.of("capture-1", "refund-1")))
                .isTrue();
    }
}
